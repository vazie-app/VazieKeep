import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  FileProblem,
  restorableCopy,
  restoreVaultFile,
  sha256Hex,
  writeVaultFile,
} from '../src/host/files.ts';

// A small in-memory stand-in for the origin-private file system, where the local copies live.
class Dir {
  dirs = new Map<string, Dir>();
  files = new Map<string, Uint8Array>();
  getDirectoryHandle(name: string) {
    let dir = this.dirs.get(name);
    if (!dir) this.dirs.set(name, (dir = new Dir()));
    return Promise.resolve(dir);
  }
  getFileHandle(name: string, options?: { create?: boolean }) {
    if (!this.files.has(name)) {
      if (!options?.create) return Promise.reject(new DOMException('missing', 'NotFoundError'));
      this.files.set(name, new Uint8Array());
    }
    const files = this.files;
    return Promise.resolve({
      getFile: () =>
        Promise.resolve({
          arrayBuffer: () => Promise.resolve((files.get(name) ?? new Uint8Array()).slice().buffer),
        }),
      createWritable: () => {
        let data = new Uint8Array();
        return Promise.resolve({
          write: (bytes: Uint8Array) => {
            data = bytes.slice();
            return Promise.resolve();
          },
          close: () => {
            files.set(name, data);
            return Promise.resolve();
          },
        });
      },
    });
  }
  removeEntry(name: string) {
    this.files.delete(name);
    this.dirs.delete(name);
    return Promise.resolve();
  }
  // eslint-disable-next-line @typescript-eslint/require-await -- the real directory handle's iterator is async
  async *values() {
    for (const name of this.files.keys()) yield { kind: 'file' as const, name };
  }
}

const root = new Dir();
Object.defineProperty(globalThis, 'navigator', {
  value: { storage: { getDirectory: () => Promise.resolve(root) } },
  configurable: true,
});

const MAGIC = [0x03, 0xd9, 0xa2, 0x9a, 0x67, 0xfb, 0x4b, 0xb5];
const vault = (tag: number, size = 64): Uint8Array => {
  const bytes = new Uint8Array(size).fill(tag);
  bytes.set(MAGIC);
  return bytes;
};

/** A vault file whose reads can be bent: `view` decides what a read sees for a while after a write. */
function fakeFile(initial: Uint8Array) {
  const state = {
    disk: initial,
    view: null as null | (() => Uint8Array),
    onWrite: null as null | ((bytes: Uint8Array) => void),
  };
  const handle = {
    queryPermission: () => Promise.resolve('granted'),
    getFile: () => {
      const seen = (state.view ? state.view() : state.disk).slice();
      return Promise.resolve({ arrayBuffer: () => Promise.resolve(seen.buffer) });
    },
    createWritable: () => {
      let data = new Uint8Array();
      return Promise.resolve({
        write: (bytes: Uint8Array) => {
          data = bytes.slice();
          return Promise.resolve();
        },
        close: () => {
          state.disk = data;
          state.onWrite?.(data);
          return Promise.resolve();
        },
      });
    },
  };
  return { state, handle: handle as unknown as FileSystemFileHandle };
}

const FAST = { readbackDelaysMs: [0, 5, 10] };

void test('a normal save is confirmed and kept as the newest local copy', async () => {
  const file = fakeFile(vault(1));
  const first = vault(2);
  const hash = await writeVaultFile(
    file.handle,
    'a',
    first,
    await sha256Hex(vault(1)),
    false,
    FAST,
  );
  assert.equal(hash, await sha256Hex(first));
  assert.deepEqual(await restorableCopy('a'), first);
});

void test('restore brings back the last save, not the one before it', async () => {
  const file = fakeFile(vault(1));
  let known = await sha256Hex(vault(1));
  for (const tag of [2, 3, 4]) {
    known = await writeVaultFile(file.handle, 'b', vault(tag), known, false, FAST);
  }
  file.state.disk = new Uint8Array(64); // damaged
  assert.equal(await restoreVaultFile(file.handle, 'b'), true);
  assert.deepEqual(file.state.disk, vault(4));
});

void test('restore keeps a readable vault it is about to replace', async () => {
  const file = fakeFile(vault(1));
  await writeVaultFile(file.handle, 'c', vault(2), await sha256Hex(vault(1)), false, FAST);
  file.state.disk = vault(9); // somebody else's complete vault
  await restoreVaultFile(file.handle, 'c');
  assert.deepEqual(file.state.disk, vault(2));
  const copies = [
    ...(await (await root.getDirectoryHandle('backups')).getDirectoryHandle('c')).files.values(),
  ];
  assert.ok(copies.some((copy) => copy.every((byte, i) => byte === (vault(9)[i] ?? -1))));
});

void test('a slow sync client that shows the old bytes for a moment does not fail the save', async () => {
  const old = vault(1);
  const file = fakeFile(old);
  let reads = 0;
  file.state.onWrite = () => {
    file.state.view = () => (++reads < 3 ? old : file.state.disk);
  };
  const next = vault(2);
  const hash = await writeVaultFile(file.handle, 'd', next, await sha256Hex(old), false, FAST);
  assert.equal(hash, await sha256Hex(next));
  assert.deepEqual(file.state.disk, next);
});

void test('a newer vault from another device is reported and never overwritten', async () => {
  const old = vault(1);
  const file = fakeFile(old);
  const theirs = vault(7, 80);
  file.state.onWrite = () => {
    file.state.disk = theirs;
  };
  await assert.rejects(
    writeVaultFile(file.handle, 'e', vault(2), await sha256Hex(old), false, FAST),
    (error: unknown) => error instanceof FileProblem && error.problem === 'changedElsewhere',
  );
  assert.deepEqual(file.state.disk, theirs);
});

void test('a damaged result is put back, and a stale one is left as it is', async () => {
  const old = vault(1);
  const damaged = fakeFile(old);
  damaged.state.onWrite = () => {
    damaged.state.onWrite = null; // only the first write goes wrong; the put-back works
    damaged.state.disk = new Uint8Array(10);
  };
  await assert.rejects(
    writeVaultFile(damaged.handle, 'f', vault(2), await sha256Hex(old), false, FAST),
    (error: unknown) => error instanceof FileProblem && error.problem === 'verificationFailed',
  );
  assert.deepEqual(damaged.state.disk, old);

  const stale = fakeFile(old);
  stale.state.onWrite = () => {
    stale.state.disk = old;
  };
  await assert.rejects(
    writeVaultFile(stale.handle, 'g', vault(2), await sha256Hex(old), false, FAST),
    (error: unknown) =>
      error instanceof FileProblem &&
      error.problem === 'verificationFailed' &&
      (error.detail ?? '').includes('still in place'),
  );
});

void test('a retry finds the earlier write has landed and says so', async () => {
  const next = vault(2);
  const file = fakeFile(next); // the write showed up after the first try gave up
  const hash = await writeVaultFile(file.handle, 'h', next, await sha256Hex(vault(1)), false, FAST);
  assert.equal(hash, await sha256Hex(next));
});
