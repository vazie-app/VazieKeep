# ADR 0011: iOS vault session lifecycle

- Status: Accepted
- Date: 2026-10-01
- Builds on [ADR 0006](0006-vault-session-handles.md), [ADR 0009](0009-key-files.md); mirrors the
  policies of [ADR 0007](0007-android-vault-session-lifecycle.md) and
  [ADR 0008](0008-android-security-settings.md) with iOS mechanisms

## Context

M6 is the first real iPhone client: choose a `.kdbx`, optionally a key file, unlock, browse,
reveal, copy, lock. It uses the same Rust core through UniFFI and the existing Swift facade
(`KeepCore`). iOS differs from Android where it matters for a password manager:

- files arrive as **security-scoped URLs** from the Files picker;
- apps are **suspended** in the background and may be killed silently;
- the system takes an **app-switcher snapshot**;
- there is **no API to block screenshots** and no "device locked" event as such.

## Decision

### Layers

```
Rust keep-core ─ UniFFI ─ KeepCore (facade) ─ KeepApp (application layer) ─ SwiftUI (iOS target)
```

`KeepApp` is a new library in `KeepAppleKit`. It uses only Foundation and Observation, so its
tests run on the Mac and on the iPhone against the real XCFramework.

- `VaultSessionController` (`@MainActor @Observable`, one per process, owned by `AppModel`):
  - the **only** owner of the Rust session;
  - opens and locks it, runs the auto-lock;
  - holds the vault reference.
- Screen models: `UnlockModel`, `BrowserModel`, `EntryModel`. They hold screen state and ask the
  controller for data; none of them keeps the session.
- The session never lives in a `View`, `@State`, `NavigationPath`, `UserDefaults`, `@SceneStorage`
  or `@AppStorage`.
- Navigation holds only group and entry ids, is reset on every lock, and is never restored.
- After process termination the app starts locked: nothing decrypted is ever persisted.

### File access

- **Picker:** SwiftUI `fileImporter` (`UIDocumentPickerViewController` in open mode, not import).
  It shows On My iPhone, iCloud Drive and any Files provider. Types: `.item`, because there is no
  system UTType for `.kdbx`.
- **Reference:** a bookmark (`URL.bookmarkData()`; on iOS the security scope is implicit) plus
  the display name. Both are stored in `Application Support/vault-reference.json`:
  - with `completeUntilFirstUserAuthentication` file protection;
  - excluded from backup, since a bookmark restored on another device can't resolve.

  The key file is stored the same way. The vault and key-file **content is never copied** into
  the app container.
- **Access window:** every read resolves the bookmark, calls `startAccessingSecurityScopedResource()`,
  does one `NSFileCoordinator` read (which also downloads iCloud items), then calls
  `stopAccessingSecurityScopedResource()`. Access is never held between reads.
- **Size limits:** checked from file metadata before reading, then enforced again while reading:
  vault 256 MiB, key file 64 MiB.
- **Stale bookmarks:** when resolution reports `isStale`, a fresh bookmark is created during that
  same access and saved.
- **Unavailable files:** if resolution or the read fails, the user sees "can't be read … choose it
  again", with the picker one tap away. There are no crashes and no raw Cocoa errors.
- **At selection:** the header is checked. A non-KDBX file is refused as a vault, and a KDBX file
  is refused as a key file.

### Unlock

- **Password:** the `SecureField` text lives in `UnlockModel` (an `@Observable` object, not
  `@State`, not saved).
  - On submit it becomes UTF-8 `Data`, and the field is emptied at once.
  - The KDF runs in the facade's `Task.detached`, off the main actor.
- **Key files:** password only, key file only, or both. With an empty field and a key file, the
  key file is tried alone first, then with an empty password (as on Android).
- **Wrong credentials (the B7 rule):**
  - the field is cleared;
  - an error banner appears;
  - focus returns to the field, and the keyboard never goes down, because the field is not
    disabled during unlock.

  Return submits.
- **Races:**
  - a second unlock while one runs is rejected;
  - a lock during the KDF wins: the session that arrives afterwards is closed at once
    (generation counter).

### Lifecycle and auto-lock

- **Settings:** `UserDefaults`, non-secret only.
  - Auto-lock: Immediately · 15 s · **30 s** · 1 min · 5 min · 15 min.
  - Clipboard: Never · 15 s · **30 s** · 60 s.
  - Lock when the iPhone locks: **on**.
  - Theme: **System** · Light · Dark.

  Unknown values fall back to the defaults.
- **`scenePhase == .background`:**
  - revealed secrets are hidden;
  - *Immediately* locks at once;
  - otherwise the clock time is recorded and a lock task scheduled, with a background task asked
    for so that short timeouts can fire before suspension.
