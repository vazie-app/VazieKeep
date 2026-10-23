# Vazie Keep for Chromium browsers

The whole of Vazie Keep in a browser extension: your KeePass file, opened where you keep it, with autofill, passkeys,
one-time codes, editing, import, history, attachments, several vaults, quick unlock and the Vazie Account. Chrome,
Edge, Brave, Vivaldi, Opera and other Chromium browsers (Manifest V3, Chrome 120 or newer).

How it is put together: [ADR 0022](../../docs/adr/0022-browser-extension-product.md) (and
[ADR 0003](../../docs/adr/0003-browser-extension-architecture.md)); what it defends:
[EXTENSION_THREAT_MODEL](../../docs/security/EXTENSION_THREAT_MODEL.md).

## Build and try it

```bash
../../scripts/build-core.sh wasm        # the Rust core as WebAssembly
pnpm install
pnpm build                               # dist/chromium
```

A production build needs the backend addresses, which are not in git: set `VAZIE_ACCOUNT_ORIGIN`, `VAZIE_KEEP_ORIGIN`
and `VAZIE_SITE_ORIGIN` in the environment (CI: repository secrets) or in a git-ignored `local.properties` next to
`package.json`, one `NAME=https://…` per line. `pnpm dev` falls back to a backend on this computer.

Open `chrome://extensions`, turn on **Developer mode**, **Load unpacked**, pick `dist/chromium`. Then:

1. Press the toolbar button. **Open a vault file** (or **Create a new vault**) opens a tab, because a file picker would
   close the small popup. Pick a `.kdbx`; the browser asks for permission to read and change it.
2. Unlock. The vault stays open (in the extension's offscreen page) until it auto-locks, you lock it, or the device
   locks. Open a sign-in page, click the Vazie Keep button in a login field, pick an entry.
3. The toolbar popup, the full tab and the side panel (Settings → Open in the side panel) are the same app.

`pnpm package` makes `dist/vazie-keep-<version>-chromium.zip`. `pnpm dev` makes a development build.

## Layout

| Path             | What                                                                                                                              |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `src/core`       | `KeepCore` facade over the WASM package; `KeepVaultSession`                                                                       |
| `src/host`       | the host (offscreen document): vault list, files and safe save, auto-lock, account, autofill matching, passkeys, icons, clipboard |
| `src/background` | the service worker: keeps the host alive, alarms, idle, shortcuts, menus, toolbar                                                 |
| `src/ui`         | the Preact app (popup, tab, side panel)                                                                                           |
| `src/content`    | the page side: form detection and menu/banner (`page.ts`), the passkey bridge, and the main-world wrapper (`passkeys-main.ts`)    |
| `src/shared`     | the typed RPC, DTOs, strings (English and Russian)                                                                                |
| `tests`          | unit tests (`pnpm test`) and `tests/e2e` (real Chromium)                                                                          |

## Checks

```bash
pnpm run check        # format, lint, types, unit tests
pnpm run build
```

The real-browser tests need Chrome for Testing (for example Playwright's `chromium`) and `playwright-core`;
see `tests/e2e/README.md`.
