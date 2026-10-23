//! Edits applied to the lossless document, following `KeePass` semantics: an edited entry keeps
//! its UUID and gets its previous version appended to `History`; times are updated only on what
//! changed; deletion goes to the recycle bin when the database has one enabled, and a permanent
//! deletion leaves a `DeletedObject` record for synchronizing apps.

use base64::Engine;
use base64::engine::general_purpose::STANDARD;

use super::dom::{self, Element};
use super::time::now;
use super::writer::random;
use super::xml::{parse_uuid, split_tags};
use crate::model::{FAVORITE_TAG, ItemId, is_favorite_tag};
use crate::secret::SecretString;
use zeroize::Zeroizing;

const STANDARD_KEYS: [&str; 5] = ["Title", "UserName", "Password", "URL", "Notes"];
pub(crate) const DEFAULT_HISTORY_MAX_ITEMS: i64 = 10;
const RECYCLE_BIN_ICON: &str = "43";
const GROUP_ICON: &str = "48";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum EditError {
    /// The session wasn't opened from bytes this crate can write back.
    ReadOnly,
    EntryNotFound,
    GroupNotFound,
    GroupNotEmpty,
    RootGroup,
    /// A field name is empty, reserved, or used twice.
    InvalidFieldName,
    /// Text XML can't carry (most control characters).
    InvalidText,
    RandomUnavailable,
    AttachmentNotFound,
    AttachmentTooLarge,
    /// The change needs Keep+.
    NotEntitled,
    VersionNotFound,
    /// Not one of the standard `KeePass` icons.
    InvalidIcon,
}

impl std::fmt::Display for EditError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::ReadOnly => "vault is read-only",
            Self::EntryNotFound => "no such entry",
            Self::GroupNotFound => "no such group",
            Self::GroupNotEmpty => "group is not empty",
            Self::RootGroup => "the root group can't be deleted",
            Self::InvalidFieldName => "invalid field name",
            Self::InvalidText => "text contains characters a vault can't store",
            Self::RandomUnavailable => "system random source unavailable",
            Self::AttachmentNotFound => "no such attachment",
            Self::AttachmentTooLarge => "attachment is too large",
            Self::NotEntitled => "this needs Keep+",
            Self::VersionNotFound => "no such earlier version",
            Self::InvalidIcon => "no such icon",
        })
    }
}

impl std::error::Error for EditError {}

pub struct CustomFieldDraft {
    pub name: String,
    pub value: SecretString,
    pub protected: bool,
}

impl std::fmt::Debug for CustomFieldDraft {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CustomFieldDraft")
            .field("protected", &self.protected)
            .finish_non_exhaustive()
    }
}

/// The editable part of an entry. Everything else (icon, colours, auto-type, attachments,
/// history, custom data) is left as it is.
pub struct EntryDraft {
    pub title: String,
    pub username: String,
    pub password: SecretString,
    pub url: String,
    pub notes: String,
    pub custom_fields: Vec<CustomFieldDraft>,
    pub tags: Vec<String>,
    pub favorite: bool,
    /// Unix seconds; `None` means the entry never expires.
    pub expires: Option<i64>,
}

impl EntryDraft {
    /// The tags as stored: trimmed, without repeats, the favourite as the one tag other apps read.
    fn stored_tags(&self) -> Vec<String> {
        let mut tags: Vec<String> = Vec::new();
        for tag in self.tags.iter().map(|t| t.trim()) {
            if !tag.is_empty() && !is_favorite_tag(tag) && !tags.iter().any(|t| t == tag) {
                tags.push(tag.to_owned());
            }
        }
        if self.favorite || self.tags.iter().any(|t| is_favorite_tag(t.trim())) {
            tags.push(FAVORITE_TAG.to_owned());
        }
        tags
    }
}

impl std::fmt::Debug for EntryDraft {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EntryDraft")
            .field("custom_fields", &self.custom_fields.len())
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeleteOutcome {
    MovedToRecycleBin,
    Deleted,
}

pub const MAX_ATTACHMENT_SIZE: usize = 64 * 1024 * 1024;
/// A passkey as an entry keeps it. The identifiers are base64url text.
pub(crate) struct StoredPasskey<'a> {
    pub(crate) rp_id: &'a str,
    pub(crate) user_name: &'a str,
    pub(crate) user_handle: &'a str,
    pub(crate) credential_id: &'a str,
    pub(crate) private_key_pem: &'a SecretString,
}

/// `KeePass` marks attachments as protected in memory with this flag.
const BINARY_PROTECTED: u8 = 1;

/// The largest image an entry may carry; a site icon is far smaller.
pub(crate) const MAX_CUSTOM_ICON: usize = 256 * 1024;
const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";

pub(crate) type Binaries = Vec<Zeroizing<Vec<u8>>>;

/// `document` is the `KeePassFile` element; `binaries` the pool `Binary` refs point into.
pub(crate) struct Editor<'a> {
    document: &'a mut Element,
    binaries: Option<&'a mut Binaries>,
    minor_version: u16,
}

impl<'a> Editor<'a> {
    #[cfg(test)]
    pub(crate) fn new(document: &'a mut Element, minor_version: u16) -> Self {
        Self {
            document,
            binaries: None,
            minor_version,
        }
    }

    pub(crate) fn with_binaries(
        document: &'a mut Element,
        binaries: &'a mut Binaries,
        minor_version: u16,
    ) -> Self {
        Self {
            document,
            binaries: Some(binaries),
            minor_version,
        }
    }

    // MARK: Attachments

