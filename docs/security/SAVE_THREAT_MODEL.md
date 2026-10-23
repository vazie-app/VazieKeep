# Save threat model

Scope: editing and writing a KDBX file on the iPhone ([ADR 0014](../adr/0014-kdbx-write-and-save.md)) and on Android ([ADR 0015](../adr/0015-android-safe-save.md); the Android section is at the end).
The two central questions: **can a save destroy the vault or someone else's version of it, and
can it leak what it writes?**

## Integrity threats

| Threat | Mitigation | Verified |
| --- | --- | --- |
| Crash or power loss during a write | Atomic replace only (`.atomic` write or `replaceItemAt`); never in place. A previous-version backup is kept first. | Unit tests with injected failures; on the device, temporary files leave nothing behind |
| Writer bug producing an unreadable or different file | Every save is read back by the reader with the transformed key and compared tree for tree and attachment for attachment before writing. The file is compared byte for byte after writing. | Rust round-trip tests on every format variant; fuzzing; KeePassXC and pykeepass interop |
| Silent data loss (fields the app doesn't model) | Lossless DOM: unknown elements, history, custom data, attachments and times are written back unchanged. | Internal tests with CustomData and attachments; KeePassXC export after a save |
| Another app or device saved in the meantime | SHA-256 of the file at unlock, compared inside the coordinated write. iCloud items must be the newest known version (`.current`), otherwise the save waits up to 15 s, then refuses. | On the iPhone: a local external change (QA hook) and a real iCloud race (Mac via KeePassXC) are both blocked, and the other version stays byte-identical |
| File moved or deleted while editing | The bookmark is resolved again; a missing file is `unavailable`. A moved file is followed by its bookmark, and its content is still checked. | iCloud move on the device: blocked with a clear message; unit tests |
| Offline iCloud file | It isn't `.current` once iCloud knows of a newer version, so `outOfDate`, nothing written. An offline device that knows of no change writes locally and iCloud resolves later (residual risk, below). | Not tested in airplane mode |
| Disk full, read-only provider | Typed failures; nothing written. | Unit tests |
| Swapped file (another vault at the same place) | The hash check catches any change. Biometric and AutoFill bindings still check the fingerprint. | — |

## Confidentiality threats

| Threat | Mitigation |
| --- | --- |
| Plaintext vault on disk | Never written. The core hands the platform only encrypted KDBX bytes; the staged file, backup and unsaved copy are complete encrypted vaults. |
| Backups leaking | App-private container, `completeFileProtection`, excluded from device backups, one per vault. |
| Plaintext XML in memory | Unavoidable during serialization. It lives in a `Zeroizing` buffer, is wiped after encryption, and gzip writes into a pre-sized zeroizing buffer. |
| Save errors and logs | Typed categories only; the debug log has timings and counts, never names, values or URLs. |
| "Save a Copy" | Hands the system exporter the encrypted bytes; the user picks where. |
| Keys kept for saving | The transformed key (opens only this file with this KDF salt), zeroized when the session closes. Never the password or the composite key. |

## Residual risks

- **iCloud freshness is only as good as what iCloud knows.** An offline device that hasn't heard
  of another device's save writes locally. iCloud then resolves the clash with its own conflict
  versions, and a later unlock sees whichever version won.
- The app-private backup can be older than the cloud's latest version.
- No merge on the iPhone yet: two real edits mean one of them becomes a separate copy.
- Protected values and the edited password exist as Swift `String`s while the editor is open.

## Android (M10)

Android cannot replace a document atomically with a single-document SAF grant, so the integrity
questions differ: the file is overwritten in place, and what is guaranteed is that **no
complete version is ever lost**, not that the file is never half-written.

| Threat | Mitigation | Verified |
| --- | --- | --- |
| Crash or power loss during the in-place write | The file is never truncated first and is rewritten from offset 0 in one pass. Before touching it, the previous bytes (backup) and the complete new bytes (journal) are in app-private storage, `fsync`ed. At the next unlock the journal says whether the write finished, never started, or was cut off; a damaged file offers "Restore the last save". | Fake-provider tests for every step; the restore path in a unit test. A real power cut was **not** tested. |
| Write fails midway (no space, permission, provider error, error on close) | The previous bytes are written back and read back; only if that fails too does `RestoreNeeded` keep the journal. | Failure injection: no space, no permission, close failure, unreadable file, silent corruption |
| Silent corruption by the provider | Read back through a fresh stream, byte for byte; on mismatch the old bytes are restored. | Failure injection |
| Writer bug producing a bad file | Same as the iPhone: the core reads its own output back before returning it. | Rust tests, interop script |
| Another app or device saved in the meantime | SHA-256 of the file at unlock compared with the current bytes before every save; no overwrite. | Device: an `adb`-written change blocked, file left byte-identical; instrumented compose test |
| Stale copy from a cloud provider | **No Android API exists to detect it.** The hash compares against whatever the provider returns now, so a stale copy that matches the baseline passes. | Not testable; Google Drive wasn't available on the device. Residual risk. |
| Provider with no seekable `rw` descriptor, or a virtual document | Detected before any side effect (`COLUMN_FLAGS`, `lseek`); the save is refused and "Save a copy" is offered. | Unit tests |
| Read-only grant from an earlier build | Editing is disabled with an "Allow editing" banner; nothing is attempted. | Unit test |
| Save running while the app locks or the screen closes | Edit and save run non-cancellable under one mutex; a lock during a save only skips marking the session clean. | Unit tests |
| Process killed after a failed save | The encrypted snapshot was written to private storage when the save failed; it is offered as a recovered copy at the next unlock. | Device: an instrumentation process teardown left a copy that showed up as "Unsaved changes recovered" |

### Confidentiality (Android)

- The backup, journal, unsaved and recovered copies are complete encrypted KDBX files in
  `noBackupFilesDir/Vault Copies`, named by a short hash, never in the user's folder, excluded
  from Auto Backup and device transfer.
- A damaged file is kept privately before it is overwritten by a restore.
- "Save a copy" gives the system document creator the encrypted bytes; the person picks where.
- Editor text lives in the screen model, not in saved instance state. Generated passwords are
  hidden by default and absent from the accessibility tree.

### Residual risks (Android)

- Not atomic: a cut during the write leaves a damaged file until the journal restores it.
- No freshness guarantee for cloud providers.
- The app-private backup can be older than the cloud's latest version and lives only on the device.
- Compose keeps edited values as `String`s while the editor is open.

### Merge (Android, ADR 0023)

- The file is read again with the same size limit as an unlock and is opened for the merge only: no session slot, wiped
  when the call returns. Its key is the session's own transformed key, or the typed master password, which the
  repository wipes after use; nothing is persisted.
- A key that does not fit changes nothing and asks for the password. A wrong password changes nothing.
- The merged state goes through the ordinary safe save (backup, journal, read-back), with the hash of the merged file as
  the baseline, so a file changed once more during the merge is refused and offered again.
- The merge report carries counts and the ids of conflict copies, never a value from the vault.
- Clocks decide which side is newer; the loser is kept as a copy and in history, so a wrong clock costs a duplicate,
  not a loss.
