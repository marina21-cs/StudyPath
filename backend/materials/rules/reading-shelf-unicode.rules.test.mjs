import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update } from 'firebase/database';

const projectId = 'demo-studypath-b12-unicode';
const protectedNamespaces = ['demo-studypath', 'demo-studypath-default-rtdb'];
assert.ok(!protectedNamespaces.includes(projectId));
const excerpt = 'A😀e\u0301Z'; // Astral emoji and a decomposed combining mark.
const utf16Length = excerpt.length;
const codePointLength = [...excerpt].length;
assert.equal(utf16Length, 6);
assert.equal(codePointLength, 5);
const source = { pageId: 123, pageKey: 'Algorithm', title: 'Unicode source fixture', excerpt,
  sourceUrl: 'https://en.wikipedia.org/wiki/Algorithm', revisionId: 456,
  revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456', revisionTimestamp: '2026-10-22T00:00:00Z',
  licenseTitle: 'CC BY-SA 4.0', licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/deed.en',
  attribution: 'Wikipedia contributors', modificationNotice: 'Synthetic Unicode test excerpt.',
  savedAt: 1792627200000, retrievedAt: 1792627200000 };
const annotation = { snapshotRevisionId: 456, excerptLength: utf16Length, note: 'Unicode fixture',
  position: utf16Length, editId: 'unicode_edit', modifiedAt: 1792627200000 };
let env, originalDigests;
const db = () => env.authenticatedContext('unicode_owner').database();
const path = suffix => `users/unicode_owner/${suffix}`;
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
beforeEach(async () => {
  await env.clearDatabase(); // Only demo-studypath-b12-unicode.
  await assertSucceeds(update(ref(db(), 'users/unicode_owner'), { readingShelfVersion: 1,
    'savedReadings/wiki_123_456': source, 'savedReadingSlots/s00': 'wiki_123_456' }));
});
after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  const finalDigests = await sharedDigests();
  console.log(`Shared namespace digest guard: ${JSON.stringify({ before: originalDigests, after: finalDigests })}`);
  assert.deepEqual(finalDigests, originalDigests);
});

test('actual RTDB rules accept UTF16 excerpt length and position and reject code-point-only metadata', async () => {
  const location = ref(db(), path('readingAnnotations/wiki_123_456'));
  await assertSucceeds(set(location, annotation));
  assert.deepEqual((await get(location)).val(), annotation);
  await assertFails(set(location, { ...annotation, excerptLength: codePointLength, position: codePointLength }));
  await assertFails(set(location, { ...annotation, position: utf16Length + 1 }));
  assert.deepEqual((await get(location)).val(), annotation);
  assert.equal((await get(ref(db(), path('savedReadings/wiki_123_456/excerpt')))).val(), excerpt);
  console.log(`Unicode excerpt observation: ${JSON.stringify({ utf16Length, codePointLength, acceptedPosition: utf16Length, rejectedCodePointOnlyLength: codePointLength })}`);
});

test('actual RTDB note bound counts astral emoji as two UTF16 units and preserves combining text', async () => {
  const location = ref(db(), path('readingAnnotations/wiki_123_456'));
  const boundary = { ...annotation, note: '😀'.repeat(2000) };
  assert.equal(boundary.note.length, 4000);
  assert.equal([...boundary.note].length, 2000);
  await assertSucceeds(set(location, boundary));
  assert.equal((await get(location)).val().note, boundary.note);
  await assertFails(set(location, { ...annotation, note: '😀'.repeat(2001) }));
  const combining = { ...annotation, note: 'e\u0301'.repeat(2000) };
  assert.equal(combining.note.length, 4000);
  await assertSucceeds(set(location, combining));
  assert.equal((await get(location)).val().note, combining.note);
  console.log(`Unicode note observation: ${JSON.stringify({ acceptedAstralCodePoints: 2000, acceptedUtf16Units: 4000,
    rejectedAstralCodePoints: 2001, rejectedUtf16Units: 4002, combiningUtf16UnitsAccepted: combining.note.length })}`);
});
