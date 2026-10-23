# Architecture Decision Records

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-shared-core-language.md) | Rust for the shared core | Accepted |
| [0002](0002-monorepo-layout.md) | Monorepo with per-toolchain builds | Accepted |
| [0003](0003-browser-extension-architecture.md) | Browser extension: MV3, WASM core, no secrets in content scripts | Accepted |
| [0004](0004-apple-bundle-identifiers.md) | Separate iOS and macOS bundle identifiers in one Xcode project | Accepted |
| [0005](0005-kdbx-engine.md) | KDBX engine: own protocol code over RustCrypto primitives | Accepted |
| [0006](0006-vault-session-handles.md) | Vault session handles, secrets on demand | Accepted |
| [0007](0007-android-vault-session-lifecycle.md) | Android vault session lifecycle: owner, process death, auto-lock, UI secret lifetime | Accepted |
| [0008](0008-android-security-settings.md) | Android security settings: lock policy, screen-off lock, clipboard, screenshots, accessibility trade-offs | Accepted |
| [0009](0009-key-files.md) | KeePass key files: formats, composite key, errors, Android key-file reference | Accepted |
| [0010](0010-android-biometric-unlock.md) | Android biometric unlock: threat model, stored material, Keystore, vault binding, invalidation | Accepted |
| [0011](0011-ios-vault-session-lifecycle.md) | iOS vault session lifecycle: bookmarks, session owner, scene lifecycle, privacy overlay, clipboard, accessibility | Accepted |
| [0012](0012-ios-biometric-unlock.md) | iPhone Face ID unlock: Keychain `biometryCurrentSet`, threat model, installation and vault binding, invalidation | Accepted |
| [0013](0013-ios-autofill-architecture.md) | iPhone AutoFill: extension opens the vault itself, App Group, shared Keychain group, identity index, origin matching | Accepted |
| [0014](0014-kdbx-write-and-save.md) | KDBX writing and safe save: lossless DOM, verified writer, edit = save, coordinated atomic replace, conflict detection, backups | Accepted |
| [0015](0015-android-safe-save.md) | Android editing and safe save through SAF: journaled overwrite, provider capabilities, no atomic replace, backups, recovery, activity-alias launcher icons | Accepted |
| [0016](0016-android-autofill.md) | Android Autofill: authentication-needed datasets, short-lived session, pinned-browser web origins, package and certificate binding, non-secret index | Accepted |
| [0017](0017-android-autofill-save.md) | Android Autofill save requests: confirm-then-safe-save, in-process handoff, pending app associations, `QUERY_ALL_PACKAGES` removed | Accepted |
| [0018](0018-shared-design-system.md) | One design system for Android and iPhone: semantic tokens from `design/`, bundled Geologica and Martian Mono, restyled native components, Autofill as a bottom sheet | Accepted |
| [0020](0020-keep-plus-entitlements.md) | Keep+ entitlements: optional Vazie Account, `KEEP_PLUS` grant, signed Ed25519 JWS lease verified offline in the Rust core, 30-day offline window, clock-rollback rule | Accepted |
| [0021](0021-totp-codes.md) | One-time codes: KeePassXC `otp` field read and written, core-side RFC 6238 (SHA-1/256/512, Steam), validation before saving, Keep+ gates the code and the helper, never the data | Accepted |
| [0022](0022-browser-extension-product.md) | The browser extension as a full client: offscreen host, one app on three surfaces, File System Access vaults, safe save, PRF quick unlock, content-script autofill, passkey interception, the whole WASM contract | Accepted |
| [0023](0023-vault-merge.md) | Merging two versions of a database: history-based relation, conflict copies, deletion records, convergent and idempotent | Accepted (core, bindings, Android) |

New ADRs take the next number and follow the same sections: Context, Decision, Consequences,
Alternatives.
