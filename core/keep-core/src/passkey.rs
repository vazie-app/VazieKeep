//! Passkeys (`WebAuthn` credentials) kept in a vault entry, the way `KeePassXC` keeps them: an ES256
//! private key in PEM and its identifiers in `KPEX_PASSKEY_*` fields, so a passkey made here signs
//! in from `KeePassXC` and the other way round. This crate is the authenticator: it makes the key,
//! builds the authenticator data and signs. The origin check and the client data belong to the
//! platform, which knows who is asking.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use p256::pkcs8::{DecodePrivateKey, EncodePrivateKey, EncodePublicKey, LineEnding};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::secret::SecretString;

pub const FIELD_USERNAME: &str = "KPEX_PASSKEY_USERNAME";
pub const FIELD_CREDENTIAL_ID: &str = "KPEX_PASSKEY_CREDENTIAL_ID";
/// What `KeePassXC` called the credential id before 2.7.7; still read.
pub const FIELD_LEGACY_CREDENTIAL_ID: &str = "KPEX_PASSKEY_GENERATED_USER_ID";
pub const FIELD_PRIVATE_KEY: &str = "KPEX_PASSKEY_PRIVATE_KEY_PEM";
pub const FIELD_RELYING_PARTY: &str = "KPEX_PASSKEY_RELYING_PARTY";
pub const FIELD_USER_HANDLE: &str = "KPEX_PASSKEY_USER_HANDLE";
pub const FIELD_BACKUP_ELIGIBLE: &str = "KPEX_PASSKEY_FLAG_BE";
pub const FIELD_BACKUP_STATE: &str = "KPEX_PASSKEY_FLAG_BS";
pub const FIELD_PREFIX: &str = "KPEX_PASSKEY_";
/// The tag `KeePassXC` gives an entry with a passkey.
pub const TAG: &str = "Passkey";

/// Identifies Vazie Keep as the authenticator model. Fixed for ever: sites may remember it.
pub const AAGUID: [u8; 16] = [
    0x7b, 0x3a, 0x5e, 0x1c, 0x94, 0x2d, 0x4f, 0x68, 0xa1, 0x0e, 0x56, 0x61, 0x7a, 0x69, 0x65, 0x4b,
];

const CREDENTIAL_ID_BYTES: usize = 32;
const FLAG_USER_PRESENT: u8 = 0x01;
const FLAG_USER_VERIFIED: u8 = 0x04;
const FLAG_BACKUP_ELIGIBLE: u8 = 0x08;
const FLAG_BACKUP_STATE: u8 = 0x10;
const FLAG_ATTESTED_CREDENTIAL: u8 = 0x40;
/// COSE: ES256.
pub const ALGORITHM_ES256: i32 = -7;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum PasskeyError {
    RandomUnavailable,
    /// The relying party id or the user handle is empty or too long.
    InvalidRequest,
    /// The entry has no passkey.
    NoPasskey,
    /// The stored key is not an ES256 private key this crate reads.
    InvalidKey,
}

impl std::fmt::Display for PasskeyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::RandomUnavailable => "system random source unavailable",
            Self::InvalidRequest => "passkey request is not valid",
            Self::NoPasskey => "the entry has no passkey",
            Self::InvalidKey => "the stored passkey is not readable",
        })
    }
}

impl std::error::Error for PasskeyError {}

/// A passkey that was just made: what goes into the entry and what goes back to the site.
pub struct NewPasskey {
    pub credential_id: Vec<u8>,
    pub private_key_pem: SecretString,
    /// The public key as `SubjectPublicKeyInfo` DER, for the response's `publicKey`.
    pub public_key: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    /// CBOR, attestation format `none`.
    pub attestation_object: Vec<u8>,
}

impl std::fmt::Debug for NewPasskey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NewPasskey").finish_non_exhaustive()
    }
}

/// The answer to a sign-in request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Assertion {
    pub authenticator_data: Vec<u8>,
    /// ECDSA over `authenticator_data || client_data_hash`, DER.
    pub signature: Vec<u8>,
}

fn valid_rp_id(rp_id: &str) -> bool {
    !rp_id.is_empty() && rp_id.len() <= 253 && !rp_id.chars().any(char::is_whitespace)
}

