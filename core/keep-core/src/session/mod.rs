mod store;
mod view;

use std::collections::{BTreeSet, HashMap, HashSet};
use std::fmt;
use std::fmt::Write as _;

pub use store::{SessionId, SharedSession, VaultSessionStore};
pub use view::{
    AttachmentInfo, ChangedField, CustomField, CustomFieldInfo, EntryDetail, EntrySummary,
    GroupSummary, HistoryVersion, PasskeyInfo, SecretField, VaultInfo, VisibleText,
};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use crate::credentials::UnlockMaterial;
use crate::entitlement::{Access, PlusFeature, can_use};
use crate::error::OpenError;
use crate::import::ImportPlan;
use crate::kdbx::dom::Element;
use crate::kdbx::edit::{
    CustomFieldDraft, DeleteOutcome, EditError, Editor, EntryDraft, StoredPasskey,
};
use crate::kdbx::merge::{MergeError, MergeOptions, MergeReport, merge};
use crate::kdbx::{VaultSource, WriteError, xml};
use crate::limits::OpenLimits;
use crate::model::{
    FieldValue, ItemId, Vault, VaultEntry, VaultGroup, VaultMetadata, is_favorite_tag,
};
use crate::passkey::PasskeyError;
use crate::secret::{SecretBytes, SecretString};
use crate::totp::{TotpCode, TotpConfig, TotpError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum TotpLookupError {
    Session(SessionError),
    NotEntitled,
    NoCode,
    Invalid(TotpError),
}

impl fmt::Display for TotpLookupError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Session(error) => error.fmt(f),
            Self::NotEntitled => f.write_str("one-time codes need Keep+"),
            Self::NoCode => f.write_str("the entry has no one-time code"),
            Self::Invalid(error) => error.fmt(f),
        }
    }
}

impl std::error::Error for TotpLookupError {}

impl From<SessionError> for TotpLookupError {
    fn from(error: SessionError) -> Self {
        Self::Session(error)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum HistoryError {
    Session(SessionError),
    NotEntitled,
    VersionNotFound,
}

impl fmt::Display for HistoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Session(error) => error.fmt(f),
            Self::NotEntitled => f.write_str("entry history needs Keep+"),
            Self::VersionNotFound => f.write_str("no such earlier version"),
        }
    }
}

impl std::error::Error for HistoryError {}

impl From<SessionError> for HistoryError {
    fn from(error: SessionError) -> Self {
        Self::Session(error)
    }
}

/// Why [`VaultSession::merge_with_session_key`] did not merge.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionKeyMergeError {
    /// The second file did not open; `OpenError::InvalidCredentials` means the session's key does not fit it.
    Open(OpenError),
    Merge(MergeError),
}

impl From<MergeError> for SessionKeyMergeError {
    fn from(error: MergeError) -> Self {
        Self::Merge(error)
    }
}

impl fmt::Display for SessionKeyMergeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Open(error) => write!(f, "{error:?}"),
            Self::Merge(error) => fmt::Display::fmt(error, f),
        }
    }
}

impl std::error::Error for SessionKeyMergeError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum SessionError {
    InvalidHandle,
    SessionNotFound,
    SessionClosed,
    EntryNotFound,
    FieldNotFound,
    TooManySessions,
    RandomUnavailable,
}

impl fmt::Display for SessionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidHandle => "malformed session handle",
            Self::SessionNotFound => "no such vault session",
            Self::SessionClosed => "vault session is closed",
            Self::EntryNotFound => "no such entry",
            Self::FieldNotFound => "no such field",
            Self::TooManySessions => "too many open vault sessions",
            Self::RandomUnavailable => "system random source unavailable",
        })
    }
}

impl std::error::Error for SessionError {}

struct StoredEntry {
    group: ItemId,
    entry: VaultEntry,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum SaveError {
    /// Opened from something this crate can't write back.
    ReadOnly,
    RandomUnavailable,
    EncryptionFailed,
    /// The encrypted bytes didn't read back as the same vault; nothing may be written.
    VerificationFailed,
}

impl fmt::Display for SaveError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::ReadOnly => "vault is read-only",
            Self::RandomUnavailable => "system random source unavailable",
            Self::EncryptionFailed => "vault could not be encrypted",
            Self::VerificationFailed => "written vault did not verify",
        })
    }
}

