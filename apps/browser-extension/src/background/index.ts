import { BACKGROUND_CHANNEL, CONTENT_CHANNEL, PAGE_CHANNEL } from '../shared/content';
import { resolveLanguage, translator } from '../shared/i18n';
import { DEFAULT_SETTINGS, ICON_COLORS, type IconColor, type Settings } from '../shared/model';
import { kvGet } from '../host/storage';

const OFFSCREEN_PATH = 'offscreen/index.html';
const HOST_CHANNEL = 'keep:host';

let ensuring: Promise<void> | undefined;

async function hostExists(): Promise<boolean> {
  const contexts = await chrome.runtime.getContexts({
    contextTypes: [chrome.runtime.ContextType.OFFSCREEN_DOCUMENT],
  });
  return contexts.length > 0;
}

function ensureHost(): Promise<void> {
  ensuring ??= (async () => {
    try {
      if (await hostExists()) return;
      await chrome.offscreen.createDocument({
        url: OFFSCREEN_PATH,
        reasons: [chrome.offscreen.Reason.CLIPBOARD, chrome.offscreen.Reason.BLOBS],
        justification:
          'Keeps the unlocked vault in memory while the popup is closed, and clears the clipboard after a copied password times out.',
      });
    } finally {
      ensuring = undefined;
    }
  })();
  return ensuring;
}

async function settings(): Promise<Settings> {
  return { ...DEFAULT_SETTINGS, ...((await kvGet<Partial<Settings>>('settings')) ?? {}) };
}

async function applyIcon(color: IconColor): Promise<void> {
  const chosen = ICON_COLORS.includes(color) ? color : 'classic';
  const path = Object.fromEntries(
    [16, 32, 48, 128].map((size) => [String(size), `icons/${chosen}-${size}.png`]),
  );
  await chrome.action.setIcon({ path });
}

async function applyState(unlocked: boolean): Promise<void> {
  const t = translator(resolveLanguage((await settings()).language));
  await chrome.action.setTitle({ title: t(unlocked ? 'bg.titleUnlocked' : 'bg.titleLocked') });
  await chrome.action.setBadgeBackgroundColor({ color: '#5D43A8' });
  await chrome.action.setBadgeText({ text: unlocked ? '●' : '' });
}

async function buildMenus(): Promise<void> {
  const t = translator(resolveLanguage((await settings()).language));
  await chrome.contextMenus.removeAll();
  chrome.contextMenus.create({
    id: 'fill',
    title: t('bg.menuFill'),
    contexts: ['editable', 'page'],
  });
  chrome.contextMenus.create({ id: 'lock', title: t('bg.menuLock'), contexts: ['action'] });
}

async function tellHost(cmd: string): Promise<void> {
  await ensureHost();
  await chrome.runtime.sendMessage({ channel: HOST_CHANNEL, cmd }).catch(() => undefined);
}

async function refresh(): Promise<void> {
  await ensureHost();
  const current = await settings();
  await applyIcon(current.toolbarIcon);
  await buildMenus();
  chrome.idle.setDetectionInterval(15);
  await chrome.alarms.create('tick', { periodInMinutes: 1 });
  await applyState(false);
}

async function activeTab(): Promise<chrome.tabs.Tab | undefined> {
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  return tab;
}

async function fillActiveTab(): Promise<void> {
  const tab = await activeTab();
  if (tab?.id === undefined) return;
  await chrome.tabs
    .sendMessage(tab.id, { channel: PAGE_CHANNEL, cmd: 'fillBest' })
    .catch(() => undefined);
}

chrome.runtime.onInstalled.addListener((details) => {
  void refresh();
  if (details.reason === 'install') {
    void chrome.tabs.create({ url: chrome.runtime.getURL('ui/tab.html') });
  }
});

chrome.runtime.onStartup.addListener(() => {
  void refresh();
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'tick') void tellHost('tick');
});

chrome.idle.onStateChanged.addListener((state) => {
  if (state === 'locked') void tellHost('deviceLocked');
});

chrome.commands.onCommand.addListener((command) => {
  if (command === 'fill-login') void fillActiveTab();
  if (command === 'lock-vault') void tellHost('lock');
});

chrome.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId === 'fill') void fillActiveTab();
  if (info.menuItemId === 'lock') void tellHost('lock');
});

chrome.runtime.onMessage.addListener((message: unknown, sender) => {
  if (sender.id !== chrome.runtime.id || typeof message !== 'object' || message === null) {
    return false;
  }
  const command = message as { channel?: unknown; cmd?: unknown; unlocked?: unknown };
  if (command.channel === CONTENT_CHANNEL) {
    // A page asked and nobody answered if the host is gone: bring it back for the next ask.
    void ensureHost();
    return false;
  }
  if (command.channel !== BACKGROUND_CHANNEL) return false;
  switch (command.cmd) {
    case 'lockState':
      void applyState(command.unlocked === true);
      break;
    case 'settings':
      void settings().then((current) => applyIcon(current.toolbarIcon));
      void buildMenus();
      break;
    case 'openPopup':
      void chrome.action.openPopup().catch(() => {
        void chrome.tabs.create({ url: chrome.runtime.getURL('ui/tab.html') });
      });
      break;
    case 'ensureHost':
      void ensureHost();
      break;
  }
  return false;
});

void ensureHost();
chrome.idle.setDetectionInterval(15);
