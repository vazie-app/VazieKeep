import type {
  Access,
  CreatedPasskey,
  DeleteOutcome,
  EntryDetail,
  EntryDraft,
  EntrySummary,
  HistoryVersion,
  ImportSummary,
  MergeOptions,
  MergeReport,
  PasskeyAssertion,
  TotpCode,
  VaultGroup,
  VaultInfo,
} from './types.ts';

export type { Text, VaultInfo, VaultGroup, EntrySummary, EntryDetail } from './types.ts';

export interface KeepCoreWasm {
  openVault(
    data: Uint8Array,
    password: Uint8Array,
    keyFile?: Uint8Array,
    material?: Uint8Array,
    retainMaterial?: boolean,
  ): string;
  mergeVault(
    handle: string,
    data: Uint8Array,
    password: Uint8Array,
    keyFile: Uint8Array | undefined,
    material: Uint8Array | undefined,
    conflictSuffix: string,
    allowUnrelated: boolean,
  ): unknown;
  vaultInfo(handle: string): unknown;
  listGroups(handle: string): unknown;
  listEntries(handle: string): unknown;
  searchEntries(handle: string, query: string): unknown;
  getEntry(handle: string, entryId: string): unknown;
  isEditable(handle: string): boolean;
  isDirty(handle: string): boolean;
  revealPassword(handle: string, entryId: string): Uint8Array;
  revealField(handle: string, entryId: string, key: string): Uint8Array;
  attachmentBytes(handle: string, entryId: string, name: string): Uint8Array;
  customIcon(handle: string, iconId: string): Uint8Array | undefined;
  unlockMaterial(handle: string): Uint8Array | undefined;
  historyVersions(handle: string, entryId: string, access: string): unknown;
  historyEntry(handle: string, entryId: string, index: number, access: string): unknown;
  revealHistory(
    handle: string,
    entryId: string,
    index: number,
    key: string,
    access: string,
  ): Uint8Array;
  restoreHistory(handle: string, entryId: string, index: number, access: string): void;
  totpCode(handle: string, entryId: string, unixSeconds: number, access: string): unknown;
  createEntry(handle: string, groupId: string, draft: Uint8Array): string;
  updateEntry(handle: string, entryId: string, draft: Uint8Array): void;
  setFavorite(handle: string, entryId: string, favorite: boolean): void;
  deleteEntry(handle: string, entryId: string, permanent: boolean): string;
  moveEntry(handle: string, entryId: string, groupId: string): void;
  createGroup(handle: string, parentId: string, name: string): string;
  renameGroup(handle: string, groupId: string, name: string): void;
  deleteGroup(handle: string, groupId: string, recursive: boolean, permanent: boolean): string;
  setEntryIcon(handle: string, entryId: string, icon: number): void;
  setGroupIcon(handle: string, groupId: string, icon: number): void;
  assignIconsByUrl(handle: string): number;
  siteIconHosts(handle: string): unknown;
  siteIconHost(handle: string, entryId: string): string | undefined;
  setSiteIcons(handle: string, icons: { host: string; png: Uint8Array }[]): number;
  addAttachment(
    handle: string,
    entryId: string,
    name: string,
    data: Uint8Array,
    access: string,
  ): string;
  removeAttachment(handle: string, entryId: string, name: string): void;
  importPlan(handle: string, groupId: string, planId: number): unknown;
  createPasskey(
    handle: string,
    groupId: string,
    rpId: string,
    rpName: string,
    userName: string,
    userHandle: Uint8Array,
  ): unknown;
  listPasskeys(handle: string, rpId: string): unknown;
  signPasskey(handle: string, entryId: string, rpId: string, clientDataHash: Uint8Array): unknown;
  removePasskey(handle: string, entryId: string): void;
  prepareSave(handle: string): unknown;
  /** Why the last save check failed, without any secret; undefined when it passed. */
  lastSaveDiagnostic(): string | undefined;
  markSaved(handle: string, generation: number): boolean;
  closeVault(handle: string): boolean;
}

export class SecretBytes {
  #bytes: Uint8Array | null;

  constructor(bytes: Uint8Array) {
    this.#bytes = bytes;
  }

