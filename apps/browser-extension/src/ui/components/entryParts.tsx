import { useEffect, useState } from 'preact/hooks';
import { plain, type EntrySummary } from '../../core/types';
import { Icon } from '../icons';
import { DEFAULT_ENTRY_ICON, DEFAULT_GROUP_ICON, itemIconOf } from '../icons';
import { host } from '../state/client';
import { useT } from '../state/hooks';
import { initialOf, monogramTone, strength } from '../util';

const customIcons = new Map<string, string>();
const loading = new Map<string, Promise<string | null>>();

export function clearCustomIcons(): void {
  for (const url of customIcons.values()) URL.revokeObjectURL(url);
  customIcons.clear();
  loading.clear();
}

function useCustomIcon(vaultId: string | null, iconId: string | null): string | null {
  const key = vaultId && iconId ? `${vaultId}:${iconId}` : null;
  const [url, setUrl] = useState<string | null>(key ? (customIcons.get(key) ?? null) : null);
  useEffect(() => {
    if (!key || !iconId) {
      setUrl(null);
      return;
    }
    const known = customIcons.get(key);
    if (known) {
      setUrl(known);
      return;
    }
    const life = new AbortController();
    let pending = loading.get(key);
    if (!pending) {
      pending = host
        .customIcon(iconId)
        .then((bytes) => {
          if (!bytes) return null;
          const made = URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'image/png' }));
          customIcons.set(key, made);
          return made;
        })
        .catch(() => null);
      loading.set(key, pending);
    }
    void pending.then((made) => {
      if (!life.signal.aborted) setUrl(made);
    });
    return () => {
      life.abort();
    };
  }, [key, iconId]);
  return url;
}

export function Monogram({ text, seed, size = 40 }: { text: string; seed: string; size?: number }) {
  const tone = monogramTone(seed);
  return (
    <span
      class="monogram"
      style={{
        width: `${size}px`,
        height: `${size}px`,
        background: `var(--mono-${tone})`,
        color: `var(--mono-${tone}-on)`,
        fontSize: `${Math.round(size * 0.42)}px`,
      }}
      aria-hidden="true"
    >
      {initialOf(text)}
    </span>
  );
}

export function EntryIcon({
  entry,
  vaultId,
  size = 40,
}: {
  entry: EntrySummary;
  vaultId: string | null;
  size?: number;
}) {
  const image = useCustomIcon(vaultId, entry.customIcon);
  if (image) {
    return (
      <span class="tile" style={{ width: `${size}px`, height: `${size}px` }} aria-hidden="true">
        <img src={image} alt="" width={size - 14} height={size - 14} />
      </span>
    );
  }
  if (entry.icon !== DEFAULT_ENTRY_ICON) {
    const icon = itemIconOf(entry.icon, DEFAULT_ENTRY_ICON);
    return (
      <span
        class="tile tile-accent"
        style={{ width: `${size}px`, height: `${size}px` }}
        aria-hidden="true"
      >
        <Icon name={icon.icon} size={Math.round(size * 0.5)} />
      </span>
    );
  }
  const title = plain(entry.title);
  return <Monogram text={title} seed={entry.id} size={size} />;
}

export function GroupIcon({ icon, size = 36 }: { icon: number; size?: number }) {
  const found = itemIconOf(icon, DEFAULT_GROUP_ICON);
  return (
    <span
      class="tile tile-accent"
      style={{ width: `${size}px`, height: `${size}px` }}
      aria-hidden="true"
    >
      <Icon name={found.icon} size={Math.round(size * 0.5)} />
    </span>
  );
}

export function StrengthMeter({ password }: { password: string }) {
  const t = useT();
  const estimate = strength(password);
  if (!estimate) return null;
  const labels = [
    t('strength.fragile'),
    t('strength.fair'),
    t('strength.sturdy'),
    t('strength.strong'),
    t('strength.formidable'),
  ] as const;
  return (
    <div class="meter" role="img" aria-label={`${labels[estimate.level]} · ${estimate.bits} bit`}>
      <div class="meter-bars">
        {[0, 1, 2, 3, 4].map((i) => (
          <span
            key={i}
            class={`meter-bar${i <= estimate.level ? ` meter-on meter-${estimate.level}` : ''}`}
          />
        ))}
      </div>
      <span class="meter-label">
        {labels[estimate.level]} · {estimate.bits} bit
      </span>
    </div>
  );
}

/** Colours the characters of a secret: letters plain, digits in the accent, symbols in the caution colour. */
export function SecretText({ value }: { value: string }) {
  return (
    <span class="secret" translate={false}>
      {Array.from(value).map((ch, i) => (
        <span
          key={i}
          class={/[0-9]/.test(ch) ? 'secret-digit' : /[A-Za-z]/.test(ch) ? '' : 'secret-symbol'}
        >
          {ch}
        </span>
      ))}
    </span>
  );
}
