# ADR 0014: KDBX writing and safe save

- Status: Accepted
- Date: 2026-10-04
- Extends [ADR 0005](0005-kdbx-engine.md) (our own KDBX 4 engine), [ADR 0006](0006-vault-session-handles.md)
  and [ADR 0011](0011-ios-vault-session-lifecycle.md)
- Threat model: [SAVE_THREAT_MODEL](../security/SAVE_THREAT_MODEL.md)

## Context

M9 lets the iPhone create, edit and delete entries and groups and write the vault back. The
rule above every other: **never corrupt the vault**. A failed or conflicting save must leave the
file exactly as it was, and an edit must never quietly drop data that this app doesn't model.

## Decision

### Writer architecture: a lossless document, not the read model

The read model (`Vault`, `VaultEntry`) is deliberately small. It has no times, icons, history,
custom data, auto-type, attachment bytes or most of the metadata. Writing it back would lose all
of that, so the writer doesn't use it.

- **`kdbx/dom.rs`** keeps the whole decrypted XML payload as an owned tree:
  - every element, attribute and text node;
  - protected values held as `SecretString` (decrypted in document order, re-encrypted in the
    same order);
  - comments and processing instructions are dropped, and the text around them is joined (a
    fuzz finding);
  - nesting depth is bounded.
- **The read model is a projection of that tree.** Edits change the tree, and the projection is
  rebuilt after each edit, so there is one source of truth.
- **`SaveState`** keeps from the opened file:
  - the version;
  - every outer header field except the master seed and IV, byte for byte (cipher, compression,
    **KDF parameters including the salt**, public custom data, unknown fields);
  - every inner-header binary (attachments) byte for byte;
  - the **transformed key** from the unlock.

### Writing

`VaultSource::write_verified`:
1. Serialize the tree. Protected values are encrypted with a fresh 64-byte ChaCha20 inner-stream
   key from `getrandom`.
2. Build the inner header with the attachments unchanged.
3. gzip if the file was gzipped. The output buffer is sized up front, so no reallocation leaves
   copies behind.
