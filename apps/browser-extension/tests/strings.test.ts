import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { translate } from '../src/shared/i18n.ts';
import { DICTIONARY } from '../src/shared/strings.ts';

const root = fileURLToPath(new URL('../src/', import.meta.url));

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? sources(path) : /\.tsx?$/.test(name) ? [path] : [];
  });
}

void test('every string has both languages and matching placeholders', () => {
  for (const [key, [en, ru]] of Object.entries(DICTIONARY)) {
    assert.ok(en.trim() !== '' && ru.trim() !== '', `${key} is missing a language`);
    const names = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
    assert.deepEqual(
      names(en),
      names(ru),
      `${key} uses different placeholders in the two languages`,
    );
  }
});

void test('every key a screen asks for exists', () => {
  const missing: string[] = [];
  for (const file of sources(root)) {
    if (file.endsWith('strings.ts')) continue;
    const text = readFileSync(file, 'utf8');
    for (const match of text.matchAll(/\bt\(\s*'([a-zA-Z0-9_.]+)'/g)) {
      const key = match[1] ?? '';
      if (!(key in DICTIONARY)) missing.push(`${file.slice(root.length)}: ${key}`);
    }
  }
  assert.deepEqual(missing, []);
});

void test('placeholders are filled and the language is chosen', () => {
  assert.equal(
    translate('en', 'copy.done', { seconds: 30 }),
    'Copied. It will be cleared from the clipboard in 30 s.',
  );
  assert.match(translate('ru', 'hub.forgetTitle', { name: 'Работа' }), /Работа/);
});
