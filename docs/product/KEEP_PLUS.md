# Keep and Keep+

What is free, what needs Keep+, and the rules the apps follow about it. Decided with the product owner;
the mechanism is [ADR 0020](../adr/0020-keep-plus-entitlements.md).

## Free

Everything that protects a person's passwords, with no account and no network:

- opening, editing, creating and saving KDBX vaults, including safe save and conflict handling;
- master password, key files, biometric unlock, auto-lock, clipboard clearing;
- AutoFill, passkeys, and saving credentials from AutoFill;
- the password generator and the passphrase generator, and every generator option;
- an icon for a group or an entry, chosen from a set of 27 and found by typing its name. Each is one of the
  standard KeePass icons, so KeePassXC and other apps show their own drawing of the same thing; an entry without
  an icon of its own shows the first letter of its title. "Get website icons" goes over the vault and, after the person
  agrees, asks each site an entry names for its icon — straight from the device, with no service in between — and
  keeps the images in the vault file, where other KeePass apps show them too. A vault's contents lead to a network
  request only here and, when the person turns on "Website icons for new entries" in Settings (off by default),
  on saving an entry — then that one site is asked for its icon. An entry whose site gave nothing gets a
  built-in icon by its address, offline (`keep-core/src/icons.rs`), as imported entries do; an entry without an
  address is left alone, and an icon someone chose is never replaced;
- import from other password managers: CSV with a header row (browsers, KeePassXC, LastPass and the like),
  1Password (`.1pux` and CSV) and Bitwarden (JSON and CSV). Encrypted exports are refused. The entries are added to
  the open vault, folders become groups, nothing is merged or replaced, and attached files are not imported;
- **one** vault. (A second one is the first Keep+ feature people will meet.)

Free is not a trial and is never degraded: no ads, no nagging, no "locked" vaults.

## Keep+

| Feature | In the code (`PlusFeature`) |
| --- | --- |
| Any number of vaults, switching between them | `MultiVault` |
| TOTP codes | `Totp` |
| Password health | `PasswordHealth` |
| Breach monitoring | `BreachMonitoring` |
| Entry history view | `HistoryUI` |
| Writing attachments | `AttachmentWrite` |
| Premium themes | `PremiumThemes` |

TOTP in practice ([ADR 0021](../adr/0021-totp-codes.md)): the secret lives in the entry's `otp` field, as in KeePassXC.
Keep+ shows the code with a countdown and offers "Add one-time code"; without Keep+ the field is an ordinary
protected field and nothing is hidden or removed.

