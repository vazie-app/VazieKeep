# Platform integration

The core is pure. Everything that touches the operating system goes through a platform adapter
owned by the app. The adapters have the same names and semantics on every platform, so a feature
behaves the same everywhere.

| Adapter | Contract | Android | Apple | Desktop (JVM) | Extension |
| --- | --- | --- | --- | --- | --- |
| `PlatformInfo` | Name and OS version for diagnostics. **Exists today.** | `AndroidPlatformInfo` | `PlatformInfo.current` | `DesktopPlatform` | n/a |
| `FileStorage` (**Android M3: read** via `VaultFileAccess`, bounded to 256 MiB; **iOS M6: read**, **Android M10: write** via `VaultDocumentWriter` (SHA-256 version check, app-private backup and journal, in-place overwrite without truncating, read-back, restore on failure; no atomic replace exists for a single-document SAF grant, see ADR 0015), **iOS M9: write** via `SecurityScopedDocumentAccess.replace`: one coordinated `.forReplacing` write, SHA-256 version check, app-private backup, atomic replace, read-back, iCloud freshness check; see ADR 0014) | Read and write vault bytes. Writes are verify-then-replace: encode, decode back, then write. Atomic where the OS allows it. | SAF `ContentResolver`: `JournaledOverwriteWriter` (`"rw"`, never `"wt"`; ADR 0015) | `FileManager` + security-scoped bookmarks | `java.nio` atomic move | n/a (no vault file; see ADR 0003) |
| `FilePicker` (**Android M3**: `OpenDocument` + read persistable permission, header check before saving the reference; **iOS M6**: `fileImporter` + bookmark, header check, stale-bookmark refresh) | Let the user choose a vault or key file and keep persistent access | `ACTION_OPEN_DOCUMENT` + persistable permission (vaults ask for read and write, key files read) | `fileImporter` / `NSOpenPanel` | Compose/AWT file dialog | n/a |
| `SecureStorage` | Store **small** secrets only: wrapped quick-unlock keys and settings. Never the vault, never the master password. | Android Keystore (StrongBox where available) | Keychain (`ThisDeviceOnly`) | Windows DPAPI / Credential Manager; Linux Secret Service | `chrome.storage.session` (memory only) |
| `Clipboard` (**Android M3**: `AndroidSecretClipboard`, 30 s clear; **iOS M6**: `PasteboardClipboard`, `localOnly` + system `expirationDate`) | Copy a secret, mark it sensitive and **clear it automatically** after a timeout if unchanged | `ClipDescription.EXTRA_IS_SENSITIVE`, timed clear | `UIPasteboard` expiration / `NSPasteboard` timed clear | AWT clipboard with timed clear | `navigator.clipboard` + timed clear from the popup |
| `BiometricAuth` (**Android M5**: Keystore + `BiometricPrompt`; **iOS M7**: `LocalBiometricAuthenticator` + `KeychainCredentialStore`, `biometryCurrentSet`, see ADR 0012) | Gate the release of a quick-unlock key | `BiometricPrompt` + Keystore auth-bound key | LocalAuthentication + Keychain access control | Windows Hello (later), none on Linux | none |
| `AutoLock` (**Android M3**: `AutoLockController` on `ProcessLifecycleOwner`, 30 s; **iOS M6**: `VaultSessionController` on `scenePhase` + protected-data notification) | Lock on timeout, screen lock and backgrounding. Calls `KeepVaultSession.close()` for each open vault, or `close_all_vault_sessions()` / `closeAllVaults()`, and drops every summary and `SecretValue` it holds. | `ProcessLifecycleOwner`, screen-off receiver | scene phase, `NSWorkspace` notifications | window focus + idle timer | `chrome.idle`, `chrome.alarms` |

## Vault sessions on each platform

