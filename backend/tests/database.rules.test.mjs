import { readFile } from 'node:fs/promises';
import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import {academicBinding,academicPreferences,seedAcademicFixture,overrideKey} from '../academics/policy-fixture.mjs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove } from 'firebase/database';

// Use the same disposable namespace for setup, clients and clearDatabase().
const projectId = 'demo-studypath-original-rules-regression';
let env;
const db = (uid = 'alice') => uid
  ? env.authenticatedContext(uid, { firebase: { sign_in_provider: 'password' } }).database()
  : env.unauthenticatedContext().database();
const path = (suffix = '') => `users/alice/${suffix}`;
const time = 1792627200000;
const profile = () => ({ displayName: 'Ada', selectedCurriculumVersion: 'bsit_demo_v1', learnerPreferences:academicPreferences(), createdAt: time, updatedAt: time });
const reading = () => ({ pageId: 123, pageKey: 'Algorithm', retrievedAt: time, title: 'Algorithm', excerpt: 'An algorithm is a finite sequence of instructions.', sourceUrl: 'https://en.wikipedia.org/wiki/Algorithm', revisionId: 456, revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456', revisionTimestamp: '2026-10-22T00:00:00Z', licenseTitle: 'CC BY-SA 4.0', licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/', modificationNotice: 'Plain-text excerpt; formatting removed.', attribution: 'Wikipedia contributors', savedAt: time });
// Metadata captured from the real API during the 2026-10-22 Android device flow.
const liveMetadata = JSON.parse(await readFile(new URL('./fixtures/wikipedia-computer-programming-metadata.json', import.meta.url), 'utf8'));
const liveReading = () => ({
  ...reading(),
  pageId: liveMetadata.id,
  pageKey: liveMetadata.key,
  title: liveMetadata.title,
  excerpt: 'Local test excerpt for the captured Computer programming metadata.',
  sourceUrl: `https://en.wikipedia.org/wiki/${liveMetadata.key}`,
  revisionId: liveMetadata.latest.id,
  revisionUrl: `https://en.wikipedia.org/w/index.php?oldid=${liveMetadata.latest.id}`,
  revisionTimestamp: liveMetadata.latest.timestamp,
  licenseTitle: liveMetadata.license.title,
  licenseUrl: liveMetadata.license.url,
  attribution: 'Wikipedia contributors. Computer programming. English Wikipedia.',
  modificationNotice: 'Text selected, shortened and reformatted for native offline reading. Images and other media omitted.'
});
const attempt = () => ({ moduleId: 'programming_basics', version: 1, correct: 2, total: 3, submittedAt: time });
const fixtures = {
  'profile': profile(),
  'scores/quiz_1': { ...academicBinding(), courseId: 'it101', assessmentId: 'quiz_1', categoryId: 'quizzes', planVersion: 'demo_linear_v1', status: 'SCORED', earnedPoints: 0, possiblePoints: 20, updatedAt: time },
  'courseAttempts/it101_2026': { ...academicBinding(), courseId: 'it101', term: '2026-1', finalPercent: 80, status: 'COMPLETED', units: 3, includeInGwa: false, updatedAt: time },
  'scenarios/scenario_1': { ...academicBinding('SCENARIOS'), courseId: 'it101', title: 'Target 85', baseVersion: 'scores_v1', targetPercent: 85, requiredPercent: 95, updatedAt: time },
  'savedReadings/page_123': reading(),
  'reviewProgress/programming_basics/v1': { moduleId: 'programming_basics', version: 1, completed: true, lastSection: 0, updatedAt: time },
  'practiceAttempts/attempt_1': attempt(),
  'reminders/reminder_1': { moduleId: 'programming_basics', title: 'Review algorithms', dueAt: time, enabled: true, updatedAt: time }
};

// Fresh snapshots must reserve a slot atomically. Keep direct writes below for
// exact retries of an already accepted snapshot, rather than bypassing the shelf.
const createReading = (id, value) => update(ref(db(), path()), {
  readingShelfVersion: 1,
  [`savedReadings/${id}`]: value,
  'savedReadingSlots/s00': id
});
const writeFixture = async (suffix, value) => {
  if (/^(scores|courseAttempts|scenarios)\//.test(suffix)) await seedAcademicFixture(env);
  return suffix.startsWith('savedReadings/') ? createReading(suffix.slice('savedReadings/'.length), value) : set(ref(db(), path(suffix)), value);
};

before(async () => {
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 9000, rules: await readFile(new URL('../database.rules.json', import.meta.url), 'utf8') } });
});
after(async () => { if (env) await env.cleanup(); });
beforeEach(async () => { await env.clearDatabase(); await seedAcademicFixture(env); });

