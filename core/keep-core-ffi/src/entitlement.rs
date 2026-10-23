use keep_core::{
    Access, AccessState, Lease, LeaseError, PlusFeature, Tier, TrustedKey, can_use, evaluate,
    verify_lease,
};

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TrustedKeyDto {
    pub kid: String,
    /// The raw 32-byte Ed25519 public key, base64url without padding.
    pub public_key: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LeaseTierDto {
    Free,
    KeepPlus,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LeaseDto {
    pub subject: String,
    pub tier: LeaseTierDto,
    pub lifetime: bool,
    pub issued_at: i64,
    pub lease_expires_at: i64,
    pub entitlement_expires_at: Option<i64>,
    pub features_version: u32,
    pub revision: u64,
    pub nonce: String,
    pub key_id: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum LeaseVerifyError {
    Malformed,
    UnsupportedAlgorithm,
    UnknownKey,
    BadSignature,
    Unsupported,
    Inconsistent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AccessStateDto {
    Free,
    PlusActive,
    PlusOfflineGrace,
    PlusLifetime,
    PlusExpired,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct AccessDto {
    pub state: AccessStateDto,
    pub plus_features: bool,
    pub needs_online_verification: bool,
    pub lease_valid_until: Option<i64>,
    pub observed_device_time: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlusFeatureDto {
    MultiVault,
    Totp,
    PasswordHealth,
    BreachMonitoring,
    HistoryUi,
    AttachmentWrite,
    PremiumThemes,
}

impl std::fmt::Display for LeaseVerifyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for LeaseVerifyError {}

impl From<LeaseError> for LeaseVerifyError {
    fn from(value: LeaseError) -> Self {
        match value {
            LeaseError::Malformed => Self::Malformed,
            LeaseError::UnsupportedAlgorithm => Self::UnsupportedAlgorithm,
            LeaseError::UnknownKey => Self::UnknownKey,
            LeaseError::BadSignature => Self::BadSignature,
            LeaseError::Unsupported => Self::Unsupported,
            LeaseError::Inconsistent => Self::Inconsistent,
        }
    }
}

impl From<Lease> for LeaseDto {
    fn from(value: Lease) -> Self {
        Self {
            subject: value.subject,
            tier: match value.tier {
                Tier::Free => LeaseTierDto::Free,
                Tier::KeepPlus => LeaseTierDto::KeepPlus,
            },
            lifetime: value.lifetime,
            issued_at: value.issued_at,
            lease_expires_at: value.lease_expires_at,
            entitlement_expires_at: value.entitlement_expires_at,
            features_version: value.features_version,
            revision: value.revision,
            nonce: value.nonce,
            key_id: value.key_id,
        }
    }
}

impl From<LeaseDto> for Lease {
    fn from(value: LeaseDto) -> Self {
        Self {
            subject: value.subject,
            tier: match value.tier {
                LeaseTierDto::Free => Tier::Free,
                LeaseTierDto::KeepPlus => Tier::KeepPlus,
            },
            lifetime: value.lifetime,
            issued_at: value.issued_at,
            lease_expires_at: value.lease_expires_at,
            entitlement_expires_at: value.entitlement_expires_at,
            features_version: value.features_version,
            revision: value.revision,
            nonce: value.nonce,
            key_id: value.key_id,
        }
    }
}

impl From<Access> for AccessDto {
    fn from(value: Access) -> Self {
        Self {
            state: match value.state {
                AccessState::Free => AccessStateDto::Free,
                AccessState::PlusActive => AccessStateDto::PlusActive,
                AccessState::PlusOfflineGrace => AccessStateDto::PlusOfflineGrace,
                AccessState::PlusLifetime => AccessStateDto::PlusLifetime,
                AccessState::PlusExpired => AccessStateDto::PlusExpired,
            },
            plus_features: value.plus_features,
            needs_online_verification: value.needs_online_verification,
            lease_valid_until: value.lease_valid_until,
            observed_device_time: value.observed_device_time,
        }
    }
}

impl From<AccessDto> for Access {
    fn from(value: AccessDto) -> Self {
        Self {
            state: match value.state {
                AccessStateDto::Free => AccessState::Free,
                AccessStateDto::PlusActive => AccessState::PlusActive,
                AccessStateDto::PlusOfflineGrace => AccessState::PlusOfflineGrace,
                AccessStateDto::PlusLifetime => AccessState::PlusLifetime,
                AccessStateDto::PlusExpired => AccessState::PlusExpired,
            },
            plus_features: value.plus_features,
            needs_online_verification: value.needs_online_verification,
            lease_valid_until: value.lease_valid_until,
            observed_device_time: value.observed_device_time,
        }
    }
}

impl From<PlusFeatureDto> for PlusFeature {
    fn from(value: PlusFeatureDto) -> Self {
        match value {
            PlusFeatureDto::MultiVault => Self::MultiVault,
            PlusFeatureDto::Totp => Self::Totp,
            PlusFeatureDto::PasswordHealth => Self::PasswordHealth,
            PlusFeatureDto::BreachMonitoring => Self::BreachMonitoring,
            PlusFeatureDto::HistoryUi => Self::HistoryUi,
            PlusFeatureDto::AttachmentWrite => Self::AttachmentWrite,
            PlusFeatureDto::PremiumThemes => Self::PremiumThemes,
        }
    }
}

/// Verifies a lease token against the keys the app trusts. A key that is not valid base64url is
/// skipped rather than trusted: a broken configuration must fail closed.
#[allow(clippy::needless_pass_by_value, reason = "UniFFI passes owned values")]
#[uniffi::export]
pub fn verify_keep_lease(
    token: String,
    keys: Vec<TrustedKeyDto>,
) -> Result<LeaseDto, LeaseVerifyError> {
    let keys: Vec<TrustedKey> = keys
        .iter()
        .filter_map(|key| TrustedKey::from_base64url(&key.kid, &key.public_key).ok())
        .collect();
    verify_lease(&token, &keys)
        .map(Into::into)
        .map_err(Into::into)
}

#[uniffi::export]
pub fn evaluate_keep_access(lease: Option<LeaseDto>, now: i64, last_observed: i64) -> AccessDto {
    let lease: Option<Lease> = lease.map(Into::into);
    evaluate(lease.as_ref(), now, last_observed).into()
}

#[uniffi::export]
pub fn can_use_plus_feature(feature: PlusFeatureDto, access: AccessDto) -> bool {
    can_use(feature.into(), &access.into())
}
