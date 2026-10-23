# ADR 0012: iPhone Face ID unlock

- Status: Accepted
- Date: 2026-10-02
- Extends [ADR 0010](0010-android-biometric-unlock.md) (the material and key-file rules) and
  [ADR 0011](0011-ios-vault-session-lifecycle.md) (the iPhone session lifecycle)

## Context

M7 lets someone who has opened a vault with its master password open it again with Face ID on
the same iPhone. ADR 0010 already settled what has to be stored (the password component) and
how key files are handled; this ADR applies those rules with Apple's own mechanisms and records
what is different on iOS.

## Threat model

### Assets

The master password, anything that opens the vault without it, the vault content, the key file.

### What is stored, and where

| Item | Where | Secret? | Protection |
| --- | --- | --- | --- |
| Password component `UnlockMaterial` (version byte + `SHA-256(password)`, 33 bytes) | Keychain, generic password | **Yes.** It is not the password, but for KDBX vaults with this master password it is as good as the password. | Access control `biometryCurrentSet`, class `WhenPasscodeSetThisDeviceOnly`, not synchronizable |
| Setup record: setup id, installation id, vault fingerprint, "key file required", Face ID state hash, state | `Application Support/face-id-unlock.json` | No (the fingerprint is a hash of a random UUID) | `completeUntilFirstUserAuthentication`, excluded from backup |
| Installation id (16 random bytes) | `Application Support/installation-id` | No | same as above |

Nothing goes to `UserDefaults`, `@AppStorage`, `@SceneStorage` or logs.

The material is chosen for the reasons in ADR 0010: the transformed key breaks on every save in
KeePass, the composite key would drop the key file, and the master password itself is often
reused elsewhere.

### When it becomes available

Only after **Face ID succeeds for the current enrollment** on an **unlocked iPhone that has a
passcode**. The passcode alone is never enough: the access control has no `.devicePasscode` or
`.userPresence` alternative, and the prompt's fallback button returns to the master password.

### Attackers

| Attacker | Result |
| --- | --- |
| Thief with the locked iPhone | Nothing: the item is in a passcode-protected class and needs Face ID. |
| Someone who knows the iPhone passcode | Can't use the passcode for the item. Adding their own face changes the enrollment, and `biometryCurrentSet` then makes the item permanently unreadable. |
| Backup of the iPhone, restored anywhere | `ThisDeviceOnly` items don't migrate; the setup record and installation id are excluded from backup. A restored app starts with Face ID unlock off. |
| iCloud Keychain | The item is explicitly not synchronizable. |
| Another app | Keychain items are scoped to this app's access group; the app declares no shared groups. |
| A vault file replaced at the same location | The vault fingerprint is checked after opening: a different database is closed at once and the setup is invalidated. |
| Someone who can make the owner look at the phone | Same as any Face ID-protected app. Off by default; auto-lock and "lock when the iPhone locks" still apply. |
| Jailbreak or code running in our process | Out of scope (SECURITY_MODEL). |

## Decision

### Keychain item, not a Secure Enclave key

`kSecClassGenericPassword`:

- service `app.vazie.keep.face-id-unlock`, account = a random setup id;
- `kSecAttrGeneric` = the installation id;
- value = the encoded material;
- `kSecAttrAccessControl` = `SecAccessControlCreateWithFlags(kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet)`;
- `kSecAttrSynchronizable = false`, `kSecUseDataProtectionKeychain = true`.

The Secure Enclave already enforces the biometric access control of a Keychain item and holds the
keys that wrap it. A Secure Enclave key that wraps the material once more would sit behind the
same access control and stop no additional attacker in this model, so it isn't used. Android
needs a Keystore key because it has no access-controlled secret storage; iOS does.

**`WhenPasscodeSetThisDeviceOnly` rather than `WhenUnlockedThisDeviceOnly`.** Both are local to
this device and readable only while the device is unlocked. The passcode class also deletes the
item when the passcode is turned off, which is exactly when Face ID stops being a meaningful
second step.

### Face ID policy

- `LAPolicy.deviceOwnerAuthenticationWithBiometrics`: Face ID only, no passcode fallback.
- `localizedFallbackTitle` = "Use Master Password". It appears after a failed attempt and returns
  to the password field. iOS handles retries itself.
- The evaluated `LAContext` is passed to the Keychain call (`kSecUseAuthenticationContext`), so
  the user sees one prompt per unlock. Status probes use a context with
  `interactionNotAllowed`, so they never prompt.
- All `LAContext` use is behind `BiometricAuthenticator`; views never touch it.

### Enabling

Enabling happens in Settings, while the vault is open after a manual unlock **with a typed
password**.

When Face ID is available and not already set up, the controller asks the core to keep the
material in the Rust session (`retain_unlock_material`, zeroized on lock), as on Android.
Turning the switch on then:

1. Face ID prompt.
2. Copy the material out of the session into `Data`.
3. `SecItemAdd` with the evaluated context.
4. Wipe the `Data`.
5. Write the setup record.
6. Delete the previous item.

