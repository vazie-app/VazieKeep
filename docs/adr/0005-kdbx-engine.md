# ADR 0005: KDBX engine: our own protocol code over RustCrypto primitives

- Status: Accepted
- Date: 2026-09-30

## Context

M1 teaches `keep-core` to open KDBX 4.x read-only. We need a KDBX engine that:

- runs on Android, Apple, Windows, Linux and WASM from one codebase (ADR 0001);
- treats vault files as hostile input: bounded memory, bounded work;
- keeps secrets out of `Debug`, logs and errors;
- returns our own domain model rather than a third-party one.

## Options

### A. A mature Rust KDBX library

| Crate | Version (2026-09) | License | KDBX 4 | Notes |
| --- | --- | --- | --- | --- |
| `keepass` (keepass-rs) | 0.15.0 | MIT | read and write (4.1 only) | Most used. Also reads KDBX 3.1 and KDB. |
| `keepass-ng` | 0.11.12 | MIT | read and write | Fork of keepass-rs, small user base |
| `kdbx-rs` | 0.5.2 (2024) | GPL-3.0+ | read and write | License incompatible with MIT distribution. Inactive. |

We evaluated keepass-rs 0.15.0 in detail:

| Criterion | Finding |
| --- | --- |
| Activity | Very active: 0.13 → 0.14 → 0.15 within four weeks, with breaking API changes each time |
| KDBX 4 / Argon2 / AES-KDF / AES / ChaCha20 | Yes. Also Twofish, Salsa20 and KDBX 3.1. |
| Argon2 implementation | `rust-argon2`, not RustCrypto `argon2` |
| Protected fields | `secrecy`-wrapped values |
| Parser safety | Argon2 memory, iteration counts and rounds are taken from the file without limits. Gzip is inflated with unbounded `read_to_end`, so a small file can exhaust memory. |
| Secret handling | `DatabaseKey` **derives `Debug`** and stores the password as `Option<String>`, so a `{:?}` prints the master password. |
| XML robustness | Rejected our pykeepass-written fixtures with `duplicate field String` because its serde schema expects `<String>` elements to be contiguous. KeePass itself reads elements in any order. |
| Dependency tree | ~100 crates (chrono, uuid, indexmap, twofish, salsa20, byteorder, getrandom 0.4 …) |
| WASM | `getrandom 0.4` needs extra configuration on `wasm32-unknown-unknown` |
| Security history | No RustSec advisories |

### B. Our own KDBX protocol code over audited primitives

We write only the **format and protocol** layer. All cryptography comes from RustCrypto crates.

## Decision

**Option B.**

keepass-rs fails three of our non-negotiables: bounded parsing, no secret in `Debug`, and tolerant XML. Its churn would also force frequent adaptation work. Patching a fast-moving upstream on all three counts would mean maintaining a fork anyway. The KDBX 4 protocol is small and specified, and implementing it ourselves costs about 1,000 lines. keepass-rs remains a useful **cross-check**: it verified the crypto layers of our fixtures, and it failed on the XML ordering case described above.

## What we implement

- **Outer header:** signature and version, TLV fields, VariantDictionary, strict duplicate and length checks.
- **Keys:**
  - composite key: `SHA-256(SHA-256(password))`;
  - master key: `SHA-256(seed ‖ transformed)`;
  - HMAC base: `SHA-512(seed ‖ transformed ‖ 0x01)`;
  - per-block key: `SHA-512(index ‖ base)`.
