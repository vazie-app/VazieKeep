import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import {
  ICON_COLORS,
  type AutoLock,
  type ClipboardClear,
  type IconColor,
  type LanguageChoice,
  type ThemeMode,
} from '../../shared/model';
import { Confirm, showToast } from '../components/dialogs';
import {
  Badge,
  Banner,
  Button,
  Card,
  Row,
  SectionLabel,
  Segmented,
  SelectRow,
  Switch,
  TopBar,
} from '../components/ui';
import { isPopup, openInTab, openSidePanel, pickKeyFile, saveAs, writeHandle } from '../files';
import { Icon } from '../icons';
import { host, surface } from '../state/client';
import { useApp, useSnapshot, useT } from '../state/hooks';
import { back, go, reset, useRoute, type SettingsPage } from '../state/route';
import { quickUnlockSupported, setupQuickUnlock } from '../quickUnlock';
import { codeOf, errorText } from '../util';
import { AboutScreen, FaqScreen, GuidesScreen } from './about';
import { AccountScreen, DeleteAccountScreen, PlusScreen, planOf } from './account';

export function SettingsFrame({ title, children }: { title: string; children: ComponentChildren }) {
  return (
    <div class="app">
      <TopBar title={title} onBack={back} />
      <div class="scroll">
        <div class="page">{children}</div>
      </div>
    </div>
  );
}

export function SettingsScreen() {
  const route = useRoute();
  if (route.name !== 'settings') return null;
  switch (route.page) {
    case 'appearance':
      return <AppearanceSettings />;
    case 'security':
      return <SecuritySettings />;
    case 'autofill':
      return <AutofillSettings />;
    case 'vaults':
      return <VaultSettings />;
    case 'account':
      return <AccountScreen />;
    case 'deleteAccount':
      return <DeleteAccountScreen />;
    case 'plus':
      return <PlusScreen />;
    case 'about':
      return <AboutScreen />;
    case 'faq':
      return <FaqScreen />;
    case 'guides':
      return <GuidesScreen guide={route.guide ?? null} />;
    default:
      return <SettingsHome />;
  }
}

function open(page: SettingsPage) {
  go({ name: 'settings', page });
}

