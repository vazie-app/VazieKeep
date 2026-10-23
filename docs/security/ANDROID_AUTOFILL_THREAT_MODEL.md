# Android Autofill threat model

Scope: the Autofill service and authentication screen from [ADR 0016](../adr/0016-android-autofill.md),
username and password only. The central questions: **can Vazie Keep hand a credential to the wrong
site or app, and can anything but the person's explicit choice make a secret leave the vault?**

## Trust boundaries

- **The package asking is supplied by the system** (`AssistStructure.getActivityComponent()`), and its
  signing certificate is read from the package manager. Neither can be set by the page or the app.
- **A page's host is supplied by the app or browser** (`ViewNode.getWebDomain()`), unverified by the
  framework. It is believed only from a pinned browser (package and certificate).
- **The service lives in the app's process.** It holds no vault and no secret. Only the authentication
  screen opens a session, for one request.
- **Stored without encryption, app-private:** the non-secret index (title, username, URL, entry id,
  vault fingerprint) and the app associations (package, certificate digest, fingerprint, entry id).
  Nothing else is shared with other apps.

## Threats

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| **Phishing domain** (`paypaI.com`, `paypal.com.evil.net`, `example.net` for `example.com`) | Shared Rust host policy after IDNA encoding: exact host, or a subdomain of a registrable saved host. Parents, siblings, look-alikes and public suffixes never match. | Manual search can still pick any entry; choosing one for a page it isn't saved for asks first ("Fill anyway?"). |
| **Public suffix / shared hosting** (`github.io`, `co.uk`) | An entry on a public suffix matches only itself. | The compiled-in PSL ages until the next release. |
| **Downgrade and ports** | An https entry is never offered to an http page. An entry saved with a non-default port is never offered, because Android reports only a host. | Local-development entries on ports must be filled by manual search. |
| **Malicious app claims a web domain** (sets `webDomain` on its own views) | The claim is ignored unless the package is a pinned browser with the pinned certificate. Such an app is identified by package and certificate and gets only its own associations. | Browsers not pinned yet fall back to manual search. |
| **Fake browser package** (same name, other signer, or same signer, other name) | The pin requires package **and** certificate digest. | The pinned certificate is Chrome stable's; other channels need their own verified pins. |
| **Browser spoofing the page** | A compromised pinned browser could report any host. Out of scope: the browser is trusted for this. | — |
| **Fake or similar package for a native app** | Associations bind package name **and** signing certificate; a different signer or a similar name is a different app. A changed certificate drops the match. | A legitimately re-signed app must be associated again. |
| **Stale index** (entry changed or deleted, vault rebuilt) | After unlock the entry is looked up in the opened vault; a missing entry, a URL that no longer matches, or a vanished association fills nothing. The index is refreshed from every session Autofill opens. | A suggestion can be shown for a while after its entry changed; filling it is refused. |
| **Swapped vault** (another database at the same file) | The index carries the vault fingerprint. A mismatch before the prompt, or after unlock, reveals nothing. The biometric record also checks the fingerprint (ADR 0010). | Fingerprints are root-group-UUID based; two vaults made from one template share it (the same limitation as the app). |
| **Service hijack** (another app binds or invokes it) | `BIND_AUTOFILL_SERVICE` limits binding to the system. The authentication activity is not exported and is reachable only through a `PendingIntent` held by the system. Its extras name an origin and an entry, never a secret. | — |
| **Authentication screen spoofing or capture** | `FLAG_SECURE`, `noHistory`, `excludeFromRecents`; finishes at once. The screen names the site or app it fills for. | A fully compromised device can overlay anything. |
| **Biometric bypass** | The fingerprint is requested only after a tap on a suggestion. It releases only the password component (strong biometrics, per-operation, enrolment-bound; ADR 0010). A vault that needs a key file still reads it. A rejected setup is invalidated. | — |
| **Key file unavailable** | The key file is read afresh each time; if it can't be read, nothing is filled and the person is sent to the app. | — |
| **Process death between request and tap** | The pending intent carries only ids; a killed process cancels it and the tap does nothing. A recreated authentication screen starts from the beginning. No secret is cached. | — |
| **Interrupted save** | If the vault is damaged and a save journal exists, Autofill refuses and sends the person to the app; it never restores. | — |
| **Accessibility services** | The password field exposes only masked text; revealed values are never in the tree. The filled value reaches the target view through the framework, as with any Autofill. | A malicious accessibility service on the device sees the target app's fields. |
| **Many requests** | One session per authentication, always closed; the core's session cap is respected and failures are shown, not crashed on. 30 consecutive fills held memory flat on the device. | — |
| **Logs** | Release builds log nothing. Debug builds log durations only (never domains, usernames, titles or package names). | — |
| **Index at rest** | Titles, usernames and URLs sit unencrypted in app-private, backup-excluded storage. | Root or a full-disk attacker reads them. A password is never in it. |
| **Package inventory** | `QUERY_ALL_PACKAGES` is not declared; `<queries>` names only pinned browsers. The caller's certificate is read from the system-supplied calling package. | New pinned browsers need a manifest entry. |
| **Saving without consent** | `SaveInfo` only starts a system prompt; Vazie Keep then shows its own confirm screen, and nothing is written before the person picks create or update and unlocks. A setting turns offers off. | — |
| **Wrong entry updated** | Candidates come from the shared host policy for the verified origin; several matches or a form without a username show a chooser with nothing preselected. Preview shows host, username and masked password only. | A person can still choose the wrong one of several. |
| **Password in transit to the save screen** | In-process `SaveHandoff`: random token, TTL, capacity, wiped on use. Never in an Intent, `SavedStateHandle` or the index. | Process memory holds a `String` while the form values are read. |
| **Malicious app saved as a native app** | A save from an app records a pending association (no certificate). The certificate is bound at the first verified fill; existing bound approvals are never downgraded. | Until then a different-signed app of the same package name can't exist on one device, but a package uninstalled and reinstalled with another signer before first fill would bind that signer. |
| **App claims a web domain at save** | Ignored, as for fill: the entry is saved against the package. | — |
| **Signing key rotation** | Lineage (`getSigningCertificateHistory`) vouches for an approved older certificate; otherwise the fill is refused and the person re-selects the entry. | — |
| **Duplicate or unchanged saves** | Same password: nothing written. Update writes KDBX history. | — |
| **Own screens** | The service ignores requests from Vazie Keep's own package, so the master password is never offered for saving. | — |

