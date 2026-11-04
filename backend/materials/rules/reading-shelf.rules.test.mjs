import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove } from 'firebase/database';
import { planReadingShelfMigration, migrateReadingShelf } from '../reading-shelf-migration.mjs';

const projectId = 'demo-studypath-b12-reading-shelf-rules';
const protectedNamespaces = ['demo-studypath', 'demo-studypath-default-rtdb'];
assert.ok(!protectedNamespaces.includes(projectId));
const time = 1792627200000;
const reading = () => ({ pageId: 123, pageKey: 'Algorithm', title: 'Algorithm',
  excerpt: 'A finite sequence of instructions.', sourceUrl: 'https://en.wikipedia.org/wiki/Algorithm',
  revisionId: 456, revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456',
  revisionTimestamp: '2026-10-22T00:00:00Z', licenseTitle: 'CC BY-SA 4.0',
  licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/', attribution: 'Wikipedia contributors',
  modificationNotice: 'Plain-text excerpt; formatting removed.', savedAt: time, retrievedAt: time });
const annotation = () => ({ snapshotRevisionId: 456, excerptLength: reading().excerpt.length,
  note: 'Synthetic private note', position: 2, editId: 'edit_1', modifiedAt: time });
const path = suffix => `users/alice/${suffix}`;
const slot = index => `s${String(index).padStart(2, '0')}`;
let env, originalDigests;
const db = (uid = 'alice') => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const owner = client => ref(client, 'users/alice');
const save = (client, id = 'wiki_123_456', index = 0, value = reading()) => update(owner(client), {
  readingShelfVersion: 1, [`savedReadings/${id}`]: value, [`savedReadingSlots/${slot(index)}`]: id
});
const contextRef = contextId => ({ schemaVersion: 1, contextId });
const contextDefinition = (kind = 'MODULE_CONCEPT') => ({ schemaVersion: 1, kind,
  curriculumVersion: 'fixture_v1', subjectId: 'programming', query: 'algorithm',
  review: { status: 'APPROVED', reviewer: 'Synthetic test reviewer', reviewedOn: '2026-10-28', approvalReference: 'FABRICATED_RULE_TEST_ONLY' },
  ...(kind === 'MODULE_CONCEPT' ? { moduleId: 'algorithms', moduleVersion: 1, packageSha256: 'a'.repeat(64), conceptId: 'sequence' }
    : { courseId: 'it101', planVersion: 'plan_v1', categoryId: 'quizzes' }) });
const adminSet = (location, value) => env.withSecurityRulesDisabled(context => set(ref(context.database(), location), value));
async function contextFixtures() {
  await adminSet('catalog', {
    readingContexts: { module_context: contextDefinition(), weak_context: contextDefinition('WEAK_CATEGORY'),
      alternative_context: contextDefinition() },
    learnerPreferenceOptions: { fixture_v1: { schemaVersion: 1, curriculumVersion: 'fixture_v1', levelId: 'college',
      approved: true, approvalStatus: 'APPROVED', subjectIds: { programming: true } } },
    moduleIndex: { fixture_v1: { algorithms: { v1: { schemaVersion: 1, moduleId: 'algorithms', version: 1,
      visibility: 'PUBLIC', packageSha256: 'a'.repeat(64), applicability: { curriculumVersion: 'fixture_v1', levelId: 'college', subjectId: 'programming' },
      review: { status: 'APPROVED', adaptationAllowed: true, redistributionAllowed: true } } } } },
    courses: { it101: { gradePlan: { version: 'plan_v1', verified: true, categories: { quizzes: { weight: 1 } } } } }
  });
  await assertSucceeds(set(ref(db(), path('profile')), { displayName: 'Synthetic', selectedCurriculumVersion: 'fixture_v1',
    learnerPreferences: { schemaVersion: 1, onboardingComplete: true, curriculumVersion: 'fixture_v1', levelId: 'college', subjectIds: { programming: true } } }));
}

