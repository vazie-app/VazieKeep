import { useEffect, useRef, useState } from 'preact/hooks';
import type { PasswordOptions } from '../../core/types';
import { SecretText, StrengthMeter } from '../components/entryParts';
import { Button, Card, IconButton, Switch, TopBar } from '../components/ui';
import { DEFAULT_SETTINGS } from '../../shared/model';
import { host } from '../state/client';
import { useApp, useT } from '../state/hooks';
import { back } from '../state/route';
import { copySecret } from '../util';

export function GeneratorPanel({ onUse }: { onUse?: (password: string) => void }) {
  const t = useT();
  const app = useApp();
  const [options, setOptions] = useState<PasswordOptions>(
    () => app?.settings.generator ?? DEFAULT_SETTINGS.generator,
  );
  const [password, setPassword] = useState('');
  const [shown, setShown] = useState(true);
  const serial = useRef(0);
  const clear = app?.settings.clipboardClear ?? 30;

  async function generate(next: PasswordOptions) {
    const ticket = ++serial.current;
    try {
      const made = await host.generatePassword(next);
      if (ticket === serial.current) setPassword(made);
    } catch {
      if (ticket === serial.current) setPassword('');
    }
  }

  useEffect(() => {
    void generate(options);
    return () => {
      serial.current += 1;
    };
  }, []);

  function change(patch: Partial<PasswordOptions>) {
    const next = { ...options, ...patch };
    const sets = [next.lowercase, next.uppercase, next.digits, next.symbols].filter(Boolean).length;
    if (sets === 0) return;
    next.length = Math.max(next.length, Math.max(4, sets));
    setOptions(next);
    void host.updateSettings({ generator: next });
    void generate(next);
  }

  return (
    <div class="stack">
      <Card class="card-pad generator-output">
        <div class="generator-text" aria-live="polite">
          {password === '' ? null : shown ? (
            <SecretText value={password} />
          ) : (
            <span class="dots">{'•'.repeat(Math.min(password.length, 32))}</span>
          )}
        </div>
        <StrengthMeter password={password} />
        <div class="cluster">
          <IconButton
            icon="refresh"
            label={t('generator.regenerate')}
            onClick={() => {
              void generate(options);
            }}
          />
          <IconButton
            icon={shown ? 'eyeOff' : 'eye'}
            label={t(shown ? 'generator.hide' : 'generator.show')}
            onClick={() => {
              setShown(!shown);
            }}
          />
          <div class="spacer" />
          <Button
            variant="tonal"
            size="pill"
            icon="copy"
            onClick={() => {
              void copySecret(password, clear);
            }}
          >
            {t('generator.copy')}
          </Button>
        </div>
      </Card>
      <Card class="card-pad">
        <div class="stack">
          <div class="length-row">
            <label for="gen-length" class="field-label">
              {t('generator.lengthLabel')}: <strong>{options.length}</strong>{' '}
              {t('generator.characters')}
            </label>
            <div class="cluster nowrap">
              <IconButton
                icon="minus"
                label={t('generator.decrease')}
                onClick={() => {
                  change({ length: Math.max(4, options.length - 1) });
                }}
              />
              <input
                id="gen-length"
                class="range"
                type="range"
                min={4}
                max={128}
                value={options.length}
                onInput={(event) => {
                  change({ length: Number(event.currentTarget.value) });
                }}
              />
              <IconButton
                icon="add"
                label={t('generator.increase')}
                onClick={() => {
                  change({ length: Math.min(128, options.length + 1) });
                }}
              />
            </div>
          </div>
        </div>
      </Card>
      <Card>
        <Switch
          label={t('generator.lowercase')}
          checked={options.lowercase}
          onChange={(checked) => {
            change({ lowercase: checked });
          }}
        />
        <Switch
          label={t('generator.uppercase')}
          checked={options.uppercase}
          onChange={(checked) => {
            change({ uppercase: checked });
          }}
        />
        <Switch
          label={t('generator.digits')}
          checked={options.digits}
          onChange={(checked) => {
            change({ digits: checked });
          }}
        />
        <Switch
          label={t('generator.symbols')}
          checked={options.symbols}
          onChange={(checked) => {
            change({ symbols: checked });
          }}
        />
        <Switch
          label={t('generator.ambiguous')}
          hint={t('generator.ambiguousHint')}
          checked={options.excludeAmbiguous}
          onChange={(checked) => {
            change({ excludeAmbiguous: checked });
          }}
        />
      </Card>
      {onUse ? (
        <Button
          variant="action"
          size="large"
          full
          onClick={() => {
            onUse(password);
          }}
          disabled={password === ''}
        >
          {t('generator.use')}
        </Button>
      ) : null}
    </div>
  );
}

export function GeneratorScreen() {
  const t = useT();
  return (
    <div class="app">
      <TopBar title={t('generator.screenTitle')} onBack={back} />
      <div class="scroll">
        <div class="page">
          <GeneratorPanel />
        </div>
      </div>
    </div>
  );
}
