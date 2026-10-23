import leaseKeysText from '@keep-lease/production-keys.txt?raw';
import type { KeepCore } from '../core/keepCore';
import { FREE_ACCESS, type Access, type Lease, type TrustedKey } from '../core/types';
import type { AccountError, AccountState, EntitlementProblem, Prices } from '../shared/model';
import { ACCOUNT_ORIGIN, KEEP_ORIGIN } from '../shared/origins';
import { parseLeaseKeys } from './leaseKeys';
import { kvDelete, kvGet, kvSet } from './storage';
import { openWithDevice, sealWithDevice, type Sealed } from './secure';

const REFRESH_AFTER_SECONDS = 5 * 60;
const EXPIRING_SOON_SECONDS = 24 * 60 * 60;
const PERSIST_AFTER_SECONDS = 60;
const TIMEOUT_MS = 15_000;

interface StoredAccount {
  accountId: string;
  email: string | null;
  session: Sealed;
  leaseToken: string | null;
  lastObservedDeviceTime: number;
  lastRefreshAt: number;
}

export class AccountFailure extends Error {
  constructor(readonly kind: AccountError) {
    super(kind);
    this.name = 'AccountFailure';
  }
}

async function request(
  method: string,
  url: string,
  options: { body?: unknown; bearer?: string } = {},
): Promise<Record<string, unknown>> {
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, TIMEOUT_MS);
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (options.bearer) headers.Authorization = `Bearer ${options.bearer}`;
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(url, {
      method,
      headers,
      redirect: 'manual',
      credentials: 'omit',
      signal: controller.signal,
      ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
    });
    const text = await response.text();
    if (!response.ok) throw failure(response.status, text);
    if (text.trim() === '') return {};
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {};
  } catch (error) {
    if (error instanceof AccountFailure) throw error;
    throw new AccountFailure('unavailable');
  } finally {
    clearTimeout(timer);
  }
}

function failure(status: number, text: string): AccountFailure {
  let code = '';
  try {
    const parsed = JSON.parse(text) as { error?: { code?: unknown } };
    if (typeof parsed.error?.code === 'string') code = parsed.error.code;
  } catch {
    /* an answer that is not JSON is "unavailable" */
  }
  if (code === 'EMAIL_CODE_INVALID') return new AccountFailure('invalidCode');
  if (code === 'AUTH_INVALID' || code === 'AUTH_REQUIRED' || status === 401) {
    return new AccountFailure('sessionRejected');
  }
  if (code === 'NOT_ENABLED') return new AccountFailure('notEnabled');
  if (code === 'RATE_LIMITED' || status === 429) return new AccountFailure('rateLimited');
  if (code === 'VALIDATION_FAILED') return new AccountFailure('invalidEmail');
  return new AccountFailure('unavailable');
}

