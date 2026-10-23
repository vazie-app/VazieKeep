import type {
  DeleteOutcome,
  EntrySummary,
  EntryDetail,
  EntryDraft,
  HistoryVersion,
  ImportSummary,
  PasswordOptions,
  SecurityPresets,
  TotpCode,
  TotpSetup,
} from '../core/types';
import type {
  AppLink,
  AppState,
  CommitResult,
  FaqItem,
  Prices,
  Settings,
  SiteIconProgress,
  Snapshot,
} from './model';

export interface SiteMatch {
  id: string;
  title: string;
  username: string;
  hasTotp: boolean;
  kind: 'exact' | 'subdomain';
}

export type SecurityLevel = 'balanced' | 'stronger';

export interface UnlockRequest {
  vaultId: string;
  password: Uint8Array;
  keyFile?: Uint8Array;
  keyFileHandle?: FileSystemFileHandle;
  rememberKeyFile?: boolean;
  retainMaterial?: boolean;
}

export interface CreateVaultRequest {
  handle: FileSystemFileHandle;
  name: string;
  level: SecurityLevel;
  password: Uint8Array;
  keyFile?: Uint8Array;
  keyFileHandle?: FileSystemFileHandle;
}

export interface Edited<T = void> extends CommitResult {
  value: T;
}

export interface HostApi {
  ping(): Promise<{ coreVersion: string }>;
  state(): Promise<AppState>;
  touch(): Promise<void>;

  addVault(handle: FileSystemFileHandle): Promise<{ id: string; created: boolean }>;
  createVault(request: CreateVaultRequest): Promise<{ id: string }>;
  forgetVault(id: string): Promise<void>;
  renameVault(id: string, name: string): Promise<void>;
  locateVault(id: string, handle: FileSystemFileHandle): Promise<void>;
  setKeyFile(id: string, handle: FileSystemFileHandle | null): Promise<void>;
  chooseMainVault(id: string): Promise<boolean>;
  activateVault(id: string): Promise<void>;

  unlock(request: UnlockRequest): Promise<void>;
  unlockQuick(vaultId: string, prf: Uint8Array, keyFile?: Uint8Array): Promise<void>;
  setupQuickUnlock(
    vaultId: string,
    credentialId: Uint8Array,
    salt: Uint8Array,
    prf: Uint8Array,
  ): Promise<void>;
  quickUnlockRecord(
    vaultId: string,
  ): Promise<{ credentialId: Uint8Array; salt: Uint8Array } | null>;
  disableQuickUnlock(vaultId: string): Promise<void>;
  lock(): Promise<void>;
  reload(): Promise<void>;
  restoreBackup(vaultId: string): Promise<void>;
  hasBackup(vaultId: string): Promise<boolean>;

  snapshot(): Promise<Snapshot>;
  search(query: string): Promise<EntrySummary[]>;
  siteMatches(url: string): Promise<SiteMatch[]>;
  entry(id: string): Promise<EntryDetail>;
  reveal(id: string, key: string): Promise<string>;
  totp(id: string): Promise<TotpCode>;
  history(id: string): Promise<HistoryVersion[]>;
  historyEntry(id: string, index: number): Promise<EntryDetail>;
  revealHistory(id: string, index: number, key: string): Promise<string>;
  attachment(id: string, name: string): Promise<Uint8Array>;
  customIcon(iconId: string): Promise<Uint8Array | null>;

  createEntry(groupId: string, draft: EntryDraft): Promise<Edited<string>>;
  updateEntry(id: string, draft: EntryDraft): Promise<Edited>;
  deleteEntry(id: string, permanent: boolean): Promise<Edited<DeleteOutcome>>;
  moveEntry(id: string, groupId: string): Promise<Edited>;
  setFavorite(id: string, favorite: boolean): Promise<Edited>;
  createGroup(parentId: string, name: string): Promise<Edited<string>>;
  renameGroup(id: string, name: string): Promise<Edited>;
  deleteGroup(id: string, recursive: boolean, permanent: boolean): Promise<Edited<DeleteOutcome>>;
  setEntryIcon(id: string, icon: number): Promise<Edited>;
  setGroupIcon(id: string, icon: number): Promise<Edited>;
  restoreHistory(id: string, index: number): Promise<Edited>;
  addAttachment(id: string, name: string, data: Uint8Array): Promise<Edited<string>>;
  removeAttachment(id: string, name: string): Promise<Edited>;
  removePasskey(id: string): Promise<Edited>;
  parseImport(data: Uint8Array): Promise<{ planId: number; summary: ImportSummary }>;
  dropImport(planId: number): Promise<void>;
  applyImport(
    planId: number,
    groupId: string,
  ): Promise<Edited<{ entries: number; groups: number }>>;
  assignIconsByUrl(): Promise<Edited<number>>;
  siteIconHosts(): Promise<string[]>;
  fetchSiteIcons(): Promise<Edited<{ found: number; asked: number }>>;

  retrySave(): Promise<CommitResult>;
  overwrite(): Promise<CommitResult>;
  exportCopy(): Promise<Uint8Array>;
  allowEditing(): Promise<void>;

  generatePassword(options: PasswordOptions): Promise<string>;
  securityPresets(): Promise<SecurityPresets>;
  benchmark(level: SecurityLevel): Promise<number>;
  generateKeyFile(): Promise<Uint8Array>;
  totpNormalize(input: string): Promise<TotpSetup>;

  settings(): Promise<Settings>;
  updateSettings(patch: Partial<Settings>): Promise<Settings>;
  neverSave(host: string, never: boolean): Promise<void>;

  requestCode(email: string): Promise<void>;
  signIn(email: string, code: string): Promise<void>;
  refreshAccount(force: boolean): Promise<void>;
  signOut(): Promise<void>;
  deleteAccount(): Promise<boolean>;
  prices(): Promise<Prices | null>;

  faq(): Promise<FaqItem[] | null>;
  links(): Promise<AppLink[] | null>;

  clipboardCopied(digest: string): Promise<void>;
}

export type HostMethod = keyof HostApi;

export interface HostEvents {
  state: AppState;
  siteIconProgress: SiteIconProgress;
}

export type EventName = keyof HostEvents;
