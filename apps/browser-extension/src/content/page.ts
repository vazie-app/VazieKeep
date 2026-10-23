import {
  BACKGROUND_CHANNEL,
  CONTENT_CHANNEL,
  PAGE_CHANNEL,
  type ContentRequest,
  type ContentResponse,
  type FillSecrets,
  type PendingSavePlan,
  type QueryResult,
} from '../shared/content';
import { completeConditional, conditionalOptions, initPasskeyBridge } from './bridge';
import {
  classify,
  hasVisiblePassword,
  otpFields,
  parseForms,
  setValue,
  type ParsedForm,
} from './detect';
import { Overlay } from './ui';

export type Ask = <R extends ContentRequest>(request: R) => Promise<ContentResponse<R> | null>;

const ask: Ask = async <R extends ContentRequest>(request: R) => {
  try {
    const answer: unknown = await chrome.runtime.sendMessage({ channel: CONTENT_CHANNEL, request });
    return answer as ContentResponse<R> | null;
  } catch {
    return null;
  }
};

const overlay = new Overlay();
let focused: HTMLInputElement | null = null;
let hideTimer: ReturnType<typeof setTimeout> | undefined;
let frameQuery: QueryResult | null = null;
let queryAt = 0;

async function query(
  mode: 'login' | 'registration' | 'otp' | 'username' = 'login',
): Promise<QueryResult | null> {
  if (frameQuery && Date.now() - queryAt < 1500) return frameQuery;
  const result = await ask({ type: 'query', mode });
  frameQuery = result;
  queryAt = Date.now();
  return result;
}

function relevant(input: HTMLInputElement): boolean {
  const kind = classify(input);
  return (
    kind === 'currentPassword' ||
    kind === 'newPassword' ||
    kind === 'username' ||
    kind === 'email' ||
    kind === 'otp'
  );
}

function formOf(field: HTMLInputElement): ParsedForm | null {
  return (
    parseForms().find((form) =>
      [form.username, form.password, form.newPassword, form.confirm].includes(field),
    ) ?? null
  );
}

// ---------------------------------------------------------------- inline icon and menu

async function onFocusIn(event: FocusEvent): Promise<void> {
  const target = event.target;
  if (!(target instanceof HTMLInputElement) || !relevant(target)) return;
  clearTimeout(hideTimer);
  focused = target;
  const result = await query();
  if (!result?.enabled || !result.hasVault || focused !== target) return;
  const strings = result.strings;
  overlay.showIcon(target, strings.openKeep, () => {
    if (overlay.menuOpen) overlay.hideMenu();
    else void openMenu(target);
  });
}

function onFocusOut(): void {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => {
    if (overlay.menuOpen) return;
    overlay.hideIcon();
    focused = null;
  }, 180);
}

function reposition(): void {
  if (focused) overlay.positionIcon(focused);
}

async function openMenu(field: HTMLInputElement): Promise<void> {
  frameQuery = null;
  const kind = classify(field);
  const result = await query(
    kind === 'otp' ? 'otp' : kind === 'newPassword' ? 'registration' : 'login',
  );
  if (!result) return;
  const form = formOf(field);
  const canGenerate =
    kind === 'newPassword' ||
    (form !== null &&
      form.kind !== 'login' &&
      form.kind !== 'usernameOnly' &&
      [form.newPassword, form.confirm].includes(field));
  let passkeys: { id: string; title: string; username: string }[] = [];
  const waiting = conditionalOptions();
  if (waiting && result.unlocked) {
    const list = await ask({
      type: 'passkeyList',
      rpId: waiting.rpId ?? location.hostname,
      allow: waiting.allow,
    });
    if (list?.ok)
      passkeys = list.choices.map((c) => ({ id: c.id, title: c.title, username: c.username }));
  }
  const renderMenu = (state: QueryResult) => {
    overlay.showMenu(
      field,
      state.strings,
      {
        unlocked: state.unlocked,
        enabled: state.enabled,
        matches: kind === 'otp' ? state.matches.filter((m) => m.hasTotp) : state.matches,
        plus: state.plus,
        warning: state.frame === 'cross-origin',
        canGenerate: canGenerate && state.unlocked,
        canCode: otpFields().length > 0 || kind === 'otp',
        passkeys,
      },
      {
        fill: (id, withTotp) => {
          overlay.hideMenu();
          void fillEntry(id, withTotp, form, field);
        },
        code: (id) => {
          overlay.hideMenu();
          void fillCode(id, field);
        },
        generate: () => {
          overlay.hideMenu();
          void generateInto(form, field);
        },
        unlock: () => {
          void chrome.runtime
            .sendMessage({ channel: BACKGROUND_CHANNEL, cmd: 'openPopup' })
            .catch(() => undefined);
          pollUnlock(field);
        },
        passkey: (id) => {
          overlay.hideMenu();
          void completeConditional(id);
        },
      },
    );
  };
  renderMenu(result);
}