impl std::error::Error for SaveError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ImportOutcome {
    pub entries: usize,
    /// Groups made for the export's folders.
    pub groups: usize,
}

/// What a site asks a new passkey to be made for.
#[derive(Debug, Clone, Copy)]
pub struct PasskeyRegistration<'a> {
    pub rp_id: &'a str,
    pub rp_name: &'a str,
    pub user_name: &'a str,
    /// The site's id for the account, as bytes.
    pub user_handle: &'a [u8],
}

/// A passkey that was made and saved: the entry it is in, and what the site gets back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CreatedPasskey {
    pub entry: ItemId,
    pub credential_id: Vec<u8>,
    /// `SubjectPublicKeyInfo` DER.
    pub public_key: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    pub attestation_object: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PasskeyAssertion {
    pub credential_id: Vec<u8>,
    pub user_handle: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PasskeyCreateError {
    Passkey(PasskeyError),
    Edit(EditError),
}

impl fmt::Display for PasskeyCreateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Passkey(error) => error.fmt(f),
            Self::Edit(error) => error.fmt(f),
        }
    }
}

impl std::error::Error for PasskeyCreateError {}

/// Encrypted KDBX bytes ready for the platform to write, and the edit generation they contain.
pub struct SaveSnapshot {
    pub bytes: Vec<u8>,
    pub generation: u64,
}

impl fmt::Debug for SaveSnapshot {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SaveSnapshot")
            .field("bytes", &self.bytes.len())
            .field("generation", &self.generation)
            .finish()
    }
}

pub struct VaultSession {
    metadata: VaultMetadata,
    root: ItemId,
    groups: Vec<GroupSummary>,
    entries: Vec<StoredEntry>,
    index: HashMap<ItemId, usize>,
    unlock_material: Option<UnlockMaterial>,
    source: Option<Box<VaultSource>>,
    generation: u64,
    saved_generation: u64,
}

struct Projection {
    groups: Vec<GroupSummary>,
    entries: Vec<StoredEntry>,
    index: HashMap<ItemId, usize>,
}

fn flatten(root: VaultGroup) -> Result<Projection, OpenError> {
    let mut groups = Vec::new();
    let mut entries = Vec::new();
    let mut group_ids = HashSet::new();
    let mut stack: Vec<(Option<ItemId>, VaultGroup)> = vec![(None, root)];
    while let Some((parent, mut group)) = stack.pop() {
        if !group_ids.insert(group.id) {
            return Err(OpenError::CorruptedVault);
        }
        groups.push(GroupSummary {
            id: group.id,
            parent,
            name: std::mem::take(&mut group.name),
            icon: group.icon,
        });
        for entry in std::mem::take(&mut group.entries) {
            entries.push(StoredEntry {
                group: group.id,
                entry,
            });
        }
        for child in std::mem::take(&mut group.groups).into_iter().rev() {
            stack.push((Some(group.id), child));
        }
    }
    let mut index = HashMap::with_capacity(entries.len());
    for (position, stored) in entries.iter().enumerate() {
        if index.insert(stored.entry.id, position).is_some() {
            return Err(OpenError::CorruptedVault);
        }
    }
    Ok(Projection {
        groups,
        entries,
        index,
    })
}

/// The fingerprint of a database whose root group has this id.
pub fn fingerprint_of(root: ItemId) -> String {
    Sha256::new()
        .chain_update(b"vazie-keep/vault-fingerprint/v1")
        .chain_update(root.as_bytes())
        .finalize()
        .iter()
        .fold(String::with_capacity(64), |mut out, b| {
            let _ = write!(out, "{b:02x}");
            out
        })
}

impl VaultSession {
    pub fn new(vault: Vault) -> Result<Self, OpenError> {
        let Vault {
            metadata,
            root,
            source,
        } = vault;
        let root_id = root.id;
        let Projection {
            groups,
            entries,
            index,
        } = flatten(root)?;
        Ok(Self {
            metadata,
            root: root_id,
            groups,
            entries,
            index,
            unlock_material: None,
            source,
            generation: 0,
            saved_generation: 0,
        })
    }

    pub fn is_editable(&self) -> bool {
        self.source.is_some()
    }

    /// True after an edit that hasn't been written yet.
    pub fn is_dirty(&self) -> bool {
        self.generation != self.saved_generation
    }

