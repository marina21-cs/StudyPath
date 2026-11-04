import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove } from 'firebase/database';

// This namespace is distinct from both normal demo namespaces. Never clear the shared demo DB.
const projectId = 'demo-studypath-b09-prefs';
assert.notEqual(projectId, 'demo-studypath');
assert.notEqual(projectId, 'demo-studypath-default-rtdb');
const mainUrl = 'http://127.0.0.1:9000/.json?ns=demo-studypath-default-rtdb';
let env, beforeDigest;
const db = (uid = 'alice') => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const profile = () => ({ displayName: 'Synthetic learner', selectedCurriculumVersion: 'bsit_demo_v1', createdAt: 42, updatedAt: 42 });
const draft = () => ({ schemaVersion: 1, onboardingComplete: false });
const complete = () => ({ ...draft(), levelId: 'fixture_level', curriculumVersion: 'fixture_v1',
  subjectIds: { math: true }, classSectionId: 'unassigned_class', onboardingComplete: true });
// Positive approval metadata is fabricated for isolated rules tests only, never a production seed.
const options = () => ({ schemaVersion: 1, curriculumVersion: 'fixture_v1', levelId: 'fixture_level',
  subjectIds: { math: true, reading: true }, approved: true, approvalStatus: 'APPROVED' });
const pref = () => ref(db(), 'users/alice/profile/learnerPreferences');
async function mainDigest() {
  const response = await fetch(mainUrl, { headers: { Authorization: 'Bearer owner' } });
  assert.equal(response.status, 200);
  return createHash('sha256').update(JSON.stringify(await response.json())).digest('hex');
}
before(async () => {
  beforeDigest = await mainDigest();
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 9000,
    rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearDatabase();
  await set(ref(db(), 'users/alice/profile'), profile());
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), 'catalog/learnerPreferenceOptions/fixture_v1'), options());
  });
});
after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  assert.equal(await mainDigest(), beforeDigest, 'Shared demo data changed during isolated preference rules tests');
});

test('legacy profile remains valid and optional preference migration preserves its fields', async () => {
  const original = (await get(ref(db(), 'users/alice/profile'))).val();
  assert.equal(original.learnerPreferences, undefined);
  await assertSucceeds(set(pref(), draft()));
  const current = (await get(ref(db(), 'users/alice/profile'))).val();
  const { learnerPreferences, ...retained } = current;
  assert.deepEqual(retained, original); assert.deepEqual(learnerPreferences, draft());
});
test('owner can retain an incomplete stale selection without claiming completion', async () => {
  const value = { ...draft(), levelId: 'unselected_level', curriculumVersion: 'unknown_v1', subjectIds: { draft_subject: true } };
  await assertSucceeds(set(pref(), value));
  assert.deepEqual((await get(pref())).val(), value);
});
test('completion accepts only matching approved published options', async () => {
  await assertSucceeds(set(pref(), complete()));
  assert.deepEqual((await get(pref())).val(), complete());
});
test('unsupported curriculum level subjects and unapproved options cannot complete onboarding', async () => {
  for (const override of [{ curriculumVersion: 'unknown_v1' }, { levelId: 'wrong_level' }, { subjectIds: { unlisted: true } }]) {
    await assertFails(set(pref(), { ...complete(), ...override }));
  }
  for (const override of [{ approved: false }, { approvalStatus: 'DEMONSTRATION_ONLY' }, { schemaVersion: 2 }, { curriculumVersion: 'wrong_v1' }]) {
    await env.withSecurityRulesDisabled(context => set(ref(context.database(), 'catalog/learnerPreferenceOptions/fixture_v1'), { ...options(), ...override }));
    await assertFails(set(pref(), complete()));
  }
});
test('preference versions types unknown fields invalid keys and subject values are enforced', async () => {
  for (const value of [{ ...draft(), schemaVersion: 2 }, { ...draft(), schemaVersion: 1.5 }, { ...draft(), onboardingComplete: 'false' },
    { ...draft(), isTeacher: true }, { ...draft(), levelId: 'bad id' }, { ...draft(), subjectIds: { math: false } },
    { ...draft(), subjectIds: { math: 1 } }]) {
    await assertFails(set(pref(), value));
  }
});
test('draft map cardinality is client bounded while completed subjects require catalog membership', async () => {
  // RTDB RuleDataSnapshot has no child-count API; document rather than claim a server count cap.
  const subjectIds = Object.fromEntries(Array.from({ length: 21 }, (_, i) => ['s' + i, true]));
  await assertSucceeds(set(pref(), { ...draft(), subjectIds }));
  await assertFails(set(pref(), { ...complete(), subjectIds }));
});
test('cross UID unauthenticated publication and forged assignment writes remain denied', async () => {
  for (const uid of ['bob', null]) {
    await assertFails(get(ref(db(uid), 'users/alice/profile/learnerPreferences')));
    await assertFails(set(ref(db(uid), 'users/alice/profile/learnerPreferences'), draft()));
  }
  await assertFails(set(ref(db(), 'catalog/learnerPreferenceOptions/forged'), options()));
  await assertFails(set(ref(db(), 'users/alice/classMembership'), { unassigned_class: true }));
});
test('class section is a preference and grants no private assignment access', async () => {
  await assertSucceeds(set(pref(), complete()));
  await assertFails(get(ref(db(), 'classAssignments/unassigned_class')));
  await assertFails(set(ref(db(), 'classAssignments/unassigned_class'), { alice: true }));
});
test('removing completion fields fails atomically while explicit reset remains valid', async () => {
  await assertSucceeds(set(pref(), complete()));
  await assertFails(remove(ref(db(), 'users/alice/profile/learnerPreferences/levelId')));
  await assertFails(remove(ref(db(), 'users/alice/profile/learnerPreferences/subjectIds')));
  assert.deepEqual((await get(pref())).val(), complete());
  await assertSucceeds(set(pref(), draft()));
});
test('preference updates leave existing scores progress and immutable attempts unchanged', async () => {
  const records = { scores: { q1: { courseId: 'it101', assessmentId: 'q1', categoryId: 'quiz', planVersion: 'demo_v1', status: 'SCORED', possiblePoints: 20, earnedPoints: 0 } },
    reviewProgress: { module: { v1: { moduleId: 'module', version: 1, completed: false, lastSection: 0 } } },
    practiceAttempts: { attempt1: { moduleId: 'module', version: 1, correct: 1, total: 2, submittedAt: 42 } } };
  // Legacy scores are retained migration controls; new unbound writes must now fail.
  await assertFails(update(ref(db(), 'users/alice'), records));
  await env.withSecurityRulesDisabled(context => update(ref(context.database(), 'users/alice'), records));
  await assertSucceeds(update(ref(db(), 'users/alice/profile'), { learnerPreferences: draft(), updatedAt: 43 }));
  const actual = (await get(ref(db(), 'users/alice'))).val();
  for (const [key, value] of Object.entries(records)) assert.deepEqual(actual[key], value);
});
