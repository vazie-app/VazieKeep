import {
  BACKGROUND_CHANNEL,
  type ContentStrings,
  type PasskeyCreateOptions,
  type PasskeyGetOptions,
} from '../shared/content';
import type { Overlay } from './ui';
import type { Ask } from './page';

export const CHANNEL_FLAG = 'vazieKeepPasskey';

interface CreateRequest {
  [CHANNEL_FLAG]: 1;
  dir: 'req';
  id: number;
  kind: 'create';
  options: PasskeyCreateOptions;
}

interface GetRequest {
  [CHANNEL_FLAG]: 1;
  dir: 'req';
  id: number;
  kind: 'get';
  options: PasskeyGetOptions;
}

interface ConditionalRequest {
  [CHANNEL_FLAG]: 1;
  dir: 'req';
  id: number;
  kind: 'conditional';
  options: PasskeyGetOptions;
}

interface CancelRequest {
  [CHANNEL_FLAG]: 1;
  dir: 'cancel';
  id: number;
}

type Incoming = CreateRequest | GetRequest | ConditionalRequest | CancelRequest;

function isIncoming(data: unknown): data is Incoming {
  if (typeof data !== 'object' || data === null) return false;
  const message = data as Record<string, unknown>;
  return (
    message[CHANNEL_FLAG] === 1 &&
    (message.dir === 'req' || message.dir === 'cancel') &&
    typeof message.id === 'number'
  );
}

function reply(id: number, result: unknown): void {
  window.postMessage({ [CHANNEL_FLAG]: 1, dir: 'res', id, result }, '*');
}

const LOCK_POLL_MS = 1500;
const LOCK_POLL_LIMIT = 60;

let conditional: { id: number; options: PasskeyGetOptions } | null = null;
let askRef: Ask | null = null;

/** The sign-in request the page left waiting for a choice from the field's own menu, if any. */
export function conditionalOptions(): PasskeyGetOptions | null {
  return conditional?.options ?? null;
}

/** Signs the waiting request with the chosen passkey and hands the answer to the page. */
export async function completeConditional(entryId: string): Promise<boolean> {
  const waiting = conditional;
  if (!waiting || !askRef) return false;
  const got = await askRef({ type: 'passkeyGet', options: waiting.options, entryId });
  if (!got?.ok) return false;
  conditional = null;
  reply(waiting.id, { ok: true, credential: got.value });
  return true;
}

export function initPasskeyBridge(overlay: Overlay, ask: Ask): void {
  const active = new Map<number, () => void>();
  askRef = ask;

  window.addEventListener('message', (event) => {
    if (event.source !== window || !isIncoming(event.data)) return;
    const message = event.data;
    if (message.dir === 'cancel') {
      if (conditional?.id === message.id) conditional = null;
      active.get(message.id)?.();
      return;
    }
    if (message.kind === 'create') void create(message.id, message.options);
    else if (message.kind === 'conditional')
      conditional = { id: message.id, options: message.options };
    else void get(message.id, message.options);
  });

  function waitForUnlock(rpId: string, done: () => void, cancelled: () => boolean): void {
    chrome.runtime
      .sendMessage({ channel: BACKGROUND_CHANNEL, cmd: 'openPopup' })
      .catch(() => undefined);
    let attempts = 0;
    const timer = setInterval(() => {
      attempts += 1;
      if (cancelled() || attempts > LOCK_POLL_LIMIT) {
        clearInterval(timer);
        return;
      }
      void ask({ type: 'passkeyList', rpId, allow: [] }).then((list) => {
        if (list?.ok && list.unlocked) {
          clearInterval(timer);
          done();
        }
      });
    }, LOCK_POLL_MS);
  }

  async function create(id: number, options: PasskeyCreateOptions): Promise<void> {
    let finished = false;
    const finish = (result: unknown) => {
      if (finished) return;
      finished = true;
      active.delete(id);
      overlay.hideModal();
      reply(id, result);
    };
    active.set(id, () => {
      finish({ ok: false, error: 'AbortError' });
    });
    const list = await ask({
      type: 'passkeyList',
      rpId: options.rpId ?? location.hostname,
      allow: [],
    });
    if (!list?.ok) {
      finish({ fallback: true });
      return;
    }
    const strings = list.strings;
    const rpId = list.rpId;
    const render = (unlocked: boolean) => {
      overlay.showModal({
        title: strings.passkeyCreateTitle,
        lines: [
          options.rpName && options.rpName !== rpId ? `${options.rpName} (${rpId})` : rpId,
          options.userName || options.userDisplayName,
        ].filter(Boolean),
        ...(unlocked ? {} : { note: strings.passkeyUnlock }),
        primary: unlocked ? strings.passkeyCreate : strings.unlock,
        other: strings.passkeyOther,
        onPrimary: () => {
          if (!unlocked) {
            waitForUnlock(
              rpId,
              () => {
                render(true);
              },
              () => finished,
            );
            return;
          }
          void (async () => {
            const made = await ask({ type: 'passkeyCreate', options });
            if (made?.ok) {
              finish({ ok: true, credential: made.value });
            } else if (made?.reason === 'exists') {
              finish({ ok: false, error: 'InvalidStateError' });
              overlay.flash(strings.passkeyExists);
            } else if (made?.reason === 'changed' || made?.reason === 'not-saved') {
              finish({ ok: false, error: 'NotAllowedError' });
              overlay.flash(strings.saveFailed);
            } else {
              finish({ fallback: true });
            }
          })();
        },
        onOther: () => {
          finish({ fallback: true });
        },
        onCancel: () => {
          finish({ ok: false, error: 'NotAllowedError' });
        },
      });
    };
    render(list.unlocked);
  }

  async function get(id: number, options: PasskeyGetOptions): Promise<void> {
    let finished = false;
    const finish = (result: unknown) => {
      if (finished) return;
      finished = true;
      active.delete(id);
      overlay.hideModal();
      reply(id, result);
    };
    active.set(id, () => {
      finish({ ok: false, error: 'AbortError' });
    });
    const list = await ask({
      type: 'passkeyList',
      rpId: options.rpId ?? location.hostname,
      allow: options.allow,
    });
    if (!list?.ok || !list.unlocked || list.choices.length === 0) {
      finish({ fallback: true });
      return;
    }
    const strings: ContentStrings = list.strings;
    overlay.showModal({
      title: strings.passkeyUseTitle,
      lines: [list.rpId],
      choices: list.choices.map((choice) => ({
        id: choice.id,
        label: choice.title,
        sub: choice.username,
      })),
      primary: strings.passkeyUse,
      other: strings.passkeyOther,
      onPrimary: (entryId) => {
        void (async () => {
          const got = await ask({ type: 'passkeyGet', options, entryId });
          if (got?.ok) finish({ ok: true, credential: got.value });
          else finish({ fallback: true });
        })();
      },
      onOther: () => {
        finish({ fallback: true });
      },
      onCancel: () => {
        finish({ ok: false, error: 'NotAllowedError' });
      },
    });
  }
}
