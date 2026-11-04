import test from 'node:test';
import assert from 'node:assert/strict';
import { validateReadingStarters, planReadingStarterSeed, assertLocalEmulatorHost } from '../reading-starters.mjs';

const record = () => ({
  schemaVersion: 1, kind: 'SUPPLEMENTAL', provider: 'ENGLISH_WIKIPEDIA',
  pageId: 123, pageKey: 'Computer_programming', title: 'Computer programming',
  description: 'Explore how people express instructions for computers.', topicQuery: 'computer programming',
  revisionId: 456, revisionTimestamp: '2026-10-25T12:34:56Z',
  sourceUrl: 'https://en.wikipedia.org/wiki/Computer_programming',
  revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456',
  licenseTitle: 'Creative Commons Attribution-ShareAlike 4.0',
  licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/deed.en',
  attribution: 'Computer programming contributors, English Wikipedia.', retrievedAt: 1_793_000_000_000
});
const batch = () => ({ wiki_123_456: record() });
const bytes = value => Buffer.from(JSON.stringify(value));
const rejectMutation = (field, value) => {
  const candidate = batch(); candidate.wiki_123_456[field] = value;
  assert.throws(() => validateReadingStarters(bytes(candidate)));
};

test('accepts a supplemental record and preserves source, revision and license metadata', () => {
  assert.deepEqual(validateReadingStarters(bytes(batch())), batch());
});

test('rejects empty, array and over-cap batches', () => {
  for (const value of [{}, [], null, 'record']) assert.throws(() => validateReadingStarters(bytes(value)));
  const many = {};
  for (let i = 1; i <= 21; i++) many[`wiki_${i}_456`] = { ...record(), pageId: i };
  assert.throws(() => validateReadingStarters(bytes(many)));
});

test('accepts the maximum batch size with distinct source identities', () => {
  const many = {};
  for (let i = 1; i <= 20; i++) many[`wiki_${i}_456`] = { ...record(), pageId: i };
  assert.equal(Object.keys(validateReadingStarters(bytes(many))).length, 20);
});

test('rejects malformed UTF-8 and malformed JSON', () => {
  for (const value of [Buffer.from([0xff]), Buffer.from('{'), Buffer.from('{"x":NaN}')]) {
    assert.throws(() => validateReadingStarters(value));
  }
  assert.throws(() => validateReadingStarters(JSON.stringify(batch())));
});

test('raw input must stay strictly below 100000 UTF-8 bytes', () => {
  const body = bytes(batch());
  assert.deepEqual(validateReadingStarters(Buffer.concat([body, Buffer.alloc(99_999 - body.length, 32)])), batch());
  assert.throws(() => validateReadingStarters(Buffer.concat([body, Buffer.alloc(100_000 - body.length, 32)])));
});

test('rejects missing or additional record fields including approval and lesson claims', () => {
  const missing = batch(); delete missing.wiki_123_456.attribution;
  assert.throws(() => validateReadingStarters(bytes(missing)));
  for (const field of ['approved', 'curriculumVersion', 'lesson', 'html', 'excerpt']) rejectMutation(field, 'not permitted');
});

test('schema, supplemental kind and provider are exact typed constants', () => {
  for (const [field, values] of Object.entries({ schemaVersion: [2, '1', null], kind: ['APPROVED', true], provider: ['WIKIPEDIA', false] })) {
    for (const value of values) rejectMutation(field, value);
  }
});

test('record identity must match positive safe numeric page and revision IDs', () => {
  for (const field of ['pageId', 'revisionId']) {
    for (const value of [0, -1, 1.2, '123', Number.MAX_SAFE_INTEGER + 1, null]) rejectMutation(field, value);
  }
  for (const key of ['wiki_123_457', 'wiki_0123_456', 'other', '__proto__']) {
    assert.throws(() => validateReadingStarters(bytes({ [key]: record() })));
  }
});

test('requires bounded nonblank metadata strings', () => {
  for (const [field, max] of Object.entries({ pageKey: 240, title: 240, description: 500, topicQuery: 120, licenseTitle: 80, attribution: 500 })) {
    for (const value of ['', ' \n ', null, false, 13, 'x'.repeat(max + 1)]) rejectMutation(field, value);
  }
});

test('validates strict real UTC revision dates without normalization', () => {
  for (const value of ['2026-02-30T12:00:00Z', '2026-13-01T12:00:00Z', '2026-10-25T24:00:00Z',
    '2026-10-25T12:34:56.000Z', '2026-10-25T12:34:56+00:00', '2026-10-25', 1]) rejectMutation('revisionTimestamp', value);
  const leap = batch(); leap.wiki_123_456.revisionTimestamp = '2024-02-29T00:00:00Z';
  assert.equal(validateReadingStarters(bytes(leap)).wiki_123_456.revisionTimestamp, '2024-02-29T00:00:00Z');
});

