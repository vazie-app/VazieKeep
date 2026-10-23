# Architecture

One rule shapes this repository: **there is exactly one implementation of vault logic.** KDBX
parsing, cryptography, the entry tree, search, password generation and merge will all live in
the Rust core. The platform apps are thin: they render UI and plug platform services into the
core.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                               keep-core (Rust)                           │
│  pure domain: formats, crypto, vault tree — no OS, no UI, no I/O policy  │
└───────────────┬───────────────────────────────────────┬──────────────────┘
                │                                       │
     keep-core-ffi (UniFFI DTOs)              keep-core-wasm (wasm-bindgen)
                │                                       │
     ┌──────────┴───────────┐                           │
 Kotlin bindings        Swift bindings                  │
 (generated, JNA)   (generated, XCFramework)            │
     │                      │                           │
 kotlin-core-bridge     KeepCore (facade)          src/core/vaultSession.ts
 (shared Kotlin facade)     │                           │
     │                      │                           │
 Android app │ Desktop app  iOS app │ macOS app      MV3 extension
     └── platform adapters: SecureStorage, Clipboard, FilePicker, Biometrics, AutoLock …
```

## Layers

| Layer | Owns | Must not |
| --- | --- | --- |
| `core/keep-core` | Domain types and algorithms. Takes bytes in and returns bytes out. | Depend on an OS API, UI toolkit, filesystem, browser or FFI crate |
| `core/keep-core-ffi` | The Kotlin/Swift contract: DTO records and enums, exported functions, error mapping | Contain logic beyond translation |
| `core/keep-core-wasm` | The JS contract for the browser extension | Expose raw secrets to JS longer than one call |
| Bridge / facade (`core-bridge`, `KeepCore`, `src/core`) | Maps generated DTOs to app-level types and loads the native library | Leak generated types into UI code |
| Platform adapters | Keychain/Keystore, clipboard, file pickers, biometrics, lifecycle | Parse or decrypt vault data |
| UI | Screens, state holders (ViewModel, `@Observable`, Preact state) | Hold crypto or format logic |

## Vault sessions (M2)

The decrypted vault never leaves Rust in bulk. `open_vault` builds a `VaultSession` inside the
core and returns an opaque handle. Platforms can then ask for non-secret views: info, groups,
entry summaries, entry details and search results. A secret comes out only through an explicit
`reveal(entry, field)`, one value at a time, as bytes that the platform wipes. See
[ADR 0006](docs/adr/0006-vault-session-handles.md) and
[SECRET_LIFECYCLE](docs/security/SECRET_LIFECYCLE.md).

```
KDBX bytes ─▶ Rust VaultSession ─▶ opaque handle ─▶ Kotlin / Swift / JS
                     ▲                                    │
                     └──── reveal(entry, field) ◀── UI explicitly asks
```

## iPhone client (M6)

```
ChooserView ─▶ UnlockView ─▶ BrowserView (groups, search) ─▶ EntryView        SettingsView
                  │               │                              │
             UnlockModel     BrowserModel                    EntryModel       (KeepApp, screen state)
                  └───────────────┴──────────────┬───────────────┘
                        VaultSessionController  (@MainActor, owns the one KeepVaultSession)
     SecurityScopedDocumentAccess (bookmarks) · FileVaultReferenceStore · SettingsStore · RustVaultOpener
             AppModel: scenePhase · protected-data notification · capture state · PasteboardClipboard
```

`KeepApp` is a platform-neutral library in `KeepAppleKit`; the SwiftUI views live in the iOS
target. The controller is the only owner of the Rust session. The app always starts locked, a
privacy overlay covers it whenever it isn't active, and auto-lock is decided from a monotonic
clock on return. See [ADR 0011](docs/adr/0011-ios-vault-session-lifecycle.md).

**Face ID unlock (M7).** `BiometricUnlock` (in `KeepApp`) coordinates four parts:
- `BiometricAuthenticator`, backed by `LAContext`;
- `BiometricCredentialStore`, a Keychain generic password with `biometryCurrentSet`;
- `BiometricSetupStore`, the setup record and the installation id in the sandbox;
- the controller's material unlock, which reads the files before the prompt and checks the
  fingerprint after opening.

Views call only `BiometricUnlock`. See [ADR 0012](docs/adr/0012-ios-biometric-unlock.md).

**AutoFill (M8).** The `VazieKeep-AutoFill` credential provider extension opens the vault
itself through the same core, using `AutoFillFlow` (in `KeepApp`) with its own
`VaultSessionController` and `BiometricUnlock`, one request at a time. It reveals one password
and closes.

The app keeps the iOS identity index current through `AutoFillIndexer`. Matching uses the
core's `origin::match_host` (Mozilla PSL). Bookmarks and the Face ID setup record live in the App
Group, and the Face ID item lives in the shared Keychain group. See
[ADR 0013](docs/adr/0013-ios-autofill-architecture.md) and
[the AutoFill threat model](docs/security/AUTOFILL_THREAT_MODEL.md).

**Editing and saving (M9).** The core keeps a lossless DOM of the payload plus the header and
attachments it was read from, applies edits as transactions, and serializes, encrypts and
verifies on `prepare_save`. Swift writes the bytes with one coordinated, version-checked, atomic
replace, keeping an app-private backup. Each edit saves at once. See
[ADR 0014](docs/adr/0014-kdbx-write-and-save.md).

**App icon.** The asset catalog has the primary icon (light, dark and tinted appearances) and
seven alternate colours, chosen in Settings via `setAlternateIconName`. The source artwork is
kept outside the repository.

## Android client (M3)

```
ChooserScreen ─▶ UnlockScreen ─▶ VaultScreen ─▶ EntryScreen
      │                │               │              │
 ChooserVM        UnlockVM        VaultVM        EntryVM       (screen state only)
      └──────────────┬─┴───────────────┴──────────────┘
              VaultRepository  (application-scoped, owns the one KeepVaultSession)
        VaultFileAccess (SAF) · VaultReferenceStore · KeepCore · SecretClipboard
              AutoLockController ◀── ProcessLifecycleOwner · ACTION_SCREEN_OFF · settings
              SecuritySettingsRepository (DataStore) · LockCleanup (clipboard on lock)
              BiometricUnlock ── BiometricAuthenticator (BiometricPrompt, strong only)
                              └─ CredentialProtector (Android Keystore AES-GCM) · BiometricSetupStore (no_backup)
