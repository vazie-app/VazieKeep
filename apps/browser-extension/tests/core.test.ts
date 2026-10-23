import assert from 'node:assert/strict';
import { createHash, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { test } from 'node:test';

import type { Access } from '../src/core/types.ts';
import { core, draft, FAST_KDF, text } from './support.ts';

const FREE: Access = {
  state: 'free',
  plusFeatures: false,
  needsOnlineVerification: false,
  leaseValidUntil: null,
  observedDeviceTime: 0,
};
const PLUS: Access = { ...FREE, state: 'plusLifetime', plusFeatures: true };

function newVault(password = 'pw-1') {
  const created = core.createVault('Test', FAST_KDF, text(password));
  const session = core.openVault(created.bytes, text(password));
  return { created, session, root: session.info().rootGroupId };
}

void test('a new vault round-trips through edit, save and reopen', () => {
  const { session, root } = newVault();
  const id = session.createEntry(root, draft('Mail', { password: 'p@ss-Ünï', favorite: true }));
  assert.equal(session.dirty, true);
  const saved = session.prepareSave();
  assert.ok(session.markSaved(saved.generation));
  assert.equal(session.dirty, false);
  session.close();

  const reopened = core.openVault(saved.bytes, text('pw-1'));
  const [entry] = reopened.entries();
  assert.ok(entry);
  assert.equal(entry.id, id);
  assert.equal(entry.favorite, true);
  assert.equal(reopened.revealText(id, 'Password'), 'p@ss-Ünï');
  reopened.close();
});

void test('a wrong password and a foreign file are refused with coarse errors', () => {
  const { created } = newVault();
  assert.throws(() => core.openVault(created.bytes, text('nope')), /invalid_credentials/);
  assert.throws(() => core.openVault(text('PK not a vault'), text('x')), /not_a_vault/);
});

void test('deleting moves to the Recycle Bin first and removes for good second', () => {
  const { session, root } = newVault();
  const id = session.createEntry(root, draft('Old'));
  assert.equal(session.deleteEntry(id, false), 'recycled');
  const bin = session.info().recycleBinId;
  assert.ok(bin);
  assert.equal(session.entry(id).summary.groupId, bin);
  assert.equal(session.deleteEntry(id, true), 'deleted');
  assert.equal(session.entries().length, 0);
});

void test('history needs Keep+ to read and keeps the previous password', () => {
  const { session, root } = newVault();
  const id = session.createEntry(root, draft('Bank', { password: 'first' }));
  session.updateEntry(id, draft('Bank', { password: 'second' }));
  assert.throws(() => session.history(id, FREE), /not_entitled/);
  const [version] = session.history(id, PLUS);
  assert.ok(version);
  assert.ok(version.differs.includes('password'));
  assert.equal(session.revealHistory(id, version.index, 'Password', PLUS), 'first');
  session.restoreHistory(id, version.index, PLUS);
  assert.equal(session.revealText(id, 'Password'), 'first');
  assert.throws(() => {
    session.restoreHistory(id, version.index, FREE);
  }, /not_entitled/);
});

void test('attachments: adding needs Keep+, reading and removing do not', () => {
  const { session, root } = newVault();
  const id = session.createEntry(root, draft('Docs'));
  const bytes = new Uint8Array(5000).map((_, i) => i % 251);
  assert.throws(() => session.addAttachment(id, 'a.bin', bytes.slice(), FREE), /not_entitled/);
  assert.equal(session.addAttachment(id, 'a.bin', bytes.slice(), PLUS), 'a.bin');
  assert.deepEqual(session.attachment(id, 'a.bin'), bytes);
  session.removeAttachment(id, 'a.bin');
  assert.equal(session.entry(id).attachments.length, 0);
});

void test('one-time codes follow RFC 6238 and need Keep+', () => {
  const setup = core.totpNormalize(
    text('otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8'),
    PLUS,
  );
  assert.equal(setup.digits, 8);
  assert.throws(() => core.totpNormalize(text('GEZDGNBVGY3TQOJQ'), FREE), /not_entitled/);
  const { session, root } = newVault();
  const id = session.createEntry(
    root,
    draft('2FA', { customFields: [{ name: 'otp', value: setup.uri, protected: true }] }),
  );
  assert.equal(session.totp(id, 59, PLUS).code, '94287082');
  assert.throws(() => session.totp(id, 59, FREE), /not_entitled/);
});

void test('import reads a CSV export and takes it into a group', () => {
  const { session, root } = newVault();
  const csv = 'Title,Username,Password,URL\nAlpha,a,pw-a,https://alpha.io\nBeta,b,pw-b,beta.org\n';
  const { planId, summary } = core.parseImport(text(csv));
  assert.equal(summary.entries, 2);
  assert.deepEqual(session.importPlan(root, planId), { entries: 2, groups: 0 });
  core.dropImport(planId);
  assert.equal(session.entries().length, 2);
  assert.throws(() => core.parseImport(text('')), /empty/);
});

void test('a passkey signs for its own site and the signature verifies', () => {
  const { session, root } = newVault();
  const made = session.createPasskey(root, 'example.com', 'Example', 'ann', new Uint8Array([1, 2]));
  const entry = session.entry(made.entryId).summary;
  assert.equal(entry.passkey?.rpId, 'example.com');
  const hash = createHash('sha256').update('client data').digest();
  const signed = session.signPasskey(made.entryId, 'example.com', hash);
  const key = createPublicKey({ key: Buffer.from(made.publicKey), format: 'der', type: 'spki' });
  const data = Buffer.concat([Buffer.from(signed.authenticatorData), hash]);
  assert.ok(verify('sha256', data, key, Buffer.from(signed.signature)));
  assert.throws(() => session.signPasskey(made.entryId, 'evil.com', hash), /invalid_request/);
  session.removePasskey(made.entryId);
  assert.throws(() => session.signPasskey(made.entryId, 'example.com', hash), /no_passkey/);
});

void test('quick-unlock material reopens the vault without the password', () => {
  const created = core.createVault('Q', FAST_KDF, text('pw'));
  const session = core.openVault(created.bytes, text('pw'), { retainMaterial: true });
  const material = session.unlockMaterial();
  assert.ok(material);
  session.close();
  const again = core.openVault(created.bytes, new Uint8Array(), { material });
  assert.equal(again.info().name, 'Q');
  assert.equal(core.openVault(created.bytes, text('pw')).unlockMaterial(), undefined);
});

void test('the generator honours its options', () => {
  const password = new TextDecoder().decode(
    core.generatePassword({
      length: 32,
      lowercase: true,
      uppercase: false,
      digits: true,
      symbols: false,
      excludeAmbiguous: true,
    }),
  );
  assert.equal(password.length, 32);
  assert.match(password, /^[a-z0-9]+$/);
  assert.doesNotMatch(password, /[0O1lI5S2Z8B]/);
  assert.throws(
    () =>
      core.generatePassword({
        length: 8,
        lowercase: false,
        uppercase: false,
        digits: false,
        symbols: false,
        excludeAmbiguous: false,
      }),
    /no_character_sets/,
  );
});

void test('a lease is trusted only when signed by a known key, and the clock rules apply', () => {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const x = publicKey.export({ format: 'jwk' }).x ?? '';
  const now = Math.floor(Date.now() / 1000);
  const b64 = (value: string | Buffer) => Buffer.from(value).toString('base64url');
  const token = (claims: Record<string, unknown>) => {
    const head = b64(JSON.stringify({ alg: 'EdDSA', kid: 'qa' }));
    const body = b64(JSON.stringify(claims));
    return `${head}.${body}.${b64(sign(null, Buffer.from(`${head}.${body}`), privateKey))}`;
  };
  const claims = {
    version: 1,
    issuer: 'vazie',
    subject: 'acc',
    product: 'keep',
    tier: 'keep_plus',
    lifetime: true,
    issued_at: now - 10,
    lease_expires_at: now + 86_400,
    entitlement_expires_at: null,
    features_version: 1,
    revision: 1,
    nonce: 'n',
  };
  const keys = [{ kid: 'qa', publicKey: x }];
  const lease = core.verifyLease(token(claims), keys);
  assert.equal(lease.tier, 'keepPlus');
  const access = core.evaluateAccess(lease, now, 0);
  assert.equal(access.plusFeatures, true);
  assert.equal(core.canUse('totp', access), true);
  assert.throws(() => core.verifyLease(token(claims), []), /unknown_key/);
  assert.throws(() => core.verifyLease(`${token(claims)}x`, keys), /bad_signature|malformed/);
  const behind = core.evaluateAccess(lease, now - 3600, now);
  assert.equal(behind.plusFeatures, false, 'a clock set back switches Keep+ off');
  assert.equal(core.evaluateAccess(null, now, 0).plusFeatures, false);
});

void test('hosts match by the shared policy', () => {
  assert.equal(core.matchHost('example.com', 'example.com'), 'exact');
  assert.equal(core.matchHost('example.com', 'accounts.example.com'), 'subdomain');
  assert.equal(core.matchHost('example.com', 'example.com.evil.test'), 'none');
  assert.equal(core.matchHost('co.uk', 'evil.co.uk'), 'none');
});

// ---------------------------------------------------------------- merging two versions (ADR 0023)

/** A database with these entries (title → password) saved at the clock's current time. */
function saved(titles: Record<string, string>, password = 'pw-1') {
  const { created, session, root } = newVault(password);
  for (const [title, secret] of Object.entries(titles)) {
    session.createEntry(root, draft(title, { password: secret }));
  }
  const snapshot = session.prepareSave();
  session.markSaved(snapshot.generation);
  session.close();
  return { bytes: snapshot.bytes, fingerprint: created.fingerprint, password };
}

function idOf(session: ReturnType<typeof core.openVault>, title: string): string {
  const found = session.entries().find((e) => e.title.kind === 'plain' && e.title.value === title);
  assert.ok(found, `no entry titled ${title}`);
  return found.id;
}

function titles(session: ReturnType<typeof core.openVault>): string[] {
  return session
    .entries()
    .map((e) => (e.title.kind === 'plain' ? e.title.value : ''))
    .sort();
}

/** The core reads the page's `Date.now()`, so a test can set the time of each edit. */
function atSecond(seconds: number) {
  const base = 1_800_000_000_000;
  Date.now = () => base + seconds * 1000;
}

void test('merge combines two versions, wipes the password and leaves the session dirty', () => {
  const realNow = Date.now;
  try {
    atSecond(0);
    const base = saved({ Mail: 'm0', Bank: 'b0' });
    const mine = core.openVault(base.bytes, text('pw-1'));
    const theirs = core.openVault(base.bytes, text('pw-1'));
    atSecond(100);
    theirs.updateEntry(idOf(theirs, 'Mail'), draft('Mail', { password: 'm1' }));
    theirs.createEntry(theirs.info().rootGroupId, draft('Shop', { password: 's1' }));
    const snapshot = theirs.prepareSave();
    theirs.close();

    assert.equal(mine.dirty, false);
    const password = text('pw-1');
    const report = mine.merge(snapshot.bytes, password);
    assert.ok(
      password.every((byte) => byte === 0),
      'the other file’s password is wiped',
    );
    assert.equal(report.changed, true);
    assert.equal(report.entriesUpdated, 1);
    assert.equal(report.entriesAdded, 1);
    assert.deepEqual(report.conflicts, []);
    assert.equal(mine.dirty, true);
    assert.equal(mine.revealText(idOf(mine, 'Mail'), 'Password'), 'm1');
    assert.deepEqual(titles(mine), ['Bank', 'Mail', 'Shop']);

    // Saved and merged again: nothing to do, and the saved file opens with the same entries.
    const merged = mine.prepareSave();
    mine.markSaved(merged.generation);
    assert.equal(mine.merge(snapshot.bytes, text('pw-1')).changed, false);
    assert.equal(mine.dirty, false);
    mine.close();
    const reopened = core.openVault(merged.bytes, text('pw-1'));
    assert.deepEqual(titles(reopened), ['Bank', 'Mail', 'Shop']);
    reopened.close();
  } finally {
    Date.now = realNow;
  }
});

void test('merge keeps the losing side of a clash as a copy named in the report', () => {
  const realNow = Date.now;
  try {
    atSecond(0);
    const base = saved({ Mail: 'm0' });
    const mine = core.openVault(base.bytes, text('pw-1'));
    const theirs = core.openVault(base.bytes, text('pw-1'));
    atSecond(100);
    mine.updateEntry(idOf(mine, 'Mail'), draft('Mail', { password: 'm-mine' }));
    atSecond(200);
    theirs.updateEntry(idOf(theirs, 'Mail'), draft('Mail', { password: 'm-theirs' }));
    const snapshot = theirs.prepareSave();
    theirs.close();

    const report = mine.merge(snapshot.bytes, text('pw-1'), { conflictSuffix: ' (конфликт)' });
    assert.equal(report.conflicts.length, 1);
    const copy = idOf(mine, 'Mail (конфликт)');
    assert.deepEqual(report.conflicts, [copy]);
    assert.equal(mine.revealText(copy, 'Password'), 'm-mine');
    assert.equal(mine.revealText(idOf(mine, 'Mail'), 'Password'), 'm-theirs');
    mine.close();
  } finally {
    Date.now = realNow;
  }
});

void test('merge opens the other file with its own key file or retained material', () => {
  const keyFile = core.generateKeyFile();
  const created = core.createVault('Keyed', FAST_KDF, text('pw-k'), keyFile.slice());
  const mine = core.openVault(created.bytes, text('pw-k'), {
    keyFile: keyFile.slice(),
    retainMaterial: true,
  });
  mine.createEntry(mine.info().rootGroupId, draft('Mail'));
  const snapshot = mine.prepareSave();
  mine.markSaved(snapshot.generation);

  const other = core.openVault(snapshot.bytes, text('pw-k'), { keyFile: keyFile.slice() });
  other.createEntry(other.info().rootGroupId, draft('Bank'));
  const theirs = other.prepareSave();
  other.close();

  // Without the key file the other file does not open, and nothing changes.
  assert.throws(() => mine.merge(theirs.bytes, text('pw-k')), /invalid_credentials|invalid_key/);
  assert.equal(mine.dirty, false);
  const report = mine.merge(theirs.bytes, text('pw-k'), { keyFile: keyFile.slice() });
  assert.equal(report.entriesAdded, 1);

  // The password component kept at unlock opens it too.
  const material = mine.unlockMaterial();
  assert.ok(material);
  const again = mine.merge(theirs.bytes, new Uint8Array(), {
    material,
    keyFile: keyFile.slice(),
  });
  assert.equal(again.changed, false);
  mine.close();
});

void test('merge refuses other databases and bad files without changing anything', () => {
  const a = saved({ Mail: 'm0' });
  const b = saved({ Bank: 'b0' });
  const mine = core.openVault(a.bytes, text('pw-1'));
  assert.throws(() => mine.merge(b.bytes, text('pw-1')), /different_vault/);
  assert.throws(() => mine.merge(b.bytes, text('wrong')), /invalid_credentials/);
  assert.throws(() => mine.merge(text('PK not a vault'), text('x')), /not_a_vault/);
  assert.equal(mine.dirty, false);
  assert.deepEqual(titles(mine), ['Mail']);

  const report = mine.merge(b.bytes, text('pw-1'), { allowUnrelated: true });
  assert.equal(report.entriesAdded, 1);
  assert.deepEqual(titles(mine), ['Bank', 'Mail']);
  mine.close();
  assert.throws(() => mine.merge(b.bytes, text('pw-1')), /session/);
});
