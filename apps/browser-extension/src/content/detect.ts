export type FieldKind =
  'currentPassword' | 'newPassword' | 'username' | 'email' | 'phone' | 'otp' | 'other';
export type FormKind = 'login' | 'registration' | 'passwordChange' | 'usernameOnly';

export interface ParsedForm {
  kind: FormKind;
  username: HTMLInputElement | null;
  password: HTMLInputElement | null;
  newPassword: HTMLInputElement | null;
  confirm: HTMLInputElement | null;
  scope: Element;
}

const MAX_INPUTS = 2000;
const USERNAME_WORDS = [
  'user',
  'login',
  'email',
  'e-mail',
  'account',
  'uname',
  'identifier',
  'nickname',
];
const OTP_WORDS = [
  'otp',
  'one-time',
  'onetime',
  'verification',
  'verify',
  '2fa',
  'mfa',
  'totp',
  'authenticator',
  'security code',
  'код',
];

function tokens(value: string | null): string[] {
  return (value ?? '').toLowerCase().split(/\s+/).filter(Boolean);
}

export function isVisible(element: Element): boolean {
  if (!(element instanceof HTMLElement)) return false;
  if (!element.isConnected) return false;
  const style = getComputedStyle(element);
  if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0')
    return false;
  const rect = element.getBoundingClientRect();
  return rect.width >= 8 && rect.height >= 8;
}

