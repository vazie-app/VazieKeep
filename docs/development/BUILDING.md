# Building

Native tools are the source of truth. The scripts only chain them.

## Core

```bash
cd core && cargo build --workspace
```

`scripts/build-core.sh` produces artifacts for clients that are not built by Gradle:

| Target | Output |
| --- | --- |
| `apple` | `apps/apple/KeepAppleKit/Generated/KeepCoreFFI.xcframework` (ios-arm64, macos-arm64) + `Sources/KeepCoreBindings/KeepCoreBindings.swift` |
| `wasm` | `core/keep-core-wasm/pkg/` (`--target web`) |
| `host` | `core/target/<profile>/libkeep_core_ffi.*` |

`KEEP_CORE_PROFILE=debug` skips optimisation.

## Android

```bash
cd apps/android && ./gradlew assembleDebug
```

`:core-bridge` registers, per variant:

- `cargoNdkBuild<Variant>`: `cargo ndk -t <abis> -P 26 build -p keep-core-ffi`, output wired in
  as `jniLibs`
- `generateUniffiKotlin<Variant>`: `uniffi-bindgen generate --library …so --language kotlin`,
  output wired in as Kotlin sources

ABIs come from `keep.rust.abis` in `gradle.properties` (default `arm64-v8a,armeabi-v7a,x86_64`: phones,
older 32-bit phones, ChromeOS/emulators), and the app's `abiFilters` use the same list. For a faster build for a
phone only, pass `./gradlew installDebug -Pkeep.rust.abis=arm64-v8a`.

The NDK version is pinned in `tooling/gradle/libs.versions.toml` (`android-ndk`) and passed to
cargo-ndk as `ANDROID_NDK_HOME`. `CARGO` overrides the cargo binary (default `~/.cargo/bin/cargo`,
then `PATH`).

## Desktop (Windows, Linux)

```bash
cd apps/desktop && ./gradlew build
```

`:core-bridge` builds `keep-core-ffi` for the host and copies the library into JNA's resource
layout (`win32-x86-64/keep_core_ffi.dll`, `linux-x86-64/libkeep_core_ffi.so`, …). It then generates
Kotlin bindings from it. `-Pkeep.rust.profile=release` builds an optimised core.

Installers are built on the matching OS:

```bash
./gradlew :app:packageDistributionForCurrentOS
```

It produces an MSI on Windows, and DEB and RPM on Linux.

## Backend addresses

The addresses of the Vazie servers are not in git. A release build reads `VAZIE_ACCOUNT_ORIGIN`, `VAZIE_KEEP_ORIGIN` and
`VAZIE_SITE_ORIGIN` from the environment (CI: secrets of the `release` environment) or, on Android and in the
extension, from a git-ignored `local.properties`; iPhone takes them from the environment when `xcodegen generate` runs.
Android refuses to assemble a release without them. Debug and development builds use a backend on this computer
(`VAZIE_DEV_*` overrides it on Android).

## Apple

```bash
scripts/build-core.sh apple
```

```bash
cd apps/apple && xcodegen generate
```

```bash
xcodebuild -project VazieKeep.xcodeproj -scheme VazieKeep-iOS -destination generic/platform=iOS CODE_SIGNING_ALLOWED=NO build
```

```bash
xcodebuild -project VazieKeep.xcodeproj -scheme VazieKeep-macOS -destination platform=macOS,arch=arm64 CODE_SIGNING_ALLOWED=NO build
```

The XCFramework contains device slices only (`ios-arm64`, `macos-arm64`). To run in the Simulator,
add `aarch64-apple-ios-sim` to `build_apple` in `scripts/build-core.sh`. To install on a device,
set your team in Xcode (never commit it).

## Browser extension

```bash
scripts/build-core.sh wasm
```

```bash
cd apps/browser-extension && pnpm install && pnpm build
```

`pnpm build` writes the unpacked extension to `apps/browser-extension/dist/chromium`: open `chrome://extensions`
(or `edge://extensions`, `brave://extensions`), turn on Developer mode, press **Load unpacked** and pick that folder.
`pnpm package` packs it into `dist/vazie-keep-<version>-chromium.zip` with the licence texts, for a store or a sideload.
`pnpm dev` makes a development build (sourcemaps, inspectable page overlays, and the QA lease key named in
`KEEP_QA_LEASE_KEYS` as `kid:publicKey`, so Keep+ can be tried with a lease signed by a throwaway key); reload the
extension after each build. `KEEP_BROWSER_TARGET` selects the manifest target (only `chromium` today). The Rust
crates compiled into the WebAssembly are listed in `apps/browser-extension/wasm-crates.txt`
(`apps/browser-extension/scripts/list-wasm-crates.sh` regenerates it); `pnpm package` fails if one has no licence folder in
`third-party/rust-licenses`.
