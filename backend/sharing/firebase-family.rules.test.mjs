import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove } from 'firebase/database';
import { createLocalFamilyRules } from './firebase-family-rules.mjs';
import { createFamilyFixture } from './family-fixture.mjs';

// Own namespace only. The coordinator starts services; this suite never resets a shared user namespace.
const namespace = 'demo-studypath-family-rules';
const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
assert.equal(host, '127.0.0.1:19000', 'Use coordinator-owned isolated database emulator on 19000');
let env, prod, f;
const db = (uid, target = env) => uid ? target.authenticatedContext(uid).database() : target.unauthenticatedContext().database();
const at = (uid, path, target = env) => path ? ref(db(uid, target), path) : ref(db(uid, target));
const adminSet = (path, value, target = env) => target.withSecurityRulesDisabled(ctx => set(ref(ctx.database(), path), value));
const seed = async target => {
  await target.clearDatabase();
  await target.withSecurityRulesDisabled(ctx => update(ref(ctx.database()), {
    ...f.trustedUpdates, [f.paths.preferences]: f.ownerPreferences
  }));
};
const accept = () => assertSucceeds(set(at('bob', f.paths.acceptance), f.acceptance));
const grant = async () => { await accept(); await assertSucceeds(set(at('alice', f.paths.slot), f.slot)); };
const append = entry => ({ count: 1, lastKey: `${entry.moduleId}:v${entry.version}:${entry.lessonId}`,
  [`entries/${entry.moduleId}:v${entry.version}:${entry.lessonId}`]: entry });
const revoke = slot => ({ grant: { ...slot.grant, status: 'REVOKED', revokedAt: Date.now() - 10 } });

before(async () => {
  const source = await readFile(new URL('../database.rules.json', import.meta.url), 'utf8');
  env = await initializeTestEnvironment({ projectId: namespace, database: {
    host: '127.0.0.1', port: 19000, rules: createLocalFamilyRules(source, namespace) } });
  prod = await initializeTestEnvironment({ projectId: 'demo-studypath-family-production', database: {
    host: '127.0.0.1', port: 19000, rules: source } });
});
beforeEach(async () => { f = createFamilyFixture(); await seed(env); });
after(async () => { for (const target of [env, prod]) if (target) { await target.clearDatabase(); await target.cleanup(); } });

test('absent or unsupported policy, eligibility, recipient role and wrong local control deny sharing', async () => {
  for (const [path, value] of [[f.paths.policy, null], [f.paths.eligibility, null], [f.paths.role, null],
    ['catalog/sharingTestControl', null], ['catalog/sharingTestControl', { enabled: true, namespace: 'demo-studypath-other' }],
    [f.paths.policy, { ...f.policy, allowedFields: { ...f.policy.allowedFields, dueItemCount: true } }],
    [f.paths.policy, { ...f.policy, approvalStatus: 'DRAFT' }],
    [f.paths.policy, { ...f.policy, validUntil: Date.now() - 1 }],
    [f.paths.eligibility, { ...f.eligibility, consentRecordReference: 'different' }],
    [f.paths.role, { ...f.recipientRole, recipientRole: 'claimed_parent' }],
    [f.paths.preferences, { ...f.ownerPreferences, levelId: 'other' }]]) {
    await seed(env); await adminSet(path, value);
    await assertFails(set(at('bob', f.paths.acceptance), f.acceptance));
  }
});

test('production rules deny local synthetic policy and trusted records are never client writable', async () => {
  await seed(prod);
  await assertFails(set(at('bob', f.paths.acceptance, prod), f.acceptance));
  for (const uid of ['alice', 'bob', 'mallory', null]) {
    for (const [path, value] of Object.entries(f.trustedUpdates)) await assertFails(set(at(uid, path), value));
  }
});