```

The repository is the only owner of the Rust session. ViewModels get read models and short-lived
secrets from it. A lock, whether from the button or after 30 s in the background, closes the
session and resets navigation to Unlock. Screen off locks immediately, and the timeouts, clipboard,
screenshots and theme are user settings. See [ADR 0007](docs/adr/0007-android-vault-session-lifecycle.md)
and [ADR 0008](docs/adr/0008-android-security-settings.md).

Since M5 a vault can also need a KeePass key file, which is chosen via SAF, remembered by URI and
re-read on every unlock ([ADR 0009](docs/adr/0009-key-files.md)). Biometric unlock is optional:
after a manual unlock the KeePass password component is encrypted with a per-vault,
fingerprint-bound Keystore key, and a key file stays required on every unlock
([ADR 0010](docs/adr/0010-android-biometric-unlock.md)).

**Editing and saving (M10).** `VaultRepository.edit` applies an edit through the core session and
writes the file at once. `VaultDocumentWriter` (`JournaledOverwriteWriter` over a `DocumentPort`,
the only code that touches `ContentResolver`) checks the file's SHA-256 against the version opened,
keeps an app-private backup and a write-ahead copy, overwrites the document in place without
truncating, reads it back and puts the old bytes back on any failure. SAF offers no atomic replace
for a single-document grant, so this is crash-recoverable rather than atomic. Failures offer
Retry, Save a copy or Reload, and a file changed elsewhere also offers Merge ([ADR 0023](docs/adr/0023-vault-merge.md)). See [ADR 0015](docs/adr/0015-android-safe-save.md).

**Autofill (M11).** `VazieAutofillService` parses the screen (`FormParser`), decides who is asking
(`AutofillCoordinator`: a pinned browser's page host, or an app's package and signing certificate),
matches entries from a non-secret index with the shared core `match_host`, and answers with
authentication-needed datasets. A tap opens `AutofillAuthActivity`, which unlocks (fingerprint, then
password; key file read again), reveals the one chosen password from a short-lived session and returns
it as a Dataset. No vault or secret lives in the service. See [ADR 0016](docs/adr/0016-android-autofill.md)
and [the threat model](docs/security/ANDROID_AUTOFILL_THREAT_MODEL.md).

**Launcher icon.** Eight `activity-alias` entries share `MainActivity`; Settings enables one and
disables the rest.

Dependencies point downward only. The core never knows which apps exist. Apps depend on the core
through the build (Gradle tasks, `scripts/build-core.sh`), never through checked-in binaries.

## Keep+ (account and entitlement)

Vaults never depend on it. The **Vazie Backend** (a separate repository) holds accounts and entitlements
and signs a short **lease**; the apps verify it offline with the Rust core.

```
Vazie Account -> backend entitlement (KEEP_PLUS) -> signed lease (JWS, Ed25519)
   -> app stores it -> core verifies + evaluates -> FeatureAccess -> Keep+ features
```

- `core/keep-core/src/entitlement.rs`: `verify_lease`, `evaluate` (states and the clock rules), `can_use`.
  Exposed through UniFFI as `verify_keep_lease`, `evaluate_keep_access`, `can_use_plus_feature`.
- Android `app.vazie.keep.account`: `HttpVazieAccountApi`, `KeystoreAccountStore`,
  `KeepEntitlementService`, `FeatureAccess`, and the account screen. iOS: `KeepApp` `HTTPVazieAccountApi`,
  `KeychainAccountStore`, `KeepEntitlementService`, and `AccountView`.
- Public keys: `shared/keep-lease/`. Shared vectors: `shared/test-vectors/keep-lease/`.

Rules and product scope: [docs/product/KEEP_PLUS.md](docs/product/KEEP_PLUS.md).

## Where things live

- **Generated bindings are never committed.** Gradle writes Kotlin bindings to `build/rust/…`.
  `scripts/build-core.sh apple` writes the XCFramework and Swift file into
  `apps/apple/KeepAppleKit` (git-ignored). The WASM package goes to `core/keep-core-wasm/pkg`.
- **Version alignment.** The core version comes from `core/Cargo.toml` and is reported at runtime
  by `core_info()`. Every client shows it on the status screen.
- **Shared tests.** `shared/test-vectors` is the source of truth for format behaviour. The core
  consumes it today, and bindings-level suites will consume the same files.

## Further reading

- [docs/architecture/MONOREPO.md](docs/architecture/MONOREPO.md): directory-by-directory rationale
- [docs/architecture/CORE_BOUNDARIES.md](docs/architecture/CORE_BOUNDARIES.md): what may cross the FFI and WASM boundaries
- [docs/architecture/PLATFORM_INTEGRATION.md](docs/architecture/PLATFORM_INTEGRATION.md): platform adapter contracts
- [docs/adr](docs/adr): decisions and their trade-offs
- [docs/product/KEEP_PLUS.md](docs/product/KEEP_PLUS.md): what is free and what is Keep+
- [docs/security/SECURITY_MODEL.md](docs/security/SECURITY_MODEL.md)