    /// Returns the name the attachment was stored under: `name`, or `name (2)` and so on when the
    /// entry already has one by that name. Identical content shares one binary.
    pub(crate) fn add_attachment(
        &mut self,
        id: ItemId,
        name: &str,
        data: &[u8],
    ) -> Result<String, EditError> {
        let name = name.trim();
        if name.is_empty() || !dom::is_xml_safe(name) {
            return Err(EditError::InvalidText);
        }
        if data.len() > MAX_ATTACHMENT_SIZE {
            return Err(EditError::AttachmentTooLarge);
        }
        let max_items = self.history_max_items();
        let binaries = self.binaries.as_deref_mut().ok_or(EditError::ReadOnly)?;
        let reference = if let Some(existing) = binaries.iter().position(|b| &b[1..] == data) {
            existing
        } else {
            let mut stored = Zeroizing::new(Vec::with_capacity(data.len() + 1));
            stored.push(BINARY_PROTECTED);
            stored.extend_from_slice(data);
            binaries.push(stored);
            binaries.len() - 1
        };
        let entry = self.entry_mut(id)?;
        let taken = attachment_names(entry);
        let stored_name = unique_name(name, &taken);
        push_history(entry, max_items);
        let mut value = Element::new("Value");
        value.set_attribute("Ref", &reference.to_string());
        let binary = Element::new("Binary")
            .with_child(Element::with_text("Key", stored_name.clone()))
            .with_child(value);
        let position = entry
            .children
            .iter()
            .rposition(
                |n| matches!(n, dom::Node::Element(e) if e.name == "Binary" || e.name == "String"),
            )
            .map_or(entry.children.len(), |p| p + 1);
        entry.children.insert(position, dom::Node::Element(binary));
        touch(entry);
        Ok(stored_name)
    }

    pub(crate) fn remove_attachment(&mut self, id: ItemId, name: &str) -> Result<(), EditError> {
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        if !attachment_names(entry).iter().any(|n| n == name) {
            return Err(EditError::AttachmentNotFound);
        }
        push_history(entry, max_items);
        entry.remove_children(|e| {
            e.name == "Binary" && e.child("Key").and_then(Element::text).as_deref() == Some(name)
        });
        touch(entry);
        self.compact_binaries();
        Ok(())
    }

    /// Drops pool binaries nothing refers to any more (history included) and renumbers the refs.
    fn compact_binaries(&mut self) {
        if let Some(binaries) = self.binaries.as_deref_mut() {
            compact_pool(self.document, binaries);
        }
    }

    // MARK: Entries

    pub(crate) fn create_entry(
        &mut self,
        group: ItemId,
        draft: &EntryDraft,
    ) -> Result<ItemId, EditError> {
        validate(draft)?;
        let protect = self.memory_protection();
        let id = new_id()?;
        let now = now();
        let mut entry = Element::new("Entry")
            .with_child(Element::with_text("UUID", encode_id(id)))
            .with_child(Element::with_text("IconID", "0"))
            .with_child(Element::new("ForegroundColor"))
            .with_child(Element::new("BackgroundColor"))
            .with_child(Element::new("OverrideURL"))
            .with_child(Element::with_text("Tags", draft.stored_tags().join(";")))
            .with_child(new_times(&now));
        set_expiry(entry.ensure_child("Times"), draft.expires);
        for key in STANDARD_KEYS {
            entry.children.push(dom::Node::Element(string_element(
                key,
                &standard_value(draft, key),
                protect(key),
            )));
        }
        for field in &draft.custom_fields {
            entry.children.push(dom::Node::Element(string_element(
                &field.name,
                &field.value,
                field.protected,
            )));
        }
        entry.children.push(dom::Node::Element(
            Element::new("AutoType")
                .with_child(Element::with_text("Enabled", "True"))
                .with_child(Element::with_text("DataTransferObfuscation", "0")),
        ));
        entry
            .children
            .push(dom::Node::Element(Element::new("History")));
        let target = self.group_mut(group)?;
        insert_entry(target, entry);
        Ok(id)
    }

    /// Returns false when the draft matches the entry already (nothing changes).
    pub(crate) fn update_entry(
        &mut self,
        id: ItemId,
        draft: &EntryDraft,
    ) -> Result<bool, EditError> {
        validate(draft)?;
        let protect = self.memory_protection();
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        if matches_draft(entry, draft) {
            return Ok(false);
        }
        push_history(entry, max_items);

        let wanted: Vec<(&str, SecretString, bool)> = STANDARD_KEYS
            .iter()
            .map(|key| (*key, standard_value(draft, key), protect(key)))
            .chain(draft.custom_fields.iter().map(|f| {
                (
                    f.name.as_str(),
                    SecretString::new(f.value.expose().to_owned()),
                    f.protected,
                )
            }))
            .collect();
        entry.remove_children(|e| {
            e.name == "String"
                && e.child("Key").and_then(Element::text).is_some_and(|key| {
                    // A passkey is not part of the form: editing the entry leaves it alone.
                    !key.starts_with(crate::passkey::FIELD_PREFIX)
                        && !wanted.iter().any(|(name, _, _)| *name == key)
                })
        });
        for (name, value, protected) in &wanted {
            let existing = entry.elements_mut().find(|e| {
                e.name == "String"
                    && e.child("Key").and_then(Element::text).as_deref() == Some(*name)
            });
            if let Some(string) = existing {
                set_value(string.ensure_child("Value"), value, *protected);
            } else {
                let position = entry
                    .children
                    .iter()
                    .rposition(|n| matches!(n, dom::Node::Element(e) if e.name == "String"))
                    .map_or(entry.children.len(), |p| p + 1);
                entry.children.insert(
                    position,
                    dom::Node::Element(string_element(name, value, *protected)),
                );
            }
        }
        entry
            .ensure_child("Tags")
            .set_text(draft.stored_tags().join(";"));
        let now = now();
        let times = entry.ensure_child("Times");
        set_expiry(times, draft.expires);
        times
            .ensure_child("LastModificationTime")
            .set_text(now.clone());
        times.ensure_child("LastAccessTime").set_text(now);
        Ok(true)
    }