function SettingsHome() {
  const t = useT();
  const app = useApp();
  if (!app) return null;
  const plan = planOf(app.account);
  return (
    <SettingsFrame title={t('settings.title')}>
      <SectionLabel>{t('settings.accountSection')}</SectionLabel>
      <Card>
        <Row
          onClick={() => {
            open('account');
          }}
          leading={<Icon name="user" size={22} />}
          title={t('account.title')}
          subtitle={
            app.account.signedIn
              ? (app.account.email ?? t('account.rowSignedIn'))
              : t('account.rowSignedOut')
          }
          chevron
        />
        <Row
          onClick={() => {
            open('plus');
          }}
          leading={<Icon name="star" size={22} />}
          title={t('settings.plusTitle')}
          subtitle={
            plan === 'lifetime'
              ? t('plus.rowLifetime')
              : plan === 'plus'
                ? t('plus.rowActive')
                : t('plus.rowBody')
          }
          trailing={plan === 'free' ? <Badge tone="accent">Keep+</Badge> : undefined}
          chevron
        />
      </Card>

      <SectionLabel>{t('settings.securitySection')}</SectionLabel>
      <Card>
        <Row
          onClick={() => {
            open('security');
          }}
          leading={<Icon name="shield" size={22} />}
          title={t('settings.securitySection')}
          subtitle={t('settings.securityRow')}
          chevron
        />
        <Row
          onClick={() => {
            open('autofill');
          }}
          leading={<Icon name="key" size={22} />}
          title={t('settings.autofillSection')}
          subtitle={t('settings.autofillRow')}
          chevron
        />
        {app.session ? (
          <Row
            onClick={() => {
              open('vaults');
            }}
            leading={<Icon name="lock" size={22} />}
            title={t('settings.thisVault')}
            subtitle={app.session.name}
            chevron
          />
        ) : null}
        <Row
          onClick={() => {
            go({ name: 'hub' });
          }}
          leading={<Icon name="swap" size={22} />}
          title={t('hub.title')}
          subtitle={t('settings.vaultsRow')}
          chevron
        />
      </Card>

      <SectionLabel>{t('settings.lookSection')}</SectionLabel>
      <Card>
        <Row
          onClick={() => {
            open('appearance');
          }}
          leading={<Icon name="palette" size={22} />}
          title={t('settings.appearance')}
          subtitle={t('settings.appearanceRow')}
          chevron
        />
        {app.session ? (
          <Row
            onClick={() => {
              go({ name: 'import' });
            }}
            leading={<Icon name="download" size={22} />}
            title={t('import.action')}
            chevron
          />
        ) : null}
        {surface() === 'popup' ? (
          <>
            <Row
              onClick={() => {
                openInTab();
              }}
              leading={<Icon name="expand" size={22} />}
              title={t('action.openTab')}
            />
            <Row
              onClick={() => {
                void openSidePanel();
              }}
              leading={<Icon name="sidebar" size={22} />}
              title={t('action.openSidePanel')}
            />
          </>
        ) : null}
      </Card>

      <SectionLabel>{t('settings.helpSection')}</SectionLabel>
      <Card>
        <Row
          onClick={() => {
            open('guides');
          }}
          leading={<Icon name="book" size={22} />}
          title={t('settings.guidesRow')}
          subtitle={t('settings.guidesRowBody')}
          chevron
        />
        <Row
          onClick={() => {
            open('faq');
          }}
          leading={<Icon name="info" size={22} />}
          title={t('settings.faqRow')}
          subtitle={t('settings.faqRowBody')}
          chevron
        />
      </Card>

      <SectionLabel>{t('settings.aboutSection')}</SectionLabel>
      <Card>
        <Row
          onClick={() => {
            open('about');
          }}
          leading={<Icon name="info" size={22} />}
          title={t('about.rowTitle')}
          subtitle={t('settings.aboutRowBody')}
          chevron
        />
      </Card>
      <p class="caption center">{t('settings.footer', { version: app.version })}</p>
      <p class="caption center">{t('settings.footerMadeBy')}</p>
    </SettingsFrame>
  );
}

const ICON_NAMES: Record<IconColor, Parameters<ReturnType<typeof useT>>[0]> = {
  classic: 'icon.classic',
  blue: 'icon.blue',
  gray: 'icon.gray',
  green: 'icon.green',
  pink: 'icon.pink',
  red: 'icon.red',
  violet: 'icon.violet',
  yellow: 'icon.yellow',
};

function AppearanceSettings() {
  const t = useT();
  const app = useApp();
  if (!app) return null;
  const { settings } = app;
  return (
    <SettingsFrame title={t('settings.appearance')}>
      <SectionLabel>{t('settings.theme')}</SectionLabel>
      <Segmented<ThemeMode>
        label={t('settings.theme')}
        value={settings.theme}
        options={[
          { value: 'system', label: t('theme.system') },
          { value: 'light', label: t('theme.light') },
          { value: 'dark', label: t('theme.dark') },
        ]}
        onChange={(theme) => {
          void host.updateSettings({ theme });
        }}
      />
      <SectionLabel>{t('settings.language')}</SectionLabel>
      <Segmented<LanguageChoice>
        label={t('settings.language')}
        value={settings.language}
        options={[
          { value: 'system', label: t('language.system') },
          { value: 'en', label: 'English' },
          { value: 'ru', label: 'Русский' },
        ]}
        onChange={(language) => {
          void host.updateSettings({ language });
        }}
      />
      <SectionLabel>{t('settings.toolbarIcon')}</SectionLabel>
      <div class="icon-choices" role="radiogroup" aria-label={t('settings.toolbarIcon')}>
        {ICON_COLORS.map((color) => (
          <button
            key={color}
            type="button"
            role="radio"
            aria-checked={settings.toolbarIcon === color}
            class={`icon-color${settings.toolbarIcon === color ? ' icon-color-on' : ''}`}
            onClick={() => {
              void host.updateSettings({ toolbarIcon: color });
            }}
          >
            <img src={`../icons/${color}-48.png`} alt="" width="40" height="40" />
            <span>{t(ICON_NAMES[color])}</span>
          </button>
        ))}
      </div>
      <p class="caption">{t('settings.toolbarIconHint')}</p>
    </SettingsFrame>
  );
}

