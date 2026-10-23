# ADR 0020: Keep+ entitlements and the signed offline lease

Status: accepted (2026-10-05)

## Context

Keep needs a paid tier that is checked on the device, works without a connection for weeks, cannot be
switched on by editing a file on the phone, and never makes the account a dependency of the vault.
A Vazie backend already exists (`VazieBackend`): accounts with email one-time-code sign-in, opaque
revocable hashed sessions, `entitlement_grants`, an operator audit log, and billing through a payment provider.

## Decisions

**1. The account is optional and unrelated to vaults.** Keep+ belongs to the Vazie Account (an immutable
UUID, not the email). Free needs no account; no vault data is ever sent.

**2. The backend is the source of truth, and Keep+ is an entitlement, not a table of its own.** A new
`EntitlementKey.KEEP_PLUS` is held through the existing `EntitlementGrant`s (source `ADMINISTRATIVE` in
M1; `PURCHASE`/`SUBSCRIPTION` when payments land). A lifetime is a grant with no `valid_until`, not a
far-future date. This reuses authorization (`ADR-003`: checks read entitlements), operator audit, account
deletion and the payment-settlement path, instead of a second system that would have to be kept in
step. A per-account `revision` (trigger-maintained, V19) records that the grants changed.

*Departure from the milestone text*, which preferred separate `keep_entitlements` tables: the existing
model already has every property asked for (tier, source, window, lifetime, revoke, audit), and a second
model would have needed its own resolver, eraser and settlement writer.

**3. The client verifies a signed lease offline.** `GET /v1/entitlement` (Keep host) returns a compact
**JWS**, `EdDSA` over **Ed25519** (RFC 7515/8037), never `{"plus": true}`. Standard format, so any JOSE
library can read it and nothing in the signature scheme is ours. The header carries `kid`; apps trust a
short list of public keys (current and previous), which is how keys rotate.

**4. The lease clock.** `lease_expires_at = min(now + 3 days, entitlement_expires_at)`; a lifetime is
`now + 3 days`; a free account gets a signed free lease. Revocation is by expiry only: a revoked
account gets no new lease and existing ones run out (the product's chosen policy), so there is no
revocation list and no replay defence beyond expiry.

**5. One implementation of the rules, in the Rust core.** `keep-core::entitlement` verifies the token
(signature before the payload is parsed, `alg` pinned to `EdDSA`, `kid` looked up, never taken on
faith), validates it, and computes access: `Free`, `PlusActive`, `PlusOfflineGrace`, `PlusLifetime`,
`PlusExpired`, plus whether the app must go online. Android and iOS wrap it through UniFFI; the
feature table (`PlusFeature`/`can_use`) is there too. Verification uses `ed25519-dalek` 3 (verify only,
`verify_strict`); `serde`/`serde_json` became normal dependencies of the core.

**6. Clock rollback.** The app stores the latest device time it has seen. A device clock behind that
reading (beyond 5 minutes), or behind the lease's own `issued_at`, switches Keep+ off until a successful
check, which resets the reference to the backend's time. Free is untouched. This is deliberately not a
promise against a rooted device.

**7. Sessions.** The existing sessions are opaque, hashed, revocable and expiring; the apps use them as
they are. A separate refresh-token scheme was not added: the lease, not the session, is what lets the
app work offline, and a rejected session only means "sign in again" while the lease keeps running.

**8. Storage.** Android: the session and lease are one AES-GCM record under a non-exportable
Android Keystore key (no user authentication, so it works in the background), in the no-backup directory.
iOS: a Keychain item, `AfterFirstUnlockThisDeviceOnly`, not synced, tagged with a per-installation id, so
an item that survives an uninstall is deleted rather than trusted.

**9. Keys and builds.** The signing key exists only in the backend's environment. Apps hold public keys:
release builds trust `shared/keep-lease/production-keys.txt` (empty until the production key exists, so
Keep+ stays off); debug builds also trust a QA key. A release build never trusts the QA key.

**10. Network isolation.** Account traffic is separate from vault operations and never awaited by them.
The app launches from its cached lease and refreshes in the background, at most once a day unless the
lease is invalid or ending within 7 days. Android declares `INTERNET`; iOS needs no declaration.

## Consequences

- Keep+ can be issued today only by an operator (`scripts/keep-plus.sh` in the backend repository), audited; payments plug in as new
  grant sources without touching the clients.
- A lease is a bearer statement: whoever holds a valid one has Keep+ until it expires. That is the same
  trust as the offline policy itself, and the lease contains no secret.
- Unlimited devices means no device record is needed for licensing; the backend's existing optional
  `devices` row (platform label only) is unrelated to it.
- The copy "no network permission / no account" is no longer true and was reworded to "works offline /
  account optional".

## Alternatives rejected

- A plain boolean from the server: editable on the device.
- A custom MAC or home-made token: not standard, needs a shared secret in the app.
- HS256: would put the signing secret in every app.
- Device-bound licences: contradict unlimited devices and add a fingerprint.
- Refresh tokens now: more backend surface for no offline benefit.
