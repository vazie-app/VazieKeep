# Real-browser tests

These load the **built** extension into Chrome for Testing and drive it: the interface, local test pages, a virtual
WebAuthn authenticator, the host in the offscreen document. They are not part of `pnpm run check` because they need a
browser binary and a dev dependency that is not in the lockfile.

```bash
npm i --no-save playwright-core            # or pnpm add -D playwright-core in a scratch copy
npx playwright-core install chromium       # Chrome for Testing, if you have none (or set KEEP_CHROME)
node tests/e2e/run.mjs                     # all; or: node tests/e2e/run.mjs site
```

`run.mjs` makes a **development** build with a throwaway QA lease key (`tests/e2e/.qa-key.pem`, ignored by git), so
Keep+ features can be tested with a lease signed here. Production builds trust only the keys in
`shared/keep-lease/production-keys.txt`. The development build also opens the page overlays to the test driver and
exposes a small hook on the host (`__keepTest`) to age the idle clock.

Vaults are made in the origin-private file system, so no file picker is needed. What each file covers:

| File                      | Covers                                                                                                                                                                                                         |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `vault.mjs`               | the host API: edit, search, history, attachments, one-time codes, import, locking, the conflict check                                                                                                          |
| `site.mjs`                | the page side: autofill, saving and updating, never-for-this-site, the generator, one-time codes, passkeys (with signature checks), conditional sign-in, the popup's fill and the shortcut, the phishing guard |
| `quick-unlock.mjs`        | quick unlock with a PRF-capable virtual authenticator                                                                                                                                                          |
| `locks-and-clipboard.mjs` | auto-lock when idle and when Keep is closed; the clipboard is cleared only if it still holds what Keep put there                                                                                               |
