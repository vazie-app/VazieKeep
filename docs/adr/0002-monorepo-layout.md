# ADR 0002: Monorepo with per-toolchain builds

- Status: Accepted
- Date: 2026-09-30

## Context

Six clients and one core share a contract (`keep-core-ffi`) that changes often in early
development. Separate repositories would turn every contract change into several coordinated
releases.

The native toolchains are also very different: Cargo, Gradle (Android and JVM), Xcode/SwiftPM
and pnpm/Vite.

## Decision

One repository. **Each toolchain keeps its own build root** and there is no umbrella build system:

- `core/`: Cargo workspace (three crates).
- `apps/android/`: Gradle build.
- `apps/desktop/`: a separate Gradle build.
- `apps/apple/`: XcodeGen project plus a local Swift package `KeepAppleKit`.
- `apps/browser-extension/`: a standalone pnpm package. There is no root `package.json` or
  `pnpm-workspace.yaml`.
- `tooling/gradle/`: a version catalog and build-logic plugin shared by the two Gradle builds.
- `shared/`: test vectors and fixtures.
- `scripts/`: thin wrappers that run each native tool in order.

Generated artifacts (bindings, native libraries, XCFramework, WASM package) are produced by the
consuming build and never committed.

## Consequences

- An FFI change and every client update land in one PR. CI path filters rebuild every consumer
  when `core/**` changes.
- Each area can be opened alone in its IDE and built with its native command.
- The Android and desktop builds are separate, so desktop CI (Windows and Linux) needs no Android
  SDK. The shared Kotlin facade lives as a plain source tree (`apps/kotlin-core-bridge`) that
  both `core-bridge` modules compile. Since M2 it is no longer duplicated. It becomes a KMP
  module once shared Kotlin presentation code justifies one.
- The Xcode project is generated, so XcodeGen is a required tool on macOS. In exchange, the
  project definition is reviewable text without `.pbxproj` merge conflicts.
- CI runs per area. There is no single "build everything" job, which keeps macOS runner minutes
  for Apple changes only.

## Alternatives considered

- **Polyrepo (core + one repo per client).** Clean ownership, but every contract change needs
  published core artifacts and version bumps across repos. Too slow before the contract
  stabilises.
- **Root Gradle build containing Android and desktop.** One catalog and shared modules for free,
  but configuring it requires an Android SDK everywhere, and Gradle ends up owning a
  Rust/Swift/TS repo.
- **Separate `apps/desktop/windows` and `apps/desktop/linux` modules.** Each would contain only a
  `main()`. Compose Desktop already packages per OS, and differences are runtime adapters.
- **Nx/Turborepo/Bazel.** A strong fit for large polyglot orgs, but here it would be a
  homemade build system on top of five native ones.
- **Separate Xcode projects for iOS and macOS.** More files, no benefit: targets already separate
  platform code, and shared code is a Swift package.
