//! Merging a second version of a database into the open one (ADR 0023).
//!
//! Both sides are lossless documents. Items are matched by UUID; the order of two versions of an
//! entry is read from their histories, so a clash is told from an ordinary update; whatever a
//! clash would lose is kept as a conflict copy with a UUID both devices derive alike.

use std::collections::{HashMap, HashSet};
use std::fmt;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use sha2::{Digest, Sha256};

use super::dom::{Element, Node};
use super::edit::{Binaries, DEFAULT_HISTORY_MAX_ITEMS, compact_pool, visit_refs};
use super::time;
use super::xml::parse_uuid;
use crate::model::ItemId;
use crate::secret::SecretString;

const ENTRY_DIGEST_DOMAIN: &[u8] = b"vazie-keep/entry-digest/v1";
const CONFLICT_ID_DOMAIN: &[u8] = b"vazie-keep/merge-conflict/v1";

/// Children of an entry that say where it is or how it was reached, not what it holds.
const NOT_CONTENT: [&str; 6] = [
    "UUID",
    "String",
    "Binary",
    "Times",
    "History",
    "PreviousParentGroup",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MergeOptions {
    /// Appended to the title of a conflict copy. The app passes one in the person's language.
    pub conflict_suffix: String,
    /// Merge two databases that are not versions of one another: every item is then new.
    pub allow_unrelated: bool,
}

impl Default for MergeOptions {
    fn default() -> Self {
        Self {
            conflict_suffix: " (conflict)".to_owned(),
            allow_unrelated: false,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MergeReport {
    pub entries_added: usize,
    /// Entries changed after a deletion here, which therefore came back.
    pub entries_restored: usize,
    /// Entries whose current version is now the other side's.
    pub entries_updated: usize,
    pub entries_moved: usize,
    pub entries_deleted: usize,
    /// Entries whose current version stayed but whose history changed.
    pub histories_merged: usize,
    pub history_versions_added: usize,
    pub groups_added: usize,
    pub groups_updated: usize,
    pub groups_moved: usize,
    pub groups_deleted: usize,
    /// The conflict copies made, one per entry changed on both sides.
    pub conflicts: Vec<ItemId>,
    pub icons_added: usize,
    pub deletion_records_added: usize,
    pub settings_updated: bool,
}

impl MergeReport {
    /// Whether the open database is different now.
    #[must_use]
    pub fn changed(&self) -> bool {
        self.entries_added
            + self.entries_restored
            + self.entries_updated
            + self.entries_moved
            + self.entries_deleted
            + self.histories_merged
            + self.groups_added
            + self.groups_updated
            + self.groups_moved
            + self.groups_deleted
            + self.icons_added
            + self.deletion_records_added
            + self.conflicts.len()
            > 0
            || self.settings_updated
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum MergeError {
    /// The open database can't be written back.
    ReadOnly,
    /// The other database was not opened from bytes this crate can read back.
    RemoteNotReadable,
    /// The two databases are not versions of one another (and `allow_unrelated` is off).
    DifferentVault,
    /// The merged result would not read as a vault; nothing was changed.
    InvalidVault,
}

impl fmt::Display for MergeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::ReadOnly => "vault is read-only",
            Self::RemoteNotReadable => "the other database can't be merged",
            Self::DifferentVault => "the two databases are not versions of one vault",
            Self::InvalidVault => "the merged vault would not be valid",
        })
    }
}

impl std::error::Error for MergeError {}

/// Merges `remote` into `local`. Returns what changed; a report that is not `changed()` means the
/// documents and the pool were left as they were.
pub(crate) fn merge(
    local: &mut Element,
    local_bins: &mut Binaries,
    remote: &Element,
    remote_bins: &Binaries,
    minor_version: u16,
    options: &MergeOptions,
) -> Result<MergeReport, MergeError> {
    let local_root = root_id(local).ok_or(MergeError::InvalidVault)?;
    let remote_root = root_id(remote).ok_or(MergeError::RemoteNotReadable)?;
    if local_root != remote_root && !options.allow_unrelated {
        return Err(MergeError::DifferentVault);
    }
    let max_history = history_max(local);
    let local_deleted = deleted_objects(local);
    let remote_deleted = deleted_objects(remote);
    let mut report = MergeReport::default();

    let remote_tree = Tree::of(remote).ok_or(MergeError::RemoteNotReadable)?;
    let mut groups = GroupMap::new(local, &remote_tree, local_root, remote_root);

    merge_groups(
        local,
        &remote_tree,
        &mut groups,
        &local_deleted,
        minor_version,
        &mut report,
    );
    merge_entries(
        local,
        local_bins,
        remote_bins,
        &remote_tree,
        &groups,
        &local_deleted,
        &Context {
            max_history,
            minor_version,
            options,
        },
        &mut report,
    );
    merge_meta(
        local,
        remote,
        &groups,
        local_root == remote_root,
        &mut report,
    );
    apply_deletions(local, &local_deleted, &remote_deleted, &mut report);
    if report.changed() {
        compact_pool(local, local_bins);
    }
    Ok(report)
}

struct Context<'a> {
    max_history: i64,
    minor_version: u16,
    options: &'a MergeOptions,
}

// MARK: Reading

fn root_group(document: &Element) -> Option<&Element> {
    document.child("Root")?.child("Group")
}

fn root_group_mut(document: &mut Element) -> Option<&mut Element> {
    document.child_mut("Root")?.child_mut("Group")
}

fn root_id(document: &Element) -> Option<ItemId> {
    id_of(root_group(document)?)
}

fn id_of(element: &Element) -> Option<ItemId> {
    element.child("UUID").and_then(|e| parse_uuid(e).ok())
}

fn encode_id(id: ItemId) -> String {
    STANDARD.encode(id.as_bytes())
}

fn time_of(element: &Element, name: &str) -> Option<i64> {
    element
        .child("Times")?
        .child(name)?
        .text()
        .and_then(|t| time::decode(&t))
}

fn modified(element: &Element) -> i64 {
    time_of(element, "LastModificationTime").unwrap_or(i64::MIN)
}

fn located(element: &Element) -> i64 {
    time_of(element, "LocationChanged").unwrap_or(i64::MIN)
}

fn history_max(document: &Element) -> i64 {
    document
        .child("Meta")
        .and_then(|m| m.child("HistoryMaxItems"))
        .and_then(Element::text)
        .and_then(|t| t.trim().parse().ok())
        .unwrap_or(DEFAULT_HISTORY_MAX_ITEMS)
}

fn recycle_bin(document: &Element) -> Option<ItemId> {
    document
        .child("Meta")?
        .child("RecycleBinUUID")
        .and_then(|e| parse_uuid(e).ok())
        .filter(|id| !id.is_nil())
}

/// The UUIDs of permanent deletions and when they happened; the later time where one repeats.
fn deleted_objects(document: &Element) -> HashMap<ItemId, i64> {
    let mut out: HashMap<ItemId, i64> = HashMap::new();
    let Some(deleted) = document
        .child("Root")
        .and_then(|r| r.child("DeletedObjects"))
    else {
        return out;
    };
    for record in deleted.elements().filter(|e| e.name == "DeletedObject") {
        let (Some(id), Some(at)) = (
            id_of(record),
            record
                .child("DeletionTime")
                .and_then(Element::text)
                .and_then(|t| time::decode(&t)),
        ) else {
            continue;
        };
        out.entry(id)
            .and_modify(|known| *known = (*known).max(at))
            .or_insert(at);
    }
    out
}

fn history_of(entry: &Element) -> Vec<&Element> {
    entry
        .child("History")
        .map(|h| h.elements().filter(|e| e.name == "Entry").collect())
        .unwrap_or_default()
}

fn content(binary: &[u8]) -> &[u8] {
    binary.get(1..).unwrap_or(&[])
}

struct Rec<'a> {
    id: ItemId,
    parent: Option<ItemId>,
    element: &'a Element,
}

