import type { Access } from '../core/types.ts';

export type VaultPlan = 'plus' | 'free' | 'unverified';

export interface Rules {
  editable: Set<string> | null;
  canAdd: boolean;
  mustChooseMain: boolean;
  mainId: string | null;
}

export function planOf(access: Access): VaultPlan {
  if (access.plusFeatures) return 'plus';
  return access.state === 'free' ? 'free' : 'unverified';
}

/**
 * Several vaults without Keep+: every vault can be opened and read, one of them (the main vault) can be
 * edited, and none can be added. Several files of one database count as one vault.
 */
export function vaultRules(
  plan: VaultPlan,
  ids: string[],
  storedMain: string | null,
  fingerprints: Map<string, string>,
): Rules {
  const main = storedMain && ids.includes(storedMain) ? storedMain : null;
  if (plan === 'plus') return { editable: null, canAdd: true, mustChooseMain: false, mainId: main };
  const database = (id: string) => fingerprints.get(id) ?? id;
  if (new Set(ids.map(database)).size <= 1) {
    return { editable: null, canAdd: ids.length === 0, mustChooseMain: false, mainId: main };
  }
  const same = (id: string) => new Set(ids.filter((other) => database(other) === database(id)));
  if (plan === 'free') {
    return {
      editable: main ? same(main) : new Set(),
      canAdd: false,
      mustChooseMain: main === null,
      mainId: main,
    };
  }
  const standIn = main ?? ids[0];
  return {
    editable: standIn ? same(standIn) : new Set(),
    canAdd: false,
    mustChooseMain: false,
    mainId: main,
  };
}