  useText<R>(block: (text: string) => R): R {
    if (this.#bytes === null) {
      throw new Error('secret_wiped');
    }
    return block(new TextDecoder('utf-8', { fatal: true }).decode(this.#bytes));
  }

  wipe(): void {
    this.#bytes?.fill(0);
    this.#bytes = null;
  }

  toString(): string {
    return 'SecretBytes(<redacted>)';
  }

  toJSON(): string {
    return '[redacted]';
  }
}

function jsonBytes(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

export class KeepVaultSession {
  readonly #core: KeepCoreWasm;
  #handle: string | null;

  private constructor(core: KeepCoreWasm, handle: string) {
    this.#core = core;
    this.#handle = handle;
  }

  static open(
    core: KeepCoreWasm,
    data: Uint8Array,
    password: Uint8Array,
    keyFile?: Uint8Array,
    options: { material?: Uint8Array; retainMaterial?: boolean } = {},
  ): KeepVaultSession {
    return new KeepVaultSession(
      core,
      core.openVault(data, password, keyFile, options.material, options.retainMaterial),
    );
  }

  get isOpen(): boolean {
    return this.#handle !== null;
  }

  info(): VaultInfo {
    return this.#core.vaultInfo(this.#live()) as VaultInfo;
  }

  groups(): VaultGroup[] {
    return this.#core.listGroups(this.#live()) as VaultGroup[];
  }

  entries(): EntrySummary[] {
    return this.#core.listEntries(this.#live()) as EntrySummary[];
  }

  search(query: string): EntrySummary[] {
    return this.#core.searchEntries(this.#live(), query) as EntrySummary[];
  }

  entry(entryId: string): EntryDetail {
    return this.#core.getEntry(this.#live(), entryId) as EntryDetail;
  }

  get editable(): boolean {
    return this.#core.isEditable(this.#live());
  }

  get dirty(): boolean {
    return this.#core.isDirty(this.#live());
  }

  revealPassword(entryId: string): SecretBytes {
    return new SecretBytes(this.#core.revealPassword(this.#live(), entryId));
  }

  revealField(entryId: string, key: string): SecretBytes {
    return new SecretBytes(this.#core.revealField(this.#live(), entryId, key));
  }

  revealText(entryId: string, key: string): string {
    const secret = this.revealField(entryId, key);
    try {
      return secret.useText((text) => text);
    } finally {
      secret.wipe();
    }
  }

  attachment(entryId: string, name: string): Uint8Array {
    return this.#core.attachmentBytes(this.#live(), entryId, name);
  }

  customIcon(iconId: string): Uint8Array | undefined {
    return this.#core.customIcon(this.#live(), iconId);
  }

  unlockMaterial(): Uint8Array | undefined {
    return this.#core.unlockMaterial(this.#live());
  }

  history(entryId: string, access: Access): HistoryVersion[] {
    return this.#core.historyVersions(
      this.#live(),
      entryId,
      JSON.stringify(access),
    ) as HistoryVersion[];
  }

  historyEntry(entryId: string, index: number, access: Access): EntryDetail {
    return this.#core.historyEntry(
      this.#live(),
      entryId,
      index,
      JSON.stringify(access),
    ) as EntryDetail;
  }

  revealHistory(entryId: string, index: number, key: string, access: Access): string {
    const bytes = this.#core.revealHistory(
      this.#live(),
      entryId,
      index,
      key,
      JSON.stringify(access),
    );
    const secret = new SecretBytes(bytes);
    try {
      return secret.useText((text) => text);
    } finally {
      secret.wipe();
    }
  }

  restoreHistory(entryId: string, index: number, access: Access): void {
    this.#core.restoreHistory(this.#live(), entryId, index, JSON.stringify(access));
  }

  totp(entryId: string, unixSeconds: number, access: Access): TotpCode {
    return this.#core.totpCode(
      this.#live(),
      entryId,
      unixSeconds,
      JSON.stringify(access),
    ) as TotpCode;
  }

  createEntry(groupId: string, draft: EntryDraft): string {
    return this.#core.createEntry(this.#live(), groupId, jsonBytes(draft));
  }

  updateEntry(entryId: string, draft: EntryDraft): void {
    this.#core.updateEntry(this.#live(), entryId, jsonBytes(draft));
  }

  setFavorite(entryId: string, favorite: boolean): void {
    this.#core.setFavorite(this.#live(), entryId, favorite);
  }

  deleteEntry(entryId: string, permanent: boolean): DeleteOutcome {
    return this.#core.deleteEntry(this.#live(), entryId, permanent) as DeleteOutcome;
  }

  moveEntry(entryId: string, groupId: string): void {
    this.#core.moveEntry(this.#live(), entryId, groupId);
  }

  createGroup(parentId: string, name: string): string {
    return this.#core.createGroup(this.#live(), parentId, name);
  }

  renameGroup(groupId: string, name: string): void {
    this.#core.renameGroup(this.#live(), groupId, name);
  }

  deleteGroup(groupId: string, recursive: boolean, permanent: boolean): DeleteOutcome {
    return this.#core.deleteGroup(this.#live(), groupId, recursive, permanent) as DeleteOutcome;
  }

  setEntryIcon(entryId: string, icon: number): void {
    this.#core.setEntryIcon(this.#live(), entryId, icon);
  }

  setGroupIcon(groupId: string, icon: number): void {
    this.#core.setGroupIcon(this.#live(), groupId, icon);
  }

  assignIconsByUrl(): number {
    return this.#core.assignIconsByUrl(this.#live());
  }

  siteIconHosts(): string[] {
    return this.#core.siteIconHosts(this.#live()) as string[];
  }

  siteIconHost(entryId: string): string | undefined {
    return this.#core.siteIconHost(this.#live(), entryId);
  }

  setSiteIcons(icons: { host: string; png: Uint8Array }[]): number {
    return this.#core.setSiteIcons(this.#live(), icons);
  }

  addAttachment(entryId: string, name: string, data: Uint8Array, access: Access): string {
    return this.#core.addAttachment(this.#live(), entryId, name, data, JSON.stringify(access));
  }

  removeAttachment(entryId: string, name: string): void {
    this.#core.removeAttachment(this.#live(), entryId, name);
  }

  /**
   * Combines another version of this database into the open one (ADR 0023). The other file is
   * opened with its own password (wiped) and key file, or with the retained `material`; it takes no
   * session. Nothing is written: the session is dirty if anything changed. Throws `different_vault`
   * for a database that is not a version of this one unless `allowUnrelated`.
   */
  merge(
    data: Uint8Array,
    password: Uint8Array,
    options: MergeOptions & { keyFile?: Uint8Array; material?: Uint8Array } = {},
  ): MergeReport {
    return this.#core.mergeVault(
      this.#live(),
      data,
      password,
      options.keyFile,
      options.material,
      options.conflictSuffix ?? ' (conflict)',
      options.allowUnrelated ?? false,
    ) as MergeReport;
  }

