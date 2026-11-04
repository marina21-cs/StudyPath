import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { inspectRules, parseArgs } from '../scripts/dry-run-cloud-rules.mjs';

// Firebase documents a 256 KB ruleset source limit and a 250 KB compiled limit, and warns that deeply
// nested boolean chains can fail compilation. The emulator does not enforce either, so these budgets
// stop the committed file from drifting past what the cloud compiler is known to accept.
const SOURCE_BUDGET = 245_000;
const EXPRESSION_BUDGET = 80_000;
const source = await readFile(new URL('../database.rules.json', import.meta.url), 'utf8');

test('committed rules stay within the cloud ruleset source budget', () => {
  const report = inspectRules(source);
  assert.ok(report.sourceBytes <= SOURCE_BUDGET, `database.rules.json is ${report.sourceBytes} bytes; budget ${SOURCE_BUDGET}`);
  assert.ok(report.longestExpression <= EXPRESSION_BUDGET, `longest rule expression is ${report.longestExpression} chars; budget ${EXPRESSION_BUDGET}`);
  assert.match(report.canonicalSha256, /^[a-f0-9]{64}$/);
});

test('local inspection rejects rules that abandon the deny-all baseline or exceed the hard limit', () => {
  const rules = JSON.parse(source);
  assert.throws(() => inspectRules(JSON.stringify({ rules: { ...rules.rules, '.read': true } })), /LOCAL_RULES_BASELINE_INVALID/);
  assert.throws(() => inspectRules(JSON.stringify({ rules: { ...rules.rules, catalog: { '.read': 'auth != null', '.write': 'auth != null' } } })), /LOCAL_RULES_BASELINE_INVALID/);
  assert.throws(() => inspectRules('{'), /LOCAL_RULES_INVALID/);
  const padded = JSON.stringify({ rules: { ...rules.rules, padding: { '.validate': 'x'.repeat(256_000) } } });
  assert.throws(() => inspectRules(padded), /LOCAL_RULES_SOURCE_OVER_LIMIT/);
});

test('dry-run script only accepts the explicit check or exact-project run modes', () => {
  assert.equal(parseArgs(['--check']).mode, 'check');
  assert.equal(parseArgs(['--run', '--project', 'studypath-c8086']).mode, 'run');
  for (const args of [[], ['--run'], ['--run', '--project', 'other'], ['--check', '--run'], ['--release']]) assert.throws(() => parseArgs(args), /USAGE/);
});