4. Encrypt with a fresh IV (AES-256-CBC/PKCS#7 or ChaCha20) under keys derived from a fresh
   32-byte master seed.
5. Write the header, SHA-256 and header HMAC, then the HMAC-SHA-256 block stream (1 MiB
   blocks).
6. **Verify**: read the bytes back with the normal reader and the transformed key, and require
   an identical tree and identical attachments. Only then are the bytes handed to the
   platform.

The cipher, KDF, KDF parameters, compression and version never change on save. Keeping the KDF
salt means **saving needs neither the password nor a KDF run** (about 11 ms to serialize,
encrypt and verify on the iPhone 16, even for Argon2d 64 MiB). The transformed key held in
memory opens only this file with this salt. That is weaker than the composite key and the
password, both of which the session never keeps.

Interop is checked automatically by `tooling/test-vectors/interop.sh`:
- pykeepass reads our files;
- KeePassXC 2.7.12 opens them, edits them, and we read its files back.

### Mutation model

- **Edits go through the Rust session API**: `create_entry`, `update_entry`, `delete_entry`,
  `move_entry`, `create_group`, `rename_group`, `delete_group`. The vault is never shipped
  between Swift and Rust.
- **Each edit is a transaction**: it runs on a copy of the tree, and the copy replaces the working
  tree only if it still projects into a valid vault. An unchanged draft is not an edit.
- **Dirty state** is a generation counter. `prepare_save()` returns encrypted bytes and their
  generation; `mark_saved(generation)` makes the session clean only if nothing changed since.
- **KeePass semantics**:
  - UUIDs are kept, and new ones come from `getrandom`;
  - an update appends the previous version, minus its history, to `History`, trimmed to
    `Meta/HistoryMaxItems` (default 10; `HistoryMaxSize` is not enforced);
  - `LastModificationTime` and `LastAccessTime` change only on the edited item;
  - a move updates `LocationChanged`, and in 4.1 `PreviousParentGroup`;
  - standard fields are protected according to `Meta/MemoryProtection`, and custom fields per
    field;
  - other strings, attachments, icons, colours, auto-type and custom data are untouched.
- **Deletion**:
  - with `RecycleBinEnabled`, an entry or group goes to the recycle bin (the bin group is created
    if missing);
  - items already in the bin, vaults with the bin disabled, or an explicit permanent delete are
    removed, with a `DeletedObject` record for synchronizing apps;
  - a non-empty group needs an explicit confirmation;
  - the root can't be deleted.
- **Validation**: text XML 1.0 can't carry is refused. Custom field names must be unique and
  non-reserved, and tags can't contain separators.

### Password generator

It lives in the core (`generator.rs`), so every client shares it:
- the CSPRNG (`getrandom`), with rejection sampling (no modulo bias);
- at least one character from each chosen set, then a Fisher–Yates shuffle;
- 4–128 characters, with an option to exclude look-alike characters.

Passphrases are deferred until a word list is chosen and its licence reviewed.

### Save model on iPhone: edit = save

Tapping "Save" in the editor, or confirming any group or delete action, applies the edit and
**writes the file at once**. There is no separate vault-level save to forget, and auto-lock
never meets edits that exist only in memory unless a write failed. A save takes 31–54 ms on the
device, so there is no performance reason for batching. A progress indicator appears only if a
save takes longer than 500 ms.

### Atomic save (Swift, `SecurityScopedDocumentAccess.replace`)

The core never touches the file system: it returns bytes, and the platform writes them. Steps:
1. **Cloud freshness**: for an iCloud item, require `ubiquitousItemDownloadingStatus == .current`.
   Otherwise ask for the download and wait up to 15 s, then refuse (`outOfDate`). An item with
   unresolved conflicts is refused (`changedExternally`).
2. **One `NSFileCoordinator` write with `.forReplacing`.** Inside it:
   1. read the current bytes, and refuse if their SHA-256 differs from the version recorded at
      unlock (`changedExternally`);
   2. keep those bytes as the backup, and refuse if that fails (`backupFailed`);
   3. **replace atomically**: `Data.write(.atomic)`, which writes a sibling file and renames it
      over the target. If the provider doesn't allow a sibling, stage the bytes in
      `itemReplacementDirectory` and use `replaceItemAt`. Otherwise refuse. The original is
      **never overwritten in place**;
   4. read the file back and compare it byte for byte. On a mismatch, put the previous bytes
      back and report `verificationFailed`.
3. Refresh the bookmark if the provider changed it. The new version becomes the next save's
   expected version.

Only already-encrypted KDBX bytes ever reach disk: the staged file, the backup and the unsaved
copy are all complete encrypted vaults.

### External-change detection and conflicts

- At unlock, the SHA-256 of the encrypted file is recorded; it is checked again inside the
  coordinated write.
- A conflict never overwrites. The UI offers **Reload** (discard and lock), **Save a Copy…**
  (`fileExporter` with the encrypted bytes) or **Cancel**. There is no "overwrite anyway".
- **Found on the device:** inside a coordinated write, iCloud hands over the local copy, which can
  be older than a version another device has already uploaded. Without the freshness check
  above, a save 50 s after a Mac edit silently replaced it. With the check, the same race
  reports "Vault changed elsewhere".

### Backup and recovery

- **Backup**: before every replace, the replaced bytes are kept in the app's own container
  (`Application Support/Vault Copies/<fingerprint>-previous.kdbx`). One version per vault,
  `completeFileProtection`, excluded from device backups. Nothing is written into the user's
  folder. **Trade-off:** it can be older than what iCloud holds, and it lives only on this
  device. It is a safety net for a failed replace, not version history; iCloud and the provider
  keep their own versions.
- **Unsaved changes at lock**: if a save failed and the vault locks (manually after "Discard"
  isn't chosen, or by auto-lock, which can't wait for an answer), the session's encrypted
  snapshot is kept as `<fingerprint>-unsaved.kdbx`. On the next unlock a banner offers **Save a
  Copy…** or **Delete**.
- **Manual lock with unsaved changes** asks: Save / Discard Changes / Cancel.

### Failure recovery

| Failure | File | Session | UI |
| --- | --- | --- | --- |
| Encryption or verification fails in Rust | untouched | still dirty | error, Save a Copy |
| Changed elsewhere, out of date | untouched | still dirty | Reload / Save a Copy / Cancel |
| Moved, deleted, offline, not writable, no space, backup fails | untouched | still dirty | Try Again / Save a Copy |
| Read-back mismatch | previous bytes restored | still dirty | error |
| Crash during the replace | the old file or the complete new one (atomic rename); the backup holds the previous bytes | lost; unsaved copy if locked earlier | — |

### Vault identity

The fingerprint is a hash of the root-group UUID, which a save never changes. Face ID setups
(ADR 0012) and AutoFill record ids (ADR 0013) stay valid. After each save the AutoFill index is
rebuilt from summaries.

## Consequences

- Edits never lose data the app doesn't understand.
- Saves are fast and need no credentials. The transformed key stays in memory while the vault
  is open.
- A save copies the document once per edit, a cost proportional to vault size that is negligible
  for typical vaults.
- No merge in this ADR: a real conflict means a separate copy. [ADR 0023](0023-vault-merge.md) adds the merge to the
  core; Android offers it in the conflict dialog, the iPhone does not yet.

## Alternatives considered

- **Writing the read model**: lossy, rejected.
- **Re-deriving the key with a new KDF salt on every save**: needs the composite key in memory
  (stronger than the transformed key) and a KDF run per save. Rejected; a fresh master seed
  already makes every save's keys new.
- **A separate "Save vault" step with a dirty state**: easy to forget, and it collides with
  auto-lock. Rejected for iPhone.
- **Backups next to the vault**: clutters the user's folder and syncs to every device. Rejected
  in favour of an app-private copy.
- **Overwriting in place when an atomic rename isn't possible**: a crash would corrupt the vault.
  Refused instead.
