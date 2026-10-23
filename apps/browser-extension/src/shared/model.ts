import type {
  Access,
  EntrySummary,
  PasswordOptions,
  VaultGroup,
  VaultInfo,
} from '../core/types.ts';

export type ThemeMode = 'system' | 'light' | 'dark';
export type LanguageChoice = 'system' | 'en' | 'ru';
export type AutoLock = 'onClose' | '1' | '5' | '15' | '30' | '60' | 'browser';
export type ClipboardClear = 0 | 15 | 30 | 60;

export const ICON_COLORS = [
  'classic',
  'blue',
  'gray',
  'green',
  'pink',
  'red',
  'violet',
  'yellow',
] as const;
export type IconColor = (typeof ICON_COLORS)[number];

export interface Settings {
  language: LanguageChoice;
  theme: ThemeMode;
  autoLock: AutoLock;
  lockOnDeviceLock: boolean;
  clipboardClear: ClipboardClear;
  autofill: boolean;
  autofillSaveOffers: boolean;
  passkeys: boolean;
  siteIconsAutomatic: boolean;
  toolbarIcon: IconColor;
  neverSave: string[];
  welcomeSeen: boolean;
  generator: PasswordOptions;
}

export const DEFAULT_SETTINGS: Settings = {
  language: 'system',
  theme: 'system',
  autoLock: '15',
  lockOnDeviceLock: true,
  clipboardClear: 30,
  autofill: true,
  autofillSaveOffers: true,
  passkeys: true,
  siteIconsAutomatic: false,
  toolbarIcon: 'classic',
  neverSave: [],
  welcomeSeen: false,
  generator: {
    length: 20,
    lowercase: true,
    uppercase: true,
    digits: true,
    symbols: true,
    excludeAmbiguous: false,
  },
};

export type SaveProblem =
  | 'changedElsewhere'
  | 'unavailable'
  | 'notWritable'
  | 'permission'
  | 'noSpace'
  | 'backupFailed'
  | 'verificationFailed'
  | 'encryptionFailed'
  | 'restoreNeeded'
  | 'readOnly';

export interface VaultListItem {
  id: string;
  name: string;
  fileName: string;
  fingerprint: string | null;
  lastUsedAt: number;
  hasKeyFile: boolean;
  keyFileName: string | null;
  quickUnlock: boolean;
  main: boolean;
  editable: boolean;
  active: boolean;
  unlocked: boolean;
}

export interface SessionState {
  vaultId: string;
  name: string;
  info: VaultInfo;
  editable: boolean;
  readOnlyReason: 'plan' | 'file' | null;
  dirty: boolean;
  saving: boolean;
  problem: SaveProblem | null;
  /** What a failed save saw (sizes, hashes, time waited), to show beside the problem. */
  problemDetail: string | null;
  dataVersion: number;
  hasRetainedMaterial: boolean;
}

export interface VaultRulesState {
  canAdd: boolean;
  mustChooseMain: boolean;
  mainId: string | null;
}

export type EntitlementProblem = 'offline' | 'sessionExpired' | 'clockOff' | 'failed';

export interface AccountState {
  signedIn: boolean;
  email: string | null;
  accountId: string | null;
  access: Access;
  leaseTier: 'free' | 'keepPlus' | null;
  lifetime: boolean;
  entitlementExpiresAt: number | null;
  refreshing: boolean;
  problem: EntitlementProblem | null;
}

export interface AppState {
  version: string;
  coreVersion: string;
  vaults: VaultListItem[];
  activeId: string | null;
  session: SessionState | null;
  rules: VaultRulesState;
  settings: Settings;
  account: AccountState;
  lockAt: number | null;
}

export interface Snapshot {
  info: VaultInfo;
  groups: VaultGroup[];
  entries: EntrySummary[];
  dataVersion: number;
}

export interface CommitResult {
  saved: boolean;
  problem: SaveProblem | null;
}

export interface Prices {
  plans: {
    id: string;
    lifetime: boolean;
    amountKopecks: number;
    regularKopecks: number | null;
    earlyRemaining: number | null;
  }[];
  checkoutAvailable: boolean;
}

export interface FaqText {
  ru: string;
  en?: string;
}

export interface FaqItem {
  key: string;
  question: FaqText;
  answer: FaqText;
  link?: { url: string; label: FaqText };
}

export interface LinkText {
  ru: string;
  en: string;
}

export interface AppLink {
  key: string;
  section: 'AUTHOR' | 'PROJECT' | 'SECURITY' | 'HELP' | 'SUPPORT' | 'FEEDBACK';
  url: string;
  title: LinkText;
  subtitle?: LinkText;
  action?: LinkText;
}

export interface QuickUnlockSetup {
  credentialId: Uint8Array;
  salt: Uint8Array;
}

export interface SiteIconProgress {
  done: number;
  total: number;
}

export type AccountError =
  'invalidCode' | 'invalidEmail' | 'rateLimited' | 'unavailable' | 'sessionRejected' | 'notEnabled';

export interface StartedVault {
  id: string;
}
