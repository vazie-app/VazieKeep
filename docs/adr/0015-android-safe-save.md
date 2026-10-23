# ADR 0015: Android editing, safe save through SAF, and alternate launcher icons

- Status: Accepted
- Date: 2026-10-04
- Extends [ADR 0007](0007-android-vault-session-lifecycle.md), [ADR 0010](0010-android-biometric-unlock.md)
  and [ADR 0014](0014-kdbx-write-and-save.md) (the shared writer)
- Threat model: [SAVE_THREAT_MODEL](../security/SAVE_THREAT_MODEL.md)

## Context

M10 gives Android the editing that M9 gave the iPhone: entries and groups, the password
generator, and a save that can never lose the vault. The cryptography is already done and shared:
`VaultSession → mutate → prepare_save → encrypted KDBX bytes`. Android's job is to get those
bytes into the file the person picked through the Storage Access Framework (SAF).

The core knows nothing about `ContentResolver`, `Uri` or SAF, and still doesn't.

## Decision

### Architecture

```
EntryEditorVM · VaultVM · EntryVM          (screen state, SaveFlow for failures)
        └─────── VaultRepository.edit { session -> … }   (mutate, then save at once)
                       │  prepareSave / markSaved (Rust)
                       ▼
                VaultDocumentWriter  ◀── fake in unit tests
          JournaledOverwriteWriter ── VaultCopyStore (backup, unsaved, journal)
                       ▼
                 DocumentPort  ◀── fake in unit tests
        ContentResolverDocumentPort (the only code that touches ContentResolver)
```

- The Kotlin bridge exposes the existing FFI at operation level (`createEntry`, `updateEntry`,
  `deleteEntry`, `moveEntry`, `createGroup`, `renameGroup`, `deleteGroup`, `prepareSave`,
  `markSaved`, `generatePassword`). The vault is never copied across.
- **Edit = save**, as on iPhone. `VaultRepository.edit` applies the edit, then writes the file,
  under one mutex, in a non-cancellable context so leaving the screen can't cut a save in half.
- Dirty state is the core's generation counter. After a failed save the session stays dirty.

### What SAF can and cannot do (researched)

The picker (`ACTION_OPEN_DOCUMENT`) returns a grant for **one document**. That grant:

- allows `openFileDescriptor(uri, "r" | "w" | "rw" | "rwt")` and `openInputStream`;
- does **not** reach the parent folder, so a sibling temp document cannot be created
  (`DocumentsContract.createDocument` needs the parent), and
- has no replace primitive: `renameDocument` does not overwrite an existing name, and
  `moveDocument` needs the parent as its source.

A folder grant (`ACTION_OPEN_DOCUMENT_TREE`) would allow stage → verify → swap by rename, but:
Android 11+ refuses to grant the Downloads root (the most common place for a vault), it adds a
second permission prompt for every vault, and a swap changes document IDs on ID-based providers
(Google Drive), which would invalidate the saved reference and the biometric binding. It also
isn't atomic: it is two renames. **Rejected for M10.**

**Consequence: with a single-document grant there is no atomic replace on Android.** The decision
below is the safest thing that exists, and ADR readers should treat the limitation as real.

### Save strategy: `JournaledOverwrite`

`VaultDocumentWriter.replace(uri, expected, bytes, backup)`:

1. **Capabilities.** Query `COLUMN_FLAGS`. Refuse (`ProviderUnsupported`) if the document is not
   writable or is a virtual document. Rename, delete and move flags are recorded for diagnostics
   only (the debug dump), since this strategy doesn't use them.
2. **Read current bytes, compare SHA-256 with the baseline** taken at unlock. A difference stops
   the save (`ChangedExternally`). There is no "overwrite anyway".
3. **Open `rw` and check it is seekable** (`lseek`). Opening writes nothing and does not truncate.
   A provider that only offers a pipe or no `rw` mode is refused (`ProviderUnsupported`) before
   any side effect.
4. **Backup** the bytes about to be replaced to app-private storage. If that fails, nothing is
   written (`BackupFailed`).
5. **Journal**: the complete new encrypted file and the baseline hash go to app-private storage,
   each written to a temporary file, `fsync`ed and renamed into place.
6. **Overwrite from offset 0 without truncating first**, then truncate to the new length, `fsync`,
   close (a proxy descriptor reports upload errors at close, so close is inside the guarded
   region). The file is never empty.
