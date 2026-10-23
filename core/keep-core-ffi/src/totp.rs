use std::fmt;

use keep_core::{
    PlusFeature, SecretBytes, SessionError, TotpAlgorithm, TotpConfig, TotpEncoder, TotpError,
    TotpLookupError, can_use,
};

use crate::entitlement::AccessDto;
use crate::vault::SecretValueDto;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultTotpError {
    SessionNotFound,
    SessionClosed,
    EntryNotFound,
    NotEntitled,
    NoCode,
    Empty,
    TooLong,
    UnsupportedType,
    MissingSecret,
    InvalidSecret,
    InvalidAlgorithm,
    InvalidDigits,
    InvalidPeriod,
    Migration,
    Malformed,
    InvalidText,
}

impl fmt::Display for VaultTotpError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl From<TotpError> for VaultTotpError {
    fn from(error: TotpError) -> Self {
        match error {
            TotpError::Empty => Self::Empty,
            TotpError::TooLong => Self::TooLong,
            TotpError::UnsupportedType => Self::UnsupportedType,
            TotpError::MissingSecret => Self::MissingSecret,
            TotpError::InvalidSecret => Self::InvalidSecret,
            TotpError::InvalidAlgorithm => Self::InvalidAlgorithm,
            TotpError::InvalidDigits => Self::InvalidDigits,
            TotpError::InvalidPeriod => Self::InvalidPeriod,
            TotpError::Migration => Self::Migration,
            _ => Self::Malformed,
        }
    }
}

impl From<SessionError> for VaultTotpError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            SessionError::EntryNotFound => Self::EntryNotFound,
            _ => Self::SessionNotFound,
        }
    }
}

impl From<TotpLookupError> for VaultTotpError {
    fn from(error: TotpLookupError) -> Self {
        match error {
            TotpLookupError::Session(error) => error.into(),
            TotpLookupError::NotEntitled => Self::NotEntitled,
            TotpLookupError::NoCode => Self::NoCode,
            TotpLookupError::Invalid(error) => error.into(),
            _ => Self::Malformed,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TotpCodeDto {
    pub code: SecretValueDto,
    pub period: u32,
    pub seconds_remaining: u32,
}

impl fmt::Debug for TotpCodeDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("TotpCodeDto([REDACTED])")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TotpAlgorithmDto {
    Sha1,
    Sha256,
    Sha512,
}

#[derive(uniffi::Record)]
pub struct TotpSetupDto {
    pub uri: SecretValueDto,
    pub algorithm: TotpAlgorithmDto,
    pub digits: u8,
    pub period: u32,
    pub steam: bool,
    pub issuer: String,
    pub account: String,
}

impl fmt::Debug for TotpSetupDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("TotpSetupDto([REDACTED])")
    }
}

#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin; the owned bytes are zeroized here"
)]
#[uniffi::export]
pub fn totp_normalize(input: Vec<u8>, access: AccessDto) -> Result<TotpSetupDto, VaultTotpError> {
    if !can_use(PlusFeature::Totp, &access.into()) {
        return Err(VaultTotpError::NotEntitled);
    }
    let bytes = SecretBytes::from(input);
    let text = std::str::from_utf8(bytes.expose()).map_err(|_| VaultTotpError::InvalidText)?;
    let config = TotpConfig::parse(text)?;
    Ok(TotpSetupDto {
        uri: SecretValueDto {
            utf8: config.to_uri().expose().as_bytes().to_vec(),
        },
        algorithm: match config.algorithm() {
            TotpAlgorithm::Sha1 => TotpAlgorithmDto::Sha1,
            TotpAlgorithm::Sha256 => TotpAlgorithmDto::Sha256,
            TotpAlgorithm::Sha512 => TotpAlgorithmDto::Sha512,
        },
        digits: config.digits(),
        period: config.period(),
        steam: config.encoder() == TotpEncoder::Steam,
        issuer: config.issuer().to_owned(),
        account: config.account().to_owned(),
    })
}
