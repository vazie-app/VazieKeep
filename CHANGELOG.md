# Changelog

Notable changes to Vazie Keep. Versions follow [Semantic Versioning](https://semver.org); each release is tagged
`vX.Y.Z` and published on the [Releases](https://github.com/vazie-app/VazieKeep/releases) page with its APK, checksums
and the signing certificate's fingerprint. The release workflow takes the notes for a version from its section below.

## Unreleased

## 1.0.0 — First release (2026-10-23)

The first release of Vazie Keep: a password manager for the KeePass file you already own. An Android app (8.0 or newer)
and a browser extension for Chromium browsers.

### Your database
- Open a KeePass database (KDBX 4) from the phone, a cloud folder or a USB stick, and save changes back to the same
  file; other KeePass apps keep working with it.
- Create a new database; master password, key files and biometric unlock.
- Safe save: a backup and a write-ahead copy before the file is touched, and what was written is read back.
- Changed on another device: **Merge** combines your changes with the other version and saves the result; an entry
  changed on both sides is kept twice (the older one as a copy), nothing is thrown away. No password is asked when Keep
  itself saved the other version; a file another program rewrote asks for the master password once.
- Works offline: opening, editing, creating and saving a database never needs a connection.

### Entries
- Copy, reveal and edit are explicit actions; the clipboard clears itself and the app locks on its own.
- Password and passphrase generator with every option.
- Icons for groups and entries from the standard KeePass set; website icons on request, fetched straight from each site
  and kept in the database file.
- Import from browsers, KeePassXC, LastPass, 1Password and Bitwarden (CSV, `.1pux`, JSON); encrypted exports are
  refused.

### On the phone
- Autofill, with saving new credentials from the form you just filled.
- Passkeys as an Android credential provider (Android 14 and newer).
- App icons in eight colours on three plates; light, dark and system themes; English and Russian.
- Update notices: an app installed from RuStore is sent to RuStore, any other build to vazie.app.

### Keep+
- Optional extras with a Vazie Account: several databases, one-time codes (TOTP), adding attachments and the entry
  history view. Bought on vazie.app; nothing is paid inside the app.
- Everything that protects your passwords stays free, with no account: Keep+ lapsing never locks or deletes a database.

### Privacy
- No ads, no analytics or crash-reporting SDKs.
- Nothing about a database, an entry, a URL or a database count is sent to Vazie. The app contacts the Vazie
  account and Keep servers only for the optional account and Keep+, the update check and the help texts, and websites only
  for icons you ask for and for passkey checks.

### Builds
- APK for `arm64-v8a`, `armeabi-v7a` and `x86_64`, signed with the Vazie release key (`CN=Vazie, OU=Application
  Signing, O=Vazovsky Development`).
- The addresses of the Vazie servers are not in the source: a release takes them from the environment or from
  `local.properties`, and the Android release refuses to build without them.
- The browser extension is a zip for Chromium browsers (`vazie-keep-1.0.0-chromium.zip`), built from the same tag.
- The save check's diagnostic never quotes a title or any other text from the vault.

### Browser extension (Chromium)
- Ready for the Chrome Web Store: version 1.0.0, a 129-character manifest description, store listing texts, permission
  justifications, reviewer instructions, 1280×800 screenshots and promo tiles in `apps/browser-extension/store`.
- Vazie Keep for Chrome, Edge, Brave, Vivaldi, Opera and other Chromium browsers, with every function of the app: open or
  create a KeePass (KDBX 4) file from the browser, edit and save it back to the same file with the phones' safe save,
  master password and key files, groups, entries, favourites, tags, expiry, custom fields, attachments, history, import,
  the generator, one-time codes, passkeys, several vaults, the Vazie Account and Keep+, themes and English and Russian.
- Autofill on web pages (a button in login fields, a menu, the toolbar popup, a shortcut), saving and updating logins after
  sign-in, a generator in sign-up forms, one-time codes into verification fields.
- Passkeys: create and sign in from the vault, with "use another way" always available, and Keep's passkeys in the
  sign-in field's menu when a site asks for conditional sign-in.
- Quick unlock with a passkey, a security key or biometrics where the browser and device can derive a secret (WebAuthn PRF).
- Popup, full tab and side panel; auto-lock after idle minutes, when Keep is closed, or when the device locks; the
  clipboard is cleared only if it still holds what Keep put there.
- The WebAssembly core now exposes the whole app contract, and the core can be given a clock (`keep_core::set_clock`),
  which a browser's WebAssembly needs.

### Licence
- Vazie Keep is under the [PolyForm Shield License 1.0.0](LICENSE): the source is public to read, build, change and use,
  but not to provide a product that competes with Vazie Keep.
