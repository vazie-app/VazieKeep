use std::fmt;
use std::sync::{Arc, LazyLock};

use keep_core::{
    Argon2Settings, ChangedField, Cipher, CreateError, CreateVaultConfig, CustomField,
    CustomFieldDraft, CustomFieldInfo, DeleteOutcome, EditError, EntryDetail, EntryDraft,
    EntrySummary, ErrorCategory, GeneratorError, GroupSummary, HistoryError, HistoryVersion,
    ImportError, ImportPlan, ImportSource, ItemId, Kdf, MergeError, MergeOptions, MergeReport,
    OpenError, PasswordOptions, SaveError, SecretBytes, SecretField, SecretString, SessionError,
    SessionId, SessionKeyMergeError, UnlockMaterial, VaultCredentials, VaultInfo, VaultSession,
    VaultSessionStore, VisibleText,
};
use keep_core::{PasskeyCreateError, PasskeyError, PasskeyRegistration};

use crate::entitlement::AccessDto;
use crate::totp::{TotpCodeDto, VaultTotpError};

static STORE: LazyLock<VaultSessionStore> = LazyLock::new(VaultSessionStore::default);

#[derive(uniffi::Enum)]
pub enum PasswordDto {
    Text { utf8: Vec<u8> },
    UnlockMaterial { bytes: Vec<u8> },
}

#[derive(uniffi::Record)]
pub struct VaultCredentialsDto {
    pub password: Option<PasswordDto>,
    pub key_file: Option<Vec<u8>>,
    pub retain_unlock_material: bool,
}