test('owner can store and retrieve every documented record shape, including a scored zero', async () => {
  for (const [suffix, value] of Object.entries(fixtures)) {
    await assertSucceeds(writeFixture(suffix, value));
    assert.deepEqual((await assertSucceeds(get(ref(db(), path(suffix))))).val(), value);
  }
});
test('unauthenticated user cannot read or write private data', async () => {
  await assertFails(get(ref(db(null), path('profile'))));
  await assertFails(set(ref(db(null), path('profile')), profile()));
});
test('another UID cannot read, write, update or delete owner data', async () => {
  await set(ref(db(), path('profile')), profile());
  await assertFails(get(ref(db('bob'), path())));
  await assertFails(set(ref(db('bob'), path('profile')), profile()));
  await assertFails(update(ref(db('bob'), path('profile')), { displayName: 'Bob' }));
  await assertFails(remove(ref(db('bob'), path('profile'))));
});
test('private collection listing and root writes are denied', async () => {
  await assertFails(get(ref(db(), 'users')));
  await assertFails(get(ref(db(), '/')));
  await assertFails(set(ref(db(), '/'), { users: { alice: { profile: profile() } } }));
});
test('unknown user collections and unknown record fields are rejected', async () => {
  await assertFails(set(ref(db(), path('admin')), true));
  for (const [suffix, value] of Object.entries(fixtures)) {
    await assertFails(writeFixture(suffix, { ...value, unexpected: true }));
  }
});
test('record collections cannot be replaced by scalar values', async () => {
  for (const suffix of ['profile', 'scores', 'courseAttempts', 'scenarios', 'savedReadings', 'reviewProgress', 'practiceAttempts', 'reminders']) {
    await assertFails(set(ref(db(), path(suffix)), 'invalid'));
  }
});
test('missing required values, wrong types, oversized values and invalid IDs are rejected', async () => {
  await assertFails(set(ref(db(), path('profile')), { displayName: 'Ada' }));
  await assertFails(set(ref(db(), path('profile')), { ...profile(), displayName: 'x'.repeat(81) }));
  await assertFails(set(ref(db(), path('profile')), { ...profile(), createdAt: 'yesterday' }));
  await assertFails(set(ref(db(), path('scores/bad id')), fixtures['scores/quiz_1']));
  await assertFails(createReading('page_123', { ...reading(), excerpt: 'x'.repeat(8001) }));
  await assertFails(createReading('page_123', { ...reading(), sourceUrl: 'http://evil.example/wiki' }));
  await assertFails(set(ref(db(), path('reminders/reminder_1')), { ...fixtures['reminders/reminder_1'], enabled: 'true' }));
});
test('score ranges enforce nonnegative earnedPoints <= possiblePoints and positive possiblePoints', async () => {
  for (const [earnedPoints, possiblePoints] of [[-1, 10], [11, 10], [0, 0], ['4', 10]]) {
    await assertFails(set(ref(db(), path('scores/quiz_1')), { ...fixtures['scores/quiz_1'], earnedPoints, possiblePoints }));
  }
});
test('course outcome and scenario percentages reject out-of-range values', async () => {
  await assertFails(set(ref(db(), path('courseAttempts/x')), { ...fixtures['courseAttempts/it101_2026'], finalPercent: 101 }));
  await assertFails(set(ref(db(), path('courseAttempts/x')), { ...fixtures['courseAttempts/it101_2026'], status: 'officially-approved' }));
  await assertFails(set(ref(db(), path('scenarios/x')), { ...fixtures['scenarios/scenario_1'], targetPercent: -1 }));
  await assertFails(set(ref(db(), path('scenarios/x')), { ...fixtures['scenarios/scenario_1'], requiredPercent: 101 }));
});
test('accepted saved reading snapshots cannot be edited but exact retries succeed', async () => {
  await assertSucceeds(createReading('page_123', reading()));
  await assertSucceeds(set(ref(db(), path('savedReadings/page_123')), reading()));
  await assertFails(update(ref(db(), path('savedReadings/page_123')), { excerpt: 'Changed' }));
});
test('live Wikipedia English deed metadata saves unchanged and permits only exact retries', async () => {
  const snapshot = liveReading();
  const location = ref(db(), path('savedReadings/page_5311_revision_1379403934'));
  await assertSucceeds(createReading('page_5311_revision_1379403934', snapshot));
  assert.deepEqual((await assertSucceeds(get(location))).val(), snapshot);
  await assertSucceeds(set(location, snapshot));
  await assertFails(update(location, { licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/' }));
  await assertFails(update(location, { excerpt: 'Changed' }));
});
test('saved readings reject unsafe license schemes, domains and unapproved paths', async () => {
  const unsafeUrls = [
    'http://creativecommons.org/licenses/by-sa/4.0/deed.en',
    'javascript:alert(1)',
    'https://evil.example/licenses/by-sa/4.0/deed.en',
    'https://creativecommons.org.evil.example/licenses/by-sa/4.0/deed.en',
    'https://creativecommons.org@evil.example/licenses/by-sa/4.0/deed.en',
    'https://creativecommons.org:444/licenses/by-sa/4.0/deed.en',
    'https://creativecommons.org/licenses/by/4.0/deed.en',
    'https://creativecommons.org/licenses/by-sa/4.0/deedXen',
    'https://creativecommons.org/licenses/by-sa/4.0/deed.en/extra',
    'https://creativecommons.org/licenses/by-sa/4.0/../deed.en',
    'https://creativecommons.org/licenses/by-sa/4.0/%64eed.en',
    'https://creativecommons.org/licenses/by-sa/4.0/deed.en?redirect=evil',
    'https://creativecommons.org/licenses/by-sa/4.0/deed.en#extra'
  ];
  for (const [index, licenseUrl] of unsafeUrls.entries()) {
    await assertFails(createReading(`unsafe_license_${index}`, { ...reading(), licenseUrl }));
  }
});
test('accepted practice attempts are immutable, retry-safe and bounded', async () => {
  await set(ref(db(), path('practiceAttempts/attempt_1')), attempt());
  await assertSucceeds(set(ref(db(), path('practiceAttempts/attempt_1')), attempt()));
  await assertFails(update(ref(db(), path('practiceAttempts/attempt_1')), { correct: 3 }));
  for (const value of [{ ...attempt(), correct: 4 }, { ...attempt(), correct: -1 }, { ...attempt(), total: 0 }, { ...attempt(), correct: 1.5 }, { ...attempt(), total: 501 }]) {
    await assertFails(set(ref(db(), path('practiceAttempts/new_attempt')), value));
  }
});
test('owners can delete each record and their entire private subtree', async () => {
  for (const [suffix, value] of Object.entries(fixtures)) {
    await assertSucceeds(writeFixture(suffix, value));
    if (suffix.startsWith('savedReadings/')) {
      await assertSucceeds(update(ref(db(), path()), {
        [suffix]: null, 'savedReadingSlots/s00': null
      }));
      assert.equal((await get(ref(db(), path('savedReadingSlots/s00')))).exists(), false);
    } else {
      await assertSucceeds(remove(ref(db(), path(suffix))));
    }
    assert.equal((await get(ref(db(), path(suffix)))).exists(), false);
  }
  await set(ref(db(), path('profile')), profile());
  await assertSucceeds(remove(ref(db(), path())));
});
test('required child removal is rejected and multi-location writes remain atomic', async () => {
  await set(ref(db(), path('profile')), profile());
  await assertFails(remove(ref(db(), path('profile/displayName'))));
  await assertFails(update(ref(db(), path()), { 'profile/displayName': 'Grace', 'admin': true }));
  assert.equal((await get(ref(db(), path('profile/displayName')))).val(), 'Ada');
});
test('authenticated users can read published catalog; no client can write it', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), 'catalog'), { pilot: { approved: false } });
  });
  await assertSucceeds(get(ref(db(), 'catalog')));
  await assertFails(get(ref(db(null), 'catalog')));
  await assertFails(set(ref(db(), 'catalog/pilot/approved'), true));
  await assertFails(remove(ref(db(), 'catalog')));
});
test('unrecognized top-level and publication paths are denied', async () => {
  await assertFails(set(ref(db(), 'other/data'), true));
  await assertFails(get(ref(db(), 'privateCatalog')));
  await assertFails(set(ref(db(), 'privateCatalog'), true));
});

