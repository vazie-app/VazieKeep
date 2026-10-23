export type Text = { kind: 'plain'; value: string } | { kind: 'protected' };

export interface VaultInfo {
  name: string;
  description: string;
  generator: string;
  format: string;
  cipher: 'aes256' | 'chacha20';
  kdf: 'aes-kdf' | 'argon2d' | 'argon2id';
  rootGroupId: string;
  recycleBinId: string | null;
  groupCount: number;
  entryCount: number;
  fingerprint: string;
}

export interface VaultGroup {
  id: string;
  parentId: string | null;
  name: string;
  icon: number;
}

export interface PasskeyInfo {
  rpId: string;
  userName: string;
  credentialId: string;
  userHandle: string;
}

export interface CustomFieldInfo {
  name: string;
  protected: boolean;
}

export interface EntrySummary {
  id: string;
  groupId: string;
  title: Text;
  username: Text;
  url: Text;
  tags: string[];
  favorite: boolean;
  expiresAt: number | null;
  icon: number;
  customIcon: string | null;
  passkey: PasskeyInfo | null;
  hasPassword: boolean;
  hasNotes: boolean;
  hasTotp: boolean;
  hasProtectedFields: boolean;
  customFields: CustomFieldInfo[];
  attachmentNames: string[];
}

export interface AttachmentInfo {
  name: string;
  size: number;
}

export interface EntryDetail {
  summary: EntrySummary;
  notes: Text;
  customFields: { name: string; value: Text }[];
  attachments: AttachmentInfo[];
  modifiedAt: number | null;
  historyCount: number;
}

export type ChangedField =
  | 'title'
  | 'userName'
  | 'password'
  | 'url'
  | 'notes'
  | 'customFields'
  | 'tags'
  | 'favorite'
  | 'attachments'
  | 'expiry';

export interface HistoryVersion {
  index: number;
  modifiedAt: number | null;
  title: Text;
  username: Text;
  differs: ChangedField[];
}

export interface CustomFieldDraft {
  name: string;
  value: string;
  protected: boolean;
}

export interface EntryDraft {
  title: string;
  username: string;
  password: string;
  url: string;
  notes: string;
  customFields: CustomFieldDraft[];
  tags: string[];
  favorite: boolean;
  expiresAt: number | null;
}

export interface TotpCode {
  code: string;
  period: number;
  secondsRemaining: number;
}

export interface TotpSetup {
  uri: string;
  algorithm: 'sha1' | 'sha256' | 'sha512';
  digits: number;
  period: number;
  steam: boolean;
  issuer: string;
  account: string;
}

export type ImportSourceKind =
  'csv' | 'onePasswordCsv' | 'onePassword1pux' | 'bitwardenJson' | 'bitwardenCsv';

/** What a merge did: counts and ids only, never a value from the vault. */
export interface MergeReport {
  /** Whether the open database is different now (and so dirty). */
  changed: boolean;
  entriesAdded: number;
  /** Entries changed after a deletion here, which therefore came back. */
  entriesRestored: number;
  /** Entries whose current version is now the other side's. */
  entriesUpdated: number;
  entriesMoved: number;
  entriesDeleted: number;
  /** Entries whose current version stayed but whose history changed. */
  historiesMerged: number;
  historyVersionsAdded: number;
  groupsAdded: number;
  groupsUpdated: number;
  groupsMoved: number;
  groupsDeleted: number;
  /** The entry ids of the conflict copies made, one per entry changed on both sides. */
  conflicts: string[];
  iconsAdded: number;
  deletionRecordsAdded: number;
  settingsUpdated: boolean;
}

export interface MergeOptions {
  /** Appended to the title of a conflict copy, in the person's language. */
  conflictSuffix?: string;
  /** Merge a database that is not a version of this one: every item is then new. */
  allowUnrelated?: boolean;
}

export interface ImportSummary {
  source: ImportSourceKind;
  entries: number;
  folders: number;
  withTotp: number;
  skipped: number;
}

export interface SecurityPreset {
  memoryKib: number;
  iterations: number;
  parallelism: number;
}

export interface SecurityPresets {
  balanced: SecurityPreset;
  stronger: SecurityPreset;
}

export interface PasswordOptions {
  length: number;
  lowercase: boolean;
  uppercase: boolean;
  digits: boolean;
  symbols: boolean;
  excludeAmbiguous: boolean;
}

export type AccessState =
  'free' | 'plusActive' | 'plusOfflineGrace' | 'plusLifetime' | 'plusExpired';

export interface Access {
  state: AccessState;
  plusFeatures: boolean;
  needsOnlineVerification: boolean;
  leaseValidUntil: number | null;
  observedDeviceTime: number;
}

export interface Lease {
  subject: string;
  tier: 'free' | 'keepPlus';
  lifetime: boolean;
  issuedAt: number;
  leaseExpiresAt: number;
  entitlementExpiresAt: number | null;
  featuresVersion: number;
  revision: number;
  nonce: string;
  keyId: string;
}

export type PlusFeature =
  | 'multiVault'
  | 'totp'
  | 'passwordHealth'
  | 'breachMonitoring'
  | 'historyUi'
  | 'attachmentWrite'
  | 'premiumThemes';

export interface TrustedKey {
  kid: string;
  publicKey: string;
}

export interface CreatedPasskey {
  entryId: string;
  credentialId: Uint8Array;
  publicKey: Uint8Array;
  authenticatorData: Uint8Array;
  attestationObject: Uint8Array;
}

export interface PasskeyAssertion {
  credentialId: Uint8Array;
  userHandle: Uint8Array;
  authenticatorData: Uint8Array;
  signature: Uint8Array;
}

export type DeleteOutcome = 'recycled' | 'deleted';

export const FREE_ACCESS: Access = {
  state: 'free',
  plusFeatures: false,
  needsOnlineVerification: false,
  leaseValidUntil: null,
  observedDeviceTime: 0,
};

export function plain(text: Text): string {
  return text.kind === 'plain' ? text.value : '';
}
