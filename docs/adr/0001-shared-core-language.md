# ADR 0001: Rust for the shared core

- Status: Accepted
- Date: 2026-09-30

## Context

Vazie Keep ships on Android, iOS, macOS, Windows, Linux and Chromium browsers. Vault logic
(KDBX 3.1/4.x parsing and serialization, AES-256, ChaCha20, Argon2d/id, AES-KDF, HMAC block
streams, protected fields, the group/entry tree, attachments, search, generation, merge) is
security-critical and subtle. KeePass clients regularly diverge on edge cases such as
whitespace, custom data, history and timestamps.

A separate implementation per platform would multiply that surface by six and guarantee drift.
We need one implementation that runs natively on every target and in the browser.

## Decision

Write the core in **Rust**:

- `keep-core` is the pure library.
- `keep-core-ffi` exports it to Kotlin and Swift through **UniFFI**.
- `keep-core-wasm` exports it to the extension through **wasm-bindgen**.

## Why Rust

- **One codebase for every target, including the browser.** Android (NDK), iOS/macOS (static
  XCFramework), Windows/Linux (cdylib through JNA) and `wasm32-unknown-unknown` are all tier-1 or
  tier-2 Rust targets with mature tooling.
- **Cryptography ecosystem.** RustCrypto (`aes`, `chacha20`, `argon2`, `hmac`, `sha2`) is widely
  reviewed, constant-time where it matters, and works unchanged on native and WASM.
  `keepass-rs` gives prior art for KDBX in Rust.
- **Memory control.** No GC: the core decides when key material is overwritten (`zeroize`), and
  copies are not left behind by a moving collector. Managed runtimes cannot promise this.
- **Performance where it counts.** Argon2 with KeePass defaults runs natively on low-end phones
  instead of in a JIT or interpreter.
- **Safety.** `keep-core` forbids `unsafe`, and memory-safety bugs in a parser for untrusted
  files are ruled out by construction.

## Why not Kotlin Multiplatform for the whole core

KMP was a serious candidate, and an earlier spike ported the `kotpass` KDBX library to KMP
successfully on JVM and Apple native. It was not chosen because:

- **The browser is a first-class target.** Kotlin/Wasm and Kotlin/JS are weaker targets for
  cryptography and binary size than Rust→WASM, and a crypto library would need a third backend.
- **There are no shared crypto primitives.** KMP has no common, audited implementation of
  Argon2, ChaCha20 and AES. Each target needs its own backend (javax.crypto, CommonCrypto, WebCrypto
  or a port), and those are exactly the per-platform differences we want to remove.
- **Memory.** On JVM/ART the collector can copy secrets and there is no reliable zeroing. Native
  Kotlin still carries a runtime with its own GC.
- **Swift ergonomics.** A KMP framework exposes Objective-C–bridged types to Swift. UniFFI
  generates idiomatic Swift enums, structs and errors.

Kotlin remains the language of the Android and desktop UIs.

## Why not independent Swift and Kotlin implementations

That means two or more KDBX engines, two sets of crypto integrations, two sets of bugs and a
guaranteed compatibility drift between the user's phone and laptop. The shared test vectors
would catch drift, but they cannot prevent it.

## Risks introduced by Rust + FFI

| Risk | Mitigation |
| --- | --- |
| FFI boundary bugs: memory ownership, panics across the boundary | Generated glue only (UniFFI, wasm-bindgen), no hand-written `extern "C"`. UniFFI turns panics into exceptions. Integration tests call the real library from Kotlin (desktop) and Swift (`swift test`). |
| Secrets copied into managed memory at the boundary | Coarse API, short-lived byte arrays, zeroization on the Rust side. The residual risk is documented in SECURITY_MODEL. |
| Build complexity: NDK, XCFramework, JNA, WASM toolchains | Pinned toolchain, Gradle tasks and `scripts/build-core.sh`. CI builds every consumer on every core change. |
| Binary size (a `.so` per ABI, JNA ~1 MB) | Release profile uses LTO, `codegen-units=1` and strip. Android ships only the ABIs we build. |
| UniFFI version churn | Generator and runtime pinned together (the `uniffi-bindgen` binary lives in `keep-core-ffi`) |
| Contributor skill set | Rust is confined to `core/`. App contributors never need to touch it. |
| Debugging across languages | Every behaviour is testable in pure Rust first, then through vectors on each binding |

## Consequences

- All vault logic PRs land in `core/` with Rust tests and, where relevant, shared vectors.
- Every client build requires the Rust toolchain.
- The FFI contract (`keep-core-ffi`) is versioned together with all clients in this repository.