Attachments in practice: everyone can see a vault's attachments, save them to a file and remove them; adding one
needs Keep+, and the core enforces it (`add_attachment` takes the app's `Access` and refuses with `NotEntitled`). An
attachment may be up to 64 MB; identical files are stored once, and a binary no entry or history item uses any more
is dropped from the file.

History in practice: every edit keeps the previous version inside the file, for everyone, as KeePass does (the
vault's `HistoryMaxItems`, 10 by default). Keep+ lists the versions newest first with what each has different from the
entry now, shows a version's fields and secrets, and goes back to one; the entry as it was goes to the history first,
so going back loses nothing. The core enforces it (`history`, `history_entry`, `reveal_history` and `restore_history`
take the app's `Access` and refuse with `NotEntitled`). Without Keep+ an entry shows how many earlier versions it has
and nothing more.

Several vaults in practice (the owner's rule of 2026-10-06; Android `data/VaultPolicy.kt`, iPhone `VaultLibrary.swift`): without Keep+
the app keeps one vault and no second one can be opened or created. An account that already has several — Keep+ ran
out, or they were added before the rule — is asked once to choose its **main vault**. The main vault is edited as
before; the others open for reading only (no edits in the app, none from Autofill). The choice is made once and
stays: it is not asked again and cannot be changed, also after Keep+ comes and goes. With Keep+ every vault is edited
and more can be added. Two cases the rule did not name, decided in code: a main vault that was removed from the list
is asked for again, and while a lapsed lease has not been checked with the backend nothing is asked and the first
vault stands in for a main one that was never chosen. On iPhone Face ID unlock is
set up per vault (each has its own Keychain item, put aside while another vault is chosen); AutoFill still fills
from the chosen vault only.

A feature is added to this list only by a product decision. Nothing that protects data may be added: a
person who lets Keep+ lapse keeps every vault they have and can open and read all of them, and edit the main one.
What lapses is the *extra* (for example, editing several vaults).

## The account

- The **Vazie Account** is optional and exists only for Keep+: to hold the entitlement, to buy later, and
  to restore Keep+ on another device. Vaults, AutoFill, passkeys, biometrics, key files and the
  generators never need it, and the apps never ask for it at first launch, when opening a vault, in
  AutoFill, or after an update.
- The account has **no cryptographic relation to any vault**. Nothing about a vault, an entry, a URL or
  a vault count is ever sent to Vazie. There is no Vazie cloud for vault data.
- Keep+ belongs to the *account*, not to a device, platform, installation or vault.

## Devices and platforms

One Keep+ works on Android and iOS together, on **any number of personal devices**. There are no device
slots and no device fingerprint for licensing.

## Lifetime and subscriptions

- A subscription has an end date; a lifetime has none (`valid_until` is absent, never a far-future date).
- Both are the same entitlement; the apps cannot tell how Keep+ was obtained, only that it is held.

## Offline

The backend is the source of truth. The app holds a **signed lease** and verifies it locally.

- A lease is valid for at most **3 days** after the last successful check, and never past the end of
  what was paid for.
- Within it, Keep+ works with no connection at all. After it, Keep+ features switch off until the app
  reaches the backend; Free carries on and nothing is locked.
- A failing or absent backend never affects Free, and never affects a valid lease.
- Setting the device clock back does not extend a lease (see the threat model).

States shown to the person: Free, Keep+ (checked recently), Keep+ offline, Keep+ Lifetime, and Keep+
needs checking.

## Signing out and switching accounts

Signing out removes the account's session and cached lease from the device and turns Keep+ off there.
Vaults, key files and biometric setups are not touched. The app says so first: *Signing out disables
Keep+ on this device. Your vaults won't be deleted.* Signing in as a different account drops the first
account's lease; an entitlement never moves between accounts.

## Prices and checkout

The backend is the only source of a price; the apps show what `GET /v1/prices` (Keep host) says and
send only a plan name when buying.

| Plan | Price | Access |
| --- | --- | --- |
| Month | 149 ₽ | one calendar month, no automatic renewal |
| Year | 1 490 ₽ | one calendar year, no automatic renewal |
| Lifetime, the first 555 | 1 990 ₽ | no end |
| Lifetime, after that | 3 490 ₽ | no end |

- A client asks for `KEEP_PLUS_LIFETIME`; the backend decides which row it buys and writes it on the
  payment, so what is charged is what was shown when the payment was created. A payment in progress
  holds its early place for two hours; an abandoned, test or refunded payment does not count.
- Paying again before the end extends the current access instead of replacing it.
- **Every Vazie subscription is bought on the site** (vazie.app): Keep+ at `/keep/plus`. The apps show the prices
  from the backend and a button, «Оформить на сайте», that opens the site with the plan (`?plan=month|year|lifetime`).
  The person signs in there with the same e-mail address, accepts the offer and pays; the app asks the backend for the
  new lease when they come back, up to six times, five seconds apart. Nothing is paid or confirmed inside the app.
- Provider: Tochka Bank (since 2026-10-07, the only one). A payment is confirmed only by the bank (a signed webhook, then
  the payment is read back from the bank), never by a page or an app. The webhook comes about a minute after the person
  pays, so six checks five seconds apart can miss it: the app says so and keeps a «check payment» button, and every launch
  asks again.
- The year is shown with twelve months at the monthly price crossed out next to it; the backend derives that number
  from the catalogue, so the site and the apps always agree.
- Automatic renewal is not on sale and not promised anywhere; the plan for it is in the backend repository.
- Keep+ never touches Vazie VPN: it is a different right on the same account, and a Keep+ purchase is
  invisible to VPN clients.

## Not decided here

Refunds and chargebacks (the backend can refund; what the apps say about it is open), Apple and
Google in-app purchase, and a "restore purchases" button (the account already restores Keep+ on any
device).
