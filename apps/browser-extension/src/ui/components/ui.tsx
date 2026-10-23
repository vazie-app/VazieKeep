import type { ComponentChildren, TargetedEvent } from 'preact';
import { useEffect, useId, useRef, useState } from 'preact/hooks';
import { Icon, type IconName } from '../icons';
import { useT } from '../state/hooks';

type ButtonVariant = 'action' | 'tonal' | 'raised' | 'text' | 'caution';

interface ButtonProps {
  variant?: ButtonVariant | undefined;
  size?: 'large' | 'medium' | 'pill' | undefined;
  icon?: IconName | undefined;
  busy?: boolean | undefined;
  disabled?: boolean | undefined;
  full?: boolean | undefined;
  type?: 'button' | 'submit' | undefined;
  onClick?: ((event: MouseEvent) => void) | undefined;
  children?: ComponentChildren | undefined;
  title?: string | undefined;
  class?: string | undefined;
}

export function Button({
  variant = 'raised',
  size = 'medium',
  icon,
  busy = false,
  disabled = false,
  full = false,
  type = 'button',
  onClick,
  children,
  title,
  class: extra,
}: ButtonProps) {
  return (
    <button
      type={type}
      class={`btn btn-${variant} btn-${size}${full ? ' btn-full' : ''}${extra ? ` ${extra}` : ''}`}
      disabled={disabled || busy}
      aria-busy={busy}
      title={title}
      onClick={onClick}
    >
      {busy ? <Spinner size={16} /> : icon ? <Icon name={icon} size={18} /> : null}
      {children ? <span>{children}</span> : null}
    </button>
  );
}

interface IconButtonProps {
  icon: IconName;
  label: string;
  onClick?: ((event: MouseEvent) => void) | undefined;
  tone?: 'default' | 'accent' | 'caution' | undefined;
  disabled?: boolean | undefined;
  filled?: boolean | undefined;
  pressed?: boolean | undefined;
  size?: number | undefined;
}

export function IconButton({
  icon,
  label,
  onClick,
  tone = 'default',
  disabled,
  filled,
  pressed,
  size = 20,
}: IconButtonProps) {
  return (
    <button
      type="button"
      class={`icon-btn icon-btn-${tone}`}
      aria-label={label}
      title={label}
      disabled={disabled}
      aria-pressed={pressed}
      onClick={onClick}
    >
      <Icon name={icon} size={size} filled={filled === true} />
    </button>
  );
}

export function Spinner({ size = 20 }: { size?: number }) {
  return <span class="spinner" style={{ width: `${size}px`, height: `${size}px` }} role="status" />;
}

interface FieldProps {
  label: string;
  value: string;
  onInput: (value: string) => void;
  type?: 'text' | 'email' | 'url' | 'password' | 'search' | 'number' | undefined;
  placeholder?: string | undefined;
  error?: string | null | undefined;
  hint?: string | undefined;
  autofocus?: boolean | undefined;
  mono?: boolean | undefined;
  multiline?: boolean | undefined;
  rows?: number | undefined;
  autocomplete?: string | undefined;
  trailing?: ComponentChildren | undefined;
  disabled?: boolean | undefined;
  maxLength?: number | undefined;
  onEnter?: (() => void) | undefined;
  name?: string | undefined;
  inputMode?: 'numeric' | 'text' | 'email' | undefined;
}

export function TextField({
  label,
  value,
  onInput,
  type = 'text',
  placeholder,
  error,
  hint,
  autofocus,
  mono,
  multiline,
  rows = 3,
  autocomplete = 'off',
  trailing,
  disabled,
  maxLength,
  onEnter,
  name,
  inputMode,
}: FieldProps) {
  const id = useId();
  const ref = useRef<HTMLInputElement & HTMLTextAreaElement>(null);
  useEffect(() => {
    if (autofocus) ref.current?.focus();
  }, [autofocus]);
  const common = {
    id,
    ref,
    value,
    placeholder,
    disabled,
    maxLength,
    name,
    inputMode,
    autocomplete,
    spellcheck: false,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error || hint ? `${id}-note` : undefined,
    class: `field-input${mono ? ' mono' : ''}`,
    onInput: (event: TargetedEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      onInput(event.currentTarget.value);
    },
    onKeyDown: (event: KeyboardEvent) => {
      if (event.key === 'Enter' && onEnter && !(multiline && !event.metaKey && !event.ctrlKey)) {
        event.preventDefault();
        onEnter();
      }
    },
  };
  return (
    <div class={`field${error ? ' field-error' : ''}${disabled ? ' field-disabled' : ''}`}>
      <label for={id} class="field-label">
        {label}
      </label>
      <div class="field-box">
        {multiline ? <textarea {...common} rows={rows} /> : <input {...common} type={type} />}
        {trailing ? <div class="field-trailing">{trailing}</div> : null}
      </div>
      {error || hint ? (
        <p id={`${id}-note`} class={`field-note${error ? ' field-note-error' : ''}`}>
          {error ?? hint}
        </p>
      ) : null}
    </div>
  );
}

