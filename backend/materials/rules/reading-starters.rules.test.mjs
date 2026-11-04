import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove } from 'firebase/database';

const fixture = JSON.parse(await readFile(new URL('../../seed/reading-starters.json', import.meta.url), 'utf8'));
const projectId = 'demo-studypath-reading-starters-rules';
assert.notEqual(projectId, 'demo-studypath');
assert.notEqual(projectId, 'demo-studypath-default-rtdb');
const shared = 'http://127.0.0.1:9000/.json?ns=demo-studypath-default-rtdb';
const catalogPath = 'catalog/readingStarters';
const firstId = Object.keys(fixture)[0];
const firstPath = `${catalogPath}/${firstId}`;
const profile = uid => ({ displayName: `${uid} rules fixture`, selectedCurriculumVersion: 'bsit_demo_v1', createdAt: 42, updatedAt: 42 });
let env, originalDigest;
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();

async function sharedDigest() {
  const response = await fetch(shared, { headers: { Authorization: 'Bearer owner' } });
  assert.equal(response.status, 200);
  return createHash('sha256').update(JSON.stringify(await response.json())).digest('hex');
}

before(async () => {
  assert.equal(Object.keys(fixture).length, 3, 'Load exactly the three gathered starter records');
  originalDigest = await sharedDigest();
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 9000,
    rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') } });
});

beforeEach(async () => {
  // This clear applies only to the isolated rules namespace, never the shared demonstration database.
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async context => {
    const admin = context.database();
    await set(ref(admin, catalogPath), fixture);
    await set(ref(admin, 'users/alice/profile'), profile('alice'));
    await set(ref(admin, 'users/bob/profile'), profile('bob'));
  });
});

after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  assert.equal(await sharedDigest(), originalDigest, 'Shared demo database changed during isolated reading-starter rules tests');
});

test('authenticated learners read the exact three gathered supplemental records', async () => {
  for (const uid of ['alice', 'bob']) {
    const result = await assertSucceeds(get(ref(db(uid), catalogPath)));
    assert.deepEqual(result.val(), fixture);
    assert.equal(Object.keys(result.val()).length, 3);
  }
});

test('unauthenticated whole-catalog and individual starter reads remain denied', async () => {
  await assertFails(get(ref(db(null), catalogPath)));
  await assertFails(get(ref(db(null), firstPath)));
});

test('every client is denied starter creation updates deletion and forged source metadata', async () => {
  for (const uid of ['alice', 'bob', null]) {
    const client = db(uid);
    await assertFails(set(ref(client, `${catalogPath}/wiki_1_1`), {
      ...fixture[firstId], pageId: 1, revisionId: 1, revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=1'
    }));
    await assertFails(update(ref(client, firstPath), { title: 'Client replacement' }));
    await assertFails(remove(ref(client, firstPath)));
    await assertFails(update(ref(client, firstPath), { sourceUrl: 'https://example.org/forged', approved: true }));
  }
  assert.deepEqual((await get(ref(db('alice'), catalogPath))).val(), fixture);
});

test('mixed owner and catalog mutation fails atomically and retains both records', async () => {
  const client = db('alice');
  await assertFails(update(ref(client), {
    'users/alice/profile/displayName': 'Attempted owner update',
    [`${firstPath}/title`]: 'Attempted catalog update'
  }));
  assert.deepEqual((await get(ref(client, 'users/alice/profile'))).val(), profile('alice'));
  assert.deepEqual((await get(ref(client, catalogPath))).val(), fixture);
  await env.withSecurityRulesDisabled(async context => {
    assert.deepEqual((await get(ref(context.database(), 'users/bob/profile'))).val(), profile('bob'));
  });
});

test('public starter access grants no cross-UID or unauthenticated private-record access', async () => {
  for (const [uid, target] of [['alice', 'bob'], ['bob', 'alice'], [null, 'alice']]) {
    const client = db(uid);
    await assertFails(get(ref(client, `users/${target}/profile`)));
    await assertFails(update(ref(client, `users/${target}/profile`), { displayName: 'Unauthorized' }));
    await assertFails(get(ref(client, `users/${target}/savedReadings`)));
  }
  await assertFails(get(ref(db('alice'), 'users')));
  assert.deepEqual((await get(ref(db('alice'), 'users/alice/profile'))).val(), profile('alice'));
});
