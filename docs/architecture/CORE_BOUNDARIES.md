# Core boundaries

## What `keep-core` may depend on

Allowed: `core`/`alloc`/`std` (without OS-specific modules), pure-Rust crates for parsing,
compression and cryptography, and `zeroize`-style memory hygiene.

Forbidden: anything that talks to an OS service (keychain, clipboard, UI, notifications), a
specific filesystem layout, networking, logging to a sink it chooses itself, the browser, JNI
or UniFFI types.

I/O is inverted: the core takes `&[u8]` and returns `Vec<u8>`. Opening, atomically replacing and
watching files is platform work. On Android, a SAF document cannot be renamed. On iOS the file may
be security-scoped. In the browser there is no filesystem at all.

When the core needs something from the outside (randomness, time, a KDF worker), it declares a
Rust trait and the boundary crates supply the implementation. Example: a future `KdfProvider`
could route Argon2 to a native implementation on a slow device.

## The FFI contract (`keep-core-ffi`)

- Only `*Dto` records and enums defined in this crate cross the boundary. Internal `keep-core`
  types never get UniFFI derives, so they can change freely.
- Functions are coarse-grained ("open vault from bytes and credentials", "search"), not chatty
  per-field getters. Every crossing copies data.
- Errors become a single `CoreError` enum with stable, non-secret variants. Messages never contain
  vault content.
- Secrets that must cross (the master password on the way in, a copied password on the way out)
  travel as `Vec<u8>` for the shortest possible time. The core zeroizes its copies. See
  [SECURITY_MODEL.md](../security/SECURITY_MODEL.md) for what the managed side can and cannot
  guarantee.
- Long-running work (KDF) will be exported as `async` or run by the caller on a background thread.
  It never blocks the UI thread.

Byte arrays are declared as `Vec<u8>`. In UniFFI 0.32, `&[u8]` maps to `ByteBuffer` in Kotlin
instead of `ByteArray`.

### Current FFI surface

| Function | Returns | Notes |
| --- | --- | --- |
| `core_info()` | `CoreInfoDto` | |
| `detect_vault_format(header)` | `VaultFormatDto` | first 12 bytes are enough |
| `open_vault(data, VaultCredentialsDto)` | `VaultSessionHandle` or `OpenVaultError` | Blocking, runs the KDF: call off the UI thread. Returns an opaque object; the vault stays in Rust. |
| `close_all_vault_sessions()` | count | Hook for auto-lock |
| `VaultSessionHandle.info()` | `VaultInfoDto` | |
| `.groups()` | `[GroupDto]` | flat, linked by `parent_id` |
| `.entries()` / `.search(query)` | `[EntrySummaryDto]` | no secret values; search covers title, username and URL only |
| `.entry(id)` | `EntryDetailDto` | adds plain notes and plain custom values; protected ones are `Protected` |
| `.reveal(id, SecretFieldDto)` | `SecretValueDto { utf8 }` | the only secret-bearing call, one value per call |
| `.close_session()` | bool | idempotent. Later calls return `SessionClosed`, and `Drop` of the handle also closes. |

Errors are coarse: `OpenVaultError` (`NotAVault`, `Unsupported`, `InvalidCredentials`,
`Damaged`, `TooLarge`, `TooManySessions`, `Unavailable`) and `VaultSessionError`
(`SessionNotFound`, `SessionClosed`, `EntryNotFound`, `FieldNotFound`, `InvalidArgument`).
Entry ids are KDBX UUID strings. A malformed id is `InvalidArgument`, never a panic.

`VaultCredentialsDto` holds only `password: Option<Vec<u8>>` today. A key-file field will be
added next to it, and the core already models credentials as composite-key components.

## The WASM contract (`keep-core-wasm`)

The whole FFI contract, in JS naming ([ADR 0022](../adr/0022-browser-extension-product.md)):

- **Vault and session:** `openVault(bytes, password, keyFile?, material?, retain?)` (the password array is zero-filled),
  `createVault`, `securityPresets`, `benchmarkKdf`, `generateKeyFile`, `detectFormat`, `closeVault`, `closeAllVaults`,
  `unlockMaterial`, `isEditable`, `isDirty`, `prepareSave`, `markSaved`.
- **Reading:** `vaultInfo`, `listGroups`, `listEntries`, `searchEntries`, `getEntry` return plain JSON with no secret
  values; `revealPassword`, `revealField`, `revealHistory`, `attachmentBytes` return a `Uint8Array`; `customIcon`.
- **Editing:** `createEntry`, `updateEntry` (the draft is UTF-8 JSON in a `Uint8Array` that is zeroed after parsing),
  `setFavorite`, `deleteEntry`, `moveEntry`, `createGroup`, `renameGroup`, `deleteGroup`, `setEntryIcon`,
  `setGroupIcon`, `assignIconsByUrl`, `siteIconHosts`, `siteIconHost`, `setSiteIcons`, `addAttachment`,
  `removeAttachment`, `historyVersions`, `historyEntry`, `restoreHistory`.
- **Keep+ aware:** `totpCode`, `totpNormalize`, `historyVersions`, `addAttachment` and friends take the app's `Access` as
  JSON and refuse with `not_entitled`; `verifyKeepLease`, `evaluateKeepAccess`, `canUsePlusFeature`.
- **Import:** `parseImport` (wipes its input, keeps the plan inside the module and returns `{ planId, summary }`),
  `importPlan`, `dropImport`.
- **Passkeys:** `createPasskey`, `listPasskeys`, `signPasskey`, `removePasskey`.
- **Sites:** `matchHost`, `siteHost`, `iconForUrl`, `siteIconLinks`. **Generator:** `generatePassword`.
- Errors are `Error` objects whose `message` is a stable snake-case code (`invalid_credentials`, `session_closed`,
  `not_entitled`, …).
- Key material and decrypted entries stay in WASM linear memory. JS holds a 128-bit hex handle, and a secret only for
  the one call that asked for it.
- A browser's WebAssembly has no clock: the module installs the page's with `keep_core::set_clock` when it starts.
- The package is loaded only by the extension's offscreen document, never by content scripts or the interface
  pages. See [ADR 0003](../adr/0003-browser-extension-architecture.md).

## Facades on the app side

Each client wraps the generated bindings in a hand-written facade: `KeepCore` in Kotlin and
Swift, `loadKeepCore()` in TypeScript. UI and ViewModels depend on the facade interface, so they
can be tested with a fake and never import generated code.

| Client | Facade | Generated package |
| --- | --- | --- |
| Android, Desktop | `KeepCore` / `KeepVaultSession` / `SecretValue` in `apps/kotlin-core-bridge` (one source tree compiled by both `core-bridge` modules) | `app.vazie.keep.core.ffi` |
| iOS, macOS | `KeepCore` module: `KeepCoreClient`, `KeepVaultSession`, `SecretValue` | `KeepCoreBindings` |
| Extension | `src/core/createCore.ts`, `src/core/vaultSession.ts` (`KeepCore`, `KeepVaultSession`, `SecretBytes`), loaded by `src/core/keepCore.ts` | `@keep-core-wasm/*` |

No facade exposes the raw handle. Sessions are `Closeable` (Kotlin) or have `close()` plus
`deinit` (Swift) or `close()` (TS), and every call after close fails with `SessionClosed`.