test('source and immutable revision URLs must match identity exactly', () => {
  for (const value of ['http://en.wikipedia.org/wiki/Computer_programming', 'https://example.org/wiki/Computer_programming',
    'https://en.wikipedia.org/wiki/Computer_programming?oldid=456', 'https://en.wikipedia.org/wiki/Other']) rejectMutation('sourceUrl', value);
  for (const value of ['https://en.wikipedia.org/w/index.php?oldid=457', 'https://en.wikipedia.org/w/index.php?oldid=0456',
    'http://en.wikipedia.org/w/index.php?oldid=456']) rejectMutation('revisionUrl', value);
  const encoded = batch(); encoded.wiki_123_456.pageKey = 'A/B and C';
  encoded.wiki_123_456.sourceUrl = 'https://en.wikipedia.org/wiki/A%2FB%20and%20C';
  assert.deepEqual(validateReadingStarters(bytes(encoded)), encoded);
});

test('canonical source URL matches Java encoding for punctuation and Unicode', () => {
  const candidate = batch();
  candidate.wiki_123_456.pageKey = "Functions (math)!'~* 中";
  candidate.wiki_123_456.sourceUrl = 'https://en.wikipedia.org/wiki/Functions%20%28math%29%21%27%7E*%20%E4%B8%AD';
  assert.deepEqual(validateReadingStarters(bytes(candidate)), candidate);
  candidate.wiki_123_456.sourceUrl = 'https://en.wikipedia.org/wiki/' + encodeURIComponent(candidate.wiki_123_456.pageKey);
  assert.throws(() => validateReadingStarters(bytes(candidate)), /SOURCE_URL_INVALID/);
  for (const key of ['\ud800', '\udc00', 'Invalid\ud800title']) {
    candidate.wiki_123_456.pageKey = key;
    assert.throws(() => validateReadingStarters(bytes(candidate)), /SOURCE_URL_INVALID/);
  }
});

test('license URLs only identify the permitted Creative Commons ShareAlike deed forms', () => {
  for (const value of ['https://creativecommons.org/licenses/by-sa/4.0/', 'https://creativecommons.org/licenses/by-sa/3.0/deed.en']) {
    const candidate = batch(); candidate.wiki_123_456.licenseUrl = value;
    assert.equal(validateReadingStarters(bytes(candidate)).wiki_123_456.licenseUrl, value);
  }
  for (const value of ['http://creativecommons.org/licenses/by-sa/4.0/', 'https://creativecommons.org/licenses/by/4.0/',
    'https://creativecommons.org/licenses/by-sa/4.0/?x=1', 'https://creativecommons.org/licenses/by-sa/4.0/deed.fr']) rejectMutation('licenseUrl', value);
});

test('retrieval timestamps are bounded integral milliseconds including zero', () => {
  for (const value of [-1, 0.5, '0', null, 4_102_444_800_001]) rejectMutation('retrievedAt', value);
  for (const value of [0, 4_102_444_800_000]) {
    const candidate = batch(); candidate.wiki_123_456.retrievedAt = value;
    assert.equal(validateReadingStarters(bytes(candidate)).wiki_123_456.retrievedAt, value);
  }
});

test('seed plan includes only new entries and does not mutate either input', () => {
  const incoming = batch(), existing = { wiki_999_888: { unrelated: 'preserved' }, wiki_123_456: null };
  const before = structuredClone({ incoming, existing });
  assert.deepEqual(planReadingStarterSeed(incoming, existing), { newEntries: incoming, reusedIds: [], total: 1 });
  assert.deepEqual({ incoming, existing }, before);
});

test('exact existing data is an idempotent retry regardless of object field order', () => {
  const reordered = Object.fromEntries(Object.entries(record()).reverse());
  assert.deepEqual(planReadingStarterSeed(batch(), { wiki_123_456: reordered }), { newEntries: {}, reusedIds: ['wiki_123_456'], total: 1 });
});

test('same ID with conflicting data rejects the entire plan before any write', () => {
  const candidate = batch(); candidate.wiki_124_456 = { ...record(), pageId: 124 };
  const conflicting = { ...record(), title: 'changed' };
  assert.throws(() => planReadingStarterSeed(candidate, { wiki_123_456: conflicting, wiki_124_456: null }), /CONFLICT/);
});

test('seed plan rejects incomplete preflight snapshots or malformed incoming data', () => {
  assert.throws(() => planReadingStarterSeed(batch(), {}));
  assert.throws(() => planReadingStarterSeed({ bad: record() }, { bad: null }));
});

test('local seeding accepts only the explicit loopback emulator host', () => {
  assert.equal(assertLocalEmulatorHost('127.0.0.1:9000'), 'http://127.0.0.1:9000');
  for (const value of [undefined, '', 'localhost:9000', '127.0.0.1:9001', 'https://example.firebaseio.com', '127.0.0.1:9000/path']) {
    assert.throws(() => assertLocalEmulatorHost(value), /LOCAL_EMULATOR_REQUIRED/);
  }
});
