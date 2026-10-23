# ADR 0010: Android biometric unlock

- Status: Accepted
- Date: 2026-10-01
- Extends [ADR 0007](0007-android-vault-session-lifecycle.md), [ADR 0008](0008-android-security-settings.md), [ADR 0009](0009-key-files.md)

## Context

Typing a long master password on a phone several times a day pushes people toward short
passwords. M5 lets a user who has already opened a vault with its master password open it again
with a fingerprint. The question is what the app has to keep, so that a fingerprint can replace
typing, and how much weaker that makes the vault.

## Threat model

### Assets

The master password (often reused elsewhere), anything that opens the vault without it, the
vault content, and the key file.

### What has to be stored

Something that, combined with the vault file (and the key file, if any), opens the vault. Every
candidate is a secret:

| Candidate | Opens | Survives a re-save in KeePass | Leaks if decrypted |
| --- | --- | --- | --- |
| Master password | this vault, every other vault and every service where it is reused | yes | the password itself |
| **Password component `SHA-256(password)`** | every KDBX vault with this master password (plus its key file) | yes | an unsalted hash: weak passwords can be brute-forced offline, strong ones cannot |
| Composite key `SHA-256(component ‖ key file)` | this vault without its key file | yes | key-file protection is gone |
| Transformed key (after the KDF) | this vault, until the next save | **no**: KeePass re-randomises the KDF salt on every save | the same, plus it skips the KDF entirely |
| Our own random token | nothing by itself: KDBX has no way to accept it | — | — |

### Decision on material

**The password component** (`UnlockMaterial`, version byte + 32 bytes), and only when the vault
was opened with a typed password.

- It is not the password: it can't be typed into another service, and recovering a strong
  password from it is infeasible.
- It is still **as good as the password for opening KDBX vaults**. We don't call it "not a
  secret"; it is protected exactly like one.
- It contains nothing from the key file, so a key-file vault still needs its key file (below).
- It is KeePass's own intermediate value, not a new replay token. It grants nothing beyond what
  the password grants.

The transformed key was rejected because every save in KeePass or KeePassXC would silently break
biometric unlock, and because it removes the KDF from the attacker's path.

### Rust API

`VaultCredentials::unlock_material()`, `UnlockMaterial::{encode, decode}` and
`VaultCredentials::from_unlock_material(material, key_file)`. The FFI accepts
`PasswordDto::UnlockMaterial` wherever a typed password is accepted.

`VaultSession::retain_unlock_material` keeps the component **in the Rust session** (zeroized on
close) only when the platform asks. Android asks only when strong biometrics are available and
biometric unlock is not already on. This lets the user turn the feature on from Settings without
retyping the password.

There is no `prepare_unlock_material(password)` that derives material without opening the vault:
that would hand out a password-equivalent for a password nobody has verified.

### Attackers

| Attacker | Result |
| --- | --- |
| Thief with the locked phone, no screen PIN | Nothing: the vault is locked, and the Keystore key needs a fingerprint. |
| Thief who saw the screen PIN | Cannot enroll a new finger without invalidating the key (`setInvalidatedByBiometricEnrollment`). Device credential is **not** an allowed authenticator, so the PIN alone never opens the vault. |
| Malware / backup reading app files | The blob is AES-256-GCM under a non-exportable TEE key. Useless off this device and without a fingerprint. It lives in `noBackupFilesDir`, and all backup domains are excluded. |
| Root on an unlocked device | Out of scope (SECURITY_MODEL): it can read process memory, including the open vault. |
| A vault file swapped at the same URI | The record is bound to the database fingerprint (below). A different database is refused before any data is shown, and the setup is invalidated. |
| Someone with the fingerprint (coerced or asleep owner) | Same as with any biometric. Mitigations: off by default, and the screen-off and auto-lock rules still lock the vault. |

## Decision

### Keystore key

One key per setup, alias `vazie_keep_biometric_<random UUID>`, in `AndroidKeyStore`:

- AES-256, GCM, no padding, randomized encryption (Keystore chooses the 12-byte IV);
- `setUserAuthenticationRequired(true)` with **per-operation** authentication:
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on API 30+, validity −1 below;
- `setInvalidatedByBiometricEnrollment(true)`;
- non-exportable. Must be hardware-backed (TEE or StrongBox); a software-only key is deleted and
  the feature reports "unavailable". StrongBox isn't requested: the Galaxy A17 has none, and the
  TEE already gives non-exportability.
- `setUnlockedDeviceRequired` is not set: per-operation biometric authentication already implies
  an unlocked device, and the flag has known OEM bugs.

Verified on the Galaxy A17 by instrumentation (`KeystoreTest`): TEE, auth required, per-operation,
strong biometric only, invalidated by enrollment, `getEncoded() == null`, and encryption without a
fingerprint fails.

### Authenticators

`BIOMETRIC_STRONG` only, through AndroidX Biometric (1.1.0, the latest stable).

