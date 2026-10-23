# ADR 0016: Android Autofill

- Status: Accepted; the `QUERY_ALL_PACKAGES` part and the save deferral are superseded by [ADR 0017](0017-android-autofill-save.md)
- Date: 2026-10-04
- Builds on [ADR 0006](0006-vault-session-handles.md), [ADR 0010](0010-android-biometric-unlock.md),
  [ADR 0015](0015-android-safe-save.md); compare [ADR 0013](0013-ios-autofill-architecture.md)
- Threat model: [ANDROID_AUTOFILL_THREAT_MODEL](../security/ANDROID_AUTOFILL_THREAT_MODEL.md)

## Context

M11 lets Vazie Keep fill logins in other apps and in Chrome through the Android Autofill
framework, so a person never has to copy a username and password by hand. One rule sits above the
rest: **a secret leaves the vault only after the person has explicitly chosen one credential, and
then only that one.**

The iPhone extension is not copied. Android has its own framework, its own identity problem (an app
can name any web domain for its own views) and no sandboxed extension process: the service lives in
the app's process.

## Research: what the framework gives us (targetSdk 37, checked on Android 16)

- `AutofillService.onFillRequest(FillRequest, CancellationSignal, FillCallback)` receives an
  `AssistStructure` (`request.fillContexts`) and, from API 30, an `InlineSuggestionsRequest`.
  `onSaveRequest` is only called if a `SaveInfo` was attached.
- A `FillResponse` carries `Dataset`s. A dataset can require authentication:
  `Dataset.Builder.setAuthentication(IntentSender)` with `setValue(id, null)` placeholders. The
  framework shows only its presentation, and on a tap starts our activity. The activity returns a
  `Dataset` with real values under `AutofillManager.EXTRA_AUTHENTICATION_RESULT` and the framework
  fills at once. **This is the mechanism that keeps every password out of the response.**
- `Dataset.Builder(RemoteViews)` is deprecated since API 33 in favour of `Presentations`, but
  still works and is the only form on API 26–32 (minSdk is 26), so it is used with a targeted
  `@Suppress`.
- Inline suggestions (API 30+) use `androidx.autofill` (`InlineSuggestionUi`, `UiVersions`). The
  Samsung keyboard shows them in its suggestion strip. The dropdown RemoteViews presentation is the
  fallback.
- The authentication `PendingIntent` must be mutable (the system adds extras) but ours names an
  explicit component, so nothing can redirect it.
- A page's host comes from `ViewNode.getWebDomain()` (scheme from `getWebScheme()`, API 28). It is
  **reported by the app or browser and not verified by the framework**: any app can call
  `ViewStructure.setWebDomain` on its own views. The package of the requesting activity
  (`AssistStructure.getActivityComponent()`) is set by the system and can't be faked.
- `AutofillManager.hasEnabledAutofillServices()` is true only if the *calling app's* service is the
  enabled one. The documented way to switch is `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE` with
  `package:<id>`.
- Package visibility (Android 11+): `PackageManager.getPackageInfo` of an arbitrary client app threw
  `NameNotFoundException`. Reading its signing certificate needs `QUERY_ALL_PACKAGES`.
- Chrome 154 offers third-party Autofill only after "Autofill with another service" is chosen in its
  settings (Passwords and autofill → Autofill settings). After that it sends a request per focused
  login field, with the web domain, to the system's service. Compatibility mode (URL bar
  heuristics) is not used.

## Decision

### Architecture

```
VazieAutofillService          system-facing: parse, ask, build. No vault, no secrets.
  AssistStructureAdapter ──▶ FormParser            (what is the login form)
  AutofillCoordinator    ──▶ origin decision       (who is asking: page or app)
  CredentialMatcher      ──▶ core match_host       (which entries may be offered)
  FillResponseFactory    ──▶ auth-needed datasets  (title + username, no values)
              │ tap
              ▼
AutofillAuthActivity (secure, no history, not in recents)
  AutofillAuthViewModel ── BiometricMaterialSource (fingerprint)  ── VaultOpener ── Rust session
                                                                      (read file + key file now)
              │  one reveal, session closed
              ▼
  Dataset(username, password)  ──▶  framework fills
```

`AutofillIndex` (non-secret) feeds the service before any unlock.

### A short-lived session, never the app's

The service must work with the app closed and after a process restart, so it never relies on the UI
session. `VaultOpener` (extracted from `VaultRepository`) reads the vault file and key file afresh and
opens **one Rust session for the request**; the authentication screen closes it before anything else
can use it, whatever the outcome. The vault's bytes are not kept between requests. The app's
`unlockWithMaterial`/`BiometricUnlock` are split so Autofill reuses the same Keystore code
(`obtainMaterial`) without opening the application session. Opening and revealing run to completion
in a non-cancellable block so a cancelled screen can't leak a session slot.

### Origin: whom to believe

