import { useEffect, useMemo, useState } from 'preact/hooks';
import { PAGE_CHANNEL } from '../../shared/content';
import type { SiteMatch } from '../../shared/api';
import { plain, type EntrySummary, type VaultGroup } from '../../core/types';
import { Confirm, Dialog, showToast } from '../components/dialogs';
import { EntryIcon, GroupIcon } from '../components/entryParts';
import {
  Banner,
  Button,
  Card,
  Chip,
  Empty,
  IconButton,
  Loading,
  Row,
  SectionLabel,
  TextField,
} from '../components/ui';
import { IconPicker } from '../components/iconPicker';
import { openInTab, requestWrite } from '../files';
import { Icon } from '../icons';
import { useAtom } from '../state/atom';
import { host, surface } from '../state/client';
import { useApp, useSnapshot, useT, useWide } from '../state/hooks';
import { browse, go, replace, useRoute, type Page } from '../state/route';
import {
  codeOf,
  copySecret,
  descendantIds,
  entryTitle,
  errorText,
  flattenGroups,
  hostOf,
  isExpired,
} from '../util';
import { EntryScreen } from './entry';
import { EntryEditor } from './editor';
import { HistoryScreen, VersionScreen } from './history';
import { saveAs, writeHandle } from '../files';

export function VaultScreen() {
  const t = useT();
  const app = useApp();
  const data = useSnapshot();
  const route = useRoute();
  const wide = useWide();
  if (!app?.session) return null;
  if (!data) return <Loading label={t('vault.loading')} />;

  const detailRoute =
    route.name === 'entry' ||
    route.name === 'edit' ||
    route.name === 'history' ||
    route.name === 'version';
  const detail = detailRoute ? <Detail route={route} /> : null;

  if (!wide) {
    return detail ?? <ListPane />;
  }
  return (
    <div class="app shell">
      <WideHeader />
      <div class="shell-body">
        <Sidebar />
        <div class="pane pane-list">
          <ListPane embedded />
        </div>
        <div class="pane pane-detail">
          {detail ?? (
            <Empty icon="lock" title={t('vault.pickTitle')} message={t('vault.pickMessage')} />
          )}
        </div>
      </div>
    </div>
  );
}

function Detail({ route }: { route: Page }) {
  switch (route.name) {
    case 'entry':
      return <EntryScreen id={route.id} />;
    case 'edit':
      return <EntryEditor id={route.id} groupId={route.groupId} prefill={route.prefill} />;
    case 'history':
      return <HistoryScreen id={route.id} />;
    case 'version':
      return <VersionScreen id={route.id} index={route.index} />;
    default:
      return null;
  }
}

function WideHeader() {
  const t = useT();
  const app = useApp();
  const session = app?.session;
  if (!session) return null;
  return (
    <header class="wide-header">
      <button
        type="button"
        class="vault-chip"
        onClick={() => {
          go({ name: 'hub' });
        }}
        aria-label={t('switcher.description', { name: session.name })}
      >
        <span class="tile tile-accent" style={{ width: '32px', height: '32px' }}>
          <Icon name="unlock" size={16} />
        </span>
        <span class="vault-chip-name">{session.name}</span>
        <Icon name="swap" size={16} />
      </button>
      <div class="spacer" />
      <IconButton
        icon="dice"
        label={t('dock.generator')}
        onClick={() => {
          go({ name: 'generator' });
        }}
      />
      <IconButton
        icon="settings"
        label={t('settings.title')}
        onClick={() => {
          go({ name: 'settings', page: 'home' });
        }}
      />
      <IconButton
        icon="lock"
        label={t('action.lock')}
        onClick={() => {
          void host.lock();
        }}
      />
    </header>
  );
}

