use keep_core::VaultFormat;

mod entitlement;
mod totp;
mod vault;

pub use entitlement::{
    AccessDto, AccessStateDto, LeaseDto, LeaseTierDto, LeaseVerifyError, PlusFeatureDto,
    TrustedKeyDto, can_use_plus_feature, evaluate_keep_access, verify_keep_lease,
};

pub use totp::{TotpAlgorithmDto, TotpCodeDto, TotpSetupDto, VaultTotpError, totp_normalize};

pub use vault::{
    AttachmentInfoDto, CipherDto, CreateVaultConfigDto, CreateVaultError, CreatedPasskeyDto,
    CreatedVaultDto, CustomFieldDraftDto, CustomFieldDto, CustomFieldInfoDto, DeleteOutcomeDto,
    EntryDetailDto, EntryDraftDto, EntrySummaryDto, GroupDto, ImportFileError, ImportOutcomeDto,
    ImportPlanHandle, ImportSourceDto, ImportSummaryDto, KdfDto, OpenVaultError,
    PasskeyAssertionDto, PasskeyInfoDto, PasswordDto, PasswordGeneratorError, PasswordOptionsDto,
    SaveSnapshotDto, SecretFieldDto, SecretValueDto, SiteIconDto, TextDto, UnlockMaterialDto,
    VaultCredentialsDto, VaultEditError, VaultInfoDto, VaultPasskeyError, VaultSaveError,
    VaultSessionError, VaultSessionHandle, benchmark_kdf, close_all_vault_sessions, create_vault,
    generate_key_file, generate_password, open_vault, parse_import, security_preset_balanced,
    security_preset_stronger, site_icon_links,
};

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CoreInfoDto {
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VaultFormatDto {
    Kdbx { major: u16, minor: u16 },
    KeePass1,
    Unknown,
}

impl From<VaultFormat> for VaultFormatDto {
    fn from(value: VaultFormat) -> Self {
        match value {
            VaultFormat::Kdbx { major, minor } => Self::Kdbx { major, minor },
            VaultFormat::KeePass1 => Self::KeePass1,
            VaultFormat::Unknown => Self::Unknown,
        }
    }
}

#[uniffi::export]
pub fn core_info() -> CoreInfoDto {
    let info = keep_core::core_info();
    CoreInfoDto {
        name: info.name.to_owned(),
        version: info.version.to_owned(),
    }
}

#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin; Vec<u8> maps to ByteArray and Data"
)]
#[uniffi::export]
pub fn detect_vault_format(header: Vec<u8>) -> VaultFormatDto {
    keep_core::detect_format(&header).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_info_matches_core() {
        assert_eq!(core_info().version, keep_core::core_info().version);
    }

    #[test]
    fn maps_detected_format() {
        let header = [
            0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5, 0x00, 0x00, 0x04, 0x00,
        ];
        assert_eq!(
            detect_vault_format(header.to_vec()),
            VaultFormatDto::Kdbx { major: 4, minor: 0 }
        );
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HostMatchDto {
    Exact,
    Subdomain,
    None,
}

/// The `AutoFill` origin policy: may an entry saved for `entry_host` be offered on `request_host`?
#[uniffi::export]
pub fn match_host(entry_host: &str, request_host: &str) -> HostMatchDto {
    match keep_core::match_host(entry_host, request_host) {
        keep_core::HostMatch::Exact => HostMatchDto::Exact,
        keep_core::HostMatch::Subdomain => HostMatchDto::Subdomain,
        keep_core::HostMatch::None => HostMatchDto::None,
    }
}