test('production human-policy branch enforces the same authority using explicitly synthetic unit records', async () => {
  await seed(prod);
  // This exercises the evidence-mode branch with mock records; it is not evidence of actual human approval.
  await adminSet(f.paths.policy, { ...f.policy, evidenceMode: 'HUMAN_REVIEW_RECORDED' }, prod);
  await assertSucceeds(set(at('bob', f.paths.acceptance, prod), f.acceptance));
  await assertSucceeds(set(at('alice', f.paths.slot, prod), f.slot));
  await assertSucceeds(get(at('bob', f.paths.projection, prod)));
  await assertFails(get(at('mallory', f.paths.projection, prod)));
  await assertFails(get(at('bob', f.paths.slots, prod)));
});

test('separately authenticated recipient accepts exact immutable scope; owner cannot impersonate', async () => {
  await accept(); await accept();
  assert.deepEqual((await get(at('alice', f.paths.acceptance))).val(), f.acceptance);
  await assertSucceeds(get(at('bob', f.paths.acceptance)));
  for (const uid of ['alice', 'mallory', null]) await assertFails(set(at(uid, f.paths.acceptance), f.acceptance));
  for (const delta of [{ recipientUid: 'alice' }, { consentRecordReference: 'claimed' }, { policyRevision: 2 },
    { fields: { ...f.acceptance.fields, grades: true } }, { acceptedAt: f.acceptance.acceptedAt + 1 },
    { expiresAt: f.policy.validUntil + 1 }, { secret: 'private' }]) {
    await assertFails(set(at('bob', f.paths.acceptance), { ...f.acceptance, ...delta }));
  }
  await assertFails(remove(at('bob', f.paths.acceptance)));
  await assertFails(remove(at('bob', `${f.paths.acceptance}/fields/asOf`)));
  await assertFails(set(at('bob', `familyAcceptances/alice/a50`), { ...f.acceptance, acceptanceId: 'a50' }));
});

test('recipient reads exact authorized projection and never owner ancestors or private records', async () => {
  await grant();
  assert.deepEqual((await get(at('bob', f.paths.projection))).val(), f.projection);
  for (const uid of ['mallory', null]) await assertFails(get(at(uid, f.paths.projection)));
  for (const path of ['sharedStudy', f.paths.slots, f.paths.slot, 'users/alice', f.paths.preferences,
    'familyAcceptances/alice', 'sharingEligibility/alice/mock_computing_v1']) await assertFails(get(at('bob', path)));
  await assertSucceeds(get(at('alice', f.paths.slots)));
  await assertSucceeds(get(at('bob', f.paths.role)));
  await assertFails(get(at('alice', f.paths.role)));
  for (const uid of ['bob', 'mallory', null]) await assertFails(set(at(uid, f.paths.slot), f.slot));
});

test('minimal projection rejects arbitrary counts, private fields, identity, expiry and scope changes', async () => {
  await accept();
  const variants = [
    { ...f.slot, projection: { ...f.projection, summary: { completedLessonCount: 1, asOf: f.projection.summary.asOf } } },
    { ...f.slot, projection: { ...f.projection, summary: { ...f.projection.summary, grades: [99] } } },
    { ...f.slot, projection: { ...f.projection, recipientUid: 'mallory' } },
    { ...f.slot, projection: { ...f.projection, expiresAt: f.projection.expiresAt + 1 } },
    { ...f.slot, grant: { ...f.grant, fields: { ...f.grant.fields, dueItemCount: true } } },
    { grant: f.grant }, { ...f.slot, privateNotes: 'never shared' }
  ];
  for (const value of variants) await assertFails(set(at('alice', f.paths.slot), value));
  await assertSucceeds(set(at('alice', f.paths.slot), f.slot));
  await assertFails(remove(at('alice', `${f.paths.slot}/projection`)));
  await assertFails(update(at('alice', `${f.paths.slot}/grant`), { expiresAt: f.grant.expiresAt + 1 }));
  await assertFails(remove(at('alice', f.paths.slot)));
});

test('acknowledged lesson proof appends one unique immutable completion and publishes its protected count', async () => {
  await adminSet(f.paths.attempt, f.attempt);
  await assertSucceeds(update(at('alice', f.paths.index), append(f.entry)));
  assert.deepEqual((await get(at('alice', f.paths.index))).val(), f.index);
  await assertSucceeds(update(at('alice', f.paths.index), append(f.entry)));
  await accept();
  await assertFails(set(at('alice', f.paths.slot), f.slot));
  const slot = { ...f.slot, projection: { ...f.projection, summary: { ...f.projection.summary, completedLessonCount: 1 } } };
  await assertSucceeds(set(at('alice', f.paths.slot), slot));
  assert.equal((await get(at('bob', f.paths.projection))).val().summary.completedLessonCount, 1);
});

