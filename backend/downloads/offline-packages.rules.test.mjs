import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, remove, update, serverTimestamp } from 'firebase/database';
import { fixture, addReviewedPdf } from '../catalog/tests/fixture.mjs';
import { summarizeCandidate } from '../catalog/summarize-candidate.mjs';
import { packageCandidate } from '../catalog/package-candidate.mjs';

// All review metadata is fabricated test data in a separate emulator namespace.
const projectId = 'demo-studypath-b10-download-rules';
assert.notEqual(projectId, 'demo-studypath-default-rtdb');
const shared = 'http://127.0.0.1:9000/.json?ns=demo-studypath-default-rtdb';
const digest = async () => {
  const response = await fetch(shared, { headers: { Authorization: 'Bearer owner' } });
  assert.equal(response.status, 200);
  return createHash('sha256').update(JSON.stringify(await response.json())).digest('hex');
};
const raw = fixture();
const candidate = { ...raw, manifestBytes: Buffer.from(JSON.stringify(raw.manifest)) };
const summary = summarizeCandidate(candidate), transport = packageCandidate(candidate);
const pdfRaw = addReviewedPdf(fixture());
const pdfCandidate = { ...pdfRaw, manifestBytes: Buffer.from(JSON.stringify(pdfRaw.manifest)) };
const pdfSummary = summarizeCandidate(pdfCandidate), pdfTransport = packageCandidate(pdfCandidate);
const suffix = 'fixture_v1/fixture_module/v1';
const publicIndex = 'catalog/moduleIndex/' + suffix;
const publicPackage = 'catalog/modulePackages/' + suffix;
const ownedPath = 'users/alice/offlinePackages/' + suffix;
const record = () => ({ schemaVersion: 1, moduleId: 'fixture_module', version: 1,
  curriculumVersion: 'fixture_v1', packageSha256: summary.packageSha256, textBytes: summary.textBytes,
  downloadedAt: 42, confirmedAt: 43 });
const kept = { profile: { displayName: 'Synthetic learner', selectedCurriculumVersion: 'bsit_demo_v1' },
  scores: { q1: { courseId: 'it101', assessmentId: 'q1', categoryId: 'quiz', planVersion: 'demo_v1',
    status: 'SCORED', possiblePoints: 20, earnedPoints: 0 } },
  reviewProgress: { fixture_module: { v1: { moduleId: 'fixture_module', version: 1, completed: false, lastSection: 0 } } },
  practiceAttempts: { attempt1: { moduleId: 'fixture_module', version: 1, correct: 1, total: 2, submittedAt: 42 } } };
let env, original;
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const owner = () => ref(db('alice'), ownedPath);
const adminSet = (path, value) => env.withSecurityRulesDisabled(context => set(ref(context.database(), path), value));
before(async () => {
  original = await digest();
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 9000,
    rules: await readFile(new URL('../database.rules.json', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearDatabase();
  await adminSet(publicIndex, summary); await adminSet(publicPackage, transport);
  // These are retained pre-policy migration controls, not newly authorized academic writes.
  await adminSet('users/alice', kept);
});
after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  assert.equal(await digest(), original, 'Shared demo database changed during isolated download tests');
});

test('owner creates reads and exactly retries an immutable version receipt with a refreshed confirmation', async () => {
  await assertSucceeds(set(owner(), record()));
  assert.deepEqual((await get(owner())).val(), record());
  await assertSucceeds(set(owner(), record()));
  await assertSucceeds(update(owner(), { confirmedAt: serverTimestamp() }));
  const actual = (await get(owner())).val();
  assert.equal(Number.isInteger(actual.confirmedAt), true);
  assert.equal(actual.downloadedAt, 42);
});

test('a reviewed PDF package receives the same immutable receipt and exact retry as a text package', async () => {
  assert.equal(pdfSummary.assetBytes, pdfRaw.files.get('source.pdf').length);
  assert.ok(pdfSummary.assetBytes > 0);
  await adminSet(publicIndex, pdfSummary); await adminSet(publicPackage, pdfTransport);
  const pdfRecord = { ...record(), packageSha256: pdfSummary.packageSha256, textBytes: pdfSummary.textBytes };
  await assertSucceeds(set(owner(), pdfRecord));
  await assertSucceeds(set(owner(), pdfRecord));
  await assertSucceeds(update(owner(), { confirmedAt: 44 }));
  assert.deepEqual((await get(owner())).val(), { ...pdfRecord, confirmedAt: 44 });
});

test('asset-bearing receipts retain owner hash and review permission guards', async () => {
  await adminSet(publicIndex, pdfSummary); await adminSet(publicPackage, pdfTransport);
  const pdfRecord = { ...record(), packageSha256: pdfSummary.packageSha256, textBytes: pdfSummary.textBytes };
  for (const uid of ['bob', null]) await assertFails(set(ref(db(uid), ownedPath), pdfRecord));
  await assertFails(set(owner(), { ...pdfRecord, packageSha256: '0'.repeat(64) }));
  for (const review of [{ status: 'DEMONSTRATION_ONLY' }, { adaptationAllowed: false }, { redistributionAllowed: false }]) {
    await adminSet(publicIndex, { ...pdfSummary, review: { ...pdfSummary.review, ...review } });
    await assertFails(set(owner(), pdfRecord));
  }
});

test('published total asset sizes must be nonnegative integers within the complete-package ceiling', async () => {
  // The index is maintainer-owned; runtime download independently verifies actual bytes and hashes.
  for (const assetBytes of [-1, 0.5, 104857601, '64', null]) {
    await adminSet(publicIndex, { ...summary, assetBytes });
    await assertFails(set(owner(), record()));
  }
  await adminSet(publicIndex, { ...summary, assetBytes: 104857600 });
  await assertSucceeds(set(owner(), record()));
});

