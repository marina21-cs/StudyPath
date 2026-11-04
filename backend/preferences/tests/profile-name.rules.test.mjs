import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update } from 'firebase/database';

// Separate port and namespace: never read, write or clear the running device-test database.
const projectId = 'demo-studypath-ux-r3';
const address = process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? '127.0.0.1:29200';
const [host, port] = address.split(':');
let env;
const db = (uid = 'alice') => env.authenticatedContext(uid).database();
const path = 'users/alice/profile';
const draft = { schemaVersion: 1, onboardingComplete: false, levelId: 'college', subjectIds: { programming: true } };
before(async () => {
  env = await initializeTestEnvironment({ projectId, database: { host, port: Number(port),
    rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearDatabase();
  await set(ref(db(), path), { displayName: 'Original', selectedCurriculumVersion: 'bsit_demo_v1', createdAt: 42, updatedAt: 42 });
});
after(async () => { if (env) { await env.clearDatabase(); await env.cleanup(); } });

test('name and preference patch persists together and preserves creation fields', async () => {
  await assertSucceeds(update(ref(db(), path), { displayName: 'Keni', learnerPreferences: draft, updatedAt: 43 }));
  const value = (await get(ref(db(), path))).val();
  assert.equal(value.displayName, 'Keni'); assert.deepEqual(value.learnerPreferences, draft);
  assert.equal(value.createdAt, 42); assert.equal(value.selectedCurriculumVersion, 'bsit_demo_v1');
});
test('blank or oversized names reject the whole patch', async () => {
  for (const name of ['', 'x'.repeat(81)]) {
    await assertFails(update(ref(db(), path), { displayName: name, learnerPreferences: draft, updatedAt: 43 }));
    const value = (await get(ref(db(), path))).val();
    assert.equal(value.displayName, 'Original'); assert.equal(value.learnerPreferences, undefined);
  }
});
test('cross-owner name and preference mutation is denied', async () => {
  await assertFails(update(ref(db('bob'), path), { displayName: 'Other', learnerPreferences: draft, updatedAt: 43 }));
  assert.equal((await get(ref(db(), path))).val().displayName, 'Original');
});
test('profile name cannot smuggle unsupported curriculum completion', async () => {
  await assertFails(update(ref(db(), path), { displayName: 'Keni', learnerPreferences: {
    ...draft, curriculumVersion: 'unpublished_v1', onboardingComplete: true
  }, updatedAt: 43 }));
  assert.equal((await get(ref(db(), path))).val().displayName, 'Original');
});
