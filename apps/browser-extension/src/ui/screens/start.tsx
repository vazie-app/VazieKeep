import { useEffect, useState } from 'preact/hooks';
import type { VaultListItem } from '../../shared/model';
import { Confirm, Dialog, showToast } from '../components/dialogs';
import { Banner, Button, Card, Empty, IconButton, Row, TextField, TopBar } from '../components/ui';
import { ensureAccess, isPopup, openInTab, pickKeyFile, pickVaultFile } from '../files';
import { Icon } from '../icons';
import { host } from '../state/client';
import { useApp, useT } from '../state/hooks';
import { go, replace } from '../state/route';
import { codeOf, errorText } from '../util';
import { formatDate } from '../../shared/i18n';
import { useLanguage } from '../state/language';
import { quickUnlockSupported, quickUnlockWithPasskey } from '../quickUnlock';

export function Welcome() {
  const t = useT();
  return (
    <div class="app">
      <div class="scroll">
        <div class="page welcome">
          <img class="welcome-icon" src="../icons/classic-128.png" alt="" width="88" height="88" />
          <p class="welcome-by">{t('welcome.by')}</p>
          <h1 class="welcome-title">
            <span>{t('welcome.line1')}</span>
            <span>{t('welcome.line2')}</span>
            <span>{t('welcome.line3')}</span>
          </h1>
          <Card>
            <Row
              leading={<Icon name="offline" size={22} />}
              title={t('welcome.offlineTitle')}
              subtitle={t('welcome.offlineBody')}
            />
            <Row
              leading={<Icon name="user" size={22} />}
              title={t('welcome.accountTitle')}
              subtitle={t('welcome.accountBody')}
            />
            <Row
              leading={<Icon name="file" size={22} />}
              title={t('welcome.kdbxTitle')}
              subtitle={t('welcome.kdbxBody')}
            />
          </Card>
          <Button
            variant="action"
            size="large"
            full
            onClick={() => {
              void host.updateSettings({ welcomeSeen: true });
            }}
          >
            {t('welcome.start')}
          </Button>
        </div>
      </div>
    </div>
  );
}

