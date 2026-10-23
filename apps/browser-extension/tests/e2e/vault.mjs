// The vault through the host: edit, search, history, attachments, one-time codes, import, save and conflicts.
import { createHmac } from 'node:crypto';
import { entryDraft, launchWithPlus, newVault, reporter, rpc } from './lib.mjs';
import { lease, qaKey } from './qa.mjs';

const t = reporter();
const run = await launchWithPlus(lease(qaKey()));
const { admin: page } = run;
const call = (method, ...args) => rpc(page, method, ...args);

await newVault(page, 'Full', 'pw-1');
let state = await call('state');
t.ok('Keep+ is on from the QA lease', state.account.access.plusFeatures === true);
t.ok('the new vault is open and editable', state.session?.editable === true);
const root = state.session.info.rootGroupId;

const draft = (over = {}) =>
  entryDraft('Mail', 'me@example.com', 'p@ss-Ünï', 'https://mail.example.com', {
    notes: 'hello\nworld',
    customFields: [
      { name: 'PIN', value: '1234', protected: true },
      { name: 'Note', value: 'x', protected: false },
    ],
    tags: ['work', 'mail'],
    favorite: true,
    expiresAt: Math.floor(Date.now() / 1000) - 3600,
    ...over,
  });
const made = await call('createEntry', root, draft());
t.ok('an entry is created and saved to the file', made.saved && made.value);
const id = made.value;
const detail = await call('entry', id);
t.ok(
  'fields, tags, favourite and notes come back',
  detail.summary.favorite &&
    detail.summary.tags.includes('work') &&
    detail.customFields.length === 2 &&
    detail.notes.value === 'hello\nworld',
);
t.ok(
  'protected values are hidden until revealed',
  detail.customFields.find((f) => f.name === 'PIN').value.kind === 'protected',
);
t.ok(
  'the password reveals as typed (Unicode)',
  (await call('reveal', id, 'Password')) === 'p@ss-Ünï',
);

const secret = 'JBSWY3DPEHPK3PXP';
const norm = await call('totpNormalize', `otpauth://totp/Mail:me?secret=${secret}&issuer=Mail`);
await call(
  'updateEntry',
  id,
  draft({ customFields: [{ name: 'otp', value: norm.uri, protected: true }] }),
);
const code = await call('totp', id);
function totp(base32, at) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of base32) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30)));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[19] & 15;
  return String(
    (((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]) % 1e6,
  ).padStart(6, '0');
}
const now = Math.floor(Date.now() / 1000);
t.ok(
  'the one-time code matches an independent TOTP',
  [now - 1, now, now + 1].some((s) => totp(secret, s) === code.code),
);

const versions = await call('history', id);
t.ok('the earlier version is kept', versions.length >= 1);
t.ok(
  'its secret can be revealed',
  (await call('revealHistory', id, versions[0].index, 'Password')) === 'p@ss-Ünï',
);
await call('updateEntry', id, draft({ password: 'changed', customFields: [] }));
const all = await call('history', id);
await call('restoreHistory', id, all.at(-1).index);
t.ok('restoring brings an old version back', (await call('reveal', id, 'Password')) === 'p@ss-Ünï');

const bytes = Array.from({ length: 70000 }, (_, i) => i % 251);
const att = await page.evaluate(
  async ([entry, data]) =>
    await window.__rpc('addAttachment', entry, 'blob.bin', new Uint8Array(data)),
  [id, bytes],
);
t.ok('an attachment is added', att.saved && att.value === 'blob.bin');
const back = await call('attachment', id, 'blob.bin');
t.ok('its bytes round-trip', back.length === 70000 && back[1234] === 1234 % 251);
await call('removeAttachment', id, 'blob.bin');

const group = await call('createGroup', root, 'Work');
await call('moveEntry', id, group.value);
t.ok('an entry moves to a group', (await call('entry', id)).summary.groupId === group.value);
t.ok(
  'search finds by tag and title, never by password',
  (await call('search', 'tag:work')).length === 1 &&
    (await call('search', 'mai')).length === 1 &&
    (await call('search', 'changed')).length === 0,
);
t.ok(
  'deleting goes to the Recycle Bin first',
  (await call('deleteEntry', id, false)).value === 'recycled',
);
t.ok('then for good', (await call('deleteEntry', id, true)).value === 'deleted');

const csv =
  'Title,Username,Password,URL\nAlpha,a@x.io,pw-a,https://alpha.io\nBeta,b,pw-b,beta.org\n';
const parsed = await page.evaluate(
  async (text) => await window.__rpc('parseImport', new TextEncoder().encode(text)),
  csv,
);
t.ok('an export is read', parsed.summary.entries === 2);
t.ok(
  'and taken into the vault',
  (await call('applyImport', parsed.planId, root)).value.entries === 2,
);

await call('lock');
state = await call('state');
t.ok('locking closes the session', state.session === null);
await page.evaluate(
  async (vault) =>
    await window.__rpc('unlock', { vaultId: vault, password: new TextEncoder().encode('pw-1') }),
  state.vaults[0].id,
);
t.ok(
  'the data is in the file after unlocking again',
  (await call('snapshot')).entries.length === 2,
);

await page.evaluate(async () => {
  const dir = await navigator.storage.getDirectory();
  const handle = await dir.getFileHandle('Full.kdbx');
  const writable = await handle.createWritable({ keepExistingData: true });
  await writable.seek((await handle.getFile()).size);
  await writable.write(new Uint8Array([0]));
  await writable.close();
});
const conflict = await call('createEntry', root, draft({ title: 'Gamma' }));
t.ok(
  'a change made elsewhere is detected and nothing is overwritten',
  conflict.saved === false && conflict.problem === 'changedElsewhere',
);
t.ok('overwriting on purpose works', (await call('overwrite')).saved === true);
t.ok('a copy can be exported', (await call('exportCopy')).length > 1000);

t.done(run.logs);
await run.context.close();