    /// A new entry with an address starts with the built-in icon that suits it, picked offline.
    pub fn create_entry(&mut self, group: ItemId, draft: &EntryDraft) -> Result<ItemId, EditError> {
        self.edit(|editor| {
            let id = editor.create_entry(group, draft)?;
            if let Some(icon) = crate::icons::icon_for_url(&draft.url) {
                editor.assign_entry_icon(id, icon)?;
            }
            Ok((id, true))
        })
    }

    /// An entry that still has the default key gets a built-in icon once it has an address.
    pub fn update_entry(&mut self, id: ItemId, draft: &EntryDraft) -> Result<(), EditError> {
        self.edit(|editor| {
            let changed = editor.update_entry(id, draft)?;
            if changed && let Some(icon) = crate::icons::icon_for_url(&draft.url) {
                editor.assign_entry_icon(id, icon)?;
            }
            Ok(((), changed))
        })
    }

    /// Makes a passkey for a site and keeps it in a new entry of `group`, titled after the site.
    /// What goes back to the site is in the answer; the private key never leaves the session.
    pub fn create_passkey(
        &mut self,
        group: ItemId,
        request: &PasskeyRegistration<'_>,
    ) -> Result<CreatedPasskey, PasskeyCreateError> {
        if request.user_handle.is_empty() || request.user_handle.len() > 64 {
            return Err(PasskeyCreateError::Passkey(PasskeyError::InvalidRequest));
        }
        let made = crate::passkey::generate(request.rp_id).map_err(PasskeyCreateError::Passkey)?;
        let credential_id = crate::passkey::encode_id(&made.credential_id);
        let user_handle = crate::passkey::encode_id(request.user_handle);
        let title = if request.rp_name.trim().is_empty() {
            request.rp_id
        } else {
            request.rp_name.trim()
        };
        let draft = EntryDraft {
            title: title.to_owned(),
            username: request.user_name.to_owned(),
            password: SecretString::new(String::new()),
            url: format!("https://{}", request.rp_id),
            notes: String::new(),
            custom_fields: Vec::new(),
            tags: Vec::new(),
            favorite: false,
            expires: None,
        };
        let entry = self
            .edit(|editor| {
                let id = editor.create_entry(group, &draft)?;
                if let Some(icon) = crate::icons::icon_for_url(&draft.url) {
                    editor.assign_entry_icon(id, icon)?;
                }
                editor.set_passkey(
                    id,
                    &StoredPasskey {
                        rp_id: request.rp_id,
                        user_name: request.user_name,
                        user_handle: &user_handle,
                        credential_id: &credential_id,
                        private_key_pem: &made.private_key_pem,
                    },
                )?;
                Ok((id, true))
            })
            .map_err(PasskeyCreateError::Edit)?;
        Ok(CreatedPasskey {
            entry,
            credential_id: made.credential_id,
            public_key: made.public_key,
            authenticator_data: made.authenticator_data,
            attestation_object: made.attestation_object,
        })
    }

    /// The passkeys of the vault, for one site or for all of them. Entries in the recycle bin are
    /// left out: a deleted passkey does not sign in.
    pub fn passkeys(&self, rp_id: Option<&str>) -> Vec<(EntrySummary, PasskeyInfo)> {
        self.entries
            .iter()
            .filter(|stored| !self.in_recycle_bin(stored.group))
            .filter_map(|stored| {
                let info = view::passkey(&stored.entry)?;
                if rp_id.is_some_and(|wanted| wanted != info.rp_id) {
                    return None;
                }
                Some((view::summary(stored.group, &stored.entry), info))
            })
            .collect()
    }

    /// Signs a sign-in request with the entry's passkey. `rp_id` must be the site the passkey was
    /// made for: a key never signs for another site, whatever the caller says.
    pub fn sign_passkey(
        &self,
        id: ItemId,
        rp_id: &str,
        client_data_hash: &[u8],
    ) -> Result<PasskeyAssertion, PasskeyError> {
        let stored = self.find(id).map_err(|_| PasskeyError::NoPasskey)?;
        if self.in_recycle_bin(stored.group) {
            return Err(PasskeyError::NoPasskey);
        }
        let entry = &stored.entry;
        let info = view::passkey(entry).ok_or(PasskeyError::NoPasskey)?;
        if info.rp_id != rp_id {
            return Err(PasskeyError::InvalidRequest);
        }
        let field = |name: &str| {
            entry
                .custom_fields
                .iter()
                .find(|f| f.name == name)
                .map(|f| f.value.expose())
        };
        let flag =
            |name: &str| field(name).is_none_or(|v| v == "1" || v.eq_ignore_ascii_case("true"));
        let pem = field(crate::passkey::FIELD_PRIVATE_KEY).ok_or(PasskeyError::NoPasskey)?;
        let assertion = crate::passkey::assert(
            pem,
            rp_id,
            client_data_hash,
            flag(crate::passkey::FIELD_BACKUP_ELIGIBLE),
            flag(crate::passkey::FIELD_BACKUP_STATE),
        )?;
        Ok(PasskeyAssertion {
            credential_id: crate::passkey::decode_id(&info.credential_id)
                .ok_or(PasskeyError::InvalidKey)?,
            user_handle: crate::passkey::decode_id(&info.user_handle).unwrap_or_default(),
            authenticator_data: assertion.authenticator_data,
            signature: assertion.signature,
        })
    }

