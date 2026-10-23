# Tooling

Configuration and generators shared by more than one build. Nothing here is a build system of its own.

| Path | Used by |
| --- | --- |
| `gradle/libs.versions.toml` | `apps/android` and `apps/desktop`: one version catalog for all Kotlin code |
| `gradle/build-logic` | Included build with the `app.vazie.keep.rust-core` plugin and `CargoTask`, which run Cargo from Gradle (cross-platform, configuration-cache safe) |
| `test-vectors/generate_kdbx4.py` | Regenerates `shared/test-vectors/kdbx4` with pykeepass (dev-only, see that README) |
