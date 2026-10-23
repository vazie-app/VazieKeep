# Third-party notices

What the Vazie Keep **Android app** carries from other people, under which licences, and how each licence is met.
Vazie Keep itself is under the PolyForm Shield License 1.0.0 ([LICENSE](LICENSE)), which is not one of the licences
below. The names and the icon are a separate matter:
[TRADEMARKS.md](TRADEMARKS.md).

Every entry below was read from the release build: the Android libraries from Gradle's `releaseRuntimeClasspath` of
`:app`, the Rust crates from `cargo tree -p keep-core-ffi -e normal,no-proc-macro` for the three Android targets the
APK carries (`arm64-v8a`, `armeabi-v7a`, `x86_64`), the fonts from `app/src/main/res/font/`. Checked on
17 October 2026 against `tooling/gradle/libs.versions.toml` and `core/Cargo.lock` at that date.

The iPhone app, the desktop app and the browser extension are not covered here: each lists its own components when it
is released.

## Compatibility with the PolyForm Shield License

| Licence | Components | With Vazie Keep's licence |
|---|---|---|
| **MIT, BSD-3-Clause** | most Rust crates (MIT alone or as one option of a dual or triple licence; `curve25519-dalek`, `ed25519-dalek` and `subtle` are BSD-3-Clause), Checker Framework qualifiers, the protobuf runtime inside DataStore | Allowed; their copyright notices and texts travel with the APK in `third-party/` |
| **Apache-2.0** | AndroidX, Kotlin, kotlinx, Guava, Dagger, javax/jakarta.inject, Okio, ZXing, JSpecify, JetBrains annotations, Error Prone and J2ObjC annotations, AutoValue annotations, JSR-305; JNA and many Rust crates under their Apache-2.0 option | Allowed: the licence text and any NOTICE file go with the binary |
| **MPL-2.0** | UniFFI runtime (`uniffi`, `uniffi_core`, `uniffi_pipeline`), compiled into `libkeep_core_ffi.so` | Allowed: MPL-2.0 is file-level; it covers UniFFI's own files only, which are used unmodified |
| **SIL OFL 1.1** | Geologica and Martian Mono fonts | Allowed: the OFL permits bundling the fonts with any software; the fonts stay under the OFL |

Where a component offers a choice of licences (`MIT OR Apache-2.0`, and for a few crates also 0BSD, Zlib, Unlicense or
Apache-2.0 WITH LLVM-exception; JNA's `Apache-2.0 OR LGPL-2.1-or-later`), Vazie Keep takes the MIT or Apache-2.0 option
for the Rust crates and Apache-2.0 for JNA. Nothing in the APK is
under the GPL or the LGPL, and none of these licences reaches Vazie Keep's own code (MPL-2.0 covers only the files it
names).

What each licence asks of a binary distribution, and how it is met:

- **Apache-2.0**: the licence text and the component's NOTICE file. The text is
  [`third-party/android-licenses/Apache-2.0.txt`](third-party/android-licenses/Apache-2.0.txt); of the Android libraries
  only `jakarta.inject` has a NOTICE, packaged in the APK (`META-INF/NOTICE.md`) and copied to
  [`third-party/android-licenses/jakarta.inject-NOTICE.md`](third-party/android-licenses/jakarta.inject-NOTICE.md).
- **MIT, BSD-3-Clause**: the copyright notice and the licence text. Each Rust crate's own licence files are
  in [`third-party/rust-licenses/<crate>-<version>/`](third-party/rust-licenses); the Android ones are in
  [`third-party/android-licenses/`](third-party/android-licenses).
- **MPL-2.0** (§3.2): recipients of the binary are told where the source of the covered files is. UniFFI is used
  unmodified, exactly as published: <https://github.com/mozilla/uniffi-rs/tree/v0.32.2> (crates.io `uniffi` 0.32.2,
  pinned in `core/Cargo.lock`). The licence text: [`third-party/rust-licenses/uniffi-0.32.2/LICENSE`](third-party/rust-licenses/uniffi-0.32.2/LICENSE).
  The crates published to crates.io carry no licence file of their own, so the text is the one in the uniffi-rs
  repository at that tag.