    /// Takes the passkey out of an entry; the entry stays, and the version with the passkey goes to
    /// its history.
    pub fn remove_passkey(&mut self, id: ItemId) -> Result<(), EditError> {
        self.edit(|editor| editor.remove_passkey(id).map(|removed| ((), removed)))
    }

    fn in_recycle_bin(&self, group: ItemId) -> bool {
        let Some(bin) = self.metadata.recycle_bin else {
            return false;
        };
        let mut current = Some(group);
        while let Some(id) = current {
            if id == bin {
                return true;
            }
            current = self
                .groups
                .iter()
                .find(|g| g.id == id)
                .and_then(|g| g.parent);
        }
        false
    }

    /// The site to ask for this entry's icon: none when it already has an image or its address is
    /// not one of a website.
    pub fn site_icon_host(&self, id: ItemId) -> Option<String> {
        let entry = &self.find(id).ok()?.entry;
        if entry.custom_icon.is_some() {
            return None;
        }
        crate::icons::site_host(entry.url.expose())
    }

    /// Adding needs Keep+; reading and removing never do. Returns the name it was stored under.
    pub fn add_attachment(
        &mut self,
        id: ItemId,
        name: &str,
        data: &[u8],
        access: &Access,
    ) -> Result<String, EditError> {
        if !can_use(PlusFeature::AttachmentWrite, access) {
            return Err(EditError::NotEntitled);
        }
        self.edit(|editor| {
            editor
                .add_attachment(id, name, data)
                .map(|name| (name, true))
        })
    }

    pub fn remove_attachment(&mut self, id: ItemId, name: &str) -> Result<(), EditError> {
        self.edit(|editor| editor.remove_attachment(id, name).map(|()| ((), true)))
    }

    pub fn attachment(&self, id: ItemId, name: &str) -> Result<SecretBytes, SessionError> {
        let entry = &self.find(id)?.entry;
        let attachment = entry
            .attachments
            .iter()
            .find(|a| a.name == name)
            .ok_or(SessionError::FieldNotFound)?;
        let source = self.source.as_ref().ok_or(SessionError::FieldNotFound)?;
        let stored = source
            .save
            .binaries
            .get(attachment.reference)
            .ok_or(SessionError::FieldNotFound)?;
        Ok(SecretBytes::from(stored[1..].to_vec()))
    }

    /// Takes in every entry of an export, under `group`. A folder of the export becomes a group of
    /// the same name (an existing one is reused). Nothing is merged or replaced: importing the same
    /// file twice gives two sets of entries. All or nothing — a failure leaves the vault as it was.
    pub fn import(&mut self, group: ItemId, plan: &ImportPlan) -> Result<ImportOutcome, EditError> {
        self.edit(|editor| {
            let mut folders: HashMap<Vec<String>, ItemId> = HashMap::new();
            let mut created = 0;
            for entry in &plan.entries {
                let mut target = group;
                for depth in 1..=entry.folder.len() {
                    let path = &entry.folder[..depth];
                    target = if let Some(known) = folders.get(path) {
                        *known
                    } else {
                        let name = &entry.folder[depth - 1];
                        let id = if let Some(existing) = editor.child_group(target, name)? {
                            existing
                        } else {
                            created += 1;
                            editor.create_group(target, name)?
                        };
                        folders.insert(path.to_vec(), id);
                        id
                    };
                }
                let draft = EntryDraft {
                    title: entry.title.clone(),
                    username: entry.username.clone(),
                    password: SecretString::new(entry.password.expose().to_owned()),
                    url: entry.url.clone(),
                    notes: entry.notes.clone(),
                    custom_fields: entry
                        .fields
                        .iter()
                        .map(|f| CustomFieldDraft {
                            name: f.name.clone(),
                            value: SecretString::new(f.value.expose().to_owned()),
                            protected: f.protected,
                        })
                        .collect(),
                    tags: entry.tags.clone(),
                    favorite: entry.favorite,
                    expires: None,
                };
                let id = editor.create_entry(target, &draft)?;
                if let Some(icon) = crate::icons::icon_for_url(&entry.url) {
                    editor.assign_entry_icon(id, icon)?;
                }
            }
            let outcome = ImportOutcome {
                entries: plan.entries.len(),
                groups: created,
            };
            Ok((outcome, !plan.entries.is_empty()))
        })
    }