- **Header integrity:** SHA-256 check, then HMAC-SHA-256 verification.
- **Payload:** HMAC block stream (verify first, then concatenate), AES-256-CBC/PKCS#7 or ChaCha20 decryption, gzip with an output cap.
- **Inner header:** random-stream id and key, attachment sizes.
- **Protected values:** decrypted in document order with the ChaCha20 inner stream. The key and nonce come from `SHA-512(key)`.
- **XML → domain model:** order-independent, whitespace-preserving, DTD rejected. `<History>` entries count toward limits but are not surfaced.
- **Limits:** see [SECURITY_MODEL](../security/SECURITY_MODEL.md#parsing-untrusted-files).

## What we delegate

| Concern | Crate |
| --- | --- |
| AES-256 block cipher (AES-KDF, CBC) | `aes` |
| CBC mode + PKCS#7 | `cbc` |
| ChaCha20 (payload, inner stream) | `chacha20` |
| Argon2d / Argon2id | `argon2` |
| SHA-256 / SHA-512 | `sha2` |
| HMAC-SHA-256, constant-time tag check | `hmac` |
| Zeroing of secrets | `zeroize` |
| gzip | `flate2` (pure-Rust `miniz_oxide` backend) |
| XML | `roxmltree` (read-only DOM, DTD off, node limit) |
| Base64 | `base64` (SIMD feature off) |

No cryptographic primitive is implemented in this repository. Versions, licenses and maintenance status are listed in [CRYPTO_DEPENDENCIES.md](../security/CRYPTO_DEPENDENCIES.md).

## Scope

- **Supported:** KDBX 4.0 and 4.1 (any 4.x minor); AES-256 and ChaCha20; AES-KDF, Argon2d and Argon2id; gzip or no compression; password credentials.
- **Rejected with a typed error:**
  - KDBX 3.x, KeePass 1.x and KDBX ≥ 5: `UnsupportedVersion`;
  - Twofish: `UnsupportedCipher`;
  - Salsa20 inner stream: `UnsupportedCipher`;
  - Argon2 with secret or associated data: `UnsupportedKdf`.
- **Not in M1:** key files and writing. The credential type is built so a key file becomes another composite-key component without an API break. Key files arrived in M5 ([ADR 0009](0009-key-files.md)).

## Security implications

- **No password oracle beyond the format's own.** `InvalidCredentials` is returned only when the header HMAC fails. That covers a wrong password and a tampered HMAC alike, and KDBX cannot tell them apart. Header SHA-256 mismatches (`CorruptedVault`) are detectable without any key, so reporting them leaks nothing. Everything after the HMAC is checked only once the key is proven correct. CBC padding is checked only after block HMACs pass, which rules out a padding oracle.
- **Bounded work before authentication.** KDF parameters and the file size are checked against `OpenLimits` before any KDF runs. The gzip output is capped. The allocation for the concatenated payload is sized from already-HMAC-verified block lengths.
- **Secrets:**
  - the password is hashed on construction of `VaultCredentials` and the input buffer is zeroized;
  - derived keys, the decrypted payload and protected values live in `Zeroizing`/`SecretString`;
  - every model type's `Debug` is redacted.
- **Residual risks, documented:**
  - `roxmltree` copies entity-decoded plain text;
  - `Vec` growth during gzip inflation can leave unzeroed copies (the capacity is pre-sized from the gzip trailer to make this rare);
  - FFI and managed runtimes copy whatever crosses the boundary.

## WASM implications

All chosen crates compile for `wasm32-unknown-unknown` without `getrandom`: the read path needs no randomness. CI builds `keep-core` for wasm32 on every change.

*Superseded in M2 by ADR 0006, which exports a handle-based session API to WASM.*

**`openVault()` is intentionally not exported to the browser extension in M1.** A WASM export would return decrypted entries into the JS heap, where they can't be zeroized. There is no handle-based API yet, and the vault source for the extension is undecided (ADR 0003). The extension keeps only `coreVersion()`. The browser milestone will add a handle-based WASM API that returns one field at a time.

## Consequences

- We own the correctness of protocol code. Mitigations:
  - shared test vectors from an independent writer (pykeepass), cross-checked by keepass-rs;
  - unit tests on every parser;
  - cargo-fuzz targets (`core/fuzz`) ready for the security milestone.
- Adding KDBX writing (M2+) is our work too. The read path's key schedule and block stream are its mirror image.
- **Revisit** if a well-maintained crate appears with limits, redacted secrets and tolerant XML.
