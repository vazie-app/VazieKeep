import type { KeepCore } from '../core/keepCore';
import type { EntryDraft } from '../core/types';
import {
  CONTENT_CHANNEL,
  type ContentRequest,
  type ContentStrings,
  type MenuEntry,
  type PasskeyAsserted,
  type PasskeyChoice,
  type PasskeyCreated,
  type PasskeyListResult,
  type PasskeyOutcome,
  type PendingSavePlan,
  type QueryResult,
} from '../shared/content';
import { translator, type Language, type StringKey } from '../shared/i18n';
import {
  matchEntries,
  pageOrigin,
  planSave,
  titleFor,
  urlFor,
  type Match,
  type WebOrigin,
} from './autofillIndex';
import type { SettingsStore } from './settings';
import type { VaultManager } from './vaultManager';

const PENDING_TTL_MS = 2 * 60 * 1000;

interface Pending {
  at: number;
  origin: WebOrigin;
  username: string;
  password: string;
  newPassword: string | null;
}

const CONTENT_KEYS = [
  ['unlock', 'c.unlock'],
  ['locked', 'c.locked'],
  ['noMatches', 'c.noMatches'],
  ['fill', 'c.fill'],
  ['fillCode', 'c.fillCode'],
  ['generate', 'c.generate'],
  ['openKeep', 'c.openKeep'],
  ['filledFrom', 'c.filledFrom'],
  ['crossOrigin', 'c.crossOrigin'],
  ['saveTitle', 'c.saveTitle'],
  ['updateTitle', 'c.updateTitle'],
  ['saveAction', 'c.saveAction'],
  ['updateAction', 'c.updateAction'],
  ['createNew', 'c.createNew'],
  ['later', 'c.later'],
  ['never', 'c.never'],
  ['saved', 'c.saved'],
  ['updated', 'c.updated'],
  ['saveFailed', 'c.saveFailed'],
  ['chooseEntry', 'c.chooseEntry'],
  ['unlockAndSave', 'c.unlockAndSave'],
  ['passkeyCreateTitle', 'c.passkeyCreateTitle'],
  ['passkeyUseTitle', 'c.passkeyUseTitle'],
  ['passkeyCreate', 'c.passkeyCreate'],
  ['passkeyUse', 'c.passkeyUse'],
  ['passkeyOther', 'c.passkeyOther'],
  ['passkeyUnlock', 'c.passkeyUnlock'],
  ['passkeyExists', 'c.passkeyExists'],
  ['cancel', 'c.cancel'],
  ['passkeys', 'c.passkeys'],
] as const satisfies readonly (readonly [keyof ContentStrings, StringKey])[];

export function contentStrings(language: Language): ContentStrings {
  const t = translator(language);
  return Object.fromEntries(
    CONTENT_KEYS.map(([name, key]) => [name, t(key)]),
  ) as unknown as ContentStrings;
}

