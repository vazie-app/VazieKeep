import type { Language } from '../../shared/i18n';
import { useAtom } from './atom';
import { language } from './client';

export function useLanguage(): Language {
  return useAtom(language);
}
