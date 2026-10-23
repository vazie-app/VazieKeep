import type { SaveProblem } from '../shared/model';

export class FileProblem extends Error {
  readonly problem: SaveProblem | 'unreadable';
  /** What was seen, for the person to read or report: sizes, short hashes, how long we waited. */
  readonly detail: string | undefined;

  constructor(problem: SaveProblem | 'unreadable', detail?: string) {
    super(problem);
    this.name = 'FileProblem';
    this.problem = problem;
    this.detail = detail;
  }
}

const BACKUPS_KEPT = 5;

/**
 * How long a written file is given to read back as written. A folder that a sync client watches (iCloud
 * Drive, Dropbox, OneDrive, Google Drive) can show the old bytes, or a partial file, for a moment after a
 * save; waiting a little is better than judging the write by the first look.
 */
export const READBACK_DELAYS_MS = [0, 400, 1200, 3000, 8000];

const KDBX_MAGIC = [0x03, 0xd9, 0xa2, 0x9a, 0x67, 0xfb, 0x4b, 0xb5];

/** True when the bytes begin like a KeePass database (the signature is the same for KDBX 3 and 4). */
export function looksLikeVault(bytes: Uint8Array): boolean {
  return bytes.length >= KDBX_MAGIC.length && KDBX_MAGIC.every((byte, i) => bytes[i] === byte);
}

function isPrefix(part: Uint8Array, whole: Uint8Array): boolean {
  if (part.length >= whole.length) return false;
  for (let i = 0; i < part.length; i += 1) if (part[i] !== whole[i]) return false;
  return true;
}

const sleep = (ms: number) =>
  new Promise<void>((resolve) => {
    setTimeout(resolve, ms);
  });

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

function classify(error: unknown, fallback: SaveProblem | 'unreadable'): FileProblem {
  if (error instanceof FileProblem) return error;
  const name = error instanceof DOMException ? error.name : '';
  switch (name) {
    case 'NotFoundError':
      return new FileProblem(fallback === 'unreadable' ? 'unreadable' : 'unavailable');
    case 'NotAllowedError':
    case 'SecurityError':
      return new FileProblem(fallback === 'unreadable' ? 'unreadable' : 'permission');
    case 'QuotaExceededError':
      return new FileProblem('noSpace');
    case 'NoModificationAllowedError':
    case 'InvalidStateError':
      return new FileProblem('notWritable');
    default:
      return new FileProblem(fallback);
  }
}

export async function canWrite(handle: FileSystemFileHandle): Promise<boolean> {
  try {
    return (await handle.queryPermission({ mode: 'readwrite' })) === 'granted';
  } catch {
    return false;
  }
}

export async function canRead(handle: FileSystemFileHandle): Promise<boolean> {
  try {
    return (await handle.queryPermission({ mode: 'read' })) === 'granted';
  } catch {
    return false;
  }
}

export async function readFile(handle: FileSystemFileHandle): Promise<Uint8Array> {
  try {
    const file = await handle.getFile();
    return new Uint8Array(await file.arrayBuffer());
  } catch (error) {
    throw classify(error, 'unreadable');
  }
}

async function root(): Promise<FileSystemDirectoryHandle> {
  return await navigator.storage.getDirectory();
}

async function directory(...path: string[]): Promise<FileSystemDirectoryHandle> {
  let current = await root();
  for (const part of path) {
    current = await current.getDirectoryHandle(part, { create: true });
  }
  return current;
}

async function writeOpfs(dir: FileSystemDirectoryHandle, name: string, bytes: Uint8Array) {
  const file = await dir.getFileHandle(name, { create: true });
  const writable = await file.createWritable();
  await writable.write(bytes as BufferSource);
  await writable.close();
}

async function backupNames(dir: FileSystemDirectoryHandle): Promise<string[]> {
  const names: string[] = [];
  for await (const entry of dir.values()) {
    if (entry.kind === 'file') names.push(entry.name);
  }
  return names.sort();
}

/** Keeps `bytes` as the newest local copy, unless the newest copy already is exactly these bytes. */
async function keepBackup(vaultId: string, bytes: Uint8Array): Promise<void> {
  const dir = await directory('backups', vaultId);
  const names = await backupNames(dir);
  const newest = names.at(-1);
  if (newest) {
    const known = new Uint8Array(
      await (await (await dir.getFileHandle(newest)).getFile()).arrayBuffer(),
    );
    if (known.length === bytes.length && (await sha256Hex(known)) === (await sha256Hex(bytes)))
      return;
  }
  const name = `${Date.now().toString().padStart(15, '0')}.kdbx`;
  await writeOpfs(dir, name, bytes);
  const all = await backupNames(dir);
  for (const old of all.slice(0, Math.max(0, all.length - BACKUPS_KEPT))) {
    await dir.removeEntry(old).catch(() => undefined);
  }
}

async function newestBackup(vaultId: string): Promise<Uint8Array | null> {
  try {
    const dir = await directory('backups', vaultId);
    const names: string[] = [];
    for await (const entry of dir.values()) {
      if (entry.kind === 'file') names.push(entry.name);
    }
    const newest = names.sort().at(-1);
    if (!newest) return null;
    const file = await (await dir.getFileHandle(newest)).getFile();
    return new Uint8Array(await file.arrayBuffer());
  } catch {
    return null;
  }
}