function SecuritySettings() {
  const t = useT();
  const app = useApp();
  if (!app) return null;
  const { settings } = app;
  const lockOptions: { value: AutoLock; label: string }[] = [
    { value: 'onClose', label: t('lock.onClose') },
    { value: '1', label: t('lock.minutes', { count: 1 }) },
    { value: '5', label: t('lock.minutes', { count: 5 }) },
    { value: '15', label: t('lock.minutes', { count: 15 }) },
    { value: '30', label: t('lock.minutes', { count: 30 }) },
    { value: '60', label: t('lock.hour') },
    { value: 'browser', label: t('lock.browser') },
  ];
  const clipboardOptions: { value: ClipboardClear; label: string }[] = [
    { value: 15, label: t('clipboard.seconds', { count: 15 }) },
    { value: 30, label: t('clipboard.seconds', { count: 30 }) },
    { value: 60, label: t('clipboard.seconds', { count: 60 }) },
    { value: 0, label: t('timeout.never') },
  ];
  return (
    <SettingsFrame title={t('settings.securitySection')}>
      <Card>
        <SelectRow<AutoLock>
          label={t('settings.autoLock')}
          value={settings.autoLock}
          options={lockOptions}
          hint={
            settings.autoLock === 'onClose'
              ? t('lock.onCloseHint')
              : settings.autoLock === 'browser'
                ? t('lock.browserHint')
                : t('lock.idleHint')
          }
          onChange={(autoLock) => {
            void host.updateSettings({ autoLock });
          }}
        />
        <Switch
          label={t('settings.lockOnDevice')}
          hint={t('settings.lockOnDeviceHint')}
          checked={settings.lockOnDeviceLock}
          onChange={(lockOnDeviceLock) => {
            void host.updateSettings({ lockOnDeviceLock });
          }}
        />
      </Card>
      <Card>
        <SelectRow<ClipboardClear>
          label={t('settings.clipboard')}
          value={settings.clipboardClear}
          options={clipboardOptions}
          onChange={(clipboardClear) => {
            void host.updateSettings({ clipboardClear });
          }}
        />
      </Card>
      {settings.clipboardClear === 0 ? (
        <Banner tone="caution">{t('settings.clipboardNeverWarning')}</Banner>
      ) : null}
      <Banner tone="info" icon="shield">
        {t('settings.memoryNote')}
      </Banner>
    </SettingsFrame>
  );
}

