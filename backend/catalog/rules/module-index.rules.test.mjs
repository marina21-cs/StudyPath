import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, remove, update } from 'firebase/database';

const fixture = JSON.parse(await readFile(new URL('../../../android/test-fixtures/catalog/module-index.json', import.meta.url)));
const projectId = 'demo-studypath-b09-index-rules';
const shared = 'http://127.0.0.1:9000/.json?ns=demo-studypath-default-rtdb';
const digest = async () => createHash('sha256').update(await (await fetch(shared, { headers: { Authorization: 'Bearer owner' } })).text()).digest('hex');
let env, original;
const db = uid => uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database();
const path = 'catalog/moduleIndex/fixture_v1/fixture_module/v1';
const fields = snapshot => {
  if (!snapshot.hasChildren()) return snapshot.val();
  const result = {}; snapshot.forEach(child => { result[child.key] = fields(child); }); return result;
};
before(async () => {
  original = await digest();
  env = await initializeTestEnvironment({ projectId, database: { host: '127.0.0.1', port: 9000,
    rules: await readFile(new URL('../../database.rules.json', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(context => set(ref(context.database(), 'catalog'), fixture.catalog));
});
after(async () => {
  if (env) { await env.clearDatabase(); await env.cleanup(); }
  assert.equal(await digest(), original, 'Shared demo database changed during isolated index tests');
});
test('authenticated learners read exact public immutable version metadata', async () => {
  for (const uid of ['alice', 'bob']) {
    const result = await assertSucceeds(get(ref(db(uid), path)));
    assert.deepEqual(fields(result), fixture.catalog.moduleIndex.fixture_v1.fixture_module.v1);
  }
});
test('unauthenticated catalog reads and writes remain denied', async () => {
  await assertFails(get(ref(db(null), path)));
  await assertFails(set(ref(db(null), path), fixture.catalog.moduleIndex.fixture_v1.fixture_module.v1));
});
test('clients cannot create alter delete or forge publication metadata', async () => {
  const client = db('alice');
  await assertFails(set(ref(client, path.replace('/v1', '/v9')), fixture.catalog.moduleIndex.fixture_v1.fixture_module.v1));
  await assertFails(update(ref(client, path), { packageSha256: '0'.repeat(64) }));
  await assertFails(remove(ref(client, path)));
  await assertFails(update(ref(client, path + '/review'), { reviewer: 'forged', status: 'APPROVED' }));
  assert.deepEqual(fields(await get(ref(client, path))), fixture.catalog.moduleIndex.fixture_v1.fixture_module.v1);
});
test('section preference gives no private assignment or membership access', async () => {
  const client = db('alice');
  await set(ref(client, 'users/alice/profile'), { displayName: 'Synthetic', selectedCurriculumVersion: 'bsit_demo_v1',
    learnerPreferences: { schemaVersion: 1, onboardingComplete: true, curriculumVersion: 'fixture_v1', levelId: 'fixture_level',
      subjectIds: { fixture_subject: true }, classSectionId: '0' } });
  await assertFails(get(ref(client, 'classAssignments/0')));
  await assertFails(set(ref(client, 'classAssignments/0/alice'), true));
  await assertFails(set(ref(client, 'users/alice/classMembership/0'), true));
  await assertFails(get(ref(db('bob'), 'users/alice/profile')));
});
test('mixed catalog and owner mutation fails atomically', async () => {
  const client = db('alice');
  await assertFails(update(ref(client), { 'users/alice/profile': { displayName: 'Synthetic', selectedCurriculumVersion: 'bsit_demo_v1' },
    [path + '/title']: 'forged' }));
  assert.equal((await get(ref(client, 'users/alice/profile'))).exists(), false);
  assert.equal((await get(ref(client, path + '/title'))).val(), 'Synthetic fixture');
});