function Sidebar() {
  const t = useT();
  const data = useSnapshot();
  const state = useAtom(browse);
  const app = useApp();
  const [creating, setCreating] = useState(false);
  if (!data || !app?.session) return null;
  const rootId = data.info.rootGroupId;
  const flat = flattenGroups(data.groups);
  const editable = app.session.editable;
  const select = (groupId: string | null, filter: 'all' | 'favorites' | 'expired' = 'all') => {
    browse.set({ groupId, query: '', filter });
    replace({ name: 'vault' });
  };
  return (
    <nav class="sidebar" aria-label={t('vault.sectionGroups')}>
      <div class="stack-tight">
        <SideItem
          icon="archive"
          label={t('vault.filterAll')}
          active={state.groupId === null && state.filter === 'all' && state.query === ''}
          onClick={() => {
            select(null);
          }}
        />
        <SideItem
          icon="star"
          label={t('vault.filterFavorites')}
          active={state.filter === 'favorites'}
          onClick={() => {
            select(null, 'favorites');
          }}
        />
        <SideItem
          icon="clock"
          label={t('vault.filterExpired')}
          active={state.filter === 'expired'}
          onClick={() => {
            select(null, 'expired');
          }}
        />
      </div>
      <SectionLabel>{t('vault.sectionGroups')}</SectionLabel>
      <div class="stack-tight sidebar-groups">
        {flat
          .filter(({ group }) => group.id !== rootId)
          .map(({ group, depth }) => (
            <SideItem
              key={group.id}
              icon="folder"
              label={group.name}
              depth={Math.max(0, depth - 1)}
              active={state.groupId === group.id && state.filter === 'all' && state.query === ''}
              onClick={() => {
                select(group.id);
              }}
            />
          ))}
      </div>
      {editable ? (
        <Button
          variant="text"
          size="pill"
          icon="folderAdd"
          onClick={() => {
            setCreating(true);
          }}
        >
          {t('action.newGroup')}
        </Button>
      ) : null}
      {creating ? (
        <GroupNameDialog
          parentId={state.groupId ?? rootId}
          onClose={() => {
            setCreating(false);
          }}
        />
      ) : null}
    </nav>
  );
}

function SideItem({
  icon,
  label,
  active,
  onClick,
  depth = 0,
}: {
  icon: 'archive' | 'star' | 'clock' | 'folder';
  label: string;
  active: boolean;
  onClick: () => void;
  depth?: number;
}) {
  return (
    <button
      type="button"
      class={`side-item${active ? ' side-item-on' : ''}`}
      style={{ paddingLeft: `${12 + depth * 14}px` }}
      onClick={onClick}
      aria-current={active ? 'true' : undefined}
    >
      <Icon name={icon} size={18} />
      <span>{label}</span>
    </button>
  );
}

function useSearch(query: string): EntrySummary[] | null {
  const [results, setResults] = useState<EntrySummary[] | null>(null);
  useEffect(() => {
    if (query.trim() === '') {
      setResults(null);
      return;
    }
    const life = new AbortController();
    const timer = setTimeout(() => {
      void host
        .search(query)
        .then((found) => {
          if (!life.signal.aborted) setResults(found);
        })
        .catch(() => {
          if (!life.signal.aborted) setResults([]);
        });
    }, 120);
    return () => {
      life.abort();
      clearTimeout(timer);
    };
  }, [query]);
  return results;
}

function sortEntries(entries: EntrySummary[]): EntrySummary[] {
  return [...entries].sort(
    (a, b) =>
      Number(b.favorite) - Number(a.favorite) ||
      plain(a.title).localeCompare(plain(b.title), undefined, { sensitivity: 'base' }),
  );
}

export function useActiveTabUrl(): { url: string | null; id: number | null } {
  const [tab, setTab] = useState<{ url: string | null; id: number | null }>({
    url: null,
    id: null,
  });
  useEffect(() => {
    if (surface() === 'tab') return;
    const read = () => {
      void chrome.tabs.query({ active: true, lastFocusedWindow: true }).then(([found]) => {
        setTab({ url: found?.url ?? null, id: found?.id ?? null });
      });
    };
    read();
    chrome.tabs.onActivated.addListener(read);
    chrome.tabs.onUpdated.addListener(read);
    return () => {
      chrome.tabs.onActivated.removeListener(read);
      chrome.tabs.onUpdated.removeListener(read);
    };
  }, []);
  return tab;
}

