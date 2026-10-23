# Kotlin core bridge (shared sources)

The hand-written Kotlin facade over the UniFFI bindings (`KeepCore`, `KeepVaultSession`,
`SecretValue`) and its tests. It is **not a Gradle build**. `apps/android/core-bridge` and
`apps/desktop/core-bridge` both add these directories as extra source roots next to the Kotlin
bindings each of them generates.

The code may use only the Kotlin stdlib, `kotlinx-coroutines-core`, `java.*` and the generated
package `app.vazie.keep.core.ffi`. Android- or desktop-specific code stays in the owning
module.