async function sharedDigests() {
  const result = {};
  for (const namespace of protectedNamespaces) {
    const response = await fetch(`http://127.0.0.1:9000/.json?ns=${namespace}`, {
      headers: { Authorization: 'Bearer owner' }, signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, 200);
    result[namespace] = createHash('sha256').update(JSON.stringify(await response.json())).digest('hex');
  }
  return result;
}

before(async () => {
  originalDigests = await sharedDigests();
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 9000,
    rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') } });
});
beforeEach(async () => { await env.clearDatabase(); });
after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  const finalDigests = await sharedDigests();
  console.log(`Shared namespace digest guard: ${JSON.stringify({ before: originalDigests, after: finalDigests })}`);
  assert.deepEqual(finalDigests, originalDigests);
});

test('fresh shelf saves immutable stable IDs through an atomic slot update and permits exact retries', async () => {
  const client = db();
  await assertSucceeds(save(client));
  await assertSucceeds(save(client));
  await assertSucceeds(set(ref(client, path('savedReadings/wiki_123_456')), reading()));
  assert.deepEqual((await get(ref(client, path('savedReadings/wiki_123_456')))).val(), reading());
  await assertFails(update(ref(client, path('savedReadings/wiki_123_456')), { excerpt: 'Replacement' }));
});

test('old non-slot creates, missing snapshots, invalid slots and forged shelf versions fail', async () => {
  const client = db();
  await assertFails(set(ref(client, path('savedReadings/old_queue_insert')), reading()));
  await assertFails(update(owner(client), { readingShelfVersion: 1, 'savedReadingSlots/s00': 'missing' }));
  for (const invalidSlot of ['s20', 's1', '00', 's-1']) {
    await assertFails(update(owner(client), { readingShelfVersion: 1, 'savedReadings/new': reading(),
      [`savedReadingSlots/${invalidSlot}`]: 'new' }));
  }
  for (const version of [0, 2, '1', true]) await assertFails(set(ref(client, path('readingShelfVersion')), version));
  await assertSucceeds(save(client));
  await assertFails(remove(ref(client, path('readingShelfVersion'))));
  await assertFails(set(owner(client), { savedReadings: { wiki_123_456: reading() } }));
});

test('twenty slots reject the 21st reading through new slots or occupied-slot reuse', async () => {
  const client = db();
  for (let index = 0; index < 20; index++) await assertSucceeds(save(client, `reading_${index}`, index));
  await assertFails(save(client, 'reading_21', 20));
  await assertFails(save(client, 'reading_21', 19));
  await assertFails(set(ref(client, path('savedReadings/reading_21')), reading()));
  assert.equal(Object.keys((await get(ref(client, path('savedReadings')))).val()).length, 20);
});

test('a fresh atomic 21-record parent replacement cannot establish an over-cap shelf', async () => {
  const records = Object.fromEntries(Array.from({ length: 21 }, (_, index) => [`r${index}`, reading()]));
  const slots = Object.fromEntries(Array.from({ length: 20 }, (_, index) => [slot(index), `r${index}`]));
  await assertFails(set(owner(db()), { readingShelfVersion: 1, savedReadings: records, savedReadingSlots: slots }));
  assert.equal((await get(owner(db()))).exists(), false);
});

test('concurrent claims for the final slot accept one snapshot without orphaning the loser', async () => {
  const client = db();
  for (let index = 0; index < 19; index++) await assertSucceeds(save(client, `reading_${index}`, index));
  const outcomes = await Promise.allSettled([save(db(), 'race_a', 19), save(db(), 'race_b', 19)]);
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1);
  const shelf = (await get(owner(client))).val();
  assert.equal(Object.keys(shelf.savedReadings).length, 20);
  const winner = shelf.savedReadingSlots.s19;
  assert.ok(['race_a', 'race_b'].includes(winner));
  assert.equal(Object.hasOwn(shelf.savedReadings, winner === 'race_a' ? 'race_b' : 'race_a'), false);
});

test('slot-only clear, repoint, whole-index removal and ancestor replacement cannot orphan snapshots', async () => {
  const client = db();
  await assertSucceeds(save(client));
  await assertSucceeds(save(client, 'second', 1));
  await assertFails(remove(ref(client, path('savedReadingSlots/s00'))));
  await assertFails(set(ref(client, path('savedReadingSlots/s00')), 'second'));
  await assertFails(remove(ref(client, path('savedReadingSlots'))));
  await assertFails(set(owner(client), { readingShelfVersion: 1, savedReadings: {
    wiki_123_456: reading(), second: reading() }, savedReadingSlots: { s01: 'second' } }));
  await assertFails(update(ref(client), { [path('savedReadingSlots/s00')]: null,
    [path('savedReadings/third')]: reading(), [path('savedReadingSlots/s02')]: 'third' }));
  assert.deepEqual((await get(ref(client, path('savedReadingSlots')))).val(), { s00: 'wiki_123_456', s01: 'second' });
});

