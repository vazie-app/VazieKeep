import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { plain, type EntryDetail, type Text, type TotpCode } from '../../core/types';
import { formatDateTime } from '../../shared/i18n';
import { Confirm, Dialog, showToast } from '../components/dialogs';
import { EntryIcon, SecretText } from '../components/entryParts';
import {
  Badge,
  Banner,
  Button,
  Card,
  Chip,
  IconButton,
  Loading,
  Row,
  SectionLabel,
  TopBar,
} from '../components/ui';
import { isPopup, openInTab, pickAnyFile } from '../files';
import { Icon } from '../icons';
import { host } from '../state/client';
import { useApp, useSnapshot, useT } from '../state/hooks';
import { useLanguage } from '../state/language';
import { back, browse, go, reset } from '../state/route';
import {
  codeOf,
  copyPlain,
  copySecret,
  descendantIds,
  entryTitle,
  errorText,
  flattenGroups,
  hostOf,
  isExpired,
  openUrl,
  without,
} from '../util';
import { SaveProblemBanner } from './vault';

const AUTO_HIDE_MS = 30_000;
const MAX_ATTACHMENT = 64 * 1024 * 1024;

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function EntryScreen({ id }: { id: string }) {
  const t = useT();
  const app = useApp();
  const data = useSnapshot();
  const language = useLanguage();
  const [detail, setDetail] = useState<EntryDetail | null>(null);
  const [missing, setMissing] = useState(false);
  const [shown, setShown] = useState<Record<string, string>>({});
  const [moving, setMoving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [removePasskey, setRemovePasskey] = useState(false);
  const timers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});
  const session = app?.session;
  const version = session?.dataVersion ?? 0;
  const clear = app?.settings.clipboardClear ?? 30;
  const plus = app?.account.access.plusFeatures === true;

  useEffect(() => {
    const life = new AbortController();
    setMissing(false);
    void host
      .entry(id)
      .then((found) => {
        if (!life.signal.aborted) setDetail(found);
      })
      .catch(() => {
        if (!life.signal.aborted) setMissing(true);
      });
    return () => {
      life.abort();
    };
  }, [id, version]);

  useEffect(() => {
    setShown({});
    return () => {
      for (const timer of Object.values(timers.current)) clearTimeout(timer);
    };
  }, [id]);

  const inBin = useMemo(() => {
    if (!data || !detail) return false;
    const binId = data.info.recycleBinId;
    return binId !== null && descendantIds(data.groups, binId).has(detail.summary.groupId);
  }, [data, detail]);

  if (!session || !data) return null;
  if (missing) {
    return (
      <div class="app">
        <TopBar title={t('entry.missingTitle')} onBack={back} />
        <div class="scroll">
          <div class="page">
            <Banner tone="caution">{t('entry.missingMessage')}</Banner>
          </div>
        </div>
      </div>
    );
  }
  if (!detail) {
    return (
      <div class="app">
        <TopBar title="" onBack={back} />
        <Loading />
      </div>
    );
  }

  const entry = detail.summary;
  const editable = session.editable;

  async function reveal(key: string) {
    try {
      const value = await host.reveal(id, key);
      setShown((current) => ({ ...current, [key]: value }));
      clearTimeout(timers.current[key]);
      timers.current[key] = setTimeout(() => {
        setShown((current) => without(current, key));
      }, AUTO_HIDE_MS);
    } catch (error) {
      showToast(errorText(codeOf(error)), 'danger');
    }
  }

  function hide(key: string) {
    clearTimeout(timers.current[key]);
    setShown((current) => without(current, key));
  }

  async function copyField(key: string) {
    try {
      await copySecret(await host.reveal(id, key), clear);
    } catch (error) {
      showToast(errorText(codeOf(error)), 'danger');
    }
  }

  async function toggleFavorite() {
    try {
      await host.setFavorite(id, !entry.favorite);
    } catch (error) {
      showToast(errorText(codeOf(error)), 'danger');
    }
  }

  async function addAttachment() {
    if (isPopup()) {
      openInTab(`#entry=${id}`);
      return;
    }
    const file = await pickAnyFile();
    if (!file) return;
    if (file.size > MAX_ATTACHMENT) {
      showToast(t('attachment.tooLarge'), 'danger');
      return;
    }
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      await host.addAttachment(id, file.name, bytes);
    } catch (error) {
      const code = codeOf(error);
      showToast(
        code === 'not_entitled'
          ? t('editor.errorAttachmentPlus')
          : code === 'attachment_too_large'
            ? t('attachment.tooLarge')
            : t('attachment.unreadable'),
        'danger',
      );
    }
  }

  async function saveAttachment(name: string) {
    try {
      const bytes = await host.attachment(id, name);
      const url = URL.createObjectURL(new Blob([bytes as BlobPart]));
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => {
        URL.revokeObjectURL(url);
      }, 10_000);
      showToast(t('attachment.saved'));
    } catch {
      showToast(t('attachment.saveFailed'), 'danger');
    }
  }

  const username = plain(entry.username);
  const url = plain(entry.url);
  const title = entryTitle(entry);

  return (
    <div class="app">
      <TopBar
        title={title}
        subtitle={hostOf(url) || undefined}
        onBack={back}
        actions={
          <>
            <IconButton
              icon="star"
              filled={entry.favorite}
              pressed={entry.favorite}
              tone="accent"
              label={t(entry.favorite ? 'entry.unfavorite' : 'entry.favorite')}
              onClick={() => {
                void toggleFavorite();
              }}
              disabled={!editable}
            />
            {editable ? (
              <IconButton
                icon="edit"
                label={t('action.edit')}
                onClick={() => {
                  go({ name: 'edit', id, groupId: null });
                }}
              />
            ) : null}
            {editable ? (
              <IconButton
                icon="swap"
                label={t('action.move')}
                onClick={() => {
                  setMoving(true);
                }}
              />
            ) : null}
            {editable ? (
              <IconButton
                icon="trash"
                tone="caution"
                label={t('action.delete')}
                onClick={() => {
                  setDeleting(true);
                }}
              />
            ) : null}
          </>
        }
      />
      <div class="scroll">
        <div class="page">
          <SaveProblemBanner />
          <div class="entry-head">
            <EntryIcon entry={entry} vaultId={session.vaultId} size={56} />
            <div class="entry-head-text">
              <h2>{title}</h2>
              <div class="cluster">
                {entry.favorite ? <Badge tone="accent">{t('entry.favoriteState')}</Badge> : null}
                {isExpired(entry) ? <Badge tone="caution">{t('entry.expiredBadge')}</Badge> : null}
                {entry.tags.map((tag) => (
                  <Chip
                    key={tag}
                    icon="tag"
                    onClick={() => {
                      browse.set({ groupId: null, query: `tag:${tag}`, filter: 'all' });
                      reset({ name: 'vault' });
                    }}
                  >
                    {tag}
                  </Chip>
                ))}
              </div>
            </div>
          </div>
          {isExpired(entry) && entry.expiresAt !== null ? (
            <Banner tone="caution" icon="clock">
              {t('entry.expiredMessage', { date: formatDateTime(language, entry.expiresAt) })}
            </Banner>
          ) : null}

          <Card>
            <FieldRow
              label={t('field.username')}
              value={username}
              empty={username === '' && entry.username.kind === 'plain'}
              onCopy={() => {
                void copyPlain(username);
              }}
            />
            {entry.hasPassword ? (
              <SecretRow
                label={t('field.password')}
                value={shown.Password}
                onShow={() => {
                  void reveal('Password');
                }}
                onHide={() => {
                  hide('Password');
                }}
                onCopy={() => {
                  void copyField('Password');
                }}
              />
            ) : null}
            {url !== '' ? (
              <FieldRow
                label={t('field.url')}
                value={url}
                onCopy={() => {
                  void copyPlain(url);
                }}
                extra={
                  <IconButton
                    icon="external"
                    label={t('action.open')}
                    onClick={() => {
                      openUrl(url);
                    }}
                  />
                }
              />
            ) : null}
          </Card>

          {entry.hasTotp ? <TotpCard id={id} plus={plus} version={version} /> : null}

          {entry.hasNotes || detail.notes.kind === 'protected' ? (
            <>
              <SectionLabel>{t('field.notes')}</SectionLabel>
              <Card class="card-pad">
                <NotesBlock
                  id={id}
                  notes={detail.notes}
                  shown={shown.Notes}
                  onShow={() => {
                    void reveal('Notes');
                  }}
                  onHide={() => {
                    hide('Notes');
                  }}
                />
              </Card>
            </>
          ) : null}

          {detail.customFields.filter((f) => f.name !== 'otp').length > 0 ? (
            <>
              <SectionLabel>{t('entry.sectionMore')}</SectionLabel>
              <Card>
                {detail.customFields
                  .filter((field) => field.name !== 'otp')
                  .map((field) =>
                    field.value.kind === 'plain' ? (
                      <FieldRow
                        key={field.name}
                        label={field.name}
                        value={field.value.value}
                        onCopy={() => {
                          void copyPlain((field.value as { value: string }).value);
                        }}
                      />
                    ) : (
                      <SecretRow
                        key={field.name}
                        label={field.name}
                        value={shown[field.name]}
                        onShow={() => {
                          void reveal(field.name);
                        }}
                        onHide={() => {
                          hide(field.name);
                        }}
                        onCopy={() => {
                          void copyField(field.name);
                        }}
                      />
                    ),
                  )}
              </Card>
            </>
          ) : null}

          {entry.passkey ? (
            <>
              <SectionLabel>{t('passkey.title')}</SectionLabel>
              <Card>
                <Row
                  leading={
                    <span class="tile tile-accent" style={{ width: '40px', height: '40px' }}>
                      <Icon name="passkey" size={20} />
                    </span>
                  }
                  title={entry.passkey.rpId}
                  subtitle={entry.passkey.userName}
                  trailing={
                    editable ? (
                      <IconButton
                        icon="trash"
                        tone="caution"
                        label={t('passkey.remove')}
                        onClick={() => {
                          setRemovePasskey(true);
                        }}
                      />
                    ) : undefined
                  }
                />
              </Card>
            </>
          ) : null}

          <SectionLabel>{t('entry.sectionAttachments')}</SectionLabel>
          <Card>
            {detail.attachments.map((attachment) => (
              <Row
                key={attachment.name}
                leading={
                  <span class="tile" style={{ width: '40px', height: '40px' }}>
                    <Icon name="paperclip" size={20} />
                  </span>
                }
                title={attachment.name}
                subtitle={`${formatSize(attachment.size)} · ${t('attachment.stored')}`}
                trailing={
                  <>
                    <IconButton
                      icon="download"
                      label={t('attachment.save', { name: attachment.name })}
                      onClick={() => {
                        void saveAttachment(attachment.name);
                      }}
                    />
                    {editable ? (
                      <IconButton
                        icon="trash"
                        tone="caution"
                        label={t('attachment.remove', { name: attachment.name })}
                        onClick={() => {
                          setRemoving(attachment.name);
                        }}
                      />
                    ) : null}
                  </>
                }
              />
            ))}
            {editable ? (
              <Row
                onClick={() => {
                  if (plus) void addAttachment();
                  else go({ name: 'settings', page: 'plus' });
                }}
                leading={
                  <span class="tile tile-accent" style={{ width: '40px', height: '40px' }}>
                    <Icon name="add" size={20} />
                  </span>
                }
                title={t('attachment.add')}
                trailing={!plus ? <Badge tone="accent">Keep+</Badge> : undefined}
              />
            ) : null}
          </Card>
          {!plus && editable ? <p class="caption">{t('editor.errorAttachmentPlus')}</p> : null}

          <Card>
            <Row
              onClick={() => {
                if (plus) go({ name: 'history', id });
                else go({ name: 'settings', page: 'plus' });
              }}
              leading={
                <span class="tile" style={{ width: '40px', height: '40px' }}>
                  <Icon name="history" size={20} />
                </span>
              }
              title={t('history.title')}
              subtitle={
                detail.historyCount === 0
                  ? t('history.emptyTitle')
                  : t('history.earlier', { count: detail.historyCount })
              }
              trailing={!plus ? <Badge tone="accent">Keep+</Badge> : undefined}
              chevron
            />
          </Card>
          {detail.modifiedAt !== null ? (
            <p class="caption center">{formatDateTime(language, detail.modifiedAt)}</p>
          ) : null}
        </div>
      </div>

      {deleting ? (
        <DeleteEntry
          title={title}
          inBin={inBin || data.info.recycleBinId === null}
          onClose={() => {
            setDeleting(false);
          }}
          onDeleted={() => {
            setDeleting(false);
            back();
          }}
          id={id}
        />
      ) : null}
      {moving ? (
        <MoveEntry
          id={id}
          current={entry.groupId}
          onClose={() => {
            setMoving(false);
          }}
        />
      ) : null}
      {removing ? (
        <Confirm
          title={t('attachment.remove', { name: removing })}
          confirm={t('action.delete')}
          tone="caution"
          onCancel={() => {
            setRemoving(null);
          }}
          onConfirm={() => {
            const name = removing;
            setRemoving(null);
            void host.removeAttachment(id, name);
          }}
        />
      ) : null}
      {removePasskey ? (
        <Confirm
          title={t('passkey.removeTitle')}
          message={t('passkey.removeMessage')}
          confirm={t('passkey.remove')}
          tone="caution"
          onCancel={() => {
            setRemovePasskey(false);
          }}
          onConfirm={() => {
            setRemovePasskey(false);
            void host.removePasskey(id);
          }}
        />
      ) : null}
    </div>
  );
}

