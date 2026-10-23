# Security model

Status: in force since 1.0. These are the rules the code follows and the tests check where they can. They describe
what the project does, not the result of an independent audit.

## Assets

| Asset | Where it may exist |
| --- | --- |
| Vault file (KDBX, encrypted) | User-chosen storage. We read and write it but never upload it. |
| Decrypted vault content (entries, notes, attachments) | Rust core memory while unlocked. Individual values in UI memory while displayed. |
| Master password, key file contents | Input field / chosen file → core, then discarded. A key file is re-read from its URI on every unlock and never copied. |
| Derived keys | Rust core memory only, while unlocked |
| Key file reference (Android) | URI + display name in app-private preferences, never the content. The file itself remains an authentication factor. |
| Biometric unlock material (Android, opt-in) | KeePass password component `SHA-256(password)`, password-equivalent for KDBX. Encrypted with a per-vault, strong-biometric-bound, non-exportable Keystore key; `no_backup`. |

## Threats in scope

- Loss or theft of an unlocked or locked device
- Malicious or compromised web pages (browser extension)
- Other apps on the device reading shared surfaces: clipboard, screenshots, backups, logs
- Accidental leakage by our own code: logs, crash reports, analytics, error messages
- Malformed or hostile vault files

Out of scope: a fully compromised OS or root/admin malware, hardware attacks on an unlocked
device, and a user who discloses their own master password.

## Rules

### Vault data

Decrypted vault data **never**:

- is logged, at any level, on any platform, including debug builds
- goes into telemetry or analytics (there is none, and adding any needs an ADR)
- appears in plaintext in crash reports, error messages or exception texts
- is written to disk outside the encrypted KDBX file: no caches, temp files or search indexes
  in plaintext
- is included in OS backups beyond the encrypted vault itself (Android `allowBackup=false`,
  data-extraction rules exclude everything)

Error types crossing the FFI carry stable codes and non-secret context only. Diagnostics about a vault (for example why
a save check failed) may name element names, positions and sizes, and never quote a title, a value or any other text
from the vault.

### Master password

The master password is **never**:

- stored in plaintext anywhere, including preferences, keychain and "remember me" features.
  Biometric unlock stores the wrapped KeePass password component (a derived value that is still
  a secret), never the password (ADR 0010).
- sent to any server
- logged, or put into a crash report or analytics
- held by platform UI longer than necessary. It is read from the field, passed to the core as
  bytes, and the field is cleared. The core derives the key and zeroizes its copy.

