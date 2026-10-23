import { vaultGet } from '../host/storage';
import { surface } from './state/client';

const KDBX = [
  { description: 'KeePass database', accept: { 'application/octet-stream': ['.kdbx'] } },
];

function aborted(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

export async function pickVaultFile(): Promise<FileSystemFileHandle | null> {
  try {
    const [handle] = await showOpenFilePicker({ types: KDBX, id: 'keep-vault' });
    return handle ?? null;
  } catch (error) {
    if (aborted(error)) return null;
    throw error;
  }
}

export async function pickKeyFile(): Promise<FileSystemFileHandle | null> {
  try {
    const [handle] = await showOpenFilePicker({ id: 'keep-keyfile' });
    return handle ?? null;
  } catch (error) {
    if (aborted(error)) return null;
    throw error;
  }
}

export async function pickAnyFile(): Promise<File | null> {
  try {
    const [handle] = await showOpenFilePicker({ id: 'keep-import' });
    return (await handle?.getFile()) ?? null;
  } catch (error) {
    if (aborted(error)) return null;
    throw error;
  }
}

export async function saveVaultAs(suggestedName: string): Promise<FileSystemFileHandle | null> {
  try {
    return await showSaveFilePicker({ suggestedName, types: KDBX, id: 'keep-vault' });
  } catch (error) {
    if (aborted(error)) return null;
    throw error;
  }
}

export async function saveKeyFileAs(suggestedName: string): Promise<FileSystemFileHandle | null> {
  try {
    return await showSaveFilePicker({
      suggestedName,
      types: [
        { description: 'KeePass key file', accept: { 'application/octet-stream': ['.keyx'] } },
      ],
      id: 'keep-keyfile',
    });
  } catch (error) {
    if (aborted(error)) return null;
    throw error;
  }
}

export async function saveAs(suggestedName: string): Promise<FileSystemFileHandle | null> {
  try {
    return await showSaveFilePicker({ suggestedName, id: 'keep-save' });
  } catch (error) {
    if (aborted(error)) return null;
    throw error;
  }
}

export async function writeHandle(handle: FileSystemFileHandle, bytes: Uint8Array): Promise<void> {
  const writable = await handle.createWritable();
  await writable.write(bytes as BufferSource);
  await writable.close();
}

/** Asks for the file access the browser forgets between sessions. Must run inside a click. */
export async function ensureAccess(vaultId: string): Promise<{ read: boolean; write: boolean }> {
  const record = await vaultGet(vaultId);
  if (!record) return { read: false, write: false };
  const ask = async (handle: FileSystemFileHandle, mode: 'read' | 'readwrite') => {
    try {
      return (await handle.requestPermission({ mode })) === 'granted';
    } catch {
      return false;
    }
  };
  const write = await ask(record.handle, 'readwrite');
  const read = write || (await ask(record.handle, 'read'));
  if (record.keyFileHandle) await ask(record.keyFileHandle, 'read');
  return { read, write };
}

export async function requestWrite(vaultId: string): Promise<boolean> {
  const record = await vaultGet(vaultId);
  if (!record) return false;
  try {
    return (await record.handle.requestPermission({ mode: 'readwrite' })) === 'granted';
  } catch {
    return false;
  }
}

export function isPopup(): boolean {
  return surface() === 'popup';
}

export function openInTab(hash = ''): void {
  void chrome.tabs.create({ url: `${chrome.runtime.getURL('ui/tab.html')}${hash}` });
  if (isPopup()) window.close();
}

export async function openSidePanel(): Promise<void> {
  try {
    const window_ = await chrome.windows.getCurrent();
    if (window_.id !== undefined) await chrome.sidePanel.open({ windowId: window_.id });
    window.close();
  } catch {
    openInTab();
  }
}
