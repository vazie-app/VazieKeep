use std::fmt;
use std::str::FromStr;

use zeroize::Zeroize;

use crate::SecretString;

#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ItemId([u8; 16]);

impl ItemId {
    pub const fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    pub const fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }

    pub fn is_nil(&self) -> bool {
        self.0 == [0; 16]
    }
}

impl fmt::Display for ItemId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for (i, byte) in self.0.iter().enumerate() {
            if matches!(i, 4 | 6 | 8 | 10) {
                f.write_str("-")?;
            }
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

impl FromStr for ItemId {
    type Err = InvalidItemId;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        let bytes = text.as_bytes();
        if bytes.len() != 36 || [8, 13, 18, 23].iter().any(|&i| bytes[i] != b'-') {
            return Err(InvalidItemId);
        }
        let mut out = [0u8; 16];
        let mut digits = bytes.iter().filter(|&&b| b != b'-');
        for byte in &mut out {
            let hi = hex_value(*digits.next().ok_or(InvalidItemId)?)?;
            let lo = hex_value(*digits.next().ok_or(InvalidItemId)?)?;
            *byte = hi << 4 | lo;
        }
        if digits.next().is_some() {
            return Err(InvalidItemId);
        }
        Ok(Self(out))
    }
}

fn hex_value(digit: u8) -> Result<u8, InvalidItemId> {
    match digit {
        b'0'..=b'9' => Ok(digit - b'0'),
        b'a'..=b'f' => Ok(digit - b'a' + 10),
        b'A'..=b'F' => Ok(digit - b'A' + 10),
        _ => Err(InvalidItemId),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InvalidItemId;

impl fmt::Debug for ItemId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "ItemId({self})")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdbxVersion {
    pub major: u16,
    pub minor: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cipher {
    Aes256,
    ChaCha20,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kdf {
    AesKdf,
    Argon2d,
    Argon2id,
}

pub enum FieldValue {
    Plain(String),
    Protected(SecretString),
}

impl FieldValue {
    pub fn expose(&self) -> &str {
        match self {
            Self::Plain(value) => value,
            Self::Protected(secret) => secret.expose(),
        }
    }

    pub fn is_protected(&self) -> bool {
        matches!(self, Self::Protected(_))
    }

    pub fn is_empty(&self) -> bool {
        self.expose().is_empty()
    }
}

impl Zeroize for FieldValue {
    fn zeroize(&mut self) {
        if let Self::Plain(value) = self {
            value.zeroize();
        }
    }
}

impl Default for FieldValue {
    fn default() -> Self {
        Self::Plain(String::new())
    }
}

impl fmt::Debug for FieldValue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Plain(value) => write!(f, "Plain(<{} chars>)", value.chars().count()),
            Self::Protected(_) => f.write_str("Protected([REDACTED])"),
        }
    }
}

#[derive(Debug)]
pub struct EntryField {
    pub name: String,
    pub value: FieldValue,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Attachment {
    pub name: String,
    pub size: usize,
    /// Index into the vault's binary pool.
    pub(crate) reference: usize,
}

pub struct VaultEntry {
    pub id: ItemId,
    pub title: FieldValue,
    pub username: FieldValue,
    pub password: SecretString,
    pub url: FieldValue,
    pub notes: FieldValue,
    pub custom_fields: Vec<EntryField>,
    pub tags: Vec<String>,
    pub attachments: Vec<Attachment>,
    /// Unix seconds, present only when the entry is set to expire.
    pub expires: Option<i64>,
    /// A standard `KeePass` icon number; 0, the key, is what an entry has by default.
    pub icon: u32,
    /// An image kept in the file itself that stands in for the standard icon.
    pub custom_icon: Option<ItemId>,
    /// Unix seconds of the last change, when the file records one.
    pub modified: Option<i64>,
    /// Earlier versions, oldest first. A version has no history of its own.
    pub history: Vec<HistoryEntry>,
}

#[derive(Debug)]
pub struct HistoryEntry {
    /// Where the version sits among the entry's `History` items in the file.
    pub(crate) position: usize,
    pub entry: VaultEntry,
}

/// The tag Strongbox and other `KeePass` apps use to mark a favourite.
pub const FAVORITE_TAG: &str = "Favorite";

pub fn is_favorite_tag(tag: &str) -> bool {
    tag.eq_ignore_ascii_case("favorite") || tag.eq_ignore_ascii_case("favourite")
}

impl fmt::Debug for VaultEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultEntry")
            .field("id", &self.id)
            .field("custom_fields", &self.custom_fields.len())
            .field("attachments", &self.attachments.len())
            .finish_non_exhaustive()
    }
}

