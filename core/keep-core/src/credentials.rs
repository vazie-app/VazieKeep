use std::fmt;

use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::SecretBytes;
use crate::error::OpenError;
use crate::key_file;

pub struct VaultCredentials {
    password: Option<Zeroizing<[u8; 32]>>,
    key_file: Option<Zeroizing<Vec<u8>>>,
}

impl VaultCredentials {
    #[allow(
        clippy::needless_pass_by_value,
        reason = "taking ownership drops and zeroizes the caller's password copy"
    )]
    pub fn from_password(password: SecretBytes) -> Self {
        Self {
            password: Some(sha256(password.expose())),
            key_file: None,
        }
    }

    #[allow(
        clippy::needless_pass_by_value,
        reason = "taking ownership drops and zeroizes the caller's copies"
    )]
    pub fn new(
        password: Option<SecretBytes>,
        key_file: Option<SecretBytes>,
    ) -> Result<Self, OpenError> {
        Self::build(password.map(|p| sha256(p.expose())), key_file)
    }

    pub fn from_unlock_material(
        material: &UnlockMaterial,
        key_file: Option<SecretBytes>,
    ) -> Result<Self, OpenError> {
        Self::build(Some(material.0.clone()), key_file)
    }

    #[allow(
        clippy::needless_pass_by_value,
        reason = "taking ownership drops and zeroizes the key file bytes"
    )]
    fn build(
        password: Option<Zeroizing<[u8; 32]>>,
        key_file: Option<SecretBytes>,
    ) -> Result<Self, OpenError> {
        let key_file = key_file
            .map(|data| key_file::component(data.expose()).map(|(_, key)| key))
            .transpose()?;
        if password.is_none() && key_file.is_none() {
            return Err(OpenError::MissingCredentials);
        }
        Ok(Self { password, key_file })
    }

    pub fn has_password(&self) -> bool {
        self.password.is_some()
    }

    pub fn has_key_file(&self) -> bool {
        self.key_file.is_some()
    }

    pub fn unlock_material(&self) -> Option<UnlockMaterial> {
        self.password.clone().map(UnlockMaterial)
    }

    pub(crate) fn composite_key(&self) -> Result<Zeroizing<[u8; 32]>, OpenError> {
        if self.password.is_none() && self.key_file.is_none() {
            return Err(OpenError::MissingCredentials);
        }
        let mut hasher = Sha256::new();
        if let Some(password) = &self.password {
            hasher.update(password.as_slice());
        }
        if let Some(key_file) = &self.key_file {
            hasher.update(key_file.as_slice());
        }
        Ok(to_array(&hasher.finalize()))
    }
}

impl fmt::Debug for VaultCredentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultCredentials")
            .field("password", &self.password.as_ref().map(|_| "[REDACTED]"))
            .field("key_file", &self.key_file.as_ref().map(|_| "[REDACTED]"))
            .finish()
    }
}

/// The `KeePass` password component, `SHA-256(password)`. It opens every vault protected by the
/// same master password (plus that vault's key file, if any), so it is as secret as the password.
pub struct UnlockMaterial(Zeroizing<[u8; 32]>);

impl UnlockMaterial {
    const VERSION: u8 = 1;

    pub fn encode(&self) -> SecretBytes {
        let mut out = Vec::with_capacity(33);
        out.push(Self::VERSION);
        out.extend_from_slice(self.0.as_slice());
        SecretBytes::from(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, OpenError> {
        match bytes {
            [Self::VERSION, rest @ ..] if rest.len() == 32 => Ok(Self(to_array(rest))),
            _ => Err(OpenError::InvalidUnlockMaterial),
        }
    }
}

impl fmt::Debug for UnlockMaterial {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("UnlockMaterial([REDACTED])")
    }
}

pub(crate) fn sha256(data: &[u8]) -> Zeroizing<[u8; 32]> {
    to_array(&Sha256::digest(data))
}

fn to_array(digest: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(digest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn secret(bytes: &[u8]) -> SecretBytes {
        SecretBytes::from(bytes.to_vec())
    }

    #[test]
    fn debug_redacts_every_component() {
        let credentials =
            VaultCredentials::new(Some(secret(b"hunter2")), Some(secret(b"key file"))).unwrap();
        let debug = format!("{credentials:?}{:?}", credentials.unlock_material());
        assert!(debug.contains("[REDACTED]"));
        assert!(!debug.contains("hunter2"));
    }

    #[test]
    fn composite_key_is_sha256_of_password_hash() {
        let credentials = VaultCredentials::from_password(secret(b"password"));
        let expected = Sha256::digest(Sha256::digest(b"password"));
        assert_eq!(
            credentials.composite_key().unwrap().as_slice(),
            expected.as_slice()
        );
    }

    #[test]
    fn composite_key_puts_the_password_before_the_key_file() {
        let key = [9u8; 32];
        let both = VaultCredentials::new(Some(secret(b"pw")), Some(secret(&key))).unwrap();
        let mut expected = Sha256::new();
        expected.update(Sha256::digest(b"pw"));
        expected.update(key);
        assert_eq!(
            both.composite_key().unwrap().as_slice(),
            expected.finalize().as_slice()
        );

        let key_only = VaultCredentials::new(None, Some(secret(&key))).unwrap();
        assert_eq!(
            key_only.composite_key().unwrap().as_slice(),
            Sha256::digest(key).as_slice()
        );
        assert!(!key_only.has_password() && key_only.has_key_file());
        assert!(key_only.unlock_material().is_none());
    }

    #[test]
    fn an_empty_password_is_still_a_component() {
        let empty = VaultCredentials::new(Some(secret(b"")), None).unwrap();
        assert_eq!(
            empty.composite_key().unwrap().as_slice(),
            Sha256::digest(Sha256::digest(b"")).as_slice()
        );
    }

    #[test]
    fn no_components_is_a_typed_error() {
        assert_eq!(
            VaultCredentials::new(None, None).unwrap_err(),
            OpenError::MissingCredentials
        );
    }

    #[test]
    fn unlock_material_round_trips_to_the_same_composite_key() {
        let key = [3u8; 32];
        let manual = VaultCredentials::new(Some(secret(b"pw")), Some(secret(&key))).unwrap();
        let encoded = manual.unlock_material().unwrap().encode();
        assert_eq!(encoded.expose().len(), 33);
        let material = UnlockMaterial::decode(encoded.expose()).unwrap();
        let remembered =
            VaultCredentials::from_unlock_material(&material, Some(secret(&key))).unwrap();
        assert_eq!(
            remembered.composite_key().unwrap().as_slice(),
            manual.composite_key().unwrap().as_slice()
        );
        let without_key_file = VaultCredentials::from_unlock_material(&material, None).unwrap();
        assert_ne!(
            without_key_file.composite_key().unwrap().as_slice(),
            manual.composite_key().unwrap().as_slice()
        );
    }

    #[test]
    fn malformed_unlock_material_is_rejected() {
        for bad in [&[][..], &[1u8; 32], &[2u8; 33], &[1u8; 34]] {
            assert_eq!(
                UnlockMaterial::decode(bad).unwrap_err(),
                OpenError::InvalidUnlockMaterial
            );
        }
    }
}
