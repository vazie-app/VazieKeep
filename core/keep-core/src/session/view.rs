use std::fmt;

use zeroize::Zeroize;

use crate::model::{
    Cipher, FieldValue, HistoryEntry, ItemId, KdbxVersion, Kdf, VaultEntry, is_favorite_tag,
};
use crate::totp::TotpConfig;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum SecretField<'a> {
    Password,
    Title,
    UserName,
    Url,
    Notes,
    Custom(&'a str),
}

impl<'a> SecretField<'a> {
    pub fn from_kdbx_key(key: &'a str) -> Self {
        match key {
            "Password" => Self::Password,
            "Title" => Self::Title,
            "UserName" => Self::UserName,
            "URL" => Self::Url,
            "Notes" => Self::Notes,
            other => Self::Custom(other),
        }
    }
}

impl fmt::Debug for SecretField<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Custom(_) => f.write_str("Custom(..)"),
            Self::Password => f.write_str("Password"),
            Self::Title => f.write_str("Title"),
            Self::UserName => f.write_str("UserName"),
            Self::Url => f.write_str("Url"),
            Self::Notes => f.write_str("Notes"),
        }
    }
}

#[derive(Clone, PartialEq, Eq)]
pub enum VisibleText {
    Plain(String),
    Protected,
}

impl VisibleText {
    pub fn as_plain(&self) -> Option<&str> {
        match self {
            Self::Plain(text) => Some(text),
            Self::Protected => None,
        }
    }

    fn of(value: &FieldValue) -> Self {
        match value {
            FieldValue::Plain(text) => Self::Plain(text.clone()),
            FieldValue::Protected(_) => Self::Protected,
        }
    }
}

impl Zeroize for VisibleText {
    fn zeroize(&mut self) {
        if let Self::Plain(text) = self {
            text.zeroize();
        }
    }
}

impl fmt::Debug for VisibleText {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Plain(_) => f.write_str("Plain(..)"),
            Self::Protected => f.write_str("Protected"),
        }
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct VaultInfo {
    pub format: KdbxVersion,
    pub cipher: Cipher,
    pub kdf: Kdf,
    pub name: String,
    pub description: String,
    pub generator: String,
    pub root_group: ItemId,
    pub recycle_bin: Option<ItemId>,
    pub group_count: usize,
    pub entry_count: usize,
    pub fingerprint: String,
}

#[derive(Clone, PartialEq, Eq)]
pub struct GroupSummary {
    pub id: ItemId,
    pub parent: Option<ItemId>,
    pub name: String,
    /// A standard `KeePass` icon number (48 is the plain folder).
    pub icon: u32,
}

#[derive(Clone, PartialEq, Eq)]
pub struct CustomFieldInfo {
    pub name: String,
    pub protected: bool,
}

#[derive(Clone, PartialEq, Eq)]
#[allow(
    clippy::struct_excessive_bools,
    reason = "one flag per thing a row can show"
)]
pub struct EntrySummary {
    pub id: ItemId,
    pub group: ItemId,
    pub title: VisibleText,
    pub username: VisibleText,
    pub url: VisibleText,
    /// The entry's own tags; the favourite tag is reported as `favorite` instead.
    pub tags: Vec<String>,
    pub favorite: bool,
    pub expires: Option<i64>,
    /// A standard `KeePass` icon number; 0 is the default key.
    pub icon: u32,
    /// An image kept in the file that stands in for the standard icon.
    pub custom_icon: Option<ItemId>,
    pub has_password: bool,
    pub has_notes: bool,
    pub has_totp: bool,
    /// Without the fields a passkey is kept in: those are reported as `passkey`.
    pub custom_fields: Vec<CustomFieldInfo>,
    pub attachment_names: Vec<String>,
    pub passkey: Option<PasskeyInfo>,
}

/// The public side of a passkey an entry holds. Nothing here signs anything.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PasskeyInfo {
    /// The site the passkey signs in to (`example.com`).
    pub rp_id: String,
    pub user_name: String,
    /// base64url, as the site knows it.
    pub credential_id: String,
    /// base64url; empty when the entry does not record one.
    pub user_handle: String,
}

pub(super) fn passkey(entry: &VaultEntry) -> Option<PasskeyInfo> {
    use crate::passkey as names;
    let field = |name: &str| {
        entry
            .custom_fields
            .iter()
            .find(|f| f.name == name)
            .map(|f| f.value.expose())
    };
    field(names::FIELD_PRIVATE_KEY)?;
    let credential_id =
        field(names::FIELD_CREDENTIAL_ID).or_else(|| field(names::FIELD_LEGACY_CREDENTIAL_ID))?;
    Some(PasskeyInfo {
        rp_id: field(names::FIELD_RELYING_PARTY)?.to_owned(),
        user_name: field(names::FIELD_USERNAME).unwrap_or_default().to_owned(),
        credential_id: credential_id.trim_end_matches('=').to_owned(),
        user_handle: field(names::FIELD_USER_HANDLE)
            .unwrap_or_default()
            .trim_end_matches('=')
            .to_owned(),
    })
}

fn shown_fields(entry: &VaultEntry) -> impl Iterator<Item = &crate::model::EntryField> {
    entry
        .custom_fields
        .iter()
        .filter(|f| !f.name.starts_with(crate::passkey::FIELD_PREFIX))
}

