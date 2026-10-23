import type { ContentStrings, MenuEntry, PendingSavePlan } from '../shared/content';

const CSS = `
:host { all: initial; position: fixed; top: 0; left: 0; width: 0; height: 0; z-index: 2147483647; }
* { box-sizing: border-box; }
.layer { font: 14px/1.4 system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif; color: #211a25; }
@media (prefers-color-scheme: dark) { .layer { color: #f2ece4; } }
.icon { position: fixed; width: 24px; height: 24px; padding: 0; border: 0; border-radius: 8px; background: #5d43a8; color: #fff; display: grid; place-items: center; cursor: pointer; box-shadow: 0 1px 4px rgba(0,0,0,.35); }
.icon:hover { filter: brightness(1.15); }
.icon svg { width: 15px; height: 15px; }
.card { position: fixed; background: #fffefc; color: #211a25; border-radius: 16px; box-shadow: 0 8px 32px rgba(52,36,70,.28), 0 0 0 1px rgba(33,26,37,.08); overflow: hidden; }
@media (prefers-color-scheme: dark) { .card { background: #1a161d; color: #f2ece4; box-shadow: 0 8px 32px rgba(0,0,0,.6), 0 0 0 1px rgba(255,255,255,.08); } }
.head { display: flex; align-items: center; gap: 8px; padding: 10px 14px; font-weight: 600; font-size: 13px; color: #5d43a8; }
@media (prefers-color-scheme: dark) { .head { color: #c9b6f2; } }
.head svg { width: 16px; height: 16px; }
.note { padding: 8px 14px; font-size: 12px; color: #a23b32; }
.muted { padding: 10px 14px 12px; font-size: 13px; opacity: .75; }
.item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 8px 14px; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; font: inherit; }
.item:hover, .item:focus-visible { background: rgba(93,67,168,.12); outline: none; }
.item .t { flex: 1; min-width: 0; }
.item .t b { display: block; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.item .t span { display: block; font-size: 12px; opacity: .7; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 6px; background: rgba(93,67,168,.16); color: #5d43a8; white-space: nowrap; }
@media (prefers-color-scheme: dark) { .tag { color: #c9b6f2; } }
.sep { height: 1px; background: rgba(127,127,127,.2); margin: 4px 0; }
.btns { display: flex; flex-wrap: wrap; gap: 8px; padding: 10px 14px 14px; }
.btn { min-height: 36px; padding: 0 14px; border: 0; border-radius: 12px; font: 600 13px system-ui, sans-serif; cursor: pointer; background: rgba(127,127,127,.18); color: inherit; }
.btn.primary { background: #2a2030; color: #f6eedd; }
@media (prefers-color-scheme: dark) { .btn.primary { background: #f1e3c8; color: #2a2018; } }
.btn.quiet { background: transparent; color: #5d43a8; }
@media (prefers-color-scheme: dark) { .btn.quiet { color: #c9b6f2; } }
.body { padding: 0 14px 4px; font-size: 13px; }
.body .k { opacity: .65; }
.radio { display: flex; align-items: center; gap: 8px; padding: 6px 0; cursor: pointer; }
.backdrop { position: fixed; inset: 0; width: 100vw; height: 100vh; background: rgba(0,0,0,.45); }
`;

const SHIELD =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5L19 6V12C19 16 16 19 12 20.5C8 19 5 16 5 12V6Z"/><path d="M9 12L11 14L15 10"/></svg>';

type Child = Node | string | null | undefined | false;

type Prop = string | number | boolean | null | undefined | ((event: Event) => void);

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Record<string, Prop> = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = String(value);
    else if (key.startsWith('on') && typeof value === 'function') {
      node.addEventListener(key.slice(2), (event) => {
        if (event.isTrusted) value(event);
      });
    } else if (typeof value !== 'function') node.setAttribute(key, String(value));
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    node.append(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return node;
}

function shield(): HTMLElement {
  const span = document.createElement('span');
  span.innerHTML = SHIELD;
  return span;
}

export class Overlay {
  readonly #host: HTMLElement;
  readonly #layer: HTMLElement;
  #icon: HTMLElement | null = null;
  #menu: HTMLElement | null = null;
  #banner: HTMLElement | null = null;
  #modal: HTMLElement | null = null;