test('index rejects forged missing same-batch foreign and incomplete completion proofs', async () => {
  await assertFails(update(at('alice', f.paths.index), append(f.entry)));
  await adminSet('users/mallory/lessonAttempts/run_1', f.attempt);
  await assertFails(update(at('alice', f.paths.index), append(f.entry)));
  await adminSet(f.paths.attempt, { ...f.attempt, resolvedSteps: 2 });
  await assertFails(update(at('alice', f.paths.index), append(f.entry)));
  await adminSet(f.paths.attempt, f.attempt);
  await adminSet(`catalog/sharingLessonKeys/${f.contextId}/${f.key}`, null);
  await assertFails(update(at('alice', f.paths.index), append(f.entry)));
  await adminSet(`catalog/sharingLessonKeys/${f.contextId}/${f.key}`, f.attempt.packageSha256);
  for (const delta of [{ moduleId: 'forged' }, { version: 2 }, { lessonId: 'other' }, { curriculumVersion: 'other' }, { runId: 'missing' }]) {
    await assertFails(update(at('alice', f.paths.index), append({ ...f.entry, ...delta })));
  }
  await adminSet(f.paths.attempt, null);
  await assertFails(update(at('alice', 'users/alice'), {
    'lessonAttempts/run_1': f.attempt, [`lessonCompletionIndex/${f.contextId}/count`]: 1,
    [`lessonCompletionIndex/${f.contextId}/lastKey`]: f.key,
    [`lessonCompletionIndex/${f.contextId}/entries/${f.key}`]: f.entry
  }));
});

test('counter cannot increment alone, duplicate runs inflate, batch append two, delete or reset through ancestors', async () => {
  await adminSet(f.paths.attempt, f.attempt);
  await assertSucceeds(update(at('alice', f.paths.index), append(f.entry)));
  await assertFails(set(at('alice', `${f.paths.index}/count`), 2));
  await assertFails(update(at('alice', f.paths.index), { ...append(f.entry), count: 2 }));
  await adminSet('users/alice/lessonAttempts/run_2', { ...f.attempt, runId: 'run_2' });
  await assertFails(update(at('alice', f.paths.index), { ...append({ ...f.entry, runId: 'run_2' }), count: 2 }));
  const entry2 = { ...f.entry, lessonId: 'lesson_2', runId: 'run_3' };
  const entry3 = { ...f.entry, lessonId: 'lesson_3', runId: 'run_4' };
  await adminSet('users/alice/lessonAttempts/run_3', { ...f.attempt, ...entry2 });
  await adminSet('users/alice/lessonAttempts/run_4', { ...f.attempt, ...entry3 });
  await adminSet(`catalog/sharingLessonKeys/${f.contextId}/mock_programming:v1:lesson_2`, f.attempt.packageSha256);
  await adminSet(`catalog/sharingLessonKeys/${f.contextId}/mock_programming:v1:lesson_3`, f.attempt.packageSha256);
  await assertFails(update(at('alice', f.paths.index), { count: 2, lastKey: 'mock_programming:v1:lesson_2',
    'entries/mock_programming:v1:lesson_2': entry2, 'entries/mock_programming:v1:lesson_3': entry3 }));
  await assertSucceeds(update(at('alice', f.paths.index), { ...append(entry2), count: 2 }));
  // A stale concurrent append must re-read the now-2 counter.
  await assertFails(update(at('alice', f.paths.index), { ...append(entry3), count: 2 }));
  await assertSucceeds(update(at('alice', f.paths.index), { ...append(entry3), count: 3 }));
  for (const path of [`${f.paths.index}/entries/${f.key}`, `${f.paths.index}/entries`, f.paths.index,
    'users/alice/lessonCompletionIndex', 'users/alice', 'users']) await assertFails(remove(at('alice', path)));
  await assertFails(update(at('alice', 'users/alice'), { lessonCompletionIndex: null }));
  await assertFails(update(at('alice', `${f.paths.index}/entries/${f.key}`), { runId: 'run_2' }));
});