/// Every group (parents before children) and entry of a document.
struct Tree<'a> {
    groups: Vec<Rec<'a>>,
    entries: Vec<Rec<'a>>,
}

impl<'a> Tree<'a> {
    fn of(document: &'a Element) -> Option<Self> {
        let mut tree = Self {
            groups: Vec::new(),
            entries: Vec::new(),
        };
        tree.walk(root_group(document)?, None);
        Some(tree)
    }

    fn walk(&mut self, group: &'a Element, parent: Option<ItemId>) {
        let Some(id) = id_of(group) else { return };
        self.groups.push(Rec {
            id,
            parent,
            element: group,
        });
        for child in group.elements() {
            match child.name.as_str() {
                "Entry" => {
                    if let Some(entry) = id_of(child) {
                        self.entries.push(Rec {
                            id: entry,
                            parent: Some(id),
                            element: child,
                        });
                    }
                }
                "Group" => self.walk(child, Some(id)),
                _ => {}
            }
        }
    }
}

// MARK: Content digest

fn put(hasher: &mut Sha256, tag: u8, bytes: &[u8]) {
    hasher.update([tag]);
    hasher.update((bytes.len() as u64).to_le_bytes());
    hasher.update(bytes);
}

fn feed_element(hasher: &mut Sha256, element: &Element) {
    put(hasher, b'n', element.name.as_bytes());
    let mut attributes: Vec<&(String, String)> = element.attributes.iter().collect();
    attributes.sort();
    for (key, value) in attributes {
        put(hasher, b'k', key.as_bytes());
        put(hasher, b'v', value.as_bytes());
    }
    for node in &element.children {
        match node {
            Node::Element(child) => {
                hasher.update(b"(");
                feed_element(hasher, child);
                hasher.update(b")");
            }
            Node::Text(text) if text.trim().is_empty() => {}
            Node::Text(text) => put(hasher, b't', text.as_bytes()),
            Node::Secret(secret) => put(hasher, b's', secret.expose().as_bytes()),
        }
    }
}

fn element_digest(element: &Element) -> [u8; 32] {
    let mut hasher = Sha256::new();
    feed_element(&mut hasher, element);
    hasher.finalize().into()
}

/// What an entry holds, without when it was touched, where it sits or its history: the strings
/// (protected or not), the attachments' bytes, expiry and everything else it carries. Never stored,
/// only compared; it covers secret values.
fn digest(entry: &Element, bins: &Binaries) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(ENTRY_DIGEST_DOMAIN);

    let mut parts: Vec<[u8; 32]> = Vec::new();
    for string in entry.elements().filter(|e| e.name == "String") {
        let key = string
            .child("Key")
            .and_then(Element::text)
            .unwrap_or_default();
        let mut part = Sha256::new();
        put(&mut part, b'S', key.as_bytes());
        if let Some(value) = string.child("Value") {
            let protected = value.is_protected() || value.secret().is_some();
            part.update([u8::from(protected)]);
            match value.secret() {
                Some(secret) => put(&mut part, b'v', secret.expose().as_bytes()),
                None => put(&mut part, b'v', value.text().unwrap_or_default().as_bytes()),
            }
        }
        parts.push(part.finalize().into());
    }
    for binary in entry.elements().filter(|e| e.name == "Binary") {
        let key = binary
            .child("Key")
            .and_then(Element::text)
            .unwrap_or_default();
        let bytes = binary
            .child("Value")
            .and_then(|v| v.attribute("Ref"))
            .and_then(|r| r.parse::<usize>().ok())
            .and_then(|r| bins.get(r))
            .map(|b| content(b));
        let mut part = Sha256::new();
        put(&mut part, b'B', key.as_bytes());
        match bytes {
            Some(bytes) => put(&mut part, b'b', &Sha256::digest(bytes)),
            None => part.update([0]),
        }
        parts.push(part.finalize().into());
    }
    if let Some(times) = entry.child("Times") {
        for name in ["CreationTime", "ExpiryTime", "Expires"] {
            let mut part = Sha256::new();
            put(&mut part, b'T', name.as_bytes());
            let text = times.child(name).and_then(Element::text);
            put(&mut part, b't', text.unwrap_or_default().trim().as_bytes());
            parts.push(part.finalize().into());
        }
    }
    for other in entry
        .elements()
        .filter(|e| !NOT_CONTENT.contains(&e.name.as_str()))
    {
        parts.push(element_digest(other));
    }
    parts.sort_unstable();
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().into()
}

// MARK: Importing from the other side

/// The pool slot holding these bytes, added if the pool has none like it.
fn adopt(remote: &Binaries, index: usize, local: &mut Binaries) -> usize {
    let Some(blob) = remote.get(index) else {
        return index;
    };
    if let Some(found) = local.iter().position(|b| content(b) == content(blob)) {
        return found;
    }
    local.push(blob.clone());
    local.len() - 1
}

/// A copy of an element of the other side whose attachments refer to this side's pool.
fn import(element: &Element, remote_bins: &Binaries, local_bins: &mut Binaries) -> Element {
    let mut copy = element.duplicate();
    visit_refs(&mut copy, &mut |reference| {
        *reference = adopt(remote_bins, *reference, local_bins);
    });
    copy
}

fn set_child_text(element: &mut Element, name: &str, text: &str) {
    element.ensure_child(name).set_text(text);
}

// MARK: Groups

/// Which groups exist here, where, and how to find the one that stands for a group of the other
/// side.
struct GroupMap {
    /// Group here → its parent here.
    local: HashMap<ItemId, Option<ItemId>>,
    /// Group there → its parent there.
    remote: HashMap<ItemId, Option<ItemId>>,
    local_root: ItemId,
    remote_root: ItemId,
}

impl GroupMap {
    fn new(local: &Element, remote: &Tree<'_>, local_root: ItemId, remote_root: ItemId) -> Self {
        let local_tree = Tree::of(local);
        Self {
            local: local_tree
                .map(|t| t.groups.into_iter().map(|g| (g.id, g.parent)).collect())
                .unwrap_or_default(),
            remote: remote.groups.iter().map(|g| (g.id, g.parent)).collect(),
            local_root,
            remote_root,
        }
    }

