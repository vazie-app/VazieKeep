import type { KeepCore } from '../core/keepCore';
import type {
  Access,
  EntryDetail,
  EntryDraft,
  HistoryVersion,
  PasskeyAssertion,
  TotpCode,
  VaultGroup,
  VaultInfo,
  CreatedPasskey,
} from '../core/types';
import type { KeepVaultSession } from '../core/vaultSession';
import type { CreateVaultRequest, Edited, SecurityLevel, UnlockRequest } from '../shared/api';
import { HostError } from '../shared/rpc';
import type {
  CommitResult,
  SaveProblem,
  SessionState,
  Snapshot,
  VaultListItem,
  VaultRulesState,
} from '../shared/model';
import type { AccountService } from './account';
import { buildIndex, type IndexedEntry } from './autofillIndex';
import {
  FileProblem,
  canWrite,
  createVaultFile,
  forgetCopies,
  readFile,
  restorableCopy,
  restoreVaultFile,
  sha256Hex,
  writeVaultFile,
} from './files';
import { planOf, vaultRules, type Rules } from './rules';
import { openWithPrf, sealWithPrf } from './secure';
import {
  kvGet,
  kvSet,
  randomId,
  vaultDelete,
  vaultPut,
  vaultsAll,
  type VaultRecord,
} from './storage';

interface ActiveVault {
  record: VaultRecord;
  session: KeepVaultSession;
  info: VaultInfo;
  fileHash: string | null;
  fileWritable: boolean;
  keyFile: Uint8Array | undefined;
  dataVersion: number;
  problem: SaveProblem | null;
  problemDetail: string | null;
  saving: boolean;
  index: IndexedEntry[];
  groups: VaultGroup[];
}

function readFailure(error: unknown): HostError {
  if (error instanceof FileProblem) {
    return new HostError(error.problem === 'permission' ? 'permission' : 'file_unavailable');
  }
  return new HostError('file_unavailable');
}

function problemOf(error: unknown): SaveProblem {
  if (error instanceof FileProblem && error.problem !== 'unreadable') return error.problem;
  if (error instanceof Error) {
    switch (error.message) {
      // The core's own check of the bytes it prepared failed, before anything was written to the file.
      case 'verification_failed':
        return 'encryptionFailed';
      case 'read_only':
        return 'readOnly';
      case 'encryption_failed':
      case 'random_unavailable':
        return 'encryptionFailed';
    }
  }
  return 'unavailable';
}

function detailOf(error: unknown): string | null {
  if (error instanceof FileProblem) return error.detail ?? null;
  const detail = (error as { detail?: unknown } | null)?.detail;
  return typeof detail === 'string' ? detail : null;
}

export class VaultManager {
  readonly #core: KeepCore;
  readonly #account: AccountService;
  readonly #changed: () => void;
  readonly #records = new Map<string, VaultRecord>();
  #main: string | null = null;
  #selected: string | null = null;
  #active: ActiveVault | null = null;
  #saveChain: Promise<unknown> = Promise.resolve();
  onLock: () => void = () => undefined;
  onUnlock: () => void = () => undefined;
  onDataChanged: () => void = () => undefined;

  constructor(core: KeepCore, account: AccountService, changed: () => void) {
    this.#core = core;
    this.#account = account;
    this.#changed = changed;
  }

  async load(): Promise<void> {
    this.#records.clear();
    for (const record of await vaultsAll()) this.#records.set(record.id, record);
    this.#main = (await kvGet<string>('mainVault')) ?? null;
    this.#selected = (await kvGet<string>('selectedVault')) ?? null;
  }

