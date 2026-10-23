import type { EventName, HostApi, HostEvents, HostMethod } from './api';

const CHANNEL = 'vazie-keep:host';

type Request = { kind: 'req'; id: number; from: string; method: string; args: unknown[] };
type Response =
  | { kind: 'res'; id: number; to: string; ok: true; value: unknown }
  | { kind: 'res'; id: number; to: string; ok: false; error: string; detail?: string };
type Event = { kind: 'evt'; name: string; payload: unknown };
type Message = Request | Response | Event;

export class HostError extends Error {
  constructor(
    readonly code: string,
    readonly detail?: string,
  ) {
    super(code);
    this.name = 'HostError';
  }
}

function clientId(): string {
  return crypto.randomUUID();
}

export type HostClient = {
  [K in HostMethod]: HostApi[K];
} & {
  on<E extends EventName>(name: E, handler: (payload: HostEvents[E]) => void): () => void;
  waitForHost(timeoutMs?: number): Promise<boolean>;
};

export function createHostClient(options: { onUnreachable?: () => void } = {}): HostClient {
  const id = clientId();
  const channel = new BroadcastChannel(CHANNEL);
  const pending = new Map<
    number,
    { resolve: (value: unknown) => void; reject: (error: Error) => void }
  >();
  const listeners = new Map<string, Set<(payload: never) => void>>();
  let counter = 0;

  channel.onmessage = (event: MessageEvent<Message>) => {
    const message = event.data;
    if (message.kind === 'res' && message.to === id) {
      const waiter = pending.get(message.id);
      if (!waiter) return;
      pending.delete(message.id);
      if (message.ok) waiter.resolve(message.value);
      else waiter.reject(new HostError(message.error, message.detail));
    } else if (message.kind === 'evt') {
      for (const handler of listeners.get(message.name) ?? []) {
        (handler as (payload: unknown) => void)(message.payload);
      }
    }
  };

  function call(method: string, args: unknown[], timeoutMs: number): Promise<unknown> {
    return new Promise((resolve, reject) => {
      counter += 1;
      const requestId = counter;
      const timer =
        timeoutMs > 0
          ? setTimeout(() => {
              if (pending.delete(requestId)) {
                options.onUnreachable?.();
                reject(new HostError('host_unreachable'));
              }
            }, timeoutMs)
          : undefined;
      pending.set(requestId, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      const request: Request = { kind: 'req', id: requestId, from: id, method, args };
      channel.postMessage(request);
    });
  }

  const base = {
    on<E extends EventName>(name: E, handler: (payload: HostEvents[E]) => void) {
      let set = listeners.get(name);
      if (!set) {
        set = new Set();
        listeners.set(name, set);
      }
      set.add(handler);
      return () => {
        set.delete(handler);
      };
    },
    async waitForHost(timeoutMs = 4000) {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        try {
          await call('ping', [], 600);
          return true;
        } catch {
          /* the host is still starting */
        }
      }
      return false;
    },
  };

  return new Proxy(base, {
    get(target, property) {
      if (typeof property !== 'string') return undefined;
      if (property in target) return (target as Record<string, unknown>)[property];
      return (...args: unknown[]) => call(property, args, 0);
    },
  }) as unknown as HostClient;
}

export type HostHandlers = {
  [K in HostMethod]: (...args: Parameters<HostApi[K]>) => ReturnType<HostApi[K]>;
};

export interface HostServer {
  emit<E extends EventName>(name: E, payload: HostEvents[E]): void;
}

export function serveHost(handlers: HostHandlers): HostServer {
  const channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = (event: MessageEvent<Message>) => {
    const message = event.data;
    if (message.kind !== 'req') return;
    const handler = (handlers as unknown as Record<string, (...args: unknown[]) => unknown>)[
      message.method
    ];
    const reply = (
      response: { ok: true; value: unknown } | { ok: false; error: string; detail?: string },
    ) => {
      channel.postMessage({ kind: 'res', id: message.id, to: message.from, ...response });
    };
    if (typeof handler !== 'function') {
      reply({ ok: false, error: 'unknown_method' });
      return;
    }
    Promise.resolve()
      .then(() => handler(...message.args))
      .then(
        (value) => {
          reply({ ok: true, value });
        },
        (error: unknown) => {
          const code = errorCode(error);
          if (code === 'unavailable') console.error('host call failed', message.method, error);
          reply({
            ok: false,
            error: code,
            detail: error instanceof Error ? error.message : String(error),
          });
        },
      );
  };
  return {
    emit(name, payload) {
      channel.postMessage({ kind: 'evt', name, payload });
    },
  };
}

export function errorCode(error: unknown): string {
  if (error instanceof HostError) return error.code;
  if (error instanceof Error) {
    return /^[a-z][a-z0-9_]*$/.test(error.message) ? error.message : 'unavailable';
  }
  return 'unavailable';
}
