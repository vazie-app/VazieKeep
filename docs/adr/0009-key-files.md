# ADR 0009: KeePass key files

- Status: Accepted
- Date: 2026-10-01
- Extends [ADR 0005](0005-kdbx-engine.md)

## Context

Many KeePass users protect a vault with a key file, alone or together with the master password.
M5 adds key files to `keep-core` and to the Android unlock screen. The format is KeePass's, not
ours. It has to open exactly the vaults that KeePass 2.x, KeePassXC and KeePassDX open.

Sources checked on 2026-10-01:

- KeePass help, *Composite Master Key → Key Files* (keepass.info/help/base/keys.html): the four
  formats and the "anything else is hashed" rule.
- KeePass 2.x source (`KeePassLib/Keys/KcpKeyFile.cs`, `KcpKeyFile.Xml.cs` / `KfxFile`,
  `CompositeKey.cs`, `KcpPassword.cs`), through the public mirror of the official releases.
- KeePassXC source (`src/keys/FileKey.cpp`, `CompositeKey.cpp`).
- pykeepass 4.2.0 (`kdbx_parsing/common.py`), which writes our vectors.

## Decision

### Interpretation of a key file (KeePass 2.x order)

1. **XML.** The file is valid UTF-8 XML (BOM allowed, DTD rejected, 10 000-node limit) whose root
   is `<KeyFile>` without a namespace:
   - `Meta/Version` gives the major version. `1.x` → `Key/Data` is Base64 (whitespace removed).
     `2.x` → `Key/Data` is hex with whitespace allowed anywhere, and an optional `Hash` attribute
     holding the first 4 bytes of `SHA-256(key)` in hex.
   - The decoded bytes are the key component as they are, with no length check. KeePass doesn't
     force 32 bytes. KeePassXC truncates or zero-pads to 32; every real key file has 32 bytes, so
     the difference never shows.
   - **A file with a `KeyFile` root that fails** (unknown version, empty or malformed data, wrong
     `Hash`) is an error, `InvalidKeyFile`. It is not hashed instead. This matches KeePass
     ("a syntactically valid XML key file must verify") and KeePassXC.
   - Malformed XML, another root element or a namespace means it is not a key-file XML; go on.
2. **Exactly 32 bytes** → used as the key.
3. **Exactly 64 bytes, all hex digits** → decoded to 32 bytes.
4. **Anything else, including an empty file** → `SHA-256(file)`.

### Composite key

`SHA-256(password component ‖ key-file component)`, password first, where the password component
is `SHA-256(UTF-8 password)`. Either component may be absent; both absent is
`OpenError::MissingCredentials`. An empty password is still a component (`SHA-256("")`).

The KDBX pipeline after the composite key (KDF, master key, HMAC, payload) is unchanged.

### API

- `VaultCredentials::new(password: Option<SecretBytes>, key_file: Option<SecretBytes>)`. Both
  inputs are consumed. The key file is parsed at once, and only its component
  (`Zeroizing<Vec<u8>>`) is kept. `from_password` stays for existing callers.
- `detect_key_file_format(&[u8]) -> Result<KeyFileFormat, OpenError>` for diagnostics and tests.
- FFI: `VaultCredentialsDto { password: Option<PasswordDto>, key_file: Option<Vec<u8>>, … }`.
- WASM: `openVault(data, password, keyFile?)`. An empty password with a key file means "key file
  only", because the extension has no separate password switch.
- Kotlin: `VaultCredentials(password, unlockMaterial, keyFile, …)`, a `Closeable` that wipes every
  array. Swift keeps a password-only facade until the Apple UI needs key files.

### Errors

| Situation | Core | User sees (Android) |
| --- | --- | --- |
| Wrong key file, or right file with wrong password | `InvalidCredentials` (indistinguishable by design: one HMAC) | "The password or the key file doesn't open this vault" |
| Damaged XML key file / bad `Hash` / unknown version | `InvalidKeyFile` | "This key file is damaged or has an unsupported version" |
| Neither password nor key file | `MissingCredentials` | "Enter the master password or choose a key file" |
| File gone, permission revoked | (platform) | "The key file is unavailable. Choose it again." |
| Larger than 64 MiB | (platform) | "This key file is too large" |
| A KDBX file chosen as key file | (platform, header check) | "That's a vault, not a key file" |

### Android

- The key file is chosen with SAF (`OpenDocument`, any type) and gets a persisted **read** URI
  permission. The vault reference stores only its **URI and display name**, never the content.
- The file is **re-read on every unlock** (bounded at 64 MiB, read buffers zero-filled) and
  wiped right after the core consumes it.
- Choosing another vault forgets the key file and releases its permission. Changing or removing
  the key file releases the old permission.
- With the password field empty and a key file chosen, the app first tries the key file alone.
  If that fails, it retries with an empty password, as KeePassXC does for vaults created with
  an empty password.

### Remembering the key file is not "the key file is public"

Remembering the URI is a convenience: the next unlock reads the file again. The file stays an
authentication factor. Anyone holding a copy of it (and the password, if any) can open the vault.
A key file stored on the same phone as the vault protects against far less than one kept on
removable or separate storage. The documentation says so, and biometric unlock never turns it
into stored data (ADR 0010).

## Test vectors

`shared/test-vectors/keyfiles`, generated by `tooling/test-vectors/generate_keyfiles.py`:

- Vaults: password-only, key-file-only and password + key file, plus one key-file-only vault for
  each format.
- Key files: XML v1, XML v2, binary 32, hex 64 and a hashed 1000-byte file. Also a wrong key file
  and an XML v2 file with a bad `Hash`.
- All key files are derived from fixed labels; all vaults are written by pykeepass. The generator
  checks every component against pykeepass before writing. The vaults were also opened with
  keepass-rs 0.15. **keepass-rs accepts the bad-`Hash` file** (it doesn't check the attribute),
  while KeePass 2.x, KeePassXC, pykeepass and we reject it.
- `vectors.json` lists passwords, components, expected entries and 17 open/fail cases;
  `SHA256SUMS` pins every file.

## Consequences

- Vaults that KeePass opens with a key file open here, including KeePass 1.x-era 32-byte, hex
  and hashed files.
- XML key files in UTF-16 are not recognised as XML and get hashed. KeePass reads them as XML.
  No current KeePass or KeePassXC version writes them.
- `KcpUserAccount` (Windows user account) and key providers (plugins, YubiKey challenge-response)
  are not supported. Such vaults fail with `InvalidCredentials`.

## Alternatives considered

- **Requiring 32-byte XML data** (KeePassXC's effective behaviour): it would silently diverge from
  KeePass for hand-made files. Rejected.
- **Falling back to hashing when an XML key file fails verification:** this would turn a damaged
  key file into a different key and a confusing "wrong password". Rejected; KeePass errors too.
- **Copying the key file into app storage:** it would create a second copy of an authentication
  factor that no backup rule or file manager shows the user. Rejected.