    /// Earlier versions of an entry, newest first. Keeping history is free and always on; reading
    /// it and going back to a version need Keep+.
    pub fn history(
        &self,
        id: ItemId,
        access: &Access,
    ) -> Result<Vec<HistoryVersion>, HistoryError> {
        let entry = self.history_of(id, access)?;
        Ok(entry
            .history
            .iter()
            .rev()
            .map(|item| view::version(entry, item))
            .collect())
    }

    pub fn history_entry(
        &self,
        id: ItemId,
        index: usize,
        access: &Access,
    ) -> Result<EntryDetail, HistoryError> {
        let group = self.find(id)?.group;
        Ok(view::detail(group, self.version(id, index, access)?))
    }

    pub fn reveal_history(
        &self,
        id: ItemId,
        index: usize,
        field: SecretField<'_>,
        access: &Access,
    ) -> Result<SecretString, HistoryError> {
        Ok(reveal_in(self.version(id, index, access)?, field)?)
    }

    pub fn restore_history(
        &mut self,
        id: ItemId,
        index: usize,
        access: &Access,
    ) -> Result<(), EditError> {
        if !can_use(PlusFeature::HistoryUi, access) {
            return Err(EditError::NotEntitled);
        }
        self.edit(|editor| editor.restore_version(id, index).map(|()| ((), true)))
    }

    fn history_of(&self, id: ItemId, access: &Access) -> Result<&VaultEntry, HistoryError> {
        if !can_use(PlusFeature::HistoryUi, access) {
            return Err(HistoryError::NotEntitled);
        }
        Ok(&self.find(id)?.entry)
    }

    fn version(
        &self,
        id: ItemId,
        index: usize,
        access: &Access,
    ) -> Result<&VaultEntry, HistoryError> {
        self.history_of(id, access)?
            .history
            .iter()
            .find(|item| item.position == index)
            .map(|item| &item.entry)
            .ok_or(HistoryError::VersionNotFound)
    }

    pub fn set_favorite(&mut self, id: ItemId, favorite: bool) -> Result<(), EditError> {
        self.edit(|editor| {
            editor
                .set_favorite(id, favorite)
                .map(|changed| ((), changed))
        })
    }

    pub fn delete_entry(
        &mut self,
        id: ItemId,
        permanent: bool,
    ) -> Result<DeleteOutcome, EditError> {
        self.edit(|editor| {
            editor
                .delete_entry(id, permanent)
                .map(|outcome| (outcome, true))
        })
    }

    pub fn move_entry(&mut self, id: ItemId, group: ItemId) -> Result<(), EditError> {
        self.edit(|editor| editor.move_entry(id, group).map(|moved| ((), moved)))
    }

    pub fn create_group(&mut self, parent: ItemId, name: &str) -> Result<ItemId, EditError> {
        self.edit(|editor| editor.create_group(parent, name).map(|id| (id, true)))
    }

    pub fn rename_group(&mut self, id: ItemId, name: &str) -> Result<(), EditError> {
        self.edit(|editor| editor.rename_group(id, name).map(|renamed| ((), renamed)))
    }

    /// Goes over every entry that still has the default key and gives it an icon by its address.
    /// Entries with an icon of their own, and entries without an address, are left as they are.
    /// Returns how many entries got an icon.
    pub fn assign_icons_by_url(&mut self) -> Result<usize, EditError> {
        let wanted: Vec<(ItemId, u32)> = self
            .entries
            .iter()
            .filter(|stored| stored.entry.icon == 0)
            .filter_map(|stored| {
                crate::icons::icon_for_url(stored.entry.url.expose())
                    .map(|icon| (stored.entry.id, icon))
            })
            .collect();
        self.edit(|editor| {
            let mut changed = 0;
            for (id, icon) in &wanted {
                if editor.assign_entry_icon(*id, *icon)? {
                    changed += 1;
                }
            }
            Ok((changed, changed > 0))
        })
    }