#[derive(uniffi::Record)]
pub struct UnlockMaterialDto {
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum OpenVaultError {
    NotAVault,
    Unsupported,
    MissingCredentials,
    InvalidCredentials,
    InvalidKeyFile,
    Damaged,
    TooLarge,
    TooManySessions,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultSessionError {
    SessionNotFound,
    SessionClosed,
    EntryNotFound,
    FieldNotFound,
    InvalidArgument,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CipherDto {
    Aes256,
    ChaCha20,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KdfDto {
    AesKdf,
    Argon2d,
    Argon2id,
}

#[derive(Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TextDto {
    Plain { value: String },
    Protected,
}

#[derive(Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SecretFieldDto {
    Password,
    Title,
    UserName,
    Url,
    Notes,
    Custom { name: String },
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct VaultInfoDto {
    pub name: String,
    pub description: String,
    pub generator: String,
    pub format_major: u16,
    pub format_minor: u16,
    pub cipher: CipherDto,
    pub kdf: KdfDto,
    pub root_group_id: String,
    pub recycle_bin_id: Option<String>,
    pub group_count: u32,
    pub entry_count: u32,
    pub fingerprint: String,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct GroupDto {
    pub id: String,
    pub parent_id: Option<String>,
    pub name: String,
    pub icon: u32,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct CustomFieldInfoDto {
    pub name: String,
    pub protected: bool,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
#[allow(
    clippy::struct_excessive_bools,
    reason = "one flag per thing a row can show"
)]
pub struct EntrySummaryDto {
    pub id: String,
    pub group_id: String,
    pub title: TextDto,
    pub username: TextDto,
    pub url: TextDto,
    pub tags: Vec<String>,
    pub favorite: bool,
    pub expires_at: Option<i64>,
    pub icon: u32,
    /// The id of an image kept in the file that stands in for the standard icon.
    pub custom_icon: Option<String>,
    /// The public side of the passkey the entry holds, if it holds one.
    pub passkey: Option<PasskeyInfoDto>,
    pub has_password: bool,
    pub has_notes: bool,
    pub has_totp: bool,
    pub has_protected_fields: bool,
    pub custom_fields: Vec<CustomFieldInfoDto>,
    pub attachment_names: Vec<String>,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct CustomFieldDto {
    pub name: String,
    pub value: TextDto,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct AttachmentInfoDto {
    pub name: String,
    pub size: u64,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct EntryDetailDto {
    pub summary: EntrySummaryDto,
    pub notes: TextDto,
    pub custom_fields: Vec<CustomFieldDto>,
    pub attachments: Vec<AttachmentInfoDto>,
    pub modified_at: Option<i64>,
    pub history_count: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ChangedFieldDto {
    Title,
    UserName,
    Password,
    Url,
    Notes,
    CustomFields,
    Tags,
    Favorite,
    Attachments,
    Expiry,
}

#[derive(Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryVersionDto {
    pub index: u32,
    pub modified_at: Option<i64>,
    pub title: TextDto,
    pub username: TextDto,
    pub differs: Vec<ChangedFieldDto>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultHistoryError {
    SessionNotFound,
    SessionClosed,
    EntryNotFound,
    FieldNotFound,
    VersionNotFound,
    NotEntitled,
    InvalidArgument,
}

impl fmt::Display for VaultHistoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl From<SessionError> for VaultHistoryError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            SessionError::EntryNotFound => Self::EntryNotFound,
            SessionError::FieldNotFound => Self::FieldNotFound,
            SessionError::InvalidHandle => Self::InvalidArgument,
            _ => Self::SessionNotFound,
        }
    }
}

impl From<HistoryError> for VaultHistoryError {
    fn from(error: HistoryError) -> Self {
        match error {
            HistoryError::Session(error) => error.into(),
            HistoryError::NotEntitled => Self::NotEntitled,
            _ => Self::VersionNotFound,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ImportSourceDto {
    Csv,
    OnePasswordCsv,
    OnePassword1pux,
    BitwardenJson,
    BitwardenCsv,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum ImportFileError {
    Empty,
    TooLarge,
    Unrecognized,
    Encrypted,
    Malformed,
}

impl fmt::Display for ImportFileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl From<ImportError> for ImportFileError {
    fn from(error: ImportError) -> Self {
        match error {
            ImportError::Empty => Self::Empty,
            ImportError::TooLarge => Self::TooLarge,
            ImportError::Encrypted => Self::Encrypted,
            ImportError::Malformed => Self::Malformed,
            _ => Self::Unrecognized,
        }
    }
}

/// What an export holds, without any of its secrets: enough to ask "import these?".
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ImportSummaryDto {
    pub source: ImportSourceDto,
    pub entries: u32,
    pub folders: u32,
    pub with_totp: u32,
    pub skipped: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ImportOutcomeDto {
    pub entries: u32,
    pub groups: u32,
}

/// A parsed export. Its passwords stay on the Rust side until a session takes them in.
#[derive(Debug, uniffi::Object)]
pub struct ImportPlanHandle {
    plan: ImportPlan,
}

#[uniffi::export]
impl ImportPlanHandle {
    pub fn summary(&self) -> ImportSummaryDto {
        let entries = &self.plan.entries;
        let mut folders: Vec<&[String]> = entries
            .iter()
            .filter(|e| !e.folder.is_empty())
            .map(|e| e.folder.as_slice())
            .collect();
        folders.sort_unstable();
        folders.dedup();
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        ImportSummaryDto {
            source: match self.plan.source {
                ImportSource::OnePasswordCsv => ImportSourceDto::OnePasswordCsv,
                ImportSource::OnePassword1pux => ImportSourceDto::OnePassword1pux,
                ImportSource::BitwardenJson => ImportSourceDto::BitwardenJson,
                ImportSource::BitwardenCsv => ImportSourceDto::BitwardenCsv,
                _ => ImportSourceDto::Csv,
            },
            entries: count(entries.len()),
            folders: count(folders.len()),
            with_totp: count(entries.iter().filter(|e| e.has_totp()).count()),
            skipped: count(self.plan.skipped),
        }
    }
}

#[derive(Debug, uniffi::Record)]
pub struct SiteIconDto {
    pub host: String,
    pub png: Vec<u8>,
}

/// Where a site's icon may be, best first, judged from the text of its front page.
#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI hands strings over by value"
)]
#[uniffi::export]
pub fn site_icon_links(host: String, html: String) -> Vec<String> {
    keep_core::icon_links(&host, &html)
}

/// Reads an export of another password manager. The bytes are the person's passwords in plain
/// text: they are wiped here once parsed.
#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin; the owned bytes are wiped here"
)]
#[uniffi::export]
pub fn parse_import(data: Vec<u8>) -> Result<Arc<ImportPlanHandle>, ImportFileError> {
    let data = SecretBytes::from(data);
    let plan = keep_core::parse_import(data.expose())?;
    Ok(Arc::new(ImportPlanHandle { plan }))
}

#[derive(uniffi::Record)]
pub struct SecretValueDto {
    pub utf8: Vec<u8>,
}

#[derive(uniffi::Object)]
pub struct VaultSessionHandle {
    id: SessionId,
}

/// The core's credentials from what a platform hands over. The bytes are wiped when they drop.
fn core_credentials(dto: VaultCredentialsDto) -> Result<VaultCredentials, OpenVaultError> {
    let VaultCredentialsDto {
        password,
        key_file,
        retain_unlock_material: _,
    } = dto;
    let key_file = key_file.map(SecretBytes::from);
    Ok(match password {
        None => VaultCredentials::new(None, key_file)?,
        Some(PasswordDto::Text { utf8 }) => {
            VaultCredentials::new(Some(SecretBytes::from(utf8)), key_file)?
        }
        Some(PasswordDto::UnlockMaterial { bytes }) => {
            let bytes = SecretBytes::from(bytes);
            VaultCredentials::from_unlock_material(
                &UnlockMaterial::decode(bytes.expose())?,
                key_file,
            )?
        }
    })
}

#[allow(
    clippy::needless_pass_by_value,
    reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin; owned values are dropped (and the password zeroized) here"
)]
#[uniffi::export]
pub fn open_vault(
    data: Vec<u8>,
    credentials: VaultCredentialsDto,
) -> Result<Arc<VaultSessionHandle>, OpenVaultError> {
    if !STORE.has_capacity() {
        return Err(OpenVaultError::TooManySessions);
    }
    let retain_unlock_material = credentials.retain_unlock_material;
    let credentials = core_credentials(credentials)?;
    let vault = keep_core::open_vault(&data, &credentials)?;
    let mut session = VaultSession::new(vault)?;
    if retain_unlock_material {
        if let Some(material) = credentials.unlock_material() {
            session.retain_unlock_material(material);
        }
    }
    drop(credentials);
    let id = STORE.insert(session).map_err(|error| match error {
        SessionError::TooManySessions => OpenVaultError::TooManySessions,
        _ => OpenVaultError::Unavailable,
    })?;
    Ok(Arc::new(VaultSessionHandle { id }))
}

#[derive(Debug, uniffi::Record)]
pub struct CreateVaultConfigDto {
    pub name: String,
    pub memory_kib: u32,
    pub iterations: u32,
    pub parallelism: u32,
}

#[derive(uniffi::Record)]
pub struct CreatedVaultDto {
    pub bytes: Vec<u8>,
    pub fingerprint: String,
}

impl fmt::Debug for CreatedVaultDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("CreatedVaultDto")
            .field("bytes", &self.bytes.len())
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct SecurityPresetDto {
    pub memory_kib: u32,
    pub iterations: u32,
    pub parallelism: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum CreateVaultError {
    EmptyName,
    InvalidName,
    InvalidSettings,
    MissingCredentials,
    InvalidKeyFile,
    Unavailable,
}

impl fmt::Display for CreateVaultError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for CreateVaultError {}

impl From<CreateError> for CreateVaultError {
    fn from(value: CreateError) -> Self {
        match value {
            CreateError::EmptyName => Self::EmptyName,
            CreateError::NameTooLong | CreateError::InvalidName => Self::InvalidName,
            CreateError::InvalidKdf => Self::InvalidSettings,
            CreateError::MissingCredentials => Self::MissingCredentials,
            CreateError::RandomUnavailable
            | CreateError::EncryptionFailed
            | CreateError::VerificationFailed => Self::Unavailable,
        }
    }
}

/// The settings of the "Balanced" preset, the same on every platform.
#[uniffi::export]
pub fn security_preset_balanced() -> SecurityPresetDto {
    preset(keep_core::SecurityPreset::Balanced)
}

/// The settings of the "Stronger" preset.
#[uniffi::export]
pub fn security_preset_stronger() -> SecurityPresetDto {
    preset(keep_core::SecurityPreset::Stronger)
}

fn preset(preset: keep_core::SecurityPreset) -> SecurityPresetDto {
    let settings = preset.settings();
    SecurityPresetDto {
        memory_kib: settings.memory_kib,
        iterations: settings.iterations,
        parallelism: settings.parallelism,
    }
}

#[allow(
    clippy::needless_pass_by_value,
    reason = "owned credentials are dropped, and the password zeroized, here"
)]
#[uniffi::export]
pub fn create_vault(
    config: CreateVaultConfigDto,
    credentials: VaultCredentialsDto,
) -> Result<CreatedVaultDto, CreateVaultError> {
    let key_file = credentials.key_file.map(SecretBytes::from);
    let password = match credentials.password {
        Some(PasswordDto::Text { utf8 }) => Some(SecretBytes::from(utf8)),
        _ => None,
    };
    let credentials = VaultCredentials::new(password, key_file).map_err(|error| match error {
        OpenError::InvalidKeyFile => CreateVaultError::InvalidKeyFile,
        _ => CreateVaultError::MissingCredentials,
    })?;
    let config = CreateVaultConfig {
        name: config.name,
        kdf: Argon2Settings {
            memory_kib: config.memory_kib,
            iterations: config.iterations,
            parallelism: config.parallelism,
        },
        cipher: Cipher::Aes256,
    };
    let created = keep_core::create_vault(&config, &credentials)?;
    Ok(CreatedVaultDto {
        bytes: created.bytes,
        fingerprint: created.fingerprint,
    })
}

/// Milliseconds one key derivation with these settings takes on this device.
#[uniffi::export]
pub fn benchmark_kdf(preset: SecurityPresetDto) -> Result<u32, CreateVaultError> {
    Ok(keep_core::benchmark_kdf(Argon2Settings {
        memory_kib: preset.memory_kib,
        iterations: preset.iterations,
        parallelism: preset.parallelism,
    })?)
}

/// A new `KeePass` XML key file (version 2), made from the system CSPRNG.
#[uniffi::export]
pub fn generate_key_file() -> Result<SecretValueDto, CreateVaultError> {
    let key = keep_core::generate_key_file().map_err(|_| CreateVaultError::Unavailable)?;
    Ok(SecretValueDto {
        utf8: key.expose().to_vec(),
    })
}

#[uniffi::export]
pub fn close_all_vault_sessions() -> u32 {
    u32::try_from(STORE.close_all()).unwrap_or(u32::MAX)
}

/// How a second version of the database is combined into the open one.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MergeOptionsDto {
    /// Appended to the title of a conflict copy; the app passes one in the person's language.
    pub conflict_suffix: String,
    /// Merge a database that is not a version of this one: every item is new.
    pub allow_unrelated: bool,
}

/// What a merge did. Counts and ids only: nothing from the vault.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MergeReportDto {
    /// Whether the open database is different now (and so dirty).
    pub changed: bool,
    pub entries_added: u32,
    pub entries_restored: u32,
    pub entries_updated: u32,
    pub entries_moved: u32,
    pub entries_deleted: u32,
    pub histories_merged: u32,
    pub history_versions_added: u32,
    pub groups_added: u32,
    pub groups_updated: u32,
    pub groups_moved: u32,
    pub groups_deleted: u32,
    /// The conflict copies made, one entry id each.
    pub conflicts: Vec<String>,
    pub icons_added: u32,
    pub deletion_records_added: u32,
    pub settings_updated: bool,
}

impl From<&MergeReport> for MergeReportDto {
    fn from(report: &MergeReport) -> Self {
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        Self {
            changed: report.changed(),
            entries_added: count(report.entries_added),
            entries_restored: count(report.entries_restored),
            entries_updated: count(report.entries_updated),
            entries_moved: count(report.entries_moved),
            entries_deleted: count(report.entries_deleted),
            histories_merged: count(report.histories_merged),
            history_versions_added: count(report.history_versions_added),
            groups_added: count(report.groups_added),
            groups_updated: count(report.groups_updated),
            groups_moved: count(report.groups_moved),
            groups_deleted: count(report.groups_deleted),
            conflicts: report.conflicts.iter().map(ToString::to_string).collect(),
            icons_added: count(report.icons_added),
            deletion_records_added: count(report.deletion_records_added),
            settings_updated: report.settings_updated,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultMergeError {
    SessionNotFound,
    SessionClosed,
    /// The open database can't be written back.
    ReadOnly,
    /// The two databases are not versions of one vault (and the options did not allow it).
    DifferentVault,
    /// The merged result would not be a valid vault; nothing changed.
    InvalidResult,
    // Opening the other file, as for `open_vault`.
    NotAVault,
    Unsupported,
    MissingCredentials,
    InvalidCredentials,
    InvalidKeyFile,
    Damaged,
    TooLarge,
    Unavailable,
}

impl fmt::Display for VaultMergeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for VaultMergeError {}

impl From<OpenVaultError> for VaultMergeError {
    fn from(error: OpenVaultError) -> Self {
        match error {
            OpenVaultError::NotAVault => Self::NotAVault,
            OpenVaultError::Unsupported => Self::Unsupported,
            OpenVaultError::MissingCredentials => Self::MissingCredentials,
            OpenVaultError::InvalidCredentials => Self::InvalidCredentials,
            OpenVaultError::InvalidKeyFile => Self::InvalidKeyFile,
            OpenVaultError::Damaged => Self::Damaged,
            OpenVaultError::TooLarge => Self::TooLarge,
            OpenVaultError::TooManySessions | OpenVaultError::Unavailable => Self::Unavailable,
        }
    }
}

impl From<MergeError> for VaultMergeError {
    fn from(error: MergeError) -> Self {
        match error {
            MergeError::ReadOnly => Self::ReadOnly,
            MergeError::DifferentVault => Self::DifferentVault,
            MergeError::InvalidVault => Self::InvalidResult,
            _ => Self::Unavailable,
        }
    }
}

impl From<SessionError> for VaultMergeError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            _ => Self::SessionNotFound,
        }
    }
}

#[uniffi::export]
impl VaultSessionHandle {
    pub fn info(&self) -> Result<VaultInfoDto, VaultSessionError> {
        Ok(STORE.read(self.id, |s| info_dto(&s.info()))?)
    }

