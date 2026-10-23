# Keep+ entitlement threat model

Scope: the Vazie Account, the signed lease and its use in the Android and iOS apps
([ADR 0020](../adr/0020-keep-plus-entitlements.md)). The question: **can anyone get Keep+ they did not
get from the backend, and can the account or the backend ever put a vault at risk?**

Vaults are out of reach of everything here by construction: the account has no key relation to a vault,
no vault data is sent, and a Keep+ failure never blocks opening one.

## Trust boundaries

- The **backend** decides who has Keep+ and signs the statement. Its signing key is the root of trust.
- The **apps** hold public keys, a session token and the last lease. They trust a lease only after the
  Rust core verified its signature against a key they ship with.
- The **device clock** is untrusted; the backend's clock (inside the lease) is the reference.
- The **device itself**, once rooted or jailbroken, is out of scope for absolute guarantees.

## Threats

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| **Editing the stored entitlement** (flip a preference or file to "plus") | There is no local flag. Keep+ exists only as a lease whose Ed25519 signature is checked on every load; a changed tier, flag or date invalidates it (shared vectors: `tier_tampered`, `lifetime_tampered`). The cached record sits in Keystore-encrypted storage / the Keychain. | A rooted device can patch the app. |
| **Forging a lease** | Needs the backend's private key. Apps pin `alg=EdDSA` (so `none` and `HS256` are refused), look up `kid` in their own list (an unknown id is refused), use `verify_strict`, and check the payload only after the signature. | Compromise of the signing key (below). |
| **Using another account's lease** | The lease `subject` must equal the signed-in account's id, or it is discarded. | — |
| **Replaying an old valid lease** | Allowed until its `lease_expires_at`: that *is* the offline policy. After expiry it is useless. A lease carries no secret. | A person can keep a valid lease for up to 30 days after revocation; chosen. |
| **Clock rollback** (set the date back to extend a lease) | The app remembers the latest time seen; a clock behind it, or behind the lease's `issued_at`, switches Keep+ off until an online check (core rule `evaluate`; tests in Rust, Android and iOS). Free keeps working. | A reinstall plus a rolled-back clock within an unexpired lease is not possible: a reinstall removes the lease. A rooted device can still rewrite the remembered time. |
| **Clock set forward** | Only shortens a lease; harmless. | — |
| **Stealing the session token** | Stored in Keystore-encrypted storage (Android) / Keychain, this-device-only (iOS); never logged; the backend stores only its SHA-256, sessions are revocable and expire. The token authorises only account calls, never a vault. | Root or device compromise reads it. |
| **MITM of account traffic** | HTTPS only in release builds (Android's default cleartext ban; iOS ATS). No certificate pinning (not part of the project's policy). The lease is signed, so a MITM can withhold or delay a lease but cannot forge one. Debug builds allow cleartext to `localhost`/`127.0.0.1` (Android) and the local network (iOS) for development. | A MITM can deny a refresh. |
| **Backend unavailable** | A valid cached lease keeps Keep+ on; an invalid or expired one leaves Free, with no lock-out and no fatal screen. The app message says the vault is still available. | Keep+ lapses after 30 days with no connection. |
| **Account takeover** (someone else signs in with the email) | One-time code: short expiry, single use, attempt and rate limits, never logged (backend). Takeover yields Keep+ of that account only, never a vault. | Email account compromise. |
| **Signing-key compromise** | Rotate: add the new public key to the apps' list and ship; switch the backend's `kid`; later remove the old `kid` from the apps. Leases signed by the old key expire within 30 days anyway. | Until apps update, a stolen key can mint leases accepted by old builds. |
| **Reinstall** | Android: Keystore key and app data are removed with the app; signed out. iOS: the Keychain item may survive an uninstall, but is tagged with a per-installation id kept in the sandbox, so it is deleted on first read and never trusted. | — |
| **Rooted / jailbroken device** | Not promised. The signature still stops a plain preference edit; a person who patches the app binary can unlock Keep+ features on their own device. | Accepted. |
| **Account deletion** | Backend erases the account and grants; the device's lease expires by itself and falls back to Free. Vaults stay. | Up to 30 days of Keep+ on the device. |
| **Account/email change** | The lease subject is the account UUID, never the email. | — |
| **Logs and telemetry** | Release builds do not log tokens, emails, leases or vault data. The backend does not log OTPs, tokens or leases. Requests carry no device identifier, no vault id and no counts. | — |
| **Debug shortcuts in a release** | The dev sign-in button is `BuildConfig.DEV_SIGN_IN` / `VazieDevSignIn`, false in release; the QA public key is trusted in debug builds only; release trusts `production-keys.txt`. A release audit checks for both. | A mis-built release (see audit script). |
| **Admin tooling reachable from an app** | Operator endpoints live under `/internal/v1`, behind a separate token and not on the Keep host; the apps have no code for them. | — |

## Verification

- Rust: `keep-core` unit tests (clock rules) and `tests/keep_lease_vectors.rs`.
- Cross-implementation: `shared/test-vectors/keep-lease/vectors.json` is signed by OpenSSL; the Rust core
  verifies it, the backend's Kotlin signer reproduces its tokens byte for byte, Android
  (`RustKeepLeaseTest`) and iOS (`LeaseVectorTests`) verify the same file.
- Backend: lease service, HTTP and Postgres tests (revision trigger, audit, V19 migration over V18).
- Apps: service tests with the real core for offline, expiry, rollback, tampering, account switching,
  logout and refresh cadence.