    /// The group here that is this group of the other side, if there is one.
    fn exact(&self, remote_group: ItemId) -> Option<ItemId> {
        if remote_group == self.remote_root {
            Some(self.local_root)
        } else {
            self.local
                .contains_key(&remote_group)
                .then_some(remote_group)
        }
    }

    /// The group here that is this group of the other side, or its nearest ancestor that is.
    fn resolve(&self, remote_group: ItemId) -> ItemId {
        let mut current = Some(remote_group);
        while let Some(id) = current {
            if let Some(found) = self.exact(id) {
                return found;
            }
            current = self.remote.get(&id).copied().flatten();
        }
        self.local_root
    }

    fn is_within(&self, group: ItemId, ancestor: ItemId) -> bool {
        let mut current = Some(group);
        let mut steps = 0;
        while let Some(id) = current {
            if id == ancestor {
                return true;
            }
            steps += 1;
            if steps > self.local.len() + 1 {
                return true;
            }
            current = self.local.get(&id).copied().flatten();
        }
        false
    }
}

fn find_group_mut(group: &mut Element, id: ItemId) -> Option<&mut Element> {
    if id_of(group) == Some(id) {
        return Some(group);
    }
    group
        .elements_mut()
        .filter(|e| e.name == "Group")
        .find_map(|child| find_group_mut(child, id))
}

fn take_child(parent: &mut Element, kind: &str, id: ItemId) -> Option<Element> {
    let position = parent
        .children
        .iter()
        .position(|n| matches!(n, Node::Element(e) if e.name == kind && id_of(e) == Some(id)));
    if let Some(position) = position {
        if let Node::Element(found) = parent.children.remove(position) {
            return Some(found);
        }
    }
    parent
        .elements_mut()
        .filter(|e| e.name == "Group")
        .find_map(|child| take_child(child, kind, id))
}

fn put_entry(group: &mut Element, entry: Element) {
    let position = group
        .children
        .iter()
        .position(|n| matches!(n, Node::Element(e) if e.name == "Group"))
        .unwrap_or(group.children.len());
    group.children.insert(position, Node::Element(entry));
}

fn is_structure(node: &Node) -> bool {
    matches!(node, Node::Element(e) if e.name == "Entry" || e.name == "Group")
}

/// Takes a group's own properties from `from` and keeps its entries and subgroups and its place.
fn replace_properties(group: &mut Element, from: &Element) {
    let place = group
        .child("Times")
        .and_then(|t| t.child("LocationChanged"))
        .and_then(Element::text);
    let structure: Vec<Node> = {
        let (kept, rest): (Vec<Node>, Vec<Node>) = std::mem::take(&mut group.children)
            .into_iter()
            .partition(is_structure);
        drop(rest);
        kept
    };
    group.children = from
        .children
        .iter()
        .filter(|n| !is_structure(n))
        .map(|n| match n {
            Node::Element(e) => Node::Element(e.duplicate()),
            Node::Text(t) => Node::Text(t.clone()),
            Node::Secret(s) => Node::Secret(SecretString::new(s.expose().to_owned())),
        })
        .collect();
    if let Some(place) = place {
        group
            .ensure_child("Times")
            .ensure_child("LocationChanged")
            .set_text(place);
    }
    group.children.extend(structure);
}

fn shell_of(group: &Element) -> Element {
    let mut copy = group.duplicate();
    copy.remove_children(|e| e.name == "Entry" || e.name == "Group");
    copy
}

fn merge_groups(
    local: &mut Element,
    remote: &Tree<'_>,
    groups: &mut GroupMap,
    local_deleted: &HashMap<ItemId, i64>,
    minor_version: u16,
    report: &mut MergeReport,
) {
    for theirs in &remote.groups {
        let Some(their_parent) = theirs.parent else {
            continue; // the root stands for the root here, and keeps its own properties
        };
        let Some(root) = root_group_mut(local) else {
            return;
        };
        if groups.local.contains_key(&theirs.id) {
            let Some(ours) = find_group_mut(root, theirs.id) else {
                continue;
            };
            let our_place = located(ours);
            if modified(theirs.element) > modified(ours) {
                replace_properties(ours, theirs.element);
                report.groups_updated += 1;
            }
            if located(theirs.element) > our_place {
                let Some(target) = groups.exact(their_parent) else {
                    continue;
                };
                let current = groups.local.get(&theirs.id).copied().flatten();
                if Some(target) == current || groups.is_within(target, theirs.id) {
                    continue;
                }
                if let Some(mut moved) = take_child(root, "Group", theirs.id) {
                    set_child_times(&mut moved, "LocationChanged", located(theirs.element));
                    if minor_version >= 1 {
                        if let Some(previous) = current {
                            set_child_text(&mut moved, "PreviousParentGroup", &encode_id(previous));
                        }
                    }
                    if let Some(parent) = find_group_mut(root, target) {
                        parent.children.push(Node::Element(moved));
                        groups.local.insert(theirs.id, Some(target));
                        report.groups_moved += 1;
                    }
                }
            }
        } else {
            if local_deleted
                .get(&theirs.id)
                .is_some_and(|at| *at >= modified(theirs.element))
            {
                continue;
            }
            let parent = groups.resolve(their_parent);
            if let Some(parent_element) = find_group_mut(root, parent) {
                parent_element
                    .children
                    .push(Node::Element(shell_of(theirs.element)));
                groups.local.insert(theirs.id, Some(parent));
                report.groups_added += 1;
            }
        }
    }
}

/// Sets one of an item's `Times` to a moment given as seconds.
fn set_child_times(element: &mut Element, name: &str, unix: i64) {
    if unix == i64::MIN {
        return;
    }
    element
        .ensure_child("Times")
        .ensure_child(name)
        .set_text(time::encode(unix));
}

// MARK: Entries

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Relation {
    Same,
    RemoteNewer,
    LocalNewer,
    Diverged,
}

/// Whether `newer`'s history can account for `older` as an earlier version of it. A history that
/// says nothing (empty, or starting after `older`: pruned, switched off, or never kept by the app
/// that saved it) is taken as consent, which is the plain rule that the newer version wins.
fn descends(
    newer: &Element,
    newer_bins: &Binaries,
    older_time: i64,
    older_digest: [u8; 32],
) -> bool {
    let history = history_of(newer);
    let Some(oldest) = history.iter().map(|h| modified(h)).min() else {
        return true;
    };
    history
        .iter()
        .any(|h| modified(h) == older_time && digest(h, newer_bins) == older_digest)
        || older_time < oldest
}

struct Side<'a> {
    element: &'a Element,
    bins: &'a Binaries,
    digest: [u8; 32],
}

