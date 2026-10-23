import { sha256Hex } from './files';

interface Pending {
  digest: string;
  timer: ReturnType<typeof setTimeout> | undefined;
}

let pending: Pending | null = null;

function textarea(): HTMLTextAreaElement {
  const area = document.createElement('textarea');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.append(area);
  return area;
}

function readClipboard(): string | null {
  const area = textarea();
  try {
    area.focus();
    // eslint-disable-next-line @typescript-eslint/no-deprecated -- the only way to read the clipboard from an offscreen document
    if (!document.execCommand('paste')) return null;
    return area.value;
  } catch {
    return null;
  } finally {
    area.remove();
  }
}

function writeClipboard(text: string): void {
  const area = textarea();
  try {
    area.value = text;
    area.select();
    // eslint-disable-next-line @typescript-eslint/no-deprecated -- the only way to write the clipboard from an offscreen document
    document.execCommand('copy');
  } finally {
    area.remove();
  }
}

/** Clears the clipboard, but only if it still holds what Keep put there. */
export async function clearNow(): Promise<void> {
  const current = pending;
  if (!current) return;
  clearTimeout(current.timer);
  pending = null;
  const text = readClipboard();
  if (text !== null && (await sha256Hex(new TextEncoder().encode(text))) === current.digest) {
    writeClipboard(' ');
  }
}

export function scheduleClear(digest: string, seconds: number): void {
  if (pending) clearTimeout(pending.timer);
  pending = {
    digest,
    timer:
      seconds > 0
        ? setTimeout(() => {
            void clearNow();
          }, seconds * 1000)
        : undefined,
  };
}

export function hasPending(): boolean {
  return pending !== null;
}
