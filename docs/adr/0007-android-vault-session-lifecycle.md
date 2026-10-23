# ADR 0007: Android vault session lifecycle

- Status: Accepted
- Date: 2026-09-30

## Context

M3 is the first user-facing flow: choose a `.kdbx` file, enter the master password, browse,
reveal, lock. Android kills processes, recreates activities on rotation, restores navigation and
`rememberSaveable` state from a `Bundle`, and freezes cached apps. Each of these could keep a
decrypted vault or a password around longer than intended, or bring one back after a lock.

## Decision

### Owner

**`VaultRepository`, created once in `AppContainer` (owned by `KeepApplication`)**, owns the only
`KeepVaultSession`. It is application-scoped because the session must outlive screens and
rotations but not the process. The repository is the only class that:

- reads the vault file (through `VaultFileAccess`, the `ContentResolver` adapter);
- calls `KeepCore.openVault`;
- holds, replaces and closes the session;
- turns session calls into read models for ViewModels.

Composables and ViewModels never see the session, the raw handle, a `ContentResolver` or a
`Uri`. Rejected owners:

- a composable (dies on recomposition and rotation);
- a `ViewModel` (tied to one screen, and we need one session for three screens);
- a static singleton (no lifecycle, and hard to replace in tests);
- `SavedStateHandle` (the session is not parcelable and must never be persisted).

### Process death

The Rust session lives in process memory only. After process death the repository starts
`Locked`. Navigation may restore a back stack that ends in `vault` or `entry/{id}`, so
`KeepNavHost` watches `(repository.state, currentBackStackEntry)`. Whenever the vault is locked
and the destination requires a session, it resets the graph to `unlock`. Nothing decrypted is
ever written to saved state, so there is nothing to restore.

### What is persisted

`SharedPreferences("vault_reference")` holds the vault reference only: document URI, display
name and last-used time. The app takes a **read** persistable URI permission (write comes with
KDBX saving). The previous vault's permission is released when another file is chosen. The file
is read through SAF on every unlock and never copied into app storage. App data is already
excluded from cloud backup and device transfer.

### Unlock

- The master password lives in a `TextFieldState` **held by `UnlockViewModel`**. It survives
  rotation and dies with the ViewModel. `rememberTextFieldState` / `rememberSaveable` are not
  used because they would put the password into the saved-state `Bundle`.
- On submit, the text is encoded to a UTF-8 `ByteArray`, the field is cleared at once, and the
  bytes go to the repository.
- The file is read on `Dispatchers.IO`. The KDF runs on `Dispatchers.Default` inside the bridge.
  The facade and the repository zero-fill the password array.
- Double submits are blocked twice over: `UnlockViewModel` ignores `unlock()` while
  `Unlocking`, and the repository's `Mutex.tryLock` returns `AlreadyRunning` and wipes the second
  password.
- Errors map to `UnlockError` (wrong password, damaged, unsupported, not a vault, file
  unavailable, too large, unknown). UI state never carries an exception or a Rust message.

### Background lock

`AutoLockController` observes `ProcessLifecycleOwner`:

| Event | Action |
| --- | --- |
| `onStop` (app in background) | emit *hide secrets* at once; record `elapsedRealtime`; start a coroutine that locks after `AUTO_LOCK_TIMEOUT` (**30 s**) |
| `onStart` (foreground) | cancel the pending lock; if the recorded background time is already ≥ timeout (the process was frozen and the timer didn't fire), lock now |
| Lock button | `VaultRepository.lock()` |

`lock()`:

1. emits *hide secrets*;
2. closes the session;
3. calls `closeAllSessions()` as a safety net;
4. publishes `Locked`.

Navigation then clears the back stack to `unlock`, so Back cannot return to the vault. Lock on
screen-off, a configurable timeout and "lock immediately" are follow-ups; the controller already
takes the timeout as a parameter.

### UI secret lifetime

| Where | Lifetime |
| --- | --- |
| Password on the entry screen | hidden (`••••••••`) until the user taps *Show*. `reveal` → `SecretValue` → `useChars` → one `String` in `EntryUiState.Loaded.revealed`. The `SecretValue` is closed (zero-filled) immediately. |
| Auto-hide | `REVEAL_DURATION` = **15 s**. Also hidden at once on: the Hide tap, leaving the screen (`DisposableEffect` dispose, which includes rotation), app background, lock, and `ViewModel.onCleared`. |
| Other entries | one revealed value at a time. Revealing another field replaces it. |
| Copy | `SecretClipboard`: the clip is marked sensitive (`android.content.extra.IS_SENSITIVE`, honoured from Android 13). It is cleared after `CLIPBOARD_CLEAR_AFTER` (**30 s**) if the clipboard still holds our label. A snackbar shows only below Android 13, where the system shows no confirmation of its own. |

`RevealedSecret`, `SecretKey.Custom` and every read model redact their `toString()`.

### Screens

`FLAG_SECURE` is set on the single activity for every screen. Even the chooser shows vault file
names, and recents thumbnails must never show an entry. It does not affect accessibility
services.

### Accessibility and the revealed value

A revealed secret is drawn but **kept out of the semantics tree**: `clearAndSetSemantics`
exposes "Password, shown on screen" instead of the characters. Compose would otherwise publish
the `Text` to every enabled `AccessibilityService`. Many third-party services read that tree,
and some log it. The master password field is Material 3 `SecureTextField`, which marks the node
as a password and exposes no text.

The trade-off: TalkBack users hear that a password is shown but not its characters, and they
can copy instead. An explicit, on-demand "read aloud" action is a follow-up. The reveal toggle
has a state description ("Shown" / "Hidden") and a label naming the field. All touch targets
are at least 48 dp.

## Consequences

- Rotation keeps the session (application-scoped) and the unlock job (in `viewModelScope`), and
  never re-runs the KDF. Rotation **hides** a revealed secret, because the composition is
  disposed.
- A frozen process can stay unlocked in memory past 30 s until the app returns to the
  foreground or is killed. An `AlarmManager`-based lock is a follow-up if that proves to matter.
- The `String` produced for display (and for the clipboard) is outside our control once
  created. See SECRET_LIFECYCLE.
