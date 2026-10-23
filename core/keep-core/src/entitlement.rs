//! Keep+ entitlement: verifying a signed lease and turning it into what the app may do.
//!
//! Everything that decides whether Keep+ is on lives here, once, for Android, iOS and the web:
//!
//! 1. [`verify_lease`] checks a compact JWS (`EdDSA` over Ed25519, RFC 7515/8037) against a short list
//!    of trusted public keys and returns the claims only if the signature is good. The payload is
//!    parsed *after* the signature check, never before.
//! 2. [`evaluate`] combines a verified [`Lease`] with the device clock and the latest clock the app
//!    has seen, and answers with an [`Access`]: the state to show, whether Keep+ features are on,
//!    and whether the app must ask the backend again.
//! 3. [`can_use`] is the single feature table.
//!
//! The core holds no network code and no secrets: it sees a token and public keys.

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ed25519_dalek::{Signature, VerifyingKey};
use serde::Deserialize;

/// Longest token the verifier looks at. Real leases are about 450 bytes.
pub const MAX_LEASE_BYTES: usize = 4096;

/// How far the device clock may disagree with a lease or its own history before it is not trusted.
pub const CLOCK_TOLERANCE_SECS: i64 = 5 * 60;

/// A lease fetched this recently counts as freshly verified by the backend.
pub const ACTIVE_WINDOW_SECS: i64 = 24 * 60 * 60;

const SUPPORTED_VERSION: u32 = 1;
const ISSUER: &str = "vazie";
const PRODUCT: &str = "keep";

/// A public key the app trusts for leases, with the id the backend puts in the token header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TrustedKey {
    pub kid: String,
    pub public_key: [u8; 32],
}

