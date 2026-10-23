import { DEFAULT_SETTINGS, ICON_COLORS, type Settings } from './model.ts';

const AUTO_LOCKS: Settings['autoLock'][] = ['onClose', '1', '5', '15', '30', '60', 'browser'];
const CLIPBOARD: Settings['clipboardClear'][] = [0, 15, 30, 60];

const flag = (value: unknown, fallback: boolean): boolean =>
  typeof value === 'boolean' ? value : fallback;

function generator(raw: unknown): Settings['generator'] {
  const base = DEFAULT_SETTINGS.generator;
  const value = (typeof raw === 'object' && raw !== null ? raw : {}) as Partial<
    Settings['generator']
  >;
  const length =
    typeof value.length === 'number' && Number.isInteger(value.length) ? value.length : base.length;
  const options = {
    length: Math.min(128, Math.max(4, length)),
    lowercase: flag(value.lowercase, base.lowercase),
    uppercase: flag(value.uppercase, base.uppercase),
    digits: flag(value.digits, base.digits),
    symbols: flag(value.symbols, base.symbols),
    excludeAmbiguous: flag(value.excludeAmbiguous, base.excludeAmbiguous),
  };
  return options.lowercase || options.uppercase || options.digits || options.symbols
    ? options
    : base;
}

export function sanitizeSettings(raw: Partial<Settings> | undefined): Settings {
  const value = { ...DEFAULT_SETTINGS, ...raw };
  const defaults = DEFAULT_SETTINGS;
  return {
    language: ['system', 'en', 'ru'].includes(value.language) ? value.language : defaults.language,
    theme: ['system', 'light', 'dark'].includes(value.theme) ? value.theme : defaults.theme,
    autoLock: AUTO_LOCKS.includes(value.autoLock) ? value.autoLock : defaults.autoLock,
    lockOnDeviceLock: flag(value.lockOnDeviceLock, defaults.lockOnDeviceLock),
    clipboardClear: CLIPBOARD.includes(value.clipboardClear)
      ? value.clipboardClear
      : defaults.clipboardClear,
    autofill: flag(value.autofill, defaults.autofill),
    autofillSaveOffers: flag(value.autofillSaveOffers, defaults.autofillSaveOffers),
    passkeys: flag(value.passkeys, defaults.passkeys),
    siteIconsAutomatic: flag(value.siteIconsAutomatic, defaults.siteIconsAutomatic),
    toolbarIcon: ICON_COLORS.includes(value.toolbarIcon) ? value.toolbarIcon : defaults.toolbarIcon,
    neverSave: Array.isArray(value.neverSave)
      ? (value.neverSave as unknown[])
          .filter((h): h is string => typeof h === 'string')
          .slice(0, 500)
      : [],
    welcomeSeen: flag(value.welcomeSeen, defaults.welcomeSeen),
    generator: generator(value.generator),
  };
}
