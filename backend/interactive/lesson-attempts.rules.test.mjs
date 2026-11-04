import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, remove, update } from 'firebase/database';
import { academicBinding, seedAcademicFixture } from '../academics/policy-fixture.mjs';

// A separate project and port are mandatory: this suite clears only its own test namespace.
const projectId = 'demo-studypath-b11-rules';
const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
assert.equal(host, '127.0.0.1:19000', 'Run with the isolated B11 emulator configuration');
let env;
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const path = 'users/alice/lessonAttempts/run_1';
const hash = 'a'.repeat(64);
const indexPath = 'catalog/moduleIndex/fixture_v1/fixture_module/v1';
const historyPath = 'catalog/modulePublicationHistory/fixture_v1/fixture_module/v1';
const receiptPath = 'users/alice/offlinePackages/fixture_v1/fixture_module/v1';
const history = () => ({ schemaVersion: 1, curriculumVersion: 'fixture_v1', moduleId: 'fixture_module',
  version: 1, packageSha256: hash, publishedAt: 1, withdrawal: { occurredAt: 100,
    retention: 'RETAIN_EXISTING_PRIVATE_COPIES' } });
const receipt = () => ({ schemaVersion: 1, curriculumVersion: 'fixture_v1', moduleId: 'fixture_module',
  version: 1, packageSha256: hash, textBytes: 100, downloadedAt: 40, confirmedAt: 41 });
const index = () => ({ schemaVersion: 1, moduleId: 'fixture_module', version: 1, visibility: 'PUBLIC',
  applicability: { curriculumVersion: 'fixture_v1' }, packageSha256: hash,
  review: { status: 'APPROVED', adaptationAllowed: true, redistributionAllowed: true } });
const attempt = () => ({ schemaVersion: 1, curriculumVersion: 'fixture_v1', moduleId: 'fixture_module',
  version: 1, packageSha256: hash, lessonId: 'lesson_1', runId: 'run_1', startedAt: 42,
  completedAt: 45, totalSteps: 6, resolvedSteps: 6, totalChecks: 3, firstTryCorrect: 1,
  assistedChecks: 1, incorrectChecks: 1, responsesJson: '{"q1":{"status":"resolved"}}' });
const score = { courseId: 'it101', assessmentId: 'quiz_1', categoryId: 'quizzes',
  status: 'SCORED', earnedPoints: 0, possiblePoints: 20, ...academicBinding() };
// Legacy migration control is admin-seeded, never newly authorized by current policy.
const legacyScore = { courseId: 'it101', assessmentId: 'q1', categoryId: 'quiz', planVersion: 'fixture_v1',
  status: 'SCORED', earnedPoints: 0, possiblePoints: 10 };
const owner = () => ref(db('alice'), path);
const adminSet = (path, value) => env.withSecurityRulesDisabled(ctx => set(ref(ctx.database(), path), value));