## What is never offered automatically

- an entry saved on a parent domain of the page, a sibling, or a different registrable domain;
- an entry for a page reported by a browser we don't pin, or by an app that merely claims a domain;
- an entry for a native app without an approved association for its package and certificate;
- an entry on a custom port, or an https entry on an http page;
- a phone field;
- a saved password for a form that is not a credential form (OTP, search, card).

All but the last two remain reachable through explicit search.

## Verification

- Unit: `FormParserTest`, `WebOriginTest`, `CredentialMatcherTest` (the real core's policy),
  `AutofillCoordinatorTest` (pinned browser, fake browser, claiming app, certificate change),
  `AutofillIndexStoreTest`, `AutofillAuthViewModelTest` (biometric, key file, wrong password, cancel,
  stale entry, swapped vault, journal, associations) and `AutofillRealCoreTest` (a synthetic KDBX end
  to end).
- Instrumented on the device: service and activity declarations, secure window, dataset result,
  cancel, wrong password, search without unlock, changed password, deleted entry, changed URL, vault
  switch.
- By hand on the Galaxy A17 (Android 16): Chrome 154 against a local page and real sites, a debug host
  app (with and without a claimed web domain, with association), key-file vault, cancel, 30 fills.
- **Not verified:** Samsung Internet (not installed), WebView, Chrome in incognito, fingerprint inside
  Autofill (it needs a finger; see the final report).

### M11.1 (SaveRequest) verification, Galaxy A17 SM-A175F, Android 16, 2026-10-04

- Host: gradle `check assembleDebug`, cargo `fmt`/`clippy -D warnings`/`test`, `scripts/check.sh all` and
  `scripts/test.sh all` pass.
- `connectedDebugAndroidTest` on the A17: 44 cases, 42 pass. The two others
  (`SafProviderDeviceTest`) are skipped by an assumption because no vault was picked by hand; the runner
  reports a skip as a failure.
- Covered on the device by those tests: save written only after confirmation and then indexed, cancel
  writes nothing, external change blocks saving, no `QUERY_ALL_PACKAGES` declared, save screen private.
- `dumpsys package` on the installed build lists no package-inventory permission.
- **Not done on the device:** the Chrome save/update, registration and password-change flows by hand, fingerprint
  inside the save screen, TalkBack, non-inline dropdown, incognito, WebView, timing of
  confirmation → completed. These need the person's finger or a scripted Chrome session and stay open.
