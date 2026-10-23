# Testing

```bash
scripts/test.sh all
```

```bash
scripts/check.sh all
```

`scripts/test.sh` runs tests only. `scripts/check.sh` also runs formatting, lint and builds.

## What is tested where

| Layer | Tests | Runs in |
| --- | --- | --- |
| `keep-core` | Unit tests per parser module; `tests/format_vectors.rs` and `tests/kdbx4_vectors.rs` over `shared/test-vectors` | `cargo test` |
| `keep-core-ffi`, `keep-core-wasm` | DTO mapping, version passthrough | `cargo test` |
| `keep-core` sessions | `tests/sessions.rs`: lifecycle, closed and forged handles, independent sessions, secret isolation, leak marker, 8-thread readers, close during read, 300 open/close cycles, session cap | `cargo test` |
| `keep-core-ffi` | handle lifecycle, `Drop` closes, invalid ids, nothing formatted contains the marker | `cargo test` |
| Kotlin bridge (shared) | `apps/kotlin-core-bridge/src/test`: `RustKeepVaultSessionTest`, run by **both** builds against the real Rust library through JNA | `apps/desktop ./gradlew test`, `apps/android ./gradlew testDebugUnitTest` (JVM host, Android-generated bindings) |
| Swift bindings | `RustKeepCoreTests`, `VaultSessionTests` (real Rust static library; checks `dump()` and `description` for the marker) | `swift test` in `KeepAppleKit` |
| Android app | `StatusViewModelTest` with a fake `KeepCore` (JVM, no device) | `./gradlew testDebugUnitTest` |
| Desktop app | `DesktopPlatformTest` | `./gradlew test` |
| Extension | `tests/*.test.ts` (Node's built-in runner, real WASM package): the session contract (`vault-session`), the whole app contract through the facade (`core`: create, edit, save, reopen, history, attachments, one-time codes against RFC 6238, import, passkeys verified with the public key, quick-unlock material, generator, leases and the clock rule), site matching and save planning (`autofill`), vault-list rules (`rules`), backend parsing and settings hygiene (`remote`), and that every string exists in both languages (`strings`); plus `tsc` and strict ESLint | `pnpm run check` |
| Extension, real browser | `apps/browser-extension/tests/e2e`: loads the built extension into Chrome for Testing and drives the interface, test pages (autofill, saving, generator, one-time codes, passkeys with signature checks, conditional sign-in), quick unlock with a virtual PRF authenticator, auto-lock and the clipboard | `node tests/e2e/run.mjs` (see its README) |

Android instrumented tests and on-device checks are manual for now. Use a physical device with
`./gradlew connectedDebugAndroidTest` once instrumented tests exist.

## Shared test vectors

`shared/test-vectors/<suite>/vectors.json` defines inputs and expected outputs. Rules:

1. A behaviour change in the core starts with a new or changed vector.
2. The core runs every suite in full. Each binding opens at least one vault from the same
   files (`kdbx4/aes256-argon2d.kdbx`) to prove the boundary. Full per-binding runners over
   `vectors.json` come when bindings gain their own logic.
3. Fuzz targets for the parser live in `core/fuzz` (nightly + cargo-fuzz, not in CI yet).
4. Vectors and fixtures are synthetic. See `shared/test-vectors/README.md`.

## Principles

- Test logic in Rust first. Binding tests only prove that the boundary maps correctly.
- UI state holders depend on the `KeepCore` interface, so they are tested with fakes and no
  native code.
- Never use a real vault in a test.

## Design checks (M12)

- **Android gallery.** `DesignGalleryTest` renders the Autofill sheets and the component kit in both
  themes with `captureToImage` (it works despite `FLAG_SECURE`) and writes PNGs to the app's
  `files/gallery`. Run it without the usual uninstall:
  `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, install both APKs, then
  `adb shell am instrument -w -e class app.vazie.keep.DesignGalleryTest app.vazie.keep.test/androidx.test.runner.AndroidJUnitRunner`
  and `adb exec-out run-as app.vazie.keep.debug cat files/gallery/<name>.png`.
- **Android screens by hand.** Screenshots of the real screens need Settings → Allow screenshots.
- **iPhone tour.** `DesignTourTests` (`testLightTour`, `testDarkTour`) walks the welcome, unlock,
  vault, group, entry, editor, generator and settings screens and attaches a screenshot of each. It
  needs `qa/rich.kdbx` in the app's Documents, the iPhone unlocked, and UI automation approved on
  the device. Debug builds accept `-VazieQATheme light|dark`.