function FieldRow({
  label,
  value,
  onCopy,
  extra,
  empty,
}: {
  label: string;
  value: string;
  onCopy: () => void;
  extra?: ComponentChildren;
  empty?: boolean;
}) {
  const t = useT();
  if (empty) return null;
  return (
    <div class="row field-row">
      <div class="row-text">
        <div class="field-row-label">{label}</div>
        <div class="field-row-value">{value}</div>
      </div>
      <div class="row-trailing">
        {extra}
        <IconButton icon="copy" label={t('action.copySecret', { name: label })} onClick={onCopy} />
      </div>
    </div>
  );
}

function SecretRow({
  label,
  value,
  onShow,
  onHide,
  onCopy,
}: {
  label: string;
  value: string | undefined;
  onShow: () => void;
  onHide: () => void;
  onCopy: () => void;
}) {
  const t = useT();
  return (
    <div class="row field-row">
      <div class="row-text">
        <div class="field-row-label">{label}</div>
        <div class="field-row-value">
          {value === undefined ? (
            <span class="dots" aria-label={t('secret.hidden', { name: label })}>
              ••••••••••
            </span>
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
          onClick={value === undefined ? onShow : onHide}
        />
        <IconButton icon="copy" label={t('action.copySecret', { name: label })} onClick={onCopy} />
      </div>
    </div>
  );
}

function NotesBlock({
  notes,
  shown,
  onShow,
  onHide,
}: {
  id: string;
  notes: Text;
  shown: string | undefined;
  onShow: () => void;
  onHide: () => void;
}) {
  const t = useT();
  if (notes.kind === 'plain') return <p class="notes">{notes.value}</p>;
  return (
    <div class="stack-tight">
      {shown === undefined ? (
        <span class="dots">{t('value.protected')}</span>
      ) : (
        <p class="notes">{shown}</p>
      )}
      <div>
        <Button
          variant="text"
          size="pill"
          icon={shown === undefined ? 'eye' : 'eyeOff'}
          onClick={shown === undefined ? onShow : onHide}
        >
          {shown === undefined ? t('state.hidden') : t('state.shown')}
        </Button>
      </div>
    </div>
  );
}

function TotpCard({ id, plus, version }: { id: string; plus: boolean; version: number }) {
  const t = useT();
  const app = useApp();
  const [code, setCode] = useState<TotpCode | null>(null);
  const [left, setLeft] = useState(0);
  const [problem, setProblem] = useState<string | null>(null);
  const clear = app?.settings.clipboardClear ?? 30;

  useEffect(() => {
    if (!plus) return;
    const life = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const next = await host.totp(id);
        if (life.signal.aborted) return;
        setCode(next);
        setLeft(next.secondsRemaining);
        setProblem(null);
        timer = setTimeout(
          () => {
            void tick();
          },
          Math.max(next.secondsRemaining, 1) * 1000,
        );
      } catch (error) {
        if (!life.signal.aborted) setProblem(codeOf(error));
      }
    };
    void tick();
    const counter = setInterval(() => {
      setLeft((value) => Math.max(0, value - 1));
    }, 1000);
    return () => {
      life.abort();
      clearTimeout(timer);
      clearInterval(counter);
    };
  }, [id, plus, version]);

  if (!plus) {
    return (
      <Card class="card-pad">
        <div class="stack-tight">
          <div class="cluster">
            <Badge tone="accent">Keep+</Badge>
            <strong>{t('totp.lockedTitle')}</strong>
          </div>
          <p class="caption">{t('totp.lockedMessage')}</p>
          <div>
            <Button
              variant="tonal"
              size="pill"
              onClick={() => {
                go({ name: 'settings', page: 'plus' });
              }}
            >
              {t('totp.lockedAction')}
            </Button>
          </div>
        </div>
      </Card>
    );
  }
  if (problem) return <Banner tone="danger">{t('totp.errorGeneric')}</Banner>;
  if (!code) return null;
  const digits =
    code.code.length === 6 ? `${code.code.slice(0, 3)} ${code.code.slice(3)}` : code.code;
  return (
    <Card class="card-pad totp">
      <div class="totp-row">
        <div>
          <div class="field-row-label">{t('totp.label')}</div>
          <div class="totp-code mono" aria-label={t('totp.description', { seconds: left })}>
            {digits}
          </div>
        </div>
        <div class="totp-side">
          <span class="totp-ring" style={{ '--p': `${(left / Math.max(code.period, 1)) * 100}%` }}>
            <span>{left}</span>
          </span>
          <IconButton
            icon="copy"
            label={t('action.copySecret', { name: t('totp.label') })}
            onClick={() => {
              void copySecret(code.code, clear);
            }}
          />
        </div>
      </div>
    </Card>
  );
}

