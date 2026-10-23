//! Creating a new KDBX 4.1 database from nothing.
//!
//! The file is built through the same lossless document and writer that saving uses, so a new
//! vault has no special path: it is written once as complete encrypted bytes and then read back
//! with the regular reader and the real credentials before anyone is allowed to keep it.

use std::fmt;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use zeroize::Zeroizing;

use super::dom::Element;
use super::header::{self, Compression, KdfParams};
use super::writer::{SaveState, VaultSource, WriteError, random};
use super::{crypto, open};
use crate::credentials::VaultCredentials;
use crate::limits::OpenLimits;
use crate::model::{Cipher, ItemId, KdbxVersion, Kdf};

pub const MIN_ARGON2_MEMORY_KIB: u32 = 8 * 1024;
pub const MAX_ARGON2_MEMORY_KIB: u32 = 1024 * 1024;
pub const MAX_ARGON2_ITERATIONS: u32 = 64;
pub const MAX_ARGON2_PARALLELISM: u32 = 16;
pub const MAX_NAME_CHARS: usize = 128;

const HISTORY_MAX_ITEMS: i64 = 10;
const HISTORY_MAX_SIZE: i64 = 6 * 1024 * 1024;
const GENERATOR: &str = "Vazie Keep";
const RECYCLE_BIN_ENABLED: &str = "True";

/// Argon2id cost. The memory is in KiB, the unit of the Argon2 specification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Argon2Settings {
    pub memory_kib: u32,
    pub iterations: u32,
    pub parallelism: u32,
}

/// The two named presets of the create flow. They are the same numbers on every platform, so a
/// vault made on one phone opens in a comparable time on the other.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecurityPreset {
    Balanced,
    Stronger,
}

