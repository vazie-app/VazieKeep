import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import { createManifest, type BrowserTarget } from './src/manifest';
import pkg from './package.json' with { type: 'json' };
import { backendOrigins } from './scripts/origins.mjs';

const target = (process.env.KEEP_BROWSER_TARGET ?? 'chromium') as BrowserTarget;
const here = (path: string) => fileURLToPath(new URL(path, import.meta.url));

export const sharedAliases = {
  '@keep-core-wasm': here('../../core/keep-core-wasm/pkg'),
  '@keep-lease': here('../../shared/keep-lease'),
  '@fonts': here('../../shared/fonts'),
};

function extensionFiles(): Plugin {
  return {
    name: 'keep-extension-files',
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'manifest.json',
        source: JSON.stringify(createManifest(target, pkg.version), null, 2),
      });
      for (const name of readdirSync(here('icons'))) {
        if (name.endsWith('.png')) {
          this.emitFile({
            type: 'asset',
            fileName: `icons/${name}`,
            source: readFileSync(here(`icons/${name}`)),
          });
        }
      }
    },
  };
}

export default defineConfig(({ mode }) => ({
  root: 'src',
  base: './',
  publicDir: false,
  resolve: { alias: sharedAliases },
  define: {
    // A release build never trusts a QA key; a development build trusts the ones named in the environment.
    __KEEP_DEBUG__: JSON.stringify(mode === 'development'),
    __VAZIE_ORIGINS__: JSON.stringify(backendOrigins(mode)),
    __QA_LEASE_KEYS__: JSON.stringify(
      mode === 'development' ? (process.env.KEEP_QA_LEASE_KEYS ?? '') : '',
    ),
  },
  oxc: {
    jsx: {
      runtime: 'automatic',
      importSource: 'preact',
    },
  },
  build: {
    outDir: here(`dist/${target}`),
    emptyOutDir: true,
    target: 'chrome120',
    sourcemap: mode === 'development',
    minify: mode !== 'development',
    modulePreload: false,
    rolldownOptions: {
      input: {
        popup: here('src/ui/popup.html'),
        tab: here('src/ui/tab.html'),
        panel: here('src/ui/panel.html'),
        offscreen: here('src/offscreen/index.html'),
        background: here('src/background/index.ts'),
      },
      output: {
        entryFileNames: (chunk) =>
          chunk.name === 'background' ? 'background.js' : 'assets/[name]-[hash].js',
      },
    },
  },
  plugins: [extensionFiles()],
}));