    /// Makes an earlier version current again. The entry as it is now goes to the history first,
    /// so nothing is lost; the history itself and the UUID stay.
    pub(crate) fn restore_version(&mut self, id: ItemId, position: usize) -> Result<(), EditError> {
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        let mut version = entry
            .elements()
            .filter(|e| e.name == "History")
            .flat_map(|h| h.elements().filter(|e| e.name == "Entry"))
            .nth(position)
            .map(Element::duplicate)
            .ok_or(EditError::VersionNotFound)?;
        push_history(entry, max_items);
        let keep = |node: &dom::Node| matches!(node, dom::Node::Element(e) if e.name == "UUID" || e.name == "History");
        let (kept, _): (Vec<dom::Node>, Vec<dom::Node>) = std::mem::take(&mut entry.children)
            .into_iter()
            .partition(keep);
        let (mut identity, history): (Vec<dom::Node>, Vec<dom::Node>) = kept
            .into_iter()
            .partition(|n| matches!(n, dom::Node::Element(e) if e.name == "UUID"));
        identity.extend(
            std::mem::take(&mut version.children)
                .into_iter()
                .filter(|node| !keep(node)),
        );
        identity.extend(history);
        entry.children = identity;
        touch(entry);
        Ok(())
    }

    /// Returns false when the entry already has this icon.
    pub(crate) fn set_entry_icon(&mut self, id: ItemId, icon: u32) -> Result<bool, EditError> {
        if icon > crate::model::MAX_STANDARD_ICON {
            return Err(EditError::InvalidIcon);
        }
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        let text = icon.to_string();
        let custom = entry.child("CustomIconUUID").is_some();
        if !custom
            && entry.child("IconID").and_then(Element::text).as_deref() == Some(text.as_str())
        {
            return Ok(false);
        }
        push_history(entry, max_items);
        entry.remove_children(|e| e.name == "CustomIconUUID");
        entry.ensure_child("IconID").set_text(text);
        touch(entry);
        Ok(true)
    }

    /// Gives an entry that still has the default key an icon, as part of a pass over the whole vault.
    /// An entry someone already chose an icon or an image for is left alone. The entry is marked as
    /// changed but its history is not: a version per entry for a cosmetic pass would crowd out real ones.
    pub(crate) fn assign_entry_icon(&mut self, id: ItemId, icon: u32) -> Result<bool, EditError> {
        if icon == 0 || icon > crate::model::MAX_STANDARD_ICON {
            return Ok(false);
        }
        let entry = self.entry_mut(id)?;
        let current = entry.child("IconID").and_then(Element::text);
        let untouched = current.as_deref().is_none_or(|text| text.trim() == "0");
        if !untouched || entry.child("CustomIconUUID").is_some() {
            return Ok(false);
        }
        entry.ensure_child("IconID").set_text(icon.to_string());
        touch(entry);
        Ok(true)
    }

    /// Keeps an image in the file and returns its id. The same image is kept once.
    pub(crate) fn add_custom_icon(&mut self, png: &[u8]) -> Result<ItemId, EditError> {
        if png.len() > MAX_CUSTOM_ICON || !png.starts_with(PNG_SIGNATURE) {
            return Err(EditError::InvalidIcon);
        }
        let data = STANDARD.encode(png);
        let icons = self.meta_mut().ensure_child("CustomIcons");
        let existing = icons
            .elements()
            .filter(|icon| icon.name == "Icon")
            .find(|icon| {
                icon.child("Data")
                    .and_then(Element::text)
                    .is_some_and(|text| text.trim() == data)
            })
            .and_then(entry_id);
        if let Some(id) = existing {
            return Ok(id);
        }
        let id = new_id()?;
        icons.children.push(dom::Node::Element(
            Element::new("Icon")
                .with_child(Element::with_text("UUID", encode_id(id)))
                .with_child(Element::with_text("Data", data)),
        ));
        Ok(id)
    }

    /// Gives an entry an image kept in the file, unless it already has one. As with a pass over the
    /// standard icons, the entry is marked as changed but gets no version in its history.
    pub(crate) fn assign_entry_image(
        &mut self,
        id: ItemId,
        image: ItemId,
    ) -> Result<bool, EditError> {
        let entry = self.entry_mut(id)?;
        if entry.child("CustomIconUUID").is_some() {
            return Ok(false);
        }
        entry
            .ensure_child("CustomIconUUID")
            .set_text(encode_id(image));
        touch(entry);
        Ok(true)
    }

    /// Puts a passkey into the entry, replacing one it had, the way `KeePassXC` stores it. The
    /// entry as it was goes to its history.
    pub(crate) fn set_passkey(
        &mut self,
        id: ItemId,
        passkey: &StoredPasskey<'_>,
    ) -> Result<(), EditError> {
        use crate::passkey as names;
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        push_history(entry, max_items);
        entry.remove_children(|e| {
            e.name == "String"
                && e.child("Key")
                    .and_then(Element::text)
                    .is_some_and(|key| key.starts_with(names::FIELD_PREFIX))
        });
        let fields: [(&str, &str, bool); 7] = [
            (names::FIELD_USERNAME, passkey.user_name, false),
            (names::FIELD_CREDENTIAL_ID, passkey.credential_id, true),
            (
                names::FIELD_PRIVATE_KEY,
                passkey.private_key_pem.expose(),
                true,
            ),
            (names::FIELD_RELYING_PARTY, passkey.rp_id, false),
            (names::FIELD_USER_HANDLE, passkey.user_handle, true),
            (names::FIELD_BACKUP_ELIGIBLE, "1", false),
            (names::FIELD_BACKUP_STATE, "1", false),
        ];
        for (name, value, protected) in fields {
            if !dom::is_xml_safe(value) {
                return Err(EditError::InvalidText);
            }
            let position = entry
                .children
                .iter()
                .rposition(|n| matches!(n, dom::Node::Element(e) if e.name == "String"))
                .map_or(entry.children.len(), |p| p + 1);
            entry.children.insert(
                position,
                dom::Node::Element(string_element(
                    name,
                    &SecretString::new(value.to_owned()),
                    protected,
                )),
            );
        }
        let mut tags = entry_tags(entry);
        if !tags.iter().any(|t| t == names::TAG) {
            tags.push(names::TAG.to_owned());
            entry.ensure_child("Tags").set_text(tags.join(";"));
        }
        touch(entry);
        Ok(())
    }

