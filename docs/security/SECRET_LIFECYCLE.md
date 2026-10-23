# Secret lifecycle

Where plaintext secrets exist, who can wipe them, and which copies nobody can wipe. "Secret" here
means the master password and the values KeePass protects (entry passwords, protected custom
fields). Plain vault data (titles, usernames, notes) follows the same path, minus the reveal
step.

Legend: ✅ zeroized by our code · ⚠️ copied into memory we free but cannot zero · ❌ unavoidable
platform copy.

## 1. Master password, into Rust

| Stage | Holder | Status |
| --- | --- | --- |
| Text field in the UI | platform `String` / `Editable` / JS string | ❌ The UI toolkit owns it. Future unlock screens should read it as `CharArray`/`Data` where possible and clear the field. |
| Bytes passed to the core | Kotlin `ByteArray` · Swift `Data` · JS `Uint8Array` | ✅ Kotlin facade `fill(0)` after the call; WASM zero-fills through `&mut [u8]` copy-back. ❌ Swift `Data` passed by value is copy-on-write and not wiped by the facade. |
| UniFFI lowering | `RustBuffer` (Kotlin/Swift → Rust) | ⚠️ Freed by UniFFI without zeroing |
| Rust lift | `Vec<u8>` → `SecretBytes` | ✅ zeroized on drop |
| `VaultCredentials` | `SHA-256(password)` and the key-file component only; the raw inputs are dropped | ✅ |
| Composite, transformed, cipher and HMAC keys | `Zeroizing<[u8; N]>` | ✅ |
| Argon2 working memory | `argon2` crate (`zeroize` feature) | ✅ as far as the crate guarantees |

## 1a. Key file (M5)

```
SAF URI ─▶ read bytes ─▶ key-file parser ─▶ key component ─▶ composite key ─▶ zeroize
```

| Stage | Holder | Status |
| --- | --- | --- |
| Reference | `SharedPreferences("vault_reference")`: URI and display name only | not secret. The content is never stored. |
| `ContentResolver` stream | provider process and kernel page cache | ❌ outside our process |
| Read loop | 64 KiB buffer + `ByteArrayOutputStream` | ✅ both zero-filled after the read · ⚠️ the stream's earlier, smaller arrays are dropped unwiped when it grows (files over 32 bytes) |
| Bytes handed to the core | Kotlin `ByteArray` | ✅ wiped by `VaultCredentials.close()` after `openVault` |
| UniFFI lowering | `RustBuffer` | ⚠️ freed without zeroing |
| Rust lift | `SecretBytes` | ✅ zeroized on drop |
| XML key file | `roxmltree` DOM over the UTF-8 text; whitespace-stripped data in `Zeroizing<String>` | ✅ data string · ⚠️ entity-decoded text nodes are copies roxmltree owns |
| Key component | `Zeroizing<Vec<u8>>` inside `VaultCredentials` | ✅ |
| Composite key and everything after it | as in §1 | ✅ |

On an empty password with a key file, the app may run the KDF twice (key file alone, then with
an empty password). Each attempt re-reads the key file and wipes it.

## 2. Vault content, inside Rust

| Stage | Holder | Status |
| --- | --- | --- |
| Encrypted file bytes | caller's buffer | not secret |
| Concatenated HMAC blocks → decrypted payload | `Zeroizing<Vec<u8>>`, sized exactly from verified block lengths | ✅ |
| gzip output | `Zeroizing<Vec<u8>>`, capacity pre-sized from the gzip trailer | ✅ final buffer · ⚠️ reallocation copies if the trailer lies |
| XML DOM (`roxmltree`) | borrows the payload; entity-decoded text is copied | ⚠️ plain text only. Protected values are base64 (never entity-encoded) and decoded by us. |
| Protected value | base64 → `Zeroizing<Vec<u8>>` → XOR stream → `SecretString` | ✅ |
| `VaultSession` | owns every `SecretString` (✅) and every plain `String`. Model types zeroize their plain strings in `Drop`. | ✅ on close / last `Arc` drop |

## 3. Reveal, out of Rust