- Class 2 face unlock (the A17's) is excluded: it can't authorize Keystore keys.
- Device credential is excluded: it would make the screen PIN a vault password.

The prompt's negative button is "Use master password".

### What is persisted

`noBackupFilesDir/biometric_unlock.bin` (mode 0600), written to a temporary file and renamed:

| Field | Secret? |
| --- | --- |
| magic, version, state | no |
| vault URI | no (already in the vault reference) |
| database fingerprint | no (hash of a random UUID) |
| Keystore alias | no |
| key file required (bool) | no |
| GCM IV | no |
| ciphertext of `UnlockMaterial` + tag | encrypted, useless without the Keystore key |

The AES-GCM associated data covers the URI, fingerprint, alias and key-file flag, so editing any
of them makes decryption fail. DataStore keeps only the security settings, and no blob is stored
as Base64 in preferences.

### Vault binding

- **Storage key:** one record, bound to the current vault reference URI. Choosing another vault
  deletes the setup and its key. The app opens one vault at a time (ADR 0007).
- **Database fingerprint:** `SHA-256("vazie-keep/vault-fingerprint/v1" ‖ root-group UUID)`,
  computed by the core. The root-group UUID is random at database creation and survives every
  save. After a biometric unlock the session is used only if the fingerprint matches; otherwise
  it is closed at once and the setup is invalidated.
- Header-derived identifiers were rejected: KeePass rewrites the seeds and IV on every save.
- Limitation: tools that create databases from a template (pykeepass) reuse one root-group UUID,
  so two such databases look identical. The material opens only vaults with the same password
  anyway, so this costs convenience checks, not security.

### Key file + biometric

Of the three options, **B: the biometric blob holds only the password component; the key file is
read from its URI on every unlock.**

- A key-file vault stays two-factor: the fingerprint replaces typing, not the file.
- Option A (storing the composite key) would quietly turn "password + file" into "phone +
  finger". Rejected for M5. It could come back later as an explicit, labelled opt-in.
- Option C (transformed key) has the problems listed above.
- If the record requires a key file and none is chosen, the app says so **before** prompting.
- A missing or unreadable key file is a normal error; it doesn't invalidate the setup.
- A key-file-only vault has no password component, so biometric unlock isn't offered ("there is
  no password to remember").

### Flows

**Enable** (Settings, vault open, opened with a typed password):

1. Create a key.
2. `BiometricPrompt` with a `CryptoObject` (encrypt).
3. Copy the material out of the Rust session.
4. Encrypt with the associated data.
5. Wipe the material.
6. Write the record.
7. Delete the previous alias.

Any failure deletes the new key and leaves biometric unlock **off**.

**Unlock** (button; there is no auto-prompt):

1. Check the record, the vault and the key file.
2. Initialise the cipher; a permanently invalidated key means set up again.
3. `BiometricPrompt` (decrypt).
4. `doFinal`.
5. `openVault(material + key file)`.
6. Check the fingerprint.
7. Wipe.

| Outcome | Result |
| --- | --- |
| Cancelled | Unlock screen, nothing changes |
| Lockout | "Too many attempts. Use your master password." |
| Other prompt error, or a Keystore error after authentication (e.g. the operation was pruned) | "Biometric unlock didn't work", setup kept |
| Bad GCM tag (blob damaged or edited) | Invalidated |
| Key missing or permanently invalidated (enrollment changed, screen lock removed, reinstall/restore) | Invalidated, **without** showing a prompt |
| Core says wrong credentials (master password changed elsewhere) | Invalidated |
| Different database | Invalidated |

"Invalidated" deletes the key and the ciphertext and keeps a tombstone (vault URI only). The
Unlock screen then shows "Biometric unlock needs to be set up again. Unlock with your master
password." Settings shows the switch as on but broken: turning it off clears the tombstone, and
turning it on again re-enrols.

**Disable:** delete the Keystore alias, delete the file, set status Off.

**No auto-prompt:** with a manual button the user chooses the moment, a stray prompt never
competes with a password the user is typing, and the screen can be read first. When biometric
unlock is on, the password field no longer grabs focus on open, so the keyboard doesn't hide the
button.

The master password always works. There is no biometric-only mode.

### Backup, reinstall, reboot

- `allowBackup=false`, every backup domain excluded, and the blob lives in `noBackupFilesDir`.
- An uninstall removes the app's Keystore keys and files. A restored blob without its key reads
  as "set up again" and never prompts (instrumentation test).
- After a reboot the key and record are intact and the session is naturally gone. Biometric
  unlock works after the first device unlock (verified on the A17).

## Consequences

- One fingerprint now stands for the master password **on this device**. That is the purpose,
  and it is why the feature is off by default and strong-biometric only.
- Changing the master password elsewhere silently breaks biometric unlock until the next manual
  unlock and re-enable. The app explains this instead of failing quietly.
- While the vault is open after a typed password, a password-equivalent stays in Rust memory
  (zeroized on lock) when the feature could still be turned on. The decrypted vault is already
  in that memory, so the exposure grows only slightly.

## Alternatives considered

- **Storing the master password** (KeePassDX does this): rejected; reused passwords would leak.
- **Device credential as a fallback authenticator:** rejected; the PIN would become the vault
  password. The master password is the fallback.
- **Asking for the password again when enabling:** safer memory-wise, worse UX. Possible later if
  the retention window proves to matter.
- **Auto-prompt on screen open:** rejected (see Flows).