- **OFL 1.1**: the copyright notice and the licence with every copy of the font. Both fonts carry them in their `name`
  table; the stand-alone texts are [`shared/fonts/OFL-Geologica.txt`](shared/fonts/OFL-Geologica.txt) and
  [`shared/fonts/OFL-MartianMono.txt`](shared/fonts/OFL-MartianMono.txt).

## Android libraries

Libraries in the release APK. AndroidX modules come from the versions in `tooling/gradle/libs.versions.toml` and the
Compose BOM `2026.09.00`, with what those pull in.

| Component | Upstream | Version | Licence |
|---|---|---|---|
| AndroidX: Activity, AppCompat, Autofill, Biometric, CameraX (camera2, lifecycle, view, viewfinder), Collection, Compose (runtime, UI, foundation, animation, Material, Material 3), Core, Credentials, DataStore, Emoji2, ExifInterface, Fragment, Graphics, Lifecycle, Media3 (common, container, muxer — pulled in by CameraX), Navigation, NavigationEvent, Profile Installer, SavedState, Startup, Tracing, Window and their transitive modules | [developer.android.com/jetpack/androidx](https://developer.android.com/jetpack/androidx) | per `libs.versions.toml` / Compose BOM | Apache-2.0 |
| Protocol Buffers (lite runtime, repackaged inside `androidx.datastore:datastore-preferences-external-protobuf`) | [protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf) | as shipped by DataStore `1.2.1` | BSD-3-Clause — [`protobuf-LICENSE.txt`](third-party/android-licenses/protobuf-LICENSE.txt) |
| Kotlin standard library | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | `2.4.20` | Apache-2.0 |
| kotlinx.coroutines | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | `1.11.0` | Apache-2.0 |
| kotlinx.serialization (core, JSON) | [Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | `1.7.3` | Apache-2.0 |
| kotlinx atomicfu | [Kotlin/kotlinx-atomicfu](https://github.com/Kotlin/kotlinx-atomicfu) | `0.28.0` | Apache-2.0 |
| JNA (Java Native Access) — loads the Rust core | [java-native-access/jna](https://github.com/java-native-access/jna) | `5.19.1` | Apache-2.0 (chosen of Apache-2.0 OR LGPL-2.1-or-later) — [`jna-LICENSE.txt`](third-party/android-licenses/jna-LICENSE.txt) |
| ZXing core — reads one-time-code QR codes | [zxing/zxing](https://github.com/zxing/zxing) | `3.5.4` | Apache-2.0 |
| Guava (with `failureaccess`) | [google/guava](https://github.com/google/guava) | `33.3.1-android` | Apache-2.0 |
| Dagger, javax.inject, jakarta.inject | [google/dagger](https://github.com/google/dagger), [jakartaee/inject](https://github.com/jakartaee/inject) | `2.59`, `1`, `2.0.1` | Apache-2.0 (jakarta.inject: with [NOTICE](third-party/android-licenses/jakarta.inject-NOTICE.md)) |
| Okio | [square/okio](https://github.com/square/okio) | `3.9.1` | Apache-2.0 |
| Annotations: JSpecify, JetBrains annotations, Error Prone annotations, J2ObjC annotations, AutoValue annotations, JSR-305 | [jspecify](https://github.com/jspecify/jspecify), [JetBrains/java-annotations](https://github.com/JetBrains/java-annotations), [google/error-prone](https://github.com/google/error-prone), [google/j2objc](https://github.com/google/j2objc), [google/auto](https://github.com/google/auto), [findbugs/jsr-305](https://code.google.com/archive/p/jsr-305/) | `1.0.0`, `23.0.0`, `2.28.0`, `3.0.0`, `1.6.3`, `3.0.2` | Apache-2.0 |
| Checker Framework qualifiers | [typetools/checker-framework](https://github.com/typetools/checker-framework) | `3.43.0` | MIT — [`checker-qual-LICENSE.txt`](third-party/android-licenses/checker-qual-LICENSE.txt) |

Test-only libraries (JUnit, kotlin-test, kotlinx-coroutines-test, AndroidX Test, Compose UI test, `org.json`) and
build tools are not shipped. `apps/android/gradlew`, `gradlew.bat` and `apps/desktop/gradlew` are Gradle's scripts,
Apache-2.0.

## The Rust core

`libkeep_core_ffi.so`, one per ABI, is Vazie Keep's own core (`core/keep-core`, `core/keep-core-ffi`, PolyForm Shield 1.0.0) compiled with
the crates below. Crates used only while compiling (procedural macros such as `serde_derive`, `thiserror-impl`,
`uniffi_macros`, and their dependencies `syn`, `quote`, `proc-macro2`, `toml`, `winnow`) leave no code in the library and
are not listed.

| Crate | Version | Licence | Licence files |
|---|---|---|---|
| [adler2](https://github.com/oyvindln/adler2) | `2.0.1` | 0BSD OR MIT OR Apache-2.0 | [adler2-2.0.1/](third-party/rust-licenses/adler2-2.0.1) |
| [aes](https://github.com/RustCrypto/block-ciphers) | `0.9.3` | MIT OR Apache-2.0 | [aes-0.9.3/](third-party/rust-licenses/aes-0.9.3) |
| [anyhow](https://github.com/dtolnay/anyhow) | `1.0.104` | MIT OR Apache-2.0 | [anyhow-1.0.104/](third-party/rust-licenses/anyhow-1.0.104) |
| [argon2](https://github.com/RustCrypto/password-hashes) | `0.6.0` | MIT OR Apache-2.0 | [argon2-0.6.0/](third-party/rust-licenses/argon2-0.6.0) |
| [base16ct](https://github.com/RustCrypto/formats) | `1.0.0` | Apache-2.0 OR MIT | [base16ct-1.0.0/](third-party/rust-licenses/base16ct-1.0.0) |
| [base64](https://github.com/marshallpierce/rust-base64) | `0.23.1` | MIT OR Apache-2.0 | [base64-0.23.1/](third-party/rust-licenses/base64-0.23.1) |
| [base64ct](https://github.com/RustCrypto/formats) | `1.8.3` | Apache-2.0 OR MIT | [base64ct-1.8.3/](third-party/rust-licenses/base64ct-1.8.3) |
| [bitflags](https://github.com/bitflags/bitflags) | `2.13.2` | MIT OR Apache-2.0 | [bitflags-2.13.2/](third-party/rust-licenses/bitflags-2.13.2) |
| [blake2](https://github.com/RustCrypto/hashes) | `0.11.0` | MIT OR Apache-2.0 | [blake2-0.11.0/](third-party/rust-licenses/blake2-0.11.0) |
| [block-buffer](https://github.com/RustCrypto/utils) | `0.12.1` | MIT OR Apache-2.0 | [block-buffer-0.12.1/](third-party/rust-licenses/block-buffer-0.12.1) |
| [block-padding](https://github.com/RustCrypto/utils) | `0.4.2` | MIT OR Apache-2.0 | [block-padding-0.4.2/](third-party/rust-licenses/block-padding-0.4.2) |
| [bytes](https://github.com/tokio-rs/bytes) | `1.12.1` | MIT | [bytes-1.12.1/](third-party/rust-licenses/bytes-1.12.1) |
| [camino](https://github.com/camino-rs/camino) | `1.2.6` | MIT OR Apache-2.0 | [camino-1.2.6/](third-party/rust-licenses/camino-1.2.6) |
| [cargo-platform](https://github.com/rust-lang/cargo) | `0.3.0` | MIT OR Apache-2.0 | [cargo-platform-0.3.0/](third-party/rust-licenses/cargo-platform-0.3.0) |
| [cargo_metadata](https://github.com/oli-obk/cargo_metadata) | `0.23.1` | MIT | [cargo_metadata-0.23.1/](third-party/rust-licenses/cargo_metadata-0.23.1) |
| [cbc](https://github.com/RustCrypto/block-modes) | `0.2.1` | MIT OR Apache-2.0 | [cbc-0.2.1/](third-party/rust-licenses/cbc-0.2.1) |
| [cfg-if](https://github.com/rust-lang/cfg-if) | `1.0.5` | MIT OR Apache-2.0 | [cfg-if-1.0.5/](third-party/rust-licenses/cfg-if-1.0.5) |
| [chacha20](https://github.com/RustCrypto/stream-ciphers) | `0.10.2` | MIT OR Apache-2.0 | [chacha20-0.10.2/](third-party/rust-licenses/chacha20-0.10.2) |
| [cipher](https://github.com/RustCrypto/traits) | `0.5.2` | MIT OR Apache-2.0 | [cipher-0.5.2/](third-party/rust-licenses/cipher-0.5.2) |
| [cmov](https://github.com/RustCrypto/utils) | `0.5.4` | Apache-2.0 OR MIT | [cmov-0.5.4/](third-party/rust-licenses/cmov-0.5.4) |
| [const-oid](https://github.com/RustCrypto/formats) | `0.10.2` | Apache-2.0 OR MIT | [const-oid-0.10.2/](third-party/rust-licenses/const-oid-0.10.2) |
| [cpubits](https://github.com/RustCrypto/utils) | `0.1.1` | MIT OR Apache-2.0 | [cpubits-0.1.1/](third-party/rust-licenses/cpubits-0.1.1) |
| [cpufeatures](https://github.com/RustCrypto/utils) | `0.3.1` | MIT OR Apache-2.0 | [cpufeatures-0.3.1/](third-party/rust-licenses/cpufeatures-0.3.1) |
| [crc32fast](https://github.com/srijs/rust-crc32fast) | `1.5.2` | MIT OR Apache-2.0 | [crc32fast-1.5.2/](third-party/rust-licenses/crc32fast-1.5.2) |
| [crypto-bigint](https://github.com/RustCrypto/crypto-bigint) | `0.7.5` | Apache-2.0 OR MIT | [crypto-bigint-0.7.5/](third-party/rust-licenses/crypto-bigint-0.7.5) |
| [crypto-common](https://github.com/RustCrypto/traits) | `0.2.2` | MIT OR Apache-2.0 | [crypto-common-0.2.2/](third-party/rust-licenses/crypto-common-0.2.2) |
| [ctutils](https://github.com/RustCrypto/utils) | `0.4.2` | Apache-2.0 OR MIT | [ctutils-0.4.2/](third-party/rust-licenses/ctutils-0.4.2) |
| [curve25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek/tree/main/curve25519-dalek) | `5.0.0` | BSD-3-Clause | [curve25519-dalek-5.0.0/](third-party/rust-licenses/curve25519-dalek-5.0.0) |
| [der](https://github.com/RustCrypto/formats) | `0.8.2` | Apache-2.0 OR MIT | [der-0.8.2/](third-party/rust-licenses/der-0.8.2) |
| [digest](https://github.com/RustCrypto/traits) | `0.11.3` | MIT OR Apache-2.0 | [digest-0.11.3/](third-party/rust-licenses/digest-0.11.3) |
| [ecdsa](https://github.com/RustCrypto/signatures) | `0.17.0` | Apache-2.0 OR MIT | [ecdsa-0.17.0/](third-party/rust-licenses/ecdsa-0.17.0) |
| [ed25519](https://github.com/RustCrypto/signatures) | `3.0.0` | Apache-2.0 OR MIT | [ed25519-3.0.0/](third-party/rust-licenses/ed25519-3.0.0) |
| [ed25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek/tree/main/ed25519-dalek) | `3.0.0` | BSD-3-Clause | [ed25519-dalek-3.0.0/](third-party/rust-licenses/ed25519-dalek-3.0.0) |
| [elliptic-curve](https://github.com/RustCrypto/traits) | `0.14.1` | Apache-2.0 OR MIT | [elliptic-curve-0.14.1/](third-party/rust-licenses/elliptic-curve-0.14.1) |
| [equivalent](https://github.com/indexmap-rs/equivalent) | `1.0.2` | Apache-2.0 OR MIT | [equivalent-1.0.2/](third-party/rust-licenses/equivalent-1.0.2) |
| [errno](https://github.com/lambda-fairy/rust-errno) | `0.3.14` | MIT OR Apache-2.0 | [errno-0.3.14/](third-party/rust-licenses/errno-0.3.14) |
| [fastrand](https://github.com/smol-rs/fastrand) | `2.5.0` | Apache-2.0 OR MIT | [fastrand-2.5.0/](third-party/rust-licenses/fastrand-2.5.0) |
| [ff](https://github.com/zkcrypto/ff) | `0.14.0` | MIT OR Apache-2.0 | [ff-0.14.0/](third-party/rust-licenses/ff-0.14.0) |
| [flate2](https://github.com/rust-lang/flate2-rs) | `1.1.10` | MIT OR Apache-2.0 | [flate2-1.1.10/](third-party/rust-licenses/flate2-1.1.10) |
| [getrandom](https://github.com/rust-random/getrandom) | `0.4.3` | MIT OR Apache-2.0 | [getrandom-0.4.3/](third-party/rust-licenses/getrandom-0.4.3) |
| [group](https://github.com/zkcrypto/group) | `0.14.0` | MIT OR Apache-2.0 | [group-0.14.0/](third-party/rust-licenses/group-0.14.0) |
| [hashbrown](https://github.com/rust-lang/hashbrown) | `0.17.1` | MIT OR Apache-2.0 | [hashbrown-0.17.1/](third-party/rust-licenses/hashbrown-0.17.1) |
| [heck](https://github.com/withoutboats/heck) | `0.5.0` | MIT OR Apache-2.0 | [heck-0.5.0/](third-party/rust-licenses/heck-0.5.0) |
| [hmac](https://github.com/RustCrypto/MACs) | `0.13.0` | MIT OR Apache-2.0 | [hmac-0.13.0/](third-party/rust-licenses/hmac-0.13.0) |
| [hybrid-array](https://github.com/RustCrypto/hybrid-array) | `0.4.15` | MIT OR Apache-2.0 | [hybrid-array-0.4.15/](third-party/rust-licenses/hybrid-array-0.4.15) |
| [indexmap](https://github.com/indexmap-rs/indexmap) | `2.14.2` | Apache-2.0 OR MIT | [indexmap-2.14.2/](third-party/rust-licenses/indexmap-2.14.2) |
| [inout](https://github.com/RustCrypto/utils) | `0.2.2` | MIT OR Apache-2.0 | [inout-0.2.2/](third-party/rust-licenses/inout-0.2.2) |
| [itoa](https://github.com/dtolnay/itoa) | `1.0.18` | MIT OR Apache-2.0 | [itoa-1.0.18/](third-party/rust-licenses/itoa-1.0.18) |
| [libc](https://github.com/rust-lang/libc) | `0.2.189` | MIT OR Apache-2.0 | [libc-0.2.189/](third-party/rust-licenses/libc-0.2.189) |
| [linux-raw-sys](https://github.com/sunfishcode/linux-raw-sys) | `0.12.1` | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | [linux-raw-sys-0.12.1/](third-party/rust-licenses/linux-raw-sys-0.12.1) |
| [memchr](https://github.com/BurntSushi/memchr) | `2.8.3` | Unlicense OR MIT | [memchr-2.8.3/](third-party/rust-licenses/memchr-2.8.3) |
| [miniz_oxide](https://github.com/Frommi/miniz_oxide/tree/master/miniz_oxide) | `0.9.1` | MIT OR Zlib OR Apache-2.0 | [miniz_oxide-0.9.1/](third-party/rust-licenses/miniz_oxide-0.9.1) |
| [num-traits](https://github.com/rust-num/num-traits) | `0.2.19` | MIT OR Apache-2.0 | [num-traits-0.2.19/](third-party/rust-licenses/num-traits-0.2.19) |
| [once_cell](https://github.com/matklad/once_cell) | `1.21.4` | MIT OR Apache-2.0 | [once_cell-1.21.4/](third-party/rust-licenses/once_cell-1.21.4) |
| [p256](https://github.com/RustCrypto/elliptic-curves) | `0.14.0` | Apache-2.0 OR MIT | [p256-0.14.0/](third-party/rust-licenses/p256-0.14.0) |
| [pem-rfc7468](https://github.com/RustCrypto/formats) | `1.0.0` | Apache-2.0 OR MIT | [pem-rfc7468-1.0.0/](third-party/rust-licenses/pem-rfc7468-1.0.0) |
| [pkcs8](https://github.com/RustCrypto/formats) | `0.11.0` | Apache-2.0 OR MIT | [pkcs8-0.11.0/](third-party/rust-licenses/pkcs8-0.11.0) |
| [primefield](https://github.com/RustCrypto/elliptic-curves) | `0.14.0` | Apache-2.0 OR MIT | [primefield-0.14.0/](third-party/rust-licenses/primefield-0.14.0) |
| [primeorder](https://github.com/RustCrypto/elliptic-curves) | `0.14.0` | Apache-2.0 OR MIT | [primeorder-0.14.0/](third-party/rust-licenses/primeorder-0.14.0) |
| [psl](https://github.com/addr-rs/psl) | `2.1.238` | MIT OR Apache-2.0 | [psl-2.1.238/](third-party/rust-licenses/psl-2.1.238) |
| [psl-types](https://github.com/addr-rs/psl-types) | `2.0.11` | MIT OR Apache-2.0 | [psl-types-2.0.11/](third-party/rust-licenses/psl-types-2.0.11) |
| [rand_core](https://github.com/rust-random/rand_core) | `0.10.1` | MIT OR Apache-2.0 | [rand_core-0.10.1/](third-party/rust-licenses/rand_core-0.10.1) |
| [rfc6979](https://github.com/RustCrypto/signatures) | `0.6.0` | Apache-2.0 OR MIT | [rfc6979-0.6.0/](third-party/rust-licenses/rfc6979-0.6.0) |
| [roxmltree](https://github.com/RazrFalcon/roxmltree) | `0.21.1` | MIT OR Apache-2.0 | [roxmltree-0.21.1/](third-party/rust-licenses/roxmltree-0.21.1) |
| [rustix](https://github.com/bytecodealliance/rustix) | `1.1.5` | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | [rustix-1.1.5/](third-party/rust-licenses/rustix-1.1.5) |
| [sec1](https://github.com/RustCrypto/formats) | `0.8.1` | Apache-2.0 OR MIT | [sec1-0.8.1/](third-party/rust-licenses/sec1-0.8.1) |
| [semver](https://github.com/dtolnay/semver) | `1.0.28` | MIT OR Apache-2.0 | [semver-1.0.28/](third-party/rust-licenses/semver-1.0.28) |
| [serde](https://github.com/serde-rs/serde) | `1.0.229` | MIT OR Apache-2.0 | [serde-1.0.229/](third-party/rust-licenses/serde-1.0.229) |
| [serde_core](https://github.com/serde-rs/serde) | `1.0.229` | MIT OR Apache-2.0 | [serde_core-1.0.229/](third-party/rust-licenses/serde_core-1.0.229) |
| [serde_json](https://github.com/serde-rs/json) | `1.0.151` | MIT OR Apache-2.0 | [serde_json-1.0.151/](third-party/rust-licenses/serde_json-1.0.151) |
| [sha1](https://github.com/RustCrypto/hashes) | `0.11.0` | MIT OR Apache-2.0 | [sha1-0.11.0/](third-party/rust-licenses/sha1-0.11.0) |
| [sha2](https://github.com/RustCrypto/hashes) | `0.11.0` | MIT OR Apache-2.0 | [sha2-0.11.0/](third-party/rust-licenses/sha2-0.11.0) |
| [signature](https://github.com/RustCrypto/traits) | `3.0.0` | Apache-2.0 OR MIT | [signature-3.0.0/](third-party/rust-licenses/signature-3.0.0) |
| [simd-adler32](https://github.com/mcountryman/simd-adler32) | `0.3.10` | MIT | [simd-adler32-0.3.10/](third-party/rust-licenses/simd-adler32-0.3.10) |
| [spki](https://github.com/RustCrypto/formats) | `0.8.1` | Apache-2.0 OR MIT | [spki-0.8.1/](third-party/rust-licenses/spki-0.8.1) |
| [static_assertions](https://github.com/nvzqz/static-assertions-rs) | `1.1.0` | MIT OR Apache-2.0 | [static_assertions-1.1.0/](third-party/rust-licenses/static_assertions-1.1.0) |
| [subtle](https://github.com/dalek-cryptography/subtle) | `2.6.1` | BSD-3-Clause | [subtle-2.6.1/](third-party/rust-licenses/subtle-2.6.1) |
| [tempfile](https://github.com/Stebalien/tempfile) | `3.27.0` | MIT OR Apache-2.0 | [tempfile-3.27.0/](third-party/rust-licenses/tempfile-3.27.0) |
| [thiserror](https://github.com/dtolnay/thiserror) | `2.0.21` | MIT OR Apache-2.0 | [thiserror-2.0.21/](third-party/rust-licenses/thiserror-2.0.21) |
| [typenum](https://github.com/paholg/typenum) | `1.20.1` | MIT OR Apache-2.0 | [typenum-1.20.1/](third-party/rust-licenses/typenum-1.20.1) |
| [uniffi](https://github.com/mozilla/uniffi-rs) | `0.32.2` | **MPL-2.0** | [uniffi-0.32.2/](third-party/rust-licenses/uniffi-0.32.2) |
| [uniffi_core](https://github.com/mozilla/uniffi-rs) | `0.32.2` | **MPL-2.0** | [uniffi_core-0.32.2/](third-party/rust-licenses/uniffi_core-0.32.2) |
| [uniffi_pipeline](https://github.com/mozilla/uniffi-rs) | `0.32.2` | **MPL-2.0** | [uniffi_pipeline-0.32.2/](third-party/rust-licenses/uniffi_pipeline-0.32.2) |
| [wnaf](https://github.com/RustCrypto/elliptic-curves) | `0.14.1` | Apache-2.0 OR MIT | [wnaf-0.14.1/](third-party/rust-licenses/wnaf-0.14.1) |
| [zeroize](https://github.com/RustCrypto/utils) | `1.9.0` | Apache-2.0 OR MIT | [zeroize-1.9.0/](third-party/rust-licenses/zeroize-1.9.0) |
| [zmij](https://github.com/dtolnay/zmij) | `1.0.23` | MIT | [zmij-1.0.23/](third-party/rust-licenses/zmij-1.0.23) |

The licence files were copied from each crate as published on crates.io, at the version pinned in `core/Cargo.lock`.

## Fonts

Bundled in `apps/android/app/src/main/res/font/`: the upstream variable fonts, unmodified (348,640 and 148,460 bytes,
as released upstream). `shared/fonts/` holds static instances of the same families.

| Font | Upstream | Version | Licence | Licence text |
|---|---|---|---|---|
| Geologica (`geologica.ttf`) | [googlefonts/geologica](https://github.com/googlefonts/geologica), via [google/fonts](https://github.com/google/fonts/tree/main/ofl/geologica) | 1.010 | OFL 1.1 | [`shared/fonts/OFL-Geologica.txt`](shared/fonts/OFL-Geologica.txt) |
| Martian Mono (`martian_mono.ttf`) | [evilmartians/mono](https://github.com/evilmartians/mono) | 1.000 | OFL 1.1 | [`shared/fonts/OFL-MartianMono.txt`](shared/fonts/OFL-MartianMono.txt) |

Neither upstream declares a Reserved Font Name. The OFL does not require attribution in the app's interface.

## Keeping this file true

When a dependency that ships in the APK is added, removed or bumped:

1. Read its licence from the artifact or its repository — do not assume.
2. Update the table, and for a Rust crate copy its licence files into `third-party/rust-licenses/<crate>-<version>/`
   (and remove the old version's folder).
3. Regenerate the lists to compare:

```bash
(cd apps/android && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath)
(cd core && for t in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; do cargo tree -p keep-core-ffi -e normal,no-proc-macro --target $t --prefix none; done | sort -u)
```
