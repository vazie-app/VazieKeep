// Backend addresses never live in git. A production build reads VAZIE_ACCOUNT_ORIGIN, VAZIE_KEEP_ORIGIN and
// VAZIE_SITE_ORIGIN from the environment (CI secrets) or from local.properties next to package.json; a development build
// falls back to a backend on this computer.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const NAMES = {
  account: 'VAZIE_ACCOUNT_ORIGIN',
  keep: 'VAZIE_KEEP_ORIGIN',
  site: 'VAZIE_SITE_ORIGIN',
};
const DEVELOPMENT = {
  account: 'http://localhost:8080',
  keep: 'http://127.0.0.1:8080',
  site: 'http://localhost:3010',
};

function localProperties() {
  const file = fileURLToPath(new URL('../local.properties', import.meta.url));
  if (!existsSync(file)) return {};
  const values = {};
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Za-z0-9_.]+)\s*[=:]\s*(.*?)\s*$/.exec(line);
    if (match && !line.trimStart().startsWith('#')) values[match[1]] = match[2];
  }
  return values;
}

export function backendOrigins(mode) {
  const local = localProperties();
  const origins = {};
  for (const [key, name] of Object.entries(NAMES)) {
    const value = (process.env[name] ?? local[name] ?? '').trim().replace(/\/+$/, '');
    if (value) {
      origins[key] = value;
    } else if (mode === 'development') {
      origins[key] = DEVELOPMENT[key];
    } else {
      throw new Error(`${name} is not set: put it in the environment or in local.properties.`);
    }
    if (mode !== 'development' && !origins[key].startsWith('https://')) {
      throw new Error(`${name} must be an https:// address.`);
    }
  }
  return origins;
}
