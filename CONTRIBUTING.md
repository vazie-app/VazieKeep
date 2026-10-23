# Contributing

Thanks for helping with Vazie Keep. The project stores people's only copy of their passwords, so
the bar for correctness and restraint is high.

## Licence

Contributions are accepted under the licence of the project, the [PolyForm Shield License 1.0.0](LICENSE). Send a pull
request only for work you may license that way.

## Ground rules

1. **Never commit real vaults, passwords, keys or personal data**: not in fixtures, tests, logs,
   screenshots or issue reports.
2. **Vault logic goes in `core/`.** A KDBX, crypto or merge change in a platform app will be
   asked to move.
3. **No telemetry, analytics or new network access** without an ADR.
4. **Justify new modules and dependencies.** Each crate, Gradle module, Swift target or npm
   package needs a reason in the PR description. Prefer the platform standard library.
5. **Code speaks for itself.** Keep comments for non-obvious constraints, such as a platform
   quirk or the security reason a line exists. Put rationale in the PR or an ADR.

## Workflow

```bash
scripts/bootstrap.sh
```

```bash
scripts/check.sh <area>
```

Areas: `core`, `android`, `apple`, `desktop`, `extension`, or `all`. CI runs the same steps per
area (see `.github/workflows`).

- Branch from `main`. Use [Conventional Commits](https://www.conventionalcommits.org)
  (`feat(core): …`, `fix(android): …`, `docs: …`).
- A change to the FFI contract (`core/keep-core-ffi`) must build every client that consumes it.
  Run `scripts/check.sh all` or rely on CI.
- Architecture changes get an ADR in `docs/adr` (copy the format of an existing one and use the
  next number).

## Editors

Zed is the primary editor and `.zed/` ships project settings and tasks. Every build runs from the
terminal (Cargo, Gradle, xcodebuild, pnpm). Android Studio and Xcode are optional, for device
debugging, layout inspection and signing.

## Formatting

| Area | Tool |
| --- | --- |
| Rust | `cargo fmt`, `cargo clippy -D warnings` (pedantic) |
| Kotlin | official Kotlin style (`kotlin.code.style=official`) |
| Swift | Xcode default, 4 spaces |
| TypeScript | Prettier + ESLint (`strictTypeChecked`) |

`.editorconfig` covers the rest.
