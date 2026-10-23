import type { TrustedKey } from '../core/types.ts';

export function parseLeaseKeys(text: string): TrustedKey[] {
  return text
    .split(/[\n,]/)
    .map((line) => line.trim())
    .filter((line) => line !== '' && !line.startsWith('#'))
    .flatMap((entry) => {
      const [kid, publicKey, ...rest] = entry.split(':');
      return kid && publicKey && rest.length === 0 ? [{ kid, publicKey }] : [];
    });
}
