import type { SVGAttributes } from 'preact';

const PATHS = {
  back: ['M15 5L8 12L15 19'],
  chevronRight: ['M9 5L16 12L9 19'],
  mail: ['M3.5 6.5H20.5V17.5H3.5Z', 'M3.5 7L12 13L20.5 7'],
  bank: [
    'M4 10L12 4.5L20 10Z',
    'M6.5 10V16.5',
    'M10.2 10V16.5',
    'M13.8 10V16.5',
    'M17.5 10V16.5',
    'M4 16.5H20',
    'M3.5 19.5H20.5',
  ],
  card: ['M3.5 6.5H20.5V17.5H3.5Z', 'M3.5 10.5H20.5', 'M7 14.5H10.5'],
  home: ['M4 11L12 4.5L20 11', 'M6 9.5V19.5H18V9.5', 'M10 19.5V14.5H14V19.5'],
  chat: ['M4.5 5.5H19.5V15.5H11L7 19V15.5H4.5Z'],
  phone: ['M8 3.5H16V20.5H8Z', 'M11 17.5H13'],
  server: ['M4.5 5H19.5V11H4.5Z', 'M4.5 13H19.5V19H4.5Z', 'M7.5 8H8', 'M7.5 16H8'],
  monitor: ['M3.5 5H20.5V16H3.5Z', 'M9 20H15', 'M12 16V20'],
  terminal: ['M3.5 5H20.5V19H3.5Z', 'M7 9.5L10 12L7 14.5', 'M12 15H16'],
  book: ['M5 4.5H17A2 2 0 0 1 19 6.5V19.5H7A2 2 0 0 1 5 17.5Z', 'M5 17.5A2 2 0 0 1 7 15.5H19'],
  note: ['M6 3.5H14.5L18 7V20.5H6Z', 'M14.5 3.5V7H18', 'M9 12H15', 'M9 15.5H15'],
  certificate: ['M12 4A5 5 0 1 1 12 14A5 5 0 1 1 12 4Z', 'M9 13L7.5 20.5L12 18L16.5 20.5L15 13'],
  briefcase: ['M4 8H20V19H4Z', 'M9 8V5.5H15V8', 'M4 13H20'],
  package: ['M4 8L12 4L20 8V16L12 20L4 16Z', 'M4 8L12 12L20 8', 'M12 12V20'],
  camera: ['M4 8H7.5L9 6H15L16.5 8H20V18.5H4Z', 'M12 10.5A3 3 0 1 1 12 16.5A3 3 0 1 1 12 10.5Z'],
  lock: ['M6 11H18V20H6Z', 'M8.5 11V8A3.5 3.5 0 0 1 15.5 8V11'],
  eye: [
    'M2.5 12C2.5 12 6 5.5 12 5.5S21.5 12 21.5 12S18 18.5 12 18.5S2.5 12 2.5 12Z',
    'M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z',
  ],
  eyeOff: [
    'M2.5 12C2.5 12 6 5.5 12 5.5S21.5 12 21.5 12S18 18.5 12 18.5S2.5 12 2.5 12Z',
    'M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z',
    'M4 4L20 20',
  ],
  search: ['M10.5 4A6.5 6.5 0 1 1 10.5 17A6.5 6.5 0 1 1 10.5 4Z', 'M15.5 15.5L20 20'],
  close: ['M6 6L18 18', 'M18 6L6 18'],
  folder: [
    'M3.5 7A1.5 1.5 0 0 1 5 5.5H9.5L11.5 7.5H19A1.5 1.5 0 0 1 20.5 9V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17Z',
  ],
  key: [
    'M7.5 8.5A3.5 3.5 0 1 1 7.5 15.5A3.5 3.5 0 1 1 7.5 8.5Z',
    'M11 12H20.5',
    'M17.5 12V15',
    'M20.5 12V14',
  ],
  cpu: [
    'M7 7H17V17H7Z',
    'M10 10H14V14H10Z',
    'M9 4V7',
    'M15 4V7',
    'M9 17V20',
    'M15 17V20',
    'M4 9H7',
    'M4 15H7',
    'M17 9H20',
    'M17 15H20',
  ],
  copy: ['M9 9H19.5V20H9Z', 'M5 15.5V4.5H15'],
  settings: [
    'M4 6H13',
    'M17 6H20',
    'M15 4A2 2 0 1 1 15 8A2 2 0 1 1 15 4Z',
    'M4 12H7',
    'M11 12H20',
    'M9 10A2 2 0 1 1 9 14A2 2 0 1 1 9 10Z',
    'M4 18H14',
    'M18 18H20',
    'M16 16A2 2 0 1 1 16 20A2 2 0 1 1 16 16Z',
  ],
  biometric: [
    'M5 15V12A7 7 0 0 1 17.6 7.8',
    'M19 11.5V13.5',
    'M8.5 17.5V12A3.5 3.5 0 0 1 15.5 12V14.5A6 6 0 0 1 14 18.8',
    'M12 11.5V15A4.5 4.5 0 0 1 10.6 18.5',
  ],
  add: ['M12 5V19', 'M5 12H19'],
  edit: ['M4 20H8L19 9L15 5L4 16Z', 'M13 7L17 11'],
  more: [
    'M12 5.5A0.6 0.6 0 1 1 12 6.7A0.6 0.6 0 1 1 12 5.5Z',
    'M12 11.4A0.6 0.6 0 1 1 12 12.6A0.6 0.6 0 1 1 12 11.4Z',
    'M12 17.3A0.6 0.6 0 1 1 12 18.5A0.6 0.6 0 1 1 12 17.3Z',
  ],
  trash: ['M4.5 7H19.5', 'M9 7V4.5H15V7', 'M6.5 7L7.5 20H16.5L17.5 7', 'M10 11V16', 'M14 11V16'],
  dice: [
    'M5 5H19V19H5Z',
    'M8.5 8.5A0.5 0.5 0 1 1 8.5 9.5A0.5 0.5 0 1 1 8.5 8.5Z',
    'M15.5 8.5A0.5 0.5 0 1 1 15.5 9.5A0.5 0.5 0 1 1 15.5 8.5Z',
    'M12 11.5A0.5 0.5 0 1 1 12 12.5A0.5 0.5 0 1 1 12 11.5Z',
    'M8.5 14.5A0.5 0.5 0 1 1 8.5 15.5A0.5 0.5 0 1 1 8.5 14.5Z',
    'M15.5 14.5A0.5 0.5 0 1 1 15.5 15.5A0.5 0.5 0 1 1 15.5 14.5Z',
  ],
  folderAdd: [
    'M3.5 7A1.5 1.5 0 0 1 5 5.5H9.5L11.5 7.5H19A1.5 1.5 0 0 1 20.5 9V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17Z',
    'M12 10.5V15.5',
    'M9.5 13H14.5',
  ],
  warning: [
    'M12 4L21 19.5H3Z',
    'M12 10V14',
    'M12 16.8A0.4 0.4 0 1 1 12 17.6A0.4 0.4 0 1 1 12 16.8Z',
  ],
  check: ['M5 12.5L10 17.5L19 7'],
  info: [
    'M12 3.5A8.5 8.5 0 1 1 12 20.5A8.5 8.5 0 1 1 12 3.5Z',
    'M12 11V16',
    'M12 7.6A0.4 0.4 0 1 1 12 8.4A0.4 0.4 0 1 1 12 7.6Z',
  ],
  star: ['M12 3.8L14.4 9L20 9.7L15.9 13.6L17 19.2L12 16.4L7 19.2L8.1 13.6L4 9.7L9.6 9Z'],
  clock: ['M12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4Z', 'M12 8V12L15 14'],
  archive: ['M4 5.5H20V9H4Z', 'M5.5 9V19H18.5V9', 'M9.5 13H14.5'],
  swap: ['M5 8H18', 'M15 5L18 8L15 11', 'M19 16H6', 'M9 13L6 16L9 19'],
  offline: [
    'M2.5 9.9A13.5 13.5 0 0 1 21.5 9.9',
    'M5.3 12.8A9.5 9.5 0 0 1 18.7 12.8',
    'M8.1 15.6A5.5 5.5 0 0 1 15.9 15.6',
    'M12 19.4L12 19.5',
    'M4 3.5L20 20.5',
  ],
  user: ['M12 4A4 4 0 1 1 12 12A4 4 0 1 1 12 4Z', 'M4.5 20A7.5 7.5 0 0 1 19.5 20'],
  external: ['M13 5H19V11', 'M19 5L11 13', 'M17 14V19H5V7H10'],
  minus: ['M5 12H19'],
  globe: [
    'M12 3.5A8.5 8.5 0 1 1 12 20.5A8.5 8.5 0 1 1 12 3.5Z',
    'M3.5 12H20.5',
    'M12 3.5C9 7 9 17 12 20.5',
    'M12 3.5C15 7 15 17 12 20.5',
  ],
  tag: ['M4 4H12L20 12L12 20L4 12Z', 'M8 8A0.5 0.5 0 1 1 8 9A0.5 0.5 0 1 1 8 8Z'],
  paperclip: ['M16.5 9.5L10 16A2.5 2.5 0 0 1 6.5 12.5L13.5 5.5A4 4 0 0 1 19 11L12 18'],
  calendar: ['M4.5 6H19.5V19.5H4.5Z', 'M4.5 10H19.5', 'M8 3.5V7', 'M16 3.5V7'],
  download: ['M12 4V15', 'M7.5 10.5L12 15L16.5 10.5', 'M5 19H19'],
  unlock: ['M6 11H18V20H6Z', 'M8.5 11V8A3.5 3.5 0 0 1 15 6.2'],
  arrowForward: ['M5 12H19', 'M13 6L19 12L13 18'],
  shield: ['M12 3.5L19 6V12C19 16 16 19 12 20.5C8 19 5 16 5 12V6Z', 'M9 12L11 14L15 10'],
  history: ['M4.5 12A7.5 7.5 0 1 0 7 6.5', 'M4 4V8H8', 'M12 8V12L14.5 13.5'],
  palette: [
    'M12 4A8 8 0 1 0 12 20C13.5 20 14 19 13.4 18C12.7 16.8 13.5 15.5 15 15.5H17A3 3 0 0 0 20 12.5A8 8 0 0 0 12 4Z',
  ],
  file: ['M6 3.5H14L18.5 8V20.5H6Z', 'M14 3.5V8H18.5'],
  sidebar: ['M4 5H20V19H4Z', 'M15 5V19'],
  expand: ['M13 5H19V11', 'M19 5L11 13', 'M11 19H5V13', 'M5 19L13 11'],
  upload: ['M12 15V4', 'M7.5 8.5L12 4L16.5 8.5', 'M5 19H19'],
  passkey: [
    'M9 4A4 4 0 1 1 9 12A4 4 0 1 1 9 4Z',
    'M13.5 11.5L20 18',
    'M17 15L19 13',
    'M5 20A6 6 0 0 1 9 14',
  ],
  refresh: ['M19 12A7 7 0 1 1 16.5 6.6', 'M19.5 4V8.5H15'],
} as const;