    pub fn groups(&self) -> Result<Vec<GroupDto>, VaultSessionError> {
        Ok(STORE.read(self.id, |s| s.groups().iter().map(group_dto).collect())?)
    }

    pub fn entries(&self) -> Result<Vec<EntrySummaryDto>, VaultSessionError> {
        Ok(STORE.read(self.id, |s| s.entries().map(|e| summary_dto(&e)).collect())?)
    }

    pub fn search(&self, query: &str) -> Result<Vec<EntrySummaryDto>, VaultSessionError> {
        Ok(STORE.read(self.id, |s| {
            s.search(query).iter().map(summary_dto).collect()
        })?)
    }

    pub fn entry(&self, entry_id: &str) -> Result<EntryDetailDto, VaultSessionError> {
        let id = parse_entry_id(entry_id)?;
        Ok(detail_dto(&STORE.read(self.id, |s| s.entry(id))??))
    }

    pub fn reveal(
        &self,
        entry_id: &str,
        field: &SecretFieldDto,
    ) -> Result<SecretValueDto, VaultSessionError> {
        let id = parse_entry_id(entry_id)?;
        let secret = STORE.read(self.id, |s| s.reveal(id, secret_field(field)))??;
        Ok(SecretValueDto {
            utf8: secret.expose().as_bytes().to_vec(),
        })
    }

    pub fn history(
        &self,
        entry_id: &str,
        access: AccessDto,
    ) -> Result<Vec<HistoryVersionDto>, VaultHistoryError> {
        let id = parse_history_id(entry_id)?;
        let versions = STORE.read(self.id, |s| s.history(id, &access.into()))??;
        Ok(versions.iter().map(version_dto).collect())
    }

    pub fn history_entry(
        &self,
        entry_id: &str,
        index: u32,
        access: AccessDto,
    ) -> Result<EntryDetailDto, VaultHistoryError> {
        let id = parse_history_id(entry_id)?;
        let detail = STORE.read(self.id, |s| {
            s.history_entry(id, index as usize, &access.into())
        })??;
        Ok(detail_dto(&detail))
    }

    pub fn reveal_history(
        &self,
        entry_id: &str,
        index: u32,
        field: &SecretFieldDto,
        access: AccessDto,
    ) -> Result<SecretValueDto, VaultHistoryError> {
        let id = parse_history_id(entry_id)?;
        let secret = STORE.read(self.id, |s| {
            s.reveal_history(id, index as usize, secret_field(field), &access.into())
        })??;
        Ok(SecretValueDto {
            utf8: secret.expose().as_bytes().to_vec(),
        })
    }

    pub fn restore_history(
        &self,
        entry_id: &str,
        index: u32,
        access: AccessDto,
    ) -> Result<(), VaultEditError> {
        let id = parse_id(entry_id)?;
        Ok(STORE.write(self.id, |s| {
            s.restore_history(id, index as usize, &access.into())
        })??)
    }

    pub fn totp_code(
        &self,
        entry_id: &str,
        unix_seconds: u64,
        access: AccessDto,
    ) -> Result<TotpCodeDto, VaultTotpError> {
        let id = parse_entry_id(entry_id).map_err(|_| VaultTotpError::EntryNotFound)?;
        let code = STORE.read(self.id, |s| s.totp(id, unix_seconds, &access.into()))??;
        Ok(TotpCodeDto {
            code: SecretValueDto {
                utf8: code.code.expose().as_bytes().to_vec(),
            },
            period: code.period,
            seconds_remaining: code.seconds_remaining,
        })
    }

    pub fn unlock_material(&self) -> Result<Option<UnlockMaterialDto>, VaultSessionError> {
        Ok(STORE.read(self.id, |s| {
            s.unlock_material().map(|material| UnlockMaterialDto {
                bytes: material.encode().expose().to_vec(),
            })
        })?)
    }

    pub fn close_session(&self) -> bool {
        STORE.close(self.id)
    }

    pub fn is_editable(&self) -> Result<bool, VaultSessionError> {
        Ok(STORE.read(self.id, VaultSession::is_editable)?)
    }

    pub fn is_dirty(&self) -> Result<bool, VaultSessionError> {
        Ok(STORE.read(self.id, VaultSession::is_dirty)?)
    }

    pub fn create_entry(
        &self,
        group_id: &str,
        draft: EntryDraftDto,
    ) -> Result<String, VaultEditError> {
        let group = parse_id(group_id)?;
        let draft = draft.into_draft()?;
        Ok(STORE
            .write(self.id, |s| s.create_entry(group, &draft))??
            .to_string())
    }

    pub fn update_entry(&self, entry_id: &str, draft: EntryDraftDto) -> Result<(), VaultEditError> {
        let id = parse_id(entry_id)?;
        let draft = draft.into_draft()?;
        Ok(STORE.write(self.id, |s| s.update_entry(id, &draft))??)
    }

    pub fn attachment(
        &self,
        entry_id: &str,
        name: &str,
    ) -> Result<SecretValueDto, VaultSessionError> {
        let id = parse_entry_id(entry_id)?;
        let bytes = STORE.read(self.id, |s| s.attachment(id, name))??;
        Ok(SecretValueDto {
            utf8: bytes.expose().to_vec(),
        })
    }

