# ADR 0023: Merging two versions of a database

- Status: Accepted (core, bindings and the Android «changed elsewhere» dialog; iPhone and the extension do not use it yet)
- Date: 2026-10-21
- Extends [ADR 0014](0014-kdbx-write-and-save.md) (lossless document, history, `DeletedObjects`) and
  [ADR 0015](0015-android-safe-save.md) (changed-elsewhere detection)
- Threat model: [SAVE_THREAT_MODEL](../security/SAVE_THREAT_MODEL.md)

## Context

A database in a cloud folder is edited from several devices. Today a save that finds the file changed
by someone else stops with *Vault changed elsewhere* and offers *Reload* (the person's edits are
thrown away), *Save a copy* or *Cancel*. Nothing combines the two versions, and a copy that a sync
client made of a clash (`conflicted copy`, `.sync-conflict-…`, `(1)`) can only be opened as another
database.

The core already writes what KeePass-family apps use to synchronize: `LastModificationTime`,
`LocationChanged`, an entry's `History`, and a `DeletedObject` for every permanent deletion. So a
merge needs no new data in the file and keeps the database readable by KeePassXC and the others.

## Decision

`VaultSession::merge(remote: Vault, &MergeOptions) -> Result<MergeReport, MergeError>` combines an
opened second version (`open_vault` with its own credentials) into the open session. It is one pure
function over the two lossless documents (`kdbx/merge.rs`), so Android, iPhone and the extension get
the same behaviour. The session applies it like any edit: on a copy of the document, kept only if the
result still reads as a vault, and the session becomes dirty only if something changed.

### Identity

Items are matched by UUID. The two root groups are always matched to each other. By default the roots
must have the same UUID (the same database); `allow_unrelated` merges any two databases, and then
every item is simply new. Root properties are never overwritten.

### Entries present in both

The order of the two current versions is decided from their histories, not from clocks alone:

| Case | Relation | Result |
| --- | --- | --- |
| Same content | Same | nothing changes (histories are still combined) |
| Remote is newer and local's current version is in remote's history | linear | take remote, local goes to history |
| Local is newer and remote's current version is in local's history | linear | keep local |
| Neither version is in the other's history | **diverged** | newer wins, the other is kept as a *conflict copy* |
| Same modification time, different content | diverged | a content digest picks the winner on both devices alike |

When a history cannot say (the other side has none, or its oldest kept version is newer than ours:
it was pruned, disabled, or written by an app that keeps none), the versions are treated as linear and
the newer one wins, which is the plain KeePass rule. A *conflict copy* would otherwise appear for every
stale device of a person who turned history off.

The two histories are combined, de-duplicated by (modification time, content) and cut to
`HistoryMaxItems`, oldest first. On divergence the loser's current version is added to the winner's
history too, so the next merge sees a linear relation.

**The conflict copy** is a new entry beside the winner: the loser's content, no history, title +
`options.conflict_suffix` (default ` (conflict)`; the app passes a localized one). Its UUID is derived
from the original UUID and the loser's modification time (never from content), so two devices that
merge the same pair make the same copy and merging twice makes one. History is a Keep+ screen, a copy
is visible to everybody: this is why both exist.

Moves follow `LocationChanged`: the later one wins, and only if the target group exists here and the
move cannot put a group inside itself. Moving to the Recycle Bin is a move, so it travels.

### Entries present on one side

* In remote only, no local deletion record: added to the matching group (or the nearest ancestor
  that exists here, or the root).
* In remote only, and a local `DeletedObject` newer than or equal to the entry's modification time:
  stays deleted. If the entry was changed after the deletion it comes back.
* In local only and remote deleted it, and local was not changed after the deletion: deleted here. If
  it was changed after, it stays.
* In local only, no remote record: stays (a new local entry).

Deletion records are united (the later time per UUID). A live item is removed only if a record has a
time not earlier than the item's last modification, so a restored item is not removed again by a
third device. A group is removed only when it is empty after everything else, and the root and the
Recycle Bin are never removed.