export type IconName = keyof typeof PATHS;

interface IconProps extends Omit<SVGAttributes<SVGSVGElement>, 'size'> {
  name: IconName;
  size?: number;
  filled?: boolean;
}

export function Icon({ name, size = 20, filled = false, ...rest }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill={filled ? 'currentColor' : 'none'}
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {PATHS[name].map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  );
}

/** The 27 icons a group or an entry can have, each standing for one standard KeePass icon number. */
export const ITEM_ICONS: readonly { id: number; icon: IconName; key: string; keywords: string }[] =
  [
    { id: 48, icon: 'folder', key: 'icon.folder', keywords: 'folder group directory' },
    { id: 0, icon: 'key', key: 'icon.key', keywords: 'key password login' },
    { id: 1, icon: 'globe', key: 'icon.web', keywords: 'web site internet globe world browser' },
    { id: 19, icon: 'mail', key: 'icon.mail', keywords: 'mail email letter inbox' },
    { id: 37, icon: 'bank', key: 'icon.bank', keywords: 'bank finance account' },
    { id: 66, icon: 'card', key: 'icon.money', keywords: 'money card payment pay wallet' },
    { id: 60, icon: 'home', key: 'icon.home', keywords: 'home house family' },
    { id: 61, icon: 'star', key: 'icon.star', keywords: 'star favorite favourite important' },
    { id: 9, icon: 'user', key: 'icon.person', keywords: 'person identity id profile user' },
    { id: 5, icon: 'chat', key: 'icon.chat', keywords: 'chat social message messenger forum' },
    { id: 68, icon: 'phone', key: 'icon.phone', keywords: 'phone mobile sim app' },
    { id: 3, icon: 'server', key: 'icon.server', keywords: 'server hosting network cloud' },
    { id: 18, icon: 'monitor', key: 'icon.computer', keywords: 'computer desktop monitor pc mac' },
    {
      id: 30,
      icon: 'terminal',
      key: 'icon.terminal',
      keywords: 'terminal console ssh shell code developer',
    },
    { id: 34, icon: 'settings', key: 'icon.settings', keywords: 'settings config admin router' },
    { id: 36, icon: 'archive', key: 'icon.archive', keywords: 'archive storage disk backup old' },
    { id: 39, icon: 'clock', key: 'icon.clock', keywords: 'clock time temporary' },
    { id: 56, icon: 'book', key: 'icon.book', keywords: 'book study school library education' },
    { id: 7, icon: 'note', key: 'icon.note', keywords: 'note document text paper' },
    {
      id: 67,
      icon: 'certificate',
      key: 'icon.certificate',
      keywords: 'certificate license document official government',
    },
    { id: 59, icon: 'briefcase', key: 'icon.work', keywords: 'work job office business tool' },
    {
      id: 47,
      icon: 'package',
      key: 'icon.shopping',
      keywords: 'shopping shop store package delivery order',
    },
    { id: 11, icon: 'camera', key: 'icon.camera', keywords: 'camera photo video media' },
    { id: 52, icon: 'lock', key: 'icon.lock', keywords: 'lock secure secret safe private' },
    { id: 2, icon: 'warning', key: 'icon.warning', keywords: 'warning important attention' },
    { id: 46, icon: 'info', key: 'icon.info', keywords: 'info information help' },
    { id: 43, icon: 'trash', key: 'icon.trash', keywords: 'trash bin deleted' },
  ] as const;

