import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { plain, type CustomFieldDraft, type EntryDraft } from '../../core/types';
import { formatDate } from '../../shared/i18n';
import { Confirm, Dialog, showToast } from '../components/dialogs';
import { Monogram, StrengthMeter } from '../components/entryParts';
import { IconPicker } from '../components/iconPicker';
import {
  Banner,
  Button,
  Card,
  Chip,
  IconButton,
  Loading,
  SecretField,
  SectionLabel,
  Switch,
  TextField,
  TopBar,
} from '../components/ui';
import { Icon, DEFAULT_ENTRY_ICON, itemIconOf } from '../icons';
import { host, surface } from '../state/client';
import { useApp, useSnapshot, useT } from '../state/hooks';
import { useLanguage } from '../state/language';
import { back, replace } from '../state/route';
import { codeOf, errorText, flattenGroups } from '../util';
import { GeneratorPanel } from './generator';

const RESERVED = new Set(['Title', 'UserName', 'Password', 'URL', 'Notes']);
const OTP = 'otp';

interface FieldState extends CustomFieldDraft {
  key: number;
}

let fieldCounter = 0;

function months(count: number): number {
  const date = new Date();
  date.setMonth(date.getMonth() + count);
  return Math.floor(date.getTime() / 1000);
}

export function EntryEditor({
  id,
  groupId,
  prefill,
}: {
  id: string | null;
  groupId: string | null;
  prefill: Partial<EntryDraft> | undefined;
}) {
  const t = useT();
  const app = useApp();
  const data = useSnapshot();
  const language = useLanguage();
  const [loading, setLoading] = useState(id !== null);
  const [title, setTitle] = useState(prefill?.title ?? '');
  const [username, setUsername] = useState(prefill?.username ?? '');
  const [password, setPassword] = useState(prefill?.password ?? '');
  const [url, setUrl] = useState(prefill?.url ?? '');
  const [notes, setNotes] = useState(prefill?.notes ?? '');
  const [fields, setFields] = useState<FieldState[]>([]);
  const [tags, setTags] = useState<string[]>(prefill?.tags ?? []);
  const [tagInput, setTagInput] = useState('');
  const [favorite, setFavorite] = useState(prefill?.favorite ?? false);
  const [expires, setExpires] = useState<number | null>(prefill?.expiresAt ?? null);
  const [icon, setIcon] = useState(DEFAULT_ENTRY_ICON);
  const [group, setGroup] = useState<string>(groupId ?? '');
  const [original, setOriginal] = useState<string>('');
  const [picking, setPicking] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [totpDialog, setTotpDialog] = useState(false);
  const [discard, setDiscard] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState(false);
  const initialIcon = useRef(DEFAULT_ENTRY_ICON);
  const initialGroup = useRef('');
  const plus = app?.account.access.plusFeatures === true;
  const rootId = data?.info.rootGroupId ?? '';

  useEffect(() => {
    if (!rootId) return;
    if (group === '') setGroup(groupId ?? rootId);
  }, [rootId]);

  useEffect(() => {
    if (id === null) {
      setOriginal(JSON.stringify(['', '', '', '', '', [], [], false, null]));
      return;
    }
    const life = new AbortController();
    void (async () => {
      try {
        const detail = await host.entry(id);
        const summary = detail.summary;
        const secret = async (key: string, known: boolean) =>
          known ? await host.reveal(id, key) : '';
        const loadedPassword = summary.hasPassword ? await host.reveal(id, 'Password') : '';
        const loadedNotes =
          detail.notes.kind === 'plain' ? detail.notes.value : await secret('Notes', true);
        const loadedFields: FieldState[] = [];
        for (const field of detail.customFields) {
          const value =
            field.value.kind === 'plain' ? field.value.value : await host.reveal(id, field.name);
          loadedFields.push({
            key: ++fieldCounter,
            name: field.name,
            value,
            protected: field.value.kind === 'protected',
          });
        }
        if (life.signal.aborted) return;
        const loadedTitle = plain(summary.title);
        const loadedUser = plain(summary.username);
        const loadedUrl = plain(summary.url);
        setTitle(loadedTitle);
        setUsername(loadedUser);
        setPassword(loadedPassword);
        setUrl(loadedUrl);
        setNotes(loadedNotes);
        setFields(loadedFields);
        setTags(summary.tags);
        setFavorite(summary.favorite);
        setExpires(summary.expiresAt);
        setIcon(summary.icon);
        initialIcon.current = summary.icon;
        setGroup(summary.groupId);
        initialGroup.current = summary.groupId;
        setOriginal(
          snapshotOf(
            loadedTitle,
            loadedUser,
            loadedPassword,
            loadedUrl,
            loadedNotes,
            loadedFields,
            summary.tags,
            summary.favorite,
            summary.expiresAt,
            summary.icon,
            summary.groupId,
          ),
        );
        setLoading(false);
      } catch (cause) {
        if (!life.signal.aborted) {
          setError(errorText(codeOf(cause)));
          setLoading(false);
        }
      }
    })();
    return () => {
      life.abort();
    };
  }, [id]);

  const current = useMemo(
    () =>
      snapshotOf(
        title,
        username,
        password,
        url,
        notes,
        fields,
        tags,
        favorite,
        expires,
        icon,
        group,
      ),
    [title, username, password, url, notes, fields, tags, favorite, expires, icon, group],
  );
  const dirty =
    id === null
      ? current !== snapshotOf('', '', '', '', '', [], [], false, null, DEFAULT_ENTRY_ICON, group)
      : current !== original;

  if (!app?.session || !data) return null;
  if (loading) {
    return (
      <div class="app">
        <TopBar title={t('editor.titleEdit')} onBack={back} />
        <Loading />
      </div>
    );
  }

  const otp = fields.find((f) => f.name === OTP);
  const visibleFields = fields.filter((f) => f.name !== OTP);

  function leave() {
    if (dirty) setDiscard(true);
    else back();
  }

  function addTag(raw: string) {
    const value = raw.trim();
    if (value === '' || tags.some((tag) => tag.toLowerCase() === value.toLowerCase())) {
      setTagInput('');
      return;
    }
    setTags([...tags, value]);
    setTagInput('');
  }

  function updateField(key: number, patch: Partial<CustomFieldDraft>) {
    setFields(fields.map((f) => (f.key === key ? { ...f, ...patch } : f)));
  }

  function validateFields(): boolean {
    const names = new Set<string>();
    for (const field of fields) {
      const name = field.name.trim();
      if (name === '' || RESERVED.has(name) || names.has(name)) return false;
      names.add(name);
    }
    return true;
  }

  async function save() {
    setError(null);
    if (!validateFields()) {
      setFieldErrors(true);
      setError(t('editor.errorFieldName'));
      return;
    }
    const draft: EntryDraft = {
      title: title.trim(),
      username,
      password,
      url: url.trim(),
      notes,
      customFields: fields.map((f) => ({
        name: f.name.trim(),
        value: f.value,
        protected: f.protected,
      })),
      tags,
      favorite,
      expiresAt: expires,
    };
    setBusy(true);
    try {
      let entryId = id;
      let saved = true;
      if (id === null) {
        const result = await host.createEntry(group || rootId, draft);
        entryId = result.value;
        saved = result.saved;
        if (icon !== DEFAULT_ENTRY_ICON) await host.setEntryIcon(entryId, icon);
      } else {
        const result = await host.updateEntry(id, draft);
        saved = result.saved;
        if (icon !== initialIcon.current) await host.setEntryIcon(id, icon);
        if (group !== initialGroup.current) await host.moveEntry(id, group);
      }
      if (!saved) showToast(t('editor.notSavedToFile'), 'danger');
      if (entryId) replace({ name: 'entry', id: entryId });
      else back();
    } catch (cause) {
      const code = codeOf(cause);
      setError(
        code === 'invalid_field_name'
          ? t('editor.errorFieldName')
          : code === 'invalid_text'
            ? t('editor.errorText')
            : code === 'read_only'
              ? t('editor.errorReadOnly')
              : t('editor.errorGeneric'),
      );
    } finally {
      setBusy(false);
    }
  }

  const groupOptions = flattenGroups(data.groups);
  const expiryLabel = expires === null ? t('editor.expiresNever') : formatDate(language, expires);
  const dateValue = expires === null ? '' : new Date(expires * 1000).toISOString().slice(0, 10);
  const previewEntry = {
    id: id ?? 'new',
    title: { kind: 'plain', value: title } as const,
    icon,
    customIcon: null,
  };

  return (
    <div class="app">
      <TopBar
        title={id === null ? t('editor.titleNew') : t('editor.titleEdit')}
        onBack={leave}
        actions={
          <Button
            variant="action"
            size="pill"
            busy={busy}
            onClick={() => {
              void save();
            }}
          >
            {t('action.save')}
          </Button>
        }
      />
      <div class="scroll">
        <form
          class="page"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          {error ? <Banner tone="danger">{error}</Banner> : null}
          <div class="cluster editor-head">
            <button
              type="button"
              class="icon-pick"
              onClick={() => {
                setPicking(true);
              }}
              aria-label={t('action.changeIcon')}
            >
              {icon === DEFAULT_ENTRY_ICON ? (
                <Monogram text={title} seed={previewEntry.id} size={56} />
              ) : (
                <span class="tile tile-accent" style={{ width: '56px', height: '56px' }}>
                  <Icon name={itemIconOf(icon, 0).icon} size={28} />
                </span>
              )}
              <span class="caption">{t('action.changeIcon')}</span>
            </button>
          </div>
          <TextField
            label={t('field.title')}
            value={title}
            onInput={setTitle}
            autofocus={id === null}
          />
          <TextField
            label={t('field.username')}
            value={username}
            onInput={setUsername}
            autocomplete="off"
          />
          <div class="stack-tight">
            <SecretField
              label={t('field.password')}
              value={password}
              onInput={setPassword}
              extra={
                <IconButton
                  icon="dice"
                  label={t('generator.open')}
                  onClick={() => {
                    setGenerating(true);
                  }}
                />
              }
            />
            <StrengthMeter password={password} />
          </div>
          <TextField
            label={t('field.url')}
            value={url}
            onInput={setUrl}
            type="url"
            autocomplete="off"
          />
          <TextField label={t('field.notes')} value={notes} onInput={setNotes} multiline rows={4} />

          <SectionLabel>{t('totp.label')}</SectionLabel>
          <Card class="card-pad">
            <div class="cluster">
              {otp ? (
                <>
                  <span class="badge badge-accent">
                    <Icon name="clock" size={14} /> {t('totp.set')}
                  </span>
                  <Button
                    variant="text"
                    size="pill"
                    onClick={() => {
                      setTotpDialog(true);
                    }}
                  >
                    {t('totp.replace')}
                  </Button>
                  <Button
                    variant="caution"
                    size="pill"
                    onClick={() => {
                      setFields(fields.filter((f) => f.name !== OTP));
                    }}
                  >
                    {t('totp.remove')}
                  </Button>
                </>
              ) : (
                <Button
                  variant="raised"
                  size="pill"
                  icon="add"
                  onClick={() => {
                    if (plus) setTotpDialog(true);
                    else showToast(t('totp.errorEntitled'), 'danger');
                  }}
                >
                  {t('editor.addTotp')}
                </Button>
              )}
              {!plus ? <span class="badge badge-accent">Keep+</span> : null}
            </div>
          </Card>

          <SectionLabel>{t('editor.customFields')}</SectionLabel>
          <div class="stack">
            {visibleFields.map((field) => {
              const name = field.name.trim();
              const bad =
                fieldErrors &&
                (name === '' ||
                  RESERVED.has(name) ||
                  fields.filter((f) => f.name.trim() === name).length > 1);
              return (
                <Card key={field.key} class="card-pad">
                  <div class="stack">
                    <TextField
                      label={t('editor.fieldName')}
                      value={field.name}
                      onInput={(value) => {
                        updateField(field.key, { name: value });
                      }}
                      error={bad ? t('editor.errorFieldName') : null}
                    />
                    {field.protected ? (
                      <SecretField
                        label={t('editor.fieldValue')}
                        value={field.value}
                        onInput={(value) => {
                          updateField(field.key, { value });
                        }}
                      />
                    ) : (
                      <TextField
                        label={t('editor.fieldValue')}
                        value={field.value}
                        onInput={(value) => {
                          updateField(field.key, { value });
                        }}
                        multiline
                        rows={2}
                      />
                    )}
                    <div class="cluster">
                      <div class="spacer">
                        <Switch
                          label={t('editor.fieldProtected')}
                          checked={field.protected}
                          onChange={(checked) => {
                            updateField(field.key, { protected: checked });
                          }}
                        />
                      </div>
                      <IconButton
                        icon="trash"
                        tone="caution"
                        label={t('editor.removeField')}
                        onClick={() => {
                          setFields(fields.filter((f) => f.key !== field.key));
                        }}
                      />
                    </div>
                  </div>
                </Card>
              );
            })}
            <div>
              <Button
                variant="raised"
                size="pill"
                icon="add"
                onClick={() => {
                  setFields([
                    ...fields,
                    { key: ++fieldCounter, name: '', value: '', protected: false },
                  ]);
                }}
              >
                {t('editor.addField')}
              </Button>
            </div>
          </div>

          <SectionLabel>{t('field.tags')}</SectionLabel>
          <div class="stack-tight">
            <div class="cluster">
              {tags.map((tag) => (
                <Chip
                  key={tag}
                  icon="tag"
                  onRemove={() => {
                    setTags(tags.filter((x) => x !== tag));
                  }}
                  removeLabel={t('editor.tagRemove', { tag })}
                >
                  {tag}
                </Chip>
              ))}
            </div>
            <TextField
              label={t('editor.tagPlaceholder')}
              value={tagInput}
              onInput={(value) => {
                if (value.endsWith(',')) addTag(value.slice(0, -1));
                else setTagInput(value);
              }}
              onEnter={() => {
                addTag(tagInput);
              }}
              trailing={
                <IconButton
                  icon="add"
                  label={t('editor.tagAdd')}
                  onClick={() => {
                    addTag(tagInput);
                  }}
                />
              }
            />
          </div>

          <SectionLabel>{t('field.expires')}</SectionLabel>
          <Card class="card-pad">
            <div class="stack">
              <p class="caption">{expiryLabel}</p>
              <div class="cluster">
                <Chip
                  selected={expires === null}
                  onClick={() => {
                    setExpires(null);
                  }}
                >
                  {t('editor.expiresNever')}
                </Chip>
                <Chip
                  onClick={() => {
                    setExpires(months(1));
                  }}
                >
                  {t('editor.expiresMonth')}
                </Chip>
                <Chip
                  onClick={() => {
                    setExpires(months(3));
                  }}
                >
                  {t('editor.expiresThreeMonths')}
                </Chip>
                <Chip
                  onClick={() => {
                    setExpires(months(6));
                  }}
                >
                  {t('editor.expiresHalfYear')}
                </Chip>
                <Chip
                  onClick={() => {
                    setExpires(months(12));
                  }}
                >
                  {t('editor.expiresYear')}
                </Chip>
              </div>
              <TextField
                label={t('editor.expiresPick')}
                value={dateValue}
                type="text"
                placeholder="YYYY-MM-DD"
                onInput={(value) => {
                  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
                    const parsed = Date.parse(`${value}T12:00:00Z`);
                    if (!Number.isNaN(parsed)) setExpires(Math.floor(parsed / 1000));
                  } else if (value === '') {
                    setExpires(null);
                  }
                }}
              />
            </div>
          </Card>

          <Card>
            <Switch label={t('editor.favorite')} checked={favorite} onChange={setFavorite} />
          </Card>

          <div class="select-row card">
            <label for="entry-group" class="select-label">
              {t('field.group')}
            </label>
            <select
              id="entry-group"
              value={group}
              onChange={(event) => {
                setGroup(event.currentTarget.value);
              }}
            >
              {groupOptions.map(({ group: option, depth }) => (
                <option key={option.id} value={option.id}>
                  {`${'  '.repeat(depth)}${option.parentId === null ? t('vault.filterAll') : option.name}`}
                </option>
              ))}
            </select>
          </div>

          <Button variant="action" size="large" full type="submit" busy={busy}>
            {t('action.save')}
          </Button>
        </form>
      </div>

      {picking ? (
        <IconPicker
          title={t('iconPicker.entry')}
          current={icon}
          onClose={() => {
            setPicking(false);
          }}
          onPick={(picked) => {
            setIcon(picked);
            setPicking(false);
          }}
        />
      ) : null}
      {generating ? (
        <Dialog
          title={t('generator.title')}
          onClose={() => {
            setGenerating(false);
          }}
          wide
        >
          <GeneratorPanel
            onUse={(value) => {
              setPassword(value);
              setGenerating(false);
            }}
          />
        </Dialog>
      ) : null}
      {totpDialog ? (
        <TotpDialog
          onClose={() => {
            setTotpDialog(false);
          }}
          onAdd={(uri) => {
            const rest = fields.filter((f) => f.name !== OTP);
            setFields([...rest, { key: ++fieldCounter, name: OTP, value: uri, protected: true }]);
            setTotpDialog(false);
          }}
        />
      ) : null}
      {discard ? (
        <Confirm
          title={t('editor.discardTitle')}
          message={t('editor.discardMessage')}
          confirm={t('editor.discard')}
          cancel={t('editor.keepEditing')}
          tone="caution"
          onCancel={() => {
            setDiscard(false);
          }}
          onConfirm={() => {
            setDiscard(false);
            back();
          }}
        />
      ) : null}
    </div>
  );
}