test('owned annotation edits preserve the source and bind revision length and bounded reading position', async () => {
  const client = db();
  await assertSucceeds(save(client));
  const location = ref(client, path('readingAnnotations/wiki_123_456'));
  await assertSucceeds(set(location, annotation()));
  await assertSucceeds(set(location, { ...annotation(), note: '', position: 0, editId: 'edit_2' }));
  await assertSucceeds(set(location, { ...annotation(), note: 'x'.repeat(4000), position: reading().excerpt.length }));
  for (const patch of [{ note: 'x'.repeat(4001) }, { position: -1 }, { position: 0.5 },
    { position: reading().excerpt.length + 1 }, { snapshotRevisionId: 457 }, { excerptLength: 1 },
    { excerptLength: '33' }, { editId: 'bad id' }, { modifiedAt: 4102444800001 },
    { modifiedAt: 0.5 }, { extra: true }]) {
    await assertFails(set(location, { ...annotation(), ...patch }));
  }
  const missing = annotation(); delete missing.note;
  await assertFails(set(location, missing));
  assert.deepEqual((await get(ref(client, path('savedReadings/wiki_123_456')))).val(), reading());
});

test('annotations require an active owned snapshot and deny foreign or unauthenticated access', async () => {
  const client = db();
  await assertFails(set(ref(client, path('readingAnnotations/wiki_123_456')), annotation()));
  await assertSucceeds(save(client));
  await assertSucceeds(set(ref(client, path('readingAnnotations/wiki_123_456')), annotation()));
  for (const uid of ['bob', null]) {
    const location = ref(db(uid), path('readingAnnotations/wiki_123_456'));
    await assertFails(get(location)); await assertFails(set(location, annotation())); await assertFails(remove(location));
    await assertFails(save(db(uid), 'foreign', 1));
  }
  await assertFails(set(ref(client, path('readingAnnotations/missing')), annotation()));
});

test('deletion requires snapshot, every slot alias and annotation to disappear atomically', async () => {
  const client = db();
  await assertSucceeds(save(client));
  await assertSucceeds(set(ref(client, path('savedReadingSlots/s01')), 'wiki_123_456'));
  await assertSucceeds(set(ref(client, path('readingAnnotations/wiki_123_456')), annotation()));
  await assertFails(remove(ref(client, path('savedReadings/wiki_123_456'))));
  await assertFails(update(owner(client), { 'savedReadings/wiki_123_456': null,
    'savedReadingSlots/s00': null, 'savedReadingSlots/s01': null }));
  await assertFails(update(owner(client), { 'savedReadings/wiki_123_456': null,
    'savedReadingSlots/s00': null, 'readingAnnotations/wiki_123_456': null }));
  await assertSucceeds(update(owner(client), { 'savedReadings/wiki_123_456': null,
    'savedReadingSlots/s00': null, 'savedReadingSlots/s01': null, 'readingAnnotations/wiki_123_456': null }));
  assert.deepEqual((await get(owner(client))).val(), { readingShelfVersion: 1 });
  await assertSucceeds(save(client, 'replacement', 0));
});

test('whole-user deletion still removes owned reading data when no immutable lesson attempt exists', async () => {
  const client = db();
  await assertSucceeds(save(client));
  await assertSucceeds(set(ref(client, path('readingAnnotations/wiki_123_456')), annotation()));
  await assertSucceeds(remove(owner(client)));
  assert.equal((await get(owner(client))).exists(), false);
});