const NEAREST: Record<number, number> = {
  4: 48,
  49: 48,
  50: 48,
  13: 0,
  58: 9,
  8: 1,
  16: 1,
  35: 1,
  25: 19,
  40: 19,
  12: 5,
  15: 18,
  23: 18,
  31: 18,
  32: 18,
  42: 18,
  62: 18,
  64: 18,
  29: 30,
  33: 30,
  20: 34,
  17: 36,
  26: 36,
  27: 36,
  38: 36,
  45: 39,
  65: 56,
  10: 7,
  21: 7,
  22: 7,
  28: 7,
  41: 7,
  44: 7,
  54: 7,
  57: 7,
  63: 7,
  53: 67,
  6: 59,
  14: 59,
  55: 11,
  51: 52,
  24: 2,
};

export function itemIconOf(id: number, fallback: number): (typeof ITEM_ICONS)[number] {
  const exact = ITEM_ICONS.find((i) => i.id === id);
  if (exact) return exact;
  const near = NEAREST[id];
  const nearest = ITEM_ICONS.find((i) => i.id === near);
  return nearest ?? (ITEM_ICONS.find((i) => i.id === fallback) as (typeof ITEM_ICONS)[number]);
}

export const DEFAULT_GROUP_ICON = 48;
export const DEFAULT_ENTRY_ICON = 0;