function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function fromBase64url(text: string): Uint8Array | null {
  try {
    const padded = text.replaceAll('-', '+').replaceAll('_', '/');
    const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
    return Uint8Array.from(binary, (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as BufferSource));
}

function isSecureOrigin(origin: WebOrigin): boolean {
  return (
    origin.scheme === 'https' ||
    origin.host === 'localhost' ||
    origin.host.endsWith('.localhost') ||
    origin.host === '127.0.0.1'
  );
}

export interface ContentDeps {
  core: KeepCore;
  manager: VaultManager;
  settings: SettingsStore;
  language: () => Language;
  touch: () => void;
  afterCreate: (host: string) => void;
}

interface Page {
  origin: WebOrigin;
  topOrigin: WebOrigin | null;
  frame: QueryResult['frame'];
  tabId: number | null;
}

export class ContentService {
  readonly #deps: ContentDeps;
  readonly #pending = new Map<number, Pending>();

  constructor(deps: ContentDeps) {
    this.#deps = deps;
  }

  clearPending(): void {
    this.#pending.clear();
  }

  #sweep(): void {
    const now = Date.now();
    for (const [tab, pending] of this.#pending) {
      if (now - pending.at > PENDING_TTL_MS) this.#pending.delete(tab);
    }
  }

  #page(sender: chrome.runtime.MessageSender): Page | null {
    if (sender.id !== chrome.runtime.id || !sender.url) return null;
    const origin = pageOrigin(sender.url);
    if (!origin) return null;
    const topOrigin = sender.tab?.url ? pageOrigin(sender.tab.url) : null;
    const sameFrame = sender.frameId === undefined || sender.frameId === 0;
    const frame: Page['frame'] = sameFrame
      ? 'top'
      : topOrigin && topOrigin.host === origin.host && topOrigin.scheme === origin.scheme
        ? 'same-origin'
        : 'cross-origin';
    return { origin, topOrigin, frame, tabId: sender.tab?.id ?? null };
  }

  #matches(page: Page): Match[] {
    return matchEntries(this.#deps.core, this.#deps.manager.index(), page.origin);
  }

  async handle(request: ContentRequest, sender: chrome.runtime.MessageSender): Promise<unknown> {
    const page = this.#page(sender);
    if (!page) return null;
    const { manager, settings } = this.#deps;
    const strings = contentStrings(this.#deps.language());
    switch (request.type) {
      case 'query': {
        // A page can focus fields on its own, so asking is not activity: only a click that fills is.
        const enabled = settings.value.autofill;
        const matches: MenuEntry[] =
          enabled && manager.isUnlocked ? this.#matches(page).map(menuEntry) : [];
        const result: QueryResult = {
          enabled,
          unlocked: manager.isUnlocked,
          hasVault: manager.list().length > 0,
          plus: this.#deps.manager.access.plusFeatures,
          frame: page.frame,
          host: page.origin.host,
          matches,
          strings,
        };
        return result;
      }
      case 'fill':
        return this.#fill(request.id, request.withTotp, page);
      case 'totp': {
        if (!this.#allowed(request.id, page)) return null;
        try {
          return { code: manager.totp(request.id).code };
        } catch {
          return null;
        }
      }
      case 'generate': {
        const bytes = this.#deps.core.generatePassword({
          length: 20,
          lowercase: true,
          uppercase: true,
          digits: true,
          symbols: true,
          excludeAmbiguous: false,
        });
        const password = new TextDecoder().decode(bytes);
        bytes.fill(0);
        return { password };
      }
      case 'submitted': {
        if (page.tabId === null || page.frame === 'cross-origin') return { ok: false };
        if (!settings.value.autofillSaveOffers || manager.list().length === 0) return { ok: false };
        if (settings.value.neverSave.includes(page.origin.host)) return { ok: false };
        this.#sweep();
        this.#pending.set(page.tabId, {
          at: Date.now(),
          origin: page.origin,
          username: request.username,
          password: request.password,
          newPassword: request.newPassword,
        });
        return { ok: true };
      }
      case 'discardSave':
        if (page.tabId !== null) this.#pending.delete(page.tabId);
        return { ok: true };
      case 'pendingSave':
        return this.#pendingPlan(page, request.passwordFieldPresent, strings);
      case 'confirmSave':
        return await this.#confirmSave(page, request.action, request.entryId);
      case 'neverSave': {
        const host = page.origin.host;
        const list = new Set(settings.value.neverSave);
        list.add(host);
        await settings.update({ neverSave: [...list] });
        if (page.tabId !== null) this.#pending.delete(page.tabId);
        return { ok: true };
      }
      case 'openPopup':
        return { ok: true };
      case 'passkeyAvailable':
        return { available: settings.value.passkeys };
      case 'passkeyList':
        return this.#passkeyList(request.rpId, request.allow, page, strings);
      case 'passkeyCreate':
        return await this.#passkeyCreate(request.options, page);
      case 'passkeyGet':
        return await this.#passkeyGet(request.options, request.entryId, page);
    }
  }

  #allowed(id: string, page: Page): boolean {
    return this.#matches(page).some((m) => m.entry.id === id);
  }

  #fill(id: string, withTotp: boolean, page: Page) {
    const { manager } = this.#deps;
    if (!manager.isUnlocked || !this.#allowed(id, page)) return null;
    this.#deps.touch();
    try {
      const username = manager.reveal(id, 'UserName');
      const password = manager.reveal(id, 'Password');
      let totp: string | null = null;
      if (withTotp) {
        try {
          totp = manager.totp(id).code;
        } catch {
          totp = null;
        }
      }
      return { username, password, totp };
    } catch {
      return null;
    }
  }

  #pendingPlan(
    page: Page,
    passwordFieldPresent: boolean,
    strings: ContentStrings,
  ): PendingSavePlan | null {
    if (page.tabId === null || passwordFieldPresent) return null;
    const pending = this.#pending.get(page.tabId);
    if (!pending) return null;
    if (Date.now() - pending.at > PENDING_TTL_MS) {
      this.#pending.delete(page.tabId);
      return null;
    }
    const { core, manager } = this.#deps;
    const related =
      core.matchHost(pending.origin.host, page.origin.host) !== 'none' ||
      core.matchHost(page.origin.host, pending.origin.host) !== 'none';
    if (!related) return null;
    const host = pending.origin.host;
    const secret = pending.newPassword ?? pending.password;
    if (secret === '') {
      this.#pending.delete(page.tabId);
      return null;
    }
    const plain = { host, username: pending.username, strings };
    if (!manager.isUnlocked) {
      return {
        kind: 'create',
        entry: null,
        candidates: [],
        alternatives: [],
        locked: true,
        passwordChanged: pending.newPassword !== null,
        ...plain,
      };
    }
    const matches = matchEntries(core, manager.index(), pending.origin);
    const plan = planSave(matches, pending.username);
    const brief = (e: { id: string; title: string; username: string }) => ({
      id: e.id,
      title: e.title,
      username: e.username,
    });
    if (plan.kind === 'update') {
      let same: boolean;
      try {
        same = manager.reveal(plan.entry.id, 'Password') === secret;
      } catch {
        same = false;
      }
      if (same) {
        this.#pending.delete(page.tabId);
        return null;
      }
    }
    return {
      kind: plan.kind,
      entry: plan.kind === 'update' ? brief(plan.entry) : null,
      candidates: plan.kind === 'choose' ? plan.candidates.map(brief) : [],
      alternatives: plan.kind === 'create' ? plan.alternatives.map(brief) : [],
      locked: false,
      passwordChanged: pending.newPassword !== null,
      ...plain,
    };
  }

  async #confirmSave(page: Page, action: 'create' | 'update', entryId: string | null) {
    const failed = { ok: false, saved: false, created: false };
    const { manager } = this.#deps;
    if (page.tabId === null || !manager.isUnlocked) return failed;
    const pending = this.#pending.get(page.tabId);
    if (!pending) return failed;
    const password = pending.newPassword ?? pending.password;
    try {
      if (action === 'create') {
        const draft: EntryDraft = {
          title: titleFor(pending.origin),
          username: pending.username,
          password,
          url: urlFor(pending.origin),
          notes: '',
          customFields: [],
          tags: [],
          favorite: false,
          expiresAt: null,
        };
        const root = manager.active?.info.rootGroupId;
        if (!root) return failed;
        const result = await manager.createEntry(root, draft);
        this.#pending.delete(page.tabId);
        if (result.saved && this.#deps.settings.value.siteIconsAutomatic) {
          this.#deps.afterCreate(pending.origin.host);
        }
        return { ok: true, saved: result.saved, created: true };
      }
      if (!entryId) return failed;
      const forSite = matchEntries(this.#deps.core, manager.index(), pending.origin);
      if (!forSite.some((m) => m.entry.id === entryId)) return failed;
      const result = await manager.edit((session) => {
        const detail = session.entry(entryId);
        const summary = detail.summary;
        const text = (value: typeof summary.title, key: string) =>
          value.kind === 'plain' ? value.value : session.revealText(entryId, key);
        const username = text(summary.username, 'UserName');
        const draft: EntryDraft = {
          title: text(summary.title, 'Title'),
          username: username === '' ? pending.username : username,
          password,
          url: text(summary.url, 'URL'),
          notes: text(detail.notes, 'Notes'),
          customFields: detail.customFields.map((field) => ({
            name: field.name,
            value:
              field.value.kind === 'plain'
                ? field.value.value
                : session.revealText(entryId, field.name),
            protected: field.value.kind === 'protected',
          })),
          tags: summary.tags,
          favorite: summary.favorite,
          expiresAt: summary.expiresAt,
        };
        session.updateEntry(entryId, draft);
      });
      this.#pending.delete(page.tabId);
      return { ok: true, saved: result.saved, created: false };
    } catch {
      return failed;
    }
  }

  // ---------------------------------------------------------------- passkeys

  #rpFor(asked: string | null, page: Page): string | null {
    const host = page.origin.host;
    const rpId = (asked ?? host).toLowerCase();
    if (rpId !== host && this.#deps.core.matchHost(rpId, host) === 'none') return null;
    return rpId;
  }

  #passkeyGate(
    asked: string | null,
    page: Page,
  ):
    | { ok: true; rpId: string }
    | { ok: false; reason: 'disabled' | 'insecure' | 'invalid-rp' | 'cross-origin' } {
    if (!this.#deps.settings.value.passkeys) return { ok: false, reason: 'disabled' };
    if (!isSecureOrigin(page.origin)) return { ok: false, reason: 'insecure' };
    if (page.frame === 'cross-origin') return { ok: false, reason: 'cross-origin' };
    const rpId = this.#rpFor(asked, page);
    if (!rpId) return { ok: false, reason: 'invalid-rp' };
    return { ok: true, rpId };
  }

  #passkeyList(
    rpAsked: string | null,
    allow: string[],
    page: Page,
    strings: ContentStrings,
  ): PasskeyListResult {
    const gate = this.#passkeyGate(rpAsked, page);
    if (!gate.ok) return gate;
    const { manager } = this.#deps;
    const choices: PasskeyChoice[] = manager
      .index()
      .filter(
        (e) =>
          e.passkeyRp === gate.rpId &&
          (allow.length === 0 ||
            (e.passkeyCredential !== null && allow.includes(e.passkeyCredential))),
      )
      .map((e) => ({ id: e.id, title: e.title, username: e.username, rpId: gate.rpId }));
    return { ok: true, unlocked: manager.isUnlocked, choices, rpId: gate.rpId, strings };
  }

  #clientData(type: 'webauthn.create' | 'webauthn.get', challenge: string, page: Page): Uint8Array {
    const origin = `${page.origin.scheme}://${page.origin.host}${page.origin.port === null ? '' : `:${page.origin.port}`}`;
    return new TextEncoder().encode(
      JSON.stringify({ type, challenge, origin, crossOrigin: false }),
    );
  }

  async #passkeyCreate(
    options: Extract<ContentRequest, { type: 'passkeyCreate' }>['options'],
    page: Page,
  ): Promise<PasskeyOutcome<PasskeyCreated>> {
    const gate = this.#passkeyGate(options.rpId, page);
    if (!gate.ok) return { ok: false, reason: gate.reason };
    const { manager } = this.#deps;
    if (!manager.isUnlocked) return { ok: false, reason: 'locked' };
    if (!options.acceptsEs256) return { ok: false, reason: 'unsupported-algorithm' };
    const userHandle = fromBase64url(options.userHandle);
    if (!userHandle || userHandle.length === 0 || userHandle.length > 64) {
      return { ok: false, reason: 'bad-request' };
    }
    const existing = manager
      .index()
      .some(
        (e) =>
          e.passkeyRp === gate.rpId &&
          e.passkeyCredential !== null &&
          options.exclude.includes(e.passkeyCredential),
      );
    if (existing) return { ok: false, reason: 'exists' };
    const root = manager.active?.info.rootGroupId;
    if (!root) return { ok: false, reason: 'locked' };
    this.#deps.touch();
    try {
      const clientData = this.#clientData('webauthn.create', options.challenge, page);
      const made = await manager.createPasskey(
        root,
        gate.rpId,
        options.rpName,
        options.userName || options.userDisplayName,
        userHandle,
      );
      if (!made.saved) {
        return { ok: false, reason: made.problem === 'changedElsewhere' ? 'changed' : 'not-saved' };
      }
      const credentialId = base64url(made.value.credentialId);
      return {
        ok: true,
        value: {
          id: credentialId,
          rawId: credentialId,
          clientDataJSON: base64url(clientData),
          attestationObject: base64url(made.value.attestationObject),
          authenticatorData: base64url(made.value.authenticatorData),
          publicKey: base64url(made.value.publicKey),
          publicKeyAlgorithm: -7,
        },
      };
    } catch (error) {
      return { ok: false, reason: error instanceof Error ? error.message : 'failed' };
    }
  }

  async #passkeyGet(
    options: Extract<ContentRequest, { type: 'passkeyGet' }>['options'],
    entryId: string | null,
    page: Page,
  ): Promise<PasskeyOutcome<PasskeyAsserted>> {
    const gate = this.#passkeyGate(options.rpId, page);
    if (!gate.ok) return { ok: false, reason: gate.reason };
    const { manager } = this.#deps;
    if (!manager.isUnlocked) return { ok: false, reason: 'locked' };
    const entry = manager.index().find((e) => e.id === entryId);
    if (!entry || entry.passkeyRp !== gate.rpId) return { ok: false, reason: 'no-passkey' };
    this.#deps.touch();
    try {
      const clientData = this.#clientData('webauthn.get', options.challenge, page);
      const hash = await sha256(clientData);
      const signed = manager.signPasskey(entry.id, gate.rpId, hash);
      const credentialId = base64url(signed.credentialId);
      return {
        ok: true,
        value: {
          id: credentialId,
          rawId: credentialId,
          clientDataJSON: base64url(clientData),
          authenticatorData: base64url(signed.authenticatorData),
          signature: base64url(signed.signature),
          userHandle: base64url(signed.userHandle),
        },
      };
    } catch (error) {
      return { ok: false, reason: error instanceof Error ? error.message : 'failed' };
    }
  }
}

function menuEntry(match: Match): MenuEntry {
  return {
    id: match.entry.id,
    title: match.entry.title,
    username: match.entry.username,
    hasTotp: match.entry.hasTotp,
    favorite: match.entry.favorite,
    kind: match.kind,
    icon: match.entry.icon,
    hasPasskey: match.entry.passkeyRp !== null,
  };
}

export function isContentMessage(
  message: unknown,
): message is { channel: typeof CONTENT_CHANNEL; request: ContentRequest } {
  if (typeof message !== 'object' || message === null) return false;
  const candidate = message as { channel?: unknown; request?: unknown };
  return (
    candidate.channel === CONTENT_CHANNEL &&
    typeof candidate.request === 'object' &&
    candidate.request !== null &&
    typeof (candidate.request as { type?: unknown }).type === 'string'
  );
}