function SiteMatches() {
  const t = useT();
  const app = useApp();
  const tab = useActiveTabUrl();
  const [matches, setMatches] = useState<SiteMatch[]>([]);
  useEffect(() => {
    if (!tab.url) {
      setMatches([]);
      return;
    }
    const life = new AbortController();
    void host
      .siteMatches(tab.url)
      .then((found) => {
        if (!life.signal.aborted) setMatches(found);
      })
      .catch(() => undefined);
    return () => {
      life.abort();
    };
  }, [tab.url, app?.session?.dataVersion]);
  if (matches.length === 0 || tab.id === null) return null;
  const tabId = tab.id;
  const fill = async (match: SiteMatch, withTotp: boolean) => {
    try {
      await chrome.tabs.sendMessage(tabId, {
        channel: PAGE_CHANNEL,
        cmd: 'fill',
        id: match.id,
        withTotp,
      });
      if (surface() === 'popup') window.close();
    } catch {
      showToast(t('site.fillFailed'), 'danger');
    }
  };
  return (
    <>
      <SectionLabel>{t('site.forThisSite', { host: hostOf(tab.url ?? '') })}</SectionLabel>
      <Card>
        {matches.map((match) => (
          <Row
            key={match.id}
            title={match.title === '' ? t('entry.untitled') : match.title}
            subtitle={match.username}
            leading={
              <span class="tile tile-accent" style={{ width: '40px', height: '40px' }}>
                <Icon name="globe" size={20} />
              </span>
            }
            trailing={
              <>
                {match.hasTotp ? (
                  <IconButton
                    icon="clock"
                    label={t('c.fillCode')}
                    onClick={() => {
                      void fill(match, true);
                    }}
                  />
                ) : null}
                <Button
                  variant="tonal"
                  size="pill"
                  onClick={() => {
                    void fill(match, false);
                  }}
                >
                  {t('c.fill')}
                </Button>
              </>
            }
          />
        ))}
      </Card>
    </>
  );
}