function AutofillSettings() {
  const t = useT();
  const app = useApp();
  if (!app) return null;
  const { settings } = app;
  return (
    <SettingsFrame title={t('settings.autofillSection')}>
      <p class="dialog-message">{t('settings.autofillHint')}</p>
      <Card>
        <Switch
          label={t('settings.autofillEnabled')}
          hint={t('settings.autofillEnabledHint')}
          checked={settings.autofill}
          onChange={(autofill) => {
            void host.updateSettings({ autofill });
          }}
        />
        <Switch
          label={t('settings.autofillSave')}
          hint={t('settings.autofillSaveHint')}
          checked={settings.autofillSaveOffers}
          onChange={(autofillSaveOffers) => {
            void host.updateSettings({ autofillSaveOffers });
          }}
        />
        <Switch
          label={t('settings.passkeys')}
          hint={t('settings.passkeysHint')}
          checked={settings.passkeys}
          onChange={(passkeys) => {
            void host.updateSettings({ passkeys });
          }}
        />
        <Switch
          label={t('settings.siteIcons')}
          hint={t('settings.siteIconsHint')}
          checked={settings.siteIconsAutomatic}
          onChange={(siteIconsAutomatic) => {
            void host.updateSettings({ siteIconsAutomatic });
          }}
        />
      </Card>
      <Card>
        <Row
          onClick={() => {
            void chrome.tabs.create({ url: 'chrome://extensions/shortcuts' });
          }}
          leading={<Icon name="terminal" size={22} />}
          title={t('settings.shortcuts')}
          subtitle={t('settings.shortcutsHint')}
          chevron
        />
      </Card>
      {settings.neverSave.length > 0 ? (
        <>
          <SectionLabel>{t('settings.neverSaveTitle')}</SectionLabel>
          <Card>
            {settings.neverSave.map((site) => (
              <Row
                key={site}
                leading={<Icon name="globe" size={20} />}
                title={site}
                trailing={
                  <Button
                    variant="text"
                    size="pill"
                    onClick={() => {
                      void host.neverSave(site, false);
                    }}
                  >
                    {t('keyFile.remove')}
                  </Button>
                }
              />
            ))}
          </Card>
        </>
      ) : null}
    </SettingsFrame>
  );
}