function DeleteEntry({
  id,
  title,
  inBin,
  onClose,
  onDeleted,
}: {
  id: string;
  title: string;
  inBin: boolean;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  return (
    <Confirm
      title={t('delete.entryTitle', { title })}
      message={inBin ? t('delete.entryPermanent') : t('delete.entryBin')}
      confirm={inBin ? t('delete.permanently') : t('delete.toBin')}
      tone="caution"
      busy={busy}
      onCancel={onClose}
      onConfirm={() => {
        setBusy(true);
        void host
          .deleteEntry(id, inBin)
          .then(onDeleted)
          .catch((error: unknown) => {
            showToast(errorText(codeOf(error)), 'danger');
            onClose();
          });
      }}
    />
  );
}

function MoveEntry({ id, current, onClose }: { id: string; current: string; onClose: () => void }) {
  const t = useT();
  const data = useSnapshot();
  if (!data) return null;
  const options = flattenGroups(data.groups);
  return (
    <Dialog title={t('move.title')} onClose={onClose}>
      <div class="card">
        {options.map(({ group, depth }) => (
          <Row
            key={group.id}
            title={group.parentId === null ? t('vault.filterAll') : group.name}
            selected={group.id === current}
            leading={
              <span style={{ paddingLeft: `${depth * 12}px` }}>
                <Icon name="folder" size={18} />
              </span>
            }
            onClick={() => {
              void host
                .moveEntry(id, group.id)
                .then(() => {
                  showToast(t('entry.moved'));
                  onClose();
                })
                .catch((error: unknown) => {
                  showToast(errorText(codeOf(error)), 'danger');
                });
            }}
          />
        ))}
      </div>
    </Dialog>
  );
}
