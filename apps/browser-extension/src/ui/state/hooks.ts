import { useEffect, useState } from 'preact/hooks';
import type { Translate } from '../../shared/i18n';
import type { AppState, Snapshot } from '../../shared/model';
import { useAtom } from './atom';
import { appState, snapshot, translate } from './client';

export function useT(): Translate {
  return useAtom(translate);
}

export function useApp(): AppState | null {
  return useAtom(appState);
}

export function useSnapshot(): Snapshot | null {
  return useAtom(snapshot);
}

export function useMedia(query: string): boolean {
  const [matches, setMatches] = useState(() => matchMedia(query).matches);
  useEffect(() => {
    const list = matchMedia(query);
    const update = () => {
      setMatches(list.matches);
    };
    update();
    list.addEventListener('change', update);
    return () => {
      list.removeEventListener('change', update);
    };
  }, [query]);
  return matches;
}

export function useWide(): boolean {
  return useMedia('(min-width: 860px)');
}
