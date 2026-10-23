// Auto-lock (idle, when Keep is closed) and the clipboard clearing.
import { inOffscreen, launch, newVault, reporter, rpc, rpcSource } from './lib.mjs';

const t = reporter();
const run = await launch();
const admin = await run.context.newPage();
run.watch(admin);
await admin.addInitScript(rpcSource);
await admin.goto(`chrome-extension://${run.id}/ui/tab.html`);
await admin.waitForTimeout(1500);
const cdp = await run.context.newCDPSession(admin);
await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true });
await admin.bringToFront();
await newVault(admin, 'Locky', 'pw');
const locked = async () => await inOffscreen(run.context, admin, 'globalThis.__keepTest.locked()');

await rpc(admin, 'updateSettings', { autoLock: '5' });
await inOffscreen(run.context, admin, 'globalThis.__keepTest.ageActivity(4*60*1000)');
t.ok('still unlocked after 4 minutes idle', !(await locked()));
await inOffscreen(run.context, admin, 'globalThis.__keepTest.ageActivity(2*60*1000)');
t.ok('locked after 6 minutes idle', await locked());

await rpc(admin, 'updateSettings', { autoLock: 'onClose' });
const state = await rpc(admin, 'state');
await rpc(admin, 'unlock', {
  vaultId: state.vaults[0].id,
  password: new TextEncoder().encode('pw'),
});
const popup = await run.context.newPage();
await popup.goto(`chrome-extension://${run.id}/ui/popup.html`);
await popup.waitForTimeout(1500);
const probe = await run.context.newPage();
await probe.goto('about:blank');
await rpc(admin, 'updateSettings', { clipboardClear: 15 });
const digest = (text) =>
  admin.evaluate(
    async (s) =>
      Array.from(
        new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s))),
        (b) => b.toString(16).padStart(2, '0'),
      ).join(''),
    text,
  );
await admin.evaluate(() => navigator.clipboard.writeText('secret-123'));
await rpc(admin, 'clipboardCopied', await digest('secret-123'));
await admin.waitForTimeout(17000);
t.ok(
  'what Keep copied is cleared after the time',
  (await admin.evaluate(() => navigator.clipboard.readText())) === ' ',
);
await admin.evaluate(() => navigator.clipboard.writeText('secret-456'));
await rpc(admin, 'clipboardCopied', await digest('secret-456'));
await admin.evaluate(() => navigator.clipboard.writeText('my own text'));
await admin.waitForTimeout(17000);
t.ok(
  'something else copied since is left alone',
  (await admin.evaluate(() => navigator.clipboard.readText())) === 'my own text',
);

t.ok(
  'still unlocked while a Keep page is open',
  !(await inOffscreen(run.context, probe, 'globalThis.__keepTest.locked()')),
);
for (const page of run.context.pages()) if (page.url().includes('/ui/')) await page.close();
await probe.waitForTimeout(12000);
t.ok(
  'locked about ten seconds after the last Keep page closed',
  await inOffscreen(run.context, probe, 'globalThis.__keepTest.locked()'),
);
t.done(run.logs);
await run.context.close();