    /// Takes the passkey out of the entry. Returns false when it had none.
    pub(crate) fn remove_passkey(&mut self, id: ItemId) -> Result<bool, EditError> {
        use crate::passkey as names;
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        let has = entry.elements().any(|e| {
            e.name == "String"
                && e.child("Key")
                    .and_then(Element::text)
                    .is_some_and(|key| key.starts_with(names::FIELD_PREFIX))
        });
        if !has {
            return Ok(false);
        }
        push_history(entry, max_items);
        entry.remove_children(|e| {
            e.name == "String"
                && e.child("Key")
                    .and_then(Element::text)
                    .is_some_and(|key| key.starts_with(names::FIELD_PREFIX))
        });
        let tags: Vec<String> = entry_tags(entry)
            .into_iter()
            .filter(|t| t != names::TAG)
            .collect();
        entry.ensure_child("Tags").set_text(tags.join(";"));
        touch(entry);
        Ok(true)
    }

    /// Returns false when the entry already is (or isn't) a favourite.
    pub(crate) fn set_favorite(&mut self, id: ItemId, favorite: bool) -> Result<bool, EditError> {
        let max_items = self.history_max_items();
        let entry = self.entry_mut(id)?;
        let mut tags = entry_tags(entry);
        if tags.iter().any(|t| is_favorite_tag(t)) == favorite {
            return Ok(false);
        }
        push_history(entry, max_items);
        tags.retain(|t| !is_favorite_tag(t));
        if favorite {
            tags.push(FAVORITE_TAG.to_owned());
        }
        entry.ensure_child("Tags").set_text(tags.join(";"));
        let now = now();
        let times = entry.ensure_child("Times");
        times
            .ensure_child("LastModificationTime")
            .set_text(now.clone());
        times.ensure_child("LastAccessTime").set_text(now);
        Ok(true)
    }

    pub(crate) fn delete_entry(
        &mut self,
        id: ItemId,
        permanent: bool,
    ) -> Result<DeleteOutcome, EditError> {
        let parent = self.entry_parent(id)?;
        if !permanent {
            if let Some(bin) = self.recycle_bin()? {
                if !self.is_within(parent, bin) {
                    self.move_entry(id, bin)?;
                    return Ok(DeleteOutcome::MovedToRecycleBin);
                }
            }
        }
        let group = self.group_mut(parent)?;
        group.remove_children(|e| e.name == "Entry" && entry_id(e) == Some(id));
        self.record_deletion(&[id]);
        self.compact_binaries();
        Ok(DeleteOutcome::Deleted)
    }

    pub(crate) fn move_entry(&mut self, id: ItemId, target: ItemId) -> Result<bool, EditError> {
        let parent = self.entry_parent(id)?;
        self.group_mut(target)?;
        if parent == target {
            return Ok(false);
        }
        let mut entry = {
            let group = self.group_mut(parent)?;
            group
                .remove_children(|e| e.name == "Entry" && entry_id(e) == Some(id))
                .pop()
                .ok_or(EditError::EntryNotFound)?
        };
        let now = now();
        entry
            .ensure_child("Times")
            .ensure_child("LocationChanged")
            .set_text(now);
        if self.minor_version >= 1 {
            entry
                .ensure_child("PreviousParentGroup")
                .set_text(encode_id(parent));
        }
        insert_entry(self.group_mut(target)?, entry);
        Ok(true)
    }

    // MARK: Groups

    pub(crate) fn create_group(&mut self, parent: ItemId, name: &str) -> Result<ItemId, EditError> {
        if !dom::is_xml_safe(name) {
            return Err(EditError::InvalidText);
        }
        let id = new_id()?;
        let group = new_group(id, name, GROUP_ICON, &now());
        self.group_mut(parent)?
            .children
            .push(dom::Node::Element(group));
        Ok(id)
    }

    /// A direct child group by name, for putting imported entries where a folder of that name
    /// already is.
    pub(crate) fn child_group(
        &mut self,
        parent: ItemId,
        name: &str,
    ) -> Result<Option<ItemId>, EditError> {
        Ok(self.group_mut(parent)?.elements().find_map(|e| {
            (e.name == "Group" && e.child("Name").and_then(Element::text).as_deref() == Some(name))
                .then(|| entry_id(e))
                .flatten()
        }))
    }

    pub(crate) fn rename_group(&mut self, id: ItemId, name: &str) -> Result<bool, EditError> {
        if !dom::is_xml_safe(name) {
            return Err(EditError::InvalidText);
        }
        let group = self.group_mut(id)?;
        if group.child("Name").and_then(Element::text).as_deref() == Some(name) {
            return Ok(false);
        }
        group.ensure_child("Name").set_text(name);
        group
            .ensure_child("Times")
            .ensure_child("LastModificationTime")
            .set_text(now());
        Ok(true)
    }

