import { kvGet, kvSet } from './storage';

export interface Sealed {
  iv: Uint8Array;
  data: Uint8Array;
}

async function deviceKey(): Promise<CryptoKey> {
  const stored = await kvGet<CryptoKey>('deviceKey');
  if (stored) return stored;
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
    'encrypt',
    'decrypt',
  ]);
  await kvSet('deviceKey', key);
  return key;
}

async function encrypt(key: CryptoKey, bytes: Uint8Array): Promise<Sealed> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, bytes as BufferSource);
  return { iv, data: new Uint8Array(data) };
}

async function decrypt(key: CryptoKey, sealed: Sealed): Promise<Uint8Array> {
  const data = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: sealed.iv as BufferSource },
    key,
    sealed.data as BufferSource,
  );
  return new Uint8Array(data);
}

/** Seals text with a key that never leaves this browser profile: it protects files at rest, not the open browser. */
export async function sealWithDevice(text: string): Promise<Sealed> {
  return await encrypt(await deviceKey(), new TextEncoder().encode(text));
}

export async function openWithDevice(sealed: Sealed): Promise<string | null> {
  try {
    return new TextDecoder().decode(await decrypt(await deviceKey(), sealed));
  } catch {
    return null;
  }
}

async function prfKey(prf: Uint8Array, salt: Uint8Array): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey('raw', prf as BufferSource, 'HKDF', false, [
    'deriveKey',
  ]);
  return await crypto.subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: salt as BufferSource,
      info: new TextEncoder().encode('vazie-keep/quick-unlock/v1'),
    },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

export async function sealWithPrf(
  prf: Uint8Array,
  salt: Uint8Array,
  secret: Uint8Array,
): Promise<Sealed> {
  return await encrypt(await prfKey(prf, salt), secret);
}

export async function openWithPrf(
  prf: Uint8Array,
  salt: Uint8Array,
  sealed: Sealed,
): Promise<Uint8Array | null> {
  try {
    return await decrypt(await prfKey(prf, salt), sealed);
  } catch {
    return null;
  }
}