| | Open | Session type | Secrets | Threading |
| --- | --- | --- | --- | --- |
| Android / Desktop | `suspend KeepCore.openVault(bytes, password)` | `KeepVaultSession : Closeable` | `password(id)` / `reveal(id, field)` → `SecretValue` (`useChars {}`, `close()` wipes) | KDF runs on `Dispatchers.Default` inside the facade. Other calls are fast and thread-safe. |
| iOS / macOS | `try await RustKeepCore().openVault(data:credentials:)` (password and/or key file); on iOS only `VaultSessionController` calls it | `final class KeepVaultSession: Sendable` | `password(for:)` / `reveal(_:for:)` → `SecretValue` (`withString {}`, `wipe()`, wiped in `deinit`) | KDF in `Task.detached`. Methods use typed `throws(VaultSessionError)`. |
| Extension | `KeepVaultSession.open(wasm, bytes, password)` | `KeepVaultSession` (handle string hidden in a `#private` field) | `revealPassword(id)` → `SecretBytes` (`useText()`, `wipe()`) | synchronous, runs in the extension page that owns the session |

The UI keeps a session only while the vault is unlocked, never caches a revealed value, and
closes (wipes) every `SecretValue` as soon as the action that needed it is done.

## Rules

1. Adapters live in the app (`app/…/platform`, `KeepPlatform`, `DesktopPlatform`, `src/platform`)
   and are injected into state holders. The core never calls them.
2. Adapter interfaces are added **when the first feature needs them**, not as empty placeholders.
   This table is the contract they must follow.
3. Screens that can show secrets opt out of screenshots and recents thumbnails (`FLAG_SECURE`
   on Android, `.privacySensitive()` and a privacy overlay on Apple).
4. Nothing an adapter handles is logged.

## iPhone QA without the Files picker (debug builds)

Device UI tests and QA scripts copy synthetic vectors into the app's own container and point the
app at them with launch arguments. This exists only in Debug builds (ADR 0011).

```bash
xcrun devicectl device copy to --device <udid> --domain-type appDataContainer --domain-identifier app.vazie.keep --source <folder with vectors> --destination Documents/qa
```

Launch arguments: `-VazieQAReset YES`, `-VazieQAVault qa/<vault>.kdbx`, `-VazieQAKeyFile qa/<key file>`,
`-VazieQARemove qa/<file>` (deletes the file first, to simulate a vault that disappeared).
Timings (names and milliseconds only) are appended to `Library/Caches/perf.log`, along with
Face ID unlock states and invalidation reasons (never data).

### Face ID on the device

- Face ID can't be simulated on a physical iPhone. The manual UI tests
  (`testManualFaceID…`, run with `TEST_RUNNER_VAZIE_QA_MANUAL=1`) drive the app, and the tester
  looks at the phone, looks away or taps Cancel when asked.
- `KeychainCredentialStoreTests` run in the app-hosted bundle with the production access control.
  They cover existence, refusal without Face ID, class, synchronizability and the sweep.
- On iOS 27, any query on a `biometryCurrentSet` item made with a non-interactive context,
  attributes included, returns `errSecInteractionNotAllowed`. The store reads that as "the item
  exists", and it matches the installation id in the query (`kSecAttrGeneric`) instead of
  reading it.
- UI tests type Latin text. A secure field's keyboard keeps the last layout and has no globe
  key, so `latinKeyboard()` switches the layout through the visible-password field first.

## iPhone AutoFill (M8, ADR 0013)

- **Targets:** `VazieKeep-AutoFill` (`app.vazie.keep.autofill`), embedded in the app. It shares
  `KeepApp`/`KeepCore`, `Theme.swift`, `Messages.swift` and the string catalogs.
- **Production entitlements** (`iOS/VazieKeep.entitlements`, `AutoFill/VazieKeepAutoFill.entitlements`):
  - `com.apple.developer.authentication-services.autofill-credential-provider`;
  - App Group `group.app.vazie.keep`;
  - `keychain-access-groups`: `$(AppIdentifierPrefix)app.vazie.keep.shared` (plus the app's own
    group in the app).
- **Free Personal Team:** it can't sign AutoFill or App Groups. For device work, pass
  `CODE_SIGN_ENTITLEMENTS=iOS/VazieKeep-PersonalTeam.entitlements` (Keychain Sharing only) on
  the `xcodebuild` command line. The app falls back to its own container, and the extension is
  embedded but inactive.
