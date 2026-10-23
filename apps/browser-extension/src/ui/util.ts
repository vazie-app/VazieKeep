import { plain, type EntrySummary, type VaultGroup } from '../core/types';
import { host, t } from './state/client';
import { showToast } from './components/dialogs';

export async function digestHex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Copies a value and tells the host to clear the clipboard later, if it still holds the value. */
export async function copySecret(
  text: string,
  clearsAfter: number,
  announce = true,
): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    showToast(t('copy.failed'), 'danger');
    return;
  }
  await host.clipboardCopied(await digestHex(text)).catch(() => undefined);
  if (announce) {
    showToast(
      clearsAfter > 0 ? t('copy.done', { seconds: clearsAfter }) : t('copy.keptInClipboard'),
    );
  }
}

export async function copyPlain(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    showToast(t('copy.plain'));
  } catch {
    showToast(t('copy.failed'), 'danger');
  }
}

const TONES = ['clay', 'rose', 'violet', 'green', 'gold', 'blue'] as const;

export function monogramTone(seed: string): (typeof TONES)[number] {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  return TONES[hash % TONES.length] ?? 'violet';
}

export function entryTitle(entry: EntrySummary): string {
  const title = plain(entry.title).trim();
  return title === '' ? t('entry.untitled') : title;
}

export function initialOf(text: string): string {
  const letter = Array.from(text.trim())[0];
  return letter ? letter.toUpperCase() : '•';
}

export function hostOf(url: string): string {
  const trimmed = url.trim();
  if (trimmed === '') return '';
  try {
    return new URL(trimmed.includes('://') ? trimmed : `https://${trimmed}`).hostname.replace(
      /^www\./,
      '',
    );
  } catch {
    return trimmed;
  }
}

export function openUrl(url: string): void {
  const trimmed = url.trim();
  if (trimmed === '') return;
  try {
    const parsed = new URL(trimmed.includes('://') ? trimmed : `https://${trimmed}`);
    if (
      parsed.protocol === 'https:' ||
      parsed.protocol === 'http:' ||
      parsed.protocol === 'mailto:'
    ) {
      void chrome.tabs.create({ url: parsed.toString() });
    }
  } catch {
    /* not an address */
  }
}

export function groupPath(groups: VaultGroup[], id: string): string {
  const byId = new Map(groups.map((g) => [g.id, g]));
  const parts: string[] = [];
  let current = byId.get(id);
  let guard = 0;
  while (current && guard < 64) {
    if (current.parentId !== null) parts.unshift(current.name);
    current = current.parentId ? byId.get(current.parentId) : undefined;
    guard += 1;
  }
  return parts.join(' / ');
}

export function descendantIds(groups: VaultGroup[], id: string): Set<string> {
  const result = new Set<string>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const group of groups) {
      if (group.parentId && result.has(group.parentId) && !result.has(group.id)) {
        result.add(group.id);
        grew = true;
      }
    }
  }
  return result;
}

export function flattenGroups(groups: VaultGroup[]): { group: VaultGroup; depth: number }[] {
  const children = new Map<string | null, VaultGroup[]>();
  for (const group of groups) {
    const list = children.get(group.parentId) ?? [];
    list.push(group);
    children.set(group.parentId, list);
  }
  const out: { group: VaultGroup; depth: number }[] = [];
  const walk = (parent: string | null, depth: number) => {
    for (const group of children.get(parent) ?? []) {
      out.push({ group, depth });
      walk(group.id, depth + 1);
    }
  };
  walk(null, 0);
  return out;
}

export function strength(password: string): { level: 0 | 1 | 2 | 3 | 4; bits: number } | null {
  if (password === '') return null;
  let lower = false;
  let upper = false;
  let digit = false;
  let symbol = false;
  let other = false;
  for (const ch of password) {
    const code = ch.codePointAt(0) ?? 0;
    if (ch >= 'a' && ch <= 'z') lower = true;
    else if (ch >= 'A' && ch <= 'Z') upper = true;
    else if (ch >= '0' && ch <= '9') digit = true;
    else if (code < 128) symbol = true;
    else other = true;
  }
  const pool =
    (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0) + (other ? 64 : 0);
  const bits = (Array.from(password).length * Math.log(Math.max(pool, 2))) / Math.LN2;
  const level = bits < 40 ? 0 : bits < 60 ? 1 : bits < 80 ? 2 : bits < 100 ? 3 : 4;
  return { level, bits: Math.round(bits) };
}

export function isExpired(entry: EntrySummary): boolean {
  return entry.expiresAt !== null && entry.expiresAt * 1000 < Date.now();
}

export function errorText(code: string): string {
  const key = `error.${code}` as Parameters<typeof t>[0];
  try {
    const text = t(key);
    return text;
  } catch {
    return t('error.unknown');
  }
}

export function codeOf(error: unknown): string {
  if (error instanceof Error) return error.message;
  return 'unknown';
}

export function without<T>(record: Record<string, T>, key: string): Record<string, T> {
  return Object.fromEntries(Object.entries(record).filter(([name]) => name !== key));
}