function VaultSettings() {
  const t = useT();
  const app = useApp();
  const data = useSnapshot();
  const [supported, setSupported] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [confirmIcons, setConfirmIcons] = useState(false);
  const [confirmForget, setConfirmForget] = useState(false);
  const session = app?.session;
  const vault = app?.vaults.find((v) => v.id === session?.vaultId);

  useEffect(() => {
    void quickUnlockSupported().then(setSupported);
    return host.on('siteIconProgress', setProgress);
  }, []);

  if (!app || !session || !vault || !data) return null;
  const { vaultId, name: vaultName } = session;

  async function toggleQuick(on: boolean) {
    setBusy('quick');
    try {
      if (on) {
        await setupQuickUnlock(vaultId, vaultName);
        showToast(t('quick.enabled'));
      } else {
        await host.disableQuickUnlock(vaultId);
      }
    } catch (error) {
      const code = codeOf(error);
      showToast(
        code === 'quick_unlock_cancelled'
          ? t('quick.cancelled')
          : code === 'prf_unsupported'
            ? t('quick.noPrf')
            : t('quick.enableFailed'),
        'danger',
      );
    } finally {
      setBusy(null);
    }
  }

  async function changeKeyFile() {
    if (isPopup()) {
      openInTab('#vaults');
      return;
    }
    const handle = await pickKeyFile();
    if (handle) await host.setKeyFile(vaultId, handle);
  }

  async function getIcons() {
    setConfirmIcons(false);
    setBusy('icons');
    setProgress(null);
    try {
      const result = await host.fetchSiteIcons();
      showToast(
        result.value.found > 0
          ? t('icons.done', { count: result.value.found })
          : t('icons.unavailable'),
        result.value.found > 0 ? 'info' : 'danger',
      );
    } catch (error) {
      showToast(errorText(codeOf(error)), 'danger');
    } finally {
      setBusy(null);
      setProgress(null);
    }
  }

  async function assign() {
    setBusy('assign');
    try {
      const result = await host.assignIconsByUrl();
      showToast(
        result.value > 0 ? t('icons.assigned', { count: result.value }) : t('icons.nothing'),
      );
    } finally {
      setBusy(null);
    }
  }

  async function exportCopy() {
    try {
      const bytes = await host.exportCopy();
      const handle = await saveAs(`${vaultName}-copy.kdbx`);
      if (!handle) return;
      await writeHandle(handle, bytes);
      showToast(t('copy.saved'));
    } catch {
      showToast(t('copy.failedSave'), 'danger');
    }
  }

  const info = data.info;
  return (
    <SettingsFrame title={t('settings.thisVault')}>
      <Card>
        <Row
          title={session.name}
          subtitle={vault.fileName}
          leading={<Icon name="lock" size={22} />}
        />
        <Row
          title={t('about.format')}
          subtitle={`KDBX ${info.format} · ${info.kdf} · ${info.cipher}`}
          leading={<Icon name="file" size={22} />}
        />
        <Row
          title={t('vault.counts', {
            entries: info.entryCount,
            groups: Math.max(0, info.groupCount - 1),
          })}
          leading={<Icon name="folder" size={22} />}
        />
      </Card>

      <Card>
        <Switch
          label={t('quick.title')}
          hint={
            !supported
              ? t('quick.unsupported')
              : vault.quickUnlock
                ? t('quick.on')
                : session.hasRetainedMaterial
                  ? t('quick.ready')
                  : t('quick.keyFileOnly')
          }
          checked={vault.quickUnlock}
          disabled={
            !supported || busy === 'quick' || (!vault.quickUnlock && !session.hasRetainedMaterial)
          }
          onChange={(on) => {
            void toggleQuick(on);
          }}
        />
      </Card>

      <Card>
        <Row
          leading={<Icon name="key" size={22} />}
          title={t('keyFile.label')}
          subtitle={vault.keyFileName ?? t('keyFile.none')}
          trailing={
            <>
              <Button
                variant="text"
                size="pill"
                onClick={() => {
                  void changeKeyFile();
                }}
              >
                {vault.keyFileName ? t('keyFile.change') : t('keyFile.choose')}
              </Button>
              {vault.keyFileName ? (
                <Button
                  variant="text"
                  size="pill"
                  onClick={() => {
                    void host.setKeyFile(vault.id, null);
                  }}
                >
                  {t('keyFile.remove')}
                </Button>
              ) : null}
            </>
          }
        />
      </Card>

      <Card>
        <Row
          onClick={() => {
            go({ name: 'import' });
          }}
          leading={<Icon name="download" size={22} />}
          title={t('import.action')}
          chevron
        />
        <Row
          onClick={() => {
            if (!busy) setConfirmIcons(true);
          }}
          leading={<Icon name="globe" size={22} />}
          title={t('icons.assignAction')}
          subtitle={
            busy === 'icons' && progress
              ? t('icons.progress', { done: progress.done, total: progress.total })
              : undefined
          }
          disabled={!session.editable || busy !== null}
        />
        <Row
          onClick={() => {
            void assign();
          }}
          leading={<Icon name="palette" size={22} />}
          title={t('icons.byAddress')}
          disabled={!session.editable || busy !== null}
        />
        <Row
          onClick={() => {
            void exportCopy();
          }}
          leading={<Icon name="download" size={22} />}
          title={t('save.copy')}
        />
      </Card>

      <Card>
        <Row
          onClick={() => {
            setConfirmForget(true);
          }}
          leading={<Icon name="trash" size={22} />}
          title={t('hub.forget')}
        />
      </Card>

      {confirmIcons ? (
        <Confirm
          title={t('icons.confirmTitle')}
          message={t('icons.confirmMessage')}
          confirm={t('icons.confirm')}
          onCancel={() => {
            setConfirmIcons(false);
          }}
          onConfirm={() => {
            void getIcons();
          }}
        />
      ) : null}
      {confirmForget ? (
        <Confirm
          title={t('hub.forgetTitle', { name: session.name })}
          message={t('hub.forgetMessage')}
          confirm={t('hub.forget')}
          tone="caution"
          onCancel={() => {
            setConfirmForget(false);
          }}
          onConfirm={() => {
            setConfirmForget(false);
            void host.forgetVault(vault.id).then(() => {
              reset({ name: 'hub' });
            });
          }}
        />
      ) : null}
    </SettingsFrame>
  );
}