function words(input: HTMLInputElement): string {
  return [
    input.name,
    input.id,
    input.placeholder,
    input.getAttribute('aria-label'),
    input.getAttribute('data-testid'),
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

export function classify(input: HTMLInputElement): FieldKind {
  const autocomplete = tokens(input.getAttribute('autocomplete'));
  const type = (input.getAttribute('type') ?? 'text').toLowerCase();
  const text = words(input);
  if (autocomplete.includes('one-time-code')) return 'otp';
  if (autocomplete.includes('new-password')) return 'newPassword';
  if (autocomplete.includes('current-password') || type === 'password') return 'currentPassword';
  if (type === 'tel' || autocomplete.includes('tel')) return 'phone';
  if (autocomplete.includes('username') || autocomplete.includes('webauthn')) return 'username';
  if (type === 'email' || autocomplete.includes('email')) return 'email';
  if (OTP_WORDS.some((word) => text.includes(word)) && input.maxLength > 0 && input.maxLength <= 10)
    return 'otp';
  if (USERNAME_WORDS.some((word) => text.includes(word))) return 'username';
  return 'other';
}

function isTextLike(input: HTMLInputElement): boolean {
  const type = (input.getAttribute('type') ?? 'text').toLowerCase();
  return ['text', 'email', 'tel', 'search', 'password', 'number', 'url', ''].includes(type);
}

function collectInputs(root: ParentNode, into: HTMLInputElement[], depth = 0): void {
  if (into.length >= MAX_INPUTS || depth > 6) return;
  for (const input of root.querySelectorAll('input')) {
    if (into.length >= MAX_INPUTS) return;
    into.push(input);
  }
  for (const element of root.querySelectorAll('*')) {
    if (element.shadowRoot) collectInputs(element.shadowRoot, into, depth + 1);
  }
}

export function allInputs(): HTMLInputElement[] {
  const found: HTMLInputElement[] = [];
  collectInputs(document, found);
  return found.filter((input) => isTextLike(input) && !input.disabled && isVisible(input));
}

function scopeOf(input: HTMLInputElement): Element {
  const form = input.form ?? input.closest('form');
  if (form) return form;
  let node: Element = input;
  for (let depth = 0; depth < 4 && node.parentElement; depth += 1) {
    node = node.parentElement;
    if (node.querySelectorAll('input[type="password"]').length > 1) break;
  }
  return node;
}

function inScope(scope: Element, inputs: HTMLInputElement[]): HTMLInputElement[] {
  return inputs.filter(
    (input) =>
      scope.contains(input) ||
      (scope.getRootNode() === input.getRootNode() && scope.contains(input)),
  );
}

function usernameBefore(
  fields: HTMLInputElement[],
  kinds: FieldKind[],
  anchor: number,
): HTMLInputElement | null {
  for (let i = anchor - 1; i >= 0; i -= 1) {
    const kind = kinds[i];
    const field = fields[i];
    if (field && (kind === 'username' || kind === 'email')) return field;
  }
  const previous = fields[anchor - 1];
  if (
    previous &&
    kinds[anchor - 1] === 'other' &&
    ['text', ''].includes((previous.getAttribute('type') ?? '').toLowerCase())
  ) {
    return previous;
  }
  return null;
}

/** Finds the login, registration or password-change forms of the page. */
export function parseForms(): ParsedForm[] {
  const inputs = allInputs();
  const forms: ParsedForm[] = [];
  const handled = new Set<Element>();
  for (const input of inputs) {
    const kind = classify(input);
    if (kind !== 'currentPassword' && kind !== 'newPassword') continue;
    const scope = scopeOf(input);
    if (handled.has(scope)) continue;
    handled.add(scope);
    const fields = inScope(scope, inputs);
    const kinds = fields.map(classify);
    const current = fields.filter((_, i) => kinds[i] === 'currentPassword');
    const fresh = fields.filter((_, i) => kinds[i] === 'newPassword');
    const plain = (list: HTMLInputElement[]) =>
      list.every(
        (field) => !tokens(field.getAttribute('autocomplete')).includes('current-password'),
      );
    let parsed: ParsedForm;
    const base = { scope };
    if (fresh.length > 0 && current.length > 0) {
      const password = current[0] ?? null;
      parsed = {
        ...base,
        kind: 'passwordChange',
        username: null,
        password,
        newPassword: fresh[0] ?? null,
        confirm: fresh[1] ?? null,
      };
    } else if (fresh.length > 0) {
      parsed = {
        ...base,
        kind: 'registration',
        username: null,
        password: null,
        newPassword: fresh[0] ?? null,
        confirm: fresh[1] ?? null,
      };
    } else if (current.length >= 3 && plain(current)) {
      parsed = {
        ...base,
        kind: 'passwordChange',
        username: null,
        password: current[0] ?? null,
        newPassword: current[1] ?? null,
        confirm: current[2] ?? null,
      };
    } else if (current.length === 2 && plain(current)) {
      parsed = {
        ...base,
        kind: 'registration',
        username: null,
        password: null,
        newPassword: current[0] ?? null,
        confirm: current[1] ?? null,
      };
    } else {
      const focused = current.find((field) => field === document.activeElement);
      parsed = {
        ...base,
        kind: 'login',
        username: null,
        password: focused ?? current[0] ?? null,
        newPassword: null,
        confirm: null,
      };
    }
    const anchorField = parsed.password ?? parsed.newPassword;
    const anchor = anchorField ? fields.indexOf(anchorField) : -1;
    parsed.username = anchor > 0 ? usernameBefore(fields, kinds, anchor) : null;
    forms.push(parsed);
  }

  // Username-first sign-in: a field that says plainly it is the username, with no password beside it.
  if (forms.length === 0) {
    const strong = inputs.filter((input) => {
      const autocomplete = tokens(input.getAttribute('autocomplete'));
      return (
        autocomplete.includes('username') ||
        (classify(input) === 'email' && autocomplete.includes('email')) ||
        autocomplete.includes('webauthn')
      );
    });
    const focused = strong.find((input) => input === document.activeElement);
    const only = strong.length === 1 ? strong[0] : focused;
    if (only) {
      forms.push({
        kind: 'usernameOnly',
        username: only,
        password: null,
        newPassword: null,
        confirm: null,
        scope: scopeOf(only),
      });
    }
  }
  return forms;
}

export function otpFields(): HTMLInputElement[] {
  return allInputs().filter((input) => classify(input) === 'otp');
}

export function hasVisiblePassword(): boolean {
  return allInputs().some((input) => ['currentPassword', 'newPassword'].includes(classify(input)));
}

export function setValue(input: HTMLInputElement, value: string): void {
  const prototype = Object.getPrototypeOf(input) as object;
  const descriptor =
    Object.getOwnPropertyDescriptor(prototype, 'value') ??
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
  input.focus({ preventScroll: true });
  if (descriptor?.set) descriptor.set.call(input, value);
  else input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
  input.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
}