impl TrustedKey {
    /// From the base64url form used in the keys file.
    pub fn from_base64url(kid: &str, public_key: &str) -> Result<Self, LeaseError> {
        let raw = URL_SAFE_NO_PAD
            .decode(public_key)
            .map_err(|_| LeaseError::Malformed)?;
        let public_key: [u8; 32] = raw.try_into().map_err(|_| LeaseError::Malformed)?;
        Ok(Self {
            kid: kid.to_owned(),
            public_key,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tier {
    Free,
    KeepPlus,
}

/// The claims of a lease whose signature has been checked.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Lease {
    pub subject: String,
    pub tier: Tier,
    pub lifetime: bool,
    pub issued_at: i64,
    pub lease_expires_at: i64,
    pub entitlement_expires_at: Option<i64>,
    pub features_version: u32,
    pub revision: u64,
    pub nonce: String,
    pub key_id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LeaseError {
    /// Not three base64url parts, not JSON, or a field out of range.
    Malformed,
    /// A header algorithm other than `EdDSA`.
    UnsupportedAlgorithm,
    /// The header names a key the app does not trust.
    UnknownKey,
    BadSignature,
    /// Another issuer or product, or a newer lease format than this build understands.
    Unsupported,
    /// Correctly signed but internally inconsistent.
    Inconsistent,
}

#[derive(Deserialize)]
struct Header {
    alg: String,
    kid: String,
}

#[derive(Deserialize)]
struct Claims {
    version: u32,
    issuer: String,
    subject: String,
    product: String,
    tier: String,
    lifetime: bool,
    issued_at: i64,
    lease_expires_at: i64,
    entitlement_expires_at: Option<i64>,
    features_version: u32,
    revision: u64,
    nonce: String,
}

/// Verifies `token` against `keys` and returns its claims.
///
/// The signature is checked over the exact `header.payload` text before anything in the payload is
/// read. An `alg` other than `EdDSA` (including `none`) is refused, and a `kid` that is not in `keys`
/// is refused, so a token cannot choose how it is verified.
pub fn verify_lease(token: &str, keys: &[TrustedKey]) -> Result<Lease, LeaseError> {
    if token.is_empty() || token.len() > MAX_LEASE_BYTES {
        return Err(LeaseError::Malformed);
    }
    let mut parts = token.split('.');
    let (Some(header_b64), Some(payload_b64), Some(signature_b64), None) =
        (parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return Err(LeaseError::Malformed);
    };

    let header: Header = decode_json(header_b64)?;
    if header.alg != "EdDSA" {
        return Err(LeaseError::UnsupportedAlgorithm);
    }
    let key = keys
        .iter()
        .find(|k| k.kid == header.kid)
        .ok_or(LeaseError::UnknownKey)?;

    let signature_bytes = URL_SAFE_NO_PAD
        .decode(signature_b64)
        .map_err(|_| LeaseError::Malformed)?;
    let signature = Signature::from_slice(&signature_bytes).map_err(|_| LeaseError::Malformed)?;
    let verifying = VerifyingKey::from_bytes(&key.public_key).map_err(|_| LeaseError::Malformed)?;
    let signed = &token[..header_b64.len() + 1 + payload_b64.len()];
    verifying
        .verify_strict(signed.as_bytes(), &signature)
        .map_err(|_| LeaseError::BadSignature)?;

    let claims: Claims = decode_json(payload_b64)?;
    check(claims, header.kid)
}

fn decode_json<T: for<'de> Deserialize<'de>>(part: &str) -> Result<T, LeaseError> {
    let raw = URL_SAFE_NO_PAD
        .decode(part)
        .map_err(|_| LeaseError::Malformed)?;
    serde_json::from_slice(&raw).map_err(|_| LeaseError::Malformed)
}

fn check(claims: Claims, key_id: String) -> Result<Lease, LeaseError> {
    if claims.version != SUPPORTED_VERSION || claims.issuer != ISSUER || claims.product != PRODUCT {
        return Err(LeaseError::Unsupported);
    }
    let tier = match claims.tier.as_str() {
        "free" => Tier::Free,
        "keep_plus" => Tier::KeepPlus,
        _ => return Err(LeaseError::Unsupported),
    };
    if claims.lease_expires_at <= claims.issued_at
        || claims.nonce.is_empty()
        || claims.subject.is_empty()
    {
        return Err(LeaseError::Inconsistent);
    }
    let consistent = match (tier, claims.lifetime, claims.entitlement_expires_at) {
        (Tier::Free, false, None) | (Tier::KeepPlus, true, None) => true,
        (Tier::KeepPlus, false, Some(end)) => {
            end >= claims.issued_at && claims.lease_expires_at <= end
        }
        _ => false,
    };
    if !consistent {
        return Err(LeaseError::Inconsistent);
    }
    Ok(Lease {
        subject: claims.subject,
        tier,
        lifetime: claims.lifetime,
        issued_at: claims.issued_at,
        lease_expires_at: claims.lease_expires_at,
        entitlement_expires_at: claims.entitlement_expires_at,
        features_version: claims.features_version,
        revision: claims.revision,
        nonce: claims.nonce,
        key_id,
    })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccessState {
    Free,
    /// Keep+, and the backend confirmed it within [`ACTIVE_WINDOW_SECS`].
    PlusActive,
    /// Keep+ on the strength of a signed lease that is still valid but not recent.
    PlusOfflineGrace,
    PlusLifetime,
    /// A Keep+ lease that has run out, or that cannot be trusted with this clock.
    PlusExpired,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Access {
    pub state: AccessState,
    /// Whether Keep+ features are on. Free features are never gated by this.
    pub plus_features: bool,
    /// The app should ask the backend for a new lease when it can.
    pub needs_online_verification: bool,
    /// When the current lease stops being enough, if there is one.
    pub lease_valid_until: Option<i64>,
    /// The latest clock reading to remember: never goes backwards.
    pub observed_device_time: i64,
}

/// What a lease allows at device time `now`, given the latest device time the app has seen.
///
/// `last_observed` is persisted by the app after every evaluation and every successful refresh. It is
/// what makes "set the date back 30 days" useless: a clock behind the last reading, or behind the
/// time the lease was issued, switches Keep+ off until the backend is reached, while Free carries on.
#[must_use]
pub fn evaluate(lease: Option<&Lease>, now: i64, last_observed: i64) -> Access {
    let observed = now.max(last_observed);
    let free = |needs_online: bool| Access {
        state: AccessState::Free,
        plus_features: false,
        needs_online_verification: needs_online,
        lease_valid_until: None,
        observed_device_time: observed,
    };
    let Some(lease) = lease else {
        return free(false);
    };
    if lease.tier == Tier::Free {
        return Access {
            lease_valid_until: Some(lease.lease_expires_at),
            ..free(false)
        };
    }

    let behind_history = now + CLOCK_TOLERANCE_SECS < last_observed;
    let behind_lease = now + CLOCK_TOLERANCE_SECS < lease.issued_at;
    let valid_until = Some(lease.lease_expires_at);
    let expired = |observed_device_time| Access {
        state: AccessState::PlusExpired,
        plus_features: false,
        needs_online_verification: true,
        lease_valid_until: valid_until,
        observed_device_time,
    };
    if behind_history || behind_lease || now >= lease.lease_expires_at {
        return expired(observed);
    }
    let state = if lease.lifetime {
        AccessState::PlusLifetime
    } else if now - lease.issued_at <= ACTIVE_WINDOW_SECS {
        AccessState::PlusActive
    } else {
        AccessState::PlusOfflineGrace
    };
    Access {
        state,
        plus_features: true,
        needs_online_verification: false,
        lease_valid_until: valid_until,
        observed_device_time: observed,
    }
}

/// Features that need Keep+. Everything that protects a person's passwords is deliberately absent:
/// `AutoFill`, passkeys, biometrics, key files, safe save, merge and the generators are always free.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlusFeature {
    MultiVault,
    Totp,
    PasswordHealth,
    BreachMonitoring,
    HistoryUi,
    AttachmentWrite,
    PremiumThemes,
}

/// Whether `feature` is available with `access`.
#[must_use]
pub fn can_use(_feature: PlusFeature, access: &Access) -> bool {
    access.plus_features
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lease(tier: Tier, lifetime: bool, issued: i64, expires: i64) -> Lease {
        Lease {
            subject: "s".into(),
            tier,
            lifetime,
            issued_at: issued,
            lease_expires_at: expires,
            entitlement_expires_at: None,
            features_version: 1,
            revision: 1,
            nonce: "n".into(),
            key_id: "k".into(),
        }
    }

    const DAY: i64 = 86_400;

    #[test]
    fn no_lease_is_free_and_not_a_problem() {
        let access = evaluate(None, 1_000, 0);
        assert_eq!(access.state, AccessState::Free);
        assert!(!access.plus_features);
        assert!(!access.needs_online_verification);
    }

    #[test]
    fn a_fresh_lease_is_active_and_an_old_one_is_offline_grace() {
        let l = lease(Tier::KeepPlus, false, 1_000, 1_000 + 30 * DAY);
        assert_eq!(
            evaluate(Some(&l), 1_000 + 60, 1_000).state,
            AccessState::PlusActive
        );
        let grace = evaluate(Some(&l), 1_000 + 3 * DAY, 1_000);
        assert_eq!(grace.state, AccessState::PlusOfflineGrace);
        assert!(grace.plus_features);
    }

    #[test]
    fn the_lease_boundary_is_exact() {
        let l = lease(Tier::KeepPlus, false, 1_000, 1_000 + 30 * DAY);
        let last = 1_000 + 30 * DAY - 1;
        assert!(evaluate(Some(&l), last, last).plus_features);
        let over = evaluate(Some(&l), last + 1, last);
        assert_eq!(over.state, AccessState::PlusExpired);
        assert!(!over.plus_features);
        assert!(over.needs_online_verification);
    }

    #[test]
    fn a_lifetime_lease_is_lifetime_until_it_runs_out() {
        let l = lease(Tier::KeepPlus, true, 1_000, 1_000 + 30 * DAY);
        assert_eq!(
            evaluate(Some(&l), 1_000 + 10 * DAY, 1_000).state,
            AccessState::PlusLifetime
        );
        assert_eq!(
            evaluate(Some(&l), 1_000 + 31 * DAY, 1_000).state,
            AccessState::PlusExpired
        );
    }

    #[test]
    fn a_clock_set_back_switches_plus_off_and_free_stays() {
        let l = lease(Tier::KeepPlus, false, 1_000, 1_000 + 30 * DAY);
        let rolled = evaluate(Some(&l), 1_000 + 20 * DAY - 31 * DAY, 1_000 + 20 * DAY);
        assert!(!rolled.plus_features);
        assert!(rolled.needs_online_verification);
        assert_eq!(
            rolled.observed_device_time,
            1_000 + 20 * DAY,
            "the remembered time never goes back"
        );
        let free = lease(Tier::Free, false, 1_000, 1_000 + 30 * DAY);
        assert_eq!(
            evaluate(Some(&free), 5, 1_000 + 20 * DAY).state,
            AccessState::Free
        );
    }

    #[test]
    fn small_clock_drift_is_tolerated() {
        let l = lease(Tier::KeepPlus, false, 10_000, 10_000 + 30 * DAY);
        assert!(
            evaluate(Some(&l), 10_000 - 60, 10_000).plus_features,
            "a minute behind the server is drift"
        );
        assert!(
            !evaluate(Some(&l), 10_000 - 3_600, 10_000).plus_features,
            "an hour behind is not"
        );
        assert!(evaluate(Some(&l), 20_000, 20_000 + 120).plus_features);
    }

    #[test]
    fn every_plus_feature_follows_the_access() {
        let on = evaluate(Some(&lease(Tier::KeepPlus, false, 1, 1 + DAY)), 2, 2);
        let off = evaluate(None, 2, 2);
        for f in [
            PlusFeature::MultiVault,
            PlusFeature::Totp,
            PlusFeature::PasswordHealth,
            PlusFeature::BreachMonitoring,
            PlusFeature::HistoryUi,
            PlusFeature::AttachmentWrite,
            PlusFeature::PremiumThemes,
        ] {
            assert!(can_use(f, &on));
            assert!(!can_use(f, &off));
        }
    }
}
