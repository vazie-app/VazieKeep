const DB_NAME = 'vazie-keep';
const DB_VERSION = 1;

export interface QuickUnlockRecord {
  credentialId: Uint8Array;
  salt: Uint8Array;
  iv: Uint8Array;
  sealed: Uint8Array;
  createdAt: number;
}

export interface VaultRecord {
  id: string;
  name: string;
  fileName: string;
  handle: FileSystemFileHandle;
  fingerprint: string | null;
  lastUsedAt: number;
  keyFileHandle: FileSystemFileHandle | null;
  keyFileName: string | null;
  quickUnlock: QuickUnlockRecord | null;
}

let opening: Promise<IDBDatabase> | undefined;

function database(): Promise<IDBDatabase> {
  opening ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
      if (!db.objectStoreNames.contains('vaults'))
        db.createObjectStore('vaults', { keyPath: 'id' });
    };
    request.onsuccess = () => {
      resolve(request.result);
    };
    request.onerror = () => {
      reject(request.error ?? new Error('storage_unavailable'));
    };
  });
  return opening;
}

async function run<T>(
  store: 'kv' | 'vaults',
  mode: IDBTransactionMode,
  body: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await database();
  return await new Promise<T>((resolve, reject) => {
    const transaction = db.transaction(store, mode);
    const request = body(transaction.objectStore(store));
    transaction.oncomplete = () => {
      resolve(request.result);
    };
    transaction.onerror = () => {
      reject(transaction.error ?? new Error('storage_unavailable'));
    };
    transaction.onabort = () => {
      reject(transaction.error ?? new Error('storage_unavailable'));
    };
  });
}

export async function kvGet<T>(key: string): Promise<T | undefined> {
  return (
    (await run<T | undefined>('kv', 'readonly', (s) => s.get(key) as IDBRequest<T | undefined>)) ??
    undefined
  );
}

export async function kvSet(key: string, value: unknown): Promise<void> {
  await run('kv', 'readwrite', (s) => s.put(value, key));
}

export async function kvDelete(key: string): Promise<void> {
  await run('kv', 'readwrite', (s) => s.delete(key));
}

export async function vaultsAll(): Promise<VaultRecord[]> {
  return await run<VaultRecord[]>(
    'vaults',
    'readonly',
    (s) => s.getAll() as IDBRequest<VaultRecord[]>,
  );
}

export async function vaultGet(id: string): Promise<VaultRecord | undefined> {
  return (
    (await run<VaultRecord | undefined>(
      'vaults',
      'readonly',
      (s) => s.get(id) as IDBRequest<VaultRecord | undefined>,
    )) ?? undefined
  );
}

export async function vaultPut(record: VaultRecord): Promise<void> {
  await run('vaults', 'readwrite', (s) => s.put(record));
}

export async function vaultDelete(id: string): Promise<void> {
  await run('vaults', 'readwrite', (s) => s.delete(id));
}

export function randomId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