    #[allow(
        clippy::needless_pass_by_value,
        reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin"
    )]
    pub fn add_attachment(
        &self,
        entry_id: &str,
        name: &str,
        data: Vec<u8>,
        access: AccessDto,
    ) -> Result<String, VaultEditError> {
        let id = parse_id(entry_id)?;
        let data = SecretBytes::from(data);
        Ok(STORE.write(self.id, |s| {
            s.add_attachment(id, name, data.expose(), &access.into())
        })??)
    }

    /// Takes every entry of a parsed export into `group_id`; the export's folders become groups.
    pub fn import_plan(
        &self,
        group_id: &str,
        plan: &ImportPlanHandle,
    ) -> Result<ImportOutcomeDto, VaultEditError> {
        let group = parse_id(group_id)?;
        let outcome = STORE.write(self.id, |s| s.import(group, &plan.plan))??;
        Ok(ImportOutcomeDto {
            entries: u32::try_from(outcome.entries).unwrap_or(u32::MAX),
            groups: u32::try_from(outcome.groups).unwrap_or(u32::MAX),
        })
    }

    pub fn remove_attachment(&self, entry_id: &str, name: &str) -> Result<(), VaultEditError> {
        let id = parse_id(entry_id)?;
        Ok(STORE.write(self.id, |s| s.remove_attachment(id, name))??)
    }

    pub fn set_favorite(&self, entry_id: &str, favorite: bool) -> Result<(), VaultEditError> {
        let id = parse_id(entry_id)?;
        Ok(STORE.write(self.id, |s| s.set_favorite(id, favorite))??)
    }

    pub fn delete_entry(
        &self,
        entry_id: &str,
        permanent: bool,
    ) -> Result<DeleteOutcomeDto, VaultEditError> {
        let id = parse_id(entry_id)?;
        Ok(STORE
            .write(self.id, |s| s.delete_entry(id, permanent))??
            .into())
    }

    pub fn move_entry(&self, entry_id: &str, group_id: &str) -> Result<(), VaultEditError> {
        let (id, group) = (parse_id(entry_id)?, parse_id(group_id)?);
        Ok(STORE.write(self.id, |s| s.move_entry(id, group))??)
    }

    pub fn create_group(&self, parent_id: &str, name: &str) -> Result<String, VaultEditError> {
        let parent = parse_id(parent_id)?;
        Ok(STORE
            .write(self.id, |s| s.create_group(parent, name))??
            .to_string())
    }

    pub fn rename_group(&self, group_id: &str, name: &str) -> Result<(), VaultEditError> {
        let id = parse_id(group_id)?;
        Ok(STORE.write(self.id, |s| s.rename_group(id, name))??)
    }

    /// Gives every entry that still has the default icon one by its address; returns how many.
    pub fn assign_icons_by_url(&self) -> Result<u32, VaultEditError> {
        let changed = STORE.write(self.id, VaultSession::assign_icons_by_url)??;
        Ok(u32::try_from(changed).unwrap_or(u32::MAX))
    }

    /// The sites whose icons are worth fetching: entries with an address and no image yet.
    pub fn site_icon_hosts(&self) -> Result<Vec<String>, VaultSessionError> {
        Ok(STORE.read(self.id, VaultSession::site_icon_hosts)?)
    }

    /// Makes a passkey for a site and keeps it in a new entry of the group. The private key stays
    /// in the vault; the answer is what the site gets.
    #[allow(
        clippy::needless_pass_by_value,
        reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin"
    )]
    pub fn create_passkey(
        &self,
        group_id: &str,
        rp_id: &str,
        rp_name: &str,
        user_name: &str,
        user_handle: Vec<u8>,
    ) -> Result<CreatedPasskeyDto, VaultPasskeyError> {
        let group = group_id
            .parse::<ItemId>()
            .map_err(|_| VaultPasskeyError::InvalidRequest)?;
        let request = PasskeyRegistration {
            rp_id,
            rp_name,
            user_name,
            user_handle: &user_handle,
        };
        let made = STORE.write(self.id, |s| s.create_passkey(group, &request))??;
        Ok(CreatedPasskeyDto {
            entry_id: made.entry.to_string(),
            credential_id: made.credential_id,
            public_key: made.public_key,
            authenticator_data: made.authenticator_data,
            attestation_object: made.attestation_object,
        })
    }

    /// The entries that hold a passkey for a site; every passkey entry when `rp_id` is empty.
    pub fn passkeys(&self, rp_id: &str) -> Result<Vec<EntrySummaryDto>, VaultSessionError> {
        let wanted = (!rp_id.is_empty()).then_some(rp_id);
        Ok(STORE.read(self.id, |s| {
            s.passkeys(wanted)
                .iter()
                .map(|(summary, _)| summary_dto(summary))
                .collect()
        })?)
    }

    /// Signs a sign-in request with the entry's passkey, for the site it was made for only.
    #[allow(
        clippy::needless_pass_by_value,
        reason = "UniFFI maps &[u8] to ByteBuffer in Kotlin"
    )]
    pub fn sign_passkey(
        &self,
        entry_id: &str,
        rp_id: &str,
        client_data_hash: Vec<u8>,
    ) -> Result<PasskeyAssertionDto, VaultPasskeyError> {
        let id = entry_id
            .parse::<ItemId>()
            .map_err(|_| VaultPasskeyError::NoPasskey)?;
        let signed = STORE.read(self.id, |s| s.sign_passkey(id, rp_id, &client_data_hash))??;
        Ok(PasskeyAssertionDto {
            credential_id: signed.credential_id,
            user_handle: signed.user_handle,
            authenticator_data: signed.authenticator_data,
            signature: signed.signature,
        })
    }

    /// Takes the passkey out of an entry; the entry stays.
    pub fn remove_passkey(&self, entry_id: &str) -> Result<(), VaultEditError> {
        let id = parse_id(entry_id)?;
        Ok(STORE.write(self.id, |s| s.remove_passkey(id))??)
    }

    /// The site to ask for one entry's icon, when it has an address and no image yet.
    pub fn site_icon_host(&self, entry_id: &str) -> Result<Option<String>, VaultSessionError> {
        let Ok(id) = entry_id.parse::<ItemId>() else {
            return Ok(None);
        };
        Ok(STORE.read(self.id, |s| s.site_icon_host(id))?)
    }

    /// Keeps the fetched icons (PNG) in the file and shows them on the entries of their sites;
    /// returns how many entries got one.
    pub fn set_site_icons(&self, icons: Vec<SiteIconDto>) -> Result<u32, VaultEditError> {
        let icons: Vec<(String, Vec<u8>)> = icons.into_iter().map(|i| (i.host, i.png)).collect();
        let changed = STORE.write(self.id, |s| s.set_site_icons(&icons))??;
        Ok(u32::try_from(changed).unwrap_or(u32::MAX))
    }

    /// The bytes of an image kept in the file, by the id an entry names.
    pub fn custom_icon(&self, icon_id: &str) -> Result<Option<Vec<u8>>, VaultSessionError> {
        let Ok(id) = icon_id.parse::<ItemId>() else {
            return Ok(None);
        };
        Ok(STORE.read(self.id, |s| s.custom_icon(id))?)
    }

    pub fn set_entry_icon(&self, entry_id: &str, icon: u32) -> Result<(), VaultEditError> {
        let id = parse_id(entry_id)?;
        Ok(STORE.write(self.id, |s| s.set_entry_icon(id, icon))??)
    }

    pub fn set_group_icon(&self, group_id: &str, icon: u32) -> Result<(), VaultEditError> {
        let id = parse_id(group_id)?;
        Ok(STORE.write(self.id, |s| s.set_group_icon(id, icon))??)
    }

    pub fn delete_group(
        &self,
        group_id: &str,
        recursive: bool,
        permanent: bool,
    ) -> Result<DeleteOutcomeDto, VaultEditError> {
        let id = parse_id(group_id)?;
        Ok(STORE
            .write(self.id, |s| s.delete_group(id, recursive, permanent))??
            .into())
    }

    /// Encrypted, verified bytes for the platform to write. The session stays dirty until
    /// `mark_saved` confirms the write.
    pub fn prepare_save(&self) -> Result<SaveSnapshotDto, VaultSaveError> {
        let snapshot = STORE.read(self.id, VaultSession::prepare_save)??;
        Ok(SaveSnapshotDto {
            bytes: snapshot.bytes,
            generation: snapshot.generation,
        })
    }

    pub fn mark_saved(&self, generation: u64) -> Result<bool, VaultSessionError> {
        Ok(STORE.write(self.id, |s| s.mark_saved(generation))?)
    }

    /// Combines another version of this database (its encrypted bytes and its own credentials) into
    /// the open one. Nothing is written: the session is dirty if anything changed, and is saved like
    /// any edit. The other file takes no session slot and is gone when this returns.
    #[allow(
        clippy::needless_pass_by_value,
        reason = "UniFFI hands records and bytes over by value; the credentials are wiped here"
    )]
    pub fn merge(
        &self,
        data: Vec<u8>,
        credentials: VaultCredentialsDto,
        options: MergeOptionsDto,
    ) -> Result<MergeReportDto, VaultMergeError> {
        let credentials = core_credentials(credentials)?;
        let other = keep_core::open_vault(&data, &credentials).map_err(OpenVaultError::from)?;
        drop(credentials);
        let options = MergeOptions {
            conflict_suffix: options.conflict_suffix,
            allow_unrelated: options.allow_unrelated,
        };
        let report = STORE.write(self.id, |s| s.merge(other, &options))??;
        Ok(MergeReportDto::from(&report))
    }

    /// `merge` for a file the session's own key opens, so no credentials are passed. `InvalidCredentials`
    /// means the key does not fit that file; the caller then asks for the password and calls `merge`.
    #[allow(
        clippy::needless_pass_by_value,
        reason = "UniFFI hands records and bytes over by value"
    )]
    pub fn merge_with_session_key(
        &self,
        data: Vec<u8>,
        options: MergeOptionsDto,
    ) -> Result<MergeReportDto, VaultMergeError> {
        let options = MergeOptions {
            conflict_suffix: options.conflict_suffix,
            allow_unrelated: options.allow_unrelated,
        };
        let report = STORE
            .write(self.id, |s| s.merge_with_session_key(&data, &options))?
            .map_err(|error| match error {
                SessionKeyMergeError::Open(error) => {
                    VaultMergeError::from(OpenVaultError::from(error))
                }
                SessionKeyMergeError::Merge(error) => VaultMergeError::from(error),
            })?;
        Ok(MergeReportDto::from(&report))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultEditError {
    SessionNotFound,
    SessionClosed,
    ReadOnly,
    EntryNotFound,
    GroupNotFound,
    GroupNotEmpty,
    RootGroup,
    InvalidFieldName,
    InvalidText,
    InvalidArgument,
    RandomUnavailable,
    AttachmentNotFound,
    AttachmentTooLarge,
    NotEntitled,
    VersionNotFound,
}

impl fmt::Display for VaultEditError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl From<SessionError> for VaultEditError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            SessionError::InvalidHandle => Self::InvalidArgument,
            _ => Self::SessionNotFound,
        }
    }
}