export function SecretField(
  props: Omit<FieldProps, 'type' | 'trailing'> & { extra?: ComponentChildren },
) {
  const t = useT();
  const [shown, setShown] = useState(false);
  const { extra, ...rest } = props;
  return (
    <TextField
      {...rest}
      type={shown ? 'text' : 'password'}
      mono
      autocomplete="off"
      trailing={
        <>
          {extra}
          <IconButton
            icon={shown ? 'eyeOff' : 'eye'}
            label={t(shown ? 'action.hidePassword' : 'action.showPassword')}
            onClick={() => {
              setShown(!shown);
            }}
          />
        </>
      }
    />
  );
}

export function Card({
  children,
  class: extra,
  tone,
}: {
  children: ComponentChildren;
  class?: string | undefined;
  tone?: 'plain' | 'accent' | undefined;
}) {
  return (
    <section class={`card${tone === 'accent' ? ' card-accent' : ''}${extra ? ` ${extra}` : ''}`}>
      {children}
    </section>
  );
}

export function SectionLabel({ children }: { children: ComponentChildren }) {
  return <h2 class="section-label">{children}</h2>;
}

interface RowProps {
  title: ComponentChildren;
  subtitle?: ComponentChildren | undefined;
  leading?: ComponentChildren | undefined;
  trailing?: ComponentChildren | undefined;
  onClick?: (() => void) | undefined;
  selected?: boolean | undefined;
  chevron?: boolean | undefined;
  disabled?: boolean | undefined;
  label?: string | undefined;
  wrap?: boolean | undefined;
}

export function Row({
  title,
  subtitle,
  leading,
  trailing,
  onClick,
  selected,
  chevron,
  disabled,
  label,
  wrap,
}: RowProps) {
  const body = (
    <>
      {leading ? <div class="row-leading">{leading}</div> : null}
      <div class="row-text">
        <div class="row-title">{title}</div>
        {subtitle ? <div class="row-subtitle">{subtitle}</div> : null}
      </div>
      {trailing ? <div class="row-trailing">{trailing}</div> : null}
      {chevron ? <Icon name="chevronRight" size={18} class="row-chevron" /> : null}
    </>
  );
  if (!onClick) return <div class={`row${wrap ? ' row-wrap' : ''}`}>{body}</div>;
  return (
    <button
      type="button"
      class={`row row-button${selected ? ' row-selected' : ''}${wrap ? ' row-wrap' : ''}`}
      onClick={onClick}
      disabled={disabled}
      aria-current={selected ? 'true' : undefined}
      aria-label={label}
    >
      {body}
    </button>
  );
}

export function Badge({
  children,
  tone = 'neutral',
}: {
  children: ComponentChildren;
  tone?: 'neutral' | 'accent' | 'caution' | 'danger' | undefined;
}) {
  return <span class={`badge badge-${tone}`}>{children}</span>;
}

export function Chip({
  children,
  selected,
  onClick,
  icon,
  onRemove,
  removeLabel,
}: {
  children: ComponentChildren;
  selected?: boolean | undefined;
  onClick?: (() => void) | undefined;
  icon?: IconName | undefined;
  onRemove?: (() => void) | undefined;
  removeLabel?: string | undefined;
}) {
  return (
    <span class={`chip${selected ? ' chip-selected' : ''}`}>
      <button
        type="button"
        class="chip-main"
        onClick={onClick}
        aria-pressed={selected}
        disabled={!onClick}
      >
        {icon ? <Icon name={icon} size={14} /> : null}
        {children}
      </button>
      {onRemove ? (
        <button type="button" class="chip-remove" onClick={onRemove} aria-label={removeLabel}>
          <Icon name="close" size={12} />
        </button>
      ) : null}
    </span>
  );
}

