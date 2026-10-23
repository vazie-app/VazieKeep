import init, * as wasm from '@keep-core-wasm/keep_core_wasm.js';
import wasmUrl from '@keep-core-wasm/keep_core_wasm_bg.wasm?url';
import { createKeepCore, type KeepCore } from './createCore.ts';

export type { KeepCore, OpenOptions } from './createCore.ts';

let loading: Promise<KeepCore> | undefined;

export function loadKeepCore(): Promise<KeepCore> {
  loading ??= init({ module_or_path: wasmUrl }).then(() => createKeepCore(wasm));
  return loading;
}
