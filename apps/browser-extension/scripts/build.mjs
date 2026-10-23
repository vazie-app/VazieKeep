import { build } from 'vite';

const mode = process.argv.includes('--development') ? 'development' : 'production';

await build({ configFile: 'vite.config.ts', mode });
for (const entry of ['page', 'passkeys-main']) {
  process.env.KEEP_CONTENT_ENTRY = entry;
  await build({ configFile: 'vite.content.config.ts', mode });
}
