# AutoFill threat model (iPhone)

Scope: the Credential Provider extension from [ADR 0013](../adr/0013-ios-autofill-architecture.md),
for username and password only. The central question: **can Vazie Keep hand a credential to the
wrong site or app?**

## Trust boundaries

- **The requesting origin is supplied by iOS:**
  - for Safari, the URL of the frame that has the login form;
  - for apps, the domains they prove through `webcredentials:` associated domains.

  The page or app can't supply its own service identifier. Vazie Keep claims no associated domains.
- **The extension runs in its own sandboxed process** and gets the password back from Rust only
  for the entry the user picks.
- **Shared with the app:**
  - non-secret files in the App Group (bookmarks, the Face ID setup record, the installation id);
  - one Face ID Keychain item in a single team-scoped group.

## Threats

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| **Phishing domain** (`paypaI.com`, `paypal.com.evil.net`, `example.net` vs `example.com`) | Exact host comparison after IDNA encoding; a subdomain only of the saved host. Tests cover `paypaI`, `.evil.net`, `.net` and punycode look-alikes. | Other entries remain reachable by manual search. Choosing one is a deliberate act, the same as copying a password. |
| **Public suffix / shared hosting** (`github.io`, `co.uk`) | A saved host that is a public suffix (ICANN or private section of the PSL) matches only itself. Sibling sites (`alice.github.io` vs `bob.github.io`) never match. | The PSL is compiled in; a site added to the list after a release is treated as registrable until the next update. |
| **Downgrade** (https entry, http page) | Never suggested; ports must be equal. | None known. |
| **Malicious native app** | iOS gives the extension only domains the app has proved via associated domains; app-type identifiers get no suggestions in M8. | An app that legitimately owns a domain gets that domain's suggestions, by design. |
| **Spoofed service identifier** | It comes from iOS, not from the page; we never parse page content. | We trust AuthenticationServices. |
| **Stale index** (entry URL changed, entry deleted) | The record id carries the vault fingerprint prefix. After unlocking, the entry must exist and its current URL must match the identity's host; otherwise the user sees the list, with nothing filled. | A suggestion can be shown for a while after its entry changed; filling it is still refused. |
| **Swapped vault** (another database at the same bookmark) | The fingerprint prefix in the record id doesn't match, so nothing is filled automatically. Face ID unlock also checks the full fingerprint (ADR 0012). | The swapped database can be unlocked with its own password, as in the app. |
| **Compromised extension process** | Runs only while iOS shows it. Holds an open session only between unlock and fill, then `lock()`. Closes on host background and on memory warnings. | Code execution inside the process sees the open vault while it is open: out of scope (SECURITY_MODEL). |
| **Face ID material in two processes** | One item in `<team>.app.vazie.keep.shared` with `biometryCurrentSet` and no passcode alternative. The extension never creates or retains material. Invalidation is shared, so there's never a second setup. | — |
| **Clipboard** | Not used. The password goes straight to `ASPasswordCredential` / `completeRequest`. | — |
| **Logs** | Release: nothing. Debug: counts and states only; no host, title, username or URL. | — |
| **Shoulder surfing / app switcher** | Passwords are never displayed in the extension; lists show titles and usernames only, and are dropped when the host goes to the background. | Titles and usernames are visible on screen while choosing. |
| **Usernames held by iOS** | Identities contain the host and username, never a password; stated in Settings. Cleared when the vault changes. | iOS keeps them while AutoFill is on. |
| **Reinstall** | Old Keychain items are swept (ADR 0012), and identities are cleared on a new installation's first launch. | — |

## What is never offered automatically

- an entry saved on a parent domain of the page (`accounts.example.com` on `example.com`);
- a sibling subdomain;
- a different registrable domain, however similar;
- an entry without an http(s) URL, or with a different port or a weaker scheme.

All of these remain reachable through explicit search.

## Verification

- Rust `origin` tests: exact, subdomain, parent, sibling, look-alikes, public suffixes, IPs,
  malformed input.
- Swift `AutoFillMatcherTests` and `AutoFillFlowTests`: origins, ports and schemes, wrong domain,
  only the chosen password revealed, stale suggestion, swapped vault, cancel and suspend, a
  shared Face ID invalidation.
- `AutoFillIntegrationTests` on the real XCFramework.
- `KeychainCredentialStoreTests` on the iPhone: the shared group, class, refusal without Face ID.
- **Not yet verified on a device:** Safari and native-app requests through iOS itself. They need
  the AutoFill entitlement, which a free Personal Team can't sign.