impl SecurityPreset {
    pub const fn settings(self) -> Argon2Settings {
        match self {
            Self::Balanced => Argon2Settings {
                memory_kib: 64 * 1024,
                iterations: 6,
                parallelism: 2,
            },
            Self::Stronger => Argon2Settings {
                memory_kib: 128 * 1024,
                iterations: 10,
                parallelism: 2,
            },
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CreateVaultConfig {
    /// The database name stored inside the file; the file name is chosen separately.
    pub name: String,
    pub kdf: Argon2Settings,
    pub cipher: Cipher,
}

impl CreateVaultConfig {
    pub fn new(name: impl Into<String>, preset: SecurityPreset) -> Self {
        Self {
            name: name.into(),
            kdf: preset.settings(),
            cipher: Cipher::Aes256,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CreateError {
    EmptyName,
    NameTooLong,
    InvalidName,
    InvalidKdf,
    MissingCredentials,
    RandomUnavailable,
    EncryptionFailed,
    /// The bytes just written didn't read back as the vault that was meant to be written.
    VerificationFailed,
}

impl fmt::Display for CreateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::EmptyName => "the database name is empty",
            Self::NameTooLong => "the database name is too long",
            Self::InvalidName => "the database name contains control characters",
            Self::InvalidKdf => "the key derivation settings are out of range",
            Self::MissingCredentials => "a password or a key file is required",
            Self::RandomUnavailable => "no secure random source",
            Self::EncryptionFailed => "the vault could not be encrypted",
            Self::VerificationFailed => "the new vault did not read back correctly",
        })
    }
}

impl std::error::Error for CreateError {}

impl From<WriteError> for CreateError {
    fn from(value: WriteError) -> Self {
        match value {
            WriteError::RandomUnavailable => Self::RandomUnavailable,
            WriteError::EncryptionFailed => Self::EncryptionFailed,
            WriteError::VerificationFailed => Self::VerificationFailed,
        }
    }
}

pub struct CreatedVault {
    pub bytes: Vec<u8>,
    pub root_group: ItemId,
    pub fingerprint: String,
}

impl fmt::Debug for CreatedVault {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("CreatedVault")
            .field("bytes", &self.bytes.len())
            .finish_non_exhaustive()
    }
}

pub(crate) fn validate(config: &CreateVaultConfig) -> Result<(), CreateError> {
    let name = config.name.trim();
    if name.is_empty() {
        return Err(CreateError::EmptyName);
    }
    if name.chars().count() > MAX_NAME_CHARS {
        return Err(CreateError::NameTooLong);
    }
    if name.chars().any(char::is_control) {
        return Err(CreateError::InvalidName);
    }
    let kdf = config.kdf;
    if !(MIN_ARGON2_MEMORY_KIB..=MAX_ARGON2_MEMORY_KIB).contains(&kdf.memory_kib)
        || !(1..=MAX_ARGON2_ITERATIONS).contains(&kdf.iterations)
        || !(1..=MAX_ARGON2_PARALLELISM).contains(&kdf.parallelism)
    {
        return Err(CreateError::InvalidKdf);
    }
    Ok(())
}

/// Builds, encrypts and verifies a new vault. The result is complete file content: the caller
/// writes it once to the place the person chose.
pub(crate) fn create(
    config: &CreateVaultConfig,
    credentials: &VaultCredentials,
    limits: &OpenLimits,
) -> Result<CreatedVault, CreateError> {
    validate(config)?;
    if !credentials.has_password() && !credentials.has_key_file() {
        return Err(CreateError::MissingCredentials);
    }
    let composite = credentials
        .composite_key()
        .map_err(|_| CreateError::MissingCredentials)?;

    let salt = random::<32>()?;
    let kdf = config.kdf;
    let transformed = crypto::transform_key(
        &KdfParams::Argon2 {
            kind: Kdf::Argon2id,
            salt: salt.as_slice(),
            memory_bytes: u64::from(kdf.memory_kib) * 1024,
            iterations: u64::from(kdf.iterations),
            parallelism: kdf.parallelism,
            version: 0x13,
        },
        &composite,
    )
    .map_err(|_| CreateError::InvalidKdf)?;

    let root = ItemId::from_bytes(*random::<16>()?);
    let document = build_document(config.name.trim(), root);
    let cipher_uuid = match config.cipher {
        Cipher::Aes256 => header::CIPHER_AES256,
        Cipher::ChaCha20 => header::CIPHER_CHACHA20,
    };
    let source = VaultSource {
        document,
        save: SaveState {
            version: KdbxVersion { major: 4, minor: 1 },
            cipher: config.cipher,
            compression: Compression::Gzip,
            fields: vec![
                (header::FIELD_CIPHER, cipher_uuid.to_vec()),
                (header::FIELD_COMPRESSION, 1u32.to_le_bytes().to_vec()),
                (header::FIELD_KDF, kdf_dictionary(kdf, salt.as_slice())),
            ],
            binaries: Vec::new(),
            transformed_key: transformed,
        },
    };
    let bytes = source.write_verified(limits)?;

    // The strongest check there is: the regular reader, the real credentials, the real KDF.
    let reopened =
        open(&bytes, credentials, limits).map_err(|_| CreateError::VerificationFailed)?;
    if reopened.root.id != root
        || reopened.metadata.name != config.name.trim()
        || reopened.metadata.format.major != 4
        || !reopened.root.entries.is_empty()
    {
        return Err(CreateError::VerificationFailed);
    }
    Ok(CreatedVault {
        bytes,
        root_group: root,
        fingerprint: crate::session::fingerprint_of(root),
    })
}

/// How long one key derivation with these settings takes on this device, in milliseconds.
pub(crate) fn benchmark(settings: Argon2Settings) -> Result<u32, CreateError> {
    let config = CreateVaultConfig {
        name: "benchmark".to_owned(),
        kdf: settings,
        cipher: Cipher::Aes256,
    };
    validate(&config)?;
    let composite = Zeroizing::new(*random::<32>()?);
    let salt = random::<32>()?;
    let started = crate::clock::Stopwatch::start();
    crypto::transform_key(
        &KdfParams::Argon2 {
            kind: Kdf::Argon2id,
            salt: salt.as_slice(),
            memory_bytes: u64::from(settings.memory_kib) * 1024,
            iterations: u64::from(settings.iterations),
            parallelism: settings.parallelism,
            version: 0x13,
        },
        &composite,
    )
    .map_err(|_| CreateError::InvalidKdf)?;
    Ok(u32::try_from(started.elapsed_millis()).unwrap_or(u32::MAX))
}

fn kdf_dictionary(kdf: Argon2Settings, salt: &[u8]) -> Vec<u8> {
    fn item(out: &mut Vec<u8>, kind: u8, key: &str, value: &[u8]) {
        out.push(kind);
        out.extend_from_slice(&u32::try_from(key.len()).unwrap_or(0).to_le_bytes());
        out.extend_from_slice(key.as_bytes());
        out.extend_from_slice(&u32::try_from(value.len()).unwrap_or(0).to_le_bytes());
        out.extend_from_slice(value);
    }
    let mut out = vec![0x00, 0x01];
    item(&mut out, 0x42, "$UUID", &header::KDF_ARGON2ID);
    item(&mut out, 0x42, "S", salt);
    item(&mut out, 0x04, "P", &kdf.parallelism.to_le_bytes());
    item(
        &mut out,
        0x05,
        "I",
        &u64::from(kdf.iterations).to_le_bytes(),
    );
    item(
        &mut out,
        0x05,
        "M",
        &(u64::from(kdf.memory_kib) * 1024).to_le_bytes(),
    );
    item(&mut out, 0x04, "V", &0x13u32.to_le_bytes());
    out.push(0);
    out
}

fn build_document(name: &str, root: ItemId) -> Element {
    let now = super::time::now();
    let meta = Element::new("Meta")
        .with_child(txt("Generator", GENERATOR))
        .with_child(txt("DatabaseName", name))
        .with_child(txt("DatabaseNameChanged", now.clone()))
        .with_child(Element::new("DatabaseDescription"))
        .with_child(txt("DatabaseDescriptionChanged", now.clone()))
        .with_child(Element::new("DefaultUserName"))
        .with_child(txt("DefaultUserNameChanged", now.clone()))
        .with_child(txt("MaintenanceHistoryDays", "365"))
        .with_child(Element::new("Color"))
        .with_child(txt("MasterKeyChanged", now.clone()))
        .with_child(txt("MasterKeyChangeRec", "-1"))
        .with_child(txt("MasterKeyChangeForce", "-1"))
        .with_child(
            Element::new("MemoryProtection")
                .with_child(txt("ProtectTitle", "False"))
                .with_child(txt("ProtectUserName", "False"))
                .with_child(txt("ProtectPassword", "True"))
                .with_child(txt("ProtectURL", "False"))
                .with_child(txt("ProtectNotes", "False")),
        )
        .with_child(txt("RecycleBinEnabled", RECYCLE_BIN_ENABLED))
        .with_child(txt(
            "RecycleBinUUID",
            encode_id(ItemId::from_bytes([0; 16])),
        ))
        .with_child(txt("RecycleBinChanged", now.clone()))
        .with_child(txt(
            "EntryTemplatesGroup",
            encode_id(ItemId::from_bytes([0; 16])),
        ))
        .with_child(txt("EntryTemplatesGroupChanged", now.clone()))
        .with_child(txt("HistoryMaxItems", HISTORY_MAX_ITEMS.to_string()))
        .with_child(txt("HistoryMaxSize", HISTORY_MAX_SIZE.to_string()))
        .with_child(txt("LastSelectedGroup", encode_id(root)))
        .with_child(txt("LastTopVisibleGroup", encode_id(root)))
        .with_child(txt("SettingsChanged", now.clone()))
        .with_child(Element::new("CustomData"));
    let group = Element::new("Group")
        .with_child(txt("UUID", encode_id(root)))
        .with_child(txt("Name", name))
        .with_child(Element::new("Notes"))
        .with_child(txt("IconID", "48"))
        .with_child(times(&now))
        .with_child(txt("IsExpanded", "True"))
        .with_child(Element::new("DefaultAutoTypeSequence"))
        .with_child(txt("EnableAutoType", "null"))
        .with_child(txt("EnableSearching", "null"))
        .with_child(txt(
            "LastTopVisibleEntry",
            encode_id(ItemId::from_bytes([0; 16])),
        ));
    Element::new("KeePassFile").with_child(meta).with_child(
        Element::new("Root")
            .with_child(group)
            .with_child(Element::new("DeletedObjects")),
    )
}

fn times(now: &str) -> Element {
    Element::new("Times")
        .with_child(txt("CreationTime", now))
        .with_child(txt("LastModificationTime", now))
        .with_child(txt("LastAccessTime", now))
        .with_child(txt("ExpiryTime", now))
        .with_child(txt("Expires", "False"))
        .with_child(txt("UsageCount", "0"))
        .with_child(txt("LocationChanged", now))
}

fn txt(name: &str, value: impl Into<String>) -> Element {
    Element::with_text(name, value)
}

fn encode_id(id: ItemId) -> String {
    STANDARD.encode(id.as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::secret::SecretBytes;

    fn fast() -> CreateVaultConfig {
        CreateVaultConfig {
            name: "Личное ключей".to_owned(),
            kdf: Argon2Settings {
                memory_kib: MIN_ARGON2_MEMORY_KIB,
                iterations: 1,
                parallelism: 1,
            },
            cipher: Cipher::Aes256,
        }
    }

    fn password(text: &str) -> VaultCredentials {
        VaultCredentials::from_password(SecretBytes::new(text.as_bytes().to_vec()))
    }

    #[test]
    fn a_new_vault_reads_back_with_its_password() {
        let created = create(&fast(), &password("pw"), &OpenLimits::default()).unwrap();
        let vault = open(&created.bytes, &password("pw"), &OpenLimits::default()).unwrap();
        assert_eq!(vault.metadata.name, "Личное ключей");
        assert_eq!(vault.metadata.format.major, 4);
        assert_eq!(vault.metadata.format.minor, 1);
        assert_eq!(vault.metadata.kdf, Kdf::Argon2id);
        assert_eq!(vault.root.id, created.root_group);
        assert!(vault.root.entries.is_empty());
        assert!(open(&created.bytes, &password("other"), &OpenLimits::default()).is_err());
    }

    #[test]
    fn presets_are_valid_and_stable() {
        for preset in [SecurityPreset::Balanced, SecurityPreset::Stronger] {
            let config = CreateVaultConfig::new("x", preset);
            validate(&config).unwrap();
        }
        assert_eq!(SecurityPreset::Balanced.settings().memory_kib, 65536);
    }

    #[test]
    fn rejects_bad_names_and_settings() {
        let mut config = fast();
        config.name = "   ".to_owned();
        assert_eq!(validate(&config), Err(CreateError::EmptyName));
        config.name = "a\nb".to_owned();
        assert_eq!(validate(&config), Err(CreateError::InvalidName));
        config.name = "x".repeat(MAX_NAME_CHARS + 1);
        assert_eq!(validate(&config), Err(CreateError::NameTooLong));
        let mut config = fast();
        config.kdf.memory_kib = 1;
        assert_eq!(validate(&config), Err(CreateError::InvalidKdf));
        config.kdf.memory_kib = MAX_ARGON2_MEMORY_KIB + 1;
        assert_eq!(validate(&config), Err(CreateError::InvalidKdf));
        config = fast();
        config.kdf.iterations = 0;
        assert_eq!(validate(&config), Err(CreateError::InvalidKdf));
    }

    #[test]
    fn two_vaults_never_share_an_identity_or_a_file() {
        let a = create(&fast(), &password("pw"), &OpenLimits::default()).unwrap();
        let b = create(&fast(), &password("pw"), &OpenLimits::default()).unwrap();
        assert_ne!(a.root_group, b.root_group);
        assert_ne!(a.bytes, b.bytes);
    }

    #[test]
    fn chacha_is_supported() {
        let mut config = fast();
        config.cipher = Cipher::ChaCha20;
        let created = create(&config, &password("pw"), &OpenLimits::default()).unwrap();
        let vault = open(&created.bytes, &password("pw"), &OpenLimits::default()).unwrap();
        assert_eq!(vault.metadata.cipher, Cipher::ChaCha20);
    }

    #[test]
    fn benchmark_reports_a_time() {
        let millis = benchmark(fast().kdf).unwrap();
        assert!(millis < 5000);
    }
}