impl From<EditError> for VaultEditError {
    fn from(error: EditError) -> Self {
        match error {
            EditError::ReadOnly => Self::ReadOnly,
            EditError::EntryNotFound => Self::EntryNotFound,
            EditError::GroupNotFound => Self::GroupNotFound,
            EditError::GroupNotEmpty => Self::GroupNotEmpty,
            EditError::RootGroup => Self::RootGroup,
            EditError::InvalidFieldName => Self::InvalidFieldName,
            EditError::RandomUnavailable => Self::RandomUnavailable,
            EditError::AttachmentNotFound => Self::AttachmentNotFound,
            EditError::AttachmentTooLarge => Self::AttachmentTooLarge,
            EditError::NotEntitled => Self::NotEntitled,
            EditError::VersionNotFound => Self::VersionNotFound,
            EditError::InvalidIcon => Self::InvalidArgument,
            _ => Self::InvalidText,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PasskeyInfoDto {
    pub rp_id: String,
    pub user_name: String,
    /// base64url
    pub credential_id: String,
    /// base64url
    pub user_handle: String,
}

#[derive(Debug, uniffi::Record)]
pub struct CreatedPasskeyDto {
    pub entry_id: String,
    pub credential_id: Vec<u8>,
    /// `SubjectPublicKeyInfo` DER.
    pub public_key: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    pub attestation_object: Vec<u8>,
}

#[derive(Debug, uniffi::Record)]
pub struct PasskeyAssertionDto {
    pub credential_id: Vec<u8>,
    pub user_handle: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultPasskeyError {
    SessionNotFound,
    SessionClosed,
    ReadOnly,
    GroupNotFound,
    InvalidRequest,
    NoPasskey,
    InvalidKey,
    Failed,
}

impl fmt::Display for VaultPasskeyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl From<SessionError> for VaultPasskeyError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            _ => Self::SessionNotFound,
        }
    }
}

impl From<PasskeyError> for VaultPasskeyError {
    fn from(error: PasskeyError) -> Self {
        match error {
            PasskeyError::InvalidRequest => Self::InvalidRequest,
            PasskeyError::NoPasskey => Self::NoPasskey,
            PasskeyError::InvalidKey => Self::InvalidKey,
            _ => Self::Failed,
        }
    }
}

impl From<PasskeyCreateError> for VaultPasskeyError {
    fn from(error: PasskeyCreateError) -> Self {
        match error {
            PasskeyCreateError::Passkey(inner) => inner.into(),
            PasskeyCreateError::Edit(EditError::ReadOnly) => Self::ReadOnly,
            PasskeyCreateError::Edit(EditError::GroupNotFound) => Self::GroupNotFound,
            PasskeyCreateError::Edit(_) => Self::Failed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum VaultSaveError {
    SessionNotFound,
    SessionClosed,
    ReadOnly,
    RandomUnavailable,
    EncryptionFailed,
    VerificationFailed,
}

impl fmt::Display for VaultSaveError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl From<SessionError> for VaultSaveError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            _ => Self::SessionNotFound,
        }
    }
}

impl From<SaveError> for VaultSaveError {
    fn from(error: SaveError) -> Self {
        match error {
            SaveError::ReadOnly => Self::ReadOnly,
            SaveError::RandomUnavailable => Self::RandomUnavailable,
            SaveError::VerificationFailed => Self::VerificationFailed,
            _ => Self::EncryptionFailed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DeleteOutcomeDto {
    MovedToRecycleBin,
    Deleted,
}

impl From<DeleteOutcome> for DeleteOutcomeDto {
    fn from(outcome: DeleteOutcome) -> Self {
        match outcome {
            DeleteOutcome::MovedToRecycleBin => Self::MovedToRecycleBin,
            DeleteOutcome::Deleted => Self::Deleted,
        }
    }
}

/// Secret values cross the boundary as UTF-8 bytes, as typed passwords do, and become
/// zeroizing strings here.
#[derive(uniffi::Record)]
pub struct CustomFieldDraftDto {
    pub name: String,
    pub value: Vec<u8>,
    pub protected: bool,
}

#[derive(uniffi::Record)]
pub struct EntryDraftDto {
    pub title: String,
    pub username: String,
    pub password: Vec<u8>,
    pub url: String,
    pub notes: String,
    pub custom_fields: Vec<CustomFieldDraftDto>,
    pub tags: Vec<String>,
    pub favorite: bool,
    pub expires_at: Option<i64>,
}

impl fmt::Debug for EntryDraftDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("EntryDraftDto([REDACTED])")
    }
}

impl fmt::Debug for CustomFieldDraftDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("CustomFieldDraftDto([REDACTED])")
    }
}

