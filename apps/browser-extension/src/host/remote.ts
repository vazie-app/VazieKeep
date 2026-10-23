import type { AppLink, FaqItem } from '../shared/model';
import { ACCOUNT_ORIGIN } from '../shared/origins';
import { parseFaq, parseLinks } from './remoteParse';
import { kvGet, kvSet } from './storage';

const TIMEOUT_MS = 8000;
const MIN_INTERVAL_MS = 10 * 60 * 1000;

async function getJson(path: string): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, TIMEOUT_MS);
  try {
    const response = await fetch(`${ACCOUNT_ORIGIN}${path}`, {
      headers: { Accept: 'application/json' },
      redirect: 'manual',
      credentials: 'omit',
      signal: controller.signal,
    });
    return response.status === 200 ? ((await response.json()) as unknown) : null;
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function cached<T>(
  name: string,
  path: string,
  parse: (raw: unknown) => T | null,
  force: boolean,
): Promise<T | null> {
  const stored = await kvGet<{ at: number; value: T }>(name);
  if (stored && !force && Date.now() - stored.at < MIN_INTERVAL_MS) return stored.value;
  const fetched = parse(await getJson(path));
  if (fetched !== null) {
    await kvSet(name, { at: Date.now(), value: fetched });
    return fetched;
  }
  return stored?.value ?? null;
}

/** Only what the backend published, kept so a cold start offline shows it too. The extension ships no questions. */
export function loadFaq(force = false): Promise<FaqItem[] | null> {
  return cached('faq', '/v1/faq/keep', parseFaq, force);
}

export function loadLinks(force = false): Promise<AppLink[] | null> {
  return cached('links', '/v1/apps/keep/links', parseLinks, force);
}
