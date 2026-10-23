import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';
import { Atom, useAtom } from '../state/atom';
import { useT } from '../state/hooks';
import { Button } from './ui';
import { Icon } from '../icons';

export function Dialog({
  title,
  children,
  actions,
  onClose,
  wide,
}: {
  title: string;
  children: ComponentChildren;
  actions?: ComponentChildren | undefined;
  onClose?: (() => void) | undefined;
  wide?: boolean | undefined;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    return () => {
      dialog?.close();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      class={`dialog${wide ? ' dialog-wide' : ''}`}
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        onClose?.();
      }}
      onClick={(event) => {
        if (event.target === ref.current) onClose?.();
      }}
    >
      <div class="dialog-body">
        <h2 class="dialog-title">{title}</h2>
        <div class="dialog-content">{children}</div>
        {actions ? <div class="dialog-actions">{actions}</div> : null}
      </div>
    </dialog>
  );
}

export function Confirm({
  title,
  message,
  confirm,
  cancel,
  tone = 'action',
  busy,
  onConfirm,
  onCancel,
  children,
}: {
  title: string;
  message?: ComponentChildren | undefined;
  confirm: string;
  cancel?: string | undefined;
  tone?: 'action' | 'caution' | undefined;
  busy?: boolean | undefined;
  onConfirm: () => void;
  onCancel: () => void;
  children?: ComponentChildren | undefined;
}) {
  const t = useT();
  return (
    <Dialog
      title={title}
      onClose={onCancel}
      actions={
        <>
          <Button variant="text" onClick={onCancel} disabled={busy}>
            {cancel ?? t('action.cancel')}
          </Button>
          <Button
            variant={tone === 'caution' ? 'caution' : 'action'}
            onClick={onConfirm}
            busy={busy === true}
          >
            {confirm}
          </Button>
        </>
      }
    >
      {message ? <p class="dialog-message">{message}</p> : null}
      {children}
    </Dialog>
  );
}

export interface ToastMessage {
  id: number;
  text: string;
  tone: 'info' | 'danger';
}

export const toasts = new Atom<ToastMessage[]>([]);
let counter = 0;

export function showToast(text: string, tone: 'info' | 'danger' = 'info'): void {
  counter += 1;
  const id = counter;
  toasts.update((list) => [...list.slice(-2), { id, text, tone }]);
  setTimeout(
    () => {
      toasts.update((list) => list.filter((toast) => toast.id !== id));
    },
    tone === 'danger' ? 6000 : 3200,
  );
}

export function ToastHost() {
  const list = useAtom(toasts);
  return (
    <div class="toasts" aria-live="polite">
      {list.map((toast) => (
        <div key={toast.id} class={`toast toast-${toast.tone}`} role="status">
          <Icon name={toast.tone === 'danger' ? 'warning' : 'check'} size={16} />
          <span>{toast.text}</span>
        </div>
      ))}
    </div>
  );
}
