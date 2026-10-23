/** What a page-side script and the host say to each other. Everything here is JSON: it crosses `runtime.sendMessage`. */

export const CONTENT_CHANNEL = 'keep:content';
export const BACKGROUND_CHANNEL = 'keep:background';
export const PAGE_CHANNEL = 'keep:page';

export interface MenuEntry {
  id: string;
  title: string;
  username: string;
  hasTotp: boolean;
  favorite: boolean;
  kind: 'exact' | 'subdomain';
  icon: number;
  hasPasskey: boolean;
}

export type FieldMode = 'login' | 'registration' | 'otp' | 'username';

export interface QueryResult {
  enabled: boolean;
  unlocked: boolean;
  hasVault: boolean;
  plus: boolean;
  frame: 'top' | 'same-origin' | 'cross-origin';
  host: string | null;
  matches: MenuEntry[];
  strings: ContentStrings;
}

export interface FillSecrets {
  username: string;
  password: string;
  totp: string | null;
}

export interface PendingSavePlan {
  kind: 'create' | 'update' | 'choose';
  host: string;
  username: string;
  entry: { id: string; title: string; username: string } | null;
  candidates: { id: string; title: string; username: string }[];
  alternatives: { id: string; title: string; username: string }[];
  locked: boolean;
  passwordChanged: boolean;
  strings: ContentStrings;
}

export type ContentRequest =
  | { type: 'query'; mode: FieldMode }
  | { type: 'fill'; id: string; withTotp: boolean }
  | { type: 'totp'; id: string }
  | { type: 'generate' }
  | {
      type: 'submitted';
      username: string;
      password: string;
      newPassword: string | null;
    }
  | { type: 'discardSave' }
  | { type: 'pendingSave'; passwordFieldPresent: boolean }
  | { type: 'confirmSave'; action: 'create' | 'update'; entryId: string | null }
  | { type: 'neverSave' }
  | { type: 'openPopup' }
  | { type: 'passkeyAvailable'; rpId: string | null }
  | { type: 'passkeyList'; rpId: string; allow: string[] }
  | {
      type: 'passkeyCreate';
      options: PasskeyCreateOptions;
    }
  | {
      type: 'passkeyGet';
      options: PasskeyGetOptions;
      entryId: string | null;
    };

export interface PasskeyCreateOptions {
  rpId: string | null;
  rpName: string;
  userName: string;
  userDisplayName: string;
  userHandle: string;
  challenge: string;
  exclude: string[];
  acceptsEs256: boolean;
  crossOrigin: boolean;
}

export interface PasskeyGetOptions {
  rpId: string | null;
  challenge: string;
  allow: string[];
  crossOrigin: boolean;
}

export interface PasskeyChoice {
  id: string;
  title: string;
  username: string;
  rpId: string;
}

export type PasskeyListResult =
  | { ok: true; unlocked: boolean; choices: PasskeyChoice[]; rpId: string; strings: ContentStrings }
  | { ok: false; reason: 'disabled' | 'insecure' | 'invalid-rp' | 'cross-origin' };

export interface PasskeyCreated {
  id: string;
  rawId: string;
  clientDataJSON: string;
  attestationObject: string;
  authenticatorData: string;
  publicKey: string;
  publicKeyAlgorithm: number;
}

export interface PasskeyAsserted {
  id: string;
  rawId: string;
  clientDataJSON: string;
  authenticatorData: string;
  signature: string;
  userHandle: string;
}

export type PasskeyOutcome<T> = { ok: true; value: T } | { ok: false; reason: string };

export type ContentResponse<R extends ContentRequest> = R extends { type: 'query' }
  ? QueryResult
  : R extends { type: 'fill' }
    ? FillSecrets | null
    : R extends { type: 'totp' }
      ? { code: string } | null
      : R extends { type: 'generate' }
        ? { password: string } | null
        : R extends { type: 'pendingSave' }
          ? PendingSavePlan | null
          : R extends { type: 'confirmSave' }
            ? { ok: boolean; saved: boolean; created: boolean }
            : R extends { type: 'passkeyAvailable' }
              ? { available: boolean }
              : R extends { type: 'passkeyList' }
                ? PasskeyListResult
                : R extends { type: 'passkeyCreate' }
                  ? PasskeyOutcome<PasskeyCreated>
                  : R extends { type: 'passkeyGet' }
                    ? PasskeyOutcome<PasskeyAsserted>
                    : { ok: boolean };

/** The words a page-side script shows, in the person's language. The host decides the language. */
export interface ContentStrings {
  unlock: string;
  locked: string;
  noMatches: string;
  fill: string;
  fillCode: string;
  generate: string;
  openKeep: string;
  filledFrom: string;
  crossOrigin: string;
  saveTitle: string;
  updateTitle: string;
  saveAction: string;
  updateAction: string;
  createNew: string;
  later: string;
  never: string;
  saved: string;
  updated: string;
  saveFailed: string;
  chooseEntry: string;
  unlockAndSave: string;
  passkeyCreateTitle: string;
  passkeyUseTitle: string;
  passkeyCreate: string;
  passkeyUse: string;
  passkeyOther: string;
  passkeyUnlock: string;
  passkeyExists: string;
  cancel: string;
  passkeys: string;
}
