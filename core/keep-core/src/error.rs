use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum OpenError {
    InvalidFormat,
    UnsupportedVersion { major: u16, minor: u16 },
    UnsupportedCipher,
    UnsupportedKdf,
    UnsupportedCompression,
    MissingCredentials,
    InvalidCredentials,
    InvalidKeyFile,
    InvalidUnlockMaterial,
    CorruptedVault,
    IntegrityCheckFailed,
    DecompressionFailed,
    XmlInvalid,
    LimitExceeded(Limit),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum Limit {
    FileSize,
    KdfMemory,
    KdfIterations,
    KdfParallelism,
    AesKdfRounds,
    DecompressedSize,
    XmlNodes,
    GroupDepth,
    Groups,
    Entries,
    FieldSize,
    Attachments,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCategory {
    NotAVault,
    Unsupported,
    MissingCredentials,
    InvalidCredentials,
    InvalidKeyFile,
    Damaged,
    TooLarge,
}

impl OpenError {
    pub fn category(self) -> ErrorCategory {
        match self {
            Self::InvalidFormat => ErrorCategory::NotAVault,
            Self::UnsupportedVersion { .. }
            | Self::UnsupportedCipher
            | Self::UnsupportedKdf
            | Self::UnsupportedCompression => ErrorCategory::Unsupported,
            Self::MissingCredentials => ErrorCategory::MissingCredentials,
            Self::InvalidCredentials | Self::InvalidUnlockMaterial => {
                ErrorCategory::InvalidCredentials
            }
            Self::InvalidKeyFile => ErrorCategory::InvalidKeyFile,
            Self::CorruptedVault
            | Self::IntegrityCheckFailed
            | Self::DecompressionFailed
            | Self::XmlInvalid => ErrorCategory::Damaged,
            Self::LimitExceeded(_) => ErrorCategory::TooLarge,
        }
    }
}

impl fmt::Display for OpenError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidFormat => f.write_str("not a KDBX file or malformed header"),
            Self::UnsupportedVersion { major, minor } => {
                write!(f, "unsupported format version {major}.{minor}")
            }
            Self::UnsupportedCipher => f.write_str("unsupported cipher"),
            Self::UnsupportedKdf => f.write_str("unsupported key derivation function"),
            Self::UnsupportedCompression => f.write_str("unsupported compression"),
            Self::MissingCredentials => f.write_str("no credentials supplied"),
            Self::InvalidCredentials => {
                f.write_str("invalid credentials or damaged header authentication code")
            }
            Self::InvalidKeyFile => {
                f.write_str("key file is damaged or has an unsupported version")
            }
            Self::InvalidUnlockMaterial => f.write_str("unlock material is malformed"),
            Self::CorruptedVault => f.write_str("vault structure is damaged"),
            Self::IntegrityCheckFailed => f.write_str("vault content failed integrity check"),
            Self::DecompressionFailed => f.write_str("vault content could not be decompressed"),
            Self::XmlInvalid => f.write_str("vault content is not valid KeePass XML"),
            Self::LimitExceeded(limit) => write!(f, "vault exceeds limit: {limit:?}"),
        }
    }
}

impl std::error::Error for OpenError {}