function pollUnlock(field: HTMLInputElement): void {
  let attempts = 0;
  const timer = setInterval(() => {
    attempts += 1;
    if (!overlay.menuOpen || attempts > 60) {
      clearInterval(timer);
      return;
    }
    frameQuery = null;
    void query().then((result) => {
      if (result?.unlocked) {
        clearInterval(timer);
        void openMenu(field);
      }
    });
  }, 1500);
}

// ---------------------------------------------------------------- filling

function fillForm(
  form: ParsedForm | null,
  secrets: FillSecrets,
  near: HTMLInputElement | null,
): void {
  if (form?.username && secrets.username !== '') setValue(form.username, secrets.username);
  else if (!form && near && classify(near) !== 'currentPassword' && secrets.username !== '')
    setValue(near, secrets.username);
  if (form?.password && secrets.password !== '') setValue(form.password, secrets.password);
  if (secrets.totp) {
    const target = otpFields()[0];
    if (target) setValue(target, secrets.totp);
  }
}

async function fillEntry(
  id: string,
  withTotp: boolean,
  form: ParsedForm | null,
  near: HTMLInputElement | null,
): Promise<void> {
  const secrets = await ask({ type: 'fill', id, withTotp });
  if (!secrets) return;
  fillForm(form ?? fillableForm(), secrets, near);
}

async function fillCode(id: string, near: HTMLInputElement): Promise<void> {
  const result = await ask({ type: 'totp', id });
  if (!result) return;
  const target = classify(near) === 'otp' ? near : (otpFields()[0] ?? near);
  setValue(target, result.code);
}

async function generateInto(form: ParsedForm | null, field: HTMLInputElement): Promise<void> {
  const made = await ask({ type: 'generate' });
  if (!made) return;
  const targets = [form?.newPassword, form?.confirm].filter(
    (x): x is HTMLInputElement => x instanceof HTMLInputElement,
  );
  if (targets.length === 0) targets.push(field);
  for (const target of targets) setValue(target, made.password);
}

function fillableForm(): ParsedForm | null {
  const forms = parseForms();
  return forms.find((f) => f.kind === 'login' || f.kind === 'usernameOnly') ?? forms[0] ?? null;
}

// ---------------------------------------------------------------- saving

let lastSubmit = '';
let lastSubmitAt = 0;
let pendingTimers: ReturnType<typeof setTimeout>[] = [];

function capture(origin: Element | null): void {
  const forms = parseForms();
  const form =
    forms.find((f) => origin !== null && f.scope.contains(origin)) ??
    forms.find((f) => f.password?.value || f.newPassword?.value);
  if (!form) return;
  const current = form.password?.value ?? '';
  const fresh = form.newPassword?.value ?? '';
  if (current === '' && fresh === '') return;
  if (form.kind === 'registration' && form.confirm && form.confirm.value !== fresh) return;
  const username = form.username?.value ?? '';
  const key = `${username}\u0000${current}\u0000${fresh}`;
  const now = Date.now();
  if (key === lastSubmit && now - lastSubmitAt < 1500) return;
  lastSubmit = key;
  lastSubmitAt = now;
  void ask({
    type: 'submitted',
    username,
    password: current,
    newPassword: fresh === '' ? null : fresh,
  });
  for (const timer of pendingTimers) clearTimeout(timer);
  pendingTimers = [
    setTimeout(() => {
      void maybeOfferSave();
    }, 1800),
    setTimeout(() => {
      if (hasVisiblePassword()) void ask({ type: 'discardSave' });
      else void maybeOfferSave();
    }, 4500),
  ];
}

function onSubmit(event: Event): void {
  if (!event.isTrusted) return;
  capture(event.target instanceof Element ? event.target : null);
}

function onClick(event: MouseEvent): void {
  if (!event.isTrusted || !(event.target instanceof Element)) return;
  const button = event.target.closest(
    'button, input[type="submit"], input[type="button"], [role="button"]',
  );
  if (!button) return;
  const type = (button.getAttribute('type') ?? 'submit').toLowerCase();
  if (button instanceof HTMLButtonElement && type !== 'submit') {
    const text = button.textContent.toLowerCase();
    if (
      !/sign|log|continue|next|register|create|submit|войти|вход|продолж|регистр|далее|создать/.test(
        text,
      )
    )
      return;
  }
  capture(button);
}

function onKeyDown(event: KeyboardEvent): void {
  if (!event.isTrusted || event.key !== 'Enter') return;
  const target = event.target;
  if (
    target instanceof HTMLInputElement &&
    (classify(target) === 'currentPassword' || classify(target) === 'newPassword')
  ) {
    capture(target);
  }
}