Password fields disable autocorrect, suggestions, personalised learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`),
and screen capture of the window.

### Clipboard

The future `Clipboard` adapter must:

- mark copied secrets as sensitive where the OS supports it (Android 13+ `EXTRA_IS_SENSITIVE`,
  iOS `UIPasteboard` `localOnly` + `expirationDate`, macOS `org.nspasteboard.ConcealedType`)
- **clear automatically** after a configurable timeout (default 30 s), and only if the clipboard
  still holds the value we put there
- never put a secret into clipboard history or universal clipboard (Handoff) when this can be
  avoided

### Memory

- **Rust core:** secrets live in types that zeroize on drop. `unsafe` is forbidden in `keep-core`.
  The core does not hand out long-lived references to decrypted data.
- **Managed runtimes (JVM/ART, Swift, JS):** the core cannot control copies made there.
  - JVM/ART `String` is immutable and can be copied by the garbage collector. Where the API
    allows it, secrets use `ByteArray`/`CharArray` that we overwrite. Values shown in Compose
    `Text` are unavoidably `String`s.
  - Swift `String` and `Data` have copy-on-write semantics and no guaranteed zeroing.
  - JS strings are immutable and garbage-collected. WASM linear memory can be zeroed, but
    anything passed to JS cannot.
  
  **Mitigation:** keep secrets in the core behind handles, pass values across the boundary only to
  display or copy one field, keep UI state for secrets short-lived, and lock (dropping every
  handle) on timeout and backgrounding. We document this residual risk rather than claim
  guarantees that managed runtimes cannot provide.

### Android policy (M4)

Auto-lock timeout (default 30 s), lock on screen off (default on), clipboard clearing (default
30 s, also on every lock), screenshot protection (on unless the user explicitly allows
screenshots) and theme are user settings in DataStore. Unknown values fall back to these
defaults. See [ADR 0008](../adr/0008-android-security-settings.md).

### Android key files and biometric unlock (M5)

- **Key files** ([ADR 0009](../adr/0009-key-files.md)):
  - KeePass-compatible: XML v1/v2 with hash check, 32-byte, 64-hex and hashed;
  - SAF with a persisted read permission; only the URI and display name are stored;
  - bounded read (64 MiB) on every unlock, buffers wiped;
  - a vault file chosen as a key file is refused.
- **Biometric unlock** ([ADR 0010](../adr/0010-android-biometric-unlock.md)):
  - off by default;
  - `BIOMETRIC_STRONG` only, never device credential;
  - Keystore AES-256-GCM, per-operation authentication, invalidated by enrollment changes,
    hardware-backed or refused;
  - one record per vault, bound to the vault URI and to the database fingerprint, with all
    metadata in the GCM associated data.
- **A key file stays a second factor:** the blob never contains key-file material, and the file
  is read on every biometric unlock.
- **The master password always works.** Any doubt (invalidated key, damaged blob, changed
  password, different database) leads to "set up again", never to a silent retry with weaker
  factors.

### iPhone policy (M6)

- **One owner:** `VaultSessionController` (ADR 0011). The app always starts locked, and nothing
  decrypted survives the process.
- **Files:**
  - security-scoped bookmarks, with access opened only for one coordinated read;
  - no copy of the vault or key file in the app container;
  - the reference is stored with file protection and excluded from backup.
- **Auto-lock:** default 30 s, decided from a monotonic clock on return so that a suspended app
  can't skip it. A protected-data loss (device lock with passcode) locks immediately when iOS
  delivers it.
- **Privacy overlay** whenever the scene isn't active and during screen capture
  (`sceneCaptureState`). iOS has no screenshot block, and we don't claim one.
- **Clipboard:** `localOnly` and a system `expirationDate` (default 30 s). iOS has no sensitive
  flag.
- **VoiceOver:** revealed values never reach the accessibility tree automatically; the explicit
  *Read aloud* action asks first.

### iPhone Face ID unlock (M7)

[ADR 0012](../adr/0012-ios-biometric-unlock.md); the material and key-file rules are ADR 0010's.

- **Off by default.** It is turned on in Settings after a manual unlock with a typed password,
  and only when Face ID is available.
- **Keychain generic password**, holding only the password component `UnlockMaterial`. That is
  not the password, but it opens the vault like one and is protected as a secret.
  - Access control `biometryCurrentSet`: Face ID only. There is no passcode alternative, and
    adding or removing a face makes the item unreadable for good.
  - Class `WhenPasscodeSetThisDeviceOnly`, not synchronizable: never in iCloud Keychain, never
    on another device, and deleted when the passcode is removed.
  - Verified on the iPhone 16 by tests: the data is refused without Face ID, the class is
    correct, the item isn't synchronizable.
- **Installation binding:** a random installation id in the sandbox, also stored in the item. With
  no active setup (including after a reinstall), every item of the service is deleted without
  being read.
- **Vault binding:** the database fingerprint is checked after opening. Choosing another vault
  deletes the setup.
- **Key file:** read again on every unlock; the item never contains key-file material. A
  key-file-only vault doesn't offer Face ID.
- **No prompt that can't help:**
  - no automatic prompt;
  - a missing key file, an unreadable file, a changed enrollment or a missing item is reported
    without prompting;
  - an invalidated setup never prompts again until it is set up anew.
- **The master password always works.** Lockout and "Use Master Password" lead to the password
  field, never to the device passcode.

### iPhone AutoFill (M8)

[ADR 0013](../adr/0013-ios-autofill-architecture.md),
[AUTOFILL_THREAT_MODEL](AUTOFILL_THREAT_MODEL.md).

- **The extension opens the vault itself**, through the same core, for one request at a time.
  The session closes after the fill, on cancel, on host background and on memory warnings.
- **One secret per request:** only the chosen entry's password is revealed, and it is handed to
  `ASPasswordCredential`. No clipboard, no notes, custom fields or TOTP.
- **Origins:**
  - an exact host, or a subdomain of a registrable saved host (core `origin`, Mozilla PSL);
  - equal ports, and no https→http downgrade;
  - no fuzzy matching; other entries only by explicit search.
- **Shared state:** the App Group holds bookmarks, the Face ID setup record and the installation
  id, never secrets. The Face ID item lives in one team-scoped Keychain group,
  `<team>.app.vazie.keep.shared`, with the same access control; it is moved there from the
  app's own group on the first app Face ID unlock.
- **Identities** given to iOS hold the host, the username and an opaque record id bound to the
  vault fingerprint. They are cleared when the vault changes and on a new installation.

### iPhone editing and saving (M9)

[ADR 0014](../adr/0014-kdbx-write-and-save.md), [SAVE_THREAT_MODEL](SAVE_THREAT_MODEL.md).

- **Never corrupt the vault:**
  - lossless DOM writer;
  - every save read back and compared before writing;
  - atomic replace only, with a byte-for-byte read-back after writing;
  - the previous version kept as an app-private encrypted backup.
- **Never overwrite someone else's save:**
  - the SHA-256 of the file at unlock is checked inside the coordinated write;
  - iCloud items must be the newest known version;
  - a conflict offers Reload or Save a Copy (Android also Merge, ADR 0023), never "overwrite anyway".
- **Nothing decrypted touches disk.** Only complete encrypted KDBX files are written: the vault,
  the backup, unsaved changes kept at lock, and exported copies.
- **What a save keeps from the file:** the cipher, KDF and its parameters (including the salt),
  compression and version. Seeds, IVs and the inner-stream key are new every save, from
  `getrandom`.
- **In memory while the vault is open:** the transformed key, never the password or the
  composite key.
- **Edit = save**: auto-lock meets only edits whose save failed, and those are kept as an encrypted
  copy for the next unlock.

### Android editing and saving (M10)

[ADR 0015](../adr/0015-android-safe-save.md), [SAVE_THREAT_MODEL](SAVE_THREAT_MODEL.md).

- **Same writer, no new cryptography.** Android writes the bytes the shared Rust writer returns.
- **Never lose the vault.** SAF gives a single-document grant no atomic replace. Instead the
  document is overwritten in place **without truncating**, after an app-private encrypted backup
  and a write-ahead copy of the new version, then read back; any failure puts the old bytes back,
  and a cut-off write is recoverable from the journal. This is **not atomic**.
- **Never overwrite someone else's save.** The SHA-256 of the file at unlock is compared with the
  current bytes before every save. There is no "overwrite anyway". Android has no cloud freshness
  API, so a provider that serves a stale copy is a residual risk.
- **Unsupported providers are refused**, with Save a copy as the way out.
- **Nothing decrypted touches disk.** The backup, journal, unsaved and recovered copies are
  complete encrypted KDBX files in `noBackupFilesDir`.
- **Secrets in the editor** live in the screen model, never in instance state; the generated
  password is hidden by default and never in the accessibility tree.
- **Biometric and key-file behaviour are unchanged by a save:** the vault fingerprint is
  root-UUID based, the biometric blob holds only the password component, and a key file is
  re-read at every unlock.
- **Launcher icon aliases** change only which icon is shown; every alias opens the same activity.

### Android Autofill (M11)

[ADR 0016](../adr/0016-android-autofill.md), [ANDROID_AUTOFILL_THREAT_MODEL](ANDROID_AUTOFILL_THREAT_MODEL.md).

- **A secret leaves the vault only after an explicit tap on one suggestion.** Every dataset is
  "authentication needed" and holds no value; the password is fetched after unlock, for that entry only.
- **No vault in the service.** The service reads a non-secret index. The authentication screen opens one
  short-lived session, reads the vault and any key file afresh, reveals once and closes it.
- **Who is asking:** the package and its signing certificate come from the system. A page's host is
  believed only from a pinned browser (package and certificate); an app that claims a web domain is
  ignored. Native apps match only through a user-approved association bound to package and certificate.
- **Matching** uses the shared core policy; the decision is repeated after unlock against the live entry.
- **No cached secrets, no cached vault bytes.** The index (title, username, URL, entry id) is
  unencrypted but app-private and backup-excluded; passwords never enter it.
- **The authentication screen** is secure, not exported, off the recents list and finishes at once; the
  fingerprint opens only after a tap and never replaces a key file.
- **Autofill refuses** when the vault changed, an entry is gone or no longer matches, or a save needs
  restoring. It never restores.
- **Saving (M11.1, [ADR 0017](../adr/0017-android-autofill-save.md)):** a system prompt, then Vazie Keep's own
  confirm screen, then unlock; never automatic. It writes only through the M10 safe save, refuses on
  external change or recovery, keeps the old password in KDBX history and holds the submitted
  password only in an in-process hand-off. `QUERY_ALL_PACKAGES` is not requested.

### Screens

Windows and activities that can show secrets are excluded from screenshots, screen recording and
recents thumbnails: `FLAG_SECURE` on Android (set globally today), a privacy overlay on iOS when
backgrounding, and `sharingType = .none` on macOS windows.

### Parsing untrusted files

Vault and key files are untrusted input. `keep-core` parses them in safe Rust
(`#![forbid(unsafe_code)]`). Every length read from the file is checked against the bytes that
remain before anything is sliced, and all allocations are bounded by `OpenLimits`:

| Limit | Default | Checked |
| --- | --- | --- |
| File size | 256 MiB | before parsing |
| Argon2 memory / iterations / parallelism | 1 GiB / 10 000 / 256 | before the KDF runs |
| AES-KDF rounds | 10⁹ | before the KDF runs |
| Decompressed payload | 512 MiB | during gzip inflation (decompression-bomb cap) |
| XML nodes | 20 000 000 | by `roxmltree` while parsing |
| Group nesting depth | 128 | during model building (bounds recursion) |
| Groups / entries (history included) | 100 000 / 1 000 000 | during model building |
| Single field size | 16 MiB | protected values before base64 decoding, plain text before copying |
| Attachments | 100 000 | inner header |

XML DTDs (and with them external and custom entities) are rejected. The limits are a first
policy, not a tuned production policy. Fuzz targets live in `core/fuzz` (see its README).

### Errors shown to users

`OpenError` is detailed for tests and diagnostics. Apps show only its coarse
`ErrorCategory`, which is also the only thing the FFI exports:

| Category | Covers |
| --- | --- |
| `NotAVault` | wrong signature, malformed outer header |
| `Unsupported` | KDBX 3.x / 1.x / ≥ 5, Twofish, Salsa20, exotic Argon2 parameters |
| `InvalidCredentials` | header HMAC mismatch (a wrong password is indistinguishable from a tampered HMAC) |
| `Damaged` | header hash mismatch, block HMAC failure, truncation, bad padding, gzip or XML errors |
| `TooLarge` | any `OpenLimits` limit |