fn secret_text(bytes: Vec<u8>) -> Result<SecretString, VaultEditError> {
    let bytes = SecretBytes::from(bytes);
    let text = std::str::from_utf8(bytes.expose()).map_err(|_| VaultEditError::InvalidText)?;
    Ok(SecretString::new(text.to_owned()))
}

impl EntryDraftDto {
    fn into_draft(self) -> Result<EntryDraft, VaultEditError> {
        let Self {
            title,
            username,
            password,
            url,
            notes,
            custom_fields,
            tags,
            favorite,
            expires_at,
        } = self;
        Ok(EntryDraft {
            title,
            username,
            password: secret_text(password)?,
            url,
            notes,
            custom_fields: custom_fields
                .into_iter()
                .map(|f| {
                    Ok(CustomFieldDraft {
                        name: f.name,
                        value: secret_text(f.value)?,
                        protected: f.protected,
                    })
                })
                .collect::<Result<_, VaultEditError>>()?,
            tags,
            favorite,
            expires: expires_at,
        })
    }
}

#[derive(uniffi::Record)]
pub struct SaveSnapshotDto {
    pub bytes: Vec<u8>,
    pub generation: u64,
}

impl fmt::Debug for SaveSnapshotDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SaveSnapshotDto")
            .field("bytes", &self.bytes.len())
            .field("generation", &self.generation)
            .finish()
    }
}

#[allow(
    clippy::struct_excessive_bools,
    reason = "one switch per character set, as in the UI"
)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PasswordOptionsDto {
    pub length: u32,
    pub lowercase: bool,
    pub uppercase: bool,
    pub digits: bool,
    pub symbols: bool,
    pub exclude_ambiguous: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Error)]
pub enum PasswordGeneratorError {
    NoCharacterSets,
    LengthOutOfRange,
    RandomUnavailable,
}

impl fmt::Display for PasswordGeneratorError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

#[uniffi::export]
pub fn generate_password(
    options: PasswordOptionsDto,
) -> Result<SecretValueDto, PasswordGeneratorError> {
    let options = PasswordOptions {
        length: usize::try_from(options.length)
            .map_err(|_| PasswordGeneratorError::LengthOutOfRange)?,
        lowercase: options.lowercase,
        uppercase: options.uppercase,
        digits: options.digits,
        symbols: options.symbols,
        exclude_ambiguous: options.exclude_ambiguous,
    };
    let password = keep_core::generate_password(&options).map_err(|error| match error {
        GeneratorError::NoCharacterSets => PasswordGeneratorError::NoCharacterSets,
        GeneratorError::RandomUnavailable => PasswordGeneratorError::RandomUnavailable,
        _ => PasswordGeneratorError::LengthOutOfRange,
    })?;
    Ok(SecretValueDto {
        utf8: password.expose().as_bytes().to_vec(),
    })
}

impl Drop for VaultSessionHandle {
    fn drop(&mut self) {
        STORE.close(self.id);
    }
}

fn parse_entry_id(text: &str) -> Result<ItemId, VaultSessionError> {
    text.parse().map_err(|_| VaultSessionError::InvalidArgument)
}

fn parse_history_id(text: &str) -> Result<ItemId, VaultHistoryError> {
    text.parse().map_err(|_| VaultHistoryError::InvalidArgument)
}

fn secret_field(field: &SecretFieldDto) -> SecretField<'_> {
    match field {
        SecretFieldDto::Password => SecretField::Password,
        SecretFieldDto::Title => SecretField::Title,
        SecretFieldDto::UserName => SecretField::UserName,
        SecretFieldDto::Url => SecretField::Url,
        SecretFieldDto::Notes => SecretField::Notes,
        SecretFieldDto::Custom { name } => SecretField::Custom(name),
    }
}

fn version_dto(version: &HistoryVersion) -> HistoryVersionDto {
    HistoryVersionDto {
        index: u32::try_from(version.index).unwrap_or(u32::MAX),
        modified_at: version.modified,
        title: text_dto(&version.title),
        username: text_dto(&version.username),
        differs: version
            .differs
            .iter()
            .map(|field| match field {
                ChangedField::Title => ChangedFieldDto::Title,
                ChangedField::UserName => ChangedFieldDto::UserName,
                ChangedField::Password => ChangedFieldDto::Password,
                ChangedField::Url => ChangedFieldDto::Url,
                ChangedField::Notes => ChangedFieldDto::Notes,
                ChangedField::CustomFields => ChangedFieldDto::CustomFields,
                ChangedField::Tags => ChangedFieldDto::Tags,
                ChangedField::Favorite => ChangedFieldDto::Favorite,
                ChangedField::Attachments => ChangedFieldDto::Attachments,
                ChangedField::Expiry => ChangedFieldDto::Expiry,
            })
            .collect(),
    }
}

fn parse_id(text: &str) -> Result<ItemId, VaultEditError> {
    text.parse().map_err(|_| VaultEditError::InvalidArgument)
}

impl From<OpenError> for OpenVaultError {
    fn from(error: OpenError) -> Self {
        match error.category() {
            ErrorCategory::NotAVault => Self::NotAVault,
            ErrorCategory::Unsupported => Self::Unsupported,
            ErrorCategory::MissingCredentials => Self::MissingCredentials,
            ErrorCategory::InvalidCredentials => Self::InvalidCredentials,
            ErrorCategory::InvalidKeyFile => Self::InvalidKeyFile,
            ErrorCategory::Damaged => Self::Damaged,
            ErrorCategory::TooLarge => Self::TooLarge,
        }
    }
}

impl From<SessionError> for VaultSessionError {
    fn from(error: SessionError) -> Self {
        match error {
            SessionError::SessionClosed => Self::SessionClosed,
            SessionError::EntryNotFound => Self::EntryNotFound,
            SessionError::FieldNotFound => Self::FieldNotFound,
            SessionError::InvalidHandle => Self::InvalidArgument,
            _ => Self::SessionNotFound,
        }
    }
}

fn text_dto(text: &VisibleText) -> TextDto {
    match text {
        VisibleText::Plain(value) => TextDto::Plain {
            value: value.clone(),
        },
        VisibleText::Protected => TextDto::Protected,
    }
}

fn info_dto(info: &VaultInfo) -> VaultInfoDto {
    VaultInfoDto {
        name: info.name.clone(),
        description: info.description.clone(),
        generator: info.generator.clone(),
        format_major: info.format.major,
        format_minor: info.format.minor,
        cipher: match info.cipher {
            Cipher::Aes256 => CipherDto::Aes256,
            Cipher::ChaCha20 => CipherDto::ChaCha20,
        },
        kdf: match info.kdf {
            Kdf::AesKdf => KdfDto::AesKdf,
            Kdf::Argon2d => KdfDto::Argon2d,
            Kdf::Argon2id => KdfDto::Argon2id,
        },
        root_group_id: info.root_group.to_string(),
        recycle_bin_id: info.recycle_bin.map(|id| id.to_string()),
        group_count: u32::try_from(info.group_count).unwrap_or(u32::MAX),
        entry_count: u32::try_from(info.entry_count).unwrap_or(u32::MAX),
        fingerprint: info.fingerprint.clone(),
    }
}

fn group_dto(group: &GroupSummary) -> GroupDto {
    GroupDto {
        id: group.id.to_string(),
        parent_id: group.parent.map(|id| id.to_string()),
        name: group.name.clone(),
        icon: group.icon,
    }
}