| Stage | Holder | Status |
| --- | --- | --- |
| `VaultSession::reveal` | fresh `SecretString` | ✅ dropped at end of the FFI call |
| FFI DTO | `SecretValueDto { utf8: Vec<u8> }` | ⚠️ UniFFI serialises it into a `RustBuffer` and frees the `Vec` without zeroing |
| `RustBuffer` → platform | Kotlin reads into `ByteArray` · Swift into `Data` | ⚠️ `RustBuffer` freed without zeroing |
| Platform `SecretValue` | `ByteArray` / `Data` | ✅ `close()` / `wipe()` / Swift `deinit` zero-fill |
| Kotlin `useChars` | decoded `CharArray` and the decoder's `CharBuffer` | ✅ both zero-filled after the block |
| Anything the block turns into a `String` | UI text, clipboard, `AutofillValue` | ❌ immutable, GC-managed |
| WASM `revealPassword` | `SecretString` in linear memory → `Uint8Array` copy | ✅ WASM copy zeroized · JS `SecretBytes.wipe()` zero-fills the array |
| `useText` in JS | `TextDecoder` output string | ❌ JS strings cannot be wiped |

## 3a. Android UI (M3)

| Stage | Holder | Status |
| --- | --- | --- |
| Typed master password | `TextFieldState` in `UnlockViewModel`: not saveable, never in a `Bundle` | ❌ the text buffer is managed by Compose; cleared (`clearText`) on submit and in `onCleared` |
| Master password in the accessibility tree | masked `editableText` (`•` × length) on a wrapper node (ADR 0008) | ✅ the plain text never reaches accessibility services; Compose's default secure field leaked it (found on device in M4) |
| Encoded password | UTF-8 `ByteArray` from a `CharBuffer` wrap; the encoder's buffer is zero-filled | ✅ zero-filled by the repository and the bridge after `openVault` |
| Revealed secret | `SecretValue` → `useChars` → one `String` in `EntryUiState.Loaded.revealed` | ✅ `SecretValue` closed at once · ❌ the `String` lives until GC; dropped from state after 15 s, on hide, leave, rotation, background, lock |
| Rendered text | Compose `Text` layout / glyph cache | ❌ platform-owned; kept out of accessibility semantics |
| Copied secret | `ClipData` in the system clipboard (sensitive flag) | ❌ system-owned; cleared by us after the configured timeout (default 30 s, or never), **and on every lock**, if still ours · keyboard clipboard histories (e.g. Samsung) may keep their own copy |
| Read aloud (explicit, confirmed) | revealed value placed in the node's accessibility description until it auto-hides | ❌ delivered to every enabled accessibility service, by the user's explicit choice |
| Saved state / navigation | only `entryId` (a KDBX UUID) | ✅ no secret ever enters a route, `SavedStateHandle` or `rememberSaveable` |

## 3b. Biometric unlock (M5, ADR 0010)

```
encrypted blob at rest ─▶ BiometricPrompt ─▶ Keystore decrypt ─▶ unlock material ─▶ Rust ─▶ zeroize
```

| Stage | Holder | Status |
| --- | --- | --- |
| Password component retained for "turn on" | `UnlockMaterial` in the Rust `VaultSession`, only after a typed password and only while the feature could still be enabled | ✅ zeroized when the session closes |
| Export during enabling | FFI `UnlockMaterialDto` → Kotlin `UnlockMaterial` | ⚠️ `RustBuffer` unzeroed · ✅ Kotlin copy wiped right after encryption |
| At rest | `no_backup/biometric_unlock.bin` (0600): AES-256-GCM ciphertext; the key never leaves the TEE | encrypted · not in any backup |
| Keystore decrypt | `Cipher.doFinal` output `ByteArray` | ✅ wiped by the repository after `openVault` · ❌ copies inside the Keystore client and binder parcels are platform-owned |
| Into Rust | `PasswordDto::UnlockMaterial` → `SecretBytes` → `UnlockMaterial` | ⚠️ `RustBuffer` · ✅ Rust copies zeroized |
| Disable / invalidation | `KeyStore.deleteEntry(alias)` + file deleted (invalidation keeps a tombstone with the vault URI only) | ✅ nothing secret remains |

Never stored anywhere: the master password, key-file bytes, the composite or transformed key.

## 3c. iPhone (M6, ADR 0011)

```
SecureField ─▶ Swift String ─▶ UTF-8 Data ─▶ UniFFI ─▶ Rust ─▶ zeroize
Rust reveal ─▶ Data (SecretValue) ─▶ temporary Swift String ─▶ SwiftUI Text ─▶ hide
```

