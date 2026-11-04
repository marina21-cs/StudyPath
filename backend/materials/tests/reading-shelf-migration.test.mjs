import test from 'node:test';
import assert from 'node:assert/strict';
import { planReadingShelfMigration, migrateReadingShelf } from '../reading-shelf-migration.mjs';

const reading = () => ({ pageId: 123, pageKey: 'Algorithm', title: 'Algorithm', excerpt: 'A finite sequence.',
  sourceUrl: 'https://en.wikipedia.org/wiki/Algorithm', revisionId: 456,
  revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456', revisionTimestamp: '2026-10-22T00:00:00Z',
  licenseTitle: 'CC BY-SA 4.0', licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
  attribution: 'Wikipedia contributors', modificationNotice: 'Plain text.', savedAt: 42, retrievedAt: 42 });
const user = () => ({ profile: { displayName: 'Synthetic learner', selectedCurriculumVersion: 'demo' },
  savedReadings: { wiki_123_456: reading(), legacy_id: { ...reading(), courseId: 'it101' } },
  scores: { preserved: { untouched: true } }, lessonAttempts: { preserved: { opaque: 'unchanged' } } });
const response = (body, etag = '"v1"', status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json', ...(etag ? { etag } : {}) } });

test('lossless migration adds only a sorted slot index and version without changing its input', () => {
  const original = user(), before = structuredClone(original);
  const result = planReadingShelfMigration(original);
  assert.deepEqual(original, before);
  assert.deepEqual(result.user, { ...before, readingShelfVersion: 1,
    savedReadingSlots: { s00: 'legacy_id', s01: 'wiki_123_456' } });
  assert.equal(result.readingCount, 2);
  assert.equal(result.changed, true);
  assert.equal(result.snapshotsSha256Before, result.snapshotsSha256After);
  assert.equal(JSON.stringify(result).includes('pruned'), false);
});

test('valid active shelf migration is an exact no-op preserving existing slot positions', () => {
  const active = { ...user(), readingShelfVersion: 1, savedReadingSlots: { s13: 'legacy_id', s19: 'wiki_123_456' } };
  const result = planReadingShelfMigration(active);
  assert.deepEqual(result.user, active);
  assert.equal(result.changed, false);
});

test('empty legacy shelves activate without inventing empty Firebase map values', () => {
  const original = { profile: user().profile };
  assert.deepEqual(planReadingShelfMigration(original).user, { ...original, readingShelfVersion: 1 });
});

test('unrelated exported priorities survive and source-snapshot priority wrappers refuse migration', () => {
  const original = { ...user(), '.priority': 5, opaque: { '.value': 'untouched', '.priority': 2 } };
  const result = planReadingShelfMigration(original);
  assert.equal(result.user['.priority'], 5);
  assert.deepEqual(result.user.opaque, original.opaque);
  const bad = user(); bad.savedReadings.legacy_id.title = { '.value': 'Algorithm', '.priority': 1 };
  assert.throws(() => planReadingShelfMigration(bad), /SNAPSHOT_TEXT_INVALID/);
});

test('twenty readings migrate and twenty-one refuse without mutating or pruning legacy data', () => {
  const legacy = { savedReadings: Object.fromEntries(Array.from({ length: 20 }, (_, i) => [`legacy_${i}`, reading()])) };
  assert.equal(Object.keys(planReadingShelfMigration(legacy).user.savedReadingSlots).length, 20);
  legacy.savedReadings.reading_21 = reading();
  const before = structuredClone(legacy);
  assert.throws(() => planReadingShelfMigration(legacy), /OVER_LIMIT/);
  assert.deepEqual(legacy, before);
});

test('unknown versions partial old indexes malformed snapshots and malformed active bindings refuse migration', () => {
  const bad = [null, [], { ...user(), readingShelfVersion: 2 }, { ...user(), savedReadingSlots: { s00: 'legacy_id' } },
    { savedReadings: 'not an object' }, { savedReadings: { 'invalid id': reading() } },
    { savedReadings: { id: { ...reading(), excerpt: 'x'.repeat(8001) } } },
    { savedReadings: { id: { ...reading(), unexpected: true } } },
    { savedReadings: { id: { ...reading(), sourceUrl: 'https://evil.example/wiki/Algorithm' } } },
    { ...user(), readingShelfVersion: 1, savedReadingSlots: { s00: 'missing' } },
    { ...user(), readingShelfVersion: 1, savedReadingSlots: { s20: 'legacy_id' } },
    { ...user(), readingShelfVersion: 1, savedReadingSlots: { s00: 'legacy_id' } }];
  for (const candidate of bad) assert.throws(() => planReadingShelfMigration(candidate));
});

test('dry-run uses loopback owner GET and returns only sanitized counts and hashes', async () => {
  const requests = [];
  const result = await migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice',
    fetchImpl: async (url, options) => { requests.push({ url, options }); return response(user()); } });
  assert.equal(requests.length, 1);
  assert.match(requests[0].url, /^http:\/\/127[.]0[.]0[.]1:9000\/users\/alice[.]json\?ns=demo-studypath-migration-unit&format=export$/);
  assert.equal(requests[0].options.headers['X-Firebase-ETag'], 'true');
  assert.equal(result.applied, false);
  assert.equal(result.readingCount, 2);
  assert.equal(JSON.stringify(result).includes('Synthetic learner'), false);
  assert.equal(JSON.stringify(result).includes('A finite sequence.'), false);
});

test('apply uses an ETag conditional PUT of the lossless full user snapshot and verifies readback', async () => {
  const original = user(), requests = [], migrated = planReadingShelfMigration(original).user;
  const result = await migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice', apply: true,
    fetchImpl: async (url, options) => {
      requests.push({ url, options });
      if (requests.length === 1) return response(original);
      if (requests.length === 2) { assert.equal(options.method, 'PUT'); assert.equal(options.headers['if-match'], '"v1"');
        assert.deepEqual(JSON.parse(options.body), migrated); return response(migrated, '"v2"'); }
      return response(migrated, '"v2"');
    } });
  assert.equal(requests.length, 3);
  assert.equal(result.applied, true);
  assert.equal(result.verified, true);
});

test('a concurrent change yields a conflict and never retries an unconditional write', async () => {
  let calls = 0;
  await assert.rejects(migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice', apply: true,
    fetchImpl: async () => ++calls === 1 ? response(user()) : response({ concurrent: true }, '"v2"', 412) }), /CONFLICT/);
  assert.equal(calls, 2);
});

test('no ETag malformed responses and failed readback cannot become a verified migration', async () => {
  await assert.rejects(migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice', apply: true,
    fetchImpl: async () => response(user(), null) }), /ETAG/);
  await assert.rejects(migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice', apply: true,
    fetchImpl: async () => new Response('{') }), /JSON/);
  let calls = 0;
  await assert.rejects(migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice', apply: true,
    fetchImpl: async () => ++calls === 1 ? response(user()) : calls === 2 ? response({}) : response(user()) }), /READBACK/);
});

test('invalid namespaces UIDs and nonboolean apply flags are rejected before any request', async () => {
  for (const options of [{ namespace: 'production' }, { namespace: 'https://evil.example' }, { uid: '../bob' },
    { uid: '' }, { apply: 'true' }]) {
    await assert.rejects(migrateReadingShelf({ namespace: 'demo-studypath-migration-unit', uid: 'alice', ...options,
      fetchImpl: async () => { assert.fail('No request should be issued'); } }));
  }
});
