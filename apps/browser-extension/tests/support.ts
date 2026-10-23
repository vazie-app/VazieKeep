import { readFileSync } from 'node:fs';

import * as wasm from '../../../core/keep-core-wasm/pkg/keep_core_wasm.js';
import { createKeepCore, type KeepCore } from '../src/core/createCore.ts';

wasm.initSync({
  module: readFileSync(
    new URL('../../../core/keep-core-wasm/pkg/keep_core_wasm_bg.wasm', import.meta.url),
  ),
});

export const core: KeepCore = createKeepCore(wasm);
export const FAST_KDF = { memoryKib: 8192, iterations: 1, parallelism: 1 };
export const text = (value: string): Uint8Array => new TextEncoder().encode(value);

export function draft(title: string, overrides: Record<string, unknown> = {}) {
  return {
    title,
    username: 'user',
    password: 'pw',
    url: '',
    notes: '',
    customFields: [],
    tags: [],
    favorite: false,
    expiresAt: null,
    ...overrides,
  };
}