fn relate(local: &Side<'_>, remote: &Side<'_>) -> Relation {
    if local.digest == remote.digest {
        return Relation::Same;
    }
    let (ours, theirs) = (modified(local.element), modified(remote.element));
    match theirs.cmp(&ours) {
        std::cmp::Ordering::Equal => Relation::Diverged,
        std::cmp::Ordering::Greater => {
            if descends(remote.element, remote.bins, ours, local.digest) {
                Relation::RemoteNewer
            } else {
                Relation::Diverged
            }
        }
        std::cmp::Ordering::Less => {
            if descends(local.element, local.bins, theirs, remote.digest) {
                Relation::LocalNewer
            } else {
                Relation::Diverged
            }
        }
    }
}

/// One version for a merged history, still pointing at the document it came from.
#[derive(Clone, Copy)]
struct Pick<'a> {
    element: &'a Element,
    from_remote: bool,
}

/// Both histories and any `extra` versions, once each, oldest first, cut to `max` from the old end.
fn combined_history<'a>(
    local: &'a Element,
    local_bins: &Binaries,
    remote: &'a Element,
    remote_bins: &Binaries,
    extra: &[Pick<'a>],
    max: i64,
) -> Vec<Pick<'a>> {
    let mut seen: HashSet<(i64, [u8; 32])> = HashSet::new();
    let mut picks: Vec<Pick<'a>> = Vec::new();
    let mut consider = |element: &'a Element, from_remote: bool, bins: &Binaries| {
        if seen.insert((modified(element), digest(element, bins))) {
            picks.push(Pick {
                element,
                from_remote,
            });
        }
    };
    for version in history_of(local) {
        consider(version, false, local_bins);
    }
    for version in history_of(remote) {
        consider(version, true, remote_bins);
    }
    for pick in extra {
        let bins = if pick.from_remote {
            remote_bins
        } else {
            local_bins
        };
        consider(pick.element, pick.from_remote, bins);
    }
    picks.sort_by_key(|p| modified(p.element));
    if let Ok(max) = usize::try_from(max) {
        let excess = picks.len().saturating_sub(max);
        picks.drain(..excess);
    }
    picks
}

