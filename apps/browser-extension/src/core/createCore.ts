import type * as wasm from '@keep-core-wasm/keep_core_wasm.js';
import type {
  Access,
  ImportSummary,
  Lease,
  PasswordOptions,
  PlusFeature,
  SecurityPresets,
  TotpSetup,
  TrustedKey,
} from './types.ts';
import { KeepVaultSession, type KeepCoreWasm } from './vaultSession.ts';

export interface OpenOptions {
  keyFile?: Uint8Array;
  material?: Uint8Array;
  retainMaterial?: boolean;
}

export interface KeepCore {
  version(): string;
  openVault(data: Uint8Array, password: Uint8Array, options?: OpenOptions): KeepVaultSession;
  createVault(
    name: string,
    kdf: { memoryKib: number; iterations: number; parallelism: number },
    password: Uint8Array,
    keyFile?: Uint8Array,
  ): { bytes: Uint8Array; fingerprint: string };
  securityPresets(): SecurityPresets;
  benchmarkKdf(kdf: { memoryKib: number; iterations: number; parallelism: number }): number;
  generateKeyFile(): Uint8Array;
  generatePassword(options: PasswordOptions): Uint8Array;
  detectFormat(header: Uint8Array): string;
  parseImport(data: Uint8Array): { planId: number; summary: ImportSummary };
  dropImport(planId: number): void;
  totpNormalize(input: Uint8Array, access: Access): TotpSetup;
  matchHost(entryHost: string, requestHost: string): 'exact' | 'subdomain' | 'none';
  siteHost(url: string): string | undefined;
  iconForUrl(url: string): number | undefined;
  siteIconLinks(host: string, html: string): string[];
  verifyLease(token: string, keys: TrustedKey[]): Lease;
  evaluateAccess(lease: Lease | null, now: number, lastObserved: number): Access;
  canUse(feature: PlusFeature, access: Access): boolean;
  closeAll(): void;
}

export function createKeepCore(module: typeof wasm): KeepCore {
  const raw = module as unknown as KeepCoreWasm;
  return {
    version: () => module.coreVersion(),
    openVault: (data, password, options = {}) =>
      KeepVaultSession.open(raw, data, password, options.keyFile, {
        ...(options.material ? { material: options.material } : {}),
        ...(options.retainMaterial ? { retainMaterial: true } : {}),
      }),
    createVault: (name, kdf, password, keyFile) =>
      module.createVault(
        name,
        kdf.memoryKib,
        kdf.iterations,
        kdf.parallelism,
        password,
        keyFile,
      ) as { bytes: Uint8Array; fingerprint: string },
    securityPresets: () => module.securityPresets() as SecurityPresets,
    benchmarkKdf: (kdf) => module.benchmarkKdf(kdf.memoryKib, kdf.iterations, kdf.parallelism),
    generateKeyFile: () => module.generateKeyFile(),
    generatePassword: (o) =>
      module.generatePassword(
        o.length,
        o.lowercase,
        o.uppercase,
        o.digits,
        o.symbols,
        o.excludeAmbiguous,
      ),
    detectFormat: (header) => module.detectFormat(header),
    parseImport: (data) => module.parseImport(data) as { planId: number; summary: ImportSummary },
    dropImport: (planId) => {
      module.dropImport(planId);
    },
    totpNormalize: (input, access) =>
      module.totpNormalize(input, JSON.stringify(access)) as TotpSetup,
    matchHost: (entryHost, requestHost) =>
      module.matchHost(entryHost, requestHost) as 'exact' | 'subdomain' | 'none',
    siteHost: (url) => module.siteHost(url),
    iconForUrl: (url) => module.iconForUrl(url),
    siteIconLinks: (host, html) => module.siteIconLinks(host, html) as string[],
    verifyLease: (token, keys) => module.verifyKeepLease(token, JSON.stringify(keys)) as Lease,
    evaluateAccess: (lease, now, lastObserved) =>
      module.evaluateKeepAccess(JSON.stringify(lease), now, lastObserved) as Access,
    canUse: (feature, access) => module.canUsePlusFeature(feature, JSON.stringify(access)),
    closeAll: () => {
      module.closeAllVaults();
    },
  };
}
