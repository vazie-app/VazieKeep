# ADR 0022: The browser extension as a full client

- Status: Accepted
- Date: 2026-10-18

## Context

[ADR 0003](0003-browser-extension-architecture.md) fixed the frame of the extension (Manifest V3, the core as
WebAssembly, no secrets in content scripts) and left two things open: where the vault comes from, and what the
extension actually does. The brief for this step was an extension for Chromium browsers that has **every function of
the app**, not only autofill: open, create, edit and save vaults, key files, quick unlock, the generator, import,
history, attachments, one-time codes, passkeys, several vaults, the Vazie Account and Keep+, settings, help.

Constraints the browser adds:

- there is no filesystem path, only handles the person grants;
- a service worker is killed at any moment, and a popup lives only while it is open;
- extension messaging is JSON, so it cannot carry bytes;
- an offscreen document sees only a sliver of the extension API (`runtime.id`, `sendMessage`, `onMessage`, `connect`,
  `onConnect`, `getURL`: no `storage`, no `getManifest`);
- `navigator.credentials` belongs to the browser, and a web page talks to it directly.

## Decision

**1. The vault lives in an offscreen document, the host.** One `offscreen/index.html` loads the WASM core and owns every
open session, the vault list, saving, auto-lock, the clipboard timer, the Keep+ account and the answers to content
scripts. The service worker only keeps the host alive, listens for alarms, idle, shortcuts and menus, and sets the
toolbar. Closing the popup does not close the vault.

**2. The interface is one Preact app on three surfaces**: the toolbar popup (380×580), a full tab and the side panel.
It is a thin client: every action is a call to the host. A page at ≥ 860 px shows groups, list and entry side by side.

**3. UI ↔ host is a typed RPC over `BroadcastChannel`** (`src/shared/api.ts`, `rpc.ts`). Unlike `runtime.sendMessage`,
it uses structured clone, so file handles, key files, attachments and icons cross as they are. It is origin-scoped:
only extension pages can reach it; web pages and content scripts cannot. A page also opens a `runtime` port named
`keep-ui:*` so the host knows when Keep is open ("lock when Keep is closed").

**4. The vault source is the File System Access API.** A vault is a `FileSystemFileHandle` the person picked, kept in
IndexedDB with its key-file handle. Picking and permission prompts need a click, so they happen in the interface
(`ensureAccess` runs inside the Unlock click); the host then reads and writes through the same handle. Pickers
close a popup, so those steps open a tab. Native messaging to a desktop app is not needed and not chosen.

**5. Saving is the phones' safe save** (ADR 0014/0015): refuse when the file changed since it was read (SHA-256 of what
was read), keep the previous bytes in the origin-private file system (five copies) and the new bytes as a write-ahead
copy, write (the browser swaps the file in on `close()`), read back and compare, put the old bytes back on a mismatch.
Failures leave the session dirty and offer Try again / Save a copy / Reload / Overwrite on purpose / Allow editing.
A damaged file can be restored from the last copy. Every edit saves at once.

**6. Quick unlock is the WebAuthn PRF extension.** After a master-password unlock the host seals the KeePass password
component (`UnlockMaterial`) with a key derived by HKDF from a passkey's PRF output; unlocking asks the authenticator
(Touch ID, Windows Hello where it supports PRF, a security key) and the host opens the vault with the material. It is
offered only where the browser can derive a secret; the master password always works.

**7. Autofill is a content script plus the host.** The script (`content/page.js`, isolated world, all frames) finds
login, registration and password-change forms (a port of the Android `FormParser`), draws a button and a menu in a
closed shadow root, and asks the host. The host matches by the frame's own origin from `sender.url` with the core's
`match_host` (the phones' rule, plus ports), never by what the page claims; it reveals one entry's secrets to that
frame only after a click. Saving after sign-in follows `SavePlanner`: update one, choose among several, or create;
nothing is written without the banner's confirmation. Cross-origin frames are marked and never filled on load.

**8. Passkeys are intercepted in the page's world** (`content/passkeys-main.js`, `world: MAIN`): `credentials.create` and
`get` are wrapped. A request goes to the isolated script and the host; the person sees a prompt and can always choose
"another way", which calls the browser's own method untouched. The host checks that the relying-party id is the page's
host or a registrable parent (`match_host`), builds `clientDataJSON` itself from the trusted origin, and the core signs.
Conditional requests stay with the browser and add Keep's passkeys to the field's menu.

**9. Keep+ is the phones' mechanism.** Same API, same signed lease verified by the Rust core, same rules for several
vaults (`host/rules.ts` is a port of `VaultPolicy`). The session token is sealed with a non-extractable AES-GCM key in
IndexedDB. Prices, FAQ and links come from the backend only; the extension ships none of those texts.

**10. The WASM contract is the whole FFI contract**, as JSON in, JSON or bytes out, with the same error codes
(snake case). Secrets still cross one at a time, as `Uint8Array`, and inputs that carry secrets (a password, an entry
draft) are `&mut [u8]` and zeroed in WASM after parsing. The core got a way to be given a clock
(`keep_core::set_clock`), because a browser's WebAssembly has none and `SystemTime::now()` panics there.

**11. Permissions**, each with its reason: `offscreen` (the host), `alarms` and `idle` (timers and device lock),
`sidePanel`, `contextMenus` (fill and lock), `clipboardRead` and `clipboardWrite` (copy and clear only what Keep put
there), `unlimitedStorage` (backups of large vaults), `<all_urls>` host access (content scripts, website icons on
request, the Vazie endpoints). No `tabs`, no `activeTab`, no `storage`, no native messaging, no remote code.

## Consequences

- The extension works in Chrome, Edge, Brave, Vivaldi, Opera and other Chromium browsers with Manifest V3 and the
  offscreen API (Chrome 120+). Firefox needs a background page in place of the host and has no File System Access API.
- After a browser restart the browser forgets file permissions; Unlock asks again inside the click.
- Quick unlock exists only where the authenticator supports PRF. Windows Hello does not today; a security key does.
- There is no update check: stores update the extension, and no endpoint exists for sideloaded builds.
- QR codes in images are read with the browser's `BarcodeDetector` where there is one; otherwise the key is pasted.
- Debug builds (`pnpm run dev`) open the page overlays to the test driver and trust the QA lease key from
  `KEEP_QA_LEASE_KEYS`; production builds do neither (the constants are folded away).

## Alternatives

- **Hold the vault in the service worker.** Rejected: it is killed at any time and the vault would lock itself.
- **Hold it in the popup.** Rejected: autofill and saving would stop whenever the popup closed.
- **Native messaging to the desktop app** (the KeePassXC model). Not available (the desktop app is a skeleton) and it would
  make the extension depend on another install.
- **`runtime.sendMessage` for the interface.** Rejected: JSON only, so every file would be base64.
- **Biometric unlock by storing the material with a non-extractable key and a user-verification gate.** Rejected: the
  gate would be software only; PRF binds the key to the authenticator.
