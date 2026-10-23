import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
type BrowserTarget = 'chromium';

const target = (process.env.KEEP_BROWSER_TARGET ?? 'chromium') as BrowserTarget;
const entry = process.env.KEEP_CONTENT_ENTRY ?? 'page';
const here = (path: string) => fileURLToPath(new URL(path, import.meta.url));

// Content scripts are classic scripts: one self-contained file each, no modules.
export default defineConfig(({ mode }) => ({
  root: 'src',
  publicDir: false,
  define: { __KEEP_DEBUG__: JSON.stringify(mode === 'development') },
  build: {
    outDir: here(`dist/${target}`),
    emptyOutDir: false,
    target: 'chrome120',
    sourcemap: mode === 'development',
    minify: mode !== 'development',
    lib: {
      entry: here(`src/content/${entry}.ts`),
      formats: ['iife'],
      name: `keepContent_${entry.replaceAll('-', '_')}`,
      fileName: () => `content/${entry}.js`,
    },
  },
}));