function snapshotOf(
  title: string,
  username: string,
  password: string,
  url: string,
  notes: string,
  fields: CustomFieldDraft[],
  tags: string[],
  favorite: boolean,
  expires: number | null,
  icon = 0,
  group = '',
): string {
  return JSON.stringify([
    title,
    username,
    password,
    url,
    notes,
    fields.map((f) => [f.name, f.value, f.protected]),
    tags,
    favorite,
    expires,
    icon,
    group,
  ]);
}

function TotpDialog({ onAdd, onClose }: { onAdd: (uri: string) => void; onClose: () => void }) {
  const t = useT();
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function add() {
    if (value.trim() === '') {
      setError(t('totp.errorEmpty'));
      return;
    }
    setBusy(true);
    try {
      const setup = await host.totpNormalize(value.trim());
      onAdd(setup.uri);
    } catch (cause) {
      const code = codeOf(cause);
      const messages: Record<string, Parameters<typeof t>[0]> = {
        empty: 'totp.errorEmpty',
        missing_secret: 'totp.errorSecret',
        invalid_secret: 'totp.errorSecret',
        unsupported_type: 'totp.errorType',
        invalid_digits: 'totp.errorDigits',
        invalid_period: 'totp.errorPeriod',
        invalid_algorithm: 'totp.errorAlgorithm',
        migration: 'totp.errorMigration',
        not_entitled: 'totp.errorEntitled',
      };
      setError(t(messages[code] ?? 'totp.errorGeneric'));
    } finally {
      setBusy(false);
    }
  }

  async function fromImage(file: File | undefined) {
    if (!file) return;
    if (typeof BarcodeDetector === 'undefined') {
      setError(t('totp.qrUnsupported'));
      return;
    }
    try {
      const bitmap = await createImageBitmap(file);
      const found = await new BarcodeDetector({ formats: ['qr_code'] }).detect(bitmap);
      const raw = found[0]?.rawValue;
      if (raw) {
        setValue(raw);
        setError(null);
      } else {
        setError(t('totp.qrNotFound'));
      }
    } catch {
      setError(t('totp.qrNotFound'));
    }
  }

  async function fromPage() {
    try {
      const shot = await chrome.tabs.captureVisibleTab({ format: 'png' });
      const bitmap = await createImageBitmap(await (await fetch(shot)).blob());
      const found = await new BarcodeDetector({ formats: ['qr_code'] }).detect(bitmap);
      const raw = found[0]?.rawValue;
      if (raw) {
        setValue(raw);
        setError(null);
      } else {
        setError(t('totp.qrNotFound'));
      }
    } catch {
      setError(t('totp.qrNotFound'));
    }
  }

  return (
    <Dialog
      title={t('totp.dialogTitle')}
      onClose={onClose}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('action.cancel')}
          </Button>
          <Button
            variant="action"
            busy={busy}
            onClick={() => {
              void add();
            }}
          >
            {t('totp.dialogAdd')}
          </Button>
        </>
      }
    >
      <p class="dialog-message">{t('totp.dialogHint')}</p>
      <TextField
        label={t('totp.dialogField')}
        value={value}
        onInput={setValue}
        autofocus
        mono
        error={error}
        onEnter={() => {
          void add();
        }}
      />
      {typeof BarcodeDetector !== 'undefined' && surface() !== 'tab' ? (
        <div>
          <Button
            variant="raised"
            size="pill"
            icon="search"
            onClick={() => {
              void fromPage();
            }}
          >
            {t('totp.scanPage')}
          </Button>
        </div>
      ) : null}
      <label class="btn btn-raised btn-pill file-button">
        <Icon name="upload" size={16} />
        <span>{t('totp.fromImage')}</span>
        <input
          type="file"
          accept="image/*"
          class="visually-hidden"
          onChange={(event) => {
            void fromImage(event.currentTarget.files?.[0]);
          }}
        />
      </label>
    </Dialog>
  );
}
