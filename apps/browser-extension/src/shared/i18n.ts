import { DICTIONARY, type StringKey } from './strings.ts';

export type Language = 'en' | 'ru';
export type { StringKey };

export function resolveLanguage(choice: 'system' | 'en' | 'ru', systemLanguage?: string): Language {
  if (choice === 'en' || choice === 'ru') return choice;
  const system =
    systemLanguage ?? (typeof navigator === 'undefined' ? 'en' : navigator.language || 'en');
  return system.toLowerCase().startsWith('ru') ? 'ru' : 'en';
}

export type Params = Record<string, string | number>;

export function translate(language: Language, key: StringKey, params?: Params): string {
  const entry = DICTIONARY[key];
  const text = language === 'ru' ? entry[1] : entry[0];
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}

export type Translate = (key: StringKey, params?: Params) => string;

export function translator(language: Language): Translate {
  return (key, params) => translate(language, key, params);
}

export function formatDate(language: Language, seconds: number): string {
  return new Intl.DateTimeFormat(language === 'ru' ? 'ru-RU' : 'en-GB', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(seconds * 1000));
}

export function formatDateTime(language: Language, seconds: number): string {
  return new Intl.DateTimeFormat(language === 'ru' ? 'ru-RU' : 'en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(seconds * 1000));
}