- **Web:** the page's host is believed **only from a pinned browser**: package name *and* signing
  certificate digest must match. Today that is Chrome stable
  (`f0fd6c5b…db83`, read from the signed APK on the test device with `apksigner`). A
  browser's own screens (no page) get nothing. Browsers whose certificate hasn't been verified the
  same way (Samsung Internet wasn't installed; Chrome Beta/Dev, Firefox, Brave, Edge…) are treated as
  ordinary apps, so they offer only manual search. Adding one is a verified one-line change.
- **App claiming a web domain:** ignored. The app is identified by package and certificate, and its
  claim is flagged (`ignoredWebClaim`). Tested with a host app that sets the domain of a saved entry.
- **Native app:** package name **plus signing certificate digest** (`GET_SIGNING_CERTIFICATES`).

### Matching

- Web: the shared Rust `match_host` (the iPhone's policy: exact host, or a subdomain of a registrable
  saved host; never a parent, sibling, look-alike or public suffix; IPs only exactly). On top: an https
  entry isn't offered to an http page, and an entry saved with a non-default port is never offered,
  because Android reports only a host.
- Hosts are IDNA (punycode) so Unicode can't pose as ASCII.
- Apps: only through a **user-approved association** `package + certificate ↔ entry`. After a manual
  pick for a native app a switch ("use this entry for this app from now on", on by default) records it.
  No fuzzy or automatic guessing, and similar package names are different apps.
- The decision is **made again after unlock** against the vault's current entry (URL changed, entry
  deleted, association removed), and a mismatch fills nothing.

### Index

Only what a list row already shows: entry id, plain title, plain username, plain URL, and the vault
fingerprint. Built from the core's summaries (never passwords, notes or custom fields; protected
values and Recycle Bin entries are skipped). Rebuilt after unlock, after every edit, and from the
session Autofill itself opened; cleared when another vault is chosen. Stored in
`noBackupFilesDir/autofill_index.bin` (a small binary file, written atomically; not DataStore, which
is for settings). The file is **not encrypted**: titles, usernames and URLs are readable by anything
that can read the app's private storage (root). That is the iPhone's trade-off too (its identities sit
in a system store). Associations are in `autofill_associations.bin`, pruned to existing entries.

### Authentication

- Tapping a suggestion starts `AutofillAuthActivity` (not exported; reachable only through the
  `PendingIntent`). `FLAG_SECURE`, `noHistory`, `excludeFromRecents`, its own task affinity, finishes
  at once with the result.
- **No fingerprint prompt merely because suggestions were shown.** The prompt opens after the tap.
  If biometric unlock is set up it opens straight away; cancelling falls back to the master password
  with no error. The password field behaves as in the app: a wrong password clears it and refocuses it.
- **Key files:** the key file is read again at every request. The fingerprint protects only the
  password component (ADR 0010) and never replaces the key file. If the key file is unreadable the
  screen says "Open Vazie Keep and choose the key file again". A key-file-only vault without biometric
  setup uses the manual screen.
- If the vault file is damaged **and** a cut-off save's journal exists (ADR 0015), Autofill refuses
  and sends the person to the app; it never restores.
- If the vault on disk isn't the indexed one (fingerprint differs), nothing is revealed.

### No match, and manual search

When nothing matches, one dataset, "Search Vazie Keep", opens the same screen in search mode: the index
list, no unlock needed to browse, no passwords in it. Picking an entry for a *web page* it isn't saved
for asks first ("Not saved for this site… Fill anyway?"); that is the phishing guard for deliberate
picks.

### Save requests

**Done in M11.1 (ADR 0017).** Originally deferred: no `SaveInfo` is attached, so the framework never asks to save, and
`onSaveRequest` only acknowledges. Adding it needs a duplicate check against the index and a write
through the M10 safe-save path.

### Service

Declared with `android.permission.BIND_AUTOFILL_SERVICE` (only the system can bind), exported because
the system must. Requests run on a service-owned coroutine scope cancelled in `onDestroy` and on the
request's `CancellationSignal`; nothing blocks the binder thread (the response needs only the index
file).

### Settings

An "Autofill" row shows Enabled / Disabled / Not available, refreshed when the screen resumes, with
"Enable Autofill" opening the system's documented request screen. The suggestion icon follows the
chosen launcher icon.

## Consequences

- Suggestions need no unlock; secrets need an explicit tap and an unlock.
- `QUERY_ALL_PACKAGES` was declared in M11 and removed in M11.1 (ADR 0017): the caller's certificate is
  read in the auth activity from the system-supplied calling package, and pinned browsers are listed
  in `<queries>`.
- Only Chrome's page claims are trusted at first; other browsers fall back to manual search.
- Entries saved on a custom port, or on a bare IP with a port, are not offered automatically.

## Alternatives considered

- **Opening the vault in the service:** requires a visible session; breaks after process restart.
- **Sharing the app's session:** the app's auto-lock would silently decide whether Autofill works, and
  Autofill would extend the lifetime of that session.
- **Datasets that already contain values:** would put every password of every match in the response.
- **Trusting `webDomain` from any package:** lets any app impersonate any site.
- **Trusting a package name alone for native apps:** a lookalike package with another signer would
  inherit the association.
- **Digital Asset Links verification:** needs a network request, and the app has no network permission.
- **Compatibility mode for old browsers:** URL-bar scraping is exactly the spoofable signal we avoid.
- **Index in DataStore or SharedPreferences:** both are for small settings; the index can be thousands
  of rows.
