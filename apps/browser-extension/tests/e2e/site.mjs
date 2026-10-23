// The page side against local test pages: autofill, saving, the generator, one-time codes, passkeys, the fill command.
import { createHash, createPublicKey, verify } from 'node:crypto';
import http from 'node:http';
import { entryDraft, launchWithPlus, newVault, reporter, rpc } from './lib.mjs';
import { lease, qaKey } from './qa.mjs';

const B64 =
  "const b64u=(b)=>btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'');";
const pages = {
  '/login':
    '<!doctype html><title>Login</title><form method="post" action="/welcome"><input name="user" autocomplete="username"><input name="pass" type="password" autocomplete="current-password"><button type="submit">Sign in</button></form>',
  '/welcome': '<!doctype html><title>Welcome</title><h1>Welcome back</h1>',
  '/signup':
    '<!doctype html><title>Signup</title><form method="post" action="/welcome"><input name="email" type="email" autocomplete="email"><input name="p1" type="password" autocomplete="new-password"><input name="p2" type="password" autocomplete="new-password"><button>Create</button></form>',
  '/otp':
    '<!doctype html><title>2FA</title><input id="code" name="otp" autocomplete="one-time-code" maxlength="6">',
  '/cond': `<!doctype html><title>Cond</title><input name="u" autocomplete="username webauthn"><script>${B64}window.results={};
navigator.credentials.get({mediation:'conditional',publicKey:{challenge:new Uint8Array(32).fill(5),rpId:location.hostname}}).then(c=>{window.results.cond={id:c.id,authData:b64u(c.response.authenticatorData),sig:b64u(c.response.signature),client:new TextDecoder().decode(c.response.clientDataJSON)}},e=>{window.results.cond={error:e.name}});</script>`,
  '/passkey': `<!doctype html><title>Passkey</title><button id="c">create</button><button id="g">get</button><script>${B64}window.results={};
document.getElementById('c').onclick=async()=>{try{const c=await navigator.credentials.create({publicKey:{rp:{name:'Local Test'},user:{id:new Uint8Array([1,2,3,4]),name:'tester',displayName:'Tester'},challenge:new Uint8Array(32).fill(7),pubKeyCredParams:[{type:'public-key',alg:-7},{type:'public-key',alg:-257}],authenticatorSelection:{residentKey:'required'}}});
window.results.create={id:c.id,type:c.type,inst:c instanceof PublicKeyCredential,att:c.response instanceof AuthenticatorAttestationResponse,clientData:new TextDecoder().decode(c.response.clientDataJSON),pub:b64u(c.response.getPublicKey()),alg:c.response.getPublicKeyAlgorithm(),authData:b64u(c.response.getAuthenticatorData())}}catch(e){window.results.create={error:e.name}}};
document.getElementById('g').onclick=async()=>{try{const c=await navigator.credentials.get({publicKey:{challenge:new Uint8Array(32).fill(9),rpId:location.hostname}});
window.results.get={id:c.id,resp:c.response instanceof AuthenticatorAssertionResponse,clientData:new TextDecoder().decode(c.response.clientDataJSON),authData:b64u(c.response.authenticatorData),sig:b64u(c.response.signature),user:b64u(c.response.userHandle)}}catch(e){window.results.get={error:e.name}}};</script>`,
  '/plain':
    '<!doctype html><form><input name="user" autocomplete="username"><input type="password" name="pass"></form>',
};
const server = http.createServer((req, res) => {
  res.setHeader('content-type', 'text/html; charset=utf-8');
  res.end(pages[req.url.split('?')[0]] ?? '<h1>404</h1>');
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const PORT = server.address().port;
const ORIGIN = `http://localhost:${PORT}`;

const t = reporter();
const run = await launchWithPlus(lease(qaKey()));
const { admin } = run;
const call = (method, ...args) => rpc(admin, method, ...args);
await newVault(admin, 'Site', 'pw-1');
let state = await call('state');
const root = state.session.info.rootGroupId;
const norm = await call('totpNormalize', 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP');
await call(
  'createEntry',
  root,
  entryDraft('Local site', 'tester@example.com', 'pw-secret', ORIGIN, {
    customFields: [{ name: 'otp', value: norm.uri, protected: true }],
  }),
);
await call(
  'createEntry',
  root,
  entryDraft('Other site', 'other', 'other-pw', 'https://other.example.org'),
);
await call('lock');

const site = await run.context.newPage();
run.watch(site);
const menu = () => site.locator('.card').innerText();
const open = async (path) => {
  await site.goto(`${ORIGIN}${path}`);
  await site.waitForTimeout(600);
};
const pick = async (selector) => {
  await site.locator(selector).click();
  await site.waitForTimeout(400);
  await site.locator('.icon').click();
  await site.waitForTimeout(500);
};

await open('/login');
await pick('input[name=user]');
t.ok('the button appears on a login field', (await site.locator('.icon').count()) === 1);
t.ok('a locked vault offers to unlock', (await menu()).includes('locked'));
await call('unlock', { vaultId: state.vaults[0].id, password: new TextEncoder().encode('pw-1') });
await site.keyboard.press('Escape');
await pick('input[name=pass]');
const listed = await menu();
t.ok(
  'the menu lists the matching entry only',
  listed.includes('Local site') && !listed.includes('Other site'),
  listed,
);
await site.locator('.card .item', { hasText: 'Local site' }).first().click();
await site.waitForTimeout(500);
t.ok(
  'username and password are filled',
  (await site.locator('input[name=user]').inputValue()) === 'tester@example.com' &&
    (await site.locator('input[name=pass]').inputValue()) === 'pw-secret',
);

const alike = await run.context.newPage();
await alike.goto(`http://127.0.0.1:${PORT}/login`);
await alike.waitForTimeout(600);
await alike.locator('input[name=pass]').click();
await alike.waitForTimeout(400);
await alike.locator('.icon').click();
await alike.waitForTimeout(500);
const none = await alike.locator('.card').innerText();
t.ok(
  'another origin is offered nothing',
  none.includes('No saved logins') && !none.includes('Local site'),
);
await alike.close();

const signIn = async (user, pass) => {
  await open('/login');
  await site.locator('input[name=user]').fill(user);
  await site.locator('input[name=pass]').fill(pass);
  await site.locator('button[type=submit]').click();
  await site.waitForURL('**/welcome');
  await site.waitForSelector('.card', { timeout: 6000 }).catch(() => undefined);
};
await signIn('brand-new', 'brand-new-pw');
t.ok('a save is offered after signing in', (await menu()).includes('Save to Vazie Keep'));
await site.locator('.btn.primary', { hasText: 'Save' }).click();
await site.waitForTimeout(1500);
t.ok(
  'the login is saved to the file',
  (await call('snapshot')).entries.some((e) => e.username.value === 'brand-new') &&
    !(await call('state')).session.dirty,
);
await signIn('brand-new', 'changed-pw');
t.ok('an update is offered for the same user', (await menu()).includes('Update in Vazie Keep'));
await site.locator('.btn.primary', { hasText: 'Update' }).click();
await site.waitForTimeout(1500);
const target = (await call('snapshot')).entries.find((e) => e.username.value === 'brand-new');
t.ok('the password is updated', (await call('reveal', target.id, 'Password')) === 'changed-pw');
await signIn('third', 'third-pw');
await site.locator('.btn.quiet', { hasText: 'Never' }).click();
await site.waitForTimeout(600);
t.ok(
  '"never for this site" is remembered',
  (await call('settings')).neverSave.includes('localhost'),
);
await call('updateSettings', { neverSave: [] });

await open('/signup');
await pick('input[name=p1]');
await site.locator('.card .item', { hasText: 'Generate' }).click();
await site.waitForTimeout(500);
const p1 = await site.locator('input[name=p1]').inputValue();
t.ok(
  'a generated password fills both fields',
  p1.length === 20 && p1 === (await site.locator('input[name=p2]').inputValue()),
);

await open('/otp');
await pick('#code');
await site.locator('.card .item', { hasText: 'one-time code' }).first().click();
await site.waitForTimeout(500);
t.ok('a one-time code is filled', /^\d{6}$/.test(await site.locator('#code').inputValue()));

await open('/passkey');
await site.locator('#c').click();
await site.waitForSelector('.card[role=dialog]', { timeout: 5000 });
t.ok(
  'creating a passkey asks first',
  (await site.locator('.card[role=dialog]').innerText()).includes('Create a passkey'),
);
await site.locator('.btn.primary').click();
await site.waitForFunction(() => window.results?.create, null, { timeout: 8000 });
const created = await site.evaluate(() => window.results.create);
t.ok(
  'the passkey is made and looks like the browser’s',
  !created.error &&
    created.type === 'public-key' &&
    created.inst &&
    created.att &&
    created.alg === -7,
  JSON.stringify(created),
);
const clientData = JSON.parse(created.clientData);
t.ok(
  'its client data names the page',
  clientData.type === 'webauthn.create' &&
    clientData.origin === ORIGIN &&
    clientData.challenge === Buffer.alloc(32, 7).toString('base64url'),
);
const authData = Buffer.from(created.authData, 'base64url');
t.ok(
  'its authenticator data is for this site, user present and verified',
  authData.subarray(0, 32).equals(createHash('sha256').update('localhost').digest()) &&
    (authData[32] & 0x45) === 0x45,
);
await site.locator('#g').click();
await site.waitForSelector('.card[role=dialog]', { timeout: 5000 });
await site.locator('.btn.primary').click();
await site.waitForFunction(() => window.results?.get, null, { timeout: 8000 });
const got = await site.evaluate(() => window.results.get);
const pub = createPublicKey({
  key: Buffer.from(created.pub, 'base64url'),
  format: 'der',
  type: 'spki',
});
const signed = Buffer.concat([
  Buffer.from(got.authData, 'base64url'),
  createHash('sha256').update(got.clientData).digest(),
]);
t.ok(
  'signing in returns an assertion that verifies with the public key',
  !got.error && got.resp && verify('sha256', signed, pub, Buffer.from(got.sig, 'base64url')),
);
t.ok('and the user handle', Buffer.from(got.user, 'base64url').equals(Buffer.from([1, 2, 3, 4])));
await open('/cond');
await pick('input[name=u]');
t.ok(
  'a conditional request gets the passkey in the field menu',
  (await menu()).includes('Passkeys'),
);
await site.locator('.card .item', { hasText: 'Local Test' }).first().click();
await site.waitForFunction(() => window.results?.cond, null, { timeout: 8000 });
const cond = await site.evaluate(() => window.results.cond);
const condSigned = Buffer.concat([
  Buffer.from(cond.authData, 'base64url'),
  createHash('sha256').update(cond.client).digest(),
]);
t.ok(
  'and it answers with a signature that verifies',
  !cond.error && verify('sha256', condSigned, pub, Buffer.from(cond.sig, 'base64url')),
);

await open('/plain');
const matches = await call('siteMatches', `${ORIGIN}/plain`);
t.ok(
  'the popup can list entries for the page',
  matches.some((m) => m.title === 'Local site') &&
    (await call('siteMatches', 'http://localhost.evil.test/')).length === 0,
);
const tabId = await admin.evaluate(
  async (url) => (await chrome.tabs.query({})).find((x) => x.url?.startsWith(url))?.id,
  ORIGIN,
);
const local = matches.find((m) => m.title === 'Local site');
await admin.evaluate(
  ([tab, entry]) =>
    chrome.tabs.sendMessage(tab, { channel: 'keep:page', cmd: 'fill', id: entry, withTotp: false }),
  [tabId, local.id],
);
await site.waitForTimeout(500);
t.ok(
  '"fill" from the popup fills the page',
  (await site.locator('input[name=pass]').inputValue()) === 'pw-secret',
);
await site.reload();
await site.waitForTimeout(600);
await admin.evaluate(
  (tab) => chrome.tabs.sendMessage(tab, { channel: 'keep:page', cmd: 'fillBest' }),
  tabId,
);
await site.waitForTimeout(600);
t.ok(
  'with several matches the shortcut opens the menu instead of guessing',
  (await site.locator('.card').innerText()).includes('Local site') &&
    (await site.locator('input[name=pass]').inputValue()) === '',
);
for (const extra of (await call('snapshot')).entries.filter(
  (e) => e.url.value === ORIGIN && e.title.value !== 'Local site',
))
  await call('deleteEntry', extra.id, true);
await site.reload();
await site.waitForTimeout(600);
await admin.evaluate(
  (tab) => chrome.tabs.sendMessage(tab, { channel: 'keep:page', cmd: 'fillBest' }),
  tabId,
);
await site.waitForTimeout(500);
t.ok(
  'with one match the shortcut fills it',
  (await site.locator('input[name=pass]').inputValue()) === 'pw-secret',
);
await call('createEntry', root, entryDraft('Bank', 'bob', 'bob-pw', 'https://bank.example'));
const bank = (await call('snapshot')).entries.find((e) => e.title.value === 'Bank');
await site.reload();
await site.waitForTimeout(600);
await admin.evaluate(
  ([tab, entry]) =>
    chrome.tabs.sendMessage(tab, { channel: 'keep:page', cmd: 'fill', id: entry, withTotp: false }),
  [tabId, bank.id],
);
await site.waitForTimeout(500);
t.ok(
  'an entry for another site is never filled',
  (await site.locator('input[name=pass]').inputValue()) === '',
);

t.done(run.logs);
await run.context.close();
server.close();
