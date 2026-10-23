# Browser extension threat model

What the Vazie Keep extension defends, against whom, and where it stops. Architecture:
[ADR 0003](../adr/0003-browser-extension-architecture.md) and [ADR 0022](../adr/0022-browser-extension-product.md).
Build-wide rules: [SECURITY_MODEL](SECURITY_MODEL.md).

## What is protected

The decrypted vault, the master password and the key file, a revealed password or one-time code, the passkey private
keys, the quick-unlock material, the Vazie session token. Of these only the **decrypted vault** and **quick-unlock
material** live long in memory, and only inside the Rust core in the offscreen document.

## Who attacks

| Attacker | What they can do | What stops them |
| --- | --- | --- |
| A web page (or an ad, or a script on it) | read and write its own DOM, call `navigator.credentials`, post window messages, build fake login forms, overlay or click-jack | Content scripts keep no vault. The page can ask only through `runtime.sendMessage`, and the host decides from `sender.url` (set by the browser), not from what the page says. The overlay is a **closed shadow root** and ignores untrusted (`isTrusted: false`) events. Fields that are hidden or invisible are never filled. |
| A look-alike site | ask for the entry of the real one | The match is the core's `match_host`: exact host or a subdomain of a registrable saved host, never a parent, sibling, look-alike, public suffix or other port; an https entry is not offered to http. Unicode hosts are compared as punycode. The host checks again when it hands secrets over (`fill` for an entry that does not match returns nothing). |
| A site that embeds another (iframes) | trick the person into filling a framed form | Entries are matched against the **frame's own origin**; a frame whose origin differs from the top page is marked, and nothing is filled until the person clicks an entry. |
| A site that asks for a passkey for another site | use a passkey it should not | The host accepts a relying-party id that is the page's host or a registrable parent of it (`match_host`), builds the client data from the trusted origin, and the core signs only for the site the passkey was made for. Cross-origin frames and insecure origins go to the browser. |
| Another extension | message our contexts | Every listener checks `sender.id`; the interface channel is a `BroadcastChannel` of the extension's origin, which other extensions' pages cannot join. |
| Someone with the unlocked browser profile on disk | read IndexedDB and the OPFS | They find vault **file handles**, the list of vaults, settings, the sealed session token and sealed quick-unlock material, five encrypted backups of each vault, and a non-extractable key. Never the master password, never decrypted data. The encrypted vault is the same file they could copy anyway. |
| Someone at the unlocked computer | open the popup | Auto-lock (idle minutes, or when Keep is closed), lock on device lock, the lock button, and the clipboard clearing narrow this; it is not a defence against a person who can use the unlocked browser. |
| Malware running as the person | read process memory, inject into the browser, keylog | Out of scope, as for every password manager. |
| A compromised store account or update | ship a malicious build | Out of scope here; mitigated by reproducible builds from tagged sources and a CSP without remote code. |

## Decisions worth knowing

- **The host, not the popup, holds the vault** (an offscreen document), so closing the popup does not lock and the
  service worker's death does not either. After a browser restart the vault is locked: there is nothing to restore it
  from. "Lock when Keep is closed" locks about ten seconds after the last Keep page closes.
- **No secret in a message the page could see.** Secrets go host → interface over a channel only extension pages can
  join, and host → content script only as the answer to that script's own request, for one entry, after a click.
- **The extension never submits a form** and never fills on page load.
- **Saving needs a click.** A submitted password is held in the host's memory for two minutes (until the next page of
  that tab can offer to save it), is dropped on lock, sign-in failure heuristics, "Not now" or "Never", and is never
  written anywhere before the person confirms.
- **Passkeys can always be declined**: "Use another way" runs the browser's own method with the original arguments.
- **Quick unlock is bound to an authenticator**, not to the disk: the key that seals the material is derived from the
  WebAuthn PRF output and exists only while the authenticator answers.
- **Website icons** are fetched only when the person asks (or turns on the setting), from the browser, with no
  credentials and no referrer, over https, capped in size.
- **Clipboard**: Keep writes only after a click and clears the clipboard after the chosen time **only if it still holds
  what Keep put there** (compared by SHA-256), using the offscreen document's `execCommand`.
- **Network**: the Vazie endpoints (account, Keep+ lease, prices, FAQ, links) and, on request, the sites' own icon URLs.
  Nothing about a vault, an entry, a URL or a vault count is sent anywhere.

## Known gaps

- JavaScript strings cannot be wiped. A password typed in the interface, an entry draft or a revealed value lives in the
  page's heap until it is collected; the interface keeps them only while a screen needs them (revealed values hide
  after 30 s). Where the core takes a secret it takes bytes and zeroes them.
- A page can read what Keep fills into its own fields, as with any autofill.
- The File System Access permission is the browser's: Keep cannot tell whether the file was changed by something other
  than a save (it can tell that it changed: that is the conflict check).
- Quick unlock is unavailable where the authenticator has no PRF (Windows Hello today).
- `BarcodeDetector` (QR from an image) is not in every browser.