  selectedId(): string | null {
    if (this.#selected && this.#records.has(this.#selected)) return this.#selected;
    const newest = [...this.#records.values()].sort((a, b) => b.lastUsedAt - a.lastUsedAt)[0];
    return newest?.id ?? null;
  }

  get active(): ActiveVault | null {
    return this.#active;
  }

  get isUnlocked(): boolean {
    return this.#active !== null;
  }

  get access(): Access {
    return this.#account.access;
  }

  rules(): Rules {
    const fingerprints = new Map<string, string>();
    for (const record of this.#records.values()) {
      if (record.fingerprint) fingerprints.set(record.id, record.fingerprint);
    }
    return vaultRules(
      planOf(this.#account.access),
      [...this.#records.keys()],
      this.#main,
      fingerprints,
    );
  }

  rulesState(): VaultRulesState {
    const rules = this.rules();
    return { canAdd: rules.canAdd, mustChooseMain: rules.mustChooseMain, mainId: rules.mainId };
  }

  #canEdit(id: string): boolean {
    const rules = this.rules();
    return rules.editable === null || rules.editable.has(id);
  }

  list(): VaultListItem[] {
    const rules = this.rules();
    return [...this.#records.values()]
      .sort((a, b) => b.lastUsedAt - a.lastUsedAt || a.name.localeCompare(b.name))
      .map((record) => ({
        id: record.id,
        name: record.name,
        fileName: record.fileName,
        fingerprint: record.fingerprint,
        lastUsedAt: record.lastUsedAt,
        hasKeyFile: record.keyFileHandle !== null,
        keyFileName: record.keyFileName,
        quickUnlock: record.quickUnlock !== null,
        main: rules.mainId === record.id,
        editable: rules.editable === null || rules.editable.has(record.id),
        active: this.selectedId() === record.id,
        unlocked: this.#active?.record.id === record.id,
      }));
  }

  sessionState(): SessionState | null {
    const active = this.#active;
    if (!active) return null;
    const planEditable = this.#canEdit(active.record.id);
    const editable = planEditable && active.fileWritable;
    return {
      vaultId: active.record.id,
      name: active.record.name,
      info: active.info,
      editable,
      readOnlyReason: editable ? null : planEditable ? 'file' : 'plan',
      dirty: active.session.dirty,
      saving: active.saving,
      problem: active.problem,
      problemDetail: active.problemDetail,
      dataVersion: active.dataVersion,
      hasRetainedMaterial: active.session.unlockMaterial() !== undefined,
    };
  }

  // ---------------------------------------------------------------- registry

  #record(id: string): VaultRecord {
    const record = this.#records.get(id);
    if (!record) throw new HostError('vault_not_found');
    return record;
  }

  async addVault(handle: FileSystemFileHandle): Promise<{ id: string; created: boolean }> {
    for (const record of this.#records.values()) {
      if (await record.handle.isSameEntry(handle)) return { id: record.id, created: false };
    }
    if (!this.rules().canAdd) throw new HostError('not_entitled');
    const header = (
      await readFile(handle).catch((error: unknown) => {
        throw readFailure(error);
      })
    ).subarray(0, 64);
    if (!this.#core.detectFormat(header).startsWith('kdbx')) {
      throw new HostError('not_a_vault');
    }
    const record = this.#newRecord(handle, null);
    await vaultPut(record);
    this.#records.set(record.id, record);
    this.#changed();
    return { id: record.id, created: true };
  }

  #newRecord(handle: FileSystemFileHandle, fingerprint: string | null): VaultRecord {
    return {
      id: randomId(),
      name: handle.name.replace(/\.kdbx$/i, ''),
      fileName: handle.name,
      handle,
      fingerprint,
      lastUsedAt: 0,
      keyFileHandle: null,
      keyFileName: null,
      quickUnlock: null,
    };
  }

  async forgetVault(id: string): Promise<void> {
    if (this.#active?.record.id === id) this.lock();
    this.#records.delete(id);
    await vaultDelete(id);
    await forgetCopies(id);
    if (this.#main === id) {
      this.#main = null;
      await kvSet('mainVault', null);
    }
    this.#changed();
  }

  async renameVault(id: string, name: string): Promise<void> {
    const record = this.#record(id);
    const trimmed = name.trim();
    if (trimmed === '' || trimmed.length > 100) throw new HostError('invalid_name');
    record.name = trimmed;
    await vaultPut(record);
    this.#changed();
  }

  async locateVault(id: string, handle: FileSystemFileHandle): Promise<void> {
    const record = this.#record(id);
    const header = (
      await readFile(handle).catch((error: unknown) => {
        throw readFailure(error);
      })
    ).subarray(0, 64);
    if (!this.#core.detectFormat(header).startsWith('kdbx')) throw new HostError('not_a_vault');
    record.handle = handle;
    record.fileName = handle.name;
    await vaultPut(record);
    this.#changed();
  }

  async setKeyFile(id: string, handle: FileSystemFileHandle | null): Promise<void> {
    const record = this.#record(id);
    record.keyFileHandle = handle;
    record.keyFileName = handle?.name ?? null;
    await vaultPut(record);
    this.#changed();
  }

  async chooseMainVault(id: string): Promise<boolean> {
    if (!this.rules().mustChooseMain || !this.#records.has(id)) return false;
    this.#main = id;
    await kvSet('mainVault', id);
    this.#changed();
    return true;
  }

  // ---------------------------------------------------------------- unlocking

  async #keyFileFor(record: VaultRecord, supplied?: Uint8Array): Promise<Uint8Array | undefined> {
    if (supplied) return supplied;
    if (!record.keyFileHandle) return undefined;
    try {
      return await readFile(record.keyFileHandle);
    } catch {
      throw new HostError('key_file_unavailable');
    }
  }

  async unlock(request: UnlockRequest): Promise<void> {
    const record = this.#record(request.vaultId);
    this.lock();
    const bytes = await readFile(record.handle).catch((error: unknown) => {
      throw readFailure(error);
    });
    const keyFile = await this.#keyFileFor(
      record,
      request.keyFile ??
        (request.keyFileHandle
          ? await readFile(request.keyFileHandle).catch(() => {
              throw new HostError('key_file_unavailable');
            })
          : undefined),
    );
    const session = this.#core.openVault(bytes, request.password, {
      ...(keyFile ? { keyFile: keyFile.slice() } : {}),
      retainMaterial: true,
    });
    await this.#adopt(record, session, bytes, keyFile);
    this.#selected = record.id;
    await kvSet('selectedVault', record.id);
    if (request.keyFileHandle && request.rememberKeyFile !== false) {
      record.keyFileHandle = request.keyFileHandle;
      record.keyFileName = request.keyFileHandle.name;
      await vaultPut(record);
    }
    this.#changed();
  }

  async #adopt(
    record: VaultRecord,
    session: KeepVaultSession,
    bytes: Uint8Array,
    keyFile: Uint8Array | undefined,
  ): Promise<void> {
    const info = session.info();
    if (record.fingerprint === null && info.name.trim() !== '') record.name = info.name;
    record.fingerprint = info.fingerprint;
    record.lastUsedAt = Date.now();
    record.fileName = record.handle.name;
    await vaultPut(record);
    const groups = session.groups();
    this.#active = {
      record,
      session,
      info,
      fileHash: await sha256Hex(bytes),
      fileWritable: await canWrite(record.handle),
      keyFile,
      dataVersion: 1,
      problem: null,
      problemDetail: null,
      saving: false,
      index: buildIndex(session.entries(), groups, info.recycleBinId),
      groups,
    };
    this.onUnlock();
  }

  async unlockQuick(vaultId: string, prf: Uint8Array, suppliedKeyFile?: Uint8Array): Promise<void> {
    const record = this.#record(vaultId);
    const quick = record.quickUnlock;
    if (!quick) throw new HostError('quick_unlock_off');
    const material = await openWithPrf(prf, quick.salt, { iv: quick.iv, data: quick.sealed });
    prf.fill(0);
    if (!material) throw new HostError('quick_unlock_failed');
    this.lock();
    const bytes = await readFile(record.handle).catch((error: unknown) => {
      throw readFailure(error);
    });
    const keyFile = await this.#keyFileFor(record, suppliedKeyFile);
    let session: KeepVaultSession;
    try {
      session = this.#core.openVault(bytes, new Uint8Array(), {
        material,
        ...(keyFile ? { keyFile: keyFile.slice() } : {}),
        retainMaterial: true,
      });
    } catch (error) {
      if (error instanceof Error && error.message === 'invalid_credentials') {
        throw new HostError('quick_unlock_stale');
      }
      throw error;
    } finally {
      material.fill(0);
    }
    await this.#adopt(record, session, bytes, keyFile);
    this.#changed();
  }

  async setupQuickUnlock(
    vaultId: string,
    credentialId: Uint8Array,
    salt: Uint8Array,
    prf: Uint8Array,
  ): Promise<void> {
    const record = this.#record(vaultId);
    const active = this.#active;
    if (active?.record.id !== vaultId) throw new HostError('locked');
    const material = active.session.unlockMaterial();
    if (!material) throw new HostError('no_password');
    const sealed = await sealWithPrf(prf, salt, material);
    prf.fill(0);
    material.fill(0);
    record.quickUnlock = {
      credentialId,
      salt,
      iv: sealed.iv,
      sealed: sealed.data,
      createdAt: Date.now(),
    };
    await vaultPut(record);
    this.#changed();
  }

  quickUnlockRecord(vaultId: string): { credentialId: Uint8Array; salt: Uint8Array } | null {
    const quick = this.#record(vaultId).quickUnlock;
    return quick ? { credentialId: quick.credentialId, salt: quick.salt } : null;
  }

  async disableQuickUnlock(vaultId: string): Promise<void> {
    const record = this.#record(vaultId);
    record.quickUnlock = null;
    await vaultPut(record);
    this.#changed();
  }

  lock(): void {
    const active = this.#active;
    if (!active) return;
    this.#active = null;
    active.session.close();
    active.keyFile?.fill(0);
    this.#core.closeAll();
    this.onLock();
    this.#changed();
  }

  async reload(): Promise<void> {
    const active = this.#active;
    if (!active) throw new HostError('locked');
    const material = active.session.unlockMaterial();
    if (!material) throw new HostError('locked');
    const { record, keyFile } = active;
    const bytes = await readFile(record.handle).catch((error: unknown) => {
      throw readFailure(error);
    });
    const next = this.#core.openVault(bytes, new Uint8Array(), {
      material,
      ...(keyFile ? { keyFile: keyFile.slice() } : {}),
      retainMaterial: true,
    });
    active.session.close();
    await this.#adopt(record, next, bytes, keyFile);
    this.#changed();
    this.onDataChanged();
  }

  async hasBackup(vaultId: string): Promise<boolean> {
    return (await restorableCopy(vaultId)) !== null;
  }

  async restoreBackup(vaultId: string): Promise<void> {
    const record = this.#record(vaultId);
    try {
      if (!(await restoreVaultFile(record.handle, vaultId))) throw new HostError('no_backup');
    } catch (error) {
      if (error instanceof FileProblem) throw new HostError('restore_failed');
      throw error;
    }
  }

  async createVault(request: CreateVaultRequest): Promise<{ id: string }> {
    if (!this.rules().canAdd) throw new HostError('not_entitled');
    const kdf = this.#core.securityPresets()[request.level];
    const password = request.password.slice();
    const keyFile = request.keyFile?.slice();
    const created = this.#core.createVault(
      request.name.trim(),
      kdf,
      request.password,
      keyFile ? keyFile.slice() : undefined,
    );
    try {
      await createVaultFile(request.handle, created.bytes);
    } catch {
      throw new HostError('write_failed');
    }
    const record = this.#newRecord(request.handle, created.fingerprint);
    record.name = request.name.trim();
    if (request.keyFileHandle) {
      record.keyFileHandle = request.keyFileHandle;
      record.keyFileName = request.keyFileHandle.name;
    }
    await vaultPut(record);
    this.#records.set(record.id, record);
    this.#changed();
    try {
      await this.unlock({
        vaultId: record.id,
        password,
        ...(keyFile ? { keyFile } : {}),
        rememberKeyFile: false,
      });
    } catch {
      throw new HostError('create_unlock_failed');
    }
    return { id: record.id };
  }

  async activateVault(id: string): Promise<void> {
    this.#record(id);
    this.#selected = id;
    await kvSet('selectedVault', id);
    this.#changed();
  }

  // ---------------------------------------------------------------- reading

  #need(): ActiveVault {
    if (!this.#active) throw new HostError('locked');
    return this.#active;
  }

  snapshot(): Snapshot {
    const active = this.#need();
    return {
      info: active.info,
      groups: active.groups,
      entries: active.session.entries(),
      dataVersion: active.dataVersion,
    };
  }

  entry(id: string): EntryDetail {
    return this.#need().session.entry(id);
  }

  reveal(id: string, key: string): string {
    return this.#need().session.revealText(id, key);
  }

  totp(id: string): TotpCode {
    return this.#need().session.totp(id, Math.floor(Date.now() / 1000), this.access);
  }

  history(id: string): HistoryVersion[] {
    return this.#need().session.history(id, this.access);
  }

  historyEntry(id: string, index: number): EntryDetail {
    return this.#need().session.historyEntry(id, index, this.access);
  }

  revealHistory(id: string, index: number, key: string): string {
    return this.#need().session.revealHistory(id, index, key, this.access);
  }

  attachment(id: string, name: string): Uint8Array {
    return this.#need().session.attachment(id, name);
  }

  customIcon(iconId: string): Uint8Array | null {
    return this.#need().session.customIcon(iconId) ?? null;
  }

  index(): IndexedEntry[] {
    return this.#active?.index ?? [];
  }

  withSession<T>(body: (session: KeepVaultSession, active: ActiveVault) => T): T {
    const active = this.#need();
    return body(active.session, active);
  }

  // ---------------------------------------------------------------- editing

  async edit<T>(operation: (session: KeepVaultSession) => T): Promise<Edited<T>> {
    const active = this.#need();
    if (!this.#canEdit(active.record.id) || !active.fileWritable) {
      throw new HostError('read_only');
    }
    const value = operation(active.session);
    active.dataVersion += 1;
    active.groups = active.session.groups();
    active.info = active.session.info();
    active.index = buildIndex(active.session.entries(), active.groups, active.info.recycleBinId);
    this.onDataChanged();
    const commit = await this.save();
    return { value, ...commit };
  }

  async createEntry(groupId: string, draft: EntryDraft): Promise<Edited<string>> {
    return await this.edit((s) => s.createEntry(groupId, draft));
  }

  async createPasskey(
    groupId: string,
    rpId: string,
    rpName: string,
    userName: string,
    userHandle: Uint8Array,
  ): Promise<Edited<CreatedPasskey>> {
    return await this.edit((s) => s.createPasskey(groupId, rpId, rpName, userName, userHandle));
  }

  signPasskey(entryId: string, rpId: string, clientDataHash: Uint8Array): PasskeyAssertion {
    return this.#need().session.signPasskey(entryId, rpId, clientDataHash);
  }

  // ---------------------------------------------------------------- saving

  save(force = false): Promise<CommitResult> {
    const next = this.#saveChain.then(() => this.#write(force));
    this.#saveChain = next.catch(() => undefined);
    return next;
  }

  async #write(force: boolean): Promise<CommitResult> {
    const active = this.#active;
    if (!active) return { saved: false, problem: null };
    if (!active.session.dirty) return { saved: true, problem: null };
    active.saving = true;
    this.#changed();
    try {
      const snapshot = active.session.prepareSave();
      active.fileHash = await writeVaultFile(
        active.record.handle,
        active.record.id,
        snapshot.bytes,
        active.fileHash,
        force,
      );
      active.session.markSaved(snapshot.generation);
      active.problem = null;
      active.problemDetail = null;
      return { saved: true, problem: null };
    } catch (error) {
      const problem = problemOf(error);
      active.problem = problem;
      active.problemDetail = detailOf(error);
      if (problem === 'permission') active.fileWritable = false;
      return { saved: false, problem };
    } finally {
      active.saving = false;
      this.#changed();
    }
  }

  async allowEditing(): Promise<void> {
    const active = this.#need();
    active.fileWritable = await canWrite(active.record.handle);
    this.#changed();
    if (active.fileWritable && active.session.dirty) await this.save();
  }

  exportCopy(): Uint8Array {
    return this.#need().session.prepareSave().bytes;
  }

  levelSettings(level: SecurityLevel): {
    memoryKib: number;
    iterations: number;
    parallelism: number;
  } {
    return this.#core.securityPresets()[level];
  }
}