test('revoke deletes projection atomically, stays usable after policy withdrawal, and exact retry retains identity', async () => {
  await grant(); await adminSet(f.paths.policy, null);
  await assertFails(get(at('bob', f.paths.projection)));
  await assertSucceeds(get(at('alice', f.paths.slots)));
  const revoked = revoke(f.slot);
  await assertSucceeds(set(at('alice', f.paths.slot), revoked));
  await assertSucceeds(set(at('alice', f.paths.slot), revoked));
  await assertFails(get(at('bob', f.paths.projection)));
  assert.deepEqual((await get(at('alice', f.paths.slot))).val(), revoked);
  await assertFails(remove(at('alice', f.paths.slot)));
});

test('stale generation cannot revive or revoke new grant, active grants require explicit revoke before change', async () => {
  await grant();
  await assertFails(set(at('alice', f.paths.slot), { ...f.slot, grant: { ...f.grant, generation: 2 },
    projection: { ...f.projection, generation: 2 } }));
  const revoked = revoke(f.slot); await assertSucceeds(set(at('alice', f.paths.slot), revoked));
  await assertFails(set(at('alice', f.paths.slot), f.slot));
  const now = Date.now() - 10;
  const second = { grant: { ...f.grant, generation: 2, createdAt: now },
    projection: { ...f.projection, generation: 2, summary: { ...f.projection.summary, asOf: now } } };
  await assertSucceeds(set(at('alice', f.paths.slot), second));
  await assertFails(set(at('alice', f.paths.slot), revoked));
  assert.equal((await get(at('bob', f.paths.projection))).val().generation, 2);
});

test('policy consent role expiry revision and context withdrawal deny next exact online read', async () => {
  await grant();
  for (const [path, value] of [[f.paths.policy, { ...f.policy, approvalStatus: 'REVOKED' }],
    [f.paths.policy, { ...f.policy, revision: 2 }], [f.paths.policy, { ...f.policy, validUntil: Date.now() - 1 }],
    [f.paths.eligibility, { ...f.eligibility, consentValidUntil: Date.now() - 1 }],
    [f.paths.eligibility, { ...f.eligibility, consentRecordReference: 'new' }],
    [f.paths.role, null], [f.paths.role, { ...f.recipientRole, validUntil: Date.now() - 1 }],
    [f.paths.preferences, { ...f.ownerPreferences, curriculumVersion: 'different' }]]) {
    await adminSet(path, value); await assertFails(get(at('bob', f.paths.projection)));
    await adminSet(f.paths.policy, f.policy); await adminSet(f.paths.eligibility, f.eligibility);
    await adminSet(f.paths.role, f.recipientRole); await adminSet(f.paths.preferences, f.ownerPreferences);
  }
});

test('slot and policy bounds cannot be bypassed by ancestor or mixed owner/recipient writes', async () => {
  await accept();
  await adminSet(f.paths.policy, { ...f.policy, maxActiveGrants: 1, maxGeneration: 1 });
  const second = { grant: { ...f.grant, slotId: 'g1' }, projection: { ...f.projection, slotId: 'g1' } };
  await assertFails(set(at('alice', 'sharedStudy/alice/g1'), second));
  for (const slotId of ['g5', 'g00', 'other']) await assertFails(set(at('alice', `sharedStudy/alice/${slotId}`), {
    grant: { ...f.grant, slotId }, projection: { ...f.projection, slotId }
  }));
  await assertFails(set(at('alice', 'sharedStudy/alice'), { g0: f.slot }));
  await assertSucceeds(set(at('alice', f.paths.slot), f.slot));
  await assertFails(update(at('alice', ''), { [f.paths.slot]: revoke(f.slot), [f.paths.acceptance]: null }));
  await assertSucceeds(get(at('bob', f.paths.projection)));
});
