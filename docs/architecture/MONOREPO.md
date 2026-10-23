# Monorepo layout

Each directory exists for a stated reason. If a directory has no reason, it isn't here yet.

## Top level

| Path | Why it exists |
| --- | --- |
| `core/` | The single Cargo workspace. Rust-only, so `cargo` commands never touch app code. |
| `apps/` | One directory per native build system. Each opens and builds on its own. |
| `shared/` | Language-neutral data used by more than one layer: test vectors and fixtures. |
| `tooling/` | Configuration shared by several builds (Gradle version catalog, Cargo build logic). |
| `scripts/` | Thin orchestration over native tools. It never replaces them. |
| `docs/` | Architecture, ADRs, security model, development guides. |

There is **no root build system**: no root `package.json`, no root Gradle build, no root
`Cargo.toml`. Every native tool runs in its own directory. Cross-cutting runs go through
`scripts/check.sh`.

## `core/`

| Crate | Reason |
| --- | --- |
| `keep-core` | Pure domain library. `#![forbid(unsafe_code)]`, no platform dependencies. |
| `keep-core-ffi` | UniFFI boundary for Kotlin and Swift: DTOs and exported functions only. Also hosts the `uniffi-bindgen` binary (feature `bindgen`), which pins the generator to the runtime version. |
| `keep-core-wasm` | wasm-bindgen boundary for the extension. It is a separate crate because WASM exports differ from UniFFI exports: JS naming, no JNA/C ABI, and a size-optimised profile. |

`rust-toolchain.toml` pins the compiler, so every machine and CI runner builds identical artifacts.

## `apps/android/`

Gradle build with two modules:

- `app`: Compose UI, ViewModels, navigation, manual DI (`AppContainer`) and Android platform
  adapters.
- `core-bridge`: runs `cargo ndk` and `uniffi-bindgen` as Gradle tasks, packages
  `libkeep_core_ffi.so` and JNA, and exposes the `KeepCore` facade.

The suggested `core-ui`, `core-platform` and `feature-vault` modules would be empty today, so they
don't exist yet. Split `feature-*` modules out of `app` when the first real feature needs its own
tests or build variant. `core-bridge` is separate from the start: it isolates generated code and
native build tasks from UI compilation.

## `apps/apple/`

One XcodeGen project (`project.yml`) with two app targets:

- `VazieKeep-iOS`, bundle id `app.vazie.keep`
- `VazieKeep-macOS`, bundle id `app.vazie.keep.macos` (see ADR 0004)

Shared code lives in the local Swift package `KeepAppleKit`:

| Target | Reason |
| --- | --- |
| `KeepCoreFFI` | Binary target: the Rust static library as an XCFramework (generated) |
| `KeepCoreBindings` | UniFFI-generated Swift (generated, Swift 5 mode) |
| `KeepCore` | Hand-written facade: app-level types, no generated types in its API |
| `KeepPlatform` | Apple platform adapters (today `PlatformInfo`; next Keychain, pasteboard, LocalAuthentication) |
| `KeepUI` | SwiftUI views shared by both apps |

The `.xcodeproj` is generated and git-ignored, so the project definition stays reviewable text.
`swift test` in `KeepAppleKit` runs on the macOS host with no simulator or signing.

## `apps/desktop/`

Gradle build with `app` (Compose Desktop) and `core-bridge` (JVM, JNA). Windows and Linux are
**not** separate modules: Compose Desktop produces one JVM app that is packaged per OS through
`nativeDistributions` (MSI; DEB and RPM). OS differences are runtime adapters
(`DesktopPlatform`), not forks of the UI. Separate `windows/` and `linux/` modules would hold
only a `main()` each, and packaging still happens on the matching OS runner.

The app also runs on macOS for development and says so on screen. The shipped macOS client is
the native SwiftUI app.

`apps/android` and `apps/desktop` are two Gradle builds, not one. Configuring Android modules needs
an Android SDK, and desktop CI on Windows should not. They share `tooling/gradle/libs.versions.toml`
and the `tooling/gradle/build-logic` plugin (`CargoTask`). Since M2 the hand-written Kotlin facade
is no longer duplicated: it lives once in `apps/kotlin-core-bridge/src` (main + tests), and both
`core-bridge` modules add it as an extra source root. It is not a Gradle build of its own. When
shared Kotlin presentation logic appears, promote it to a KMP module included by both builds.

## `apps/browser-extension/`

A standalone pnpm package (it is not a workspace). `src/` holds only the layers that exist today:

| Dir | Reason |
| --- | --- |
| `background/` | MV3 service worker: session and lock state, the future auto-lock timer |
| `popup/` | Toolbar UI (Preact) |
| `core/` | The only place that imports the WASM package |
| `platform/` | `chrome.*` wrappers, the seam for a later Firefox build |
| `shared/` | Typed message protocol between contexts |

Content scripts and the options page are added with autofill and settings.

## Dependency direction

```
apps/*  ──►  core/keep-core-ffi | core/keep-core-wasm  ──►  core/keep-core
apps/*  ──►  tooling/gradle (Kotlin builds only)
core/keep-core (tests) ──► shared/test-vectors
```

Nothing points back up. `scripts/build-core.sh` is the only place that knows both a core output
path and an app input path.
