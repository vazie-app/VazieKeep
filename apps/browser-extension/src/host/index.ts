import { loadKeepCore, type KeepCore } from '../core/keepCore';
import type { PasswordOptions } from '../core/types';
import type { HostApi } from '../shared/api';
import { BACKGROUND_CHANNEL } from '../shared/content';
import { resolveLanguage, type Language } from '../shared/i18n';
import type { AppState, CommitResult, Settings } from '../shared/model';
import { HostError, serveHost, type HostHandlers, type HostServer } from '../shared/rpc';
import { AccountFailure, AccountService, fetchPrices } from './account';
import { clearNow, scheduleClear } from './clipboard';
import { matchEntries, pageOrigin } from './autofillIndex';
import { ContentService, isContentMessage } from './contentService';
import { loadFaq, loadLinks } from './remote';
import { SettingsStore } from './settings';
import { fetchSiteIcon, fetchSiteIcons } from './siteIcons';
import { VaultManager } from './vaultManager';

const GRACE_AFTER_CLOSE_MS = 10_000;

export interface Host {
  core: KeepCore;
  manager: VaultManager;
}

async function manifestVersion(): Promise<string> {
  try {
    const response = await fetch(chrome.runtime.getURL('manifest.json'));
    return ((await response.json()) as { version?: string }).version ?? '0';
  } catch {
    return '0';
  }
}