### Groups

Properties (name, notes, icon, flags) come from the side with the later `LastModificationTime`,
the local side on a tie. New remote groups are created under their mapped parent. Moves as for
entries.

### Database settings

Only for a database that is a version of this one. An unrelated database (`allow_unrelated`) brings its
items and the images they use, never its settings: a later name over there does not rename this database.

Name, description, default user name, the Recycle Bin and the template group follow their own
`…Changed` time. A Recycle Bin that points at a group missing here is ignored. Custom icons and
custom data are united (nothing is overwritten). The binary pool is united by content; binaries
nothing refers to any more are dropped. `HistoryMaxItems`, `MemoryProtection` and the key derivation
parameters stay as they are here.

### Bindings

One call on an open session, the same on every platform. The other database is passed as its encrypted
bytes with its own credentials (password, key file, or the retained unlock material); it is opened for
the merge only, takes no session slot and is gone when the call returns. The result is a report of
counts and the ids of the conflict copies; no value of the vault is in it.

| Platform | Call | Errors |
| --- | --- | --- |
| Rust | `VaultSession::merge(Vault, &MergeOptions)` | `MergeError` |
| Kotlin / Swift (UniFFI) | `merge(data, credentials, options)` on the session handle | `VaultMergeError`: the reasons of `openVault` for the other file, plus `DifferentVault`, `ReadOnly`, `InvalidResult`, `SessionClosed` |
| WebAssembly | `mergeVault(handle, data, password, keyFile, material, conflictSuffix, allowUnrelated)`; the password is wiped | error codes `different_vault`, `read_only`, `invalid_result`, and the `openVault` codes |

### What the platform does

The core returns a changed session; the platform saves it with the ordinary safe save. Because the
merged state contains the remote file, the baseline hash for the changed-elsewhere check must be the
hash of the remote bytes that were merged, not the one taken at unlock. The remote file is opened with
its own credentials (a changed password or key file on the other device means the person is asked).

**The session's own key.** A session already holds the transformed key it saves with, so a file that another device
running this core saved (same key derivation parameters, same password) opens with it and the person types nothing:
`VaultSession::merge_with_session_key(data, &MergeOptions)`, `mergeWithSessionKey` in Kotlin. A key that does not fit
(another program such as KeePassXC rewrote the file with new parameters, or the password changed) fails as
`InvalidCredentials`; only then does the app ask for the master password and call `merge`. Android's dialog offers
*Merge* next to *Reload* and *Save a copy* and does exactly this.

## Consequences

* Two devices that edit different entries, or different fields' worth of time apart, combine without
  losing anything; true clashes produce a visible copy instead of a silent winner.
* Merging is convergent: merging A into B and B into A gives the same entries, and repeating a merge
  changes nothing.
* A conflict copy can be an unwanted duplicate for a person who edited the same entry twice with a
  history-less app between syncs; they delete one entry.
* Times come from device clocks. A device whose clock is far ahead wins ties of "newer"; the loser is
  still kept (copy and history), so the cost is a duplicate, not a loss.
* The comparison hashes secret values in memory (to order a tie and to tell versions apart). The
  hashes are never stored: the copy's UUID is made from non-secret facts only.
* The remote database's passwords exist decrypted in memory for the merge and are zeroized with the
  opened `Vault`.

## Alternatives

* **Newer wins per entry, as KeePass does, with the loser only in history.** Simple, but history is a
  Keep+ screen, so a free account would lose the other version out of sight.
* **Field-level three-way merge.** Needs the common ancestor, which a file does not carry. The
  history is only a hint of one; merging fields from guesses can produce a password that never
  existed.
* **Always duplicate on any difference.** Safe, but turns every ordinary sync into duplicates.
* **A sync service in the app.** Rejected in the product principles: the database never leaves the
  person's storage through Vazie.
