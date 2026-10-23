import type { KeepCore } from '../core/createCore.ts';
import { plain, type EntrySummary, type VaultGroup } from '../core/types.ts';

export interface WebOrigin {
  scheme: 'https' | 'http';
  host: string;
  port: number | null;
}

export interface IndexedEntry {
  id: string;
  title: string;
  username: string;
  url: string;
  origin: WebOrigin | null;
  hasPassword: boolean;
  hasTotp: boolean;
  favorite: boolean;
  passkeyRp: string | null;
  passkeyCredential: string | null;
  icon: number;
  customIcon: string | null;
}

/** An entry's address field. A bare host ("example.com/login") is read as https; only http and https can be filled. */
export function parseEntryUrl(raw: string): WebOrigin | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const text = trimmed.includes('://') ? trimmed : `https://${trimmed}`;
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return null;
  }
  return originOf(url);
}

export function originOf(url: URL): WebOrigin | null {
  const scheme = url.protocol === 'https:' ? 'https' : url.protocol === 'http:' ? 'http' : null;
  if (!scheme || url.hostname === '') return null;
  const host = url.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  const port = url.port === '' ? null : Number(url.port);
  return { scheme, host, port };
}

export function pageOrigin(href: string): WebOrigin | null {
  try {
    return originOf(new URL(href));
  } catch {
    return null;
  }
}

function recycleBinMembers(groups: VaultGroup[], recycleBinId: string | null): Set<string> {
  const inBin = new Set<string>();
  if (!recycleBinId) return inBin;
  const children = new Map<string, string[]>();
  for (const group of groups) {
    if (group.parentId) {
      const list = children.get(group.parentId) ?? [];
      list.push(group.id);
      children.set(group.parentId, list);
    }
  }
  const stack = [recycleBinId];
  while (stack.length > 0) {
    const id = stack.pop();
    if (id === undefined || inBin.has(id)) continue;
    inBin.add(id);
    stack.push(...(children.get(id) ?? []));
  }
  return inBin;
}

export function isInRecycleBin(
  groups: VaultGroup[],
  recycleBinId: string | null,
  groupId: string,
): boolean {
  return recycleBinMembers(groups, recycleBinId).has(groupId);
}

/** The non-secret part of the open vault that Autofill needs: plain title, username and address, and nothing in the Recycle Bin. */
export function buildIndex(
  entries: EntrySummary[],
  groups: VaultGroup[],
  recycleBinId: string | null,
): IndexedEntry[] {
  const bin = recycleBinMembers(groups, recycleBinId);
  return entries
    .filter((entry) => !bin.has(entry.groupId))
    .map((entry) => {
      const url = plain(entry.url);
      return {
        id: entry.id,
        title: plain(entry.title),
        username: plain(entry.username),
        url,
        origin: parseEntryUrl(url),
        hasPassword: entry.hasPassword,
        hasTotp: entry.hasTotp,
        favorite: entry.favorite,
        passkeyRp: entry.passkey?.rpId ?? null,
        passkeyCredential: entry.passkey?.credentialId ?? null,
        icon: entry.icon,
        customIcon: entry.customIcon,
      };
    });
}

export type MatchKind = 'exact' | 'subdomain';

export interface Match {
  entry: IndexedEntry;
  kind: MatchKind;
}

/**
 * Which indexed entries may be offered to a page. Hosts follow the shared core policy: the exact
 * host, or a subdomain of a registrable saved host; never a parent, a sibling, a look-alike, a public
 * suffix. An https entry is not offered to an http page, and ports must agree.
 */
export function matchEntries(core: KeepCore, index: IndexedEntry[], page: WebOrigin): Match[] {
  const found: Match[] = [];
  for (const entry of index) {
    const saved = entry.origin;
    if (!saved || (!entry.hasPassword && !entry.username)) continue;
    if (saved.port !== page.port) continue;
    if (saved.scheme === 'https' && page.scheme !== 'https') continue;
    const kind = core.matchHost(saved.host, page.host);
    if (kind === 'exact' || kind === 'subdomain') found.push({ entry, kind });
  }
  return found.sort(
    (a, b) =>
      Number(a.kind === 'subdomain') - Number(b.kind === 'subdomain') ||
      Number(b.entry.favorite) - Number(a.entry.favorite) ||
      a.entry.title.localeCompare(b.entry.title, undefined, { sensitivity: 'base' }),
  );
}

export type SavePlan =
  | { kind: 'create'; alternatives: IndexedEntry[] }
  | { kind: 'update'; entry: IndexedEntry }
  | { kind: 'choose'; candidates: IndexedEntry[] };

export function sameUsername(saved: string, submitted: string): boolean {
  const a = saved.trim();
  const b = submitted.trim();
  if (a === '' || b === '') return false;
  return a === b || (a.includes('@') && b.includes('@') && a.toLowerCase() === b.toLowerCase());
}

/** What to offer for a submitted credential, from the non-secret index only. */
export function planSave(matches: Match[], username: string): SavePlan {
  const forOrigin = matches.map((m) => m.entry);
  const name = username.trim();
  if (name === '') {
    if (forOrigin.length === 0) return { kind: 'create', alternatives: [] };
    if (forOrigin.length === 1 && forOrigin[0]) return { kind: 'update', entry: forOrigin[0] };
    return { kind: 'choose', candidates: forOrigin };
  }
  const same = forOrigin.filter((entry) => sameUsername(entry.username, name));
  if (same.length === 0) return { kind: 'create', alternatives: forOrigin };
  if (same.length === 1 && same[0]) return { kind: 'update', entry: same[0] };
  return { kind: 'choose', candidates: same };
}

export function urlFor(page: WebOrigin): string {
  const port = page.port === null ? '' : `:${page.port}`;
  return `${page.scheme}://${page.host}${port}`;
}

export function titleFor(page: WebOrigin): string {
  return page.host.replace(/^www\./, '');
}
