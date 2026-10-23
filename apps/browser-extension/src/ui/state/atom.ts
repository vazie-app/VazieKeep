import { useEffect, useState } from 'preact/hooks';

export class Atom<T> {
  #value: T;
  readonly #listeners = new Set<(value: T) => void>();

  constructor(initial: T) {
    this.#value = initial;
  }

  get(): T {
    return this.#value;
  }

  set(next: T): void {
    if (Object.is(next, this.#value)) return;
    this.#value = next;
    for (const listener of this.#listeners) listener(next);
  }

  update(change: (current: T) => T): void {
    this.set(change(this.#value));
  }

  subscribe(listener: (value: T) => void): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }
}

export function useAtom<T>(atom: Atom<T>): T {
  const [value, setValue] = useState<T>(() => atom.get());
  useEffect(() => {
    const adopt = (next: T) => {
      setValue(() => next);
    };
    adopt(atom.get());
    return atom.subscribe(adopt);
  }, [atom]);
  return value;
}
