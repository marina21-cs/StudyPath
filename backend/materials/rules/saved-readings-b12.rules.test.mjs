import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove } from 'firebase/database';

// Never point this regression suite at either shared demonstration namespace.
const projectId = 'demo-studypath-b12-reading-context-regression';
const protectedNamespaces = ['demo-studypath', 'demo-studypath-default-rtdb'];
assert.ok(!protectedNamespaces.includes(projectId));
const time = 1792627200000;
const reading = () => ({
  pageId: 123, pageKey: 'Algorithm', title: 'Algorithm',
  excerpt: 'An algorithm is a finite sequence of instructions.',
  sourceUrl: 'https://en.wikipedia.org/wiki/Algorithm',
  revisionId: 456, revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456',
  revisionTimestamp: '2026-10-22T00:00:00Z',
  licenseTitle: 'CC BY-SA 4.0', licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
  modificationNotice: 'Plain-text excerpt; formatting removed.',
  attribution: 'Wikipedia contributors', savedAt: time, retrievedAt: time,
  courseId: 'it101'
});
const ownerPath = suffix => `users/alice/${suffix}`;
const createReading = (client, id, value = reading(), index = 0) => update(ref(client, 'users/alice'), {
  readingShelfVersion: 1, [`savedReadings/${id}`]: value,
  [`savedReadingSlots/s${String(index).padStart(2, '0')}`]: id
});
let env, originalDigests;
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();

// Same guard used by reading-starters.rules.test.mjs, extended to both shared namespaces.
async function sharedDigests() {
  const digests = {};
  for (const namespace of protectedNamespaces) {
    const response = await fetch(`http://127.0.0.1:9000/.json?ns=${namespace}`, {
      headers: { Authorization: 'Bearer owner' }, signal: AbortSignal.timeout(10000)
    });
    assert.equal(response.status, 200);
    digests[namespace] = createHash('sha256').update(JSON.stringify(await response.json())).digest('hex');
  }
  return digests;
}

before(async () => {
  originalDigests = await sharedDigests();
  env = await initializeTestEnvironment({
    projectId,
    database: { host: '127.0.0.1', port: 9000,
      rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') }
  });
});

beforeEach(async () => {
  // clearDatabase uses only projectId above, never either protected namespace.
  await env.clearDatabase();
});

after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  const finalDigests = await sharedDigests();
  console.log(`Shared namespace digest guard: ${JSON.stringify({ before: originalDigests, after: finalDigests })}`);
  assert.deepEqual(finalDigests, originalDigests, 'Shared demo database changed during isolated B12 tests');
});

test('B12 baseline: owned immutable snapshot saves unchanged and exact retry preserves ID and provenance', async () => {
  const location = ref(db('alice'), ownerPath('savedReadings/page_123_revision_456'));
  const value = reading();
  await assertSucceeds(createReading(db('alice'), 'page_123_revision_456', value));
  await assertSucceeds(set(location, value));
  assert.deepEqual((await assertSucceeds(get(location))).val(), value);
  assert.deepEqual(Object.keys((await get(ref(db('alice'), ownerPath('savedReadings')))).val()), ['page_123_revision_456']);
});

test('B12 baseline: accepted excerpt source revision attribution timestamps and course remain immutable', async () => {
  const location = ref(db('alice'), ownerPath('savedReadings/page_123'));
  const value = reading();
  await assertSucceeds(createReading(db('alice'), 'page_123', value));
  for (const patch of [
    { excerpt: 'Replacement excerpt' }, { sourceUrl: 'https://en.wikipedia.org/wiki/Computer' },
    { pageId: 124 }, { revisionId: 457, revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=457' },
    { attribution: 'Replacement attribution' }, { savedAt: time + 1 }, { retrievedAt: time + 1 },
    { courseId: 'it102' }
  ]) await assertFails(update(location, patch));
  assert.deepEqual((await get(location)).val(), value);
});

test('B12 baseline: foreign and unauthenticated clients cannot read write update or delete a saved snapshot', async () => {
  const path = ownerPath('savedReadings/page_123');
  await assertSucceeds(createReading(db('alice'), 'page_123'));
  for (const uid of ['bob', null]) {
    const location = ref(db(uid), path);
    await assertFails(get(location));
    await assertFails(set(location, reading()));
    await assertFails(update(location, { excerpt: 'Unauthorized' }));
    await assertFails(remove(location));
  }
  assert.deepEqual((await get(ref(db('alice'), path))).val(), reading());
});

test('B12 baseline: new snapshots reject unsafe source revision and license URLs', async () => {
  const unsafe = [
    { sourceUrl: 'http://en.wikipedia.org/wiki/Algorithm' },
    { sourceUrl: 'https://en.wikipedia.org.evil.example/wiki/Algorithm' },
    { sourceUrl: 'https://en.wikipedia.org@evil.example/wiki/Algorithm' },
    { sourceUrl: 'javascript:alert(1)' },
    { revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=457' },
    { licenseUrl: 'https://evil.example/licenses/by-sa/4.0/' },
    { licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/deed.en?redirect=evil' }
  ];
  for (const [index, patch] of unsafe.entries()) {
    await assertFails(createReading(db('alice'), `unsafe_${index}`, { ...reading(), ...patch }));
  }
});

test('B12 baseline: 8000-character excerpts save while oversized fields and malformed course IDs are rejected', async () => {
  await assertSucceeds(createReading(db('alice'), 'boundary', { ...reading(), excerpt: 'x'.repeat(8000) }));
  for (const [index, patch] of [
    { excerpt: 'x'.repeat(8001) }, { title: 'x'.repeat(241) }, { attribution: 'x'.repeat(501) },
    { courseId: 'invalid course ID' }
  ].entries()) {
    await assertFails(createReading(db('alice'), `invalid_${index}`, { ...reading(), ...patch }, 1));
  }
});

test('B12 legacy note resume paths and malformed embedded context remain denied', async () => {
  // New annotations and typed context references are covered by reading-shelf.rules.test.mjs.
  for (const collection of ['readingNotes', 'readingResume']) {
    await assertFails(set(ref(db('alice'), ownerPath(`${collection}/page_123`)), {
      readingId: 'page_123', text: 'Synthetic note', position: 1, updatedAt: time
    }));
  }
  await assertFails(createReading(db('alice'), 'page_with_context', {
    ...reading(), readingContext: { moduleId: 'programming_basics', version: 1 }
  }));
});

test('B12 contract: the Firebase rules reject a 21st saved reading', async () => {
  const client = db('alice');
  for (let index = 1; index <= 20; index++) {
    await assertSucceeds(createReading(client, `reading_${index}`, reading(), index - 1));
  }
  const result = await createReading(client, 'reading_21', reading(), 20)
    .then(() => ({ accepted: true }), error => ({ accepted: false, code: error.code }));
  const count = Object.keys((await get(ref(client, ownerPath('savedReadings')))).val()).length;
  console.log(`B12 21st-reading probe: ${JSON.stringify({ ...result, storedReadingCount: count })}`);
  assert.equal(result.accepted, false, `B12 requires rejection of the 21st reading; actual stored reading count: ${count}`);
  assert.equal(count, 20);
});
