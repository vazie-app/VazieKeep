import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import * as wasm from '../../../core/keep-core-wasm/pkg/keep_core_wasm.js';
import { KeepVaultSession } from '../src/core/vaultSession.ts';

const MARKER = 'VAZIE_KEEP_SECRET_DO_NOT_LEAK_42';
const vectors = new URL('../../../shared/test-vectors/kdbx4/', import.meta.url);
const keyFiles = new URL('../../../shared/test-vectors/keyfiles/', import.meta.url);

wasm.initSync({
  module: readFileSync(
    new URL('../../../core/keep-core-wasm/pkg/keep_core_wasm_bg.wasm', import.meta.url),
  ),
});

function markerVault(): Uint8Array {
  return readFileSync(new URL('leak-marker.kdbx', vectors));
}

function errorCode(block: () => unknown): string {
  try {
    block();
  } catch (error) {
    return (error as Error).message;
  }
  return 'no error';
}

void test('summaries carry no secrets and one secret is revealed on request', () => {
  const password = new TextEncoder().encode(MARKER);
  const session = KeepVaultSession.open(wasm, markerVault(), password);
  assert.ok(
    password.every((byte) => byte === 0),
    'password buffer is wiped',
  );

  const entries = session.entries();
  const [entry] = entries;
  assert.ok(entry);
  assert.equal(entry.hasPassword, true);
  assert.equal(entry.hasProtectedFields, true);
  const detail = session.entry(entry.id);
  assert.deepEqual(detail.customFields[0]?.value, { kind: 'protected' });
  const visible = JSON.stringify([
    session.info(),
    session.groups(),
    entries,
    detail,
    session.search('marker'),
    session,
  ]);
  assert.ok(!visible.includes(MARKER), 'summaries leak the secret');

  const secret = session.revealPassword(entry.id);
  assert.equal(
    secret.useText((text) => text),
    MARKER,
  );
  assert.ok(!`${String(secret)} ${JSON.stringify({ secret })}`.includes(MARKER));
  secret.wipe();
  assert.throws(() => secret.useText((text) => text), /secret_wiped/);

  const field = session.revealField(entry.id, 'Marker Field');
  assert.equal(
    field.useText((text) => text),
    MARKER,
  );
  field.wipe();

  session.close();
  assert.throws(() => session.entries(), /session_closed/);
});

void test('raw handles fail safely after close and when forged', () => {
  const handle = wasm.openVault(markerVault(), new TextEncoder().encode(MARKER));
  assert.match(handle, /^[0-9a-f]{32}$/);
  assert.ok(wasm.closeVault(handle));
  assert.equal(
    errorCode(() => wasm.listEntries(handle)),
    'session_closed',
  );
  assert.equal(wasm.closeVault(handle), false);
  assert.equal(
    errorCode(() => wasm.listEntries('0123456789abcdef0123456789abcdef')),
    'session_not_found',
  );
  assert.equal(
    errorCode(() => wasm.listEntries('<script>')),
    'invalid_argument',
  );
});

void test('wrong password and foreign files map to coarse errors', () => {
  assert.equal(
    errorCode(() => wasm.openVault(markerVault(), new TextEncoder().encode('wrong'))),
    'invalid_credentials',
  );
  assert.equal(
    errorCode(() => wasm.openVault(new Uint8Array([0x50, 0x4b]), new Uint8Array([1]))),
    'not_a_vault',
  );
});

void test('key files: alone, with a password, wrong or damaged', () => {
  const read = (name: string): Uint8Array => readFileSync(new URL(name, keyFiles));
  const password = (): Uint8Array => new TextEncoder().encode('correct horse battery staple');
  const keyOnly = KeepVaultSession.open(
    wasm,
    read('vault-keyfile-only.kdbx'),
    new Uint8Array(),
    read('keyfile-v2.keyx'),
  );
  assert.equal(keyOnly.info().name, 'Key File Vault');
  keyOnly.close();

  const typed = password();
  const both = KeepVaultSession.open(
    wasm,
    read('vault-password-plus-keyfile.kdbx'),
    typed,
    read('keyfile-v2.keyx'),
  );
  assert.ok(typed.every((byte) => byte === 0));
  both.close();

  const open = (keyFile?: Uint8Array): string =>
    errorCode(() => wasm.openVault(read('vault-password-plus-keyfile.kdbx'), password(), keyFile));
  assert.equal(open(read('keyfile-wrong.keyx')), 'invalid_credentials');
  assert.equal(open(), 'invalid_credentials');
  assert.equal(open(read('keyfile-v2-bad-hash.keyx')), 'invalid_key_file');
});
