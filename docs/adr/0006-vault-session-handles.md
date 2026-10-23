# ADR 0006: Vault session handles, secrets on demand

- Status: Accepted
- Date: 2026-09-30
- Supersedes the M1 FFI call `open_vault → VaultDto` (removed, pre-release).

## Context

In M1, `open_vault` returned the whole decrypted structure to Kotlin and Swift. Protected values
were masked, but every title, username, URL and note was copied into managed memory at once and
lived as long as the platform kept the DTO. The platforms' copies can't be wiped. The more
data crosses early, the more of the vault outlives a lock.

## Decision

The decrypted vault **stays in Rust**. Opening a vault creates a `VaultSession` inside the
core, and platforms get an opaque handle to it:

```
KDBX bytes ─▶ keep_core::open_vault ─▶ VaultSession ─▶ VaultSessionStore
                                                         │ opaque handle
                         Kotlin / Swift / JS ◀───────────┘
  info · groups · entries · search · entry(id) ─▶ non-secret views
  reveal(id, field) ─▶ one secret, as UTF-8 bytes, only when asked
  close ─▶ session removed, secrets zeroized
```

### Read model

The session exposes views that carry no secret by construction:

- `VaultInfo`
- `GroupSummary`
- `EntrySummary`: title, username, URL, tags, `has_password`, `has_notes`,
  `has_protected_fields`, custom-field *names* with a protected flag, attachment names
- `EntryDetail`: adds plain notes and plain custom values

A field KeePass marks `Protected` appears as `Protected`/`Hidden` with no content. The entry
password is **always** treated as a secret, even when the file stores it unprotected.

### Secret access

The only way to read a secret is `reveal(entry_id, field)`. `field` is a closed enum:
`Password | Title | UserName | Url | Notes | Custom { name }`. The only free-form string is a
custom-field name, because KDBX keys custom fields by name. Every call returns exactly **one**
value. Nothing is prefetched, and search never looks at secret values.

### Entry ids

Entries and groups are addressed by their KDBX UUID (`ItemId`), serialised as a hyphenated UUID
string. Ids are stable across sessions and re-opens, which future autofill, favorites and
editing rely on. List positions are never part of the API. A file with duplicate entry or group
UUIDs is rejected as `CorruptedVault`: lookups by id must be unambiguous.

## Handle format

| Surface | Handle | Why |
| --- | --- | --- |
| Rust core | `SessionId`: 128 bits from the OS CSPRNG (`getrandom`); `Debug` prints `SessionId(..)` | Unguessable, never reused (collisions are re-rolled against open and recently closed ids) |
| Kotlin / Swift (UniFFI) | `VaultSessionHandle` **object** wrapping a `SessionId` | Platform code gets an opaque class. It cannot forge, read or reuse the id, and a malformed handle can't be expressed. |
| WASM / JS | the `SessionId` as 32 lowercase hex characters | JS objects can't carry Rust ownership across extension contexts. A string can be passed over `chrome.runtime` messaging, and it is unguessable by other contexts. |

Sequential integers were rejected. They are guessable, so any context that can call the API
could reach any other session, and a recycled number can silently point a stale handle at a new
vault.

The ids of the last 256 closed sessions are remembered. A stale handle then yields
`SessionClosed` rather than `SessionNotFound`, and the UI can say "vault locked" instead of
showing a bug.

## Concurrency

- `VaultSession` is immutable after construction, so it is `Send + Sync` and needs no lock of its
  own.
- `VaultSessionStore` holds `RwLock<HashMap<SessionId, Arc<VaultSession>>>`. Every operation
  locks only to look up, insert or remove an `Arc`, and never while the work runs:
  - key derivation runs before `insert`;
  - reads clone the `Arc` and release the lock;
  - `close` removes the `Arc` under the lock, and the vault is dropped (and zeroized) after the
    lock is released.
- A `close` racing with a read is safe. The in-flight read keeps its `Arc`, the vault is freed
  when that read returns, and later calls get `SessionClosed`.
- A poisoned lock is recovered with `PoisonError::into_inner`. The critical sections are single
  map operations and cannot leave the map inconsistent. The core has no `unwrap` on lock
  results.
- The FFI store is a process-wide `LazyLock<VaultSessionStore>`. WASM has its own. On
  `wasm32` the locks are uncontended, since there's a single thread.

## Lifecycle and cleanup

1. **Explicit close.** `KeepVaultSession.close()` (Kotlin `Closeable`, Swift `close()`) or
   `closeVault(handle)` (JS) removes the session. The handle becomes invalid and further calls
   return `SessionClosed`. Closing is idempotent.
2. **RAII fallback.** The UniFFI `VaultSessionHandle` closes its session in `Drop`, which runs
   when Swift releases the object (`deinit`) or Kotlin's cleaner or `close()` destroys it. A
   forgotten Kotlin/Swift session is therefore bounded by the platform's object lifetime, not
   by process lifetime.
