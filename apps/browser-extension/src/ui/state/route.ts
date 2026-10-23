import type { EntryDraft } from '../../core/types';
import { Atom, useAtom } from './atom';

export type SettingsPage =
  | 'home'
  | 'appearance'
  | 'security'
  | 'autofill'
  | 'vaults'
  | 'account'
  | 'plus'
  | 'about'
  | 'faq'
  | 'guides'
  | 'deleteAccount';

export type Page =
  | { name: 'vault' }
  | { name: 'hub' }
  | { name: 'entry'; id: string }
  | { name: 'edit'; id: string | null; groupId: string | null; prefill?: Partial<EntryDraft> }
  | { name: 'history'; id: string }
  | { name: 'version'; id: string; index: number }
  | { name: 'generator' }
  | { name: 'import' }
  | { name: 'create' }
  | { name: 'settings'; page: SettingsPage; guide?: string };

export const stack = new Atom<Page[]>([{ name: 'vault' }]);

export function current(): Page {
  return stack.get().at(-1) ?? { name: 'vault' };
}

export function go(page: Page): void {
  stack.update((pages) => [...pages, page]);
}

export function replace(page: Page): void {
  stack.update((pages) => [...pages.slice(0, -1), page]);
}

export function reset(page: Page = { name: 'vault' }): void {
  stack.set([page]);
}

export function back(): void {
  stack.update((pages) => (pages.length > 1 ? pages.slice(0, -1) : pages));
}

export function useRoute(): Page {
  return useAtom(stack).at(-1) ?? { name: 'vault' };
}

export function useCanGoBack(): boolean {
  return useAtom(stack).length > 1;
}

export interface Browse {
  groupId: string | null;
  query: string;
  filter: 'all' | 'favorites' | 'expired';
}

export const browse = new Atom<Browse>({ groupId: null, query: '', filter: 'all' });