export function Hub() {
  const t = useT();
  const language = useLanguage();
  const app = useApp();
  const [adding, setAdding] = useState(false);
  const [menu, setMenu] = useState<VaultListItem | null>(null);
  const [rename, setRename] = useState<VaultListItem | null>(null);
  const [forget, setForget] = useState<VaultListItem | null>(null);
  const [locate, setLocate] = useState<VaultListItem | null>(null);
  const [missing, setMissing] = useState<Record<string, boolean>>({});
  const vaults = app?.vaults ?? [];

  useEffect(() => {
    const life = new AbortController();
    void (async () => {
      const { vaultGet } = await import('../../host/storage');
      const found: Record<string, boolean> = {};
      for (const vault of vaults) {
        const record = await vaultGet(vault.id);
        if (!record) continue;
        try {
          if ((await record.handle.queryPermission({ mode: 'read' })) === 'granted') {
            await record.handle.getFile();
          }
        } catch (error) {
          if (error instanceof DOMException && error.name === 'NotFoundError')
            found[vault.id] = true;
        }
      }
      if (!life.signal.aborted) setMissing(found);
    })();
    return () => {
      life.abort();
    };
  }, [vaults.length]);

  if (!app) return null;
  const { rules } = app;

  async function addFile() {
    if (isPopup()) {
      openInTab('#hub');
      return;
    }
    setAdding(true);
    try {
      const handle = await pickVaultFile();
      if (!handle) return;
      const { id } = await host.addVault(handle);
      await host.activateVault(id);
      replace({ name: 'vault' });
    } catch (error) {
      showToast(errorText(codeOf(error)), 'danger');
    } finally {
      setAdding(false);
    }
  }

  function open(vault: VaultListItem) {
    void (async () => {
      if (app?.session && app.session.vaultId !== vault.id) await host.lock();
      await host.activateVault(vault.id);
      replace({ name: 'vault' });
    })();
  }

  return (
    <div class="app">
      <TopBar
        title={t('hub.title')}
        onBack={
          vaults.some((v) => v.unlocked) || vaults.length > 0
            ? () => {
                replace({ name: 'vault' });
              }
            : undefined
        }
        actions={
          <IconButton
            icon="settings"
            label={t('settings.title')}
            onClick={() => {
              go({ name: 'settings', page: 'home' });
            }}
          />
        }
      />
      <div class="scroll">
        <div class="page">
          {vaults.length === 0 ? (
            <Empty icon="key" title={t('hub.emptyTitle')} message={t('chooser.tagline')} />
          ) : (
            <Card>
              {vaults.map((vault) => (
                <Row
                  key={vault.id}
                  onClick={() => {
                    open(vault);
                  }}
                  leading={
                    <span class="tile tile-accent" style={{ width: '44px', height: '44px' }}>
                      <Icon
                        name={missing[vault.id] ? 'warning' : vault.unlocked ? 'unlock' : 'lock'}
                        size={22}
                      />
                    </span>
                  }
                  title={vault.name}
                  subtitle={
                    missing[vault.id]
                      ? t('hub.unavailable')
                      : vault.lastUsedAt === 0
                        ? t('hub.neverOpened')
                        : t('hub.lastOpened', {
                            date: formatDate(language, Math.floor(vault.lastUsedAt / 1000)),
                          })
                  }
                  trailing={
                    <>
                      {vault.main && rules.mainId ? (
                        <span class="badge badge-accent">{t('hub.main')}</span>
                      ) : null}
                      {!vault.editable ? <span class="badge">{t('hub.readOnly')}</span> : null}
                      {vault.hasKeyFile ? <Icon name="key" size={16} /> : null}
                      {vault.active ? (
                        <span class="badge badge-accent">{t('hub.current')}</span>
                      ) : null}
                      <IconButton
                        icon="more"
                        label={t('hub.actions', { name: vault.name })}
                        onClick={(event) => {
                          event.stopPropagation();
                          setMenu(vault);
                        }}
                      />
                    </>
                  }
                />
              ))}
            </Card>
          )}
          {Object.values(missing).some(Boolean) ? (
            <Banner tone="caution">
              <strong>{t('hub.unavailable')}.</strong> {t('hub.unavailableBody')}
            </Banner>
          ) : null}
          {!rules.canAdd && vaults.length > 0 ? (
            <Banner
              tone="info"
              icon="star"
              action={t('plus.about')}
              onAction={() => {
                go({ name: 'settings', page: 'plus' });
              }}
            >
              <strong>{t('hub.moreVaultsTitle')}.</strong>{' '}
              {vaults.length > 1 ? t('hub.moreVaultsSeveral') : t('hub.moreVaultsOne')}
            </Banner>
          ) : null}
          {rules.canAdd ? (
            <div class="stack">
              <Button
                variant="action"
                size="large"
                full
                icon="folder"
                busy={adding}
                onClick={() => {
                  void addFile();
                }}
              >
                {t('hub.openFile')}
              </Button>
              <Button
                variant="tonal"
                size="large"
                full
                icon="add"
                onClick={() => {
                  if (isPopup()) openInTab('#create');
                  else go({ name: 'create' });
                }}
              >
                {t('hub.create')}
              </Button>
              {isPopup() ? <p class="caption">{t('hub.tabHint')}</p> : null}
            </div>
          ) : null}
          <p class="caption center">{t('chooser.footer')}</p>
        </div>
      </div>

      {menu ? (
        <Dialog
          title={menu.name}
          onClose={() => {
            setMenu(null);
          }}
        >
          <div class="stack-tight">
            <Button
              variant="raised"
              full
              icon="edit"
              onClick={() => {
                setRename(menu);
                setMenu(null);
              }}
            >
              {t('hub.rename')}
            </Button>
            {missing[menu.id] ? (
              <Button
                variant="raised"
                full
                icon="folder"
                onClick={() => {
                  setLocate(menu);
                  setMenu(null);
                }}
              >
                {t('hub.locate')}
              </Button>
            ) : null}
            <Button
              variant="caution"
              full
              icon="trash"
              onClick={() => {
                setForget(menu);
                setMenu(null);
              }}
            >
              {t('hub.forget')}
            </Button>
          </div>
        </Dialog>
      ) : null}
      {rename ? (
        <RenameVault
          vault={rename}
          onClose={() => {
            setRename(null);
          }}
        />
      ) : null}
      {forget ? (
        <Confirm
          title={t('hub.forgetTitle', { name: forget.name })}
          message={t('hub.forgetMessage')}
          confirm={t('hub.forget')}
          tone="caution"
          onCancel={() => {
            setForget(null);
          }}
          onConfirm={() => {
            void host.forgetVault(forget.id);
            setForget(null);
          }}
        />
      ) : null}
      {locate ? (
        <Locate
          vault={locate}
          onClose={() => {
            setLocate(null);
          }}
        />
      ) : null}
    </div>
  );
}