| Stage | Holder | Status |
| --- | --- | --- |
| Typed master password | `UnlockModel.password` (`String`, observable object; not `@State`, not saved) | ❌ Swift `String` can't be zeroed. Emptied on submit and when the screen goes away. |
| Encoded password | `Data(password.utf8)` passed by value into the facade | ❌ copy-on-write `Data`; it can't be reliably wiped and is released when the call returns |
| Key-file bytes | `Data` from a coordinated, security-scoped read | ❌ the same; released after `openVault`. Never stored; only the bookmark and name are kept. |
| UniFFI lowering / Rust | `RustBuffer` → `SecretBytes` | ⚠️ / ✅ as in §1 and §1a |
| Revealed value | `SecretValue` (`Data`) → one `String` in `EntryModel.revealed` | ✅ `SecretValue.wipe()` right away · ❌ the `String` lives until released; dropped after 15 s, on leaving the entry, on background, on capture and on lock |
| Rendered text | SwiftUI `Text` / Core Text caches | ❌ platform-owned; kept out of accessibility (explicit *Read aloud* only) |
| Copied value | `UIPasteboard` item, `localOnly`, `expirationDate` | ❌ system-owned; removed by iOS at expiry (default 30 s) and by us on lock if still ours |
| App-switcher snapshot | privacy overlay when not `.active` | ✅ no vault content in the snapshot |
| Navigation / saved state | group and entry ids only | ✅ nothing secret is ever restored |

## 3d. iPhone Face ID unlock (M7, ADR 0012)

```
manual password ─▶ Rust session keeps UnlockMaterial (only while Face ID could be turned on)
  ─▶ Face ID ─▶ Data ─▶ SecItemAdd (biometryCurrentSet, ThisDeviceOnly) ─▶ wipe Data
lock ─▶ Face ID ─▶ SecItemCopyMatching ─▶ Data ─▶ UniFFI ─▶ Rust VaultSession ─▶ wipe Data / zeroize
```

| Stage | Holder | Status |
| --- | --- | --- |
| Password component kept for "turn on" | `UnlockMaterial` in the Rust `VaultSession`, only after a typed password, with Face ID available and not yet on | ✅ zeroized when the session closes |
| Export while turning on | FFI `UnlockMaterialDto` → `SecretValue` (`Data`) | ⚠️ `RustBuffer` · ✅ `SecretValue.wipe()` after `SecItemAdd` · ❌ the `Data` copy handed to the Keychain API is copy-on-write |
| At rest | Keychain item: Face ID (`biometryCurrentSet`), `WhenPasscodeSetThisDeviceOnly`, not synchronizable | protected by the Secure Enclave–enforced access control · not in backups, not in iCloud |
| Setup record | `face-id-unlock.json`: setup id, installation id, fingerprint, key-file flag, Face ID state hash | not secret |
| Read after Face ID | `Data` from `SecItemCopyMatching` | ✅ `resetBytes` right after `openVault` · ❌ copies inside `securityd` and the Keychain client are platform-owned |
| Into Rust | `PasswordDto::UnlockMaterial` → `SecretBytes` | ⚠️ `RustBuffer` · ✅ Rust copies zeroized |
| Disable, invalidation, reinstall | `SecItemDelete`; record removed or kept as "needs setup" without the enrollment hash | ✅ nothing secret remains in the app |

LocalAuthentication evaluates the face outside the process; the app only receives a context.
Nothing in this path is logged; the debug log records states and counts only.

## 3e. iPhone AutoFill (M8, ADR 0013)

```
request ─▶ (Face ID ─▶ Keychain material │ typed password) ─▶ vault + key file read ─▶ Rust session
  ─▶ summaries (no secrets) ─▶ user picks one ─▶ reveal ─▶ SecretValue ─▶ String ─▶ ASPasswordCredential
  ─▶ completeRequest ─▶ wipe SecretValue ─▶ lock()
```

| Stage | Holder | Status |
| --- | --- | --- |
| Identity index | `ASCredentialIdentityStore`: host, username, record id | not secret; owned by iOS |
| Unlock | as §3c and §3d, inside the extension process | the same guarantees; the extension never retains material |
| Lists and search | `EntrySummary` (title, username, URL) | no password is revealed to build them |
| Chosen password | `SecretValue` → one `String` → `ASPasswordCredential` | ✅ `SecretValue.wipe()` · ❌ the `String` and AuthenticationServices' copy are outside our control |
| Session | `VaultSessionController` in the extension | ✅ closed right after `completeRequest`, on cancel, on host background and on memory warnings |

