import assert from 'node:assert/strict';
import { test } from 'node:test';

import { planOf, vaultRules } from '../src/host/rules.ts';

const none = new Map<string, string>();

void test('with Keep+ every vault is edited and more can be added', () => {
  const rules = vaultRules('plus', ['a', 'b'], null, none);
  assert.equal(rules.editable, null);
  assert.equal(rules.canAdd, true);
  assert.equal(rules.mustChooseMain, false);
});

void test('without Keep+ one vault is the whole library and none can be added', () => {
  assert.deepEqual(vaultRules('free', ['a'], null, none), {
    editable: null,
    canAdd: false,
    mustChooseMain: false,
    mainId: null,
  });
  assert.equal(vaultRules('free', [], null, none).canAdd, true);
});

void test('several vaults without Keep+ ask for a main vault once, then only it is edited', () => {
  const asked = vaultRules('free', ['a', 'b'], null, none);
  assert.equal(asked.mustChooseMain, true);
  assert.deepEqual([...(asked.editable ?? [])], []);
  const chosen = vaultRules('free', ['a', 'b'], 'b', none);
  assert.equal(chosen.mustChooseMain, false);
  assert.deepEqual([...(chosen.editable ?? [])], ['b']);
  assert.equal(chosen.canAdd, false);
});

void test('a main vault that was removed is asked for again', () => {
  assert.equal(vaultRules('free', ['a', 'b'], 'gone', none).mustChooseMain, true);
});

void test('copies of one database count as one vault', () => {
  const same = new Map([
    ['a', 'fp'],
    ['b', 'fp'],
  ]);
  const rules = vaultRules('free', ['a', 'b'], null, same);
  assert.equal(rules.mustChooseMain, false);
  assert.equal(rules.editable, null);
});

void test('while a lapsed lease is unchecked, the first vault stands in for the main one', () => {
  const rules = vaultRules('unverified', ['a', 'b'], null, none);
  assert.equal(rules.mustChooseMain, false);
  assert.deepEqual([...(rules.editable ?? [])], ['a']);
});

void test('the plan follows the access state', () => {
  const base = {
    plusFeatures: false,
    needsOnlineVerification: false,
    leaseValidUntil: null,
    observedDeviceTime: 0,
  };
  assert.equal(planOf({ ...base, state: 'free' }), 'free');
  assert.equal(planOf({ ...base, state: 'plusActive', plusFeatures: true }), 'plus');
  assert.equal(planOf({ ...base, state: 'plusExpired' }), 'unverified');
});
