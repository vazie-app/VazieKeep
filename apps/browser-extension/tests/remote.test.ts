import assert from 'node:assert/strict';
import { test } from 'node:test';

import { parseLeaseKeys } from '../src/host/leaseKeys.ts';
import { isOpenable, parseFaq, parseLinks } from '../src/host/remoteParse.ts';
import { sanitizeSettings } from '../src/shared/settings.ts';
import { DEFAULT_SETTINGS } from '../src/shared/model.ts';

void test('only https pages and mail addresses are ever opened', () => {
  assert.equal(isOpenable('https://vazie.app/keep'), true);
  assert.equal(isOpenable('mailto:support@vazie.app'), true);
  assert.equal(isOpenable('http://vazie.app'), false);
  assert.equal(isOpenable('javascript:alert(1)'), false);
  assert.equal(isOpenable('https://vazie.app/a b'), false);
  assert.equal(isOpenable('mailto:@x'), false);
});

void test('links: one that cannot be shown is dropped on its own', () => {
  const links = parseLinks({
    links: [
      { key: 'a', section: 'AUTHOR', url: 'https://t.me/x', title: { ru: 'Р', en: 'E' } },
      { key: 'b', section: 'NOPE', url: 'https://t.me/x', title: { ru: 'Р', en: 'E' } },
      { key: 'c', section: 'HELP', url: 'http://t.me/x', title: { ru: 'Р', en: 'E' } },
      { key: 'd', section: 'HELP', url: 'https://t.me/y', title: { ru: 'Р' } },
    ],
  });
  assert.deepEqual(
    links?.map((l) => l.key),
    ['a'],
  );
  assert.equal(parseLinks({ links: [] }), null);
  assert.equal(parseLinks('nope'), null);
});

void test('faq: Russian is required, English optional, a bad link drops only the link', () => {
  const items = parseFaq({
    items: [
      {
        key: 'q1',
        question: { ru: 'В?' },
        answer: { ru: 'О', en: 'A' },
        link: { url: 'javascript:1', label: { ru: 'х' } },
      },
      { key: 'q2', question: { en: 'Only English' }, answer: { ru: 'О' } },
      {
        key: 'q3',
        question: { ru: 'В?' },
        answer: { ru: 'О' },
        link: { url: '/legal/refunds', label: { ru: 'Возвраты' } },
      },
    ],
  });
  assert.deepEqual(
    items?.map((i) => i.key),
    ['q1', 'q3'],
  );
  assert.ok(items);
  assert.equal(items[0]?.link, undefined);
  assert.equal(items[1]?.link?.url, '/legal/refunds');
  assert.deepEqual(parseFaq({ items: [] }), []);
  assert.equal(
    parseFaq({ items: [{ key: 'x' }] }),
    null,
    'questions with none usable are not believed',
  );
});

void test('lease keys are read as kid:publicKey, comments ignored', () => {
  assert.deepEqual(parseLeaseKeys('# note\nk1:abc\n\nk2:def, k3:ghi'), [
    { kid: 'k1', publicKey: 'abc' },
    { kid: 'k2', publicKey: 'def' },
    { kid: 'k3', publicKey: 'ghi' },
  ]);
  assert.deepEqual(parseLeaseKeys('broken\na:b:c'), []);
});

void test('settings read from storage are made safe', () => {
  const clean = sanitizeSettings({
    theme: 'neon',
    autoLock: '7',
    clipboardClear: 99,
    autofill: 'yes',
    neverSave: ['a.test', 5],
    generator: { length: 9999, lowercase: false, uppercase: false, digits: false, symbols: false },
  } as never);
  assert.equal(clean.theme, 'system');
  assert.equal(clean.autoLock, DEFAULT_SETTINGS.autoLock);
  assert.equal(clean.clipboardClear, DEFAULT_SETTINGS.clipboardClear);
  assert.equal(clean.autofill, true);
  assert.deepEqual(clean.neverSave, ['a.test']);
  assert.deepEqual(
    clean.generator,
    DEFAULT_SETTINGS.generator,
    'no character set means the defaults',
  );
  assert.deepEqual(sanitizeSettings(undefined), DEFAULT_SETTINGS);
});