3. **Global shutdown.** `close_all_vault_sessions()` / `closeAllVaults()` is the hook for the
   future auto-lock: screen lock, backgrounding, idle timeout. The platform `AutoLock` adapter
   calls it. It is not wired to UI yet.
4. **Hard cap.** At most 16 open sessions per store. Opening more returns `TooManySessions`
   *before* running the KDF, so leaked handles cannot grow memory without bound.

No TTL in M2. Idle time is a platform concept (Android lifecycle, `scenePhase`, `chrome.idle`),
and `std::time::Instant` panics on `wasm32-unknown-unknown`. Timed locking belongs to the
auto-lock milestone, which will call the shutdown hook.

## Password representation at the boundary

Secrets cross the FFI as **UTF-8 bytes**: `SecretValueDto { utf8: Vec<u8> }`, then Kotlin
`ByteArray`, Swift `Data` or a JS `Uint8Array`. They are never a `String`. Each platform wraps
them in its own `SecretValue` type:

| | Kotlin `SecretValue` | Swift `SecretValue` | TS `SecretBytes` |
| --- | --- | --- | --- |
| Access | `useChars { CharArray }`, `useBytes {}` | `withString {}`, `withBytes {}` | `useText(text => …)` |
| Wipe | `close()` zero-fills the array; decoded `CharArray` is zero-filled after the block | `wipe()`/`deinit` `resetBytes` | `wipe()` zero-fills |
| `toString` / `description` / `JSON` | `SecretValue(<redacted>)` | redacted, empty `Mirror` | `SecretBytes(<redacted>)`, `"[redacted]"` |

**Why bytes.** A byte buffer is mutable and can be overwritten. A JVM/Swift/JS `String` is
immutable, may be interned or copied, and can't be wiped. Bytes let the platform defer creating
a `String` until the exact moment an API demands one: a `TextField`, the clipboard, or
`input.value`. Many consumers can avoid it entirely (Android `AutofillValue.forText` accepts a
`CharSequence` built over a `CharArray`).

**UX cost.** Showing a password in a text field still needs a `String`. That copy is
unavoidable and documented. UTF-8 correctness is preserved because Rust only ever holds valid
UTF-8 (`SecretString`), and decoding uses strict decoders (`REPORT` in Kotlin, `fatal: true`
in JS, a failable initializer in Swift).

The master password travels **into** Rust the same way: `ByteArray`/`Data`/`Uint8Array`. The
Kotlin facade and the WASM boundary zero-fill the caller's buffer after the call.

## WASM implications

With handles, the browser model is now acceptable:

- The decrypted vault lives in WASM linear memory, inside the extension page that opened it.
- JS receives the handle, non-secret summaries (as plain JSON objects) and a `Uint8Array` for a
  single secret on explicit request.

So M2 exports:

- `openVault`, `vaultInfo`, `listGroups`, `listEntries`, `searchEntries`, `getEntry`;
- `revealPassword`, `revealField`;
- `closeVault`, `closeAllVaults`.

The popup UI does not use them yet.

Limits:

- The service worker must not host a session, because it is killed at will. Sessions belong to
  an extension page or offscreen document (ADR 0003).
- A revealed secret necessarily exists in JS until it is written into the page.

### Future: `fillCredential` instead of reveal

Could JS avoid holding the password at all? We looked at a `fillCredential(handle, entryId,
target)` design:

- WASM cannot touch the DOM, and content scripts must not host the vault. The last hop,
  `input.value = …` in the page's frame, is a JS string in the page's own realm, and every
  password manager has the same limitation.
- What *can* shrink: (1) the origin check moves into Rust, so `revealForOrigin(handle, entryId,
  origin)` releases the secret only if the entry's URL matches the requesting frame's origin;
  (2) the extension page sends the bytes straight to that frame's content script with
  `chrome.tabs.sendMessage(tabId, …, { frameId })`, which sets the value, zero-fills the array and
  drops the reference; (3) no copy is kept in the popup or the service worker.
- Result: the password lives in extension JS for one message hop. The page's copy is the unavoidable
  final destination. We will implement this in the browser autofill milestone. `revealPassword`
  remains for explicit "copy/show" actions only.

## Consequences

- Platforms hold one handle per open vault and ask Rust for everything else. Listing a large
  vault crosses the FFI once per call. That is acceptable, and `search` runs in Rust.
- The Kotlin facade now lives in `apps/kotlin-core-bridge`: one source tree compiled by both
  the Android and the desktop `core-bridge`. This removes the ~250 lines that would otherwise
  have been duplicated, with no new Gradle module.
- Zeroization guarantees end at the FFI boundary. See
  [SECRET_LIFECYCLE.md](../security/SECRET_LIFECYCLE.md) for every copy.
