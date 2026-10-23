# ADR 0021: One-time codes (TOTP)

Status: accepted (2026-10-06)

## Context

`Totp` is a Keep+ feature (`PlusFeature::Totp`, see `product/KEEP_PLUS.md`). Vaults made by KeePassXC,
KeePass 2 and Strongbox already carry the secret in the entry; Keep has to read it, count the time, and
let a person add one, without giving up the rules that protect secrets (ADR 0008, 0011) or turning the
free tier into a trial (ADR 0020).

## Decisions

**1. The code setup is an ordinary custom field.** KeePassXC's `otp` field (an `otpauth://totp/…` link,
protected) is the format Keep reads and writes. Read-only compatibility: KeeOtp (`key=…&step=…`), KeePass 2
`TimeOtp-*` fields and KeePassXC's older `TOTP Seed` / `TOTP Settings`. Nothing new is stored in the file,
so the vault stays usable in every other KeePass app, and a person who lets Keep+ lapse loses nothing.

**2. One implementation, in the Rust core** (`keep-core::totp`). RFC 6238 over HMAC-SHA1/256/512, 6–8 digits,
periods 1–3600 s, and the Steam encoder (5 characters). Checked against the RFC 6238 vectors for all three
hashes. Platforms never see the secret: the core returns the code and the seconds left
(`VaultSessionHandle::totp_code`), and `has_totp` on the entry summary says whether there is one. A setup that
cannot be read (unknown algorithm, bad key) is `has_totp = false` and shows as the plain field it is.

**3. Adding a code is validated by the core, whether typed, pasted or scanned.** QR codes are read on the device
(ZXing on Android, the system detector on iPhone; camera only while scanning, or an image picked from the
gallery) and the text goes through the same path as a pasted link. `totp_normalize` takes a pasted link or a bare Base32 key
(spaces, dashes, lower case and padding are forgiven), refuses anything that cannot be a code
(`hotp`, bad digits or period, unknown algorithm), and returns the canonical link, which the editor stores as a
protected `otp` field. Adding the same code again replaces the field.

**4. Keep+ gates showing the code, never the data, and the core enforces it.** Both `totp_code` and
`totp_normalize` take the app's current `Access` and refuse with `NotEntitled` before looking at the entry, so a
new screen cannot forget the check and a free account learns nothing about whether a setup is valid. Without
Keep+ the entry shows the `otp` field as it always did and a short Keep+ card. The "Add one-time code" helper
and the QR scanner are part of Keep+; typing an `otp` custom field by hand stays free. This is a product
gate, not a security boundary: the client holds the lease.

**5. The code is treated like a revealed secret.** It is shown while the entry screen is in the
foreground and is dropped (not hidden — removed from memory) when the screen stops, the vault locks or the
session closes. Copying uses the usual clipboard timer and sensitive flag. Accessibility does not read the
digits aloud by default; the card announces the time left.

## Not decided here

HOTP, `otpauth-migration://` exports from Google Authenticator (recognised and refused with a message),
and TOTP in AutoFill (the code is not filled, only the password; see ADR 0013/0016).
