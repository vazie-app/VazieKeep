import assert from 'node:assert/strict';
import { test } from 'node:test';

import type { EntrySummary, Text, VaultGroup } from '../src/core/types.ts';
import {
  buildIndex,
  matchEntries,
  pageOrigin,
  parseEntryUrl,
  planSave,
  sameUsername,
  titleFor,
  urlFor,
} from '../src/host/autofillIndex.ts';
import { core } from './support.ts';

const plainText = (value: string): Text => ({ kind: 'plain', value });

function summary(id: string, group: string, url: string, over: Partial<EntrySummary> = {}) {
  return {
    id,
    groupId: group,
    title: plainText(id),
    username: plainText('user'),
    url: plainText(url),
    tags: [],
    favorite: false,
    expiresAt: null,
    icon: 0,
    customIcon: null,
    passkey: null,
    hasPassword: true,
    hasNotes: false,
    hasTotp: false,
    hasProtectedFields: false,
    customFields: [],
    attachmentNames: [],
    ...over,
  };
}

function origin(url: string) {
  const found = pageOrigin(url);
  assert.ok(found, `${url} is not a page address`);
  return found;
}

const groups: VaultGroup[] = [
  { id: 'root', parentId: null, name: 'Root', icon: 48 },
  { id: 'bin', parentId: 'root', name: 'Recycle Bin', icon: 43 },
  { id: 'old', parentId: 'bin', name: 'Old', icon: 48 },
];

void test('an entry address is read the way the phone apps read it', () => {
  assert.deepEqual(parseEntryUrl('example.com/login'), {
    scheme: 'https',
    host: 'example.com',
    port: null,
  });
  assert.deepEqual(parseEntryUrl('http://Example.COM:8080/x'), {
    scheme: 'http',
    host: 'example.com',
    port: 8080,
  });
  assert.deepEqual(parseEntryUrl('https://user@example.com'), {
    scheme: 'https',
    host: 'example.com',
    port: null,
  });
  assert.equal(parseEntryUrl('ssh://host'), null);
  assert.equal(parseEntryUrl('  '), null);
  assert.equal(parseEntryUrl('androidapp://app.vazie'), null);
});

void test('an address with a Unicode host becomes punycode and cannot pose as an ASCII one', () => {
  assert.equal(parseEntryUrl('https://bücher.de')?.host, 'xn--bcher-kva.de');
  assert.equal(parseEntryUrl('https://pаypal.com')?.host, 'xn--pypal-4ve.com');
});

void test('matching: exact, subdomains, never look-alikes, parents or public suffixes', () => {
  const index = buildIndex(
    [
      summary('a', 'root', 'https://example.com'),
      summary('b', 'root', 'https://accounts.example.com'),
      summary('c', 'root', 'https://github.io'),
      summary('d', 'root', 'http://insecure.test'),
      summary('e', 'root', 'https://secure.test'),
      summary('f', 'root', 'https://example.com:8443'),
    ],
    groups,
    'bin',
  );
  const ids = (url: string) => matchEntries(core, index, origin(url)).map((m) => m.entry.id);
  assert.deepEqual(ids('https://example.com/login'), ['a']);
  assert.deepEqual(ids('https://sso.example.com'), ['a']);
  assert.deepEqual(ids('https://accounts.example.com'), ['b', 'a']);
  assert.deepEqual(ids('https://example.com.evil.test'), []);
  assert.deepEqual(ids('https://evilexample.com'), []);
  assert.deepEqual(ids('https://someone.github.io'), []);
  assert.deepEqual(ids('https://example.com:8443'), ['f']);
  assert.deepEqual(ids('http://secure.test'), [], 'an https entry is not offered to an http page');
  assert.deepEqual(
    ids('https://insecure.test'),
    ['d'],
    'an http entry may be offered to its https page',
  );
  assert.deepEqual(ids('http://insecure.test'), ['d']);
});

void test('the index leaves out the Recycle Bin and everything below it', () => {
  const index = buildIndex(
    [
      summary('keep', 'root', 'https://a.test'),
      summary('gone', 'bin', 'https://a.test'),
      summary('deeper', 'old', 'https://a.test'),
    ],
    groups,
    'bin',
  );
  assert.deepEqual(
    index.map((e) => e.id),
    ['keep'],
  );
});

void test('favourites come first, then alphabetical order', () => {
  const index = buildIndex(
    [
      summary('zeta', 'root', 'https://a.test', { title: plainText('Zeta') }),
      summary('alpha', 'root', 'https://a.test', { title: plainText('alpha') }),
      summary('fav', 'root', 'https://a.test', {
        title: plainText('Mid'),
        favorite: true,
      }),
    ],
    groups,
    null,
  );
  assert.deepEqual(
    matchEntries(core, index, origin('https://a.test')).map((m) => m.entry.id),
    ['fav', 'alpha', 'zeta'],
  );
});

void test('saving: update the one entry, choose among several, create otherwise', () => {
  const index = buildIndex(
    [
      summary('one', 'root', 'https://a.test', {
        username: plainText('Ann@Mail.com'),
      }),
      summary('two', 'root', 'https://a.test', { username: plainText('bob') }),
      summary('three', 'root', 'https://a.test', { username: plainText('bob') }),
    ],
    groups,
    null,
  );
  const matches = matchEntries(core, index, origin('https://a.test'));
  assert.equal(planSave(matches, 'ann@mail.com').kind, 'update');
  assert.equal(planSave(matches, 'bob').kind, 'choose');
  const created = planSave(matches, 'carol');
  assert.ok(created.kind === 'create');
  assert.equal(created.alternatives.length, 3);
  assert.equal(planSave([], '').kind, 'create');
  assert.equal(
    planSave(matches.slice(0, 1), '').kind,
    'update',
    'a password-change form has no username',
  );
});

void test('usernames: addresses ignore case, nothing else is merged', () => {
  assert.equal(sameUsername('Ann@x.io', 'ann@X.io'), true);
  assert.equal(sameUsername('Ann', 'ann'), false);
  assert.equal(sameUsername('', ''), false);
});

void test('a new entry is saved for the page origin, never downgraded', () => {
  const page = origin('https://www.example.com:8443/a?b=1');
  assert.equal(urlFor(page), 'https://www.example.com:8443');
  assert.equal(titleFor(page), 'example.com');
});
