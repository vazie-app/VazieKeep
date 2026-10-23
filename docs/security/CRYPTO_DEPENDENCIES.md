# Security-sensitive dependencies

Every crate `keep-core` depends on for cryptography or for parsing untrusted input. The full
normal-dependency tree of `keep-core` is 31 crates, mostly RustCrypto support crates
(`cipher`, `digest`, `hybrid-array`, `inout`, …).

Last reviewed: 2026-10-04 (writer: no new crates; `flate2` and `getrandom` gained uses). RustSec advisories were checked against the `rustsec/advisory-db`
repository on that date.

| Crate | Version | License | Why | Used in | Maintained | Advisories |
| --- | --- | --- | --- | --- | --- | --- |
| `aes` | 0.9.3 | MIT/Apache-2.0 | AES-256 for AES-KDF and CBC | `kdbx/crypto.rs` | RustCrypto, release 2026-08 | none |
| `cbc` | 0.2.1 | MIT/Apache-2.0 | CBC mode + PKCS#7 unpadding | `kdbx/crypto.rs` | RustCrypto, 2026-05 | none |
| `chacha20` | 0.10.2 | MIT/Apache-2.0 | Payload cipher, protected-value stream | `kdbx/crypto.rs` | RustCrypto, 2026-08 | RUSTSEC-2019-0029 (fixed in 0.2.3) |
| `argon2` | 0.6.0 | MIT/Apache-2.0 | Argon2d/Argon2id KDF | `kdbx/crypto.rs` | RustCrypto, 2026-08 | none |
| `sha2` | 0.11.0 | MIT/Apache-2.0 | SHA-256/SHA-512 key schedule, key-file hashing, vault fingerprint | `kdbx/crypto.rs`, `credentials.rs`, `key_file.rs`, `session/mod.rs` | RustCrypto, 2026-03 | RUSTSEC-2021-0100 (fixed in 0.9.8) |
| `hmac` | 0.13.0 | MIT/Apache-2.0 | Header and block authentication, constant-time verify | `kdbx/crypto.rs` | RustCrypto, 2026-03 | none |
| `zeroize` | 1.9.0 | MIT/Apache-2.0 | Wiping keys, payloads, secrets | everywhere | RustCrypto, 2026-06 | none |
| `flate2` | 1.1.10 | MIT/Apache-2.0 | gzip inflate and, since M9, deflate on save (pure-Rust `miniz_oxide` backend only) | `kdbx/mod.rs`, `kdbx/writer.rs` | rust-lang, 2026-08 | none |
| `roxmltree` | 0.21.1 | MIT/Apache-2.0 | Read-only XML DOM, DTD disabled, node limit | `kdbx/xml.rs`, `key_file.rs` | 2025-10 | none |
| `getrandom` | 0.4.3 | MIT/Apache-2.0 | OS CSPRNG: session handles; since M9 the master seed, IVs, inner-stream key, new UUIDs and the password generator (`wasm_js` backend = `crypto.getRandomValues` in the WASM crate only) | `session/store.rs`, `kdbx/writer.rs`, `kdbx/edit.rs`, `generator.rs` | rust-random, 2026-06 | none |
| `psl` | 2.1.238 | MIT/Apache-2.0 | Compiled-in Mozilla Public Suffix List for AutoFill origin matching (M8); not used for crypto | `origin.rs` | addr-rs, 2026-09 | none |
| `ed25519-dalek` | 3.0.0 | BSD-3-Clause | Verification of Keep+ lease signatures (Ed25519, `verify_strict`); verify-only, default features off | `entitlement.rs` | dalek-cryptography, 2026 | none checked on 2026-10-05 |
| `serde`, `serde_json` | 1.x | MIT/Apache-2.0 | Parsing the lease header and payload *after* the signature has been checked | `entitlement.rs` | serde-rs | none |
| `base64` | 0.23.1 | MIT/Apache-2.0 | Protected values, UUIDs, XML v1 key files (SIMD feature disabled) | `kdbx/xml.rs`, `key_file.rs` | 2026-08 | RUSTSEC-2017-0004 (fixed long ago) |

`keep-core-wasm` additionally uses `js-sys` 0.3.106 (pinned with `wasm-bindgen` =0.2.129) for
`Uint8Array`/`JSON`, and `serde`/`serde_json` to build the JSON summaries.

Enabled features are deliberately narrow:

- `argon2`: `alloc` and `zeroize`, but no `password-hash`/`getrandom`.
- `sha2`: no `oid`.
- `aes`, `chacha20`, `cbc`, `hmac`: `zeroize`.
- `flate2`: `rust_backend` only (no C zlib).

**Considered and not used:**

- `keepass` 0.15.0 (see ADR 0005);
- `quick-xml` 0.42: an event parser with more code to own. It had two 2026 DoS advisories (RUSTSEC-2026-0194/0195), both fixed in 0.41;
- `secrecy`: our `SecretString`/`SecretBytes` cover the need with only `zeroize`.

## Android (M5)

No cryptographic library was added on Android. Biometric unlock uses only the platform:

| Component | Version | Why |
| --- | --- | --- |
| Android Keystore (`AndroidKeyStore` provider) + JCA `AES/GCM/NoPadding` | platform | Non-exportable, biometric-bound AES-256 key; encryption of the unlock material |
| `androidx.biometric:biometric` | 1.1.0 (latest stable; 1.2–1.4 are alpha only) | `BiometricPrompt` with `CryptoObject`, `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` |
| `androidx.fragment` 1.9.1, `androidx.appcompat` 1.8.0 | pinned explicitly | `FragmentActivity` host for `BiometricPrompt`; AppCompat theme for its pre-API-28 dialog (biometric 1.1.0 would otherwise pull fragment 1.2.5 / appcompat 1.2.0) |

Last reviewed: 2026-10-01.

## Review rules

- Add a crate only with a row in this table and a reason in the PR.
- Bump crypto crates in their own PR. Run `scripts/check.sh all` and the full vector suite.
- Before a release, run `cargo audit` (or `cargo deny check advisories`) on `core/Cargo.lock`.