    /// The sites whose icons are worth fetching: the host of every entry that has an address and no
    /// image of its own, each once, in alphabetical order.
    pub fn site_icon_hosts(&self) -> Vec<String> {
        let hosts: BTreeSet<String> = self
            .entries
            .iter()
            .filter(|stored| stored.entry.custom_icon.is_none())
            .filter_map(|stored| crate::icons::site_host(stored.entry.url.expose()))
            .collect();
        hosts.into_iter().collect()
    }

    /// Keeps the fetched site icons (PNG) in the file and gives each to the entries of its host that
    /// have no image yet. An image that is not a PNG, or is too large, is skipped. Returns how many
    /// entries got an image.
    pub fn set_site_icons(&mut self, icons: &[(String, Vec<u8>)]) -> Result<usize, EditError> {
        let wanted: Vec<(ItemId, usize)> = self
            .entries
            .iter()
            .filter(|stored| stored.entry.custom_icon.is_none())
            .filter_map(|stored| {
                let host = crate::icons::site_host(stored.entry.url.expose())?;
                let position = icons.iter().position(|(known, _)| *known == host)?;
                Some((stored.entry.id, position))
            })
            .collect();
        self.edit(|editor| {
            let mut images: HashMap<usize, Option<ItemId>> = HashMap::new();
            let mut changed = 0;
            for (id, position) in &wanted {
                let image = if let Some(known) = images.get(position) {
                    *known
                } else {
                    let added = match editor.add_custom_icon(&icons[*position].1) {
                        Ok(image) => Some(image),
                        Err(EditError::InvalidIcon) => None,
                        Err(error) => return Err(error),
                    };
                    images.insert(*position, added);
                    added
                };
                if let Some(image) = image
                    && editor.assign_entry_image(*id, image)?
                {
                    changed += 1;
                }
            }
            Ok((changed, changed > 0))
        })
    }

    /// The bytes (PNG, as a rule) of an image kept in the file.
    pub fn custom_icon(&self, id: ItemId) -> Option<Vec<u8>> {
        self.source
            .as_ref()?
            .document
            .child("Meta")?
            .child("CustomIcons")?
            .elements()
            .filter(|icon| icon.name == "Icon")
            .find(|icon| icon.child("UUID").and_then(|e| xml::parse_uuid(e).ok()) == Some(id))
            .and_then(|icon| icon.child("Data")?.text())
            .and_then(|data| STANDARD.decode(data.trim()).ok())
    }

    /// Gives the entry one of the standard `KeePass` icons. The entry as it was goes to its history.
    pub fn set_entry_icon(&mut self, id: ItemId, icon: u32) -> Result<(), EditError> {
        self.edit(|editor| editor.set_entry_icon(id, icon).map(|changed| ((), changed)))
    }

    /// Gives the group one of the standard `KeePass` icons; other apps show their own drawing of it.
    pub fn set_group_icon(&mut self, id: ItemId, icon: u32) -> Result<(), EditError> {
        self.edit(|editor| editor.set_group_icon(id, icon).map(|changed| ((), changed)))
    }

    pub fn delete_group(
        &mut self,
        id: ItemId,
        recursive: bool,
        permanent: bool,
    ) -> Result<DeleteOutcome, EditError> {
        self.edit(|editor| {
            editor
                .delete_group(id, recursive, permanent)
                .map(|outcome| (outcome, true))
        })
    }

    /// Encrypts the current state for the platform to write, verified by reading it back.
    /// The session stays dirty until `mark_saved` confirms the write.
    pub fn prepare_save(&self) -> Result<SaveSnapshot, SaveError> {
        let source = self.source.as_ref().ok_or(SaveError::ReadOnly)?;
        let bytes = source
            .write_verified(&OpenLimits::default())
            .map_err(|error| match error {
                WriteError::RandomUnavailable => SaveError::RandomUnavailable,
                WriteError::EncryptionFailed => SaveError::EncryptionFailed,
                WriteError::VerificationFailed => SaveError::VerificationFailed,
            })?;
        Ok(SaveSnapshot {
            bytes,
            generation: self.generation,
        })
    }

