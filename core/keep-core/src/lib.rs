#![forbid(unsafe_code)]

mod clock;
mod credentials;
mod entitlement;
mod error;
mod format;
mod generator;
mod icons;
mod import;
mod info;
mod kdbx;
mod key_file;
mod limits;
mod model;
mod origin;
pub mod passkey;
pub use passkey::PasskeyError;
mod secret;
mod session;
mod totp;

pub use clock::set_clock;
pub use credentials::{UnlockMaterial, VaultCredentials};
pub use entitlement::{
    ACTIVE_WINDOW_SECS, Access, AccessState, CLOCK_TOLERANCE_SECS, Lease, LeaseError,
    MAX_LEASE_BYTES, PlusFeature, Tier, TrustedKey, can_use, evaluate, verify_lease,
};
pub use error::{ErrorCategory, Limit, OpenError};
pub use format::{HEADER_PREFIX_LEN, VaultFormat, detect_format};
pub use generator::{GeneratorError, MAX_LENGTH, MIN_LENGTH, PasswordOptions, generate_password};
pub use icons::{icon_for_url, icon_links, site_host};
pub use import::{
    ImportError, ImportPlan, ImportSource, ImportedEntry, ImportedField, MAX_IMPORT_ENTRIES,
    MAX_IMPORT_SIZE, parse_import,
};
pub use info::{CoreInfo, core_info};
pub use kdbx::create::{
    Argon2Settings, CreateError, CreateVaultConfig, CreatedVault, MAX_ARGON2_ITERATIONS,
    MAX_ARGON2_MEMORY_KIB, MAX_ARGON2_PARALLELISM, MAX_NAME_CHARS, MIN_ARGON2_MEMORY_KIB,
    SecurityPreset,
};
pub use kdbx::edit::{CustomFieldDraft, DeleteOutcome, EditError, EntryDraft, MAX_ATTACHMENT_SIZE};
pub use kdbx::merge::{MergeError, MergeOptions, MergeReport};
pub use key_file::{KeyFileFormat, detect_key_file_format, generate_key_file};
pub use limits::OpenLimits;

/// Why the last save check failed, in words and without any secret; None if it passed.
#[must_use]
pub fn last_save_diagnostic() -> Option<String> {
    kdbx::writer::last_diagnostic()
}
pub use model::{
    Attachment, Cipher, DEFAULT_GROUP_ICON, EntryField, FAVORITE_TAG, FieldValue, HistoryEntry,
    InvalidItemId, ItemId, KdbxVersion, Kdf, MAX_STANDARD_ICON, Vault, VaultEntry, VaultGroup,
    VaultMetadata, is_favorite_tag,
};
pub use origin::{HostMatch, match_host};
pub use secret::{SecretBytes, SecretString};
pub use session::{
    AttachmentInfo, ChangedField, CreatedPasskey, CustomField, CustomFieldInfo, EntryDetail,
    EntrySummary, GroupSummary, HistoryError, HistoryVersion, ImportOutcome, PasskeyAssertion,
    PasskeyCreateError, PasskeyInfo, PasskeyRegistration, SaveError, SaveSnapshot, SecretField,
    SessionError, SessionId, SessionKeyMergeError, SharedSession, TotpLookupError, VaultInfo,
    VaultSession, VaultSessionStore, VisibleText, fingerprint_of,
};
pub use totp::{
    MAX_INPUT_CHARS, MAX_PERIOD, MAX_SECRET_BYTES, MIN_PERIOD, OTP_FIELD, TotpAlgorithm, TotpCode,
    TotpConfig, TotpEncoder, TotpError,
};

/// Creates a new KDBX 4.1 database: Argon2id, AES-256, gzip, a Recycle Bin, no entries.
pub fn create_vault(
    config: &CreateVaultConfig,
    credentials: &VaultCredentials,
) -> Result<CreatedVault, CreateError> {
    kdbx::create::create(config, credentials, &OpenLimits::default())
}

/// How long one key derivation with these settings takes on this device.
pub fn benchmark_kdf(settings: Argon2Settings) -> Result<u32, CreateError> {
    kdbx::create::benchmark(settings)
}

pub fn open_vault(data: &[u8], credentials: &VaultCredentials) -> Result<Vault, OpenError> {
    open_vault_with_limits(data, credentials, &OpenLimits::default())
}

pub fn open_vault_with_limits(
    data: &[u8],
    credentials: &VaultCredentials,
    limits: &OpenLimits,
) -> Result<Vault, OpenError> {
    kdbx::open(data, credentials, limits)
}

#[cfg(feature = "fuzzing")]
pub mod fuzzing {
    pub fn parse_header(data: &[u8]) {
        crate::kdbx::fuzz_header(data);
    }

    pub fn parse_inner_payload(data: &[u8]) {
        crate::kdbx::fuzz_payload(data);
    }

    /// Any XML the reader accepts must write back to XML that reads as the same tree.
    pub fn xml_round_trip(data: &[u8]) {
        crate::kdbx::fuzz_xml_round_trip(data);
    }
}