export function Banner({
  tone = 'info',
  children,
  action,
  onAction,
  icon,
  onDismiss,
}: {
  tone?: 'info' | 'caution' | 'danger' | 'success' | undefined;
  children: ComponentChildren;
  action?: string | undefined;
  onAction?: (() => void) | undefined;
  icon?: IconName | undefined;
  onDismiss?: (() => void) | undefined;
}) {
  const t = useT();
  return (
    <div class={`banner banner-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon
        name={
          icon ??
          (tone === 'danger' || tone === 'caution'
            ? 'warning'
            : tone === 'success'
              ? 'check'
              : 'info')
        }
        size={18}
      />
      <div class="banner-text">{children}</div>
      {action && onAction ? (
        <button type="button" class="banner-action" onClick={onAction}>
          {action}
        </button>
      ) : null}
      {onDismiss ? (
        <button
          type="button"
          class="banner-action"
          onClick={onDismiss}
          aria-label={t('action.dismiss')}
        >
          <Icon name="close" size={14} />
        </button>
      ) : null}
    </div>
  );
}

export function Switch({
  checked,
  onChange,
  label,
  hint,
  disabled,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  hint?: string | undefined;
  disabled?: boolean | undefined;
}) {
  const id = useId();
  return (
    <label class={`switch${disabled ? ' switch-disabled' : ''}`} for={id}>
      <span class="switch-text">
        <span class="switch-label">{label}</span>
        {hint ? <span class="switch-hint">{hint}</span> : null}
      </span>
      <input
        id={id}
        type="checkbox"
        role="switch"
        checked={checked}
        disabled={disabled}
        onChange={(event) => {
          onChange(event.currentTarget.checked);
        }}
      />
      <span class="switch-track" aria-hidden="true" />
    </label>
  );
}

export function Segmented<T extends string | number>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  label: string;
}) {
  return (
    <div class="segmented" role="radiogroup" aria-label={label}>
      {options.map((option) => (
        <button
          key={String(option.value)}
          type="button"
          role="radio"
          aria-checked={option.value === value}
          class={`segment${option.value === value ? ' segment-on' : ''}`}
          onClick={() => {
            onChange(option.value);
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function SelectRow<T extends string | number>({
  label,
  value,
  options,
  onChange,
  hint,
}: {
  label: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  hint?: string | undefined;
}) {
  const id = useId();
  return (
    <div class="select-row">
      <label for={id} class="select-label">
        {label}
      </label>
      <select
        id={id}
        value={String(value)}
        onChange={(event) => {
          const picked = options.find((o) => String(o.value) === event.currentTarget.value);
          if (picked) onChange(picked.value);
        }}
      >
        {options.map((option) => (
          <option key={String(option.value)} value={String(option.value)}>
            {option.label}
          </option>
        ))}
      </select>
      {hint ? <p class="field-note">{hint}</p> : null}
    </div>
  );
}

export function TopBar({
  title,
  onBack,
  actions,
  subtitle,
}: {
  title: ComponentChildren;
  onBack?: (() => void) | undefined;
  actions?: ComponentChildren | undefined;
  subtitle?: ComponentChildren | undefined;
}) {
  const t = useT();
  return (
    <header class="topbar">
      {onBack ? <IconButton icon="back" label={t('action.back')} onClick={onBack} /> : null}
      <div class="topbar-text">
        <h1 class="topbar-title">{title}</h1>
        {subtitle ? <div class="topbar-subtitle">{subtitle}</div> : null}
      </div>
      {actions ? <div class="topbar-actions">{actions}</div> : null}
    </header>
  );
}

export function Empty({
  icon,
  title,
  message,
  action,
}: {
  icon: IconName;
  title: string;
  message?: string | undefined;
  action?: ComponentChildren | undefined;
}) {
  return (
    <div class="empty">
      <div class="empty-icon">
        <Icon name={icon} size={28} />
      </div>
      <h2>{title}</h2>
      {message ? <p>{message}</p> : null}
      {action}
    </div>
  );
}

export function Loading({ label }: { label?: string }) {
  return (
    <div class="loading">
      <Spinner size={28} />
      {label ? <p>{label}</p> : null}
    </div>
  );
}