function RenameVault({ vault, onClose }: { vault: VaultListItem; onClose: () => void }) {
  const t = useT();
  const [name, setName] = useState(vault.name);
  const [error, setError] = useState<string | null>(null);
  async function save() {
    try {
      await host.renameVault(vault.id, name);
      onClose();
    } catch (cause) {
      setError(errorText(codeOf(cause)));
    }
  }
  return (
    <Dialog
      title={t('hub.renameTitle')}
      onClose={onClose}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('action.cancel')}
          </Button>
          <Button
            variant="action"
            onClick={() => {
              void save();
            }}
          >
            {t('action.save')}
          </Button>
        </>
      }
    >
      <p class="dialog-message">{t('hub.renameMessage')}</p>
      <TextField
        label={t('create.nameLabel')}
        value={name}
        onInput={setName}
        autofocus
        error={error}
        onEnter={() => {
          void save();
        }}
      />
    </Dialog>
  );
}

function Locate({ vault, onClose }: { vault: VaultListItem; onClose: () => void }) {
  const t = useT();
  useEffect(() => {
    if (isPopup()) {
      openInTab('#hub');
      return;
    }
    void (async () => {
      try {
        const handle = await pickVaultFile();
        if (handle) await host.locateVault(vault.id, handle);
      } catch {
        showToast(t('hub.locateFailed'), 'danger');
      } finally {
        onClose();
      }
    })();
  }, []);
  return null;
}