test('legacy snapshots stay readable retryable and deletable but clients cannot activate or add a record', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await set(ref(context.database(), path('savedReadings/legacy_id')), reading());
  });
  const client = db();
  await assertSucceeds(set(ref(client, path('savedReadings/legacy_id')), reading()));
  assert.deepEqual((await get(ref(client, path('savedReadings/legacy_id')))).val(), reading());
  await assertFails(set(ref(client, path('readingShelfVersion')), 1));
  await assertFails(save(client, 'new', 0));
  await assertFails(update(owner(client), { readingShelfVersion: 1, 'savedReadingSlots/s00': 'legacy_id' }));
  await assertFails(set(ref(client, path('readingAnnotations/legacy_id')), annotation()));
  await assertSucceeds(remove(ref(client, path('savedReadings/legacy_id'))));
  await assertSucceeds(save(client));
});

test('trusted migration preserves every legacy snapshot and then enforces the same slot rules', async () => {
  const legacy = { profile: { displayName: 'Synthetic', selectedCurriculumVersion: 'demo' },
    savedReadings: { legacy_id: reading(), wiki_123_456: reading() } };
  const planned = planReadingShelfMigration(legacy);
  await adminSet('users/alice', legacy);
  await adminSet('users/bob/profile', { displayName: 'Preserved other synthetic user', selectedCurriculumVersion: 'demo' });
  const migration = await migrateReadingShelf({ namespace: projectId, uid: 'alice', apply: true });
  assert.equal(migration.applied, true); assert.equal(migration.verified, true);
  assert.deepEqual((await get(owner(db()))).val(), planned.user);
  assert.equal((await migrateReadingShelf({ namespace: projectId, uid: 'alice', apply: true })).changed, false);
  await env.withSecurityRulesDisabled(async context => {
    assert.equal((await get(ref(context.database(), 'users/bob/profile/displayName'))).val(), 'Preserved other synthetic user');
  });
  const client = db();
  assert.deepEqual((await get(ref(client, path('savedReadings')))).val(), legacy.savedReadings);
  await assertSucceeds(set(ref(client, path('savedReadings/legacy_id')), reading()));
  await assertSucceeds(save(client, 'new', 2));
  await assertFails(save(client, 'unindexed_new', 20));
  await assertFails(remove(ref(client, path('readingShelfVersion'))));
});

test('reviewed module and weak-category context references require current owned context and exact fields', async () => {
  await contextFixtures();
  const client = db();
  await assertSucceeds(save(client));
  await assertSucceeds(set(ref(client, path('readingContexts/wiki_123_456')), contextRef('module_context')));
  await assertSucceeds(save(client, 'weak_reading', 1, { ...reading(), courseId: 'it101' }));
  await assertSucceeds(set(ref(client, path('readingContexts/weak_reading')), contextRef('weak_context')));
  await assertFails(set(ref(client, path('readingContexts/missing')), contextRef('module_context')));
  await assertSucceeds(save(client, 'bad_reference', 2));
  const location = ref(client, path('readingContexts/bad_reference'));
  for (const value of [{ contextId: 'module_context' }, { ...contextRef('module_context'), extra: true },
    { schemaVersion: '1', contextId: 'module_context' }, contextRef('missing'), contextRef('bad id')]) {
    await assertFails(set(location, value));
  }
  for (const uid of ['bob', null]) {
    await assertFails(get(ref(db(uid), path('readingContexts/wiki_123_456'))));
    await assertFails(set(ref(db(uid), path('readingContexts/wiki_123_456')), contextRef('module_context')));
    await assertFails(remove(ref(db(uid), path('readingContexts/wiki_123_456'))));
  }
});

test('initial source slot annotation and reviewed context save together in one UID atomic update', async () => {
  await contextFixtures();
  const client = db();
  await assertSucceeds(update(owner(client), { readingShelfVersion: 1, 'savedReadings/wiki_123_456': reading(),
    'savedReadingSlots/s00': 'wiki_123_456', 'readingAnnotations/wiki_123_456': annotation(),
    'readingContexts/wiki_123_456': contextRef('module_context') }));
  assert.deepEqual((await get(ref(client, path('readingAnnotations/wiki_123_456')))).val(), annotation());
  assert.deepEqual((await get(ref(client, path('readingContexts/wiki_123_456')))).val(), contextRef('module_context'));
});