/// The standard `KeePass` icon a group has when nothing else is set: a folder.
pub const DEFAULT_GROUP_ICON: u32 = 48;
/// The last of the standard `KeePass` icons.
pub const MAX_STANDARD_ICON: u32 = 68;

pub struct VaultGroup {
    pub id: ItemId,
    pub name: String,
    /// A standard `KeePass` icon number. A custom image the file may also name is not read.
    pub icon: u32,
    pub notes: String,
    pub groups: Vec<VaultGroup>,
    pub entries: Vec<VaultEntry>,
}

impl VaultGroup {
    pub fn walk(&self) -> impl Iterator<Item = (Vec<&str>, &VaultGroup)> {
        let mut stack = vec![(Vec::new(), self)];
        std::iter::from_fn(move || {
            let (path, group) = stack.pop()?;
            for child in group.groups.iter().rev() {
                let mut child_path = path.clone();
                child_path.push(child.name.as_str());
                stack.push((child_path, child));
            }
            Some((path, group))
        })
    }
}

impl fmt::Debug for VaultGroup {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultGroup")
            .field("id", &self.id)
            .field("groups", &self.groups)
            .field("entries", &self.entries.len())
            .finish_non_exhaustive()
    }
}

pub struct VaultMetadata {
    pub format: KdbxVersion,
    pub cipher: Cipher,
    pub kdf: Kdf,
    pub name: String,
    pub description: String,
    pub generator: String,
    pub recycle_bin: Option<ItemId>,
}

impl fmt::Debug for VaultMetadata {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultMetadata")
            .field("format", &self.format)
            .field("cipher", &self.cipher)
            .field("kdf", &self.kdf)
            .field("recycle_bin", &self.recycle_bin)
            .finish_non_exhaustive()
    }
}

impl Drop for EntryField {
    fn drop(&mut self) {
        self.name.zeroize();
        self.value.zeroize();
    }
}

impl Drop for VaultEntry {
    fn drop(&mut self) {
        self.title.zeroize();
        self.username.zeroize();
        self.url.zeroize();
        self.notes.zeroize();
        self.tags.zeroize();
        for attachment in &mut self.attachments {
            attachment.name.zeroize();
        }
    }
}

impl Drop for VaultGroup {
    fn drop(&mut self) {
        self.name.zeroize();
        self.notes.zeroize();
    }
}

impl Drop for VaultMetadata {
    fn drop(&mut self) {
        self.name.zeroize();
        self.description.zeroize();
    }
}

pub struct Vault {
    pub metadata: VaultMetadata,
    pub root: VaultGroup,
    /// The lossless document and save state, so a session over this vault can be edited and
    /// written back.
    pub(crate) source: Option<Box<crate::kdbx::VaultSource>>,
}

impl fmt::Debug for Vault {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Vault")
            .field("metadata", &self.metadata)
            .field("root", &self.root)
            .field("editable", &self.source.is_some())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn item_id_formats_as_uuid() {
        let id = ItemId::from_bytes([
            0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab,
            0xcd, 0xef,
        ]);
        assert_eq!(id.to_string(), "12345678-9abc-def0-0123-456789abcdef");
    }

    #[test]
    fn item_id_round_trips_through_text() {
        let id = ItemId::from_bytes([0xab; 16]);
        assert_eq!(id.to_string().parse::<ItemId>(), Ok(id));
        assert_eq!(
            "ABABABAB-ABAB-ABAB-ABAB-ABABABABABAB".parse::<ItemId>(),
            Ok(id)
        );
        for bad in [
            "",
            "abababab",
            "abababababababababababababababababab",
            "zbababab-abab-abab-abab-abababababab",
            "abababab-abab-abab-abab-ababababab-b",
        ] {
            assert_eq!(bad.parse::<ItemId>(), Err(InvalidItemId), "{bad}");
        }
    }

    #[test]
    fn field_debug_hides_content() {
        let plain = FieldValue::Plain("alice@example.org".to_owned());
        let protected = FieldValue::Protected(SecretString::from("hunter2".to_owned()));
        let debug = format!("{plain:?} {protected:?}");
        assert!(!debug.contains("alice") && !debug.contains("hunter2"));
    }
}