    /// Returns false when the group already has this icon. A custom image the group pointed at is
    /// dropped: `KeePass` apps show it in place of the standard icon.
    pub(crate) fn set_group_icon(&mut self, id: ItemId, icon: u32) -> Result<bool, EditError> {
        if icon > crate::model::MAX_STANDARD_ICON {
            return Err(EditError::InvalidIcon);
        }
        let group = self.group_mut(id)?;
        let text = icon.to_string();
        let custom = group.child("CustomIconUUID").is_some();
        if !custom
            && group.child("IconID").and_then(Element::text).as_deref() == Some(text.as_str())
        {
            return Ok(false);
        }
        group.remove_children(|e| e.name == "CustomIconUUID");
        group.ensure_child("IconID").set_text(text);
        group
            .ensure_child("Times")
            .ensure_child("LastModificationTime")
            .set_text(now());
        Ok(true)
    }

    pub(crate) fn delete_group(
        &mut self,
        id: ItemId,
        recursive: bool,
        permanent: bool,
    ) -> Result<DeleteOutcome, EditError> {
        let root = self.root_group_id()?;
        if id == root {
            return Err(EditError::RootGroup);
        }
        let parent = self.group_parent(id)?;
        let group = self.group_mut(id)?;
        let empty = !group
            .elements()
            .any(|e| e.name == "Entry" || e.name == "Group");
        if !empty && !recursive {
            return Err(EditError::GroupNotEmpty);
        }
        if !permanent {
            if let Some(bin) = self.recycle_bin()? {
                if id != bin && !self.is_within(parent, bin) {
                    let mut group = self
                        .group_mut(parent)?
                        .remove_children(|e| e.name == "Group" && entry_id(e) == Some(id))
                        .pop()
                        .ok_or(EditError::GroupNotFound)?;
                    group
                        .ensure_child("Times")
                        .ensure_child("LocationChanged")
                        .set_text(now());
                    if self.minor_version >= 1 {
                        group
                            .ensure_child("PreviousParentGroup")
                            .set_text(encode_id(parent));
                    }
                    self.group_mut(bin)?
                        .children
                        .push(dom::Node::Element(group));
                    return Ok(DeleteOutcome::MovedToRecycleBin);
                }
            }
        }
        let removed = self
            .group_mut(parent)?
            .remove_children(|e| e.name == "Group" && entry_id(e) == Some(id))
            .pop()
            .ok_or(EditError::GroupNotFound)?;
        let mut ids = Vec::new();
        collect_ids(&removed, &mut ids);
        if self.recycle_bin_id() == Some(id) {
            self.meta_mut()
                .ensure_child("RecycleBinUUID")
                .set_text(encode_id(ItemId::from_bytes([0; 16])));
        }
        self.record_deletion(&ids);
        self.compact_binaries();
        Ok(DeleteOutcome::Deleted)
    }

    // MARK: Lookup

    fn root_group(&mut self) -> Result<&mut Element, EditError> {
        self.document
            .child_mut("Root")
            .and_then(|r| r.child_mut("Group"))
            .ok_or(EditError::GroupNotFound)
    }

    fn root_group_id(&mut self) -> Result<ItemId, EditError> {
        entry_id(self.root_group()?).ok_or(EditError::GroupNotFound)
    }

    fn group_mut(&mut self, id: ItemId) -> Result<&mut Element, EditError> {
        find_group(self.root_group()?, id).ok_or(EditError::GroupNotFound)
    }

    fn group_parent(&mut self, id: ItemId) -> Result<ItemId, EditError> {
        let root = self.root_group()?;
        parent_of(root, id, "Group").ok_or(EditError::GroupNotFound)
    }

    fn entry_parent(&mut self, id: ItemId) -> Result<ItemId, EditError> {
        let root = self.root_group()?;
        parent_of(root, id, "Entry").ok_or(EditError::EntryNotFound)
    }

    fn entry_mut(&mut self, id: ItemId) -> Result<&mut Element, EditError> {
        let parent = self.entry_parent(id)?;
        self.group_mut(parent)?
            .elements_mut()
            .find(|e| e.name == "Entry" && entry_id(e) == Some(id))
            .ok_or(EditError::EntryNotFound)
    }

    /// Whether `group` is `ancestor` or lies inside it.
    fn is_within(&mut self, group: ItemId, ancestor: ItemId) -> bool {
        self.group_mut(ancestor)
            .is_ok_and(|a| entry_id(a) == Some(group) || find_group(a, group).is_some())
    }

    fn meta_mut(&mut self) -> &mut Element {
        self.document.ensure_child("Meta")
    }

    fn meta_flag(&self, name: &str) -> Option<bool> {
        self.document
            .child("Meta")
            .and_then(|m| m.child(name))
            .and_then(Element::text)
            .map(|t| t.trim().eq_ignore_ascii_case("true"))
    }

