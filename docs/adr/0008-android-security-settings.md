# ADR 0008: Android security settings and lock policy

- Status: Accepted
- Date: 2026-10-01
- Extends [ADR 0007](0007-android-vault-session-lifecycle.md)

## Context

M3 hard-coded a 30 s background lock, a 30 s clipboard clear and an always-on `FLAG_SECURE`. M4
turns the Android client into something usable every day. People need to tune these, the
device's own lock must lock the vault, and on-device QA found accessibility gaps (below).

## Decision

### Settings store

- **DataStore Preferences** (`security_settings`), read through `SecuritySettingsRepository`.
  It exposes one `Flow<SecuritySettings>` plus per-field flows: `autoLockTimeout`,
  `lockOnDeviceLock`, `clipboardTimeout`, `screenshotsAllowed`, `themeMode`.
- Values are stored as enum names. An unknown or corrupt value falls back to the safe default,
  never to the most permissive option.
- `AppContainer` turns the flow into an application-scoped `StateFlow`, so lock decisions read the
  current policy synchronously.
- The vault reference stays in its own `SharedPreferences` file. It is a single record with
  no policy in it, and migrating it would buy nothing.
- The settings file holds no secrets: timeouts, booleans and a theme name only.

| Setting | Options | Default |
| --- | --- | --- |
| Auto-lock | Immediately · 15 s · 30 s · 1 min · 5 min · 15 min | **30 s** |
| Lock when the device locks | on / off | **on** |
| Clear clipboard after | Never · 15 s · 30 s · 60 s | **30 s** |
| Allow screenshots | on / off | **off** |
| Theme | System · Light · Dark | System |

### Auto-lock

`AutoLockController` (one instance, app-scoped) listens to `ProcessLifecycleOwner` and to the
settings flow:

- **Background:** hide secrets at once, record `elapsedRealtime`, schedule a lock for the
  *current* timeout. With *Immediately* it locks right away.
- **Settings change while in background:** the pending lock is rescheduled against the time
  already spent in the background. If the new timeout has already elapsed, it locks at once.
  The controller is never recreated.
- **Foreground:** cancel the pending lock. If the elapsed background time already exceeds the
  timeout (the process was frozen and the timer didn't fire), lock before any vault screen is
  shown.

### Screen off / device lock

A context-registered `BroadcastReceiver` for `ACTION_SCREEN_OFF` (registered in `Application`,
`RECEIVER_NOT_EXPORTED`; screen-off cannot be declared in the manifest). On screen off:

1. hide secrets;
2. lock immediately if *Lock when the device locks* is on, regardless of the auto-lock timeout.

The receiver lives as long as the process does, with no foreground service. If the process is
already dead, there is no session to lock. `ACTION_USER_PRESENT` is not needed: unlocking the
device must not unlock the vault.

Screen off is treated as "device locks". Keyguard lock without screen off is not a thing on
Android phones, and a smart-lock trusted state keeps the screen on anyway. That's why we don't
wait for the keyguard's own grace period.

### Clipboard

- A copy is marked sensitive (`android.content.extra.IS_SENSITIVE`) and labelled `Vazie Keep`.
- It is cleared after the chosen timeout, and on **every lock** (`LockCleanup` watches the
  repository state), but only while our label is still on the clipboard.
- *Never* shows a persistent warning in Settings and in the copy snackbar.
- Android 13+ shows its own copy confirmation, so the app shows a snackbar only below Android 13,
  or when the value will not be cleared.
- One `String` is created per copy because `ClipData` is parcelled as a `String` by the
  framework. We make no other copies.

### Screenshots

- `FLAG_SECURE` is set in `onCreate` **before** any content and removed only when the setting
  says so. A slow DataStore read therefore errs on the secure side.
- Turning screenshots on asks for confirmation. The warning mentions the recents preview and
  screen recordings, in user language rather than "FLAG_SECURE".

### Theme

The app uses its own Vazie palette in light and dark. Dynamic color (Material You) is not used,
because it would replace the lavender and plum identity. System bar icon colors follow the
in-app theme choice (`enableEdgeToEdge` with `SystemBarStyle.auto`).

### Accessibility trade-offs

| Finding (Galaxy A17, One UI 8.5) | Decision |
| --- | --- |
| **Compose `SecureTextField` publishes the master password as plain text** in the accessibility tree: `AccessibilityNodeInfo.text` held the real characters, while platform `EditText` exposes only dots. Any accessibility service could read it. | The field is wrapped in one semantics node: `password()`, a **masked** `editableText`, focus/click/`setText`/`insertTextAtCursor` actions, and the show/hide toggle as a custom action. Verified on device: the node now reports only `•` characters. |
| Revealed secrets could not be heard by TalkBack users (M3) | An explicit **Read aloud** custom accessibility action on each secret. The first use per screen shows a warning that the value goes to every enabled accessibility service and may be heard nearby. Only after confirmation is the value put into the node's description, with an assertive live region, until it auto-hides (15 s). Nothing is announced automatically. |
| Labels read twice ("Password" + "Password, hidden"); label and value split into separate stops | Each field is one node: plain fields merge label and value; secret fields expose "Password, hidden" / "…, shown on screen". |
| Non-interactive vault card was exposed as a disabled button | Plain container with merged text |
| Chooser errors were not announced | All error banners are polite live regions |

## Consequences

- The policy can be looser than the old fixed 30 s, but the defaults are unchanged and every
  unknown value falls back to them.
- *Immediately* also locks when the user leaves for another app for a moment, for example to
  look something up. That is the intended meaning.
- The screen-off receiver needs the process alive. After process death there is nothing to lock
  (ADR 0007).
- Samsung Keyboard's clipboard history may keep its own copy regardless of the sensitive flag.
  We clear only the system clipboard. This still needs a hands-on check on each OEM keyboard.