async function pendingCopy(vaultId: string): Promise<Uint8Array | null> {
  try {
    const dir = await directory('pending');
    const file = await (await dir.getFileHandle(vaultId)).getFile();
    return new Uint8Array(await file.arrayBuffer());
  } catch {
    return null;
  }
}

async function clearPending(vaultId: string): Promise<void> {
  try {
    await (await directory('pending')).removeEntry(vaultId);
  } catch {
    /* nothing was kept */
  }
}

export async function forgetCopies(vaultId: string): Promise<void> {
  try {
    await (await directory('backups')).removeEntry(vaultId, { recursive: true });
  } catch {
    /* nothing was kept */
  }
  await clearPending(vaultId);
}

export async function restorableCopy(vaultId: string): Promise<Uint8Array | null> {
  return (await pendingCopy(vaultId)) ?? (await newestBackup(vaultId));
}

/**
 * Writes `bytes` over the vault file the way the other platforms do: refuse if the file changed
 * since it was read, keep a copy of what is there (when no copy of it exists yet) and of what is about
 * to be written, write, read it back (waiting a little for a sync client to settle), and keep what was
 * written as the newest local copy. Returns the hash of what the file holds now.
 *
 * When the read-back differs, the file is only put back if it is damaged (not a vault, or the start of
 * what we wrote). A complete vault that is not ours is somebody else's newer version: it is left alone
 * and reported, never overwritten.
 */
export async function writeVaultFile(
  handle: FileSystemFileHandle,
  vaultId: string,
  bytes: Uint8Array,
  expectedHash: string | null,
  force: boolean,
  options: { readbackDelaysMs?: number[] } = {},
): Promise<string> {
  if (!(await canWrite(handle))) throw new FileProblem('permission');
  const current = await readFile(handle).catch((error: unknown) => {
    throw error instanceof FileProblem && error.problem === 'unreadable'
      ? new FileProblem('unavailable')
      : error;
  });
  const currentHash = await sha256Hex(current);
  const expected = await sha256Hex(bytes);
  if (currentHash === expected) {
    // An earlier try landed late (a sync client was slow to show it): the file already holds these bytes.
    await keepBackup(vaultId, bytes).catch(() => undefined);
    await clearPending(vaultId);
    return expected;
  }
  if (!force && expectedHash !== null && currentHash !== expectedHash) {
    throw new FileProblem('changedElsewhere');
  }
  try {
    await keepBackup(vaultId, current);
    await writeOpfs(await directory('pending'), vaultId, bytes);
  } catch {
    throw new FileProblem('backupFailed');
  }
  try {
    const writable = await handle.createWritable();
    await writable.write(bytes as BufferSource);
    await writable.close();
  } catch (error) {
    throw classify(error, 'unavailable');
  }

  let found: Uint8Array | null = null;
  let waited = 0;
  let confirmed = false;
  for (const delay of options.readbackDelaysMs ?? READBACK_DELAYS_MS) {
    if (delay > 0) await sleep(delay);
    waited += delay;
    try {
      found = await readFile(handle);
    } catch {
      continue;
    }
    if ((await sha256Hex(found)) === expected) {
      confirmed = true;
      break;
    }
  }
  if (!confirmed) {
    if (found === null) {
      throw new FileProblem(
        'verificationFailed',
        `the file could not be read after writing (${waited} ms)`,
      );
    }
    const foundHash = await sha256Hex(found);
    const detail = `wrote ${bytes.length} B (${expected.slice(0, 8)}), found ${found.length} B (${foundHash.slice(0, 8)}) after ${waited} ms`;
    console.warn('vault write not confirmed:', detail);
    if (foundHash === currentHash) {
      throw new FileProblem(
        'verificationFailed',
        `${detail}; the previous version is still in place`,
      );
    }
    if (looksLikeVault(found) && !isPrefix(found, bytes)) {
      throw new FileProblem('changedElsewhere', detail);
    }
    try {
      const writable = await handle.createWritable();
      await writable.write(current as BufferSource);
      await writable.close();
    } catch {
      throw new FileProblem('restoreNeeded', detail);
    }
    throw new FileProblem('verificationFailed', `${detail}; the previous version was put back`);
  }
  await keepBackup(vaultId, bytes).catch(() => undefined);
  await clearPending(vaultId);
  return expected;
}

export async function restoreVaultFile(
  handle: FileSystemFileHandle,
  vaultId: string,
): Promise<boolean> {
  const copy = await restorableCopy(vaultId);
  if (!copy) return false;
  if (!(await canWrite(handle))) throw new FileProblem('permission');
  // A readable vault that differs from the copy is somebody's work: keep it before putting the copy over it.
  const present = await readFile(handle).catch(() => null);
  if (present && looksLikeVault(present)) await keepBackup(vaultId, present).catch(() => undefined);
  try {
    const writable = await handle.createWritable();
    await writable.write(copy as BufferSource);
    await writable.close();
  } catch (error) {
    throw classify(error, 'unavailable');
  }
  await clearPending(vaultId);
  return true;
}

export async function createVaultFile(
  handle: FileSystemFileHandle,
  bytes: Uint8Array,
): Promise<string> {
  try {
    const writable = await handle.createWritable();
    await writable.write(bytes as BufferSource);
    await writable.close();
  } catch (error) {
    throw classify(error, 'unavailable');
  }
  const written = await readFile(handle);
  const expected = await sha256Hex(bytes);
  if ((await sha256Hex(written)) !== expected) throw new FileProblem('verificationFailed');
  return expected;
}