test('UNGRADED and EXCUSED scores omit earnedPoints; a scored zero stays explicit', async () => {
  for (const status of ['UNGRADED', 'EXCUSED']) {
    const { earnedPoints, ...score } = fixtures['scores/quiz_1'];
    await assertSucceeds(set(ref(db(), path('scores/quiz_1')), { ...score, status }));
    await assertFails(set(ref(db(), path('scores/quiz_1')), { ...score, status, earnedPoints: 0 }));
  }
  const { earnedPoints, ...score } = fixtures['scores/quiz_1'];
  await assertFails(set(ref(db(), path('scores/quiz_1')), score));
});

test('progress preserves module versions and rejects mismatched version keys', async () => {
  const progress = fixtures['reviewProgress/programming_basics/v1'];
  await assertSucceeds(set(ref(db(), path('reviewProgress/programming_basics/v1')), progress));
  await assertSucceeds(set(ref(db(), path('reviewProgress/programming_basics/v2')), { ...progress, version: 2 }));
  await assertFails(set(ref(db(), path('reviewProgress/programming_basics/v3')), progress));
  await assertFails(set(ref(db(), path('reviewProgress/data_structures/v1')), progress));
  await assertFails(set(ref(db(), path('reviewProgress/programming_basics')), progress));
});
test('scenarios validate hypothetical overrides and keep an explicit base fingerprint', async () => {
  const scenario = fixtures['scenarios/scenario_1'];
  await assertSucceeds(set(ref(db(), path('scenarios/x')), { ...scenario, overrides: { [overrideKey('quizzes','quiz_1')]: 95, [overrideKey('quizzes','quiz_2')]: 0 } }));
  await assertFails(set(ref(db(), path('scenarios/x')), { ...scenario, overrides: { [overrideKey('quizzes','quiz_1')]: 101 } }));
  await assertFails(set(ref(db(), path('scenarios/x')), { ...scenario, overrides: { [overrideKey('quizzes','quiz_1')]: '95' } }));
  await assertFails(set(ref(db(), path('scenarios/x')), { ...scenario, baseVersion: '' }));
});
test('course completion remains self-reported and cannot attach results to an in-progress attempt', async () => {
  const course = fixtures['courseAttempts/it101_2026'];
  await assertSucceeds(set(ref(db(), path('courseAttempts/x')), { ...course, ...academicBinding('PREREQUISITES'), completionStatus: 'PASSED', scaleId: 'demo' }));
  await assertFails(set(ref(db(), path('courseAttempts/x')), { ...course, status: 'IN_PROGRESS' }));
  await assertFails(set(ref(db(), path('courseAttempts/x')), { ...course, completionStatus: 'OFFICIALLY_APPROVED' }));
});