Nothing passes through the clipboard or the App Group.

## 3f. Editing and saving (M9, ADR 0014)

```
editor Strings ─▶ EntryDraft (Data) ─▶ UniFFI ─▶ SecretString in the Rust DOM ─▶ wipe draft Data
save: DOM ─▶ plaintext XML (Zeroizing) ─▶ gzip (Zeroizing) ─▶ encrypt ─▶ encrypted bytes ─▶ verify ─▶ Swift ─▶ atomic file write
```

| Stage | Holder | Status |
| --- | --- | --- |
| Values being edited | `EntryEditorModel` strings (password, protected fields, revealed once when the editor opens) | ❌ Swift `String`; cleared when the editor closes |
| Draft across the FFI | `EntryDraft` `Data` → `Vec<u8>` → `SecretString` | ✅ `wipe()` after the edit · ⚠️ `RustBuffer` · ✅ Rust copies zeroized |
| Document | Owned DOM; protected values as `SecretString`, other text in `Zeroizing<String>` | ✅ zeroized when replaced (each edit) and when the session closes |
| Transformed key | `SaveState` in the Rust session | ✅ zeroized when the session closes; opens only this file with this KDF salt |
| Plaintext XML and compressed payload during save | `Zeroizing` buffers | ✅ wiped after encryption; gzip buffer pre-sized so it never reallocates |
| Encrypted bytes | `Vec<u8>` → `Data` → file, backup, unsaved copy or exporter | encrypted; backup and unsaved copy app-private with complete file protection |
| New UUIDs, seeds, IVs, inner-stream key, generated passwords | `getrandom` | ✅ keys zeroized after use |

## 3g. Android editing and saving (M10, ADR 0015)

```
TextFieldState (screen model) ─▶ EntryDraft (ByteArray) ─▶ UniFFI ─▶ Rust DOM ─▶ draft.close() wipes the arrays
generator: Rust ─▶ SecretValue ─▶ (String only while revealed or when used) ─▶ TextFieldState
save: prepare_save ─▶ encrypted ByteArray ─▶ app-private backup + journal ─▶ in-place write ─▶ read-back
```

| Stage | Holder | Status |
| --- | --- | --- |
| Values being edited | `EditorFields` `TextFieldState`s in the screen model; never `rememberSaveable` or `SavedStateHandle` | ❌ immutable `String`/`CharSequence` inside Compose state; wiped (overwritten, then cleared) when the editor saves, locks or the model is cleared |
| Draft across the FFI | `EntryDraft` `ByteArray`s (UTF-8) | ✅ `close()` overwrites them after the call · ⚠️ UniFFI `RustBuffer` copy · ✅ Rust copies zeroized |
| Generated password | `SecretValue` in the screen model | ✅ closed on dismiss, regenerate, use and lock · ❌ one `String` while revealed and when used; hidden on background |
| Accessibility | masked `editableText`; the generated value is never in the semantics tree | tested |
| Encrypted bytes | `ByteArray` → backup, unsaved copy, journal, document, "Save a copy" | encrypted; app copies in `noBackupFilesDir`, excluded from backup |
| Logs | typed failure names and timings only (debug builds) | no names, URIs, values |

Process death with a failed save: the encrypted snapshot was already written to app-private
storage when the save failed, so nothing readable is lost and nothing plaintext exists.

## 3h. Android Autofill (M11, ADR 0016)

```
tap on a suggestion ─▶ fingerprint (stored password component) or typed password ─▶ short-lived Rust session
   ─▶ reveal(entry, Password)  ─▶ SecretValue ─▶ String in an AutofillValue ─▶ framework fills ─▶ session closed
```

| Stage | Holder | Status |
| --- | --- | --- |
| Before the tap | the non-secret index (title, username, URL, id) and datasets with no values | no secret exists |
| Typed password | `TextFieldState` in the screen model | ❌ immutable text inside Compose state; cleared at once when submitted, on cancel and when the model clears |
| Fingerprint material | `ByteArray` from the Keystore decrypt, passed to the opener | ✅ overwritten after the open |
| Session | one `VaultSessionHandle` per request | ✅ closed in a `finally` before the result is delivered; the vault file bytes are not kept |
| Revealed password | `SecretValue` in `FillPayload` | ✅ closed right after the Dataset is built · ❌ one `String` inside the `AutofillValue` |
| Dataset in the result | `Intent` extra to the framework | handed to the system, which fills the target view; not stored by us |
| Logs and semantics | durations only; masked password node | no domain, username, title, package or value |

