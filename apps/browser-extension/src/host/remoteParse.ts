import type { AppLink, FaqItem, FaqText, LinkText } from '../shared/model.ts';

const SECTIONS = new Set(['AUTHOR', 'PROJECT', 'SECURITY', 'HELP', 'SUPPORT', 'FEEDBACK']);
const SITE_PATH = /^\/[A-Za-z0-9._~/-]*(#[A-Za-z0-9_-]+)?$/;

/** Control characters (a new line only where `allowNewline`), and spaces too when `allowSpace` is false. */
function hasControl(text: string, allowNewline: boolean, allowSpace = true): boolean {
  for (const char of text) {
    const code = char.codePointAt(0) ?? 0;
    if (code === 0x0a && allowNewline) continue;
    if (code < 0x20 || code === 0x7f) return true;
    if (!allowSpace && /\s/.test(char)) return true;
  }
  return false;
}

function usable(text: unknown, max: number, multiline: boolean): text is string {
  if (typeof text !== 'string' || text.trim() === '' || text.length > max) return false;
  return !hasControl(text, multiline);
}

/** Only a web page over https with a host, or a mailto: address: nothing else is ever opened. */
export function isOpenable(url: unknown): url is string {
  if (typeof url !== 'string' || url.length > 512 || hasControl(url, false, false)) return false;
  try {
    const parsed = new URL(url);
    if (parsed.protocol === 'https:') return parsed.hostname !== '';
    if (parsed.protocol === 'mailto:') {
      const address = parsed.pathname;
      return address.includes('@') && !address.startsWith('@');
    }
  } catch {
    return false;
  }
  return false;
}

function isFaqLink(url: unknown): url is string {
  if (typeof url !== 'string' || url.length > 512 || url.startsWith('//')) return false;
  if (url.startsWith('/')) return SITE_PATH.test(url);
  return url.startsWith('https://') && isOpenable(url);
}

function faqText(raw: unknown, max: number, multiline: boolean): FaqText | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const value = raw as { ru?: unknown; en?: unknown };
  if (!usable(value.ru, max, multiline)) return null;
  return usable(value.en, max, multiline) ? { ru: value.ru, en: value.en } : { ru: value.ru };
}

export function parseFaq(raw: unknown): FaqItem[] | null {
  const list = (raw as { items?: unknown } | null)?.items;
  if (!Array.isArray(list)) return null;
  const items: FaqItem[] = [];
  for (const item of list as Record<string, unknown>[]) {
    const key = item.key;
    const question = faqText(item.question, 200, false);
    const answer = faqText(item.answer, 2000, true);
    if (typeof key !== 'string' || key.trim() === '' || !question || !answer) continue;
    const link = item.link as { url?: unknown; label?: unknown } | undefined;
    const label = link ? faqText(link.label, 80, false) : null;
    items.push({
      key,
      question,
      answer,
      ...(link && isFaqLink(link.url) && label ? { link: { url: link.url, label } } : {}),
    });
  }
  return list.length > 0 && items.length === 0 ? null : items;
}

function linkText(raw: unknown): LinkText | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const value = raw as { ru?: unknown; en?: unknown };
  return usable(value.ru, 400, false) && usable(value.en, 400, false)
    ? { ru: value.ru, en: value.en }
    : null;
}

export function parseLinks(raw: unknown): AppLink[] | null {
  const list = (raw as { links?: unknown } | null)?.links;
  if (!Array.isArray(list)) return null;
  const links: AppLink[] = [];
  for (const item of list as Record<string, unknown>[]) {
    const section = item.section;
    const title = linkText(item.title);
    if (
      typeof item.key !== 'string' ||
      item.key.trim() === '' ||
      typeof section !== 'string' ||
      !SECTIONS.has(section) ||
      !isOpenable(item.url) ||
      !title
    ) {
      continue;
    }
    const subtitle = linkText(item.subtitle);
    const action = linkText(item.action);
    links.push({
      key: item.key,
      section: section as AppLink['section'],
      url: item.url,
      title,
      ...(subtitle ? { subtitle } : {}),
      ...(action ? { action } : {}),
    });
  }
  return links.length > 0 ? links : null;
}