  constructor() {
    this.#host = document.createElement('div');
    this.#host.setAttribute('data-vazie-keep', '');
    const root = this.#host.attachShadow({ mode: __KEEP_DEBUG__ ? 'open' : 'closed' });
    const style = document.createElement('style');
    style.textContent = CSS;
    this.#layer = el('div', { class: 'layer' });
    root.append(style, this.#layer);
  }

  #mount(): void {
    if (!this.#host.isConnected) document.documentElement.append(this.#host);
  }

  contains(target: EventTarget | null): boolean {
    return target === this.#host;
  }

  // ---- inline icon

  showIcon(anchor: HTMLElement, label: string, onClick: () => void): void {
    this.hideIcon();
    this.#mount();
    const button = el('button', {
      class: 'icon',
      type: 'button',
      'aria-label': label,
      title: label,
      onclick: onClick,
    });
    button.append(shield());
    button.addEventListener('mousedown', (event) => {
      event.preventDefault();
    });
    this.#layer.append(button);
    this.#icon = button;
    this.positionIcon(anchor);
  }

  positionIcon(anchor: HTMLElement): void {
    const icon = this.#icon;
    if (!icon) return;
    const rect = anchor.getBoundingClientRect();
    const size = 24;
    icon.style.top = `${Math.round(rect.top + (rect.height - size) / 2)}px`;
    icon.style.left = `${Math.round(Math.max(rect.left, rect.right - size - 6))}px`;
  }

  hideIcon(): void {
    this.#icon?.remove();
    this.#icon = null;
  }

  // ---- menu

  hideMenu(): void {
    this.#menu?.remove();
    this.#menu = null;
  }

  get menuOpen(): boolean {
    return this.#menu !== null;
  }

  showMenu(
    anchor: HTMLElement,
    strings: ContentStrings,
    state: {
      unlocked: boolean;
      enabled: boolean;
      matches: MenuEntry[];
      plus: boolean;
      warning: boolean;
      canGenerate: boolean;
      canCode: boolean;
      passkeys: { id: string; title: string; username: string }[];
    },
    actions: {
      fill: (id: string, withTotp: boolean) => void;
      code: (id: string) => void;
      generate: () => void;
      unlock: () => void;
      passkey: (id: string) => void;
    },
  ): void {
    this.hideMenu();
    this.#mount();
    const card = el('div', { class: 'card', role: 'menu', 'aria-label': 'Vazie Keep' });
    card.append(el('div', { class: 'head' }, shield(), 'Vazie Keep'));
    if (state.warning) card.append(el('div', { class: 'note' }, strings.crossOrigin));
    if (!state.unlocked) {
      card.append(el('div', { class: 'muted' }, strings.locked));
      card.append(
        el(
          'div',
          { class: 'btns' },
          el(
            'button',
            { class: 'btn primary', type: 'button', onclick: actions.unlock },
            strings.unlock,
          ),
        ),
      );
    } else {
      if (state.matches.length === 0 && state.passkeys.length === 0 && !state.canGenerate) {
        card.append(el('div', { class: 'muted' }, strings.noMatches));
      }
      for (const match of state.matches) {
        const row = el(
          'button',
          {
            class: 'item',
            type: 'button',
            role: 'menuitem',
            onclick: () => {
              actions.fill(match.id, false);
            },
          },
          el(
            'div',
            { class: 't' },
            el('b', {}, match.title === '' ? match.username : match.title),
            el('span', {}, match.username),
          ),
          match.kind === 'subdomain' ? el('span', { class: 'tag' }, '*') : null,
        );
        card.append(row);
        if (match.hasTotp && state.plus && state.canCode) {
          card.append(
            el(
              'button',
              {
                class: 'item',
                type: 'button',
                role: 'menuitem',
                onclick: () => {
                  actions.code(match.id);
                },
              },
              el('div', { class: 't' }, el('span', {}, `${strings.fillCode} — ${match.title}`)),
            ),
          );
        }
      }
      if (state.passkeys.length > 0) {
        card.append(el('div', { class: 'sep' }));
        card.append(el('div', { class: 'head' }, strings.passkeys));
        for (const key of state.passkeys) {
          card.append(
            el(
              'button',
              {
                class: 'item',
                type: 'button',
                role: 'menuitem',
                onclick: () => {
                  actions.passkey(key.id);
                },
              },
              el('div', { class: 't' }, el('b', {}, key.title), el('span', {}, key.username)),
            ),
          );
        }
      }
      if (state.canGenerate) {
        card.append(el('div', { class: 'sep' }));
        card.append(
          el(
            'button',
            { class: 'item', type: 'button', role: 'menuitem', onclick: actions.generate },
            el('div', { class: 't' }, el('b', {}, strings.generate)),
          ),
        );
      }
    }
    card.addEventListener('keydown', (event) => {
      const items = [...card.querySelectorAll<HTMLElement>('.item, .btn')];
      const index = items.indexOf(
        card.getRootNode() instanceof ShadowRoot
          ? ((card.getRootNode() as ShadowRoot).activeElement as HTMLElement)
          : (document.activeElement as HTMLElement),
      );
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        items[Math.min(index + 1, items.length - 1)]?.focus();
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        items[Math.max(index - 1, 0)]?.focus();
      }
    });
    this.#layer.append(card);
    this.#menu = card;
    const place = () => {
      const rect = anchor.getBoundingClientRect();
      const width = Math.min(Math.max(rect.width, 280), Math.min(360, window.innerWidth - 16));
      card.style.width = `${width}px`;
      const left = Math.min(Math.max(8, rect.left), window.innerWidth - width - 8);
      const below = rect.bottom + 6;
      const height = card.offsetHeight;
      const top =
        below + height > window.innerHeight - 8 && rect.top - height - 6 > 8
          ? rect.top - height - 6
          : below;
      card.style.left = `${Math.round(left)}px`;
      card.style.top = `${Math.round(Math.max(8, top))}px`;
    };
    place();
    requestAnimationFrame(place);
  }

