# ADR 0003: Browser extension architecture

- Status: Accepted
- Date: 2026-09-30

## Context

The extension must work in Chrome, Chromium, Brave, Edge and other Chromium browsers, and later
in Firefox. A browser is the most hostile environment the product runs in:

- Pages are attacker-controlled.
- Content scripts share the DOM with those pages.
- MV3 service workers are killed at any time.
- The extension auto-updates from a store.

## Decision

1. **Manifest V3, TypeScript, Vite, Preact.** Preact was chosen over React because it has zero
   runtime dependencies (~4 KB), which means a smaller supply-chain surface inside a password
   manager, while keeping the React component model. Vite builds each entry point (popup,
   service worker) to fixed file names, and a Vite plugin generates `manifest.json` from
   `src/manifest.ts`.
2. **The directory is `apps/browser-extension`, and targets are a build parameter.**
   `createManifest(target)` exists so that a `firefox` target can later differ where it must
   (background `scripts` instead of `service_worker`, `browser_specific_settings`). Each build
   goes to `dist/<target>`.
3. **The core runs as WASM, in extension pages only.** `keep-core-wasm` is loaded by
   `src/core/keepCore.ts`, which is the only module that imports it. Parsing, key derivation and
   decryption will run in WASM inside an extension page (popup now; an offscreen document or
   side panel for long sessions). Only rendered values reach JS, and nothing sensitive is ported
   to TypeScript.
4. **The service worker holds state, not secrets.** It keeps the lock state and auto-lock timers.
   It must assume it can be terminated at any moment, so a locked vault is the default state
   after a restart.
5. **Content scripts will never receive the vault.** When autofill arrives, a content script
   gets one credential for the current, user-confirmed origin, sent from an extension page after
   origin matching in the core. It never gets a list or a key.
6. **Messaging is typed and sender-checked.** `src/shared/messages.ts` defines the protocol, and
   the service worker ignores messages from other extensions (`sender.id` check).
7. **The CSP allows WASM and nothing else.** `script-src 'self' 'wasm-unsafe-eval'; object-src
   'self'`: no remote code, no `eval`.
8. **Permissions stay minimal.** None today. Each future permission (`storage`, `alarms`, `idle`,
   `activeTab`, host permissions for autofill) needs a PR justification.

## Where the vault comes from (open question)

The extension has no filesystem. Candidates for the next phase:

- **Native messaging to the desktop app,** which already has the file and the unlocked session.
  This is the KeePassXC-Browser model and keeps the vault outside the browser.
- **A user-selected file** through the File System Access API. This is Chromium-only and needs
  re-selection or stored handles.

This ADR does not choose between them. Either way, decryption stays in Rust.

Update 2026-09-30 (ADR 0005): `keep-core` can open KDBX 4, but the WASM package still exports
only `coreVersion()`. Exporting `openVault()` now would return every decrypted entry into the
JS heap. It waits for the handle-based API and the vault-source decision.

Update 2026-09-30 (ADR 0006): the handle-based WASM session API now exists. JS gets a handle,
summaries and a single `Uint8Array` secret on request. The vault-source question and the popup
UI are still open. Sessions must live in an extension page or offscreen document, never in the
service worker.

Update 2026-10-18 ([ADR 0022](0022-browser-extension-product.md)): the vault source is the File System Access API, the
session lives in an offscreen document, and the extension is a full client with autofill, passkeys, editing and Keep+.
The open question above is closed; native messaging was not needed.

## Consequences

- Building the extension requires Rust and `wasm-bindgen-cli` (pinned to the crate version).
- The popup shows "Core: connected (version)" only when the WASM module actually instantiated.
- Firefox support is additive: a new target in `manifest.ts`, a `platform/` shim if needed, and a
  CI matrix entry.