impl EntrySummary {
    pub fn has_protected_fields(&self) -> bool {
        self.custom_fields.iter().any(|f| f.protected)
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct CustomField {
    pub name: String,
    pub value: VisibleText,
}

#[derive(Clone, PartialEq, Eq)]
pub struct AttachmentInfo {
    pub name: String,
    pub size: usize,
}

#[derive(Clone, PartialEq, Eq)]
pub struct EntryDetail {
    pub summary: EntrySummary,
    pub notes: VisibleText,
    pub custom_fields: Vec<CustomField>,
    pub attachments: Vec<AttachmentInfo>,
    /// Unix seconds of the last change, when the file records one.
    pub modified: Option<i64>,
    /// How many earlier versions the entry keeps. Reading them needs Keep+; the count does not.
    pub history_count: usize,
}

/// What an earlier version has different from the entry as it is now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChangedField {
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

#[derive(Clone, PartialEq, Eq)]
pub struct HistoryVersion {
    /// Names the version in `history_entry`, `reveal_history` and `restore_history`.
    pub index: usize,
    /// Unix seconds when this version was written.
    pub modified: Option<i64>,
    pub title: VisibleText,
    pub username: VisibleText,
    pub differs: Vec<ChangedField>,
}

fn own_tags(e: &VaultEntry) -> Vec<&str> {
    let mut tags: Vec<&str> = e
        .tags
        .iter()
        .filter(|t| !is_favorite_tag(t))
        .map(String::as_str)
        .collect();
    tags.sort_unstable();
    tags
}
fn attachment_refs(e: &VaultEntry) -> Vec<(&str, usize)> {
    let mut names: Vec<(&str, usize)> = e
        .attachments
        .iter()
        .map(|a| (a.name.as_str(), a.reference))
        .collect();
    names.sort_unstable();
    names
}

pub(super) fn version(current: &VaultEntry, item: &HistoryEntry) -> HistoryVersion {
    let old = &item.entry;
    let custom = |e: &VaultEntry| {
        let mut fields: Vec<(String, String, bool)> = e
            .custom_fields
            .iter()
            .map(|f| {
                (
                    f.name.clone(),
                    f.value.expose().to_owned(),
                    f.value.is_protected(),
                )
            })
            .collect();
        fields.sort();
        zeroize::Zeroizing::new(fields)
    };
    let favorite = |e: &VaultEntry| e.tags.iter().any(|t| is_favorite_tag(t));
    let checks = [
        (
            ChangedField::Title,
            old.title.expose() != current.title.expose(),
        ),
        (
            ChangedField::UserName,
            old.username.expose() != current.username.expose(),
        ),
        (
            ChangedField::Password,
            old.password.expose() != current.password.expose(),
        ),
        (ChangedField::Url, old.url.expose() != current.url.expose()),
        (
            ChangedField::Notes,
            old.notes.expose() != current.notes.expose(),
        ),
        (ChangedField::CustomFields, *custom(old) != *custom(current)),
        (ChangedField::Tags, own_tags(old) != own_tags(current)),
        (ChangedField::Favorite, favorite(old) != favorite(current)),
        (
            ChangedField::Attachments,
            attachment_refs(old) != attachment_refs(current),
        ),
        (ChangedField::Expiry, old.expires != current.expires),
    ];
    HistoryVersion {
        index: item.position,
        modified: old.modified,
        title: VisibleText::of(&old.title),
        username: VisibleText::of(&old.username),
        differs: checks
            .into_iter()
            .filter_map(|(field, changed)| changed.then_some(field))
            .collect(),
    }
}

pub(super) fn summary(group: ItemId, entry: &VaultEntry) -> EntrySummary {
    EntrySummary {
        id: entry.id,
        group,
        title: VisibleText::of(&entry.title),
        username: VisibleText::of(&entry.username),
        url: VisibleText::of(&entry.url),
        tags: entry
            .tags
            .iter()
            .filter(|t| !is_favorite_tag(t))
            .cloned()
            .collect(),
        favorite: entry.tags.iter().any(|t| is_favorite_tag(t)),
        expires: entry.expires,
        icon: entry.icon,
        custom_icon: entry.custom_icon,
        has_password: !entry.password.is_empty(),
        has_notes: !entry.notes.is_empty(),
        has_totp: matches!(TotpConfig::from_fields(&entry.custom_fields), Some(Ok(_))),
        custom_fields: shown_fields(entry)
            .map(|f| CustomFieldInfo {
                name: f.name.clone(),
                protected: f.value.is_protected(),
            })
            .collect(),
        attachment_names: entry.attachments.iter().map(|a| a.name.clone()).collect(),
        passkey: passkey(entry),
    }
}

pub(super) fn detail(group: ItemId, entry: &VaultEntry) -> EntryDetail {
    EntryDetail {
        summary: summary(group, entry),
        notes: VisibleText::of(&entry.notes),
        custom_fields: shown_fields(entry)
            .map(|f| CustomField {
                name: f.name.clone(),
                value: VisibleText::of(&f.value),
            })
            .collect(),
        attachments: entry
            .attachments
            .iter()
            .map(|a| AttachmentInfo {
                name: a.name.clone(),
                size: a.size,
            })
            .collect(),
        modified: entry.modified,
        history_count: entry.history.len(),
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
    VaultInfo => format, cipher, kdf, root_group, group_count, entry_count;
    GroupSummary => id, parent;
    CustomFieldInfo => protected;
    EntrySummary => id, group, title, username, url, favorite, expires, has_password, has_notes, has_totp;
    CustomField => value;
    EntryDetail => summary, notes, modified, history_count;
    HistoryVersion => index, modified, differs;
    AttachmentInfo => size;
}