  // ---- banner

  hideBanner(): void {
    this.#banner?.remove();
    this.#banner = null;
  }

  showBanner(
    plan: PendingSavePlan,
    actions: {
      create: () => void;
      update: (id: string) => void;
      later: () => void;
      never: () => void;
      unlock: () => void;
    },
  ): void {
    this.hideBanner();
    this.#mount();
    const s = plan.strings;
    const card = el('div', { class: 'card', role: 'dialog', 'aria-label': 'Vazie Keep' });
    card.style.top = '16px';
    card.style.right = '16px';
    card.style.width = '340px';
    const title = plan.kind === 'update' ? s.updateTitle : s.saveTitle;
    card.append(el('div', { class: 'head' }, shield(), title));
    const body = el('div', { class: 'body' });
    body.append(el('div', {}, el('span', { class: 'k' }, '↗ '), plan.host));
    if (plan.username !== '')
      body.append(el('div', {}, el('span', { class: 'k' }, '@ '), plan.username));
    body.append(el('div', {}, el('span', { class: 'k' }, '• '), '••••••••'));
    let selected: string | null = plan.entry?.id ?? null;
    const choices =
      plan.kind === 'choose' ? plan.candidates : plan.kind === 'create' ? plan.alternatives : [];
    if (plan.locked) {
      card.append(body);
      card.append(
        el(
          'div',
          { class: 'btns' },
          el(
            'button',
            { class: 'btn primary', type: 'button', onclick: actions.unlock },
            s.unlockAndSave,
          ),
          el('button', { class: 'btn quiet', type: 'button', onclick: actions.later }, s.later),
        ),
      );
    } else {
      if (plan.kind === 'choose')
        body.append(el('div', { class: 'muted', style: 'padding:6px 0 0' }, s.chooseEntry));
      if (choices.length > 0) {
        for (const candidate of choices) {
          const input = el('input', { type: 'radio', name: 'entry' });
          input.addEventListener('change', () => {
            selected = candidate.id;
          });
          body.append(
            el(
              'label',
              { class: 'radio' },
              input,
              `${candidate.title}${candidate.username ? ` · ${candidate.username}` : ''}`,
            ),
          );
        }
      }
      card.append(body);
      const buttons = el('div', { class: 'btns' });
      if (plan.kind === 'update') {
        buttons.append(
          el(
            'button',
            {
              class: 'btn primary',
              type: 'button',
              onclick: () => {
                if (selected) actions.update(selected);
              },
            },
            s.updateAction,
          ),
        );
      } else if (plan.kind === 'choose') {
        buttons.append(
          el(
            'button',
            {
              class: 'btn primary',
              type: 'button',
              onclick: () => {
                if (selected) actions.update(selected);
              },
            },
            s.updateAction,
          ),
        );
        buttons.append(
          el('button', { class: 'btn', type: 'button', onclick: actions.create }, s.createNew),
        );
      } else {
        buttons.append(
          el(
            'button',
            { class: 'btn primary', type: 'button', onclick: actions.create },
            s.saveAction,
          ),
        );
        if (choices.length > 0) {
          buttons.append(
            el(
              'button',
              {
                class: 'btn',
                type: 'button',
                onclick: () => {
                  if (selected) actions.update(selected);
                },
              },
              s.updateAction,
            ),
          );
        }
      }
      buttons.append(
        el('button', { class: 'btn quiet', type: 'button', onclick: actions.later }, s.later),
      );
      buttons.append(
        el('button', { class: 'btn quiet', type: 'button', onclick: actions.never }, s.never),
      );
      card.append(buttons);
    }
    this.#layer.append(card);
    this.#banner = card;
  }

