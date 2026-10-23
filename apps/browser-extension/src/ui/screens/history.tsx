import { useEffect, useState } from 'preact/hooks';
import { plain, type ChangedField, type EntryDetail, type HistoryVersion } from '../../core/types';
import { formatDateTime } from '../../shared/i18n';
import { Confirm, showToast } from '../components/dialogs';
import { SecretText } from '../components/entryParts';
import {
  Banner,
  Button,
  Card,
  Empty,
  IconButton,
  Loading,
  Row,
  SectionLabel,
  TopBar,
} from '../components/ui';
import { host } from '../state/client';
import { useApp, useT } from '../state/hooks';
import { useLanguage } from '../state/language';
import { back, go, replace } from '../state/route';
import { codeOf, copySecret, errorText, without } from '../util';

const FIELD_KEYS = {
  title: 'history.fieldTitle',
  userName: 'history.fieldUsername',
  password: 'history.fieldPassword',
  url: 'history.fieldUrl',
  notes: 'history.fieldNotes',
  customFields: 'history.fieldCustom',
  tags: 'history.fieldTags',
  favorite: 'history.fieldFavorite',
  attachments: 'history.fieldAttachments',
  expiry: 'history.fieldExpiry',
} as const satisfies Record<ChangedField, string>;

export function HistoryScreen({ id }: { id: string }) {
  const t = useT();
  const app = useApp();
  const language = useLanguage();
  const [versions, setVersions] = useState<HistoryVersion[] | null>(null);
  const plus = app?.account.access.plusFeatures === true;
  const version = app?.session?.dataVersion ?? 0;

  useEffect(() => {
    if (!plus) return;
    const life = new AbortController();
    void host
      .history(id)
      .then((found) => {
        if (!life.signal.aborted) setVersions(found);
      })
      .catch(() => {
        if (!life.signal.aborted) setVersions([]);
      });
    return () => {
      life.abort();
    };
  }, [id, plus, version]);

  return (
    <div class="app">
      <TopBar title={t('history.title')} onBack={back} />
      <div class="scroll">
        <div class="page">
          {!plus ? (
            <Empty
              icon="history"
              title={t('history.lockedTitle')}
              message={t('history.lockedMessage')}
              action={
                <Button
                  variant="tonal"
                  onClick={() => {
                    go({ name: 'settings', page: 'plus' });
                  }}
                >
                  {t('totp.lockedAction')}
                </Button>
              }
            />
          ) : versions === null ? (
            <Loading />
          ) : versions.length === 0 ? (
            <Empty
              icon="history"
              title={t('history.emptyTitle')}
              message={t('history.emptyMessage')}
            />
          ) : (
            <>
              <p class="caption">{t('history.intro')}</p>
              <Card>
                {versions.map((entry) => (
                  <Row
                    key={entry.index}
                    onClick={() => {
                      go({ name: 'version', id, index: entry.index });
                    }}
                    title={
                      entry.modifiedAt === null
                        ? t('history.versionUndated')
                        : t('history.versionOf', {
                            date: formatDateTime(language, entry.modifiedAt),
                          })
                    }
                    subtitle={
                      entry.differs.length === 0
                        ? t('history.same')
                        : t('history.differs', {
                            fields: entry.differs.map((f) => t(FIELD_KEYS[f])).join(', '),
                          })
                    }
                    chevron
                  />
                ))}
              </Card>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export function VersionScreen({ id, index }: { id: string; index: number }) {
  const t = useT();
  const app = useApp();
  const language = useLanguage();
  const [detail, setDetail] = useState<EntryDetail | null>(null);
  const [shown, setShown] = useState<Record<string, string>>({});
  const [restoring, setRestoring] = useState(false);
  const [busy, setBusy] = useState(false);
  const clear = app?.settings.clipboardClear ?? 30;
  const editable = app?.session?.editable === true;

  useEffect(() => {
    const life = new AbortController();
    setShown({});
    void host
      .historyEntry(id, index)
      .then((found) => {
        if (!life.signal.aborted) setDetail(found);
      })
      .catch((error: unknown) => {
        showToast(errorText(codeOf(error)), 'danger');
        back();
      });
    return () => {
      life.abort();
    };
  }, [id, index]);

  if (!detail) {
    return (
      <div class="app">
        <TopBar title={t('history.title')} onBack={back} />
        <Loading />
      </div>
    );
  }
  const entry = detail.summary;

  async function reveal(key: string) {
    try {
      const value = await host.revealHistory(id, index, key);
      setShown((current) => ({ ...current, [key]: value }));
    } catch (error) {
      showToast(errorText(codeOf(error)), 'danger');
    }
  }

  function secretRow(label: string, key: string) {
    const value = shown[key];
    return (
      <div class="row field-row" key={key}>
        <div class="row-text">
          <div class="field-row-label">{label}</div>
          <div class="field-row-value">
            {value === undefined ? (
              <span class="dots">••••••••••</span>
            ) : (
              <SecretText value={value} />
            )}
          </div>
        </div>
        <div class="row-trailing">
          <IconButton
            icon={value === undefined ? 'eye' : 'eyeOff'}
            label={t(value === undefined ? 'action.showSecret' : 'action.hideSecret', {
              name: label,
            })}
            onClick={() => {
              if (value === undefined) void reveal(key);
              else setShown((current) => without(current, key));
            }}
          />
          <IconButton
            icon="copy"
            label={t('action.copySecret', { name: label })}
            onClick={() => {
              void host.revealHistory(id, index, key).then((v) => copySecret(v, clear));
            }}
          />
        </div>
      </div>
    );
  }

  const plainRow = (label: string, value: string) =>
    value === '' ? null : (
      <div class="row field-row" key={label}>
        <div class="row-text">
          <div class="field-row-label">{label}</div>
          <div class="field-row-value">{value}</div>
        </div>
      </div>
    );

  return (
    <div class="app">
      <TopBar
        title={
          detail.modifiedAt === null
            ? t('history.versionUndated')
            : t('history.versionOf', { date: formatDateTime(language, detail.modifiedAt) })
        }
        onBack={back}
      />
      <div class="scroll">
        <div class="page">
          <Card>
            {plainRow(t('field.title'), plain(entry.title))}
            {plainRow(t('field.username'), plain(entry.username))}
            {entry.hasPassword ? secretRow(t('field.password'), 'Password') : null}
            {plainRow(t('field.url'), plain(entry.url))}
          </Card>
          {detail.notes.kind === 'plain' && detail.notes.value !== '' ? (
            <>
              <SectionLabel>{t('field.notes')}</SectionLabel>
              <Card class="card-pad">
                <p class="notes">{detail.notes.value}</p>
              </Card>
            </>
          ) : null}
          {detail.customFields.length > 0 ? (
            <>
              <SectionLabel>{t('entry.sectionMore')}</SectionLabel>
              <Card>
                {detail.customFields.map((field) =>
                  field.value.kind === 'plain'
                    ? plainRow(field.name, field.value.value)
                    : secretRow(field.name, field.name),
                )}
              </Card>
            </>
          ) : null}
          {editable ? (
            <Button
              variant="action"
              size="large"
              full
              icon="history"
              onClick={() => {
                setRestoring(true);
              }}
            >
              {t('history.restore')}
            </Button>
          ) : (
            <Banner tone="info">{t('vault.readOnlyPlan')}</Banner>
          )}
        </div>
      </div>
      {restoring ? (
        <Confirm
          title={t('history.restoreTitle')}
          message={t('history.restoreMessage')}
          confirm={t('history.restoreConfirm')}
          busy={busy}
          onCancel={() => {
            setRestoring(false);
          }}
          onConfirm={() => {
            setBusy(true);
            void host
              .restoreHistory(id, index)
              .then(() => {
                setRestoring(false);
                replace({ name: 'entry', id });
              })
              .catch((error: unknown) => {
                showToast(errorText(codeOf(error)), 'danger');
                setRestoring(false);
              })
              .finally(() => {
                setBusy(false);
              });
          }}
        />
      ) : null}
    </div>
  );
}
