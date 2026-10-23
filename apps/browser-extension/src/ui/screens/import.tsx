import { useEffect, useRef, useState } from 'preact/hooks';
import type { ImportSourceKind, ImportSummary } from '../../core/types';
import { Banner, Button, Card, Empty, Loading, Row, TopBar } from '../components/ui';
import { isPopup, openInTab } from '../files';
import { Icon } from '../icons';
import { host } from '../state/client';
import { useApp, useSnapshot, useT } from '../state/hooks';
import { back } from '../state/route';
import { codeOf } from '../util';

type Phase =
  | { name: 'pick' }
  | { name: 'reading' }
  | { name: 'preview'; planId: number; summary: ImportSummary }
  | { name: 'importing' }
  | { name: 'done'; entries: number; groups: number }
  | { name: 'failed'; message: string };

const SOURCES: Record<
  ImportSourceKind,
  'import.sourceCsv' | 'import.source1password' | 'import.sourceBitwarden'
> = {
  csv: 'import.sourceCsv',
  onePasswordCsv: 'import.source1password',
  onePassword1pux: 'import.source1password',
  bitwardenJson: 'import.sourceBitwarden',
  bitwardenCsv: 'import.sourceBitwarden',
};

export function ImportScreen() {
  const t = useT();
  const app = useApp();
  const data = useSnapshot();
  const [phase, setPhase] = useState<Phase>({ name: 'pick' });
  const planRef = useRef<number | null>(null);
  const editable = app?.session?.editable === true;

  useEffect(
    () => () => {
      if (planRef.current !== null) void host.dropImport(planRef.current);
    },
    [],
  );

  async function read(file: File | undefined) {
    if (!file) return;
    setPhase({ name: 'reading' });
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      const parsed = await host.parseImport(bytes);
      planRef.current = parsed.planId;
      setPhase({ name: 'preview', planId: parsed.planId, summary: parsed.summary });
    } catch (error) {
      const code = codeOf(error);
      const messages: Record<string, Parameters<typeof t>[0]> = {
        empty: 'import.problemEmpty',
        too_large: 'import.problemTooLarge',
        unrecognized: 'import.problemUnrecognized',
        encrypted: 'import.problemEncrypted',
        malformed: 'import.problemMalformed',
      };
      setPhase({ name: 'failed', message: t(messages[code] ?? 'import.problemUnreadable') });
    }
  }

  async function confirm(planId: number) {
    if (!data) return;
    setPhase({ name: 'importing' });
    try {
      const result = await host.applyImport(planId, data.info.rootGroupId);
      planRef.current = null;
      setPhase({ name: 'done', entries: result.value.entries, groups: result.value.groups });
    } catch {
      setPhase({ name: 'failed', message: t('import.problemFailed') });
    }
  }

  return (
    <div class="app">
      <TopBar title={t('import.action')} onBack={back} />
      <div class="scroll">
        <div class="page">
          {!editable ? <Banner tone="info">{t('vault.readOnlyPlan')}</Banner> : null}
          {phase.name === 'pick' ? (
            <>
              <p class="dialog-message">{t('guide.importBody')}</p>
              {isPopup() ? (
                <Button
                  variant="action"
                  size="large"
                  full
                  icon="expand"
                  onClick={() => {
                    openInTab('#import');
                  }}
                >
                  {t('action.openTab')}
                </Button>
              ) : (
                <label
                  class={`btn btn-action btn-large btn-full file-button${editable ? '' : ' file-disabled'}`}
                >
                  <Icon name="upload" size={18} />
                  <span>{t('import.choose')}</span>
                  <input
                    type="file"
                    class="visually-hidden"
                    disabled={!editable}
                    accept=".csv,.json,.1pux,text/csv,application/json,application/zip"
                    onChange={(event) => {
                      void read(event.currentTarget.files?.[0]);
                    }}
                  />
                </label>
              )}
            </>
          ) : null}
          {phase.name === 'reading' ? <Loading label={t('import.reading')} /> : null}
          {phase.name === 'importing' ? (
            <Loading label={`${t('import.importing')}. ${t('import.wait')}`} />
          ) : null}
          {phase.name === 'preview' ? (
            <>
              <Card>
                <Row
                  title={t('import.from', { source: t(SOURCES[phase.summary.source]) })}
                  subtitle={t('import.entriesCount', { count: phase.summary.entries })}
                  leading={<Icon name="download" size={22} />}
                />
                {phase.summary.folders > 0 ? (
                  <Row
                    title={t('import.foldersCount', { count: phase.summary.folders })}
                    leading={<Icon name="folder" size={22} />}
                  />
                ) : null}
                {phase.summary.withTotp > 0 ? (
                  <Row
                    title={t('import.totpCount', { count: phase.summary.withTotp })}
                    leading={<Icon name="clock" size={22} />}
                  />
                ) : null}
              </Card>
              <p class="caption">{t('import.previewNote')}</p>
              <div class="cluster">
                <Button
                  variant="text"
                  onClick={() => {
                    void host.dropImport(phase.planId);
                    planRef.current = null;
                    setPhase({ name: 'pick' });
                  }}
                >
                  {t('action.cancel')}
                </Button>
                <div class="spacer" />
                <Button
                  variant="action"
                  disabled={!editable}
                  onClick={() => {
                    void confirm(phase.planId);
                  }}
                >
                  {t('import.confirm')}
                </Button>
              </div>
            </>
          ) : null}
          {phase.name === 'done' ? (
            <>
              <Empty
                icon="check"
                title={t('import.doneTitle', { count: phase.entries })}
                message={t('import.doneMessage')}
              />
              <Button variant="action" size="large" full onClick={back}>
                {t('action.done')}
              </Button>
            </>
          ) : null}
          {phase.name === 'failed' ? (
            <>
              <Empty icon="warning" title={t('import.failedTitle')} message={phase.message} />
              <Button
                variant="tonal"
                size="large"
                full
                onClick={() => {
                  setPhase({ name: 'pick' });
                }}
              >
                {t('save.tryAgain')}
              </Button>
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
