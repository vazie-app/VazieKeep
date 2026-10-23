import { useState } from 'preact/hooks';
import { BACKGROUND_CHANNEL } from '../shared/content';
import { Confirm, Dialog } from './components/dialogs';
import { Banner, Button, Empty, Loading, Row } from './components/ui';
import { Icon } from './icons';
import { CreateVault } from './screens/create';
import { GeneratorScreen } from './screens/generator';
import { ImportScreen } from './screens/import';
import { SettingsScreen } from './screens/settings';
import { Hub, Unlock, Welcome } from './screens/start';
import { VaultScreen } from './screens/vault';
import { useAtom } from './state/atom';
import { connection, host } from './state/client';
import { useApp, useT } from './state/hooks';
import { useRoute } from './state/route';

function HostDown() {
  const t = useT();
  return (
    <div class="app">
      <Empty
        icon="warning"
        title={t('host.downTitle')}
        message={t('host.downMessage')}
        action={
          <Button
            variant="action"
            onClick={() => {
              void chrome.runtime
                .sendMessage({ channel: BACKGROUND_CHANNEL, cmd: 'ensureHost' })
                .finally(() => {
                  location.reload();
                });
            }}
          >
            {t('host.retry')}
          </Button>
        }
      />
    </div>
  );
}

function MainVaultDialog() {
  const t = useT();
  const app = useApp();
  const [picked, setPicked] = useState<string | null>(null);
  if (!app?.rules.mustChooseMain) return null;
  if (picked) {
    const vault = app.vaults.find((v) => v.id === picked);
    return (
      <Confirm
        title={t('main.title')}
        message={t('main.message')}
        confirm={t('main.confirm')}
        onCancel={() => {
          setPicked(null);
        }}
        onConfirm={() => {
          void host.chooseMainVault(picked);
          setPicked(null);
        }}
      >
        <Banner tone="info" icon="lock">
          {vault?.name}
        </Banner>
      </Confirm>
    );
  }
  return (
    <Dialog title={t('main.title')}>
      <p class="dialog-message">{t('main.message')}</p>
      <div class="card">
        {app.vaults.map((vault) => (
          <Row
            key={vault.id}
            title={vault.name}
            subtitle={vault.fileName}
            leading={<Icon name="lock" size={20} />}
            onClick={() => {
              setPicked(vault.id);
            }}
          />
        ))}
      </div>
    </Dialog>
  );
}

export function App() {
  const t = useT();
  const status = useAtom(connection);
  const app = useApp();
  const route = useRoute();

  if (status === 'down') return <HostDown />;
  if (status === 'connecting' || !app) {
    return (
      <div class="app">
        <Loading label={t('host.starting')} />
      </div>
    );
  }

  const overlay = <MainVaultDialog />;
  let body;
  if (route.name === 'settings') {
    body = <SettingsScreen />;
  } else if (route.name === 'create') {
    body = <CreateVault />;
  } else if (route.name === 'hub') {
    body = <Hub />;
  } else if (!app.session) {
    const selected = app.vaults.find((v) => v.active) ?? app.vaults[0];
    if (!selected) body = app.settings.welcomeSeen ? <Hub /> : <Welcome />;
    else body = <Unlock vault={selected} />;
  } else if (route.name === 'generator') {
    body = <GeneratorScreen />;
  } else if (route.name === 'import') {
    body = <ImportScreen />;
  } else {
    body = <VaultScreen />;
  }
  return (
    <>
      {body}
      {overlay}
    </>
  );
}
