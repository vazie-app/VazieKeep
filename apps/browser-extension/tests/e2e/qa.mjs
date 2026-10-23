// A throwaway Ed25519 key the development build trusts (KEEP_QA_LEASE_KEYS), and leases signed with it.
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const b64u = (value) => Buffer.from(value).toString('base64url');
const FILE = fileURLToPath(new URL('./.qa-key.pem', import.meta.url));

export function qaKey() {
  let privateKey;
  if (existsSync(FILE)) privateKey = createPrivateKey(readFileSync(FILE));
  else {
    ({ privateKey } = generateKeyPairSync('ed25519'));
    writeFileSync(FILE, privateKey.export({ format: 'pem', type: 'pkcs8' }));
  }
  const jwk = createPublicKey(privateKey).export({ format: 'jwk' });
  return { kid: 'qa', privateKey, env: `qa:${jwk.x}` };
}

export function lease(
  key,
  {
    subject = 'acc-1',
    tier = 'keep_plus',
    lifetime = true,
    now = Math.floor(Date.now() / 1000),
  } = {},
) {
  const header = b64u(JSON.stringify({ alg: 'EdDSA', kid: key.kid, typ: 'JWT' }));
  const payload = b64u(
    JSON.stringify({
      version: 1,
      issuer: 'vazie',
      subject,
      product: 'keep',
      tier,
      lifetime,
      issued_at: now - 10,
      lease_expires_at: now + 3 * 86400,
      entitlement_expires_at: lifetime ? null : now + 3 * 86400,
      features_version: 1,
      revision: 1,
      nonce: `n-${now}`,
    }),
  );
  return `${header}.${payload}.${b64u(sign(null, Buffer.from(`${header}.${payload}`), key.privateKey))}`;
}
