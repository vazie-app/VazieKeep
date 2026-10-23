/**
 * Runs in the page's own world. It stands in front of `navigator.credentials` for passkey requests and
 * hands them to the extension; anything the extension does not take goes to the browser untouched.
 */
const FLAG = 'vazieKeepPasskey';
const BRIDGE_TIMEOUT_MS = 5 * 60 * 1000;

type Fallback = { fallback: true };
type Failure = { ok: false; error: string };

interface CreatedPayload {
  id: string;
  rawId: string;
  clientDataJSON: string;
  attestationObject: string;
  authenticatorData: string;
  publicKey: string;
  publicKeyAlgorithm: number;
}

interface AssertedPayload {
  id: string;
  rawId: string;
  clientDataJSON: string;
  authenticatorData: string;
  signature: string;
  userHandle: string;
}

function toBase64url(source: BufferSource): string {
  const bytes =
    source instanceof ArrayBuffer
      ? new Uint8Array(source)
      : new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function toBuffer(text: string): ArrayBuffer {
  const padded = text.replaceAll('-', '+').replaceAll('_', '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

let counter = 0;
const waiting = new Map<number, (value: unknown) => void>();

window.addEventListener('message', (event) => {
  if (event.source !== window) return;
  const data = event.data as Record<string, unknown> | null;
  if (!data || data[FLAG] !== 1 || data.dir !== 'res' || typeof data.id !== 'number') return;
  const resolve = waiting.get(data.id);
  if (resolve) {
    waiting.delete(data.id);
    resolve(data.result);
  }
});

type Answer = Fallback | Failure | { ok: true; credential: unknown };

function bridge(
  kind: 'create' | 'get' | 'conditional',
  options: unknown,
  signal: AbortSignal | null | undefined,
): { id: number; promise: Promise<Answer> } {
  counter += 1;
  const id = counter;
  const promise = new Promise<Answer>((resolve) => {
    let settled = false;
    const done = (value: unknown) => {
      if (settled) return;
      settled = true;
      waiting.delete(id);
      clearTimeout(timer);
      resolve(value as Answer);
    };
    const timer = setTimeout(() => {
      window.postMessage({ [FLAG]: 1, dir: 'cancel', id }, '*');
      done({ fallback: true });
    }, BRIDGE_TIMEOUT_MS);
    waiting.set(id, done);
    signal?.addEventListener('abort', () => {
      window.postMessage({ [FLAG]: 1, dir: 'cancel', id }, '*');
      done({ ok: false, error: 'AbortError' });
    });
    window.postMessage({ [FLAG]: 1, dir: 'req', id, kind, options }, '*');
  });
  return { id, promise };
}

function define(target: object, values: Record<string, unknown>): void {
  for (const [name, value] of Object.entries(values)) {
    Object.defineProperty(target, name, {
      value,
      enumerable: true,
      configurable: true,
      writable: false,
    });
  }
}

function credentialObject(
  id: string,
  rawId: ArrayBuffer,
  response: object,
  results: Record<string, unknown>,
  json: () => unknown,
): PublicKeyCredential {
  const prototype =
    typeof PublicKeyCredential === 'undefined' ? Object.prototype : PublicKeyCredential.prototype;
  const credential = Object.create(prototype) as PublicKeyCredential;
  define(credential, {
    id,
    rawId,
    type: 'public-key',
    authenticatorAttachment: 'platform',
    response,
    getClientExtensionResults: () => results,
    toJSON: json,
  });
  return credential;
}

function attestationResponse(payload: CreatedPayload): AuthenticatorAttestationResponse {
  const prototype =
    typeof AuthenticatorAttestationResponse === 'undefined'
      ? Object.prototype
      : AuthenticatorAttestationResponse.prototype;
  const response = Object.create(prototype) as AuthenticatorAttestationResponse;
  const authenticatorData = toBuffer(payload.authenticatorData);
  const publicKey = toBuffer(payload.publicKey);
  define(response, {
    clientDataJSON: toBuffer(payload.clientDataJSON),
    attestationObject: toBuffer(payload.attestationObject),
    getTransports: () => ['internal', 'hybrid'],
    getAuthenticatorData: () => authenticatorData,
    getPublicKey: () => publicKey,
    getPublicKeyAlgorithm: () => payload.publicKeyAlgorithm,
  });
  return response;
}

function assertionResponse(payload: AssertedPayload): AuthenticatorAssertionResponse {
  const prototype =
    typeof AuthenticatorAssertionResponse === 'undefined'
      ? Object.prototype
      : AuthenticatorAssertionResponse.prototype;
  const response = Object.create(prototype) as AuthenticatorAssertionResponse;
  define(response, {
    clientDataJSON: toBuffer(payload.clientDataJSON),
    authenticatorData: toBuffer(payload.authenticatorData),
    signature: toBuffer(payload.signature),
    userHandle: payload.userHandle === '' ? null : toBuffer(payload.userHandle),
  });
  return response;
}

function rejection(error: string): DOMException {
  const message =
    error === 'InvalidStateError'
      ? 'The user attempted to register an authenticator that contains one of the credentials already registered with the relying party.'
      : error === 'AbortError'
        ? 'The operation was aborted.'
        : 'The operation either timed out or was not allowed.';
  return new DOMException(message, error);
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function acceptsEs256(params: unknown): boolean {
  if (!Array.isArray(params) || params.length === 0) return true;
  return params.some((p: { alg?: unknown }) => p.alg === -7);
}

function install(): void {
  const container = navigator.credentials;
  const found = container as CredentialsContainer | undefined;
  if (
    !window.isSecureContext ||
    typeof found?.create !== 'function' ||
    typeof found.get !== 'function'
  )
    return;
  const nativeCreate = container.create.bind(container);
  const nativeGet = container.get.bind(container);

  const create = async function create(
    options?: CredentialCreationOptions,
  ): Promise<Credential | null> {
    const publicKey = options?.publicKey;
    if (!publicKey || options.signal?.aborted) return nativeCreate(options);
    const selection = publicKey.authenticatorSelection;
    if (
      selection?.authenticatorAttachment === 'cross-platform' ||
      (publicKey as { hints?: string[] }).hints?.includes('security-key')
    ) {
      return nativeCreate(options);
    }
    let payload: unknown;
    try {
      payload = {
        rpId: publicKey.rp.id ?? null,
        rpName: text(publicKey.rp.name),
        userName: text(publicKey.user.name),
        userDisplayName: text(publicKey.user.displayName),
        userHandle: toBase64url(publicKey.user.id),
        challenge: toBase64url(publicKey.challenge),
        exclude: (publicKey.excludeCredentials ?? []).map((c) => toBase64url(c.id)),
        acceptsEs256: acceptsEs256(publicKey.pubKeyCredParams),
        crossOrigin: window.top !== window,
      };
    } catch {
      return nativeCreate(options);
    }
    const answer = await bridge('create', payload, options.signal).promise;
    if ('fallback' in answer) return nativeCreate(options);
    if (!answer.ok) throw rejection(answer.error);
    const made = (answer as { credential: CreatedPayload }).credential;
    const rawId = toBuffer(made.rawId);
    return credentialObject(
      made.id,
      rawId,
      attestationResponse(made),
      { credProps: { rk: true } },
      () => ({
        id: made.id,
        rawId: made.rawId,
        type: 'public-key',
        authenticatorAttachment: 'platform',
        response: {
          clientDataJSON: made.clientDataJSON,
          attestationObject: made.attestationObject,
          authenticatorData: made.authenticatorData,
          publicKey: made.publicKey,
          publicKeyAlgorithm: made.publicKeyAlgorithm,
          transports: ['internal', 'hybrid'],
        },
        clientExtensionResults: { credProps: { rk: true } },
      }),
    );
  };

  const gotCredential = (got: AssertedPayload): PublicKeyCredential =>
    credentialObject(got.id, toBuffer(got.rawId), assertionResponse(got), {}, () => ({
      id: got.id,
      rawId: got.rawId,
      type: 'public-key',
      authenticatorAttachment: 'platform',
      response: {
        clientDataJSON: got.clientDataJSON,
        authenticatorData: got.authenticatorData,
        signature: got.signature,
        userHandle: got.userHandle,
      },
      clientExtensionResults: {},
    }));

  const get = async function get(options?: CredentialRequestOptions): Promise<Credential | null> {
    const publicKey = options?.publicKey;
    if (!publicKey || options.signal?.aborted) return nativeGet(options);
    if ((publicKey as { hints?: string[] }).hints?.includes('security-key'))
      return nativeGet(options);
    let payload: unknown;
    try {
      payload = {
        rpId: publicKey.rpId ?? null,
        challenge: toBase64url(publicKey.challenge),
        allow: (publicKey.allowCredentials ?? []).map((c) => toBase64url(c.id)),
        crossOrigin: window.top !== window,
      };
    } catch {
      return nativeGet(options);
    }

    if (options.mediation === 'conditional') {
      // The browser's own list stays; Vazie Keep adds its passkeys to the field's menu. Whichever answers first wins.
      const own = new AbortController();
      const signal = options.signal ? AbortSignal.any([options.signal, own.signal]) : own.signal;
      return await new Promise<Credential | null>((resolve, reject) => {
        let settled = false;
        const waiting = bridge('conditional', payload, options.signal);
        nativeGet({ ...options, signal }).then(
          (value) => {
            if (settled) return;
            settled = true;
            window.postMessage({ [FLAG]: 1, dir: 'cancel', id: waiting.id }, '*');
            resolve(value);
          },
          (error: unknown) => {
            if (settled) return;
            settled = true;
            window.postMessage({ [FLAG]: 1, dir: 'cancel', id: waiting.id }, '*');
            reject(error instanceof Error ? error : rejection('NotAllowedError'));
          },
        );
        void waiting.promise.then((answer) => {
          if (settled || 'fallback' in answer) return;
          settled = true;
          own.abort();
          if (!answer.ok) reject(rejection(answer.error));
          else resolve(gotCredential((answer as { credential: AssertedPayload }).credential));
        });
      });
    }

    const answer = await bridge('get', payload, options.signal).promise;
    if ('fallback' in answer) return nativeGet(options);
    if (!answer.ok) throw rejection(answer.error);
    return gotCredential((answer as { credential: AssertedPayload }).credential);
  };

  Object.defineProperty(container, 'create', { value: create, configurable: true, writable: true });
  Object.defineProperty(container, 'get', { value: get, configurable: true, writable: true });
}

try {
  install();
} catch {
  /* the page keeps the browser's own passkeys */
}