before(async () => {
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 19000,
    rules: await readFile(new URL('../database.rules.json', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearDatabase();
  await seedAcademicFixture(env);
  await adminSet(indexPath, index());
  await update(ref(db('alice'), 'users/alice/profile'), { displayName: 'Synthetic learner', selectedCurriculumVersion: 'fixture_v1' });
  await adminSet('users/alice/scores/legacy_q1', legacyScore);
  await set(ref(db('alice'), 'users/alice/scores/q1'), score);
});
after(async () => { if (env) { await env.clearDatabase(); await env.cleanup(); } });

test('owner stores reads and exactly retries a version-bound completion without changing manual scores', async () => {
  await assertSucceeds(set(owner(), attempt()));
  await assertSucceeds(set(owner(), attempt()));
  assert.deepEqual((await get(owner())).val(), attempt());
  assert.deepEqual((await get(ref(db('alice'), 'users/alice/scores/q1'))).val(), score);
  assert.deepEqual((await get(ref(db('alice'), 'users/alice/scores/legacy_q1'))).val(), legacyScore);
  // Existing legitimate score/preference writes remain available after an immutable completion.
  await assertSucceeds(update(ref(db('alice'), 'users/alice/scores/q1'), { earnedPoints: 2 }));
  await assertFails(update(ref(db('alice'), 'users/alice/scores/legacy_q1'), { earnedPoints: 2 }));
  await assertFails(set(ref(db('alice'), 'users/alice/scores/forged'), { ...score, policyRef: 'forged' }));
  await assertSucceeds(update(ref(db('alice'), 'users/alice/profile'), { displayName: 'Updated learner' }));
});

test('another UID and unauthenticated clients cannot read write update or delete completions', async () => {
  await assertSucceeds(set(owner(), attempt()));
  for (const uid of ['bob', null]) {
    const foreign = ref(db(uid), path);
    await assertFails(get(foreign)); await assertFails(set(foreign, attempt()));
    await assertFails(update(foreign, { firstTryCorrect: 2 })); await assertFails(remove(foreign));
  }
});

test('accepted completion fields are immutable and deletion cannot bypass through ancestors', async () => {
  await assertSucceeds(set(owner(), attempt()));
  for (const [field, value] of Object.entries(attempt())) {
    const changed = typeof value === 'number' ? value + 1 : value + 'x';
    await assertFails(update(owner(), { [field]: changed }));
    await assertFails(remove(ref(db('alice'), `${path}/${field}`)));
  }
  for (const ancestor of [path, 'users/alice/lessonAttempts', 'users/alice', 'users']) {
    await assertFails(remove(ref(db('alice'), ancestor)));
  }
  await assertFails(update(ref(db('alice'), 'users/alice'), { lessonAttempts: null }));
  assert.deepEqual((await get(owner())).val(), attempt());
});

test('completion requires the immutable published catalog version and matching package identity', async () => {
  for (const delta of [{ version: 2 }, { curriculumVersion: 'other' }, { moduleId: 'other' },
    { packageSha256: 'b'.repeat(64) }]) await assertFails(set(owner(), { ...attempt(), ...delta }));
  for (const delta of [{ schemaVersion: 2 }, { moduleId: 'other' }, { version: 2 }, { visibility: 'PRIVATE' },
    { applicability: { curriculumVersion: 'other' } }, { review: { ...index().review, status: 'DEMONSTRATION_ONLY' } },
    { review: { ...index().review, adaptationAllowed: false } }, { review: { ...index().review, redistributionAllowed: false } }]) {
    await adminSet(indexPath, { ...index(), ...delta });
    await assertFails(set(owner(), attempt()));
  }
  await adminSet(indexPath, null); await assertFails(set(owner(), attempt()));
});

test('exact retry remains available after catalog version withdrawal while changed payload is denied', async () => {
  await assertSucceeds(set(owner(), attempt())); await adminSet(indexPath, null);
  await assertSucceeds(set(owner(), attempt()));
  await assertFails(update(owner(), { completedAt: 46 }));
});

test('withdrawn retained-copy policy accepts a new private completion from the acknowledged original owner copy', async () => {
  await adminSet(historyPath, history()); await adminSet(receiptPath, receipt());
  await adminSet(indexPath, null);
  await assertSucceeds(set(owner(), attempt()));
  await assertSucceeds(set(owner(), attempt()));
  assert.deepEqual((await get(owner())).val(), attempt());
  await assertFails(update(owner(), { completedAt: 46 }));
});

test('withdrawal completion requires immutable history identity retained-copy permission and a prior owner receipt', async () => {
  await adminSet(indexPath, null);
  await adminSet(historyPath, history()); await adminSet(receiptPath, receipt());
  for (const delta of [{ schemaVersion: 2 }, { curriculumVersion: 'other' }, { moduleId: 'other' },
    { version: 2 }, { packageSha256: 'b'.repeat(64) }, { publishedAt: 101 },
    { withdrawal: { occurredAt: 100, retention: 'REVIEW_REQUIRED_FOR_RETAINED_COPIES' } },
    { withdrawal: { occurredAt: 100, retention: 'UNKNOWN' } }, { withdrawal: { retention: 'RETAIN_EXISTING_PRIVATE_COPIES' } }]) {
    await adminSet(historyPath, { ...history(), ...delta });
    await assertFails(set(owner(), attempt()));
  }
  await adminSet(historyPath, history());
  for (const delta of [{ schemaVersion: 2 }, { curriculumVersion: 'other' }, { moduleId: 'other' },
    { version: 2 }, { packageSha256: 'b'.repeat(64) }, { confirmedAt: 101 }, { confirmedAt: '41' },
    { confirmedAt: -1 }, { confirmedAt: 0.5 }]) {
    await adminSet(receiptPath, { ...receipt(), ...delta });
    await assertFails(set(owner(), attempt()));
  }
  await adminSet(receiptPath, null);
  await adminSet(receiptPath.replace('users/alice/', 'users/bob/'), receipt());
  await assertFails(set(owner(), attempt()));
  // The same mutation cannot manufacture the historical owner receipt after withdrawal.
  await assertFails(update(ref(db('alice'), 'users/alice'), {
    'offlinePackages/fixture_v1/fixture_module/v1': receipt(), 'lessonAttempts/run_1': attempt() }));
  await adminSet(receiptPath, receipt()); await adminSet(historyPath, null);
  await assertFails(set(owner(), attempt()));
  await adminSet(historyPath, history());
  await adminSet('catalog/modulePackages/fixture_v1/fixture_module/v1', { schemaVersion: 1 });
  await assertFails(set(owner(), attempt()));
});

test('keys required fields unknown fields types counts and timestamps fail closed', async () => {
  for (const key of Object.keys(attempt())) {
    const value = attempt(); delete value[key]; await assertFails(set(owner(), value));
  }
  for (const delta of [{ schemaVersion: 2 }, { runId: 'other' }, { lessonId: 'invalid lesson' },
    { curriculumVersion: 'bad context' }, { version: 1.5 }, { version: 1_000_001 },
    { packageSha256: 'A'.repeat(64) }, { totalSteps: 0 }, { totalSteps: 1001 }, { resolvedSteps: 5 },
    { totalChecks: 0 }, { totalChecks: 501 }, { firstTryCorrect: 4 }, { assistedChecks: -1 },
    { incorrectChecks: 0.5 }, { startedAt: -1 }, { completedAt: 41 }, { completedAt: 4_102_444_800_001 },
    { responsesJson: '' }, { responsesJson: 'x'.repeat(60001) }, { responsesJson: {} }, { scores: score }]) {
    await assertFails(set(owner(), { ...attempt(), ...delta }));
  }
  await assertFails(set(ref(db('alice'), 'users/alice/lessonAttempts/bad key'), { ...attempt(), runId: 'bad key' }));
});

test('different deliberate runs preserve independent immutable completion identities', async () => {
  await assertSucceeds(set(owner(), attempt()));
  const second = { ...attempt(), runId: 'run_2', startedAt: 50, completedAt: 55 };
  await assertSucceeds(set(ref(db('alice'), 'users/alice/lessonAttempts/run_2'), second));
  assert.deepEqual((await get(owner())).val(), attempt());
  assert.deepEqual((await get(ref(db('alice'), 'users/alice/lessonAttempts/run_2'))).val(), second);
});
