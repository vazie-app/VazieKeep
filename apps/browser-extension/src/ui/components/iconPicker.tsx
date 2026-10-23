import { useState } from 'preact/hooks';
import { Icon, ITEM_ICONS, itemIconOf } from '../icons';
import { useT } from '../state/hooks';
import type { StringKey } from '../../shared/i18n';
import { Dialog } from './dialogs';
import { Button, TextField } from './ui';

export function IconPicker({
  title,
  current,
  onPick,
  onClose,
}: {
  title: string;
  current: number;
  onPick: (icon: number) => void | Promise<void>;
  onClose: () => void;
}) {
  const t = useT();
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const selected = itemIconOf(current, 0).id;
  const needle = query.trim().toLowerCase();
  const shown = ITEM_ICONS.filter(
    (icon) =>
      needle === '' ||
      icon.keywords.includes(needle) ||
      t(icon.key as StringKey)
        .toLowerCase()
        .includes(needle),
  );
  return (
    <Dialog
      title={title}
      onClose={onClose}
      wide
      actions={
        <Button variant="text" onClick={onClose}>
          {t('action.cancel')}
        </Button>
      }
    >
      <TextField
        label={t('iconPicker.search')}
        value={query}
        onInput={setQuery}
        type="search"
        autofocus
      />
      {shown.length === 0 ? (
        <p class="caption">{t('iconPicker.none')}</p>
      ) : (
        <div class="icon-grid" role="listbox" aria-label={title}>
          {shown.map((icon) => (
            <button
              key={icon.id}
              type="button"
              role="option"
              aria-selected={icon.id === selected}
              class={`icon-choice${icon.id === selected ? ' icon-choice-on' : ''}`}
              disabled={busy}
              onClick={() => {
                setBusy(true);
                void Promise.resolve(onPick(icon.id)).finally(() => {
                  setBusy(false);
                });
              }}
            >
              <Icon name={icon.icon} size={22} />
              <span>{t(icon.key as StringKey)}</span>
            </button>
          ))}
        </div>
      )}
    </Dialog>
  );
}