export function Unlock({ vault }: { vault: VaultListItem }) {
  const t = useT();
  const app = useApp();
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [canRestore, setCanRestore] = useState(false);
  const [quick, setQuick] = useState(false);
  const [picked, setPicked] = useState<{ handle: FileSystemFileHandle; name: string } | null>(null);
  const [removedKeyFile, setRemovedKeyFile] = useState(false);

  useEffect(() => {
    setPassword('');
    setError(null);
    setPicked(null);
    setRemovedKeyFile(false);
    setCanRestore(false);
    void quickUnlockSupported().then((ok) => {
      setQuick(ok && vault.quickUnlock);
    });
  }, [vault.id]);

  const keyFileName = removedKeyFile ? null : (picked?.name ?? vault.keyFileName);

  async function attempt(run: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      const access = await ensureAccess(vault.id);
      if (!access.read) {
        setError(t('error.fileUnavailable'));
        return;
      }
      await run();
      setPassword('');
    } catch (cause) {
      const code = codeOf(cause);
      if (code === 'damaged' || code === 'not_a_vault') {
        setCanRestore(await host.hasBackup(vault.id));
      }
      if (code === 'invalid_credentials')
        setError(keyFileName ? t('error.wrongPasswordOrKeyFile') : t('error.wrongPassword'));
      else if (code === 'missing_credentials') setError(t('error.missingCredentials'));
      else setError(errorText(code));
    } finally {
      setBusy(false);
    }
  }

  async function submit() {
    if (busy) return;
    const typed = new TextEncoder().encode(password);
    await attempt(async () => {
      try {
        if (removedKeyFile && vault.hasKeyFile) await host.setKeyFile(vault.id, null);
        await host.unlock({
          vaultId: vault.id,
          password: typed,
          ...(picked ? { keyFileHandle: picked.handle, rememberKeyFile: true } : {}),
        });
      } finally {
        typed.fill(0);
      }
    });
  }

  async function submitQuick() {
    await attempt(async () => {
      await quickUnlockWithPasskey(vault.id);
    });
  }

  async function chooseKeyFile() {
    if (isPopup()) {
      openInTab('#unlock');
      return;
    }
    const handle = await pickKeyFile();
    if (handle) {
      setPicked({ handle, name: handle.name });
      setRemovedKeyFile(false);
    }
  }

  async function restore() {
    setBusy(true);
    try {
      await host.restoreBackup(vault.id);
      setCanRestore(false);
      setError(null);
      showToast(t('unlock.restoreDone'));
    } catch {
      showToast(t('unlock.restoreFailed'), 'danger');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div class="app">
      <TopBar
        title={t('app.name')}
        actions={
          <>
            <IconButton
              icon="swap"
              label={t('unlock.databases')}
              onClick={() => {
                go({ name: 'hub' });
              }}
            />
            <IconButton
              icon="settings"
              label={t('settings.title')}
              onClick={() => {
                go({ name: 'settings', page: 'home' });
              }}
            />
          </>
        }
      />
      <div class="scroll">
        <div class="page unlock">
          <div class="unlock-head">
            <span
              class="tile tile-accent"
              style={{ width: '64px', height: '64px', borderRadius: '20px' }}
            >
              <Icon name="lock" size={30} />
            </span>
            <h1>{vault.name}</h1>
            <p class="caption">{vault.fileName}</p>
          </div>
          {app && !vault.editable ? <Banner tone="info">{t('vault.readOnlyPlan')}</Banner> : null}
          <form
            class="stack"
            onSubmit={(event) => {
              event.preventDefault();
              void submit();
            }}
          >
            <TextField
              label={t('unlock.passwordLabel')}
              value={password}
              onInput={setPassword}
              type="password"
              mono
              autofocus
              autocomplete="current-password"
              error={error}
              onEnter={() => {
                void submit();
              }}
            />
            <div class="stack-tight">
              <span class="field-label">{t('keyFile.label')}</span>
              {keyFileName ? (
                <div class="cluster">
                  <span class="badge badge-accent">
                    <Icon name="key" size={14} /> {keyFileName}
                  </span>
                  <Button
                    variant="text"
                    size="pill"
                    onClick={() => {
                      void chooseKeyFile();
                    }}
                  >
                    {t('keyFile.change')}
                  </Button>
                  <Button
                    variant="text"
                    size="pill"
                    onClick={() => {
                      setPicked(null);
                      setRemovedKeyFile(true);
                    }}
                  >
                    {t('keyFile.remove')}
                  </Button>
                </div>
              ) : (
                <div>
                  <Button
                    variant="raised"
                    size="pill"
                    icon="key"
                    onClick={() => {
                      void chooseKeyFile();
                    }}
                  >
                    {t('keyFile.choose')}
                  </Button>
                </div>
              )}
            </div>
            {busy ? <p class="caption">{t('unlock.progress')}</p> : null}
            <Button
              variant="action"
              size="large"
              full
              type="submit"
              busy={busy}
              disabled={password === '' && !keyFileName}
            >
              {t('unlock.action')}
            </Button>
            {quick ? (
              <Button
                variant="tonal"
                size="large"
                full
                icon="biometric"
                disabled={busy}
                onClick={() => {
                  void submitQuick();
                }}
              >
                {t('unlock.quick')}
              </Button>
            ) : null}
            {canRestore ? (
              <Banner
                tone="caution"
                action={t('unlock.restore')}
                onAction={() => {
                  void restore();
                }}
              >
                {t('error.damagedRestorable')}
              </Banner>
            ) : null}
          </form>
        </div>
      </div>
    </div>
  );
}