/// Makes a new ES256 key for `rp_id`. The user is taken as present and verified: the vault was
/// unlocked for this.
pub fn generate(rp_id: &str) -> Result<NewPasskey, PasskeyError> {
    if !valid_rp_id(rp_id) {
        return Err(PasskeyError::InvalidRequest);
    }
    let mut credential_id = vec![0u8; CREDENTIAL_ID_BYTES];
    getrandom::fill(&mut credential_id).map_err(|_| PasskeyError::RandomUnavailable)?;
    let key = loop {
        let mut scalar = Zeroizing::new([0u8; 32]);
        getrandom::fill(scalar.as_mut_slice()).map_err(|_| PasskeyError::RandomUnavailable)?;
        // A scalar outside the curve's order is refused; that happens about once in 2^32 tries.
        if let Ok(key) = SigningKey::from_slice(scalar.as_slice()) {
            break key;
        }
    };
    let pem = key
        .to_pkcs8_pem(LineEnding::LF)
        .map_err(|_| PasskeyError::InvalidKey)?;
    let verifying = key.verifying_key();
    let public_key = verifying
        .to_public_key_der()
        .map_err(|_| PasskeyError::InvalidKey)?
        .into_vec();
    let point = verifying.to_sec1_point(false);
    let (Some(x), Some(y)) = (point.x(), point.y()) else {
        return Err(PasskeyError::InvalidKey);
    };

    let mut data = authenticator_data(rp_id, FLAG_ATTESTED_CREDENTIAL, true, true);
    data.extend_from_slice(&AAGUID);
    data.extend_from_slice(&[0, u8::try_from(CREDENTIAL_ID_BYTES).unwrap_or(u8::MAX)]);
    data.extend_from_slice(&credential_id);
    // COSE_Key: {1: 2 (EC2), 3: -7 (ES256), -1: 1 (P-256), -2: x, -3: y}
    data.extend_from_slice(&[0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20]);
    data.extend_from_slice(x);
    data.extend_from_slice(&[0x22, 0x58, 0x20]);
    data.extend_from_slice(y);

    Ok(NewPasskey {
        credential_id,
        private_key_pem: SecretString::new(pem.as_str().to_owned()),
        public_key,
        attestation_object: attestation_object(&data),
        authenticator_data: data,
    })
}

/// Signs a sign-in request with a stored key. `client_data_hash` is the SHA-256 of the client
/// data the platform (or the browser) made for this request.
pub fn assert(
    private_key_pem: &str,
    rp_id: &str,
    client_data_hash: &[u8],
    backup_eligible: bool,
    backup_state: bool,
) -> Result<Assertion, PasskeyError> {
    if !valid_rp_id(rp_id) || client_data_hash.len() != 32 {
        return Err(PasskeyError::InvalidRequest);
    }
    let key = signing_key(private_key_pem)?;
    let authenticator_data = authenticator_data(rp_id, 0, backup_eligible, backup_state);
    let mut signed = authenticator_data.clone();
    signed.extend_from_slice(client_data_hash);
    let signature: Signature = key.sign(&signed);
    Ok(Assertion {
        authenticator_data,
        signature: signature.to_der().as_bytes().to_vec(),
    })
}

fn signing_key(pem: &str) -> Result<SigningKey, PasskeyError> {
    if let Ok(key) = SigningKey::from_pkcs8_pem(pem.trim()) {
        return Ok(key);
    }
    // An `EC PRIVATE KEY` block, as some tools export it.
    p256::SecretKey::from_sec1_pem(pem.trim())
        .map(|secret| SigningKey::from(&secret))
        .map_err(|_| PasskeyError::InvalidKey)
}

/// `rpIdHash || flags || signCount`. The counter stays at zero: a key that lives in a file several
/// devices open cannot count, and zero tells the site not to expect it to.
fn authenticator_data(
    rp_id: &str,
    extra: u8,
    backup_eligible: bool,
    backup_state: bool,
) -> Vec<u8> {
    let mut flags = FLAG_USER_PRESENT | FLAG_USER_VERIFIED | extra;
    if backup_eligible {
        flags |= FLAG_BACKUP_ELIGIBLE;
        if backup_state {
            flags |= FLAG_BACKUP_STATE;
        }
    }
    let mut data = Sha256::digest(rp_id.as_bytes()).to_vec();
    data.push(flags);
    data.extend_from_slice(&[0, 0, 0, 0]);
    data
}

/// `{"fmt": "none", "attStmt": {}, "authData": <bytes>}` in CBOR.
fn attestation_object(authenticator_data: &[u8]) -> Vec<u8> {
    let mut out = vec![0xa3];
    out.extend_from_slice(&[0x63, b'f', b'm', b't', 0x64, b'n', b'o', b'n', b'e']);
    out.extend_from_slice(&[0x67, b'a', b't', b't', b'S', b't', b'm', b't', 0xa0]);
    out.extend_from_slice(&[0x68, b'a', b'u', b't', b'h', b'D', b'a', b't', b'a']);
    let length = u16::try_from(authenticator_data.len()).unwrap_or(u16::MAX);
    if length < 24 {
        out.push(0x40 | u8::try_from(length).unwrap_or(0));
    } else if length < 256 {
        out.extend_from_slice(&[0x58, u8::try_from(length).unwrap_or(0)]);
    } else {
        out.push(0x59);
        out.extend_from_slice(&length.to_be_bytes());
    }
    out.extend_from_slice(authenticator_data);
    out
}