export function SaveProblemBanner() {
  const t = useT();
  const app = useApp();
  const [busy, setBusy] = useState(false);
  const session = app?.session;
  if (!session?.problem) return null;
  const problem = session.problem;
  const retry = async (force = false) => {
    setBusy(true);
    try {
      if (problem === 'permission') {
        if (!(await requestWrite(session.vaultId))) return;
        await host.allowEditing();
      } else if (force) await host.overwrite();
      else await host.retrySave();
    } finally {
      setBusy(false);
    }
  };
  const saveCopy = async () => {
    try {
      const bytes = await host.exportCopy();
      const handle = await saveAs(`${session.name}-copy.kdbx`);
      if (!handle) return;
      await writeHandle(handle, bytes);
      showToast(t('copy.saved'));
    } catch {
      showToast(t('copy.failedSave'), 'danger');
    }
  };
  const messages: Record<string, Parameters<typeof t>[0]> = {
    changedElsewhere: 'save.changedMessage',
    unavailable: 'save.unavailable',
    notWritable: 'save.notWritable',
    permission: 'vault.accessNotGranted',
    noSpace: 'save.noSpace',
    backupFailed: 'save.backupFailed',
    verificationFailed: 'save.verificationFailed',
    encryptionFailed: 'save.encryptionFailed',
    restoreNeeded: 'save.restoreNeeded',
    readOnly: 'save.readOnly',
  };
  return (
    <div class="stack-tight">
      <Banner tone="danger">
        <strong>
          {problem === 'changedElsewhere' ? t('save.changedTitle') : t('save.failedTitle')}.
        </strong>{' '}
        {t(messages[problem] ?? 'save.unavailable')}
      </Banner>
      {session.problemDetail ? <p class="caption">{session.problemDetail}</p> : null}
      <div class="cluster">
        {problem === 'changedElsewhere' ? (
          <>
            <Button
              variant="tonal"
              size="pill"
              onClick={() => {
                void host.reload();
              }}
              busy={busy}
            >
              {t('save.reload')}
            </Button>
            <Button
              variant="raised"
              size="pill"
              onClick={() => {
                void saveCopy();
              }}
            >
              {t('save.copy')}
            </Button>
            <Button
              variant="caution"
              size="pill"
              onClick={() => {
                void retry(true);
              }}
              busy={busy}
            >
              {t('save.overwrite')}
            </Button>
          </>
        ) : problem === 'permission' ? (
          <Button
            variant="tonal"
            size="pill"
            onClick={() => {
              void retry();
            }}
            busy={busy}
          >
            {t('vault.allowEditing')}
          </Button>
        ) : (
          <>
            <Button
              variant="tonal"
              size="pill"
              onClick={() => {
                void retry();
              }}
              busy={busy}
            >
              {t('save.tryAgain')}
            </Button>
            <Button
              variant="raised"
              size="pill"
              onClick={() => {
                void saveCopy();
              }}
            >
              {t('save.copy')}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

function EntryRow({
  entry,
  vaultId,
  onOpen,
  selected,
}: {
  entry: EntrySummary;
  vaultId: string;
  onOpen: (id: string) => void;
  selected: boolean;
}) {
  const t = useT();
  const app = useApp();
  const subtitle = plain(entry.username) || hostOf(plain(entry.url));
  const clear = app?.settings.clipboardClear ?? 30;
  return (
    <Row
      onClick={() => {
        onOpen(entry.id);
      }}
      selected={selected}
      leading={<EntryIcon entry={entry} vaultId={vaultId} />}
      title={entryTitle(entry)}
      subtitle={subtitle}
      trailing={
        <>
          {isExpired(entry) ? (
            <span class="badge badge-caution">{t('entry.expiredBadge')}</span>
          ) : null}
          {entry.passkey ? <Icon name="passkey" size={16} /> : null}
          {entry.favorite ? <Icon name="star" size={16} filled class="fav" /> : null}
          {entry.hasPassword ? (
            <IconButton
              icon="copy"
              label={t('entry.copyPasswordFor', { title: entryTitle(entry) })}
              onClick={(event) => {
                event.stopPropagation();
                void host
                  .reveal(entry.id, 'Password')
                  .then((value) => copySecret(value, clear))
                  .catch((error: unknown) => {
                    showToast(errorText(codeOf(error)), 'danger');
                  });
              }}
            />
          ) : null}
        </>
      }
    />
  );
}

export function ListPane({ embedded = false }: { embedded?: boolean }) {
  const t = useT();
  const app = useApp();
  const data = useSnapshot();
  const route = useRoute();
  const wide = useWide();
  const state = useAtom(browse);
  const results = useSearch(state.query);
  const [fab, setFab] = useState(false);
  const [newGroup, setNewGroup] = useState(false);
  const [groupMenu, setGroupMenu] = useState<VaultGroup | null>(null);
  const session = app?.session;
  const groups = data?.groups ?? [];
  const bin = useMemo(
    () =>
      data?.info.recycleBinId ? descendantIds(groups, data.info.recycleBinId) : new Set<string>(),
    [data],
  );

  if (!session || !data) return null;
  const rootId = data.info.rootGroupId;
  const currentId = state.groupId ?? rootId;
  const editable = session.editable;
  const selectedId = route.name === 'entry' || route.name === 'edit' ? route.id : null;
  const open = (id: string) => {
    if (
      wide &&
      (route.name === 'entry' ||
        route.name === 'edit' ||
        route.name === 'history' ||
        route.name === 'version')
    ) {
      replace({ name: 'entry', id });
    } else {
      go({ name: 'entry', id });
    }
  };

  const searching = state.query.trim() !== '';
  let entries: EntrySummary[];
  let subgroups: VaultGroup[] = [];
  if (searching) {
    entries = results ?? [];
  } else if (state.filter === 'favorites') {
    entries = data.entries.filter((e) => e.favorite && !bin.has(e.groupId));
  } else if (state.filter === 'expired') {
    entries = data.entries.filter((e) => isExpired(e) && !bin.has(e.groupId));
  } else {
    entries = data.entries.filter((e) => e.groupId === currentId);
    subgroups = groups.filter((g) => g.parentId === currentId);
  }
  entries = sortEntries(entries);
  subgroups = [...subgroups].sort((a, b) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }),
  );

  const path: VaultGroup[] = [];
  {
    let cursor = groups.find((g) => g.id === currentId);
    const byId = new Map(groups.map((g) => [g.id, g]));
    let guard = 0;
    while (cursor && cursor.id !== rootId && guard < 64) {
      path.unshift(cursor);
      cursor = cursor.parentId ? byId.get(cursor.parentId) : undefined;
      guard += 1;
    }
  }
  const currentGroup = groups.find((g) => g.id === currentId);
  const empty = entries.length === 0 && subgroups.length === 0;

  return (
    <div class={embedded ? 'list-pane' : 'app'}>
      {!embedded ? (
        <header class="shell-top">
          <button
            type="button"
            class="vault-chip"
            onClick={() => {
              go({ name: 'hub' });
            }}
            aria-label={t('switcher.description', { name: session.name })}
          >
            <span class="tile tile-accent" style={{ width: '32px', height: '32px' }}>
              <Icon name="unlock" size={16} />
            </span>
            <span class="vault-chip-name">{session.name}</span>
            <Icon name="swap" size={16} />
          </button>
          <div class="spacer" />
          <IconButton
            icon="dice"
            label={t('dock.generator')}
            onClick={() => {
              go({ name: 'generator' });
            }}
          />
          <IconButton
            icon="settings"
            label={t('settings.title')}
            onClick={() => {
              go({ name: 'settings', page: 'home' });
            }}
          />
          {surface() === 'popup' ? (
            <IconButton
              icon="expand"
              label={t('action.openTab')}
              onClick={() => {
                openInTab();
              }}
            />
          ) : null}
          <IconButton
            icon="lock"
            label={t('action.lock')}
            onClick={() => {
              void host.lock();
            }}
          />
        </header>
      ) : null}

      <div class="search-bar">
        <div class="field-box search-box">
          <Icon name="search" size={18} />
          <input
            class="field-input"
            type="search"
            value={state.query}
            placeholder={t('vault.searchHint')}
            aria-label={t('vault.searchHint')}
            spellcheck={false}
            onInput={(event) => {
              browse.update((current) => ({ ...current, query: event.currentTarget.value }));
            }}
          />
          {state.query !== '' ? (
            <IconButton
              icon="close"
              label={t('action.clearSearch')}
              onClick={() => {
                browse.update((current) => ({ ...current, query: '' }));
              }}
            />
          ) : null}
        </div>
      </div>

      <div class="scroll">
        <div class="page page-list">
          <SaveProblemBanner />
          {!editable ? (
            <Banner
              tone="info"
              action={session.readOnlyReason === 'file' ? t('vault.allowEditing') : undefined}
              onAction={() => {
                void requestWrite(session.vaultId).then((ok) =>
                  ok ? host.allowEditing() : undefined,
                );
              }}
            >
              {session.readOnlyReason === 'file'
                ? t('vault.readOnlyFile')
                : t('vault.readOnlyPlan')}
            </Banner>
          ) : null}
          {!embedded || !wide ? (
            <div class="cluster filters">
              <Chip
                selected={state.filter === 'all' && !searching}
                onClick={() => {
                  browse.update((c) => ({ ...c, filter: 'all', query: '' }));
                }}
              >
                {t('vault.filterAll')}
              </Chip>
              <Chip
                selected={state.filter === 'favorites'}
                icon="star"
                onClick={() => {
                  browse.update((c) => ({ ...c, filter: 'favorites', query: '' }));
                }}
              >
                {t('vault.filterFavorites')}
              </Chip>
              <Chip
                selected={state.filter === 'expired'}
                icon="clock"
                onClick={() => {
                  browse.update((c) => ({ ...c, filter: 'expired', query: '' }));
                }}
              >
                {t('vault.filterExpired')}
              </Chip>
            </div>
          ) : null}
          {!searching && state.filter === 'all' && !wide ? <SiteMatches /> : null}

          {!searching && state.filter === 'all' && path.length > 0 ? (
            <div class="crumbs" aria-label={t('vault.sectionGroups')}>
              <button
                type="button"
                class="crumb"
                onClick={() => {
                  browse.update((c) => ({ ...c, groupId: null }));
                }}
              >
                <Icon name="archive" size={14} />
              </button>
              {path.map((group) => (
                <span key={group.id} class="crumb-part">
                  <Icon name="chevronRight" size={12} />
                  <button
                    type="button"
                    class="crumb"
                    onClick={() => {
                      browse.update((c) => ({ ...c, groupId: group.id }));
                    }}
                  >
                    {group.name}
                  </button>
                </span>
              ))}
              {editable && currentGroup && currentGroup.id !== rootId ? (
                <IconButton
                  icon="more"
                  label={t('group.actions', { name: currentGroup.name })}
                  onClick={() => {
                    setGroupMenu(currentGroup);
                  }}
                />
              ) : null}
            </div>
          ) : null}

          {subgroups.length > 0 ? (
            <>
              <SectionLabel>{t('vault.sectionGroups')}</SectionLabel>
              <Card>
                {subgroups.map((group) => (
                  <Row
                    key={group.id}
                    onClick={() => {
                      browse.set({ groupId: group.id, query: '', filter: 'all' });
                    }}
                    leading={<GroupIcon icon={group.icon} />}
                    title={group.name}
                    subtitle={t('group.count', {
                      count: data.entries.filter((e) => e.groupId === group.id).length,
                    })}
                    chevron
                  />
                ))}
              </Card>
            </>
          ) : null}

          {entries.length > 0 ? (
            <>
              <SectionLabel>{t('vault.sectionEntries')}</SectionLabel>
              <Card>
                {entries.map((entry) => (
                  <EntryRow
                    key={entry.id}
                    entry={entry}
                    vaultId={session.vaultId}
                    onOpen={open}
                    selected={selectedId === entry.id}
                  />
                ))}
              </Card>
            </>
          ) : null}

          {empty ? (
            searching ? (
              results === null ? (
                <Loading />
              ) : (
                <Empty
                  icon="search"
                  title={t('vault.searchEmptyTitle')}
                  message={t('vault.searchEmptyMessage')}
                />
              )
            ) : state.filter !== 'all' ? (
              <Empty
                icon={state.filter === 'favorites' ? 'star' : 'clock'}
                title={t('vault.searchEmptyTitle')}
              />
            ) : data.entries.length === 0 && data.groups.length <= 2 ? (
              <Empty
                icon="key"
                title={t('vault.newTitle')}
                message={t('vault.newMessage')}
                action={
                  editable ? (
                    <Button
                      variant="action"
                      icon="add"
                      onClick={() => {
                        go({ name: 'edit', id: null, groupId: currentId });
                      }}
                    >
                      {t('vault.firstEntry')}
                    </Button>
                  ) : undefined
                }
              />
            ) : (
              <Empty
                icon="folder"
                title={t('vault.emptyTitle')}
                message={editable ? t('vault.emptyMessageEditable') : t('vault.emptyMessage')}
              />
            )
          ) : null}
        </div>
      </div>

      {editable ? (
        <div class="fab-wrap">
          {fab ? (
            <div class="fab-menu">
              <Button
                variant="raised"
                icon="key"
                onClick={() => {
                  setFab(false);
                  go({ name: 'edit', id: null, groupId: currentId });
                }}
              >
                {t('action.newEntry')}
              </Button>
              <Button
                variant="raised"
                icon="folderAdd"
                onClick={() => {
                  setFab(false);
                  setNewGroup(true);
                }}
              >
                {t('action.newGroup')}
              </Button>
            </div>
          ) : null}
          <button
            type="button"
            class="fab"
            aria-label={t('action.add')}
            aria-expanded={fab}
            onClick={() => {
              setFab(!fab);
            }}
          >
            <Icon name={fab ? 'close' : 'add'} size={24} />
          </button>
        </div>
      ) : null}

      {newGroup ? (
        <GroupNameDialog
          parentId={currentId}
          onClose={() => {
            setNewGroup(false);
          }}
        />
      ) : null}
      {groupMenu ? (
        <GroupMenu
          group={groupMenu}
          bin={bin}
          groups={groups}
          entries={data.entries}
          onClose={() => {
            setGroupMenu(null);
          }}
        />
      ) : null}
    </div>
  );
}

export function GroupNameDialog({
  parentId,
  group,
  onClose,
}: {
  parentId: string;
  group?: VaultGroup;
  onClose: () => void;
}) {
  const t = useT();
  const [name, setName] = useState(group?.name ?? '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function save() {
    if (name.trim() === '') {
      setError(t('vault.errorName'));
      return;
    }
    setBusy(true);
    try {
      const result = group
        ? await host.renameGroup(group.id, name.trim())
        : await host.createGroup(parentId, name.trim());
      if (!result.saved) showToast(t('save.failedTitle'), 'danger');
      if (!group) browse.set({ groupId: result.value as string, query: '', filter: 'all' });
      onClose();
    } catch (cause) {
      setError(codeOf(cause) === 'invalid_text' ? t('vault.errorName') : t('vault.errorEdit'));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog
      title={group ? t('group.renameTitle') : t('group.newTitle')}
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
              void save();
            }}
          >
            {group ? t('action.rename') : t('action.create')}
          </Button>
        </>
      }
    >
      <TextField
        label={t('group.nameLabel')}
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

function GroupMenu({
  group,
  bin,
  groups,
  entries,
  onClose,
}: {
  group: VaultGroup;
  bin: Set<string>;
  groups: VaultGroup[];
  entries: EntrySummary[];
  onClose: () => void;
}) {
  const t = useT();
  const [mode, setMode] = useState<'menu' | 'rename' | 'icon' | 'delete'>('menu');
  const [busy, setBusy] = useState(false);
  const inBin = bin.has(group.id);
  const members = descendantIds(groups, group.id);
  const hasContent =
    entries.some((e) => members.has(e.groupId)) || groups.some((g) => g.parentId === group.id);
  if (mode === 'rename')
    return (
      <GroupNameDialog parentId={group.parentId ?? group.id} group={group} onClose={onClose} />
    );
  if (mode === 'icon') {
    return (
      <IconPicker
        title={t('iconPicker.group', { name: group.name })}
        current={group.icon}
        onClose={onClose}
        onPick={async (icon) => {
          await host.setGroupIcon(group.id, icon);
          onClose();
        }}
      />
    );
  }
  if (mode === 'delete') {
    const message = hasContent
      ? inBin
        ? t('group.deletePermanent')
        : t('group.deleteBin')
      : inBin
        ? t('group.deleteEmptyPermanent')
        : t('group.deleteEmptyBin');
    return (
      <Confirm
        title={t('group.deleteTitle', { name: group.name })}
        message={message}
        confirm={inBin ? t('delete.permanently') : t('delete.toBin')}
        tone="caution"
        busy={busy}
        onCancel={onClose}
        onConfirm={() => {
          setBusy(true);
          void host
            .deleteGroup(group.id, hasContent, inBin)
            .then(() => {
              browse.update((c) => ({ ...c, groupId: group.parentId }));
              onClose();
            })
            .catch((error: unknown) => {
              showToast(errorText(codeOf(error)), 'danger');
              onClose();
            });
        }}
      />
    );
  }
  return (
    <Dialog title={group.name} onClose={onClose}>
      <div class="stack-tight">
        <Button
          variant="raised"
          full
          icon="edit"
          onClick={() => {
            setMode('rename');
          }}
        >
          {t('action.rename')}
        </Button>
        <Button
          variant="raised"
          full
          icon="palette"
          onClick={() => {
            setMode('icon');
          }}
        >
          {t('action.changeIcon')}
        </Button>
        <Button
          variant="caution"
          full
          icon="trash"
          onClick={() => {
            setMode('delete');
          }}
        >
          {t('action.delete')}
        </Button>
      </div>
    </Dialog>
  );
}