A cancel or failure leaves the switch off and keeps no item. A vault opened with a key file only
has no password component, so the switch explains that instead of turning on.

### Unlocking

There is a button, "Unlock with Face ID", and **no automatic prompt**. The user chooses the
moment, a prompt never interrupts typing, and the master password stays one tap away. When the
setup is on, the password field doesn't grab focus on open, so the keyboard doesn't cover the
button.

The order avoids useless prompts:

1. The setup record exists, belongs to this installation and has state "active".
2. The Face ID state hash still matches, and the item still exists. The existence query doesn't
   prompt; "interaction not allowed" counts as "exists". A mismatch invalidates the setup without
   a prompt.
3. If the setup needs a key file and none is chosen, show "Choose the key file" with no prompt.
4. Read the vault and the key file (stale bookmarks are refreshed). If they are unreadable, show
   a file error with no prompt; the setup is kept.
5. Face ID prompt.
6. Read the item.
7. Open the vault with the material and the key file.
8. Compare fingerprints.
9. Wipe the `Data`.

| Outcome | Result |
| --- | --- |
| Cancel, "Use Master Password", or the system or app cancelled the prompt | Locked, no banner, password field focused |
| Face ID not recognised | iOS keeps the prompt and offers another try, then the fallback |
| Lockout | "Face ID is locked after too many attempts. Use your master password." No passcode prompt. |
| Face ID not enrolled, not available, or denied for the app | A plain explanation; the master password works |
| Item missing, or the enrollment changed (hash or Keychain error) | Invalidated without a prompt where possible |
| Core reports wrong credentials (the password was changed elsewhere, or the material is malformed) | Invalidated |
| Different database | Session closed, invalidated |
| Other Keychain or Face ID error | "Face ID unlock didn't work"; the setup is kept |

**Invalidated** means: delete the item, keep the record with state "needs setup", and show
"Face ID unlock needs to be set up again." Nothing prompts again until the user sets it up again
in Settings. In Settings the user can turn the broken setup off, or set it up again after a
manual unlock.

### Vault binding

- **Fingerprint:** the core's `SHA-256("vazie-keep/vault-fingerprint/v1" ‖ root-group UUID)`,
  which is stable across saves.
- **Choosing a vault** through the picker deletes the setup, because the app keeps one vault
  reference.
- **Same location, different database:** the fingerprint check after opening catches it.
- The limitation in ADR 0010 also applies here: databases created from a template share a
  root-group UUID.

### Key files

Option B from ADR 0010:

- the item holds only the password component;
- the key file is read again on every unlock;
- Face ID never turns "password + key file" into "Face ID only";
- an unreadable key file is a file error, not an invalidation.

### Installation binding

On iOS, Keychain items can outlive an app deletion. The sandbox can't. On first launch the app
creates a random installation id in its sandbox and stores it both in the setup record and in
each item's `kSecAttrGeneric`. A query matches an item only when it carries the
current installation id. With no active setup (after a reinstall, after "turn off", or with a
broken setup), **every item of this service is deleted without being read**. After a reinstall
there is no record and a new installation id, so Face ID unlock is off and the old item is
removed, even though it was still in the Keychain.

On iOS 27, any query on a `biometryCurrentSet` item made with a non-interactive context,
attributes included, returns `errSecInteractionNotAllowed`, so items can't be listed silently.
"Exists" is therefore checked by matching the account and the installation id in the query.
Removal relies on the rule above, together with deleting the previous item on every re-enable.

### Reboot

The Rust session lives only in memory, so the vault is locked after a reboot. The item and the
record survive. iOS requires the passcode for the first unlock after a reboot; after that, Face
ID unlock works again.

## Memory

| Value | Wiped? |
| --- | --- |
| Material in the Rust session | Zeroized on lock and close |
| `Data` copied out for `SecItemAdd`, or returned by `SecItemCopyMatching` | `resetBytes` right after use, best effort: `Data` is copy-on-write |
| The FFI copy | Turned into `SecretBytes` and zeroized in Rust |
| Copies inside `securityd`, the Keychain and LocalAuthentication | Outside the app's control |
| The typed master password `String` | Cannot be wiped (ADR 0011) |

## Consequences

- On this iPhone, Face ID stands in for the master password. It is off by default, and the
  password always works.
- A password change elsewhere breaks Face ID unlock once; the app says why and asks for the
  password.
- While a vault opened with a typed password is unlocked and Face ID could still be turned on,
  the password component stays in Rust memory next to the decrypted vault.

## Alternatives considered

- **`.userPresence` or `.biometryAny`:** the first lets the passcode open the vault, the second
  survives a new face being added. Both rejected.
- **A Secure Enclave key wrapping the material:** the same access control with more code; see
  above.
- **Storing the composite key or the transformed key:** rejected in ADR 0010.
- **Prompting automatically on launch:** rejected, see Unlocking.