    /// The platform wrote a snapshot. Clean only if nothing changed since it was taken.
    pub fn mark_saved(&mut self, generation: u64) -> bool {
        if generation > self.saved_generation && generation <= self.generation {
            self.saved_generation = generation;
        }
        !self.is_dirty()
    }

    /// Applies an edit to a copy of the document and keeps it only if the result still reads as
    /// a valid vault, so a failed edit never leaves a half-changed session.
    fn edit<T>(
        &mut self,
        body: impl FnOnce(&mut Editor<'_>) -> Result<(T, bool), EditError>,
    ) -> Result<T, EditError> {
        let source = self.source.as_mut().ok_or(EditError::ReadOnly)?;
        let mut working = source.document.duplicate();
        let mut binaries = source.save.binaries.clone();
        let (value, changed) = body(&mut Editor::with_binaries(
            &mut working,
            &mut binaries,
            source.save.version.minor,
        ))?;
        if !changed {
            return Ok(value);
        }
        self.install(working, binaries)
            .map_err(|()| EditError::InvalidText)?;
        Ok(value)
    }

    /// Makes a changed copy of the document the session's own, if it still reads as a vault.
    fn install(&mut self, working: Element, binaries: Vec<Zeroizing<Vec<u8>>>) -> Result<(), ()> {
        let source = self.source.as_mut().ok_or(())?;
        let sizes: Vec<usize> = binaries.iter().map(|b| b.len().saturating_sub(1)).collect();
        let (metadata, root) =
            xml::project(&working, &sizes, &OpenLimits::default()).map_err(|_| ())?;
        let root_id = root.id;
        let Projection {
            groups,
            entries,
            index,
        } = flatten(root).map_err(|_| ())?;
        source.document = working;
        source.save.binaries = binaries;
        self.metadata.name = metadata.name;
        self.metadata.description = metadata.description;
        self.metadata.recycle_bin = metadata.recycle_bin;
        self.root = root_id;
        self.groups = groups;
        self.entries = entries;
        self.index = index;
        self.generation += 1;
        Ok(())
    }

    /// Combines another version of this database into the open one (ADR 0023). `other` is the
    /// second file, opened with its own credentials. Nothing is written: the session is dirty if
    /// anything changed, and the platform saves it like any edit. A failure changes nothing.
    pub fn merge(
        &mut self,
        other: Vault,
        options: &MergeOptions,
    ) -> Result<MergeReport, MergeError> {
        let remote = other.source.ok_or(MergeError::RemoteNotReadable)?;
        let source = self.source.as_ref().ok_or(MergeError::ReadOnly)?;
        let mut working = source.document.duplicate();
        let mut binaries = source.save.binaries.clone();
        let report = merge(
            &mut working,
            &mut binaries,
            &remote.document,
            &remote.save.binaries,
            source.save.version.minor,
            options,
        )?;
        if report.changed() {
            self.install(working, binaries)
                .map_err(|()| MergeError::InvalidVault)?;
        }
        Ok(report)
    }

    /// [`merge`](Self::merge) for a second file that the session's own key opens, so the person types
    /// nothing: the file of this database, as another device running this core saved it. Fails with
    /// `OpenError::InvalidCredentials` when the key does not fit (another program rewrote the file with
    /// new key derivation parameters, or the password changed); then the platform asks for the password
    /// and uses `merge`.
    pub fn merge_with_session_key(
        &mut self,
        data: &[u8],
        options: &MergeOptions,
    ) -> Result<MergeReport, SessionKeyMergeError> {
        let other = {
            let source = self.source.as_ref().ok_or(MergeError::ReadOnly)?;
            crate::kdbx::open_with_transformed_key(
                data,
                &source.save.transformed_key,
                &OpenLimits::default(),
            )
            .map_err(SessionKeyMergeError::Open)?
        };
        self.merge(other, options)
            .map_err(SessionKeyMergeError::Merge)
    }

    pub fn retain_unlock_material(&mut self, material: UnlockMaterial) {
        self.unlock_material = Some(material);
    }

    pub fn unlock_material(&self) -> Option<&UnlockMaterial> {
        self.unlock_material.as_ref()
    }

    /// Stable across saves of the same database (the root group UUID never changes), different
    /// for every other database, and derived from nothing secret.
    pub fn fingerprint(&self) -> String {
        fingerprint_of(self.root)
    }

    pub fn info(&self) -> VaultInfo {
        let meta = &self.metadata;
        VaultInfo {
            format: meta.format,
            cipher: meta.cipher,
            kdf: meta.kdf,
            name: meta.name.clone(),
            description: meta.description.clone(),
            generator: meta.generator.clone(),
            root_group: self.root,
            recycle_bin: meta.recycle_bin,
            group_count: self.groups.len(),
            entry_count: self.entries.len(),
            fingerprint: self.fingerprint(),
        }
    }

    pub fn groups(&self) -> &[GroupSummary] {
        &self.groups
    }

    pub fn entries(&self) -> impl Iterator<Item = EntrySummary> + '_ {
        self.entries
            .iter()
            .map(|stored| view::summary(stored.group, &stored.entry))
    }