fn history_element(
    picks: &[Pick<'_>],
    remote_bins: &Binaries,
    local_bins: &mut Binaries,
) -> Element {
    let mut history = Element::new("History");
    for pick in picks {
        let mut version = if pick.from_remote {
            import(pick.element, remote_bins, local_bins)
        } else {
            pick.element.duplicate()
        };
        version.remove_children(|e| e.name == "History");
        history.children.push(Node::Element(version));
    }
    history
}

fn with_history(mut entry: Element, history: Element) -> Element {
    entry.remove_children(|e| e.name == "History");
    entry.children.push(Node::Element(history));
    entry
}

/// The id of the copy made when `lost` (an entry of `original`) loses a clash: from facts nobody
/// keeps secret, so two devices make the same copy and a second merge makes none.
fn conflict_id(original: ItemId, lost_modified: i64) -> ItemId {
    let mut bytes: [u8; 16] = [0; 16];
    let digest = Sha256::new()
        .chain_update(CONFLICT_ID_DOMAIN)
        .chain_update(original.as_bytes())
        .chain_update(lost_modified.to_le_bytes())
        .finalize();
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = (bytes[6] & 0x0f) | 0x50;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    ItemId::from_bytes(bytes)
}

fn conflict_copy(lost: &Element, id: ItemId, suffix: &str) -> Element {
    let mut copy = lost.duplicate();
    copy.remove_children(|e| e.name == "History" || e.name == "PreviousParentGroup");
    copy.ensure_child("UUID").set_text(encode_id(id));
    if let Some(value) = copy
        .elements_mut()
        .filter(|e| e.name == "String")
        .find(|s| s.child("Key").and_then(Element::text).as_deref() == Some("Title"))
        .and_then(|s| s.child_mut("Value"))
    {
        let protected_title = value
            .secret()
            .map(|secret| format!("{}{suffix}", secret.expose()));
        if let Some(titled) = protected_title {
            value.set_secret(SecretString::new(titled));
        } else {
            let titled = format!("{}{suffix}", value.text().unwrap_or_default());
            value.set_text(titled);
        }
    }
    copy.children.push(Node::Element(Element::new("History")));
    copy
}

/// A current version taken from the other side keeps this side's place.
fn keep_place(taken: &mut Element, ours: &Element) {
    let place = ours
        .child("Times")
        .and_then(|t| t.child("LocationChanged"))
        .and_then(Element::text);
    if let Some(place) = place {
        taken
            .ensure_child("Times")
            .ensure_child("LocationChanged")
            .set_text(place);
    }
    taken.remove_children(|e| e.name == "PreviousParentGroup");
    if let Some(previous) = ours.child("PreviousParentGroup") {
        taken.children.push(Node::Element(previous.duplicate()));
    }
}

/// An entry that moved on the other side: where to, when, and where it was.
struct Move {
    entry: ItemId,
    target: ItemId,
    at: i64,
    from: Option<ItemId>,
}

#[derive(Default)]
struct Plan {
    /// Whole new elements for entries here.
    updates: HashMap<ItemId, Element>,
    /// Entries to put in a group here.
    adds: Vec<(ItemId, Element)>,
    moves: Vec<Move>,
}

#[allow(clippy::too_many_arguments)]
fn merge_entries(
    local: &mut Element,
    local_bins: &mut Binaries,
    remote_bins: &Binaries,
    remote: &Tree<'_>,
    groups: &GroupMap,
    local_deleted: &HashMap<ItemId, i64>,
    context: &Context<'_>,
    report: &mut MergeReport,
) {
    let plan = {
        let Some(local_tree) = Tree::of(local) else {
            return;
        };
        let ours: HashMap<ItemId, &Rec<'_>> =
            local_tree.entries.iter().map(|e| (e.id, e)).collect();
        plan_entries(
            &ours,
            local_bins,
            remote,
            remote_bins,
            groups,
            local_deleted,
            context,
            report,
        )
    };
    let Plan {
        mut updates,
        adds,
        moves,
    } = plan;
    let Some(root) = root_group_mut(local) else {
        return;
    };
    if !updates.is_empty() {
        replace_entries(root, &mut updates);
    }
    for (parent, entry) in adds {
        if let Some(group) = find_group_mut(root, parent) {
            put_entry(group, entry);
        }
    }
    for Move {
        entry: id,
        target,
        at,
        from,
    } in moves
    {
        let Some(mut entry) = take_child(root, "Entry", id) else {
            continue;
        };
        set_child_times(&mut entry, "LocationChanged", at);
        if context.minor_version >= 1 {
            if let Some(previous) = from {
                set_child_text(&mut entry, "PreviousParentGroup", &encode_id(previous));
            }
        }
        match find_group_mut(root, target) {
            Some(group) => {
                put_entry(group, entry);
                report.entries_moved += 1;
            }
            None => {
                // Not reached: the target was checked to exist. Never lose the entry.
                put_entry(root, entry);
            }
        }
    }
}

fn replace_entries(group: &mut Element, updates: &mut HashMap<ItemId, Element>) {
    for child in group.elements_mut() {
        match child.name.as_str() {
            "Entry" => {
                if let Some(new) = id_of(child).and_then(|id| updates.remove(&id)) {
                    *child = new;
                }
            }
            "Group" => replace_entries(child, updates),
            _ => {}
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[allow(clippy::too_many_lines)]
fn plan_entries(
    index: &HashMap<ItemId, &Rec<'_>>,
    local_bins: &mut Binaries,
    remote: &Tree<'_>,
    remote_bins: &Binaries,
    groups: &GroupMap,
    local_deleted: &HashMap<ItemId, i64>,
    context: &Context<'_>,
    report: &mut MergeReport,
) -> Plan {
    let mut plan = Plan::default();
    let mut taken_ids: HashSet<ItemId> = index.keys().copied().collect();
    for theirs in &remote.entries {
        let Some(their_parent) = theirs.parent else {
            continue;
        };
        let Some(ours) = index.get(&theirs.id) else {
            let deleted_at = local_deleted.get(&theirs.id).copied();
            if deleted_at.is_some_and(|at| at >= modified(theirs.element)) {
                continue;
            }
            let entry = import(theirs.element, remote_bins, local_bins);
            plan.adds.push((groups.resolve(their_parent), entry));
            taken_ids.insert(theirs.id);
            if deleted_at.is_some() {
                report.entries_restored += 1;
            } else {
                report.entries_added += 1;
            }
            continue;
        };

        let local_side = Side {
            element: ours.element,
            bins: local_bins,
            digest: digest(ours.element, local_bins),
        };
        let remote_side = Side {
            element: theirs.element,
            bins: remote_bins,
            digest: digest(theirs.element, remote_bins),
        };
        let relation = relate(&local_side, &remote_side);
        let (local_el, remote_el) = (ours.element, theirs.element);
        let (local_digest, remote_digest) = (local_side.digest, remote_side.digest);
        let local_history_len = history_of(local_el).len();

        if located(remote_el) > located(local_el) {
            if let Some(target) = groups.exact(their_parent) {
                if Some(target) != ours.parent {
                    plan.moves.push(Move {
                        entry: theirs.id,
                        target,
                        at: located(remote_el),
                        from: ours.parent,
                    });
                }
            }
        }

        let takes_remote = match relation {
            Relation::Same | Relation::LocalNewer => false,
            Relation::RemoteNewer => true,
            Relation::Diverged => match modified(remote_el).cmp(&modified(local_el)) {
                std::cmp::Ordering::Greater => true,
                std::cmp::Ordering::Less => false,
                std::cmp::Ordering::Equal => remote_digest > local_digest,
            },
        };
        let here = Pick {
            element: local_el,
            from_remote: false,
        };
        let there = Pick {
            element: remote_el,
            from_remote: true,
        };
        // The version that is not kept as the current one still ends up in the history.
        let extra: Vec<Pick<'_>> = match relation {
            Relation::Same => Vec::new(),
            Relation::LocalNewer => vec![there],
            Relation::RemoteNewer => vec![here],
            Relation::Diverged => vec![if takes_remote { here } else { there }],
        };
        let history = combined_history(
            local_el,
            local_bins,
            remote_el,
            remote_bins,
            &extra,
            context.max_history,
        );
        let history_changed =
            history.iter().any(|p| p.from_remote) || history.len() != local_history_len;

        if relation == Relation::Diverged {
            let lost = if takes_remote { here } else { there };
            let copy_id = conflict_id(theirs.id, modified(lost.element));
            let skip = taken_ids.contains(&copy_id)
                || local_deleted
                    .get(&copy_id)
                    .is_some_and(|at| *at >= modified(lost.element));
            if let (false, Some(parent)) = (skip, ours.parent) {
                let lost_owned = if lost.from_remote {
                    import(lost.element, remote_bins, local_bins)
                } else {
                    lost.element.duplicate()
                };
                let copy = conflict_copy(&lost_owned, copy_id, &context.options.conflict_suffix);
                plan.adds.push((parent, copy));
                taken_ids.insert(copy_id);
                report.conflicts.push(copy_id);
            }
        }

        if takes_remote {
            let mut taken = import(remote_el, remote_bins, local_bins);
            keep_place(&mut taken, local_el);
            let versions = history_element(&history, remote_bins, local_bins);
            report.history_versions_added += history.iter().filter(|p| p.from_remote).count();
            plan.updates
                .insert(theirs.id, with_history(taken, versions));
            report.entries_updated += 1;
        } else if history_changed {
            let versions = history_element(&history, remote_bins, local_bins);
            report.history_versions_added += history.iter().filter(|p| p.from_remote).count();
            plan.updates
                .insert(theirs.id, with_history(local_el.duplicate(), versions));
            report.histories_merged += 1;
        }
    }
    plan
}

// MARK: Database settings

fn meta_time(meta: Option<&Element>, name: &str) -> i64 {
    meta.and_then(|m| m.child(name))
        .and_then(Element::text)
        .and_then(|t| time::decode(&t))
        .unwrap_or(i64::MIN)
}

fn copy_child(into: &mut Element, from: &Element, name: &str) {
    if let Some(source) = from.child(name) {
        into.remove_children(|e| e.name == name);
        into.children.push(Node::Element(source.duplicate()));
    }
}

/// `related`: the other database is a version of this one. Only then do its settings count; the
/// images of an unrelated database still come over, for the entries that bring them.
fn merge_meta(
    local: &mut Element,
    remote: &Element,
    groups: &GroupMap,
    related: bool,
    report: &mut MergeReport,
) {
    let Some(theirs) = remote.child("Meta") else {
        return;
    };
    let ours_snapshot_times: Vec<(&str, i64)> = [
        "DatabaseNameChanged",
        "DatabaseDescriptionChanged",
        "DefaultUserNameChanged",
        "RecycleBinChanged",
        "EntryTemplatesGroupChanged",
    ]
    .iter()
    .map(|name| (*name, meta_time(local.child("Meta"), name)))
    .collect();
    let ours_time = |name: &str| {
        ours_snapshot_times
            .iter()
            .find(|(n, _)| *n == name)
            .map_or(i64::MIN, |(_, t)| *t)
    };
    let group_here = |element: Option<&Element>| {
        element
            .and_then(|e| parse_uuid(e).ok())
            .is_none_or(|id| id.is_nil() || groups.local.contains_key(&id))
    };
    let ours = local.ensure_child("Meta");

    if related {
        for (value, changed) in [
            ("DatabaseName", "DatabaseNameChanged"),
            ("DatabaseDescription", "DatabaseDescriptionChanged"),
            ("DefaultUserName", "DefaultUserNameChanged"),
        ] {
            if meta_time(Some(theirs), changed) > ours_time(changed) {
                copy_child(ours, theirs, value);
                copy_child(ours, theirs, changed);
                report.settings_updated = true;
            }
        }
        if meta_time(Some(theirs), "RecycleBinChanged") > ours_time("RecycleBinChanged")
            && group_here(theirs.child("RecycleBinUUID"))
        {
            for name in ["RecycleBinEnabled", "RecycleBinUUID", "RecycleBinChanged"] {
                copy_child(ours, theirs, name);
            }
            report.settings_updated = true;
        }
        if meta_time(Some(theirs), "EntryTemplatesGroupChanged")
            > ours_time("EntryTemplatesGroupChanged")
            && group_here(theirs.child("EntryTemplatesGroup"))
        {
            copy_child(ours, theirs, "EntryTemplatesGroup");
            copy_child(ours, theirs, "EntryTemplatesGroupChanged");
            report.settings_updated = true;
        }
    }

    // Images: the ones missing here come over; none is replaced or removed.
    if let Some(icons) = theirs.child("CustomIcons") {
        let known: HashSet<ItemId> = ours
            .child("CustomIcons")
            .map(|i| i.elements().filter_map(id_of).collect())
            .unwrap_or_default();
        let missing: Vec<Element> = icons
            .elements()
            .filter(|i| i.name == "Icon")
            .filter(|i| id_of(i).is_some_and(|id| !known.contains(&id)))
            .map(Element::duplicate)
            .collect();
        if !missing.is_empty() {
            report.icons_added += missing.len();
            let target = ours.ensure_child("CustomIcons");
            target
                .children
                .extend(missing.into_iter().map(Node::Element));
        }
    }

    if related {
        merge_custom_data(ours, theirs, report);
    }
}

/// Custom data: the items missing here come over; a shared key follows the later change.
fn merge_custom_data(ours: &mut Element, theirs: &Element, report: &mut MergeReport) {
    if let Some(data) = theirs.child("CustomData") {
        let item_time = |item: &Element| {
            item.child("LastModificationTime")
                .and_then(Element::text)
                .and_then(|t| time::decode(&t))
                .unwrap_or(i64::MIN)
        };
        for item in data.elements().filter(|i| i.name == "Item") {
            let Some(key) = item.child("Key").and_then(Element::text) else {
                continue;
            };
            let target = ours.ensure_child("CustomData");
            let existing = target.elements_mut().find(|i| {
                i.name == "Item" && i.child("Key").and_then(Element::text).as_deref() == Some(&key)
            });
            if let Some(found) = existing {
                if item_time(item) > item_time(found) {
                    *found = item.duplicate();
                    report.settings_updated = true;
                }
            } else {
                target.children.push(Node::Element(item.duplicate()));
                report.settings_updated = true;
            }
        }
    }
}

// MARK: Deletions

fn purge_entries(group: &mut Element, records: &HashMap<ItemId, i64>, count: &mut usize) {
    let removed = group
        .remove_children(|e| {
            e.name == "Entry"
                && id_of(e)
                    .and_then(|id| records.get(&id))
                    .is_some_and(|at| *at >= modified(e))
        })
        .len();
    *count += removed;
    for child in group.elements_mut().filter(|e| e.name == "Group") {
        purge_entries(child, records, count);
    }
}

fn purge_groups(
    group: &mut Element,
    records: &HashMap<ItemId, i64>,
    keep: Option<ItemId>,
    count: &mut usize,
) {
    for child in group.elements_mut().filter(|e| e.name == "Group") {
        purge_groups(child, records, keep, count);
    }
    let removed = group
        .remove_children(|e| {
            e.name == "Group"
                && !e.elements().any(|c| c.name == "Entry" || c.name == "Group")
                && id_of(e).is_some_and(|id| {
                    Some(id) != keep && records.get(&id).is_some_and(|at| *at >= modified(e))
                })
        })
        .len();
    *count += removed;
}

fn apply_deletions(
    local: &mut Element,
    local_records: &HashMap<ItemId, i64>,
    remote_records: &HashMap<ItemId, i64>,
    report: &mut MergeReport,
) {
    let mut records = local_records.clone();
    for (id, at) in remote_records {
        records
            .entry(*id)
            .and_modify(|known| *known = (*known).max(*at))
            .or_insert(*at);
    }
    if records.is_empty() {
        return;
    }
    let bin = recycle_bin(local);
    if let Some(root) = root_group_mut(local) {
        purge_entries(root, &records, &mut report.entries_deleted);
        // Only children are looked at, so the root itself is never removed.
        purge_groups(root, &records, bin, &mut report.groups_deleted);
    }
    let changed = records
        .iter()
        .filter(|(id, at)| local_records.get(id) != Some(at))
        .count();
    if changed > 0 {
        report.deletion_records_added = changed;
        let mut sorted: Vec<(&ItemId, &i64)> = records.iter().collect();
        sorted.sort_by_key(|(id, at)| (**at, **id));
        let objects = local
            .child_mut("Root")
            .map(|r| r.ensure_child("DeletedObjects"));
        if let Some(objects) = objects {
            objects.remove_children(|e| e.name == "DeletedObject");
            for (id, at) in sorted {
                objects.children.push(Node::Element(
                    Element::new("DeletedObject")
                        .with_child(Element::with_text("UUID", encode_id(*id)))
                        .with_child(Element::with_text("DeletionTime", time::encode(*at))),
                ));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use zeroize::Zeroizing;

    fn uuid(n: u8) -> ItemId {
        ItemId::from_bytes([n; 16])
    }

    fn times(modified: i64, located: i64) -> Element {
        Element::new("Times")
            .with_child(Element::with_text("CreationTime", time::encode(1)))
            .with_child(Element::with_text(
                "LastModificationTime",
                time::encode(modified),
            ))
            .with_child(Element::with_text("LocationChanged", time::encode(located)))
    }

    fn string(key: &str, value: &str, protected: bool) -> Element {
        let mut text = Element::new("Value");
        if protected {
            text.set_attribute("Protected", "True");
            text.set_secret(SecretString::new(value.to_owned()));
        } else {
            text.set_text(value);
        }
        Element::new("String")
            .with_child(Element::with_text("Key", key))
            .with_child(text)
    }

    fn entry(n: u8, title: &str, password: &str, modified: i64, history: Vec<Element>) -> Element {
        let mut versions = Element::new("History");
        versions
            .children
            .extend(history.into_iter().map(Node::Element));
        Element::new("Entry")
            .with_child(Element::with_text("UUID", encode_id(uuid(n))))
            .with_child(times(modified, 1))
            .with_child(string("Title", title, false))
            .with_child(string("Password", password, true))
            .with_child(versions)
    }

    fn group(n: u8, name: &str, modified: i64, located: i64, children: Vec<Element>) -> Element {
        let mut group = Element::new("Group")
            .with_child(Element::with_text("UUID", encode_id(uuid(n))))
            .with_child(Element::with_text("Name", name))
            .with_child(times(modified, located));
        group
            .children
            .extend(children.into_iter().map(Node::Element));
        group
    }

    fn document(root: Element, meta: Element, deleted: &[(u8, i64)]) -> Element {
        let mut objects = Element::new("DeletedObjects");
        for (n, at) in deleted {
            objects.children.push(Node::Element(
                Element::new("DeletedObject")
                    .with_child(Element::with_text("UUID", encode_id(uuid(*n))))
                    .with_child(Element::with_text("DeletionTime", time::encode(*at))),
            ));
        }
        Element::new("KeePassFile")
            .with_child(meta)
            .with_child(Element::new("Root").with_child(root).with_child(objects))
    }

    fn run(local: &mut Element, remote: &Element) -> MergeReport {
        let mut bins: Binaries = Vec::new();
        merge(
            local,
            &mut bins,
            remote,
            &Vec::<Zeroizing<Vec<u8>>>::new(),
            1,
            &MergeOptions::default(),
        )
        .expect("merges")
    }

    fn side<'a>(element: &'a Element, bins: &'a Binaries) -> Side<'a> {
        Side {
            element,
            bins,
            digest: digest(element, bins),
        }
    }

    fn relation(local: &Element, remote: &Element) -> Relation {
        let bins: Binaries = Vec::new();
        relate(&side(local, &bins), &side(remote, &bins))
    }

    #[test]
    fn the_relation_is_read_from_the_histories() {
        let v1 = entry(1, "Mail", "v1", 10, vec![]);
        // The other side's file is the next version of ours, and says so.
        let next = entry(
            1,
            "Mail",
            "v2",
            20,
            vec![entry(1, "Mail", "v1", 10, vec![])],
        );
        assert_eq!(relation(&v1, &next), Relation::RemoteNewer);
        assert_eq!(relation(&next, &v1), Relation::LocalNewer);

        // A history that holds nothing, or starts after our version, proves nothing against it.
        let silent = entry(1, "Mail", "v2", 20, vec![]);
        assert_eq!(relation(&v1, &silent), Relation::RemoteNewer);
        let pruned = entry(
            1,
            "Mail",
            "v3",
            20,
            vec![entry(1, "Mail", "v2", 15, vec![])],
        );
        assert_eq!(relation(&v1, &pruned), Relation::RemoteNewer);

        // A history from before our version that does not hold it: both went their own way.
        let apart = entry(1, "Mail", "v2", 20, vec![entry(1, "Mail", "v0", 5, vec![])]);
        assert_eq!(relation(&v1, &apart), Relation::Diverged);
        // Same moment, different content: nothing can order them.
        let tie = entry(1, "Mail", "other", 10, vec![]);
        assert_eq!(relation(&v1, &tie), Relation::Diverged);
        // Touched later, nothing changed: the same entry.
        let touched = entry(1, "Mail", "v1", 99, vec![]);
        assert_eq!(relation(&v1, &touched), Relation::Same);
    }

    #[test]
    fn what_an_entry_holds_is_not_where_it_is_or_how_its_fields_are_ordered() {
        let bins: Binaries = Vec::new();
        let a = entry(1, "Mail", "pw", 10, vec![]);
        let mut b = entry(
            1,
            "Mail",
            "pw",
            50,
            vec![entry(1, "Mail", "old", 5, vec![])],
        );
        b.children.reverse();
        assert_eq!(digest(&a, &bins), digest(&b, &bins));
        let other = entry(1, "Mail", "pw2", 10, vec![]);
        assert_ne!(digest(&a, &bins), digest(&other, &bins));
        // Protected or not is part of what a field is.
        let mut plain = entry(1, "Mail", "pw", 10, vec![]);
        plain.children.retain(|n| match n {
            Node::Element(e) => {
                e.name != "String"
                    || e.child("Key").and_then(Element::text).as_deref() != Some("Password")
            }
            _ => true,
        });
        plain
            .children
            .push(Node::Element(string("Password", "pw", false)));
        assert_ne!(digest(&a, &bins), digest(&plain, &bins));
    }

    #[test]
    fn the_copy_of_a_lost_version_has_an_id_both_devices_make() {
        let one = conflict_id(uuid(1), 100);
        assert_eq!(one, conflict_id(uuid(1), 100));
        assert_ne!(one, conflict_id(uuid(1), 101));
        assert_ne!(one, conflict_id(uuid(2), 100));
        assert_ne!(one, uuid(1));
    }

    #[test]
    fn a_group_is_not_moved_into_its_own_subtree() {
        // Here B is inside A. There, later, A was moved into B.
        let mut local = document(
            group(
                100,
                "Root",
                1,
                1,
                vec![group(1, "A", 1, 1, vec![group(2, "B", 1, 1, vec![])])],
            ),
            Element::new("Meta"),
            &[],
        );
        let remote = document(
            group(
                100,
                "Root",
                1,
                1,
                vec![group(2, "B", 1, 1, vec![group(1, "A", 1, 50, vec![])])],
            ),
            Element::new("Meta"),
            &[],
        );
        let report = run(&mut local, &remote);
        assert_eq!(report.groups_moved, 0, "{report:?}");
        let tree = Tree::of(&local).unwrap();
        let parent_of_a = tree.groups.iter().find(|g| g.id == uuid(1)).unwrap().parent;
        assert_eq!(parent_of_a, Some(uuid(100)));
    }

    #[test]
    fn a_group_with_something_new_in_it_is_not_deleted() {
        let mut local = document(
            group(
                100,
                "Root",
                1,
                1,
                vec![group(
                    1,
                    "Work",
                    5,
                    1,
                    vec![entry(7, "New here", "pw", 30, vec![])],
                )],
            ),
            Element::new("Meta"),
            &[],
        );
        // There the group was deleted at 20 (and its old entry with it).
        let remote = document(
            group(100, "Root", 1, 1, vec![]),
            Element::new("Meta"),
            &[(1, 20), (6, 20)],
        );
        let report = run(&mut local, &remote);
        assert_eq!(report.groups_deleted, 0, "{report:?}");
        assert_eq!(report.entries_deleted, 0);
        let tree = Tree::of(&local).unwrap();
        assert!(tree.groups.iter().any(|g| g.id == uuid(1)));
        assert!(tree.entries.iter().any(|e| e.id == uuid(7)));
        // The records are kept, so it goes once it is empty.
        assert!(deleted_objects(&local).contains_key(&uuid(1)));

        // Emptied, a later merge of the same records removes it.
        let mut emptied = document(
            group(100, "Root", 1, 1, vec![group(1, "Work", 5, 1, vec![])]),
            Element::new("Meta"),
            &[],
        );
        let report = run(&mut emptied, &remote);
        assert_eq!(report.groups_deleted, 1, "{report:?}");
    }

    #[test]
    fn the_root_and_the_recycle_bin_are_never_removed() {
        let meta = Element::new("Meta")
            .with_child(Element::with_text("RecycleBinUUID", encode_id(uuid(1))));
        let mut local = document(
            group(100, "Root", 1, 1, vec![group(1, "Bin", 5, 1, vec![])]),
            meta,
            &[],
        );
        let remote = document(
            group(100, "Root", 1, 1, vec![]),
            Element::new("Meta"),
            &[(1, 20), (100, 20)],
        );
        let report = run(&mut local, &remote);
        assert_eq!(report.groups_deleted, 0, "{report:?}");
        assert!(root_group(&local).is_some());
        assert!(
            Tree::of(&local)
                .unwrap()
                .groups
                .iter()
                .any(|g| g.id == uuid(1))
        );
    }

    #[test]
    fn settings_follow_their_own_change_time_and_images_are_united() {
        let local_meta = Element::new("Meta")
            .with_child(Element::with_text("DatabaseName", "Old"))
            .with_child(Element::with_text("DatabaseNameChanged", time::encode(10)))
            .with_child(Element::with_text("DatabaseDescription", "Mine"))
            .with_child(Element::with_text(
                "DatabaseDescriptionChanged",
                time::encode(50),
            ))
            .with_child(
                Element::new("CustomIcons").with_child(
                    Element::new("Icon")
                        .with_child(Element::with_text("UUID", encode_id(uuid(40))))
                        .with_child(Element::with_text("Data", "AAAA")),
                ),
            );
        let remote_meta = Element::new("Meta")
            .with_child(Element::with_text("DatabaseName", "New"))
            .with_child(Element::with_text("DatabaseNameChanged", time::encode(20)))
            .with_child(Element::with_text("DatabaseDescription", "Theirs"))
            .with_child(Element::with_text(
                "DatabaseDescriptionChanged",
                time::encode(5),
            ))
            .with_child(
                Element::new("CustomIcons")
                    .with_child(
                        Element::new("Icon")
                            .with_child(Element::with_text("UUID", encode_id(uuid(40))))
                            .with_child(Element::with_text("Data", "BBBB")),
                    )
                    .with_child(
                        Element::new("Icon")
                            .with_child(Element::with_text("UUID", encode_id(uuid(41))))
                            .with_child(Element::with_text("Data", "CCCC")),
                    ),
            );
        let root = || group(100, "Root", 1, 1, vec![]);
        let mut local = document(root(), local_meta, &[]);
        let remote = document(root(), remote_meta, &[]);
        let report = run(&mut local, &remote);
        assert!(report.settings_updated);
        assert_eq!(report.icons_added, 1, "one new image; none replaced");
        let meta = local.child("Meta").unwrap();
        let text = |name: &str| meta.child(name).and_then(Element::text).unwrap();
        assert_eq!(text("DatabaseName"), "New", "theirs is later");
        assert_eq!(text("DatabaseDescription"), "Mine", "ours is later");
        let icons = meta.child("CustomIcons").unwrap();
        assert_eq!(icons.elements().count(), 2);
        let kept = icons
            .elements()
            .find(|i| id_of(i) == Some(uuid(40)))
            .unwrap();
        assert_eq!(kept.child("Data").and_then(Element::text).unwrap(), "AAAA");
    }

    #[test]
    fn custom_data_is_united_and_a_shared_key_follows_the_later_change() {
        let item = |key: &str, value: &str, at: i64| {
            Element::new("Item")
                .with_child(Element::with_text("Key", key))
                .with_child(Element::with_text("Value", value))
                .with_child(Element::with_text("LastModificationTime", time::encode(at)))
        };
        let meta = |items: Vec<Element>| {
            let mut data = Element::new("CustomData");
            data.children.extend(items.into_iter().map(Node::Element));
            Element::new("Meta").with_child(data)
        };
        let root = || group(100, "Root", 1, 1, vec![]);
        let mut local = document(
            root(),
            meta(vec![item("shared", "mine", 50), item("only-here", "x", 1)]),
            &[],
        );
        let remote = document(
            root(),
            meta(vec![
                item("shared", "theirs", 10),
                item("only-there", "y", 1),
            ]),
            &[],
        );
        let report = run(&mut local, &remote);
        assert!(report.settings_updated);
        let value = |key: &str| {
            local
                .child("Meta")
                .and_then(|m| m.child("CustomData"))
                .and_then(|d| {
                    d.elements()
                        .find(|i| i.child("Key").and_then(Element::text).as_deref() == Some(key))
                })
                .and_then(|i| i.child("Value").and_then(Element::text))
        };
        assert_eq!(value("shared").as_deref(), Some("mine"), "ours is later");
        assert_eq!(value("only-here").as_deref(), Some("x"));
        assert_eq!(value("only-there").as_deref(), Some("y"));
    }

    #[test]
    fn an_unrelated_database_brings_its_items_and_images_but_not_its_settings() {
        let meta = |name: &str, at: i64, icon: u8| {
            Element::new("Meta")
                .with_child(Element::with_text("DatabaseName", name))
                .with_child(Element::with_text("DatabaseNameChanged", time::encode(at)))
                .with_child(
                    Element::new("CustomIcons").with_child(
                        Element::new("Icon")
                            .with_child(Element::with_text("UUID", encode_id(uuid(icon))))
                            .with_child(Element::with_text("Data", "AAAA")),
                    ),
                )
        };
        let mut local = document(group(100, "Root", 1, 1, vec![]), meta("Mine", 10, 40), &[]);
        let remote = document(
            group(
                101,
                "Root",
                1,
                1,
                vec![entry(7, "Theirs", "pw", 30, vec![])],
            ),
            meta("Other", 99, 41),
            &[],
        );
        let mut bins: Binaries = Vec::new();
        let none = Vec::<Zeroizing<Vec<u8>>>::new();
        let refused = merge(
            &mut local,
            &mut bins,
            &remote,
            &none,
            1,
            &MergeOptions::default(),
        );
        assert_eq!(refused.unwrap_err(), MergeError::DifferentVault);

        let options = MergeOptions {
            allow_unrelated: true,
            ..MergeOptions::default()
        };
        let report = merge(&mut local, &mut bins, &remote, &none, 1, &options).unwrap();
        assert_eq!(report.entries_added, 1);
        assert_eq!(report.icons_added, 1, "its image comes with its entries");
        assert!(!report.settings_updated);
        let meta = local.child("Meta").unwrap();
        assert_eq!(
            meta.child("DatabaseName").and_then(Element::text).unwrap(),
            "Mine",
            "a newer name over there does not rename this database"
        );
    }

    #[test]
    fn a_group_that_is_gone_here_but_has_news_there_goes_to_the_nearest_group_that_exists() {
        // The group Work was deleted here at 20; there it has an entry edited at 30.
        let mut local = document(
            group(100, "Root", 1, 1, vec![]),
            Element::new("Meta"),
            &[(1, 20)],
        );
        let remote = document(
            group(
                100,
                "Root",
                1,
                1,
                vec![group(
                    1,
                    "Work",
                    5,
                    1,
                    vec![entry(7, "VPN", "pw", 30, vec![])],
                )],
            ),
            Element::new("Meta"),
            &[],
        );
        let report = run(&mut local, &remote);
        assert_eq!(
            report.groups_added, 0,
            "the group stays deleted: {report:?}"
        );
        assert_eq!(report.entries_restored, 0);
        assert_eq!(
            report.entries_added, 1,
            "the entry was never deleted: {report:?}"
        );
        let tree = Tree::of(&local).unwrap();
        let moved = tree.entries.iter().find(|e| e.id == uuid(7)).unwrap();
        assert_eq!(moved.parent, Some(uuid(100)), "in the root, not lost");
    }
}