There is no oracle beyond the one KDBX has by design: header damage is detected by an unkeyed
SHA-256 before any key is involved, and everything after the header HMAC is reported only after
the key is proven correct. CBC padding is checked only after block HMACs pass, so it cannot act
as a padding oracle. Error messages never contain file content, paths or credentials.

### Secrets in the Rust core

- `VaultCredentials::from_password` takes ownership of the password bytes, hashes them
  immediately, and the input is zeroized on drop. Only `SHA-256(password)` is kept.
- The composite key, the transformed key, the cipher key and the HMAC keys, as well as the
  decrypted payload, are `Zeroizing`.
- Protected values are `SecretString`. Their `Debug` prints `[REDACTED]`.
- `Debug` for `Vault`, `VaultGroup`, `VaultEntry`, `VaultMetadata`, `FieldValue` and every FFI
  DTO prints only ids, counts and flags, never names, titles or values.
- Since M2 the decrypted vault stays in a Rust `VaultSession` behind an opaque handle
  (ADR 0006). Summaries and details never contain protected values or entry passwords. Secrets
  leave Rust only through `reveal(entry, field)`, one value per call, as bytes.
- `VaultSession` drop, on close or on release of the last reference, zeroizes every secret and
  every plain string of the model.
- Every copy of a secret, and which of them can or cannot be wiped, is listed in
  [SECRET_LIFECYCLE.md](SECRET_LIFECYCLE.md).

