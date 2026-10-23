# ADR 0013: iPhone AutoFill credential provider

- Status: Accepted (device QA pending a paid Apple Developer Program team)
- Date: 2026-10-02
- Extends [ADR 0011](0011-ios-vault-session-lifecycle.md) and [ADR 0012](0012-ios-biometric-unlock.md)
- Threat model: [AUTOFILL_THREAT_MODEL](../security/AUTOFILL_THREAT_MODEL.md)

## Context

iOS fills passwords into Safari and apps through an AuthenticationServices Credential Provider
extension. The extension runs in its own process, often started cold, with tighter memory and
time limits than the app, and it can't reach the app's open Rust session.

## Decision

### Process model: the extension opens the vault itself (option A)

The extension (`app.vazie.keep.autofill`) links the same `KeepApp` / `KeepCore` / XCFramework
as the app. For each request it builds its own `VaultSessionController` and `BiometricUnlock`:

1. unlock;
2. list entries from summaries;
3. reveal the password of the entry the user picks;
4. `completeRequest`;
5. `lock()`.

Nothing stays open between requests.

**Rejected: a shared encrypted index with deferred secret access (option B).** It needs its own
key hierarchy, it duplicates the vault's data, and it still has to open the vault to fill a
password. It adds a second encrypted store with nothing to show for it.

**Rejected: IPC to the app's session.** Extensions can't keep the app running, and any channel
that hands out passwords to another process would need its own authentication.

### Shared state: App Group `group.app.vazie.keep`

`SharedContainer` moves these files from the app's Application Support into the group container
on the app's first launch:
- `vault-reference.json` (bookmarks);
- `face-id-unlock.json` (the setup record);
- `installation-id`.

That is all it holds. The decrypted vault, the master password and the unlock material never go
there.

A build without the App Group entitlement falls back to the app's own container. That covers a
free Personal Team, which can't sign App Groups or AutoFill; the extension can't run there anyway.

### Shared Keychain: `<team>.app.vazie.keep.shared`

- Both targets list this group in `keychain-access-groups`; the app also keeps its own default group.
- New Face ID items are created in the shared group, with the same access control as ADR 0012:
  `biometryCurrentSet`, `WhenPasscodeSetThisDeviceOnly`, not synchronizable.
- An M7 item still in the app's own group is moved on the first successful Face ID read in the
  app: it is re-added in the shared group with the already authenticated `LAContext`, then the
  old item is deleted. There is one setup, one item and one invalidation for both processes.
- The installation id comes from the shared container, so the app and the extension belong to
  the same installation. An uninstall removes both and the container. Old items are swept (ADR
  0012).
- The extension never turns Face ID unlock on (it never retains material). An invalidation found
  by the extension is written to the shared record, and the app then shows "Needs setup".

### Requests

| iOS call | Behaviour |
| --- | --- |
| `prepareCredentialList(for:)` | Unlock UI, then "Suggested" (matches) and "Other entries", plus Rust search |
| `prepareInterfaceToProvideCredential(for:)` (QuickType suggestion) | Unlock, check the record, fill; otherwise fall back to the list |
| `provideCredentialWithoutUserInteraction(for:)` | Always `userInteractionRequired`: the vault is never left open |
| `prepareInterfaceForExtensionConfiguration()` | "Ready", or "Open Vazie Keep first and choose a vault" |

When the host app goes to the background (`NSExtensionHostDidEnterBackground`) or memory runs
low, the session is closed and the lists are dropped.

### Identity index (`ASCredentialIdentityStore`)

- After each unlock in the app, `AutoFillIndexer` replaces the identities with: the host of each
  entry that has a password, a username and an http(s) URL without an explicit port; the
  username; and the record id `"<first 16 hex of the vault fingerprint>/<entry id>"`. All of
  this is built from Rust summaries; no password is revealed.
- The identities are cleared when the vault is switched or forgotten, and on the first launch
  of a new installation.
- They stay registered while the vault is locked: they hold no secret.
- iOS stores these hosts and usernames itself in order to offer suggestions. That is stated in
  Settings.

### Matching

- **Hosts:** the core's `origin::match_host`, using the Mozilla Public Suffix List (`psl` crate):
  - an exact host matches;
  - a subdomain of the saved host matches, but only if the saved host is a registrable domain
    or below one;
  - parents, siblings and look-alikes never match, and neither does a public suffix (ICANN or
    private, such as `github.io`);
  - an IP address matches only exactly.
- **Scheme and port (Swift):** ports must be equal, and an https entry is never offered to an
  http page.
- **Host input:** `URLComponents.encodedHost` gives an IDNA host, so a Unicode host can't pose as
  an ASCII one.
- **QuickType suggestions:** iOS matches our identities against the page itself. When one is
  picked, the extension re-checks that the record belongs to this vault and that the entry's
  current URL still matches the identity's host; otherwise it shows the list with "This saved
  login changed".
- **No fuzzy matching:** "Other entries" and search are offered only as explicit manual choices.
- Native apps without web credentials and `.app` service identifiers (iOS 26.2+) get no
  suggestions in M8, only search.

### What is filled

The username and password of the chosen entry, once, through
`ASPasswordCredential`. Not notes, custom fields or TOTP. The clipboard is never used.

### UI

SwiftUI inside `ASCredentialProviderViewController`, sharing `Theme`, the messages and the string
catalog with the app:
- Face ID first when it is set up; the master password always works;
- a key file can't be chosen here, so it is "Open Vazie Keep and choose the key file again";
- VoiceOver reads each row as "title, username", with the hint "Fills this login";
- passwords are never shown.

### Settings in the app

The "AutoFill" section shows On/Off and the number of logins offered. It uses the official
`ASSettingsHelper.requestToTurnOnCredentialProviderExtension()` and
`openCredentialProviderAppSettings()`, with no private URL schemes. It also gives the path:
Settings → General → AutoFill & Passwords → Vazie Keep.

### Signing

- **Production** (`project.yml`): the app and the extension carry AutoFill, App Groups and Keychain
  Sharing.
- **Free Personal Team:** it has no AutoFill or App Groups; Apple refuses with "team does not have
  a program membership that is eligible for this feature". For the iPhone,
  `CODE_SIGN_ENTITLEMENTS=iOS/VazieKeep-PersonalTeam.entitlements` (Keychain Sharing only) is
  passed on the command line. The app then works as before, and the extension is embedded but
  inactive.

## Consequences

- AutoFill costs one full vault open (KDF included) per fill. On the iPhone 16 that is about
  250 ms for Argon2 64 MiB in the app. The extension's memory headroom is not documented by
  Apple and has not been measured yet (device QA is pending).
- The extension links its own static copy of the core (+3.8 MB).
- iOS holds hosts and usernames for suggestions.

## Alternatives considered

- **Keeping a session open in the extension for a quicker second fill:** rejected, because
  extension processes are suspended and killed unpredictably and a long-lived session widens
  the exposure.
- **Registering URL-type identities with ports:** deferred. Domain identities cover the common
  case.
- **Matching in Swift:** there is no public PSL API on iOS. The core can be shared with the
  browser extension later.