    fn memory_protection(&self) -> impl Fn(&str) -> bool + 'static {
        let protection = self
            .document
            .child("Meta")
            .and_then(|m| m.child("MemoryProtection"));
        let flag = |name: &str, default: bool| {
            protection
                .and_then(|p| p.child(name))
                .and_then(Element::text)
                .map_or(default, |t| t.trim().eq_ignore_ascii_case("true"))
        };
        let title = flag("ProtectTitle", false);
        let user = flag("ProtectUserName", false);
        let password = flag("ProtectPassword", true);
        let url = flag("ProtectURL", false);
        let notes = flag("ProtectNotes", false);
        move |key| match key {
            "Title" => title,
            "UserName" => user,
            "Password" => password,
            "URL" => url,
            "Notes" => notes,
            _ => false,
        }
    }

    fn history_max_items(&self) -> i64 {
        self.document
            .child("Meta")
            .and_then(|m| m.child("HistoryMaxItems"))
            .and_then(Element::text)
            .and_then(|t| t.trim().parse().ok())
            .unwrap_or(DEFAULT_HISTORY_MAX_ITEMS)
    }

    fn recycle_bin_id(&self) -> Option<ItemId> {
        self.document
            .child("Meta")
            .and_then(|m| m.child("RecycleBinUUID"))
            .and_then(|e| parse_uuid(e).ok())
            .filter(|id| !id.is_nil())
    }

    /// The recycle bin group, created when the database has the bin enabled but no group yet.
    fn recycle_bin(&mut self) -> Result<Option<ItemId>, EditError> {
        if self.meta_flag("RecycleBinEnabled") != Some(true) {
            return Ok(None);
        }
        if let Some(id) = self.recycle_bin_id() {
            if self.group_mut(id).is_ok() {
                return Ok(Some(id));
            }
        }
        let id = new_id()?;
        let now = now();
        let mut bin = new_group(id, "Recycle Bin", RECYCLE_BIN_ICON, &now);
        bin.ensure_child("EnableAutoType").set_text("false");
        bin.ensure_child("EnableSearching").set_text("false");
        self.root_group()?.children.push(dom::Node::Element(bin));
        let meta = self.meta_mut();
        meta.ensure_child("RecycleBinUUID").set_text(encode_id(id));
        meta.ensure_child("RecycleBinChanged").set_text(now);
        Ok(Some(id))
    }

    fn record_deletion(&mut self, ids: &[ItemId]) {
        let now = now();
        let Some(root) = self.document.child_mut("Root") else {
            return;
        };
        let deleted = root.ensure_child("DeletedObjects");
        for id in ids {
            deleted.children.push(dom::Node::Element(
                Element::new("DeletedObject")
                    .with_child(Element::with_text("UUID", encode_id(*id)))
                    .with_child(Element::with_text("DeletionTime", now.clone())),
            ));
        }
    }
}

// MARK: Helpers

fn attachment_names(entry: &Element) -> Vec<String> {
    entry
        .elements()
        .filter(|e| e.name == "Binary")
        .filter_map(|e| e.child("Key").and_then(Element::text))
        .collect()
}

fn unique_name(name: &str, taken: &[String]) -> String {
    if !taken.iter().any(|t| t == name) {
        return name.to_owned();
    }
    let (stem, extension) = match name.rfind('.') {
        Some(dot) if dot > 0 => (&name[..dot], &name[dot..]),
        _ => (name, ""),
    };
    (2..=taken.len() + 2)
        .map(|n| format!("{stem} ({n}){extension}"))
        .find(|candidate| !taken.contains(candidate))
        .unwrap_or_else(|| name.to_owned())
}

/// Drops pool binaries nothing refers to any more (history included) and renumbers the refs.
pub(crate) fn compact_pool(document: &mut Element, binaries: &mut Binaries) {
    let mut used = vec![false; binaries.len()];
    visit_refs(document, &mut |reference| {
        if let Some(slot) = used.get_mut(*reference) {
            *slot = true;
        }
    });
    if used.iter().all(|u| *u) {
        return;
    }
    let mut remap = Vec::with_capacity(used.len());
    let mut next = 0;
    for keep in &used {
        remap.push(next);
        if *keep {
            next += 1;
        }
    }
    let mut index = 0;
    binaries.retain(|_| {
        let keep = used[index];
        index += 1;
        keep
    });
    visit_refs(document, &mut |reference| {
        if let Some(new) = remap.get(*reference) {
            *reference = *new;
        }
    });
}

/// Every `Binary` `Ref` in the document, entries and history alike.
pub(crate) fn visit_refs(element: &mut Element, visit: &mut impl FnMut(&mut usize)) {
    for child in element.elements_mut() {
        if child.name == "Binary" {
            if let Some(value) = child.child_mut("Value") {
                if let Some(mut reference) = value.attribute("Ref").and_then(|r| r.parse().ok()) {
                    visit(&mut reference);
                    value.set_attribute("Ref", &reference.to_string());
                }
            }
        } else {
            visit_refs(child, visit);
        }
    }
}

fn touch(entry: &mut Element) {
    let now = now();
    let times = entry.ensure_child("Times");
    times
        .ensure_child("LastModificationTime")
        .set_text(now.clone());
    times.ensure_child("LastAccessTime").set_text(now);
}

fn validate(draft: &EntryDraft) -> Result<(), EditError> {
    let texts = [&draft.title, &draft.username, &draft.url, &draft.notes];
    if !texts.iter().all(|t| dom::is_xml_safe(t))
        || !dom::is_xml_safe(draft.password.expose())
        || !draft
            .stored_tags()
            .iter()
            .all(|t| dom::is_xml_safe(t) && !t.contains([';', ',']))
    {
        return Err(EditError::InvalidText);
    }
    let mut seen = Vec::new();
    for field in &draft.custom_fields {
        let name = field.name.as_str();
        if name.trim().is_empty()
            || STANDARD_KEYS.contains(&name)
            || seen.contains(&name)
            || !dom::is_xml_safe(name)
        {
            return Err(EditError::InvalidFieldName);
        }
        if !dom::is_xml_safe(field.value.expose()) {
            return Err(EditError::InvalidText);
        }
        seen.push(name);
    }
    Ok(())
}

fn standard_value(draft: &EntryDraft, key: &str) -> SecretString {
    let text = match key {
        "Title" => &draft.title,
        "UserName" => &draft.username,
        "Password" => draft.password.expose(),
        "URL" => &draft.url,
        _ => &draft.notes,
    };
    SecretString::new(text.to_owned())
}

fn string_element(key: &str, value: &SecretString, protected: bool) -> Element {
    let mut v = Element::new("Value");
    set_value(&mut v, value, protected);
    Element::new("String")
        .with_child(Element::with_text("Key", key))
        .with_child(v)
}

fn set_value(value: &mut Element, text: &SecretString, protected: bool) {
    if protected {
        value.set_attribute("Protected", "True");
        value.set_secret(SecretString::new(text.expose().to_owned()));
    } else {
        value.remove_attribute("Protected");
        value.set_text(text.expose().to_owned());
    }
}

