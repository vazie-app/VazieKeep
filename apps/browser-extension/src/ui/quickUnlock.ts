import { host } from './state/client';

interface PrfOutputs {
  enabled?: boolean;
  results?: { first?: ArrayBuffer };
}

function randomBytes(length: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(length));
}

function prfOf(credential: PublicKeyCredential): PrfOutputs | undefined {
  return (credential.getClientExtensionResults() as { prf?: PrfOutputs }).prf;
}

/** Quick unlock needs a passkey that can derive a secret (the WebAuthn PRF extension): a security key, or a platform authenticator that supports it. */
export async function quickUnlockSupported(): Promise<boolean> {
  if (typeof PublicKeyCredential === 'undefined') return false;
  try {
    const capabilities = (
      PublicKeyCredential as unknown as {
        getClientCapabilities?: () => Promise<Record<string, boolean>>;
      }
    ).getClientCapabilities;
    if (capabilities) {
      const all = await capabilities.call(PublicKeyCredential);
      if (all['extension:prf'] === false) return false;
    }
    return true;
  } catch {
    return true;
  }
}

async function assertPrf(credentialId: Uint8Array, salt: Uint8Array): Promise<Uint8Array> {
  const credential = (await navigator.credentials.get({
    publicKey: {
      challenge: randomBytes(32) as BufferSource,
      allowCredentials: [{ id: credentialId as BufferSource, type: 'public-key' }],
      userVerification: 'required',
      timeout: 60_000,
      extensions: { prf: { eval: { first: salt as BufferSource } } },
    },
  })) as PublicKeyCredential | null;
  const first = credential ? prfOf(credential)?.results?.first : undefined;
  if (!first) throw new Error('prf_unsupported');
  return new Uint8Array(first);
}

export async function setupQuickUnlock(vaultId: string, label: string): Promise<void> {
  const salt = randomBytes(32);
  let created: PublicKeyCredential | null;
  try {
    created = (await navigator.credentials.create({
      publicKey: {
        rp: { name: 'Vazie Keep', id: location.hostname },
        user: { id: randomBytes(16) as BufferSource, name: label, displayName: label },
        challenge: randomBytes(32) as BufferSource,
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 },
          { type: 'public-key', alg: -257 },
        ],
        authenticatorSelection: { residentKey: 'discouraged', userVerification: 'required' },
        timeout: 60_000,
        extensions: { prf: {} },
      },
    })) as PublicKeyCredential | null;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') {
      throw new Error('quick_unlock_cancelled', { cause: error });
    }
    throw new Error('quick_unlock_unavailable', { cause: error });
  }
  if (!created) throw new Error('quick_unlock_cancelled');
  if (prfOf(created)?.enabled === false) throw new Error('prf_unsupported');
  const credentialId = new Uint8Array(created.rawId);
  let output: Uint8Array;
  try {
    output = await assertPrf(credentialId, salt);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') {
      throw new Error('quick_unlock_cancelled', { cause: error });
    }
    throw error;
  }
  await host.setupQuickUnlock(vaultId, credentialId, salt, output);
}

export async function quickUnlockWithPasskey(vaultId: string): Promise<void> {
  const record = await host.quickUnlockRecord(vaultId);
  if (!record) throw new Error('quick_unlock_off');
  let output: Uint8Array;
  try {
    output = await assertPrf(record.credentialId, record.salt);
  } catch (error) {
    if (error instanceof DOMException) throw new Error('quick_unlock_failed', { cause: error });
    throw error;
  }
  await host.unlockQuick(vaultId, output);
}