- **Info.plist:** `VazieKeychainGroup` is set from `$(AppIdentifierPrefix)` and read by
  `KeychainCredentialStore`. The extension declares `ProvidesPasswords` and
  `ASCredentialProviderExtensionShowsConfigurationUI`.
- **QA:** Safari and native-app requests need a signed extension, which requires a paid Apple
  Developer Program team. The logic runs on the iPhone in `AutoFillFlowTests`,
  `AutoFillIntegrationTests` and `KeychainCredentialStoreTests`.

## iPhone editing and saving (M9, ADR 0014)

- **The core returns encrypted bytes** (`prepare_save`), and Swift writes them; the core never sees a
  path.
- **`VaultSessionController.edit { … }`** applies an edit and saves at once. Saves run one at a time
  and return a typed `SaveFailure`.
- **Device QA without the picker**: `-VazieQAReplaceAfterUnlock qa/<file>` (DEBUG only) replaces the
  vault file after the first unlock, to simulate another app saving it.
- **Interop**: run `tooling/test-vectors/interop.sh`. It needs pykeepass in a venv and/or
  `keepassxc-cli`.
- **Fixtures**: rich editable vaults in `shared/test-vectors/editable`, from
  `tooling/test-vectors/generate_editable.py`.

## Launcher icon (Android M10)

| Adapter | Contract | Android | iOS |
| --- | --- | --- | --- |
| `LauncherIcons` | Show exactly one app icon, chosen in Settings, from the shared icon set | `activity-alias` per icon, `setComponentEnabledSetting`, choice kept in DataStore | `setAlternateIconName` |

## Autofill (Android M11)

| Adapter | Contract | Android | iOS |
| --- | --- | --- | --- |
| `Autofill` | Offer matching entries on login forms; reveal one secret only after an explicit choice | `AutofillService` with authentication-needed datasets and an authentication activity; web hosts from pinned browsers only; native apps by package and certificate | Credential Provider extension (ADR 0013) |
| `AutofillSystem` | Report and request the provider setting | `AutofillManager.hasEnabledAutofillServices`, `ACTION_REQUEST_SET_AUTOFILL_SERVICE` | Settings |

## Account and Keep+ (Keep+ M1, ADR 0020)

Both apps: the account is optional and separate from vaults; nothing that opens, creates or saves a vault
awaits it. At launch the cached record is loaded and judged by the core with no network; a refresh runs in
the background at most once a day (sooner if the lease is missing, invalid or ends within 7 days).

- **Android.** `INTERNET` is declared. `HttpVazieAccountApi` talks to the account service (sign-in) and
  the Keep service (lease), whose addresses come from `local.properties` or CI secrets; debug builds use `adb reverse tcp:8080 tcp:8080` and a `localhost` /
  `127.0.0.1` pair, with cleartext allowed for those two hosts in the debug manifest only.
  `KeystoreAccountStore` keeps the session and lease in one AES-GCM record. Public keys come from
  `BuildConfig.KEEP_LEASE_KEYS`.
- **iOS.** `KeychainAccountStore` (generic password, `AfterFirstUnlockThisDeviceOnly`, installation-tagged).
  Origins and keys come from `Info.plist` (`VazieAccountOrigin`, `VazieKeepOrigin`, `VazieKeepLeaseKeys`);
  a post-build script (`scripts/apple-embed-lease-keys.sh`) writes the keys, and a debug build can
  override the origins with `-VazieAccountOrigin` / `-VazieKeepOrigin`. `NSAllowsLocalNetworking` lets a
  development backend on the local network use http; the internet still requires https.
- **Both.** Debug-only developer sign-in (`/api/v1/auth/dev` of a development backend) exists to test
  without an email; it is compiled out of release behaviour (`DEV_SIGN_IN` / `VazieDevSignIn` = false).

## Android Autofill save (M11.1)

`VazieAutofillService.onSaveRequest` → `SaveHandoff` → `AutofillSaveActivity` → `SaveExecutor` → the same `VaultDocumentWriter` as editing (`JournaledOverwriteWriter`). Package identity comes from `PackageIdentity` / `CallerVerifier`; pinned browsers need a `<queries>` entry. See [ADR 0017](../adr/0017-android-autofill-save.md).
