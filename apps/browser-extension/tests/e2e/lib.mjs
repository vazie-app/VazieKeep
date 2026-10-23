// Helpers for the real-browser tests: launch Chrome for Testing with the built extension, talk to the host.
import { chromium } from 'playwright-core';
import { existsSync, mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = fileURLToPath(new URL('../..', import.meta.url));
export const EXT = join(ROOT, 'dist', 'chromium');

export function chromePath() {
  if (process.env.KEEP_CHROME) return process.env.KEEP_CHROME;
  const caches = [
    join(homedir(), 'Library', 'Caches', 'ms-playwright'),
    join(homedir(), '.cache', 'ms-playwright'),
  ];
  for (const cache of caches) {
    if (!existsSync(cache)) continue;
    for (const dir of readdirSync(cache)
      .filter((d) => d.startsWith('chromium-'))
      .sort()
      .reverse()) {
      const mac = join(
        cache,
        dir,
        'chrome-mac-arm64',
        'Google Chrome for Testing.app',
        'Contents',
        'MacOS',
        'Google Chrome for Testing',
      );
      const macIntel = join(
        cache,
        dir,
        'chrome-mac-x64',
        'Google Chrome for Testing.app',
        'Contents',
        'MacOS',
        'Google Chrome for Testing',
      );
      const linux = join(cache, dir, 'chrome-linux', 'chrome');
      for (const candidate of [mac, macIntel, linux]) if (existsSync(candidate)) return candidate;
    }
  }
  throw new Error('No Chrome for Testing found: set KEEP_CHROME to a Chromium binary');
}

export async function launch({
  headless = true,
  dir = mkdtempSync(join(tmpdir(), 'keep-e2e-')),
  deviceScaleFactor = 1,
} = {}) {
  const context = await chromium.launchPersistentContext(dir, {
    executablePath: chromePath(),
    headless,
    args: [
      `--disable-extensions-except=${EXT}`,
      `--load-extension=${EXT}`,
      ...(headless ? ['--headless=new'] : []),
      '--no-first-run',
      '--no-default-browser-check',
    ],
    viewport: { width: 1280, height: 800 },
    deviceScaleFactor,
  });
  let worker = context.serviceWorkers()[0];
  worker ??= await context.waitForEvent('serviceworker', { timeout: 15000 });
  const id = new URL(worker.url()).host;
  const logs = [];
  const watch = (page) => {
    page.on('console', (m) => {
      if (['error', 'warning'].includes(m.type())) logs.push(`[${m.type()}] ${m.text()}`);
    });
    page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`));
  };
  context.on('page', watch);
  return { context, id, logs, watch, dir };
}

/** Calls the host from a page: install with `page.addInitScript(rpcSource)`, call with `rpc(page, 'state')`. */
export const rpcSource = `
window.__rpc = (() => {
  const channel = new BroadcastChannel('vazie-keep:host');
  const me = crypto.randomUUID();
  const pending = new Map();
  let n = 0;
  channel.onmessage = (e) => {
    const m = e.data;
    if (m.kind === 'res' && m.to === me) {
      const w = pending.get(m.id); pending.delete(m.id);
      if (!w) return;
      if (m.ok) w.resolve(m.value); else w.reject(new Error(m.error + ' :: ' + m.detail));
    }
  };
  return (method, ...args) => new Promise((resolve, reject) => {
    n += 1; pending.set(n, { resolve, reject });
    channel.postMessage({ kind: 'req', id: n, from: me, method, args });
    setTimeout(() => { if (pending.delete(n)) reject(new Error('timeout:' + method)); }, 60000);
  });
})();
`;

export const rpc = (page, method, ...args) =>
  page.evaluate(([m, a]) => window.__rpc(m, ...a), [method, args]);

/** Evaluates an expression in the offscreen document (the host) through the DevTools protocol. */
export async function inOffscreen(context, anyPage, expression) {
  const cdp = await context.newCDPSession(anyPage);
  const { targetInfos } = await cdp.send('Target.getTargets');
  const target = targetInfos.find((t) => t.url.includes('offscreen/index.html'));
  if (!target) throw new Error('no offscreen target');
  const { sessionId } = await cdp.send('Target.attachToTarget', {
    targetId: target.targetId,
    flatten: false,
  });
  return await new Promise((resolve, reject) => {
    const onMsg = ({ sessionId: sid, message }) => {
      if (sid !== sessionId) return;
      const data = JSON.parse(message);
      if (data.id === 1) {
        cdp.off('Target.receivedMessageFromTarget', onMsg);
        if (data.error) reject(new Error(JSON.stringify(data.error)));
        else resolve(data.result.result.value ?? data.result.result);
      }
    };
    cdp.on('Target.receivedMessageFromTarget', onMsg);
    cdp.send('Target.sendMessageToTarget', {
      sessionId,
      message: JSON.stringify({
        id: 1,
        method: 'Runtime.evaluate',
        params: { expression, returnByValue: true, awaitPromise: true },
      }),
    });
  });
}

/** Creates a vault in the origin-private file system, so no file picker is needed. */
export async function newVault(page, name, password) {
  return await page.evaluate(
    async ([vaultName, secret]) => {
      const root = await navigator.storage.getDirectory();
      const handle = await root.getFileHandle(`${vaultName}.kdbx`, { create: true });
      return await window.__rpc('createVault', {
        handle,
        name: vaultName,
        level: 'balanced',
        password: new TextEncoder().encode(secret),
      });
    },
    [name, password],
  );
}

export function entryDraft(title, username, password, url, extra = {}) {
  return {
    title,
    username,
    password,
    url,
    notes: '',
    customFields: [],
    tags: [],
    favorite: false,
    expiresAt: null,
    ...extra,
  };
}

/** Writes a Keep+ lease account into the profile (needs a development build trusting the QA key), then restarts. */
export async function seedAccount(page, token) {
  await page.evaluate(async (leaseToken) => {
    const db = await new Promise((res, rej) => {
      const r = indexedDB.open('vazie-keep', 1);
      r.onsuccess = () => res(r.result);
      r.onerror = rej;
    });
    const tx = (store, mode, fn) =>
      new Promise((res, rej) => {
        const t = db.transaction(store, mode);
        const r = fn(t.objectStore(store));
        t.oncomplete = () => res(r.result);
        t.onerror = rej;
      });
    let key = await tx('kv', 'readonly', (s) => s.get('deviceKey'));
    if (!key) {
      key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
        'encrypt',
        'decrypt',
      ]);
      await tx('kv', 'readwrite', (s) => s.put(key, 'deviceKey'));
    }
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = new Uint8Array(
      await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        new TextEncoder().encode('qa-session'),
      ),
    );
    await tx('kv', 'readwrite', (s) =>
      s.put(
        {
          accountId: 'acc-1',
          email: 'qa@example.com',
          session: { iv, data },
          leaseToken,
          lastObservedDeviceTime: 0,
          lastRefreshAt: Math.floor(Date.now() / 1000),
        },
        'account',
      ),
    );
  }, token);
}

/** A browser with Keep+ switched on by a lease signed with the QA key. */
export async function launchWithPlus(lease, options = {}) {
  let run = await launch(options);
  const first = await run.context.newPage();
  await first.goto(`chrome-extension://${run.id}/ui/tab.html`);
  await first.waitForTimeout(1200);
  await seedAccount(first, lease);
  const { dir } = run;
  await run.context.close();
  run = await launch({ ...options, dir });
  const admin = await run.context.newPage();
  run.watch(admin);
  await admin.addInitScript(rpcSource);
  await admin.goto(`chrome-extension://${run.id}/ui/tab.html`);
  await admin.waitForTimeout(1500);
  return { ...run, admin };
}

export function reporter() {
  let passed = 0;
  let failed = 0;
  return {
    ok(name, condition, detail = '') {
      if (condition) {
        passed += 1;
        console.log('ok  ', name);
      } else {
        failed += 1;
        console.log('FAIL', name, detail);
        process.exitCode = 1;
      }
    },
    done(logs) {
      console.log(`${passed} passed, ${failed} failed`);
      const noise = (logs ?? []).filter((l) => !l.includes('pubKeyCredParams'));
      console.log(noise.length ? `console:\n${noise.join('\n')}` : 'no console errors');
      if (noise.length) process.exitCode = 1;
    },
  };
}

export function vector(path) {
  return new Uint8Array(readFileSync(join(ROOT, '..', '..', 'shared', 'test-vectors', path)));
}