What a killed process leaves: nothing readable. The index and associations are non-secret files; the
pending intent holds ids only.

## 3i. Android Autofill save (M11.1, ADR 0017)

```
form values (AssistStructure) ─▶ SubmittedCredential(SecretValue) in SaveHandoff ─▶ token only in the PendingIntent
  ─▶ confirm + unlock ─▶ EntryDraft bytes ─▶ updateEntry/createEntry ─▶ prepareSave ─▶ journaled write ─▶ session closed
```

| Stage | Holder | Status |
| --- | --- | --- |
| Value read from the structure | one `String` per field in the service | ❌ immutable; short-lived |
| Hand-off | `SecretValue` in `SaveHandoff` | ✅ wiped on take, discard, expiry or capacity eviction |
| Save screen | `SecretValue` from `take`; shown only masked, revealed on request | ✅ closed after the save, on cancel or on model clear |
| Draft to the core | `EntryDraft.password` bytes | ✅ closed after the call |
| Intent, `SavedStateHandle`, index, logs | nothing | no secret |

A killed process loses the hand-off (the screen then ends cancelled); nothing readable persists.

## What Android and Swift cannot wipe

- `ContentResolver`/binder buffers, the provider's own copies, and the page cache of the key
  file.
- `ByteArrayOutputStream` arrays discarded on growth.
- The Keystore client's and binder's internal buffers around `Cipher.doFinal`.
- Compose's text buffer for the typed password (§3a).
- Swift `String` and `Data` (password field, encoded password, key-file bytes, revealed value,
  the unlock material between the Keychain and the FFI): copy-on-write and ARC-managed; no API
  guarantees zeroing. `Data` is wiped with `resetBytes` where we hold the only reference.
- Copies made by `securityd`, the Keychain client and LocalAuthentication.

## 4. Close

`KeepVaultSession.close()` → `close_session` → the store removes the `Arc` → when the last reader
returns, `VaultSession` drops → every `SecretString` and plain string in the model is zeroized.

What survives a close:

- summaries, details and revealed secrets that the platform still references. They are its own
  copies. UIs must drop them on lock.
- the ⚠️ freed-but-unzeroed allocations above, until the allocator reuses them.

## 3j. Vazie Account (Keep+ M1, ADR 0020)

Not a vault secret, but a credential with a lifecycle of its own:

- The one-time code is typed, sent once, and held in a text-field state that is cleared on success and when
  the screen goes away; it is never put in saved state.
- The **session token** is returned once by the backend, stored encrypted (Keystore record / Keychain) and
  read only to call the backend. Sign-out clears the record, deletes the Keystore key, and revokes the
  session on the backend (best effort, after the local state is gone).
- The **lease** is not secret, but is stored with the session so that signing out removes both.
- None of it is ever mixed with vault material: the master password, key files and unlock material
  never leave the core, and no account call includes anything about a vault.

## What we do not promise

- That no copy of a secret remains in process memory after close. Managed runtimes (ART/JVM,
  Swift, V8) and FFI glue make copies we do not control.
- Protection against an attacker who can read process memory or attach a debugger while a vault
  is unlocked (out of scope in SECURITY_MODEL).

## What we do guarantee (and test)

- No API returns a secret except the explicit reveal calls, and each returns exactly one value.
- `Debug`, `Display`, `toString()`, `description`, `dump()` and `JSON.stringify` of every
  session, summary, detail, secret wrapper and error omit secret values. Tests search for the
  marker `VAZIE_KEEP_SECRET_DO_NOT_LEAK_42` in Rust, Kotlin, Swift and TypeScript.
- Closing a session makes every later call fail with `SessionClosed`, and the Rust-side data is
  zeroized once in-flight calls finish.
- There is no logging of vault data anywhere: core, bridges, extension. This was audited in M2,
  and there is no logging framework.

## Mitigations to add later

- Short-lived secret handles in Rust (`reveal` → secret handle → `copy_to_clipboard` done in
  Rust-adjacent platform code) to shrink the platform copy further.
- A zeroizing global allocator wrapper for the FFI crates, which would also cover the
  ⚠️ rows. Evaluate its cost on Android.
- `revealForOrigin` for browser autofill (ADR 0006).
