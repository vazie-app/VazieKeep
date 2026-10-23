// Quick unlock with a virtual authenticator that supports the PRF extension.
import { launch, newVault, reporter, rpc, rpcSource } from './lib.mjs';

const t = reporter();
const run = await launch();
const page = await run.context.newPage();
run.watch(page);
await page.addInitScript(rpcSource);
await page.goto(`chrome-extension://${run.id}/ui/tab.html`);
await page.waitForTimeout(1500);
const cdp = await run.context.newCDPSession(page);
await cdp.send('WebAuthn.enable');
await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true });
await page.bringToFront();
const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
  options: {
    protocol: 'ctap2',
    ctap2Version: 'ctap2_1',
    transport: 'internal',
    hasResidentKey: true,
    hasUserVerification: true,
    isUserVerified: true,
    hasPrf: true,
    automaticPresenceSimulation: true,
  },
});
await newVault(page, 'Quick', 'pw-quick');
await page.reload();
await page.waitForTimeout(1500);
await page.getByRole('button', { name: 'Settings' }).click();
await page.getByText('This vault').click();
await page.locator('label.switch', { hasText: 'Quick unlock' }).click();
await page.waitForTimeout(2500);
t.ok(
  'quick unlock is turned on with the authenticator',
  (await rpc(page, 'state')).vaults[0].quickUnlock === true,
);
await rpc(page, 'lock');
await page.reload();
await page.waitForTimeout(1500);
await page.getByRole('button', { name: 'Quick unlock' }).click();
await page.waitForTimeout(3500);
t.ok('the vault opens without the password', (await rpc(page, 'state')).session !== null);
await rpc(page, 'lock');
await cdp.send('WebAuthn.clearCredentials', { authenticatorId });
await page.reload();
await page.waitForTimeout(1500);
await page.getByRole('button', { name: 'Quick unlock' }).click();
await page.waitForTimeout(3000);
t.ok(
  'without the passkey it fails and the vault stays locked',
  (await rpc(page, 'state')).session === null,
);
t.done(run.logs);
await run.context.close();