test('local download time may be ahead of independently measured server confirmation time', async () => {
  const ahead = { ...record(), downloadedAt: 2_000_000, confirmedAt: 43 };
  await assertSucceeds(set(owner(), ahead));
  assert.deepEqual((await get(owner())).val(), ahead);
  await assertSucceeds(update(owner(), { confirmedAt: 44 }));
  assert.equal((await get(owner())).val().downloadedAt, ahead.downloadedAt);
  await assertFails(update(owner(), { downloadedAt: 1_999_999 }));
});

test('foreign users and unauthenticated clients cannot read write or remove another owner receipt', async () => {
  await assertSucceeds(set(owner(), record()));
  for (const uid of ['bob', null]) {
    await assertFails(get(ref(db(uid), ownedPath)));
    await assertFails(set(ref(db(uid), ownedPath), record()));
    await assertFails(remove(ref(db(uid), ownedPath)));
  }
});

test('authenticated public transport reads work while every client publication mutation is denied', async () => {
  assert.deepEqual((await assertSucceeds(get(ref(db('alice'), publicPackage)))).val(), transport);
  await assertFails(get(ref(db(null), publicPackage)));
  for (const path of [publicPackage, publicIndex]) {
    await assertFails(set(ref(db('alice'), path), {}));
    await assertFails(remove(ref(db('alice'), path)));
  }
  await assertFails(update(ref(db('alice')), { [ownedPath]: record(), [publicPackage + '/schemaVersion']: 2 }));
  assert.equal((await get(owner())).exists(), false);
});

test('missing version references mismatched index identity and invalid package transport are denied', async () => {
  for (const path of [ownedPath.replace('fixture_v1', 'missing'), ownedPath.replace('fixture_module', 'missing'),
    ownedPath.replace('/v1', '/v2'), ownedPath.replace('/v1', '/v01')]) {
    await assertFails(set(ref(db('alice'), path), record()));
  }
  for (const override of [{ moduleId: 'wrong' }, { version: 2 }, { schemaVersion: 2 },
    { textBytes: summary.textBytes + 1 }, { packageSha256: '0'.repeat(64) },
    { visibility: 'PRIVATE' }]) {
    await adminSet(publicIndex, { ...summary, ...override });
    await assertFails(set(owner(), record()));
  }
  await adminSet(publicIndex, summary);
  for (const value of [null, false, { ...transport, schemaVersion: 2 }, { schemaVersion: 1 }]) {
    await adminSet(publicPackage, value);
    await assertFails(set(owner(), record()));
  }
});

test('unapproved source permissions cannot receive a completed package receipt', async () => {
  for (const override of [{ status: 'DEMONSTRATION_ONLY' }, { adaptationAllowed: false }, { redistributionAllowed: false }]) {
    await adminSet(publicIndex, { ...summary, review: { ...summary.review, ...override } });
    await assertFails(set(owner(), record()));
  }
});

test('exact fields types integer ranges keys and version identities fail closed', async () => {
  for (const override of [{ schemaVersion: 2 }, { moduleId: 'other' }, { curriculumVersion: 'other' },
    { version: 1.5 }, { version: 0 }, { version: 1_000_001 }, { packageSha256: 'A'.repeat(64) },
    { textBytes: 0 }, { textBytes: 100_000 }, { textBytes: 1.5 }, { downloadedAt: -1 },
    { downloadedAt: 42.5 }, { confirmedAt: 4_102_444_800_001 }, { confirmedAt: 'now' }, { unexpected: true }]) {
    await assertFails(set(owner(), { ...record(), ...override }));
  }
  for (const field of Object.keys(record())) {
    const value = record(); delete value[field]; await assertFails(set(owner(), value));
  }
  await assertFails(set(ref(db('alice'), ownedPath.replace('fixture_v1', 'invalid context')), record()));
  await assertFails(set(ref(db('alice'), ownedPath.replace('fixture_module', 'invalid module')), record()));
});

test('retry cannot change original download time or accepted version identity', async () => {
  await assertSucceeds(set(owner(), record()));
  await assertFails(update(owner(), { downloadedAt: 44 }));
  await assertFails(update(owner(), { packageSha256: '0'.repeat(64) }));
  await assertFails(remove(ref(db('alice'), ownedPath + '/textBytes')));
  assert.deepEqual((await get(owner())).val(), record());
});

test('removing one retained version preserves another version and all learner work', async () => {
  await assertSucceeds(set(owner(), record()));
  // A second immutable version is independently retained; the synthetic identity is only a rules fixture.
  const second = { ...record(), version: 2 };
  await adminSet(publicIndex.replace('/v1', '/v2'), { ...summary, version: 2 });
  await adminSet(publicPackage.replace('/v1', '/v2'), transport);
  await assertSucceeds(set(ref(db('alice'), ownedPath.replace('/v1', '/v2')), second));
  await assertSucceeds(remove(owner())); await assertSucceeds(remove(owner()));
  assert.equal((await get(owner())).exists(), false);
  assert.deepEqual((await get(ref(db('alice'), ownedPath.replace('/v1', '/v2')))).val(), second);
  const actual = (await get(ref(db('alice'), 'users/alice'))).val();
  for (const [key, value] of Object.entries(kept)) assert.deepEqual(actual[key], value);
});

test('owner can remove its retained receipt after the public source version is unavailable', async () => {
  await assertSucceeds(set(owner(), record()));
  await adminSet(publicIndex, null); await adminSet(publicPackage, null);
  await assertSucceeds(remove(owner()));
  assert.equal((await get(owner())).exists(), false);
});