pub fn encode_id(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Reads an identifier as the entry keeps it: base64url, with or without padding.
pub fn decode_id(text: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(text.trim().trim_end_matches('='))
        .ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::VerifyingKey;
    use p256::ecdsa::signature::Verifier;
    use p256::pkcs8::DecodePublicKey;

    #[test]
    fn a_new_passkey_signs_what_its_public_key_verifies() {
        let made = generate("example.com").unwrap();
        assert_eq!(made.credential_id.len(), 32);
        assert!(
            made.private_key_pem
                .expose()
                .starts_with("-----BEGIN PRIVATE KEY-----")
        );

        let data = &made.authenticator_data;
        assert_eq!(&data[..32], Sha256::digest(b"example.com").as_slice());
        assert_eq!(
            data[32],
            0x01 | 0x04 | 0x08 | 0x10 | 0x40,
            "UP, UV, BE, BS, AT"
        );
        assert_eq!(&data[33..37], &[0, 0, 0, 0]);
        assert_eq!(&data[37..53], &AAGUID);
        assert_eq!(&data[53..55], &[0, 32]);
        assert_eq!(&data[55..87], made.credential_id.as_slice());
        assert_eq!(data.len(), 87 + 77, "the COSE key is 77 bytes");
        assert!(made.attestation_object.ends_with(data));
        assert_eq!(made.attestation_object[0], 0xa3);

        let hash = Sha256::digest(b"client data");
        let answer = assert(
            made.private_key_pem.expose(),
            "example.com",
            &hash,
            true,
            true,
        )
        .unwrap();
        assert_eq!(answer.authenticator_data.len(), 37);
        assert_eq!(answer.authenticator_data[32], 0x01 | 0x04 | 0x08 | 0x10);
        let key = VerifyingKey::from_public_key_der(&made.public_key).unwrap();
        let mut signed = answer.authenticator_data.clone();
        signed.extend_from_slice(&hash);
        let signature = Signature::from_der(&answer.signature).unwrap();
        key.verify(&signed, &signature).unwrap();
    }

    #[test]
    fn two_passkeys_never_share_a_key_or_an_id() {
        let (a, b) = (
            generate("example.com").unwrap(),
            generate("example.com").unwrap(),
        );
        assert_ne!(a.credential_id, b.credential_id);
        assert_ne!(a.public_key, b.public_key);
    }

    #[test]
    fn a_bad_request_or_a_bad_key_is_refused() {
        assert_eq!(generate("").unwrap_err(), PasskeyError::InvalidRequest);
        assert_eq!(
            generate("exa mple.com").unwrap_err(),
            PasskeyError::InvalidRequest
        );
        let made = generate("example.com").unwrap();
        let short = [0u8; 16];
        assert_eq!(
            assert(
                made.private_key_pem.expose(),
                "example.com",
                &short,
                true,
                true
            )
            .unwrap_err(),
            PasskeyError::InvalidRequest
        );
        assert_eq!(
            assert(
                "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----",
                "example.com",
                &[0u8; 32],
                true,
                true
            )
            .unwrap_err(),
            PasskeyError::InvalidKey
        );
    }

    #[test]
    fn a_pkcs8_key_made_elsewhere_signs() {
        // A throwaway P-256 key in the PKCS#8 PEM that KeePassXC also writes, made with openssl for this test.
        let pem = include_str!("../tests/data/passkey-p256.pem");
        let public = include_bytes!("../tests/data/passkey-p256.pub.der");
        let hash = Sha256::digest(b"x");
        let answer = assert(pem, "example.org", &hash, false, false).unwrap();
        assert_eq!(answer.authenticator_data[32], 0x01 | 0x04);
        let key = VerifyingKey::from_public_key_der(public).unwrap();
        let mut signed = answer.authenticator_data.clone();
        signed.extend_from_slice(&hash);
        key.verify(&signed, &Signature::from_der(&answer.signature).unwrap())
            .unwrap();
    }

    #[test]
    fn identifiers_round_trip_with_and_without_padding() {
        let id = [0xfb, 0xff, 0x00, 0x01];
        assert_eq!(encode_id(&id), "-_8AAQ");
        assert_eq!(decode_id("-_8AAQ").unwrap(), id);
        assert_eq!(decode_id("-_8AAQ==").unwrap(), id);
        assert_eq!(decode_id("not base64!"), None);
    }
}