fn summary_dto(entry: &EntrySummary) -> EntrySummaryDto {
    EntrySummaryDto {
        id: entry.id.to_string(),
        group_id: entry.group.to_string(),
        title: text_dto(&entry.title),
        username: text_dto(&entry.username),
        url: text_dto(&entry.url),
        tags: entry.tags.clone(),
        favorite: entry.favorite,
        expires_at: entry.expires,
        icon: entry.icon,
        custom_icon: entry.custom_icon.map(|id| id.to_string()),
        passkey: entry.passkey.as_ref().map(|p| PasskeyInfoDto {
            rp_id: p.rp_id.clone(),
            user_name: p.user_name.clone(),
            credential_id: p.credential_id.clone(),
            user_handle: p.user_handle.clone(),
        }),
        has_password: entry.has_password,
        has_notes: entry.has_notes,
        has_totp: entry.has_totp,
        has_protected_fields: entry.has_protected_fields(),
        custom_fields: entry
            .custom_fields
            .iter()
            .map(|CustomFieldInfo { name, protected }| CustomFieldInfoDto {
                name: name.clone(),
                protected: *protected,
            })
            .collect(),
        attachment_names: entry.attachment_names.clone(),
    }
}

fn detail_dto(detail: &EntryDetail) -> EntryDetailDto {
    EntryDetailDto {
        summary: summary_dto(&detail.summary),
        notes: text_dto(&detail.notes),
        custom_fields: detail
            .custom_fields
            .iter()
            .map(|CustomField { name, value }| CustomFieldDto {
                name: name.clone(),
                value: text_dto(value),
            })
            .collect(),
        attachments: detail
            .attachments
            .iter()
            .map(|a| AttachmentInfoDto {
                name: a.name.clone(),
                size: a.size as u64,
            })
            .collect(),
        modified_at: detail.modified,
        history_count: u32::try_from(detail.history_count).unwrap_or(u32::MAX),
    }
}

impl fmt::Display for OpenVaultError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for OpenVaultError {}

impl fmt::Display for VaultSessionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self, f)
    }
}

impl std::error::Error for VaultSessionError {}

impl fmt::Debug for VaultCredentialsDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("VaultCredentialsDto([REDACTED])")
    }
}

impl fmt::Debug for PasswordDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("PasswordDto([REDACTED])")
    }
}

impl fmt::Debug for UnlockMaterialDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("UnlockMaterialDto([REDACTED])")
    }
}

impl fmt::Debug for SecretValueDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SecretValueDto([REDACTED])")
    }
}

impl fmt::Debug for VaultSessionHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("VaultSessionHandle(..)")
    }
}

impl fmt::Debug for TextDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Plain { .. } => f.write_str("Plain(..)"),
            Self::Protected => f.write_str("Protected"),
        }
    }
}

impl fmt::Debug for SecretFieldDto {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Custom { .. } => f.write_str("Custom(..)"),
            Self::Password => f.write_str("Password"),
            Self::Title => f.write_str("Title"),
            Self::UserName => f.write_str("UserName"),
            Self::Url => f.write_str("Url"),
            Self::Notes => f.write_str("Notes"),
        }
    }
}

macro_rules! redacted_debug {
    ($($ty:ident => $($field:ident),*;)*) => {$(
        impl fmt::Debug for $ty {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.debug_struct(stringify!($ty))$(.field(stringify!($field), &self.$field))*.finish_non_exhaustive()
            }
        }
    )*};
}

redacted_debug! {
    VaultInfoDto => format_major, format_minor, cipher, kdf, group_count, entry_count;
    GroupDto => id, parent_id;
    CustomFieldInfoDto => protected;
    EntrySummaryDto => id, group_id, title, username, url, favorite, expires_at, has_password, has_protected_fields;
    CustomFieldDto => value;
    EntryDetailDto => summary, notes, modified_at, history_count;
    HistoryVersionDto => index, modified_at, differs;
    AttachmentInfoDto => size;
}

#[cfg(test)]
mod tests {
    use super::*;

    const MARKER: &str = "VAZIE_KEEP_SECRET_DO_NOT_LEAK_42";

    fn vector(name: &str) -> Vec<u8> {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../shared/test-vectors/kdbx4")
            .join(name);
        std::fs::read(path).unwrap()
    }

    fn open_marker() -> Arc<VaultSessionHandle> {
        open_vault(vector("leak-marker.kdbx"), text_credentials(MARKER, true)).unwrap()
    }

    fn text_credentials(password: &str, retain: bool) -> VaultCredentialsDto {
        VaultCredentialsDto {
            password: Some(PasswordDto::Text {
                utf8: password.as_bytes().to_vec(),
            }),
            key_file: None,
            retain_unlock_material: retain,
        }
    }

    #[test]
    fn session_lifecycle_through_the_ffi() {
        let handle = open_marker();
        let entries = handle.entries().unwrap();
        let entry = &entries[0];
        assert!(entry.has_password && entry.has_protected_fields);
        let secret = handle.reveal(&entry.id, &SecretFieldDto::Password).unwrap();
        assert_eq!(secret.utf8, MARKER.as_bytes());
        assert!(handle.close_session());
        assert_eq!(
            handle.entries().unwrap_err(),
            VaultSessionError::SessionClosed
        );
        assert_eq!(
            handle
                .reveal(&entry.id, &SecretFieldDto::Password)
                .unwrap_err(),
            VaultSessionError::SessionClosed
        );
    }

    #[test]
    fn nothing_formatted_contains_a_secret() {
        let handle = open_marker();
        let entry = handle.entries().unwrap().remove(0);
        let detail = handle.entry(&entry.id).unwrap();
        let secret = handle
            .reveal(
                &entry.id,
                &SecretFieldDto::Custom {
                    name: "Marker Field".into(),
                },
            )
            .unwrap();
        let credentials = text_credentials(MARKER, false);
        let material = handle.unlock_material().unwrap().unwrap();
        let rendered = format!(
            "{handle:?} {:?} {:?} {entry:?} {detail:?} {secret:?} {credentials:?} {material:?} {:?} {} {}",
            handle.info().unwrap(),
            handle.groups().unwrap(),
            credentials.password,
            VaultSessionError::FieldNotFound,
            OpenVaultError::InvalidCredentials,
        );
        assert!(!rendered.contains(MARKER));
        assert_eq!(detail.custom_fields[0].value, TextDto::Protected);
    }

    #[test]
    fn invalid_arguments_are_errors() {
        let handle = open_marker();
        assert_eq!(
            handle.entry("not-a-uuid").unwrap_err(),
            VaultSessionError::InvalidArgument
        );
        assert_eq!(
            handle
                .entry("00000000-0000-0000-0000-000000000000")
                .unwrap_err(),
            VaultSessionError::EntryNotFound
        );
        let entry = handle.entries().unwrap().remove(0);
        assert_eq!(
            handle
                .reveal(
                    &entry.id,
                    &SecretFieldDto::Custom {
                        name: "missing".into()
                    }
                )
                .unwrap_err(),
            VaultSessionError::FieldNotFound
        );
        assert_eq!(
            open_vault(
                b"PK".to_vec(),
                VaultCredentialsDto {
                    password: None,
                    key_file: None,
                    retain_unlock_material: false,
                }
            )
            .unwrap_err(),
            OpenVaultError::MissingCredentials
        );
        assert_eq!(
            open_vault(b"PK".to_vec(), text_credentials("x", false)).unwrap_err(),
            OpenVaultError::NotAVault
        );
        assert_eq!(
            open_vault(
                vector("leak-marker.kdbx"),
                VaultCredentialsDto {
                    password: Some(PasswordDto::UnlockMaterial { bytes: vec![9; 33] }),
                    key_file: None,
                    retain_unlock_material: false,
                }
            )
            .unwrap_err(),
            OpenVaultError::InvalidCredentials
        );
    }

    #[test]
    fn unlock_material_is_exported_only_when_retained_and_reopens_the_vault() {
        let plain =
            open_vault(vector("leak-marker.kdbx"), text_credentials(MARKER, false)).unwrap();
        assert!(plain.unlock_material().unwrap().is_none());
        let retained = open_marker();
        let material = retained.unlock_material().unwrap().unwrap();
        let fingerprint = retained.info().unwrap().fingerprint;
        let reopened = open_vault(
            vector("leak-marker.kdbx"),
            VaultCredentialsDto {
                password: Some(PasswordDto::UnlockMaterial {
                    bytes: material.bytes,
                }),
                key_file: None,
                retain_unlock_material: false,
            },
        )
        .unwrap();
        assert_eq!(reopened.info().unwrap().fingerprint, fingerprint);
        assert!(reopened.unlock_material().unwrap().is_none());
        assert!(retained.close_session());
        assert_eq!(
            retained.unlock_material().unwrap_err(),
            VaultSessionError::SessionClosed
        );
    }