7. **Read back through a fresh stream** and compare byte for byte.
8. Any failure in 6–7 **puts the previous bytes back** (itself verified). Only if that also fails
   is the result `RestoreNeeded`, and the journal stays.
9. Success: the journal is deleted; the new hash becomes the next save's baseline.

This is **not atomic**. A crash or power loss between steps 6 and 7 can leave the file holding a
mix of new and old bytes. What it guarantees instead is that **no data is lost**: the backup holds
the old complete version and the journal holds the new complete version, both encrypted, both in
app-private storage that survives process death.

At the next unlock, the journal is compared with the file:

| File holds | Meaning | Action |
| --- | --- | --- |
| the journaled new bytes | the write finished | journal deleted |
| the baseline bytes | the write never started | journal deleted |
| neither, and the vault opens | someone else changed it | journal deleted |
| neither, and it is damaged | the write was cut off | unlock offers **Restore the last save**, which writes the journaled bytes back (the damaged file is kept privately first) |

If the person does not want any non-atomic write, the alternative is "Save a copy" only.
This ADR chooses to allow the journaled overwrite because refusing it would make editing
impossible on every provider, including local storage, and because every failure path is
recoverable.

### Provider capability detection

`ProviderCapabilities` (from `COLUMN_FLAGS`): write, delete, rename, move, virtual.
`chooseStrategy` is a pure function: writable and not virtual → `JournaledOverwrite`, otherwise
`Unsupported`. A URI with no flags (a `file://` URI, a plain content URI) is attempted, and the
seek check and the read-back decide.

Observed on the Galaxy A17 (Android 16, One UI 8.5):

| Provider | Flags | Result |
| --- | --- | --- |
| `com.android.providers.downloads.documents` (Downloads) | write, delete, rename, move | save, conflict block and restore all verified |
| `com.android.externalstorage.documents` (Documents, other folders) | same family | not separately exercised |
| Samsung My Files (`com.sec.android.app.myfiles`) | installed | **not tested** |
| Google Drive (`com.google.android.apps.docs`) | installed, no signed-in account on the test device | **not tested** |

### Fallback: Save a copy

Any failure offers **Save a copy** (`ACTION_CREATE_DOCUMENT`): the encrypted session bytes are
written to a new document and verified by reading them back. For an unsupported provider the
message is: "This storage provider can't safely replace the vault. Save a copy instead."

### Cloud providers and freshness

Android has no equivalent of iCloud's `ubiquitousItemDownloadingStatus`. A provider may serve a
cached copy, and `COLUMN_LAST_MODIFIED` / `COLUMN_SIZE` come from the same provider and say
nothing about whether the content is current. **There is no API to guarantee freshness.** The
only guard is the SHA-256 comparison against what the provider hands out now, so a provider that
serves a stale copy can still let a save overwrite a newer cloud version. This is a documented
limitation, not something the app can fix. Cloud-synced files are safest when the sync client
keeps a local copy and the app is opened after sync.

### External-change detection and conflicts

At unlock the SHA-256 of the encrypted file is recorded. Before a save the current bytes are read
and hashed. On a difference: **Vault changed elsewhere** with **Merge**, **Reload** (discard and lock),
**Save a copy** and **Cancel**. Cancel keeps the session dirty and the banner on the vault screen,
with Try again and Save a copy.

**Merge** ([ADR 0023](0023-vault-merge.md)) reads the file as it is now, combines it with the open session
and saves the result with that file's hash as the next baseline. The session's own transformed key opens
the other file, so a version that another device running Keep saved needs no password; only a key that
does not fit (another program rewrote the file with new key derivation parameters, or the password
changed) asks for the master password. A file that changes once more during the merge shows this choice
again. `VaultRepository.mergeChangedFile`, `SaveFlow.merge`.

### Backup and recovery

- **Location:** `noBackupFilesDir/Vault Copies`, so it is excluded from Auto Backup and device
  transfer whatever the extraction rules say (the app also sets `allowBackup=false`). Nothing is
  written into the user's folder. Names are a short hash of the vault fingerprint or URI.