function string(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

export async function fetchPrices(): Promise<Prices | null> {
  try {
    const root = await request('GET', `${KEEP_ORIGIN}/v1/prices`);
    const plans = root.plans;
    const checkout = root.checkout as { available?: unknown } | undefined;
    if (!Array.isArray(plans)) return null;
    return {
      plans: plans.map((plan: Record<string, unknown>) => ({
        id: String(plan.id),
        lifetime: plan.kind === 'lifetime',
        amountKopecks: Number(plan.amountKopecks),
        regularKopecks:
          plan.regularAmountKopecks == null ? null : Number(plan.regularAmountKopecks),
        earlyRemaining:
          plan.earlyAccess && typeof plan.earlyAccess === 'object'
            ? Number((plan.earlyAccess as { remaining?: unknown }).remaining)
            : null,
      })),
      checkoutAvailable: checkout?.available === true,
    };
  } catch {
    return null;
  }
}

export class AccountService {
  readonly #core: KeepCore;
  readonly #keys: TrustedKey[];
  readonly #changed: () => void;
  #stored: StoredAccount | null = null;
  #lease: Lease | null = null;
  #access: Access = FREE_ACCESS;
  #refreshing = false;
  #problem: EntitlementProblem | null = null;
  #chain: Promise<unknown> = Promise.resolve();

  constructor(
    core: KeepCore,
    changed: () => void,
    keys = parseLeaseKeys(`${leaseKeysText}\n${__QA_LEASE_KEYS__}`),
  ) {
    this.#core = core;
    this.#changed = changed;
    this.#keys = keys;
  }

  async start(): Promise<void> {
    this.#stored = (await kvGet<StoredAccount>('account')) ?? null;
    await this.#publish();
  }

  get access(): Access {
    return this.#access;
  }

  state(): AccountState {
    const stored = this.#stored;
    return {
      signedIn: stored !== null,
      email: stored?.email ?? null,
      accountId: stored?.accountId ?? null,
      access: this.#access,
      leaseTier: this.#lease?.tier ?? null,
      lifetime: this.#lease?.lifetime ?? false,
      entitlementExpiresAt: this.#lease?.entitlementExpiresAt ?? null,
      refreshing: this.#refreshing,
      problem: this.#problem,
    };
  }

  canUse(feature: Parameters<KeepCore['canUse']>[0]): boolean {
    return this.#core.canUse(feature, this.#access);
  }

  async requestCode(email: string): Promise<void> {
    await request('POST', `${ACCOUNT_ORIGIN}/v1/auth/email/start`, {
      body: { email: email.trim() },
    });
  }

  async signIn(email: string, code: string): Promise<void> {
    const root = await request('POST', `${ACCOUNT_ORIGIN}/v1/auth/email/verify`, {
      body: { email: email.trim(), code: code.trim(), platform: 'WEB' },
    });
    const accountId = string(root.accountId);
    const token = string(root.token);
    if (!accountId || !token) throw new AccountFailure('unavailable');
    await this.#serial(async () => {
      const previous = this.#stored;
      const same = previous !== null && previous.accountId === accountId;
      const account: StoredAccount = {
        accountId,
        email: string(root.email),
        session: await sealWithDevice(token),
        leaseToken: same ? previous.leaseToken : null,
        lastObservedDeviceTime: same ? previous.lastObservedDeviceTime : 0,
        lastRefreshAt: 0,
      };
      await kvSet('account', account);
      this.#stored = account;
      await this.#publish();
      await this.#fetch(account);
    });
  }

  async refresh(force = false): Promise<void> {
    await this.#serial(async () => {
      const account = this.#stored;
      if (!account) return;
      if (!force && !this.#due(account)) return;
      await this.#fetch(account);
    });
  }

  async reevaluate(): Promise<void> {
    if (this.#stored) await this.#publish();
  }

  async signOut(): Promise<void> {
    await this.#serial(async () => {
      const account = this.#stored;
      await this.#forget();
      if (account) {
        const token = await openWithDevice(account.session);
        if (token) {
          await request('DELETE', `${ACCOUNT_ORIGIN}/v1/session`, { bearer: token }).catch(
            () => undefined,
          );
        }
      }
    });
  }

  async deleteAccount(): Promise<boolean> {
    return await this.#serial(async () => {
      const account = this.#stored;
      if (!account) return true;
      const token = await openWithDevice(account.session);
      if (token) {
        try {
          await request('DELETE', `${ACCOUNT_ORIGIN}/v1/account`, {
            body: { confirm: 'DELETE' },
            bearer: token,
          });
        } catch (error) {
          if (!(error instanceof AccountFailure && error.kind === 'sessionRejected')) {
            return false;
          }
        }
      }
      await this.#forget();
      return true;
    });
  }

  #serial<T>(task: () => Promise<T>): Promise<T> {
    const next = this.#chain.then(task, task);
    this.#chain = next.catch(() => undefined);
    return next;
  }

  async #forget(): Promise<void> {
    this.#stored = null;
    this.#lease = null;
    this.#access = FREE_ACCESS;
    this.#problem = null;
    await kvDelete('account');
    this.#changed();
  }

  #due(account: StoredAccount): boolean {
    const now = Date.now() / 1000;
    return (
      account.leaseToken === null ||
      this.#access.needsOnlineVerification ||
      now - account.lastRefreshAt >= REFRESH_AFTER_SECONDS ||
      (this.#access.leaseValidUntil !== null &&
        this.#access.leaseValidUntil - now < EXPIRING_SOON_SECONDS)
    );
  }

  async #fetch(account: StoredAccount): Promise<void> {
    this.#refreshing = true;
    this.#problem = null;
    this.#changed();
    try {
      const session = await openWithDevice(account.session);
      if (!session) throw new AccountFailure('sessionRejected');
      const root = await request('GET', `${KEEP_ORIGIN}/v1/entitlement`, { bearer: session });
      const token = string(root.lease);
      if (!token) throw new AccountFailure('unavailable');
      const lease = this.#core.verifyLease(token, this.#keys);
      if (lease.subject !== account.accountId) throw new Error('inconsistent');
      const updated: StoredAccount = {
        ...account,
        leaseToken: token,
        lastObservedDeviceTime: lease.issuedAt,
        lastRefreshAt: Math.floor(Date.now() / 1000),
      };
      await kvSet('account', updated);
      this.#stored = updated;
      await this.#publish();
      if (this.#access.needsOnlineVerification && lease.tier === 'keepPlus') {
        this.#problem = 'clockOff';
      }
    } catch (error) {
      if (error instanceof AccountFailure) {
        this.#problem = error.kind === 'sessionRejected' ? 'sessionExpired' : 'offline';
      } else {
        this.#problem = 'failed';
      }
    } finally {
      this.#refreshing = false;
      this.#changed();
    }
  }

  async #publish(): Promise<void> {
    const account = this.#stored;
    if (!account) {
      this.#lease = null;
      this.#access = FREE_ACCESS;
      this.#changed();
      return;
    }
    let lease: Lease | null = null;
    if (account.leaseToken) {
      try {
        const verified = this.#core.verifyLease(account.leaseToken, this.#keys);
        lease = verified.subject === account.accountId ? verified : null;
      } catch {
        lease = null;
      }
    }
    const access = this.#core.evaluateAccess(
      lease,
      Math.floor(Date.now() / 1000),
      account.lastObservedDeviceTime,
    );
    this.#lease = lease;
    this.#access = access;
    if (access.observedDeviceTime - account.lastObservedDeviceTime >= PERSIST_AFTER_SECONDS) {
      const updated = { ...account, lastObservedDeviceTime: access.observedDeviceTime };
      this.#stored = updated;
      await kvSet('account', updated);
    }
    this.#changed();
  }
}
