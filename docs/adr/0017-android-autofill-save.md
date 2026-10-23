# ADR 0017: Android Autofill save requests, and dropping QUERY_ALL_PACKAGES

- Status: Accepted
- Date: 2026-10-04
- Builds on [ADR 0015](0015-android-safe-save.md), [ADR 0016](0016-android-autofill.md);
  supersedes ADR 0016's use of `QUERY_ALL_PACKAGES`
- Threat model: [ANDROID_AUTOFILL_THREAT_MODEL](../security/ANDROID_AUTOFILL_THREAT_MODEL.md)

## Context

M11 fills credentials. M11.1 lets Vazie Keep **save** them: after a sign-in, registration or password
change, Android asks whether to save, and Vazie Keep creates or updates an entry. The rule above the
rest: **nothing is written without an explicit confirmation on Vazie Keep's own screen**, and the write
is the ordinary M10 safe save, not a second path.

## Decision

### Flow

```
FillResponse + SaveInfo ─▶ person submits ─▶ system prompt "Save to Vazie Keep?"  ─ Save ─▶
VazieAutofillService.onSaveRequest: parse values, decide origin, plan ─▶ SaveHandoff (in-process token)
  ─▶ IntentSender ─▶ AutofillSaveActivity (secure, noHistory, not exported)
       confirm (create / update / choose among matches) ─▶ unlock (fingerprint after confirm, or password; key file re-read)
       ─▶ SaveExecutor ─▶ JournaledOverwriteWriter (M10) ─▶ index rebuild ─▶ session closed
```

- **SaveInfo only for real credential forms.** `FormParser` returns a `FormKind` (Login, Registration,
  PasswordChange, Unsupported). Required ids: the password (new password for registration and change)
  plus the username when present. OTP, phone, search and card fields never qualify. Apps get
  `FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`. Vazie Keep's own screens are skipped entirely (found on device:
  its master-password field otherwise offered to save itself).
- **No secret in an Intent.** The submitted password lives in `SaveHandoff` (random token, 10 min TTL,
  capacity 4, wiped on take/discard/expiry); the `PendingIntent` carries only the token.
- **Plan:** `SavePlanner` compares the submitted username with entries matching the same origin through
  the shared core policy. One match: offer update (preselected); several: a chooser, nothing
  preselected, plus "create new"; none: create. No username on the form (password change): the
  chooser lists all matches.
- **Execute:** create goes to the root group with title from host or app label and URL = canonical
  origin (`scheme://host`, no path, no scheme downgrade). Update preserves title, URL, notes, tags and
  custom fields; the core's `updateEntry` pushes the old password into KDBX history. The same password
  changes nothing and says so.
- **Locked vault:** the same `VaultUnlocker` as the fill screen; the fingerprint prompt opens only after
  the person confirmed; cancelling falls back to the master password; the key file is read again and is
  never replaced by the fingerprint.
- **Refusals:** vault changed on disk, journal needing recovery, read-only grant, expired token — each
  with a clear message, nothing written.
- **Setting:** "Offer to save passwords" (default on) in Settings → Autofill.
- After a save the Autofill index is rebuilt from the session just used.

### Identity without QUERY_ALL_PACKAGES

ADR 0016 declared `QUERY_ALL_PACKAGES` to read the signing certificate of whichever app is asking.
That permission needs a Play declaration and exposes a package inventory, so it was tested for removal.

**Experiment (Galaxy A17, Android 16, targetSdk 37; debug probe logging, removed from release):**

1. Without the permission and without `<queries>`, `getPackageInfo(<client>, GET_SIGNING_CERTIFICATES)`
   from the service threw `NameNotFoundException` for Chrome and the host test app.
2. Adding `<queries><package android:name="com.android.chrome"/></queries>` made Chrome visible to the
   service (certificate readable and equal to the pin).
3. In `AutofillAuthActivity` launched from a dataset, `callingPackage` was the client app (Chrome or the
   host app) and its certificate was readable (the system exposes the caller).
4. `AutofillSaveActivity` is started by an `IntentSender` from the system prompt and has no calling
   package.

**Result: the permission is removed.** Web origins are believed only from pinned browsers whose package
is declared in `<queries>` (today Chrome; the list must stay in step with `TrustedBrowsers`). Native
apps are matched before unlock by package and fingerprint, and the certificate is verified in the auth
activity against the verified caller (`PlatformCallerVerifier`). The pinned-browser check is repeated
there as well. Reproduce: build debug, `adb logcat | grep -E "certificate lookup|callingPackage"` while
requesting a fill from Chrome and from the host app; remove the `<queries>` entry to see step 1 again.

### Associations for native apps saved without a caller

Because the save screen can't verify a caller, a credential saved from a native app records a
**pending association** (package, empty certificate, vault fingerprint, entry). The first fill, whose
auth activity does verify the caller, binds the certificate (trust on first use). An existing bound
approval is never downgraded to pending. A browser package is never recorded. A fill with an
unexpected certificate is refused unless the new certificate's lineage
(`SigningInfo.getSigningCertificateHistory`) vouches for the approved one; otherwise the person must
choose the entry again (manual re-association). Browser pins accept any certificate in a pin's set, so
rotation is an app update that adds a digest.

## Consequences

- Play Console: no `QUERY_ALL_PACKAGES` declaration is needed, so no justification document is written.
- Only Chrome stable's page claims are trusted; other browsers still get manual search and no save.
- A malicious app that gets a pending association for a package it doesn't own could bind its own
  certificate first only if the person saved a credential for that package; the window is documented in
  the threat model.
- Saving on a custom port stores no port (Android reports only a host).

## Alternatives considered

- **Keep QUERY_ALL_PACKAGES:** simplest, but a broad permission and a Play declaration for a check that
  the system already does for the auth activity.
- **Separate save writer:** rejected; one safe-save path.
- **Auto-save without confirmation:** never.
- **Password in the PendingIntent extras:** the Intent can be logged and cached; the in-process handoff
  can't.