- **Backup:** one previous version per vault, replaced at every successful save.
- **Unsaved changes:** whenever a save fails after `prepare_save`, the encrypted snapshot is
  kept at once, not only at lock, so a process kill cannot lose it. Auto-lock with a dirty
  session keeps one too. A successful save deletes it. At the next unlock it becomes a
  **recovered copy** (so a new session's own copy can't overwrite it), shown as "Unsaved changes
  recovered" with **Save a copy** and **Delete**.
- **Manual lock with a dirty session:** Save / Discard / Cancel.
- All of these are complete KDBX files, as encrypted as the vault. No plaintext recovery exists.

### Write permission

The picker is now asked for a persistable read-write grant. A vault chosen by an earlier build has
a read-only grant: the vault opens, editing is off, and a banner offers **Allow editing**, which
asks for the same file again and upgrades the grant without locking.

### Editing UX

- Entry editor (new and edit): title, username, password, URL, notes, tags, group, custom fields
  with a Protected switch. Entry history is written by the core; there is no history UI.
- The editor's text lives in the screen model, never in `rememberSaveable` or `SavedStateHandle`,
  so no password reaches an instance-state bundle. Password and protected values use the same
  masked-semantics field as the unlock screen.
- Generator: the core generator (length 4–128, lowercase, uppercase, digits, symbols, avoid
  look-alikes). The result is hidden, exposed to accessibility only as "hidden/shown", revealed on
  demand, hidden again on background, and dropped when the sheet closes.
- Delete: with a recycle bin group present, "Move to Recycle Bin" or "Delete permanently";
  otherwise only permanent delete, each explained. A non-empty group states how many entries and
  subgroups go with it. The root can't be deleted. Move to group is a native dialog.
- A saving indicator appears only if a save takes longer than 500 ms, and is indeterminate.

### Alternate launcher icons

- Eight `activity-alias` entries (`.launcher.KeepClassic`, `KeepBlue`, `KeepGray`, `KeepGreen`,
  `KeepPink`, `KeepRed`, `KeepViolet`, `KeepYellow`), all targeting `MainActivity`, label
  "Vazie Keep", each with a launcher intent filter. Only `KeepClassic` is enabled in the manifest.
- `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)` enables the chosen alias, then
  disables the others, so exactly one launcher entry exists afterwards (verified with
  `queryIntentActivities`).
- The artwork is the iPhone icon set (`keep_icons/`). Each icon is one transparent 432 px WebP
  foreground (artwork inside the 66 dp safe zone) over one shared cream background colour
  resource; a single monochrome layer serves every icon for themed icons. Generated by
  `tooling/android/make_launcher_icons.py`. The Settings picker draws the same foreground
  drawables, so no assets are duplicated.
- The choice is stored in DataStore and reconciled at startup. After an update the component
  state persists; after a reinstall both are back to Classic.
- **Device behaviour (Galaxy A17, One UI 8.5):** switching ends the app's task and returns to the
  home screen; the process survives, and the vault session is governed by the normal auto-lock.
  Some launchers refresh the icon late. Neither is treated as a failure.

## Consequences

- Editing works on any provider that hands out a seekable read-write descriptor, with every
  failure leaving the original untouched or recoverable. Providers that don't are refused with a
  clear route (Save a copy).
- The save is not atomic, and the limits above (no atomic replace, no cloud freshness) are
  stated rather than hidden.
- Biometric setup and the vault fingerprint are unaffected by a save: the fingerprint is
  root-UUID based and the URI doesn't change. A key file is re-read at every unlock as before.
- Release APK (unsigned, R8): 6.32 MB before, 6.77 MB after (+0.45 MB, +7%): about 0.22 MB of that is the nine icon layers, the rest editing code and the Material components the new screens use.

## Alternatives considered

- **`openOutputStream(uri, "wt")`:** truncates first; an interrupted write leaves an empty file.
  Rejected.
- **Folder grant and sibling swap:** see above. Revisit if a provider-specific need appears.
- **Refusing every non-atomic write:** correct on paper, but no provider could be edited.
- **A copy in `cacheDir` or the user's folder:** the first can be evicted, the second clutters
  and syncs. Rejected for private storage.
- **`COLUMN_LAST_MODIFIED` as a freshness check:** from the same provider as the bytes, so it
  proves nothing about staleness and false-alarms on touched files.
- **Dynamic shortcuts or an in-app icon only:** the launcher icon is what the person sees;
  `activity-alias` is the supported mechanism.