fn matches_draft(entry: &Element, draft: &EntryDraft) -> bool {
    let strings: Vec<(String, String, bool)> = entry
        .elements()
        .filter(|e| e.name == "String")
        .filter_map(|e| {
            let key = e.child("Key")?.text()?;
            let value = e.child("Value")?;
            let text = value.secret().map_or_else(
                || value.text().unwrap_or_default(),
                |s| s.expose().to_owned(),
            );
            Some((key, text, value.is_protected()))
        })
        .collect();
    let get = |key: &str| {
        strings
            .iter()
            .find(|(k, _, _)| k == key)
            .map_or("", |(_, v, _)| v.as_str())
    };
    let standard_same = get("Title") == draft.title
        && get("UserName") == draft.username
        && get("Password") == draft.password.expose()
        && get("URL") == draft.url
        && get("Notes") == draft.notes;
    let custom: Vec<_> = strings
        .iter()
        .filter(|(k, _, _)| {
            !STANDARD_KEYS.contains(&k.as_str()) && !k.starts_with(crate::passkey::FIELD_PREFIX)
        })
        .collect();
    let custom_same = custom.len() == draft.custom_fields.len()
        && draft.custom_fields.iter().all(|f| {
            custom
                .iter()
                .any(|(k, v, p)| *k == f.name && v == f.value.expose() && *p == f.protected)
        });
    let expires = entry.child("Times").and_then(super::xml::expiry);
    let normalized = |tags: Vec<String>| {
        let favorite = tags.iter().any(|t| is_favorite_tag(t));
        let own: Vec<String> = tags.into_iter().filter(|t| !is_favorite_tag(t)).collect();
        (own, favorite)
    };
    standard_same
        && custom_same
        && normalized(entry_tags(entry)) == normalized(draft.stored_tags())
        && expires == draft.expires
}

fn entry_tags(entry: &Element) -> Vec<String> {
    entry
        .child("Tags")
        .and_then(Element::text)
        .map(|t| split_tags(&t))
        .unwrap_or_default()
}

/// Appends the entry as it is now to its history, keeping at most `max_items` (negative: no limit).
fn push_history(entry: &mut Element, max_items: i64) {
    let mut previous = entry.duplicate();
    previous.remove_children(|e| e.name == "History");
    let history = entry.ensure_child("History");
    history.children.push(dom::Node::Element(previous));
    if max_items >= 0 {
        let excess = history
            .elements()
            .filter(|e| e.name == "Entry")
            .count()
            .saturating_sub(usize::try_from(max_items).unwrap_or(usize::MAX));
        let mut dropped = 0;
        history.remove_children(|e| {
            let drop = e.name == "Entry" && dropped < excess;
            if drop {
                dropped += 1;
            }
            drop
        });
    }
}

/// Turning expiry off keeps the old date, as `KeePass` does.
fn set_expiry(times: &mut Element, expires: Option<i64>) {
    match expires {
        Some(unix) => {
            times
                .ensure_child("ExpiryTime")
                .set_text(super::time::encode(unix));
            times.ensure_child("Expires").set_text("True");
        }
        None => times.ensure_child("Expires").set_text("False"),
    }
}

fn insert_entry(group: &mut Element, entry: Element) {
    let position = group
        .children
        .iter()
        .position(|n| matches!(n, dom::Node::Element(e) if e.name == "Group"))
        .unwrap_or(group.children.len());
    group.children.insert(position, dom::Node::Element(entry));
}

fn new_group(id: ItemId, name: &str, icon: &str, now: &str) -> Element {
    Element::new("Group")
        .with_child(Element::with_text("UUID", encode_id(id)))
        .with_child(Element::with_text("Name", name))
        .with_child(Element::new("Notes"))
        .with_child(Element::with_text("IconID", icon))
        .with_child(new_times(now))
        .with_child(Element::with_text("IsExpanded", "True"))
        .with_child(Element::new("DefaultAutoTypeSequence"))
        .with_child(Element::with_text("EnableAutoType", "null"))
        .with_child(Element::with_text("EnableSearching", "null"))
        .with_child(Element::with_text(
            "LastTopVisibleEntry",
            encode_id(ItemId::from_bytes([0; 16])),
        ))
}

fn new_times(now: &str) -> Element {
    Element::new("Times")
        .with_child(Element::with_text("CreationTime", now))
        .with_child(Element::with_text("LastModificationTime", now))
        .with_child(Element::with_text("LastAccessTime", now))
        .with_child(Element::with_text("ExpiryTime", now))
        .with_child(Element::with_text("Expires", "False"))
        .with_child(Element::with_text("UsageCount", "0"))
        .with_child(Element::with_text("LocationChanged", now))
}

/// The UUID of an `Entry` or `Group` element.
fn entry_id(element: &Element) -> Option<ItemId> {
    element.child("UUID").and_then(|e| parse_uuid(e).ok())
}

fn find_group(group: &mut Element, id: ItemId) -> Option<&mut Element> {
    if entry_id(group) == Some(id) {
        return Some(group);
    }
    group
        .elements_mut()
        .filter(|e| e.name == "Group")
        .find_map(|child| find_group(child, id))
}

/// The id of the group directly containing the `Entry` or `Group` with this id.
fn parent_of(group: &Element, id: ItemId, kind: &str) -> Option<ItemId> {
    if group
        .elements()
        .any(|e| e.name == kind && entry_id(e) == Some(id))
    {
        return entry_id(group);
    }
    group
        .elements()
        .filter(|e| e.name == "Group")
        .find_map(|child| parent_of(child, id, kind))
}

fn collect_ids(group: &Element, out: &mut Vec<ItemId>) {
    if let Some(id) = entry_id(group) {
        out.push(id);
    }
    for child in group.elements() {
        match child.name.as_str() {
            "Entry" => out.extend(entry_id(child)),
            "Group" => collect_ids(child, out),
            _ => {}
        }
    }
}

