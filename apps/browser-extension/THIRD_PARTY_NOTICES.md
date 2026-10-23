# Third-party notices: browser extension

What the Vazie Keep browser extension carries from other people. Vazie Keep itself is under the MIT License
([LICENSE](../../LICENSE)). Checked on 18 October 2026 against `package.json`, `pnpm-lock.yaml` and `core/Cargo.lock`.
The zip made by `pnpm run package` carries the licence texts listed here in its `licenses/` folder.

| Component                                               | What for                                                                                           | Licence                                                                                                                      |
| ------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Preact 10                                               | the interface                                                                                      | MIT (`licenses/preact-LICENSE`)                                                                                              |
| Geologica, Martian Mono                                 | the typefaces, bundled as files                                                                    | SIL OFL 1.1 (`licenses/OFL-*.txt`)                                                                                           |
| The Rust core, compiled to WebAssembly                  | everything that touches vault data                                                                 | MIT, with the crates below                                                                                                   |
| The Rust crates in [`wasm-crates.txt`](wasm-crates.txt) | RustCrypto primitives, Argon2, flate2, serde, wasm-bindgen and the rest of the core's dependencies | MIT, Apache-2.0 or both (the MIT or Apache-2.0 option is taken wherever there is a choice); `unicode-ident` also Unicode-3.0 |

Everything else in `package.json` (Vite, TypeScript, ESLint, Prettier and their dependencies) is a build or lint tool and
is not shipped. The licence texts of the Rust crates are in [`third-party/rust-licenses`](../../third-party/rust-licenses),
one folder per crate and version; `scripts/list-wasm-crates.sh` regenerates the list when the core's dependencies change,
and `pnpm run package` stops if a crate has no folder there.

The fonts are the same files the phone apps use ([`shared/fonts`](../../shared/fonts)); the OFL permits bundling them
with any software, and they stay under the OFL.
