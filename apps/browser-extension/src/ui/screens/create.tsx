import { useEffect, useState } from 'preact/hooks';
import type { SecurityLevel } from '../../shared/api';
import {
  Banner,
  Button,
  Card,
  Row,
  SecretField,
  SectionLabel,
  TextField,
  TopBar,
} from '../components/ui';
import { showToast } from '../components/dialogs';
import { isPopup, openInTab, pickKeyFile, saveKeyFileAs, saveVaultAs, writeHandle } from '../files';
import { Icon } from '../icons';
import { host } from '../state/client';
import { useT } from '../state/hooks';
import { back, replace } from '../state/route';
import { codeOf, errorText } from '../util';

interface KeyFile {
  handle: FileSystemFileHandle;
  name: string;
  bytes: Uint8Array;
}

export function CreateVault() {
  const t = useT();
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [keyFile, setKeyFile] = useState<KeyFile | null>(null);
  const [level, setLevel] = useState<SecurityLevel>('balanced');
  const [timing, setTiming] = useState<Partial<Record<SecurityLevel, number>>>({});
  const [busy, setBusy] = useState(false);
  const [generated, setGenerated] = useState(false);
  const [errors, setErrors] = useState<{
    name?: string;
    password?: string;
    confirm?: string;
    general?: string;
  }>({});
  const [done, setDone] = useState(false);

  useEffect(() => {
    const life = new AbortController();
    void (async () => {
      for (const which of ['balanced', 'stronger'] as const) {
        try {
          const ms = await host.benchmark(which);
          if (!life.signal.aborted) setTiming((current) => ({ ...current, [which]: ms }));
        } catch {
          /* the estimate is a courtesy */
        }
      }
    })();
    return () => {
      life.abort();
    };
  }, []);

  async function generatePassword() {
    const made = await host.generatePassword({
      length: 24,
      lowercase: true,
      uppercase: true,
      digits: true,
      symbols: true,
      excludeAmbiguous: false,
    });
    setPassword(made);
    setConfirm(made);
    setGenerated(true);
  }

  async function chooseKeyFile() {
    if (isPopup()) {
      openInTab('#create');
      return;
    }
    const handle = await pickKeyFile();
    if (!handle) return;
    try {
      const bytes = new Uint8Array(await (await handle.getFile()).arrayBuffer());
      setKeyFile({ handle, name: handle.name, bytes });
    } catch {
      showToast(t('create.keyFileUnreadable'), 'danger');
    }
  }

  async function makeKeyFile() {
    if (isPopup()) {
      openInTab('#create');
      return;
    }
    const handle = await saveKeyFileAs(`${name.trim() || 'vault'}.keyx`);
    if (!handle) return;
    try {
      const bytes = await host.generateKeyFile();
      await writeHandle(handle, bytes);
      setKeyFile({ handle, name: handle.name, bytes });
    } catch {
      showToast(t('create.keyFileNotSaved'), 'danger');
    }
  }

  async function create() {
    const next: typeof errors = {};
    if (name.trim() === '') next.name = t('create.nameMissing');
    if (password === '' && !keyFile) next.password = t('create.passwordMissing');
    if (password !== confirm) next.confirm = t('create.passwordMismatch');
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    if (isPopup()) {
      openInTab('#create');
      return;
    }
    setBusy(true);
    try {
      const handle = await saveVaultAs(`${name.trim()}.kdbx`);
      if (!handle) return;
      await host.createVault({
        handle,
        name: name.trim(),
        level,
        password: new TextEncoder().encode(password),
        ...(keyFile ? { keyFile: keyFile.bytes.slice(), keyFileHandle: keyFile.handle } : {}),
      });
      setPassword('');
      setConfirm('');
      setDone(true);
    } catch (error) {
      const code = codeOf(error);
      setErrors({
        general:
          code === 'write_failed'
            ? t('create.errorWrite')
            : code === 'create_unlock_failed'
              ? t('create.unlockFailed')
              : code === 'not_entitled'
                ? t('hub.moreVaultsOne')
                : code === 'invalid_key_file'
                  ? t('error.keyFileInvalid')
                  : errorText(code),
      });
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div class="app">
        <TopBar title={t('create.doneTitle')} />
        <div class="scroll">
          <div class="page">
            <div class="empty">
              <div class="empty-icon">
                <Icon name="check" size={28} />
              </div>
              <h2>{t('create.doneTitle')}</h2>
              <p>{t('create.doneBody')}</p>
            </div>
            <Button
              variant="action"
              size="large"
              full
              onClick={() => {
                replace({ name: 'vault' });
              }}
            >
              {t('create.open')}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  const seconds = (which: SecurityLevel) => {
    const ms = timing[which];
    return ms === undefined
      ? t('create.presetMeasuring')
      : t('create.presetTime', { seconds: (ms / 1000).toFixed(1) });
  };

  return (
    <div class="app">
      <TopBar title={t('create.title')} onBack={back} />
      <div class="scroll">
        <form
          class="page"
          onSubmit={(event) => {
            event.preventDefault();
            void create();
          }}
        >
          <TextField
            label={t('create.nameLabel')}
            value={name}
            onInput={setName}
            autofocus
            error={errors.name ?? null}
            hint={t('create.nameSupporting')}
          />
          <SecretField
            label={t('create.passwordLabel')}
            value={password}
            onInput={(value) => {
              setPassword(value);
              setGenerated(false);
            }}
            autocomplete="new-password"
            error={errors.password ?? null}
          />
          <SecretField
            label={t('create.confirmLabel')}
            value={confirm}
            onInput={setConfirm}
            autocomplete="new-password"
            error={errors.confirm ?? null}
          />
          <div>
            <Button
              variant="tonal"
              size="pill"
              icon="dice"
              onClick={() => {
                void generatePassword();
              }}
            >
              {t('create.generate')}
            </Button>
          </div>
          {generated ? <Banner tone="caution">{t('create.generatedWarning')}</Banner> : null}
          <Banner tone="info" icon="shield">
            {t('create.noRecovery')}
          </Banner>

          <SectionLabel>{t('create.keyFileTitle')}</SectionLabel>
          <Card class="card-pad">
            <div class="stack">
              <p class="caption">{t('create.keyFileBody')}</p>
              {keyFile ? (
                <div class="cluster">
                  <span class="badge badge-accent">
                    <Icon name="key" size={14} />{' '}
                    {t('create.keyFileSelected', { name: keyFile.name })}
                  </span>
                  <Button
                    variant="text"
                    size="pill"
                    onClick={() => {
                      setKeyFile(null);
                    }}
                  >
                    {t('create.keyFileRemove')}
                  </Button>
                </div>
              ) : (
                <div class="cluster">
                  <Button
                    variant="raised"
                    size="pill"
                    icon="folder"
                    onClick={() => {
                      void chooseKeyFile();
                    }}
                  >
                    {t('create.keyFileChoose')}
                  </Button>
                  <Button
                    variant="raised"
                    size="pill"
                    icon="add"
                    onClick={() => {
                      void makeKeyFile();
                    }}
                  >
                    {t('create.keyFileGenerate')}
                  </Button>
                </div>
              )}
              {keyFile ? <Banner tone="caution">{t('create.keyFileWarning')}</Banner> : null}
            </div>
          </Card>

          <SectionLabel>{t('create.securityTitle')}</SectionLabel>
          <Card>
            <Row
              onClick={() => {
                setLevel('balanced');
              }}
              selected={level === 'balanced'}
              title={t('create.presetBalanced')}
              subtitle={`${t('create.presetBalancedBody')} · ${seconds('balanced')}`}
              leading={<Icon name={level === 'balanced' ? 'check' : 'shield'} size={20} />}
              wrap
            />
            <Row
              onClick={() => {
                setLevel('stronger');
              }}
              selected={level === 'stronger'}
              title={t('create.presetStronger')}
              subtitle={`${t('create.presetStrongerBody')} · ${seconds('stronger')}`}
              leading={<Icon name={level === 'stronger' ? 'check' : 'shield'} size={20} />}
              wrap
            />
          </Card>

          {errors.general ? <Banner tone="danger">{errors.general}</Banner> : null}
          {busy ? <p class="caption">{t('create.creating')}</p> : null}
          <Button variant="action" size="large" full type="submit" busy={busy}>
            {t('create.action')}
          </Button>
          {isPopup() ? <p class="caption">{t('hub.tabHint')}</p> : null}
        </form>
      </div>
    </div>
  );
}
