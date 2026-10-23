# Getting started

Everything here works from a terminal and Zed. IDEs are optional.

## 1. Install tools

| Tool | Install |
| --- | --- |
| rustup | <https://rustup.rs>. The toolchain pinned in `core/rust-toolchain.toml` installs on first use. |
| JDK 21 | Temurin or any JDK 21 (`/usr/libexec/java_home -v 21` on macOS) |
| Android SDK + NDK 28.2 | Android Studio's SDK manager or `sdkmanager "ndk;28.2.13676358"`. Set `ANDROID_HOME`. |
| Xcode + XcodeGen (macOS) | App Store; `brew install xcodegen` |
| Node 22+ | nodejs.org or a version manager; pnpm comes from `corepack` |

Windows: the desktop app, core and extension build natively with `scripts/check.ps1`. Android also
builds on Windows through Gradle (it runs `cargo ndk` itself). Apple needs macOS.

## 2. Bootstrap

```bash
scripts/bootstrap.sh
```

This installs Rust targets, `wasm-bindgen-cli` and `cargo-ndk`. It also writes
`apps/android/local.properties`, builds the Apple XCFramework, generates the Xcode project, builds
the WASM package and installs the extension's npm dependencies.

## 3. Check everything

```bash
scripts/check.sh all
```

## 4. Run a client

| Client | Command |
| --- | --- |
| Desktop | `cd apps/desktop && ./gradlew :app:run` |
| Android (device over USB) | `cd apps/android && ./gradlew :app:installDebug` |
| iOS / macOS | `cd apps/apple && xcodegen generate && open VazieKeep.xcodeproj`, or build with `xcodebuild` (see BUILDING.md) |
| Extension | `cd apps/browser-extension && pnpm build`, then load `dist/chromium` unpacked at `chrome://extensions` |

Each client shows:

```
Vazie Keep

Core: connected (0.1.0)
Platform: <platform>
```

The extension shows `Vault locked` instead of the platform. "connected" appears only after a
real call into the Rust core has succeeded.

## Zed

Open the repository root. `.zed/settings.json` points rust-analyzer at `core/Cargo.toml`, runs
clippy on save and formats TypeScript with the extension's Prettier. `.zed/tasks.json` has
tasks for every `scripts/check.sh` area plus "run: desktop" and "android: install debug".

For Kotlin, install Zed's Kotlin extension. For Swift, the Swift extension with `sourcekit-lsp`
works in `KeepAppleKit`. Run `scripts/build-core.sh apple` first so the bindings exist.