  flash(text: string): void {
    this.hideBanner();
    this.#mount();
    const card = el('div', { class: 'card', role: 'status' });
    card.style.top = '16px';
    card.style.right = '16px';
    card.style.width = '320px';
    card.append(
      el('div', { class: 'head' }, shield(), 'Vazie Keep'),
      el('div', { class: 'body', style: 'padding-bottom:14px' }, text),
    );
    this.#layer.append(card);
    this.#banner = card;
    setTimeout(() => {
      if (this.#banner === card) this.hideBanner();
    }, 3500);
  }

  // ---- modal (passkeys)

  hideModal(): void {
    this.#modal?.remove();
    this.#modal = null;
  }

  showModal(config: {
    title: string;
    lines: string[];
    note?: string;
    choices?: { id: string; label: string; sub: string }[];
    primary: string;
    other: string;
    onPrimary: (choice: string | null) => void;
    onOther: () => void;
    onCancel: () => void;
  }): void {
    this.hideModal();
    this.#mount();
    const wrap = el('div', {});
    const backdrop = el('div', { class: 'backdrop' });
    const card = el('div', {
      class: 'card',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-label': config.title,
    });
    card.style.top = '50%';
    card.style.left = '50%';
    card.style.transform = 'translate(-50%, -50%)';
    card.style.width = 'min(380px, calc(100vw - 32px))';
    card.append(el('div', { class: 'head' }, shield(), config.title));
    const body = el('div', { class: 'body' });
    for (const line of config.lines) body.append(el('div', {}, line));
    if (config.note)
      body.append(el('div', { class: 'note', style: 'padding:6px 0 0' }, config.note));
    let chosen: string | null = config.choices?.[0]?.id ?? null;
    if (config.choices) {
      config.choices.forEach((choice, index) => {
        const input = el('input', { type: 'radio', name: 'passkey' });
        if (index === 0) input.checked = true;
        input.addEventListener('change', () => {
          chosen = choice.id;
        });
        body.append(
          el(
            'label',
            { class: 'radio' },
            input,
            el('span', {}, el('b', {}, choice.label), choice.sub ? ` · ${choice.sub}` : ''),
          ),
        );
      });
    }
    card.append(body);
    card.append(
      el(
        'div',
        { class: 'btns' },
        el(
          'button',
          {
            class: 'btn primary',
            type: 'button',
            onclick: () => {
              config.onPrimary(chosen);
            },
          },
          config.primary,
        ),
        el('button', { class: 'btn', type: 'button', onclick: config.onOther }, config.other),
      ),
    );
    backdrop.addEventListener('click', (event) => {
      if (event.isTrusted) config.onCancel();
    });
    wrap.append(backdrop, card);
    this.#layer.append(wrap);
    this.#modal = wrap;
    card.querySelector<HTMLElement>('.btn.primary')?.focus();
  }
}