export async function startHost(): Promise<Host> {
  const core = await loadKeepCore();
  const version = await manifestVersion();
  const settings = new SettingsStore();
  await settings.load();

  const server: { current: HostServer | undefined } = { current: undefined };
  let scheduled = false;
  const publish = () => {
    if (scheduled) return;
    scheduled = true;
    queueMicrotask(() => {
      scheduled = false;
      server.current?.emit('state', buildState());
    });
  };

  const account = new AccountService(core, publish);
  await account.start();
  const manager = new VaultManager(core, account, publish);
  await manager.load();

  const language = (): Language => resolveLanguage(settings.value.language);

  // ---------------------------------------------------------------- locking

  let lastActivity = Date.now();
  let uiPorts = 0;
  let lockTimer: ReturnType<typeof setTimeout> | undefined;

  const lockTimeoutMs = (): number | null => {
    const mode = settings.value.autoLock;
    if (mode === 'browser' || mode === 'onClose') return null;
    return Number(mode) * 60_000;
  };

  const armLock = () => {
    clearTimeout(lockTimer);
    if (!manager.isUnlocked) return;
    const mode = settings.value.autoLock;
    if (mode === 'onClose') {
      if (uiPorts === 0)
        lockTimer = setTimeout(() => {
          manager.lock();
        }, GRACE_AFTER_CLOSE_MS);
      return;
    }
    const limit = lockTimeoutMs();
    if (limit === null) return;
    const left = lastActivity + limit - Date.now();
    lockTimer = setTimeout(checkLock, Math.max(left, 1000));
  };

  const checkLock = () => {
    const limit = lockTimeoutMs();
    if (manager.isUnlocked && limit !== null && Date.now() - lastActivity >= limit) {
      manager.lock();
    } else {
      armLock();
    }
  };

  const touch = () => {
    lastActivity = Date.now();
    armLock();
  };

  const tellBackground = (cmd: string) => {
    chrome.runtime
      .sendMessage({ channel: BACKGROUND_CHANNEL, cmd, unlocked: manager.isUnlocked })
      .catch(() => undefined);
  };

  const content = new ContentService({
    core,
    manager,
    settings,
    language,
    touch,
    afterCreate: (host) => {
      void (async () => {
        const png = await fetchSiteIcon(core, host).catch(() => null);
        if (png) await manager.edit((s) => s.setSiteIcons([{ host, png }])).catch(() => undefined);
      })();
    },
  });

  manager.onUnlock = () => {
    touch();
    tellBackground('lockState');
  };
  manager.onLock = () => {
    clearTimeout(lockTimer);
    content.clearPending();
    void clearNow();
    tellBackground('lockState');
  };

  chrome.runtime.onConnect.addListener((port) => {
    if (port.sender?.id !== chrome.runtime.id || !port.name.startsWith('keep-ui')) return;
    uiPorts += 1;
    touch();
    port.onDisconnect.addListener(() => {
      uiPorts = Math.max(0, uiPorts - 1);
      armLock();
    });
  });

  chrome.runtime.onMessage.addListener((message: unknown, sender, sendResponse) => {
    if (sender.id !== chrome.runtime.id) return false;
    if (isContentMessage(message)) {
      content.handle(message.request, sender).then(sendResponse, () => {
        sendResponse(null);
      });
      return true;
    }
    if (typeof message === 'object' && message !== null) {
      const command = message as { channel?: unknown; cmd?: unknown };
      if (command.channel === 'keep:host') {
        if (command.cmd === 'tick') checkLock();
        if (command.cmd === 'deviceLocked' && settings.value.lockOnDeviceLock) manager.lock();
        if (command.cmd === 'lock') manager.lock();
        sendResponse({ ok: true });
        return false;
      }
    }
    return false;
  });

  // ---------------------------------------------------------------- state

  const buildState = (): AppState => ({
    version,
    coreVersion: core.version(),
    vaults: manager.list(),
    activeId: manager.selectedId(),
    session: manager.sessionState(),
    rules: manager.rulesState(),
    settings: settings.value,
    account: account.state(),
    lockAt: null,
  });

  const retryResult = async (force: boolean): Promise<CommitResult> => await manager.save(force);

  const handlers: HostHandlers = {
    ping: async () => ({ coreVersion: core.version() }),
    state: async () => buildState(),
    touch: async () => {
      touch();
    },

    addVault: (handle) => manager.addVault(handle),
    createVault: (request) => manager.createVault(request),
    forgetVault: (id) => manager.forgetVault(id),
    renameVault: (id, name) => manager.renameVault(id, name),
    locateVault: (id, handle) => manager.locateVault(id, handle),
    setKeyFile: (id, handle) => manager.setKeyFile(id, handle),
    chooseMainVault: (id) => manager.chooseMainVault(id),
    activateVault: (id) => manager.activateVault(id),

    unlock: async (request) => {
      await manager.unlock(request);
      void account.refresh(false);
    },
    unlockQuick: async (vaultId, prf, keyFile) => {
      await manager.unlockQuick(vaultId, prf, keyFile);
      void account.refresh(false);
    },
    setupQuickUnlock: (vaultId, credentialId, salt, prf) =>
      manager.setupQuickUnlock(vaultId, credentialId, salt, prf),
    quickUnlockRecord: async (vaultId) => manager.quickUnlockRecord(vaultId),
    disableQuickUnlock: (vaultId) => manager.disableQuickUnlock(vaultId),
    lock: async () => {
      manager.lock();
    },
    reload: () => manager.reload(),
    restoreBackup: (vaultId) => manager.restoreBackup(vaultId),
    hasBackup: (vaultId) => manager.hasBackup(vaultId),

    snapshot: async () => {
      touch();
      return manager.snapshot();
    },
    search: async (query) => manager.withSession((s) => s.search(query)),
    siteMatches: async (url) => {
      const origin = pageOrigin(url);
      if (!origin || !manager.isUnlocked) return [];
      return matchEntries(core, manager.index(), origin).map((m) => ({
        id: m.entry.id,
        title: m.entry.title,
        username: m.entry.username,
        hasTotp: m.entry.hasTotp,
        kind: m.kind,
      }));
    },
    entry: async (id) => manager.entry(id),
    reveal: async (id, key) => {
      touch();
      return manager.reveal(id, key);
    },
    totp: async (id) => manager.totp(id),
    history: async (id) => manager.history(id),
    historyEntry: async (id, index) => manager.historyEntry(id, index),
    revealHistory: async (id, index, key) => manager.revealHistory(id, index, key),
    attachment: async (id, name) => manager.attachment(id, name),
    customIcon: async (iconId) => manager.customIcon(iconId),

    createEntry: (groupId, draft) => manager.createEntry(groupId, draft),
    updateEntry: (id, draft) =>
      manager.edit((s) => {
        s.updateEntry(id, draft);
      }),
    deleteEntry: (id, permanent) => manager.edit((s) => s.deleteEntry(id, permanent)),
    moveEntry: (id, groupId) =>
      manager.edit((s) => {
        s.moveEntry(id, groupId);
      }),
    setFavorite: (id, favorite) =>
      manager.edit((s) => {
        s.setFavorite(id, favorite);
      }),
    createGroup: (parentId, name) => manager.edit((s) => s.createGroup(parentId, name)),
    renameGroup: (id, name) =>
      manager.edit((s) => {
        s.renameGroup(id, name);
      }),
    deleteGroup: (id, recursive, permanent) =>
      manager.edit((s) => s.deleteGroup(id, recursive, permanent)),
    setEntryIcon: (id, icon) =>
      manager.edit((s) => {
        s.setEntryIcon(id, icon);
      }),
    setGroupIcon: (id, icon) =>
      manager.edit((s) => {
        s.setGroupIcon(id, icon);
      }),
    restoreHistory: (id, index) =>
      manager.edit((s) => {
        s.restoreHistory(id, index, account.access);
      }),
    addAttachment: (id, name, data) =>
      manager.edit((s) => s.addAttachment(id, name, data, account.access)),
    removeAttachment: (id, name) =>
      manager.edit((s) => {
        s.removeAttachment(id, name);
      }),
    removePasskey: (id) =>
      manager.edit((s) => {
        s.removePasskey(id);
      }),
    parseImport: async (data) => core.parseImport(data),
    dropImport: async (planId) => {
      core.dropImport(planId);
    },
    applyImport: async (planId, groupId) => {
      const result = await manager.edit((s) => s.importPlan(groupId, planId));
      core.dropImport(planId);
      return result;
    },
    assignIconsByUrl: () => manager.edit((s) => s.assignIconsByUrl()),
    siteIconHosts: async () => manager.withSession((s) => s.siteIconHosts()),
    fetchSiteIcons: async () => {
      const hosts = manager.withSession((s) => s.siteIconHosts());
      if (hosts.length === 0) return { value: { found: 0, asked: 0 }, saved: true, problem: null };
      const icons = await fetchSiteIcons(core, hosts, (done, total) => {
        server.current?.emit('siteIconProgress', { done, total });
      });
      if (icons.length === 0)
        return { value: { found: 0, asked: hosts.length }, saved: true, problem: null };
      const edited = await manager.edit((s) => s.setSiteIcons(icons));
      return { ...edited, value: { found: edited.value, asked: hosts.length } };
    },

    retrySave: () => retryResult(false),
    overwrite: () => retryResult(true),
    exportCopy: async () => manager.exportCopy(),
    allowEditing: () => manager.allowEditing(),

    generatePassword: async (options: PasswordOptions) => {
      const bytes = core.generatePassword(options);
      const text = new TextDecoder().decode(bytes);
      bytes.fill(0);
      return text;
    },
    securityPresets: async () => core.securityPresets(),
    benchmark: async (level) => core.benchmarkKdf(manager.levelSettings(level)),
    generateKeyFile: async () => core.generateKeyFile(),
    totpNormalize: async (input) => {
      const bytes = new TextEncoder().encode(input);
      return core.totpNormalize(bytes, account.access);
    },

    settings: async () => settings.value,
    updateSettings: async (patch: Partial<Settings>) => {
      const next = await settings.update(patch);
      armLock();
      tellBackground('settings');
      publish();
      return next;
    },
    neverSave: async (host, never) => {
      const list = new Set(settings.value.neverSave);
      if (never) list.add(host);
      else list.delete(host);
      await settings.update({ neverSave: [...list] });
      publish();
    },

    requestCode: async (email) => {
      try {
        await account.requestCode(email);
      } catch (error) {
        throw accountError(error);
      }
    },
    signIn: async (email, code) => {
      try {
        await account.signIn(email, code);
      } catch (error) {
        throw accountError(error);
      }
    },
    refreshAccount: (force) => account.refresh(force),
    signOut: () => account.signOut(),
    deleteAccount: () => account.deleteAccount(),
    prices: () => fetchPrices(),

    faq: () => loadFaq(),
    links: () => loadLinks(),

    clipboardCopied: async (digest) => {
      scheduleClear(digest, settings.value.clipboardClear);
    },
  } satisfies Omit<HostApi, never>;

  if (__KEEP_DEBUG__) {
    (globalThis as { __keepTest?: unknown }).__keepTest = {
      ageActivity: (ms: number) => {
        lastActivity -= ms;
        checkLock();
      },
      locked: () => !manager.isUnlocked,
      ports: () => uiPorts,
    };
  }

  server.current = serveHost(handlers);
  setInterval(() => {
    void account.reevaluate();
  }, 60_000);
  publish();
  return { core, manager };
}

function accountError(error: unknown): HostError {
  return new HostError(error instanceof AccountFailure ? error.kind : 'unavailable');
}
