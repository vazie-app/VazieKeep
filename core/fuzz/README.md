# keep-core fuzz targets

cargo-fuzz targets for the KDBX parser. Not part of the workspace or CI yet; they need nightly.

| Target | Input |
| --- | --- |
| `outer_header` | Raw bytes into the outer header / KDF parameter parser |
| `inner_payload` | Decrypted, decompressed payload: inner header + XML (the part the HMAC normally guards) |
| `open_vault` | Whole-file path with a fixed password and tiny KDF limits |
| `session_inputs` | Handle strings, entry ids and field keys as they arrive from JS/FFI: parse, round-trip, look up in an empty store |
| `key_file` | Any file a user picks as a key file: XML detection, v1/v2 parsing, hex, hashing |

```bash
cargo install cargo-fuzz
cd core/fuzz && cargo +nightly fuzz run inner_payload
```

Seed corpora: copy files from `shared/test-vectors/kdbx4/` into `corpus/open_vault/`.
`inner_payload` is the highest-value target because an attacker who controls a vault file
controls this input once the password is known.

## Smoke runs

| Date | Toolchain | Target | Time | Executions | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-09-30 | nightly (cargo-fuzz), seed corpus = `shared/test-vectors/kdbx4` | `outer_header` | 60 s | 5.59 M | no crash |
| | | `open_vault` | 60 s | 5.27 M | no crash |
| | | `inner_payload` | 60 s | 3.63 M | no crash |
| | | `session_inputs` | 60 s | 5.97 M | no crash |
| 2026-10-01 | nightly (cargo-fuzz), seed corpus = `shared/test-vectors/keyfiles` key files | `key_file` | 60 s | 2.47 M | no crash |

These are smoke runs, not a fuzzing campaign: they prove the targets build and run under
libFuzzer, not that the parser is free of bugs.
