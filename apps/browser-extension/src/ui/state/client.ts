import { BACKGROUND_CHANNEL } from '../../shared/content';
import { resolveLanguage, translator, type Language, type Translate } from '../../shared/i18n';
import type { AppState, Snapshot } from '../../shared/model';
import { createHostClient } from '../../shared/rpc';
import { Atom } from './atom';

export type Surface = 'popup' | 'tab' | 'panel';

export function surface(): Surface {
  const value = document.documentElement.dataset.surface;
  return value === 'tab' || value === 'panel' ? value : 'popup';
}

export const connection = new Atom<'connecting' | 'ready' | 'down'>('connecting');
export const appState = new Atom<AppState | null>(null);
export const snapshot = new Atom<Snapshot | null>(null);
export const language = new Atom<Language>(resolveLanguage('system'));
export const translate = new Atom<Translate>(translator(language.get()));

export const host = createHostClient();

language.subscribe((value) => {
  translate.set(translator(value));
  document.documentElement.lang = value;
});

let snapshotRequest = 0;
let snapshotKey = '';

async function refreshSnapshot(state: AppState): Promise<void> {
  const session = state.session;
  if (!session) {
    snapshotKey = '';
    snapshot.set(null);
    return;
  }
  const key = `${session.vaultId}:${session.dataVersion}`;
  if (key === snapshotKey) return;
  snapshotKey = key;
  const request = ++snapshotRequest;
  try {
    const next = await host.snapshot();
    if (request === snapshotRequest) snapshot.set(next);
  } catch {
    if (request === snapshotRequest) snapshot.set(null);
  }
}

function adopt(state: AppState): void {
  appState.set(state);
  language.set(resolveLanguage(state.settings.language));
  applyTheme(state.settings.theme);
  void refreshSnapshot(state);
}

export function applyTheme(mode: 'system' | 'light' | 'dark'): void {
  document.documentElement.dataset.theme = mode;
}

let booted = false;

export async function boot(): Promise<void> {
  if (booted) return;
  booted = true;
  host.on('state', adopt);
  let ready = await host.waitForHost(2500);
  if (!ready) {
    await chrome.runtime
      .sendMessage({ channel: BACKGROUND_CHANNEL, cmd: 'ensureHost' })
      .catch(() => undefined);
    ready = await host.waitForHost(8000);
  }
  if (!ready) {
    connection.set('down');
    return;
  }
  try {
    chrome.runtime.connect({ name: `keep-ui:${surface()}` });
  } catch {
    /* the host will notice the missing presence on its own */
  }
  adopt(await host.state());
  connection.set('ready');
  let last = 0;
  const touch = () => {
    const now = Date.now();
    if (now - last < 4000) return;
    last = now;
    void host.touch().catch(() => undefined);
  };
  addEventListener('pointerdown', touch, { capture: true, passive: true });
  addEventListener('keydown', touch, { capture: true, passive: true });
}

export function t(key: Parameters<Translate>[0], params?: Parameters<Translate>[1]): string {
  return translate.get()(key, params);
}