test('existing immutable lesson attempts do not block granular shelf saves edits or linked deletions', async () => {
  const retained = { opaqueExistingAttempt: { source: 'admin seeded unrelated immutable fixture' } };
  await adminSet(path('lessonAttempts'), retained);
  const client = db();
  await assertSucceeds(save(client));
  await assertSucceeds(set(ref(client, path('readingAnnotations/wiki_123_456')), annotation()));
  await assertSucceeds(update(owner(client), { 'savedReadings/wiki_123_456': null, 'savedReadingSlots/s00': null,
    'readingAnnotations/wiki_123_456': null }));
  assert.deepEqual((await get(ref(client, path('lessonAttempts')))).val(), retained);
  await assertFails(remove(owner(client)));
});

test('new context references fail for unapproved stale or unrelated registry preferences and plan/module data', async () => {
  const mutateAndReject = async (location, value, chosen = 'module_context', snapshot = reading()) => {
    await env.clearDatabase(); await contextFixtures();
    await assertSucceeds(save(db(), 'candidate', 0, snapshot));
    await adminSet(location, value);
    await assertFails(set(ref(db(), path('readingContexts/candidate')), contextRef(chosen)));
  };
  for (const value of ['WITHDRAWN', 'DEMONSTRATION_ONLY', 'REVIEW_PENDING']) {
    await mutateAndReject('catalog/readingContexts/module_context/review/status', value);
  }
  await mutateAndReject('catalog/readingContexts/module_context/schemaVersion', 2);
  await mutateAndReject('catalog/readingContexts/module_context/query', ' \t\n');
  await mutateAndReject('catalog/readingContexts/module_context/kind', 'APPROVED_ARTICLE');
  await mutateAndReject('catalog/readingContexts/module_context/curriculumVersion', 'another_v1');
  await mutateAndReject('catalog/readingContexts/module_context/subjectId', 'unselected');
  await mutateAndReject(path('profile/learnerPreferences/onboardingComplete'), false);
  await mutateAndReject('catalog/learnerPreferenceOptions/fixture_v1/approved', false);
  await mutateAndReject('catalog/moduleIndex/fixture_v1/algorithms/v1/review/status', 'WITHDRAWN');
  await mutateAndReject('catalog/moduleIndex/fixture_v1/algorithms/v1/review/adaptationAllowed', false);
  await mutateAndReject('catalog/moduleIndex/fixture_v1/algorithms/v1/packageSha256', 'b'.repeat(64));
  await mutateAndReject('catalog/moduleIndex/fixture_v1/algorithms/v1/applicability/subjectId', 'another');
  await mutateAndReject('catalog/moduleIndex/fixture_v1/algorithms/v1/applicability/levelId', 'another');
  await mutateAndReject('catalog/moduleIndex/fixture_v1/algorithms/v1/applicability/classIds', { unselected_class: true });
  await mutateAndReject('catalog/courses/it101/gradePlan/verified', false, 'weak_context', { ...reading(), courseId: 'it101' });
  await mutateAndReject('catalog/courses/it101/gradePlan/version', 'stale', 'weak_context', { ...reading(), courseId: 'it101' });
  await mutateAndReject('catalog/courses/it101/gradePlan/categories/quizzes', null, 'weak_context', { ...reading(), courseId: 'it101' });
  await mutateAndReject('catalog/readingContexts/weak_context/query', 'algorithm', 'weak_context', { ...reading(), courseId: 'another_course' });
});

test('saved context identity cannot change or disappear while its snapshot remains and retries survive withdrawal', async () => {
  await contextFixtures();
  const client = db();
  await assertSucceeds(save(client));
  const location = ref(client, path('readingContexts/wiki_123_456'));
  await assertSucceeds(set(location, contextRef('module_context')));
  await assertFails(set(location, contextRef('alternative_context')));
  await assertFails(remove(location));
  await assertFails(remove(ref(client, path('readingContexts'))));
  const current = (await get(owner(client))).val(); delete current.readingContexts;
  await assertFails(set(owner(client), current));
  await adminSet('catalog/readingContexts/module_context/review/status', 'WITHDRAWN');
  await assertSucceeds(set(location, contextRef('module_context')));
  await assertFails(update(owner(client), { 'savedReadings/wiki_123_456': null, 'savedReadingSlots/s00': null }));
  await assertSucceeds(update(owner(client), { 'savedReadings/wiki_123_456': null, 'savedReadingSlots/s00': null,
    'readingContexts/wiki_123_456': null }));
});
