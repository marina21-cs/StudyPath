import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { completionIndexRules, createLocalFamilyRules, familyRuleAdditions } from '../firebase-family-rules.mjs';
import { createFamilyFixture } from '../family-fixture.mjs';

test('production family rules never contain the synthetic enabling branch', () => {
  const rules = JSON.stringify(familyRuleAdditions());
  assert.ok(rules.includes('HUMAN_REVIEW_RECORDED'));
  assert.ok(!rules.includes('SYNTHETIC_LOCAL_ONLY'));
  assert.ok(!rules.includes('numChildren'));
});
test('committed database.rules.json matches the generated sharing and completion-index sections exactly', async () => {
  // Reviews happen on the generator source; the committed JSON must be its unedited output.
  const committed = JSON.parse(await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8')).rules;
  for (const [key, generated] of Object.entries(familyRuleAdditions())) assert.deepEqual(committed[key], generated, key);
  assert.deepEqual(committed.users.$uid.lessonCompletionIndex, completionIndexRules());
});
test('local family rules require an isolated demo namespace and bind it exactly', async () => {
  const source = await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8');
  for (const namespace of ['studypath-c8086', 'demo-studypath', 'demo-studypath-x\' || true']) {
    assert.throws(() => createLocalFamilyRules(source, namespace));
  }
  const rules = createLocalFamilyRules(source, 'demo-studypath-family-rules');
  assert.ok(rules.includes('SYNTHETIC_LOCAL_ONLY'));
  assert.ok(rules.includes("== 'demo-studypath-family-rules'"));
  assert.ok(rules.includes("== 'mock_computing_v1'"));
});
test('local fixture is explicit and account-free, with linked bounded identities', () => {
  const f = createFamilyFixture({ now: 2000000000000 });
  assert.equal(f.policy.evidenceMode, 'SYNTHETIC_LOCAL_ONLY');
  assert.equal(f.projection.expiresAt, f.grant.expiresAt);
  assert.equal(f.projection.summary.completedLessonCount, 0);
  assert.equal(f.index.count, 1);
  assert.equal(f.entry.runId, f.attempt.runId);
  assert.ok(Object.keys(f.trustedUpdates).every(path => !path.startsWith('users/')));
  assert.throws(() => createFamilyFixture({ ownerUid: 'same', recipientUid: 'same' }));
});