  importPlan(groupId: string, planId: number): { entries: number; groups: number } {
    return this.#core.importPlan(this.#live(), groupId, planId) as {
      entries: number;
      groups: number;
    };
  }

  createPasskey(
    groupId: string,
    rpId: string,
    rpName: string,
    userName: string,
    userHandle: Uint8Array,
  ): CreatedPasskey {
    return this.#core.createPasskey(
      this.#live(),
      groupId,
      rpId,
      rpName,
      userName,
      userHandle,
    ) as CreatedPasskey;
  }

  passkeys(rpId: string): EntrySummary[] {
    return this.#core.listPasskeys(this.#live(), rpId) as EntrySummary[];
  }

  signPasskey(entryId: string, rpId: string, clientDataHash: Uint8Array): PasskeyAssertion {
    return this.#core.signPasskey(this.#live(), entryId, rpId, clientDataHash) as PasskeyAssertion;
  }

  removePasskey(entryId: string): void {
    this.#core.removePasskey(this.#live(), entryId);
  }

  prepareSave(): { bytes: Uint8Array; generation: number } {
    try {
      return this.#core.prepareSave(this.#live()) as { bytes: Uint8Array; generation: number };
    } catch (error) {
      // The core's own read-back of what it is about to write failed: say where, so it can be fixed.
      const detail =
        error instanceof Error && error.message === 'verification_failed'
          ? this.#core.lastSaveDiagnostic()
          : undefined;
      if (detail && error instanceof Error) {
        console.warn('vault save check failed:', detail);
        throw Object.assign(error, { detail });
      }
      throw error;
    }
  }

  markSaved(generation: number): boolean {
    return this.#core.markSaved(this.#live(), generation);
  }

  close(): void {
    if (this.#handle !== null) {
      this.#core.closeVault(this.#handle);
      this.#handle = null;
    }
  }

  toString(): string {
    return 'KeepVaultSession(<opaque>)';
  }

  toJSON(): string {
    return '[vault session]';
  }

  #live(): string {
    if (this.#handle === null) {
      throw new Error('session_closed');
    }
    return this.#handle;
  }
}

export type { ImportSummary };
