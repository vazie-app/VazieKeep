// Builds a development build that trusts the QA lease key, then runs every real-browser test in turn.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { qaKey } from './qa.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const here = fileURLToPath(new URL('.', import.meta.url));
const only = process.argv[2];
const build = spawnSync('node', ['scripts/build.mjs', '--development'], {
  cwd: root,
  stdio: 'inherit',
  env: { ...process.env, KEEP_QA_LEASE_KEYS: qaKey().env },
});
if (build.status !== 0) process.exit(build.status ?? 1);
let failed = 0;
for (const name of ['vault', 'site', 'quick-unlock', 'locks-and-clipboard']) {
  if (only && only !== name) continue;
  console.log(`\n== ${name}`);
  if (spawnSync('node', [`${here}${name}.mjs`], { stdio: 'inherit' }).status !== 0) failed += 1;
}
process.exit(failed === 0 ? 0 : 1);