    #[test]
    fn key_files_cross_the_ffi() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../shared/test-vectors/keyfiles");
        let read = |name: &str| std::fs::read(dir.join(name)).unwrap();
        let open = |key_file: &str| {
            open_vault(
                read("vault-password-plus-keyfile.kdbx"),
                VaultCredentialsDto {
                    password: Some(PasswordDto::Text {
                        utf8: b"correct horse battery staple".to_vec(),
                    }),
                    key_file: Some(read(key_file)),
                    retain_unlock_material: false,
                },
            )
        };
        assert!(open("keyfile-v2.keyx").is_ok());
        assert_eq!(
            open("keyfile-wrong.keyx").unwrap_err(),
            OpenVaultError::InvalidCredentials
        );
        assert_eq!(
            open("keyfile-v2-bad-hash.keyx").unwrap_err(),
            OpenVaultError::InvalidKeyFile
        );
    }

    fn created_vault() -> Vec<u8> {
        create_vault(
            CreateVaultConfigDto {
                name: "Shared".to_owned(),
                memory_kib: 8 * 1024,
                iterations: 1,
                parallelism: 1,
            },
            text_credentials("pw", false),
        )
        .unwrap()
        .bytes
    }

    fn draft(title: &str, password: &str) -> EntryDraftDto {
        EntryDraftDto {
            title: title.to_owned(),
            username: "user".to_owned(),
            password: password.as_bytes().to_vec(),
            url: String::new(),
            notes: String::new(),
            custom_fields: Vec::new(),
            tags: Vec::new(),
            favorite: false,
            expires_at: None,
        }
    }

    fn saved(handle: &VaultSessionHandle) -> Vec<u8> {
        let snapshot = handle.prepare_save().unwrap();
        assert!(handle.mark_saved(snapshot.generation).unwrap());
        snapshot.bytes
    }

    fn options() -> MergeOptionsDto {
        MergeOptionsDto {
            conflict_suffix: " (conflict)".to_owned(),
            allow_unrelated: false,
        }
    }

    fn id_titled(handle: &VaultSessionHandle, title: &str) -> String {
        let entries = handle.entries().unwrap();
        entries
            .iter()
            .find(|e| {
                e.title
                    == TextDto::Plain {
                        value: title.to_owned(),
                    }
            })
            .unwrap_or_else(|| panic!("no entry titled {title}"))
            .id
            .clone()
    }

    fn password_of(handle: &VaultSessionHandle, id: &str) -> Vec<u8> {
        handle.reveal(id, &SecretFieldDto::Password).unwrap().utf8
    }

    /// Edits in the same second tie, and a tie is a clash; the merge tests move the clock on.
    fn next_second() {
        std::thread::sleep(std::time::Duration::from_millis(1100));
    }

    #[test]
    fn a_second_version_merges_through_the_ffi() {
        let first = open_vault(created_vault(), text_credentials("pw", false)).unwrap();
        let root = first.info().unwrap().root_group_id;
        first.create_entry(&root, draft("Mail", "m0")).unwrap();
        let base = saved(&first);

        let mine = open_vault(base.clone(), text_credentials("pw", false)).unwrap();
        let theirs = open_vault(base, text_credentials("pw", false)).unwrap();
        next_second();
        theirs
            .update_entry(&id_titled(&theirs, "Mail"), draft("Mail", "m1"))
            .unwrap();
        theirs.create_entry(&root, draft("Shop", "s1")).unwrap();
        let theirs_bytes = saved(&theirs);
        assert!(!mine.is_dirty().unwrap());

        let report = mine
            .merge(
                theirs_bytes.clone(),
                text_credentials("pw", false),
                options(),
            )
            .unwrap();
        assert!(report.changed);
        assert_eq!((report.entries_updated, report.entries_added), (1, 1));
        assert!(report.conflicts.is_empty());
        assert!(mine.is_dirty().unwrap());
        assert_eq!(password_of(&mine, &id_titled(&mine, "Mail")), b"m1");
        assert_eq!(password_of(&mine, &id_titled(&mine, "Shop")), b"s1");

        // Saved, then merged again: nothing to do, nothing dirty, and the file opens.
        let merged = saved(&mine);
        let again = mine
            .merge(theirs_bytes, text_credentials("pw", false), options())
            .unwrap();
        assert!(!again.changed);
        assert!(!mine.is_dirty().unwrap());
        let reopened = open_vault(merged, text_credentials("pw", false)).unwrap();
        assert_eq!(reopened.entries().unwrap().len(), 2);
    }

    #[test]
    fn a_clash_keeps_the_loser_as_an_entry_the_report_names() {
        let first = open_vault(created_vault(), text_credentials("pw", false)).unwrap();
        let root = first.info().unwrap().root_group_id;
        first.create_entry(&root, draft("Mail", "m0")).unwrap();
        let base = saved(&first);
        let mine = open_vault(base.clone(), text_credentials("pw", false)).unwrap();
        let theirs = open_vault(base, text_credentials("pw", false)).unwrap();
        next_second();
        mine.update_entry(&id_titled(&mine, "Mail"), draft("Mail", "m-mine"))
            .unwrap();
        next_second();
        theirs
            .update_entry(&id_titled(&theirs, "Mail"), draft("Mail", "m-theirs"))
            .unwrap();
        let theirs_bytes = saved(&theirs);

        let options = MergeOptionsDto {
            conflict_suffix: " (конфликт)".to_owned(),
            ..options()
        };
        let report = mine
            .merge(theirs_bytes, text_credentials("pw", false), options)
            .unwrap();
        assert_eq!(report.conflicts.len(), 1);
        let copy = id_titled(&mine, "Mail (конфликт)");
        assert_eq!(report.conflicts[0], copy);
        assert_eq!(password_of(&mine, &copy), b"m-mine");
        assert_eq!(password_of(&mine, &id_titled(&mine, "Mail")), b"m-theirs");
    }

    #[test]
    fn a_merge_that_cannot_happen_changes_nothing_and_says_why() {
        let mine = open_vault(created_vault(), text_credentials("pw", false)).unwrap();
        let root = mine.info().unwrap().root_group_id;
        mine.create_entry(&root, draft("Mail", "m0")).unwrap();
        saved(&mine);
        let other = created_vault();

        // The other file's own password is wrong.
        assert_eq!(
            mine.merge(other.clone(), text_credentials("nope", false), options())
                .unwrap_err(),
            VaultMergeError::InvalidCredentials
        );
        assert_eq!(
            mine.merge(
                b"not a vault".to_vec(),
                text_credentials("pw", false),
                options()
            )
            .unwrap_err(),
            VaultMergeError::NotAVault
        );
        // A different database, unless allowed.
        assert_eq!(
            mine.merge(other.clone(), text_credentials("pw", false), options())
                .unwrap_err(),
            VaultMergeError::DifferentVault
        );
        assert!(!mine.is_dirty().unwrap());
        let allowed = MergeOptionsDto {
            allow_unrelated: true,
            ..options()
        };
        let report = mine
            .merge(other, text_credentials("pw", false), allowed)
            .unwrap();
        assert!(!report.changed, "an empty database adds nothing");

        // A closed session cannot be merged into.
        assert!(mine.close_session());
        assert_eq!(
            mine.merge(created_vault(), text_credentials("pw", false), options())
                .unwrap_err(),
            VaultMergeError::SessionClosed
        );
    }

    #[test]
    fn dropping_the_handle_closes_the_session() {
        let handle = open_marker();
        let id = handle.id;
        drop(handle);
        assert_eq!(STORE.get(id).unwrap_err(), SessionError::SessionClosed);
    }
}
