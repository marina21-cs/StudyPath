import { test } from 'node:test';
import assert from 'node:assert/strict';
const api = await import('../family-sharing.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});
const NOW = 1793318400000;
const clone = value => structuredClone(value);
function fixture() {
  const context = { ownerUid: 'learner_uid', contextId: 'synthetic_context', curriculumVersion: 'fixture_v1',
    levelId: 'fixture_level', learnerRole: 'adult_student', permissionModelReference: 'TEST ONLY PERMISSION MODEL',
    consentModelReference: 'TEST ONLY CONSENT MODEL', consentRecordReference: 'TEST ONLY LEARNER CONSENT', consentValidUntil: NOW + 1_000_000 };
  const policy = { schemaVersion: 1, contextId: 'synthetic_context', curriculumVersion: 'fixture_v1', levelId: 'fixture_level',
    approvalStatus: 'APPROVED', approvalReference: 'TEST ONLY APPROVAL', reviewer: 'TEST ONLY', reviewedAt: NOW - 100,
    evidenceMode: 'SYNTHETIC_LOCAL_ONLY', revision: 1, validFrom: NOW - 100, validUntil: NOW + 1_000_000,
    eligibleLearnerRole: 'adult_student', eligibleRecipientRole: 'verified_family',
    permissionModelReference: context.permissionModelReference, consentModelReference: context.consentModelReference,
    allowedFields: { completedLessonCount: true, dueItemCount: true, asOf: true }, maxLifetimeMs: 100_000,
    maxActiveGrants: 2, maxGeneration: 5 };
  let state = { acceptances: {}, grants: {}, projections: {} }, revision = 0, calls = 0, commits = 0;
  let principal = { uid: 'learner_uid', roles: ['adult_student'] };
  let currentPolicy = policy, currentContext = context, now = NOW;
  let totals = { completedLessonCount: 7, dueItemCount: 2, privateNotes: 'NEVER LEAK' };
  const adapter = {
    async authorizeFresh({ idToken, ownerUid, requireFreshNetwork, checkRevoked }) {
      calls++;
      assert.equal(requireFreshNetwork, true); assert.equal(checkRevoked, true);
      if (idToken !== 'synthetic_authenticated_token') throw new Error('AUTH_REQUIRED');
      return { principal: clone(principal), policy: clone(currentPolicy), eligibility: clone(currentContext),
        state: clone(state), sourceTotals: clone(totals),
        revision, now, ownerUid };
    },
    async compareAndSet({ expectedRevision, ownerUid, nextState }) {
      commits++; assert.equal(ownerUid, 'learner_uid');
      if (expectedRevision !== revision) return false;
      state = clone(nextState); revision++; return true;
    },
  };
  return { policy, context, adapter, token: 'synthetic_authenticated_token',
    usePrincipal(value) { principal = value; }, setPolicy(value) { currentPolicy = value; },
    setContext(value) { currentContext = value; }, advance(value) { now = value; },
    state: () => clone(state), calls: () => calls, commits: () => commits,
    replaceState(value) { state = value; revision++; } };
}
function engine(value) {
  assert.equal(typeof api.createFamilySharingEngine, 'function', 'disabled-by-default family engine must exist');
  return api.createFamilySharingEngine({ adapter: value.adapter, allowSynthetic: true });
}
const fields = { completedLessonCount: true, dueItemCount: true, asOf: true };
const accept = () => ({ ownerUid: 'learner_uid', contextId: 'synthetic_context', policyRevision: 1,
  acceptanceId: 'acceptance_1', consentRecordReference: 'TEST ONLY LEARNER CONSENT', fields,
  expiresAt: NOW + 90_000 });
const grant = () => ({ ownerUid: 'learner_uid', contextId: 'synthetic_context', policyRevision: 1,
  grantId: 'grant_1', acceptanceId: 'acceptance_1', recipientUid: 'family_uid', expectedGeneration: 0,
  consentRecordReference: 'TEST ONLY LEARNER CONSENT', fields, expiresAt: NOW + 80_000 });
const read = () => ({ ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', generation: 1 });
async function active(value, service = engine(value)) {
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await service.acceptRecipient(value.token, accept());
  value.usePrincipal({ uid: 'learner_uid', roles: ['adult_student'] });
  await service.createFamilyGrant(value.token, grant());
  return service;
}

test('absent, review-pending, mismatched, expired and unapproved synthetic policies remain disabled', () => {
  engine(fixture());
  const value = fixture();
  for (const policy of [null, { ...value.policy, approvalStatus: 'REVIEW_PENDING' },
    { ...value.policy, approvalReference: '' }, { ...value.policy, curriculumVersion: 'other' },
    { ...value.policy, validUntil: NOW }, { ...value.policy, allowedFields: { ...fields, rawScores: true } },
    { ...value.policy, maxLifetimeMs: 30 * 86400000 + 1 }, { ...value.policy, maxActiveGrants: 6 }]) {
    assert.equal(api.resolveSharingPolicy({ policy, eligibility: value.context, now: NOW, allowSynthetic: true }).enabled, false);
  }
  assert.equal(api.resolveSharingPolicy({ policy: value.policy, eligibility: value.context, now: NOW }).enabled, false);
});

test('recipient independently accepts using a fresh verified auth principal; owner cannot claim recipient identity', async () => {
  const value = fixture(), service = engine(value);
  await assert.rejects(() => service.acceptRecipient(value.token, accept()), error => error.code === 'RECIPIENT_ROLE_REQUIRED');
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  const result = await service.acceptRecipient(value.token, accept());
  assert.equal(result.status, 'ACKNOWLEDGED');
  assert.equal(value.state().acceptances.acceptance_1.recipientUid, 'family_uid');
  await assert.rejects(() => service.acceptRecipient(value.token, { ...accept(), recipientUid: 'forged_uid' }),
    error => error.code === 'INVALID_REQUEST');
  assert.equal(value.calls() >= 2, true);
});

test('owner opt-in binds accepted UID, reviewed fields and consent; private data never appears in projection', async () => {
  const value = fixture(), service = await active(value);
  const state = value.state();
  assert.equal(state.grants.grant_1.generation, 1);
  assert.equal(state.grants.grant_1.status, 'ACTIVE');
  assert.equal(state.grants.grant_1.recipientUid, 'family_uid');
  assert.deepEqual(state.projections.grant_1.summary, { completedLessonCount: 7, dueItemCount: 2, asOf: NOW });
  assert.equal(JSON.stringify(state).includes('NEVER LEAK'), false);
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  const result = await service.readFamilySummaryOnline(value.token, read());
  assert.deepEqual(result.summary, state.projections.grant_1.summary);
});

test('every recipient read calls fresh authorization and expired/revoked/logout state never falls back to cache', async () => {
  const value = fixture(), service = await active(value);
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  const before = value.calls();
  await service.readFamilySummaryOnline(value.token, read());
  await service.readFamilySummaryOnline(value.token, read());
  assert.equal(value.calls(), before + 2);
  await assert.rejects(() => service.readFamilySummaryOnline('expired_token', read()), error => error.code === 'AUTH_REQUIRED');
  value.advance(NOW + 80_001);
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()), error => error.code === 'GRANT_INACTIVE');
});

test('no acceptance, foreign UID, self-grant, forged roles/fields and untrusted consent cannot create access', async () => {
  const value = fixture(), service = engine(value);
  await assert.rejects(() => service.createFamilyGrant(value.token, grant()), error => error.code === 'RECIPIENT_ACCEPTANCE_REQUIRED');
  await active(value, service);
  for (const request of [{ ...grant(), grantId: 'new', recipientUid: 'other_uid' },
    { ...grant(), grantId: 'new', recipientUid: 'learner_uid' },
    { ...grant(), grantId: 'new', fields: { asOf: true, rawScores: true } },
    { ...grant(), grantId: 'new', consentRecordReference: 'forged consent' }]) {
    await assert.rejects(() => service.createFamilyGrant(value.token, request));
  }
  value.usePrincipal({ uid: 'unrelated_uid', roles: ['adult_student'] });
  await assert.rejects(() => service.createFamilyGrant(value.token, { ...grant(), grantId: 'new' }), error => error.code === 'OWNER_REQUIRED');
});

test('revocation removes projection atomically and fresh reads deny the old generation', async () => {
  const value = fixture(), service = await active(value);
  const request = { ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', expectedGeneration: 1 };
  assert.equal((await service.revokeFamilyGrant(value.token, request)).status, 'ACKNOWLEDGED');
  assert.equal(value.state().grants.grant_1.status, 'REVOKED');
  assert.equal(value.state().projections.grant_1, undefined);
  const commits = value.commits();
  assert.equal((await service.revokeFamilyGrant(value.token, request)).outcome, 'UNCHANGED');
  assert.equal(value.commits(), commits);
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()), error => error.code === 'GRANT_INACTIVE');
});