- **Return to `.active`:** the pending lock is cancelled and the elapsed `ContinuousClock` time
  decides. A suspended app's timer may never fire, but a vault that sat in the background too
  long is locked before any content is shown.
- **Device lock:** iOS has no public "screen locked" event.
  - `protectedDataWillBecomeUnavailableNotification` is the closest signal. It arrives only with
    a passcode set, about 10 s after locking, and only if the app is still running to receive it.
  - When it arrives the vault locks. When it doesn't, auto-lock still covers the gap.
  - Private APIs (SpringBoard lock state, Darwin notifications) are not used.

### App switcher, screenshots and capture

- **Privacy overlay:** whenever `scenePhase != .active`, a full-screen brand view covers the UI,
  so the app-switcher snapshot shows "Vazie Keep" and a lock, not content.
- **Screenshots:** there is **no public API to block them**. Settings says so honestly. The known
  trick of a secure text field layer relies on undocumented behaviour and is not used.
- **Capture:** recording, mirroring and AirPlay are detected via `UITraitCollection.sceneCaptureState`
  (iOS 17+; `UIScreen.isCaptured` is deprecated). While captured, the privacy overlay covers the
  app and revealed secrets are hidden.

### Secrets on screen

- A secret is fetched from Rust only on Show and becomes one short-lived Swift `String` in
  `EntryModel.revealed`. The `SecretValue` is wiped right away.
- It is hidden after **15 s**, on leaving the entry, on background, on capture and on lock.
- It never goes into `@SceneStorage`, `@AppStorage`, navigation state or logs.
- Swift `String` can't be zeroed. That limit is documented in SECRET_LIFECYCLE.

### Clipboard

- `UIPasteboard.general.setItems(_:options:)` with:
  - **`.expirationDate`** (the system removes the item after the chosen timeout, even if the app
    is suspended or killed);
  - **`.localOnly`** (no Universal Clipboard to the user's other devices).
- iOS has **no "sensitive" flag** like Android 13; nothing is invented.
- On lock, the item is removed if it is still ours (checked by `changeCount`).
- *Never* keeps the item until it is replaced and shows a warning.

### Accessibility

- VoiceOver never reads a revealed secret automatically. The value element is
  `accessibilityElement(children: .ignore)`, with label = field name and value = "Hidden" or
  "Shown on screen".
- An explicit **Read aloud** accessibility action (with a one-time confirmation per entry)
  reveals the value and posts it as an announcement.
- `SecureField` exposes only bullets. The Show toggle switches to a plain `TextField` because the
  user asked for it.
- UI tests on the iPhone search the whole accessibility tree for the master password and the
  revealed secret, and find neither.

### Debug tooling

Debug builds only, compiled out of Release:

- launch arguments `-VazieQAVault`, `-VazieQAKeyFile` and `-VazieQAReset` point the app at
  synthetic vectors copied into its own `Documents/qa`, so device UI tests don't need the system
  picker; `-VazieQARemove` deletes one of them first to simulate a vault that disappeared;
- `DebugTiming` writes operation names and milliseconds to `Library/Caches/perf.log` and to
  `Logger` with public numbers only.

### Measured on a physical iPhone 16 (iOS 27, Debug build)

| Operation | Time |
|---|---|
| Unlock, small synthetic vault (AES-256, Argon2d, low cost) | 9–16 ms in total |
| Unlock, Argon2d 64 MiB / 10 iterations | about 250 ms in total (about 242 ms in the core) |
| Reading the vault from iCloud Drive (coordinated) | 25–45 ms |
| Search | under 1 ms |
| Cold launch (`XCTApplicationLaunchMetric`) | 0.44–0.46 s |

The vault reference survived a process kill and a full reboot of the iPhone. After the first
unlock with the passcode, the app started locked and the bookmark resolved.

## Consequences

- iPhone and Android share every format, key-file and session rule through one core, with
  native UI and platform mechanisms.
- The app-switcher snapshot and recordings are covered. Plain screenshots of a revealed secret
  remain possible, as on any iOS app.
- "Lock when the iPhone locks" depends on a notification iOS doesn't promise to deliver while
  suspended. Auto-lock remains the guarantee.
- Face ID unlock is a separate milestone: it needs its own threat model, Keychain access control
  and invalidation design (the iOS counterpart of ADR 0010).

## Alternatives considered

- **Copying the vault into the sandbox:** simpler reads, but it would create a second, stale
  copy of the vault that the user can't see. Rejected.
- **Holding security-scoped access for the whole session:** unnecessary, because the file is read
  once per unlock. Rejected.
- **Private lock-state APIs:** not allowed for App Store apps and fragile. Rejected.
- **Clearing the clipboard from an app timer only:** a suspended app can't run timers. The
  system expiration is reliable, so it's the primary mechanism.