test('scores require a stable plan version and cannot silently drop it on updates', async () => {
  const { planVersion, ...withoutVersion } = fixtures['scores/quiz_1'];
  await assertFails(set(ref(db(), path('scores/version_missing')), withoutVersion));
  await assertFails(set(ref(db(), path('scores/version_bad')), { ...withoutVersion, planVersion: 'invalid version' }));
  await assertSucceeds(set(ref(db(), path('scores/version_ok')), { ...withoutVersion, planVersion: 'demo_linear_v1' }));
  await assertFails(remove(ref(db(), path('scores/version_ok/planVersion'))));
});
test('GWA inclusion is explicit and requires a completed numeric grade with a scale', async () => {
  const base = fixtures['courseAttempts/it101_2026'];
  const { includeInGwa, ...withoutChoice } = base;
  await assertFails(set(ref(db(), path('courseAttempts/gwa')), withoutChoice));
  await assertFails(set(ref(db(), path('courseAttempts/gwa')), { ...base, includeInGwa: 'false' }));
  await assertSucceeds(set(ref(db(), path('courseAttempts/gwa')), { ...base, ...academicBinding('GWA'), includeInGwa: true, grade: 1.75, scaleId: 'demo_scale' }));
  for (const value of [
    { ...base, includeInGwa: true },
    { ...base, includeInGwa: true, grade: 1.75 },
    { ...base, includeInGwa: true, scaleId: 'demo_scale' },
    { ...base, includeInGwa: true, grade: 1.75, scaleId: '' },
    { ...base, includeInGwa: true, grade: 1.75, scaleId: 'demo_scale', status: 'IN_PROGRESS' }
  ]) await assertFails(set(ref(db(), path('courseAttempts/gwa')), value));
  await assertSucceeds(set(ref(db(), path('courseAttempts/excluded')), {
    ...academicBinding(), courseId: 'it101', term: '2026-1', status: 'IN_PROGRESS', units: 3, includeInGwa: false
  }));
  await assertSucceeds(set(ref(db(), path('courseAttempts/excluded')), base));
});