test('exact create retry preserves timestamps and replay cannot replace a newer generation', async () => {
  const value = fixture(), service = await active(value);
  const first = value.state(); value.advance(NOW + 1);
  assert.equal((await service.createFamilyGrant(value.token, grant())).outcome, 'UNCHANGED');
  assert.deepEqual(value.state(), first);
  await service.revokeFamilyGrant(value.token, { ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', expectedGeneration: 1 });
  await service.createFamilyGrant(value.token, { ...grant(), expectedGeneration: 1 });
  assert.equal(value.state().grants.grant_1.generation, 2);
  await assert.rejects(() => service.createFamilyGrant(value.token, grant()), error => error.code === 'GENERATION_CONFLICT');
  await assert.rejects(() => service.revokeFamilyGrant(value.token,
    { ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', expectedGeneration: 1 }),
  error => error.code === 'GENERATION_CONFLICT');
});

test('bounds apply to expiry, active grant count and generation', async () => {
  const value = fixture(), service = await active(value);
  await assert.rejects(() => service.createFamilyGrant(value.token, { ...grant(), grantId: 'new', expiresAt: NOW + 200_000 }));
  await service.createFamilyGrant(value.token, { ...grant(), grantId: 'grant_2' });
  await assert.rejects(() => service.createFamilyGrant(value.token, { ...grant(), grantId: 'grant_3' }), error => error.code === 'GRANT_LIMIT');
  const state = value.state(); state.grants.grant_1.generation = 5; state.grants.grant_1.status = 'REVOKED';
  state.grants.grant_1.revokedAt = NOW;
  delete state.projections.grant_1; value.replaceState(state);
  await assert.rejects(() => service.createFamilyGrant(value.token, { ...grant(), expectedGeneration: 5 }), error => error.code === 'GENERATION_LIMIT');
});

test('policy revision, consent eligibility and projection tampering invalidate recipient reads', async () => {
  for (const mutate of [v => v.setPolicy({ ...v.policy, revision: 2 }),
    v => v.setContext({ ...v.context, consentValidUntil: NOW }),
    v => { const s = v.state(); s.projections.grant_1.summary.rawScores = [99]; v.replaceState(s); },
    v => { const s = v.state(); s.projections.grant_1.recipientUid = 'other'; v.replaceState(s); },
    v => { const s = v.state(); s.grants.grant_1.grantId = 'other_grant';
      s.projections.grant_1.grantId = 'other_grant'; v.replaceState(s); },
    v => { const s = v.state(); s.acceptances.acceptance_1.acceptanceId = 'other_acceptance'; v.replaceState(s); }]) {
    const value = fixture(), service = await active(value); mutate(value);
    value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
    await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()));
  }
});

test('an offline or failed trusted adapter returns no cached summary and no acknowledged mutation', async () => {
  const value = fixture(), service = await active(value);
  value.adapter.authorizeFresh = async () => { throw new Error('offline'); };
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()), error => error.code === 'AUTH_REQUIRED');
  await assert.rejects(() => service.revokeFamilyGrant(value.token,
    { ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', expectedGeneration: 1 }));
  assert.equal(value.state().grants.grant_1.status, 'ACTIVE');
});

test('bounded compare-and-set conflicts do not acknowledge a grant or discard concurrent data', async () => {
  const value = fixture(), service = engine(value);
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await service.acceptRecipient(value.token, accept());
  value.usePrincipal({ uid: 'learner_uid', roles: ['adult_student'] });
  value.adapter.compareAndSet = async () => false;
  await assert.rejects(() => service.createFamilyGrant(value.token, grant()), error => error.code === 'RETRYABLE_FAILURE');
  assert.equal(value.state().grants.grant_1, undefined);
});

test('owner refresh uses trusted aggregate source and recipient cannot supply summary values', async () => {
  const value = fixture(), service = await active(value);
  value.advance(NOW + 100);
  const request = { ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', expectedGeneration: 1 };
  assert.equal(typeof service.refreshFamilyProjection, 'function', 'version-bound projection refresh must exist');
  assert.equal((await service.refreshFamilyProjection(value.token, request)).outcome, 'PROJECTED');
  assert.equal(value.state().projections.grant_1.summary.asOf, NOW + 100);
  await assert.rejects(() => service.refreshFamilyProjection(value.token, { ...request, summary: { rawScores: [100] } }),
    error => error.code === 'INVALID_REQUEST');
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await assert.rejects(() => service.refreshFamilyProjection(value.token, request), error => error.code === 'OWNER_REQUIRED');
});

test('owner can revoke when policy is withdrawn and all other access remains disabled', async () => {
  const value = fixture(), service = await active(value);
  value.setPolicy({ ...value.policy, approvalStatus: 'WITHDRAWN' });
  await service.revokeFamilyGrant(value.token,
    { ownerUid: 'learner_uid', contextId: 'synthetic_context', grantId: 'grant_1', expectedGeneration: 1 });
  assert.equal(value.state().grants.grant_1.status, 'REVOKED');
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()), error => error.code === 'SHARING_DISABLED');
});

test('owner/unrelated recipients, stale generation and ancestor-shaped requests cannot read projections', async () => {
  const value = fixture(), service = await active(value);
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()), error => error.code === 'RECIPIENT_REQUIRED');
  value.usePrincipal({ uid: 'other_uid', roles: ['verified_family'] });
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, read()), error => error.code === 'RECIPIENT_REQUIRED');
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, { ...read(), generation: 2 }), error => error.code === 'GENERATION_CONFLICT');
  await assert.rejects(() => service.readFamilySummaryOnline(value.token, { ownerUid: 'learner_uid', contextId: 'synthetic_context' }),
    error => error.code === 'INVALID_REQUEST');
});

test('commit network uncertainty remains unconfirmed and default engine cannot enable synthetic policies', async () => {
  const value = fixture();
  const defaultEngine = api.createFamilySharingEngine({ adapter: value.adapter });
  value.usePrincipal({ uid: 'family_uid', roles: ['verified_family'] });
  await assert.rejects(() => defaultEngine.acceptRecipient(value.token, accept()), error => error.code === 'SHARING_DISABLED');
  const service = engine(value);
  value.adapter.compareAndSet = async () => { throw new Error('network timeout'); };
  await assert.rejects(() => service.acceptRecipient(value.token, accept()), error => error.code === 'ACKNOWLEDGMENT_UNCONFIRMED');
  assert.deepEqual(value.state().acceptances, {});
});