let bannerPlan: PendingSavePlan | null = null;
let unlockTimer: ReturnType<typeof setInterval> | undefined;

async function maybeOfferSave(): Promise<void> {
  if (window.top !== window) return;
  const plan = await ask({ type: 'pendingSave', passwordFieldPresent: hasVisiblePassword() });
  if (!plan) return;
  showBanner(plan);
}

function showBanner(plan: PendingSavePlan): void {
  bannerPlan = plan;
  overlay.showBanner(plan, {
    create: () => {
      void confirm('create', null);
    },
    update: (id) => {
      void confirm('update', id);
    },
    later: () => {
      overlay.hideBanner();
      clearInterval(unlockTimer);
      void ask({ type: 'discardSave' });
    },
    never: () => {
      overlay.hideBanner();
      clearInterval(unlockTimer);
      void ask({ type: 'neverSave' });
    },
    unlock: () => {
      void chrome.runtime
        .sendMessage({ channel: BACKGROUND_CHANNEL, cmd: 'openPopup' })
        .catch(() => undefined);
      clearInterval(unlockTimer);
      let attempts = 0;
      unlockTimer = setInterval(() => {
        attempts += 1;
        if (attempts > 60) {
          clearInterval(unlockTimer);
          return;
        }
        void ask({ type: 'pendingSave', passwordFieldPresent: false }).then((next) => {
          if (next && !next.locked) {
            clearInterval(unlockTimer);
            showBanner(next);
          }
        });
      }, 1500);
    },
  });
}

async function confirm(action: 'create' | 'update', entryId: string | null): Promise<void> {
  const strings = bannerPlan?.strings;
  const result = await ask({ type: 'confirmSave', action, entryId });
  if (!strings) {
    overlay.hideBanner();
    return;
  }
  if (result?.ok && result.saved) overlay.flash(result.created ? strings.saved : strings.updated);
  else overlay.flash(strings.saveFailed);
}

// ---------------------------------------------------------------- commands from the toolbar, popup and shortcut

chrome.runtime.onMessage.addListener((message: unknown, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id || typeof message !== 'object' || message === null)
    return false;
  const command = message as { channel?: unknown; cmd?: unknown; id?: unknown; withTotp?: unknown };
  if (command.channel !== PAGE_CHANNEL) return false;
  if (command.cmd === 'fill' && typeof command.id === 'string') {
    const form = fillableForm();
    if (!form) {
      sendResponse({ filled: false });
      return false;
    }
    void fillEntry(command.id, command.withTotp === true, form, null).then(() => {
      sendResponse({ filled: true });
    });
    return true;
  }
  if (command.cmd === 'fillBest') {
    void (async () => {
      const form = fillableForm();
      const field = form?.password ?? form?.username ?? null;
      if (!form || !field) {
        sendResponse({ filled: false });
        return;
      }
      const result = await query();
      if (!result || !result.enabled) {
        sendResponse({ filled: false });
        return;
      }
      if (!result.unlocked) {
        await chrome.runtime
          .sendMessage({ channel: BACKGROUND_CHANNEL, cmd: 'openPopup' })
          .catch(() => undefined);
      } else if (result.matches.length === 1 && result.matches[0]) {
        await fillEntry(result.matches[0].id, result.matches[0].hasTotp, form, field);
      } else {
        field.focus();
        await openMenu(field);
      }
      sendResponse({ filled: true });
    })();
    return true;
  }
  return false;
});

// ---------------------------------------------------------------- start

function start(): void {
  document.addEventListener(
    'focusin',
    (event) => {
      void onFocusIn(event);
    },
    true,
  );
  document.addEventListener('focusout', onFocusOut, true);
  document.addEventListener('submit', onSubmit, true);
  document.addEventListener('click', onClick, true);
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener(
    'keydown',
    (event) => {
      if (event.key === 'Escape' && overlay.menuOpen) {
        overlay.hideMenu();
        event.stopPropagation();
      }
    },
    true,
  );
  document.addEventListener(
    'pointerdown',
    (event) => {
      if (overlay.menuOpen && !event.composedPath().some((node) => overlay.contains(node)))
        overlay.hideMenu();
    },
    true,
  );
  let frame = 0;
  const schedule = () => {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      reposition();
    });
  };
  window.addEventListener('scroll', schedule, true);
  window.addEventListener('resize', schedule);
  if (window.top === window) {
    setTimeout(() => {
      void maybeOfferSave();
    }, 900);
  }
}

initPasskeyBridge(overlay, ask);

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', start, { once: true });
} else {
  start();
}