    pub fn entry(&self, id: ItemId) -> Result<EntryDetail, SessionError> {
        let stored = self.find(id)?;
        Ok(view::detail(stored.group, &stored.entry))
    }

    /// Plain words match the title, username, URL or a tag; `tag:name` keeps only entries with
    /// that tag. Every term must match.
    pub fn search(&self, query: &str) -> Vec<EntrySummary> {
        let terms: Vec<String> = query.split_whitespace().map(str::to_lowercase).collect();
        if terms.is_empty() {
            return self.entries().collect();
        }
        let has_tag = |entry: &VaultEntry, tag: &str| {
            entry
                .tags
                .iter()
                .any(|t| !is_favorite_tag(t) && t.to_lowercase() == tag)
        };
        let matches = |entry: &VaultEntry, term: &str| {
            if let Some(tag) = term.strip_prefix("tag:") {
                return tag.is_empty() || has_tag(entry, tag);
            }
            let contains = |value: &FieldValue| match value {
                FieldValue::Plain(text) => text.to_lowercase().contains(term),
                FieldValue::Protected(_) => false,
            };
            contains(&entry.title)
                || contains(&entry.username)
                || contains(&entry.url)
                || entry
                    .tags
                    .iter()
                    .any(|t| !is_favorite_tag(t) && t.to_lowercase().contains(term))
        };
        self.entries
            .iter()
            .filter(|s| terms.iter().all(|term| matches(&s.entry, term)))
            .map(|s| view::summary(s.group, &s.entry))
            .collect()
    }

    pub fn totp(
        &self,
        id: ItemId,
        unix_seconds: u64,
        access: &Access,
    ) -> Result<TotpCode, TotpLookupError> {
        if !can_use(PlusFeature::Totp, access) {
            return Err(TotpLookupError::NotEntitled);
        }
        let entry = &self.find(id)?.entry;
        match TotpConfig::from_fields(&entry.custom_fields) {
            None => Err(TotpLookupError::NoCode),
            Some(Err(error)) => Err(TotpLookupError::Invalid(error)),
            Some(Ok(config)) => Ok(config.code_at(unix_seconds)),
        }
    }

    pub fn reveal(&self, id: ItemId, field: SecretField<'_>) -> Result<SecretString, SessionError> {
        reveal_in(&self.find(id)?.entry, field)
    }

    fn find(&self, id: ItemId) -> Result<&StoredEntry, SessionError> {
        let position = *self.index.get(&id).ok_or(SessionError::EntryNotFound)?;
        self.entries
            .get(position)
            .ok_or(SessionError::EntryNotFound)
    }
}

fn reveal_in(entry: &VaultEntry, field: SecretField<'_>) -> Result<SecretString, SessionError> {
    {
        let value = match field {
            SecretField::Password => {
                return Ok(SecretString::new(entry.password.expose().to_owned()));
            }
            SecretField::Title => &entry.title,
            SecretField::UserName => &entry.username,
            SecretField::Url => &entry.url,
            SecretField::Notes => &entry.notes,
            SecretField::Custom(name) => {
                &entry
                    .custom_fields
                    .iter()
                    .find(|f| f.name == name)
                    .ok_or(SessionError::FieldNotFound)?
                    .value
            }
        };
        Ok(SecretString::new(value.expose().to_owned()))
    }
}

impl fmt::Debug for VaultSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultSession")
            .field("groups", &self.groups.len())
            .field("entries", &self.entries.len())
            .field("unlock_material", &self.unlock_material.is_some())
            .finish_non_exhaustive()
    }
}
