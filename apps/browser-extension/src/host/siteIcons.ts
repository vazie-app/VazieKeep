import type { KeepCore } from '../core/keepCore';

const PAGE_LIMIT = 512 * 1024;
const ICON_LIMIT = 256 * 1024;
const TIMEOUT_MS = 8000;
const ICON_SIZE = 48;
const CONCURRENCY = 4;

async function fetchLimited(
  url: string,
  limit: number,
): Promise<{ bytes: Uint8Array; type: string } | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      credentials: 'omit',
      redirect: 'follow',
      referrerPolicy: 'no-referrer',
      cache: 'no-store',
      signal: controller.signal,
    });
    if (!response.ok || !response.url.startsWith('https://')) return null;
    const declared = Number(response.headers.get('content-length') ?? '0');
    if (declared > limit) return null;
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length === 0 || bytes.length > limit) return null;
    return { bytes, type: response.headers.get('content-type') ?? '' };
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function toPng(bytes: Uint8Array, type: string): Promise<Uint8Array | null> {
  const mime = type.split(';')[0]?.trim() || 'image/x-icon';
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: mime }));
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    if (image.naturalWidth < 8 || image.naturalHeight < 8) return null;
    const canvas = new OffscreenCanvas(ICON_SIZE, ICON_SIZE);
    const context = canvas.getContext('2d');
    if (!context) return null;
    const scale = Math.min(ICON_SIZE / image.naturalWidth, ICON_SIZE / image.naturalHeight);
    const width = image.naturalWidth * scale;
    const height = image.naturalHeight * scale;
    context.drawImage(image, (ICON_SIZE - width) / 2, (ICON_SIZE - height) / 2, width, height);
    const blob = await canvas.convertToBlob({ type: 'image/png' });
    return new Uint8Array(await blob.arrayBuffer());
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Asks one site for its icon, straight from this browser. */
export async function fetchSiteIcon(core: KeepCore, host: string): Promise<Uint8Array | null> {
  const page = await fetchLimited(`https://${host}/`, PAGE_LIMIT);
  const html = page ? new TextDecoder().decode(page.bytes) : '';
  const candidates = core.siteIconLinks(host, html);
  for (const link of candidates.slice(0, 5)) {
    if (!link.startsWith('https://')) continue;
    const icon = await fetchLimited(link, ICON_LIMIT);
    if (!icon) continue;
    const png = await toPng(icon.bytes, icon.type);
    if (png) return png;
  }
  return null;
}

export async function fetchSiteIcons(
  core: KeepCore,
  hosts: string[],
  progress: (done: number, total: number) => void,
): Promise<{ host: string; png: Uint8Array }[]> {
  const found: { host: string; png: Uint8Array }[] = [];
  let next = 0;
  let done = 0;
  progress(0, hosts.length);
  const worker = async () => {
    while (next < hosts.length) {
      const host = hosts[next++];
      if (host === undefined) break;
      const png = await fetchSiteIcon(core, host);
      if (png) found.push({ host, png });
      done += 1;
      progress(done, hosts.length);
    }
  };
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, hosts.length) }, worker));
  return found;
}