Known gaps:

- `roxmltree` allocates non-zeroized copies of entity-decoded *plain* text.
- `Vec` growth during gzip inflation may leave plaintext in freed memory. Capacity is
  pre-sized from the gzip trailer to make this rare.
- UniFFI copies the password through a `RustBuffer` that is freed without zeroing, and the
  Kotlin `ByteArray` / Swift `Data` copies belong to the managed runtime. The Kotlin facade
  wipes the caller's array after the call.

## Browser extension: elevated risk

The extension runs next to attacker-controlled pages, inside a browser that auto-updates it. The full threat model is
[EXTENSION_THREAT_MODEL.md](EXTENSION_THREAT_MODEL.md); the rules in short:

- **Content scripts are hostile territory.** They share the DOM with the page and must never hold the vault, keys or
  lists of credentials. A content script receives one entry's secrets for its own frame's origin, after a click; the
  origin match is done by the core in the offscreen host from the browser-supplied sender, never by the script.
- **Origin matching** is the core's `match_host` (the phones' rule, plus ports). IDN/punycode is normalised. Phishing
  domains that look similar get no autofill.
- **The vault lives in an offscreen document**; the service worker holds no secrets. After any restart the default state
  is *locked*. Nothing that can unlock a vault is written to `chrome.storage`, `localStorage` or IndexedDB in the clear:
  the only unlock material stored is sealed with a key derived from a passkey's PRF output. ESLint rejects
  `localStorage` and `sessionStorage`.
- **Permissions** are minimal and justified per PR (ADR 0022 lists each). There are no remote scripts, and the CSP
  allows `wasm-unsafe-eval` only.
- **Supply chain:** one runtime dependency (Preact), a lockfile with `--frozen-lockfile` in CI, and reproducible builds
  from tagged sources before store release.
- **Clickjacking and overlay attacks** on injected UI: the menu, banner and prompts render in a closed shadow root and
  act only on trusted events.
- **The vault source** is a file the person picked, through the File System Access API; saves are the phones' safe save.

## Network

Vault operations need no network access. The desktop and mobile apps use the network only for
optional features. Such features never receive vault contents and never become required to open, edit
or save a vault.

Today the only one is the **Vazie Account and Keep+** ([ADR 0020](../adr/0020-keep-plus-entitlements.md),
[threat model](KEEP_PLUS_ENTITLEMENT_THREAT_MODEL.md)): signing in by email code and fetching a signed
lease. The requests carry a session token and nothing about vaults (no vault id, name, count, URL, entry
or device fingerprint). Android therefore declares `INTERNET`; its only network code is
`app.vazie.keep.account`. The account has no cryptographic relation to any vault, the master password
and the account are unrelated, and a failing or missing backend only ever changes what the account
screen says. The session token and the lease are stored in the Keystore-backed record (Android) or the
Keychain (iOS), are never logged, and are removed on sign-out together with Keep+ — never with a vault.