fn new_id() -> Result<ItemId, EditError> {
    let bytes = random::<16>().map_err(|_| EditError::RandomUnavailable)?;
    Ok(ItemId::from_bytes(*bytes))
}

fn encode_id(id: ItemId) -> String {
    STANDARD.encode(id.as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::credentials::VaultCredentials;
    use crate::kdbx::{open, read_with_key};
    use crate::limits::OpenLimits;
    use crate::secret::SecretBytes;

    fn rich() -> crate::kdbx::VaultSource {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../shared/test-vectors/editable/rich-aes-argon2d-gzip.kdbx"
        );
        let credentials = VaultCredentials::from_password(SecretBytes::from(
            b"correct horse battery staple".to_vec(),
        ));
        *open(
            &std::fs::read(path).unwrap(),
            &credentials,
            &OpenLimits::default(),
        )
        .unwrap()
        .source
        .unwrap()
    }

    fn find_entry<'a>(group: &'a Element, title: &str) -> Option<&'a Element> {
        group.elements().find_map(|e| match e.name.as_str() {
            "Entry"
                if e.elements().any(|s| {
                    s.name == "String"
                        && s.child("Value").and_then(Element::text).as_deref() == Some(title)
                }) =>
            {
                Some(e)
            }
            "Group" => find_entry(e, title),
            _ => None,
        })
    }

    fn root_group(source: &crate::kdbx::VaultSource) -> &Element {
        source
            .document
            .child("Root")
            .unwrap()
            .child("Group")
            .unwrap()
    }

    fn draft(password: &str) -> EntryDraft {
        EntryDraft {
            title: "Bank".to_owned(),
            username: "bob".to_owned(),
            password: SecretString::new(password.to_owned()),
            url: "https://bank.example.com".to_owned(),
            notes: "Line one\nLine two".to_owned(),
            custom_fields: Vec::new(),
            tags: Vec::new(),
            favorite: false,
            expires: None,
        }
    }

    #[test]
    fn unknown_elements_survive_an_edit_and_a_save() {
        let mut source = rich();
        let id = parse_uuid(
            find_entry(root_group(&source), "Bank")
                .unwrap()
                .child("UUID")
                .unwrap(),
        )
        .unwrap();
        Editor::new(&mut source.document, 0)
            .update_entry(id, &draft("fourth-password"))
            .unwrap();
        let bytes = source.write_verified(&OpenLimits::default()).unwrap();
        let (document, binaries) =
            read_with_key(&bytes, &source.save.transformed_key, &OpenLimits::default()).unwrap();
        let xml = String::from_utf8(
            dom::write(&document, &mut crate::kdbx::crypto::ProtectedStream::none()).to_vec(),
        )
        .unwrap();
        assert!(xml.contains("VazieKeepFixture") && xml.contains("preserve-me"));
        assert!(xml.contains("browser.extension") && xml.contains("keep-this-too"));
        assert_eq!(binaries.len(), 1);
        assert!(binaries[0].len() > 100);
    }

    #[test]
    fn edits_append_history_and_respect_the_limit() {
        let mut source = rich();
        let bank = |s: &crate::kdbx::VaultSource| {
            find_entry(root_group(s), "Bank")
                .unwrap()
                .child("History")
                .unwrap()
                .elements()
                .count()
        };
        let id = parse_uuid(
            find_entry(root_group(&source), "Bank")
                .unwrap()
                .child("UUID")
                .unwrap(),
        )
        .unwrap();
        let before = bank(&source);
        assert_eq!(before, 2, "the fixture has two history items");
        Editor::new(&mut source.document, 0)
            .update_entry(id, &draft("fourth"))
            .unwrap();
        assert_eq!(bank(&source), 3);
        let newest = find_entry(root_group(&source), "Bank")
            .unwrap()
            .child("History")
            .unwrap()
            .elements()
            .last()
            .unwrap();
        let old_password = newest
            .elements()
            .find(|s| s.child("Key").and_then(Element::text).as_deref() == Some("Password"))
            .unwrap();
        assert_eq!(
            old_password
                .child("Value")
                .unwrap()
                .secret()
                .unwrap()
                .expose(),
            "third-password"
        );
        assert!(
            newest.child("History").is_none(),
            "history items carry no history of their own"
        );

        source
            .document
            .child_mut("Meta")
            .unwrap()
            .ensure_child("HistoryMaxItems")
            .set_text("2");
        Editor::new(&mut source.document, 0)
            .update_entry(id, &draft("fifth"))
            .unwrap();
        assert_eq!(bank(&source), 2);
    }

    #[test]
    fn a_permanent_delete_leaves_a_deleted_object_and_updates_times() {
        let mut source = rich();
        let id = parse_uuid(
            find_entry(root_group(&source), "Bank")
                .unwrap()
                .child("UUID")
                .unwrap(),
        )
        .unwrap();
        let before = find_entry(root_group(&source), "Bank")
            .unwrap()
            .child("Times")
            .unwrap()
            .child("LastModificationTime")
            .unwrap()
            .text();
        Editor::new(&mut source.document, 0)
            .update_entry(id, &draft("changed"))
            .unwrap();
        let after = find_entry(root_group(&source), "Bank")
            .unwrap()
            .child("Times")
            .unwrap()
            .child("LastModificationTime")
            .unwrap()
            .text();
        assert_ne!(before, after);
        assert_eq!(
            Editor::new(&mut source.document, 0)
                .delete_entry(id, true)
                .unwrap(),
            DeleteOutcome::Deleted
        );
        let deleted = source
            .document
            .child("Root")
            .unwrap()
            .child("DeletedObjects")
            .unwrap();
        assert!(
            deleted
                .elements()
                .any(|d| d.child("UUID").and_then(|u| parse_uuid(u).ok()) == Some(id))
        );
    }
}
