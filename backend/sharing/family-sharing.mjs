import { isDeepStrictEqual } from 'node:util';

export class SharingError extends Error {
  constructor(code) { super(code); this.name = 'SharingError'; this.code = code; }
}
const check = (condition, code) => { if (!condition) throw new SharingError(code); };
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const exact = (value, keys, code) => check(record(value) && Object.keys(value).length === keys.length &&
  keys.every(key => Object.hasOwn(value, key)), code);
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value) &&
  !['__proto__', 'constructor', 'prototype'].includes(value);
const uid = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value) &&
  !['__proto__', 'constructor', 'prototype'].includes(value);
const text = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 1000;
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const time = value => integer(value, 0, 4102444800000);
const allowed = new Set(['completedLessonCount', 'dueItemCount', 'asOf']);
const fieldsValid = value => record(value) && Object.keys(value).length >= 1 && Object.keys(value).length <= 3 &&
  value.asOf === true && Object.entries(value).every(([key, enabled]) => allowed.has(key) && enabled === true);
const subset = (value, scope) => fieldsValid(value) && Object.keys(value).every(key => scope[key] === true);

/** Default is disabled. Record shape validation cannot establish the authenticity of human approval. */
export function resolveSharingPolicy({ policy, eligibility, now, allowSynthetic = false }) {
  try {
    const code = 'SHARING_DISABLED';
    exact(policy, ['schemaVersion', 'contextId', 'curriculumVersion', 'levelId', 'approvalStatus', 'approvalReference',
      'reviewer', 'reviewedAt', 'evidenceMode', 'revision', 'validFrom', 'validUntil', 'eligibleLearnerRole',
      'eligibleRecipientRole', 'permissionModelReference', 'consentModelReference', 'allowedFields',
      'maxLifetimeMs', 'maxActiveGrants', 'maxGeneration'], code);
    exact(eligibility, ['ownerUid', 'contextId', 'curriculumVersion', 'levelId', 'learnerRole',
      'permissionModelReference', 'consentModelReference', 'consentRecordReference', 'consentValidUntil'], code);
    check(time(now) && policy.schemaVersion === 1 && policy.approvalStatus === 'APPROVED' &&
      ['contextId', 'curriculumVersion', 'levelId', 'eligibleLearnerRole', 'eligibleRecipientRole'].every(key => id(policy[key])) &&
      ['approvalReference', 'reviewer', 'permissionModelReference', 'consentModelReference'].every(key => text(policy[key])) &&
      time(policy.reviewedAt) && policy.reviewedAt <= now && integer(policy.revision, 1, 1_000_000) &&
      time(policy.validFrom) && time(policy.validUntil) && policy.validFrom <= now && policy.validUntil > now &&
      fieldsValid(policy.allowedFields) && integer(policy.maxLifetimeMs, 1, 30 * 86400000) &&
      integer(policy.maxActiveGrants, 1, 5) && integer(policy.maxGeneration, 1, 1_000_000), code);
    check(policy.evidenceMode === 'HUMAN_REVIEW_RECORDED' ||
      (allowSynthetic === true && policy.evidenceMode === 'SYNTHETIC_LOCAL_ONLY'), code);
    check(uid(eligibility.ownerUid) && ['contextId', 'curriculumVersion', 'levelId', 'permissionModelReference',
      'consentModelReference'].every(key => eligibility[key] === policy[key]) &&
      eligibility.learnerRole === policy.eligibleLearnerRole && text(eligibility.consentRecordReference) &&
      time(eligibility.consentValidUntil) && eligibility.consentValidUntil > now, code);
    return { enabled: true, policy: structuredClone(policy) };
  } catch { return { enabled: false, code: 'SHARING_DISABLED' }; }
}

function requestShape(request, kind) {
  const common = ['ownerUid', 'contextId'];
  const extra = kind === 'accept' ? ['policyRevision', 'acceptanceId', 'consentRecordReference', 'fields', 'expiresAt'] :
    kind === 'create' ? ['policyRevision', 'grantId', 'acceptanceId', 'recipientUid', 'expectedGeneration',
      'consentRecordReference', 'fields', 'expiresAt'] : kind === 'read' ? ['grantId', 'generation'] :
      ['grantId', 'expectedGeneration'];
  exact(request, [...common, ...extra], 'INVALID_REQUEST');
  check(uid(request.ownerUid) && id(request.contextId), 'INVALID_REQUEST');
  if ('grantId' in request) check(id(request.grantId), 'INVALID_REQUEST');
  if ('acceptanceId' in request) check(id(request.acceptanceId), 'INVALID_REQUEST');
  if ('recipientUid' in request) check(uid(request.recipientUid), 'INVALID_REQUEST');
  if ('policyRevision' in request) check(integer(request.policyRevision, 1, 1_000_000), 'INVALID_REQUEST');
  if ('expectedGeneration' in request) check(integer(request.expectedGeneration, 0, 1_000_000), 'INVALID_REQUEST');
  if ('generation' in request) check(integer(request.generation, 1, 1_000_000), 'INVALID_REQUEST');
  if ('expiresAt' in request) check(time(request.expiresAt), 'INVALID_REQUEST');
  if ('fields' in request) check(fieldsValid(request.fields), 'INVALID_REQUEST');
  if ('consentRecordReference' in request) check(text(request.consentRecordReference), 'INVALID_REQUEST');
}

function validState(state) {
  exact(state, ['acceptances', 'grants', 'projections'], 'INVALID_STATE');
  for (const kind of ['acceptances', 'grants', 'projections']) {
    check(record(state[kind]) && Object.keys(state[kind]).length <= 50 && Object.keys(state[kind]).every(id), 'INVALID_STATE');
  }
}

function acceptedRecord(acceptance) {
  exact(acceptance, ['schemaVersion', 'ownerUid', 'recipientUid', 'contextId', 'policyRevision', 'acceptanceId',
    'consentRecordReference', 'fields', 'acceptedAt', 'expiresAt'], 'RECIPIENT_ACCEPTANCE_REQUIRED');
  check(acceptance.schemaVersion === 1 && uid(acceptance.ownerUid) && uid(acceptance.recipientUid) &&
    id(acceptance.contextId) && id(acceptance.acceptanceId) && integer(acceptance.policyRevision, 1, 1_000_000) &&
    text(acceptance.consentRecordReference) && fieldsValid(acceptance.fields) && time(acceptance.acceptedAt) &&
    time(acceptance.expiresAt) && acceptance.expiresAt > acceptance.acceptedAt, 'RECIPIENT_ACCEPTANCE_REQUIRED');
}

function grantRecord(grant) {
  exact(grant, ['schemaVersion', 'ownerUid', 'recipientUid', 'contextId', 'policyRevision', 'grantId',
    'acceptanceId', 'generation', 'consentRecordReference', 'fields', 'createdAt', 'expiresAt', 'status',
    ...(grant?.status === 'REVOKED' ? ['revokedAt'] : [])], 'INVALID_STATE');
  check(grant.schemaVersion === 1 && uid(grant.ownerUid) && uid(grant.recipientUid) && id(grant.contextId) &&
    id(grant.grantId) && id(grant.acceptanceId) && integer(grant.generation, 1, 1_000_000) &&
    integer(grant.policyRevision, 1, 1_000_000) && fieldsValid(grant.fields) && text(grant.consentRecordReference) &&
    time(grant.createdAt) && time(grant.expiresAt) && grant.expiresAt > grant.createdAt &&
    ['ACTIVE', 'REVOKED'].includes(grant.status) && (grant.status !== 'REVOKED' || time(grant.revokedAt)), 'INVALID_STATE');
}

function enabled(snapshot, request, allowSynthetic) {
  const resolved = resolveSharingPolicy({ policy: snapshot.policy, eligibility: snapshot.eligibility,
    now: snapshot.now, allowSynthetic });
  check(resolved.enabled && snapshot.eligibility.ownerUid === request.ownerUid &&
    snapshot.policy.contextId === request.contextId, 'SHARING_DISABLED');
  return snapshot.policy;
}
const owner = (snapshot, request) => check(snapshot.principal.uid === request.ownerUid, 'OWNER_REQUIRED');
function scopedAcceptance(snapshot, request, policy, recipientUid) {
  const acceptance = snapshot.state.acceptances[request.acceptanceId]; acceptedRecord(acceptance);
  check(acceptance.acceptanceId === request.acceptanceId && acceptance.ownerUid === request.ownerUid && acceptance.contextId === request.contextId &&
    acceptance.policyRevision === policy.revision && acceptance.recipientUid === recipientUid &&
    acceptance.acceptedAt <= snapshot.now && acceptance.expiresAt > snapshot.now &&
    acceptance.consentRecordReference === snapshot.eligibility.consentRecordReference &&
    subset(request.fields, acceptance.fields), 'RECIPIENT_ACCEPTANCE_REQUIRED');
  return acceptance;
}
function projected(grant, snapshot) {
  const summary = {};
  for (const field of Object.keys(grant.fields)) {
    const value = field === 'asOf' ? snapshot.now : snapshot.sourceTotals?.[field];
    check(field === 'asOf' ? time(value) : integer(value, 0, 1_000_000), 'INVALID_PROJECTION');
    summary[field] = value;
  }
  return { schemaVersion: 1, ownerUid: grant.ownerUid, recipientUid: grant.recipientUid, grantId: grant.grantId,
    generation: grant.generation, contextId: grant.contextId, policyRevision: grant.policyRevision, summary };
}
function activeGrant(snapshot, request, policy) {
  const grant = snapshot.state.grants[request.grantId]; grantRecord(grant);
  check(grant.grantId === request.grantId && grant.ownerUid === request.ownerUid && grant.contextId === request.contextId && grant.status === 'ACTIVE' &&
    grant.createdAt <= snapshot.now && grant.expiresAt > snapshot.now && grant.policyRevision === policy.revision &&
    grant.consentRecordReference === snapshot.eligibility.consentRecordReference && subset(grant.fields, policy.allowedFields), 'GRANT_INACTIVE');
  scopedAcceptance(snapshot, { ...request, acceptanceId: grant.acceptanceId, fields: grant.fields }, policy, grant.recipientUid);
  return grant;
}

/**
 * Backend foundation only: adapter MUST verify a JWT and read current authorization from the server on EVERY call.
 * No client-supplied UID, role, connected flag or cached record is accepted as authentication. The test adapter is synthetic.
 * No production adapter/endpoint is exported by this module, so shipping the foundation cannot enable sharing by itself.
 */
export function createFamilySharingEngine({ adapter, allowSynthetic = false }) {
  check(typeof adapter?.authorizeFresh === 'function' && typeof adapter?.compareAndSet === 'function' &&
    typeof allowSynthetic === 'boolean', 'TRUSTED_ADAPTER_REQUIRED');
  async function fresh(idToken, request, operation) {
    check(typeof idToken === 'string' && idToken.length > 0 && idToken.length <= 8192, 'AUTH_REQUIRED');
    let snapshot;
    try { snapshot = await adapter.authorizeFresh({ idToken, ownerUid: request.ownerUid,
      contextId: request.contextId, operation, requireFreshNetwork: true, checkRevoked: true }); }
    catch { throw new SharingError('AUTH_REQUIRED'); }
    check(record(snapshot) && record(snapshot.principal) && uid(snapshot.principal.uid) &&
      Array.isArray(snapshot.principal.roles) && snapshot.principal.roles.length <= 10 &&
      snapshot.principal.roles.every(id) && snapshot.ownerUid === request.ownerUid && time(snapshot.now) &&
      integer(snapshot.revision, 0, Number.MAX_SAFE_INTEGER), 'AUTH_REQUIRED');
    validState(snapshot.state);
    return snapshot;
  }
  async function mutate(idToken, request, kind, transition) {
    requestShape(request, kind);
    for (let attempt = 0; attempt < 3; attempt++) {
      const snapshot = await fresh(idToken, request, kind);
      const next = transition(snapshot);
      if (next.unchanged) return { status: 'ACKNOWLEDGED', outcome: 'UNCHANGED', generation: next.generation };
      let committed;
      try { committed = await adapter.compareAndSet({ expectedRevision: snapshot.revision, ownerUid: request.ownerUid,
        nextState: next.state, requireServerAcknowledgment: true }); }
      catch { throw new SharingError('ACKNOWLEDGMENT_UNCONFIRMED'); }
      if (committed === true) return { status: 'ACKNOWLEDGED', outcome: next.outcome, generation: next.generation };
    }
    throw new SharingError('RETRYABLE_FAILURE');
  }
  return {
    async acceptRecipient(idToken, request) {
      return mutate(idToken, request, 'accept', snapshot => {
        const policy = enabled(snapshot, request, allowSynthetic);
        check(snapshot.principal.roles.includes(policy.eligibleRecipientRole), 'RECIPIENT_ROLE_REQUIRED');
        check(snapshot.principal.uid !== request.ownerUid, 'RECIPIENT_ACCEPTANCE_REQUIRED');
        check(request.policyRevision === policy.revision && subset(request.fields, policy.allowedFields) &&
          request.consentRecordReference === snapshot.eligibility.consentRecordReference && request.expiresAt > snapshot.now &&
          request.expiresAt <= Math.min(snapshot.now + policy.maxLifetimeMs, policy.validUntil,
            snapshot.eligibility.consentValidUntil), 'RECIPIENT_ACCEPTANCE_REQUIRED');
        const acceptance = { schemaVersion: 1, ownerUid: request.ownerUid, recipientUid: snapshot.principal.uid,
          contextId: request.contextId, policyRevision: policy.revision, acceptanceId: request.acceptanceId,
          consentRecordReference: request.consentRecordReference, fields: structuredClone(request.fields),
          acceptedAt: snapshot.now, expiresAt: request.expiresAt };
        const existing = snapshot.state.acceptances[request.acceptanceId];
        if (existing) {
          acceptedRecord(existing);
          check(isDeepStrictEqual({ ...acceptance, acceptedAt: existing.acceptedAt }, existing), 'ACCEPTANCE_CONFLICT');
          return { unchanged: true };
        }
        check(Object.keys(snapshot.state.acceptances).length < 50, 'ACCEPTANCE_LIMIT');
        const state = structuredClone(snapshot.state); state.acceptances[request.acceptanceId] = acceptance;
        return { state, outcome: 'RECIPIENT_ACCEPTED' };
      });
    },
    async createFamilyGrant(idToken, request) {
      return mutate(idToken, request, 'create', snapshot => {
        owner(snapshot, request); const policy = enabled(snapshot, request, allowSynthetic);
        check(request.policyRevision === policy.revision && request.recipientUid !== request.ownerUid &&
          subset(request.fields, policy.allowedFields) && request.consentRecordReference === snapshot.eligibility.consentRecordReference,
        'RECIPIENT_ACCEPTANCE_REQUIRED');
        const acceptance = scopedAcceptance(snapshot, request, policy, request.recipientUid);
        check(request.expiresAt > snapshot.now && request.expiresAt <= Math.min(snapshot.now + policy.maxLifetimeMs,
          policy.validUntil, snapshot.eligibility.consentValidUntil, acceptance.expiresAt), 'EXPIRY_INVALID');
        const current = snapshot.state.grants[request.grantId];
        if (current) {
          grantRecord(current);
          check(current.grantId === request.grantId && current.ownerUid === request.ownerUid &&
            current.contextId === request.contextId, 'INVALID_STATE');
        }
        const generation = (current?.generation ?? 0) + 1;
        const grant = { schemaVersion: 1, ownerUid: request.ownerUid, recipientUid: request.recipientUid,
          contextId: request.contextId, policyRevision: policy.revision, grantId: request.grantId, acceptanceId: request.acceptanceId,
          generation, consentRecordReference: request.consentRecordReference, fields: structuredClone(request.fields),
          createdAt: snapshot.now, expiresAt: request.expiresAt, status: 'ACTIVE' };
        if (current?.status === 'ACTIVE' && current.generation === request.expectedGeneration + 1 &&
          isDeepStrictEqual({ ...grant, generation: current.generation, createdAt: current.createdAt }, current)) {
          return { unchanged: true, generation: current.generation };
        }
        check((current?.generation ?? 0) === request.expectedGeneration && current?.status !== 'ACTIVE', 'GENERATION_CONFLICT');
        check(generation <= policy.maxGeneration, 'GENERATION_LIMIT');
        let active = 0;
        for (const row of Object.values(snapshot.state.grants)) {
          grantRecord(row); if (row.status === 'ACTIVE' && row.expiresAt > snapshot.now) active++;
        }
        check(active < policy.maxActiveGrants && (current || Object.keys(snapshot.state.grants).length < 50), 'GRANT_LIMIT');
        const state = structuredClone(snapshot.state); state.grants[request.grantId] = grant;
        state.projections[request.grantId] = projected(grant, snapshot);
        return { state, generation, outcome: 'GRANTED' };
      });
    },
    async revokeFamilyGrant(idToken, request) {
      return mutate(idToken, request, 'revoke', snapshot => {
        owner(snapshot, request);
        const current = snapshot.state.grants[request.grantId]; grantRecord(current);
        check(current.grantId === request.grantId && current.ownerUid === request.ownerUid && current.contextId === request.contextId, 'OWNER_REQUIRED');
        check(current.generation === request.expectedGeneration, 'GENERATION_CONFLICT');
        if (current.status === 'REVOKED') {
          check(!snapshot.state.projections[request.grantId], 'INVALID_STATE');
          return { unchanged: true, generation: current.generation };
        }
        const state = structuredClone(snapshot.state);
        state.grants[request.grantId] = { ...current, status: 'REVOKED', revokedAt: snapshot.now };
        delete state.projections[request.grantId];
        return { state, generation: current.generation, outcome: 'REVOKED' };
      });
    },
    async refreshFamilyProjection(idToken, request) {
      return mutate(idToken, request, 'project', snapshot => {
        owner(snapshot, request);
        const policy = enabled(snapshot, request, allowSynthetic);
        const grant = activeGrant(snapshot, request, policy);
        check(grant.generation === request.expectedGeneration, 'GENERATION_CONFLICT');
        const projection = projected(grant, snapshot);
        if (isDeepStrictEqual(projection, snapshot.state.projections[request.grantId])) {
          return { unchanged: true, generation: grant.generation };
        }
        const state = structuredClone(snapshot.state); state.projections[request.grantId] = projection;
        return { state, generation: grant.generation, outcome: 'PROJECTED' };
      });
    },
    async readFamilySummaryOnline(idToken, request) {
      requestShape(request, 'read');
      const snapshot = await fresh(idToken, request, 'read');
      const policy = enabled(snapshot, request, allowSynthetic);
      const grant = activeGrant(snapshot, request, policy);
      check(snapshot.principal.uid === grant.recipientUid && snapshot.principal.roles.includes(policy.eligibleRecipientRole), 'RECIPIENT_REQUIRED');
      check(request.generation === grant.generation, 'GENERATION_CONFLICT');
      const projection = snapshot.state.projections[request.grantId];
      exact(projection, ['schemaVersion', 'ownerUid', 'recipientUid', 'grantId', 'generation', 'contextId', 'policyRevision', 'summary'], 'INVALID_PROJECTION');
      check(projection.schemaVersion === 1 && ['ownerUid', 'recipientUid', 'grantId', 'generation', 'contextId', 'policyRevision']
        .every(key => projection[key] === grant[key]) && record(projection.summary) &&
        isDeepStrictEqual(Object.keys(projection.summary).sort(), Object.keys(grant.fields).sort()), 'INVALID_PROJECTION');
      for (const [key, value] of Object.entries(projection.summary)) check(key === 'asOf' ?
        time(value) && value >= grant.createdAt && value <= snapshot.now : integer(value, 0, 1_000_000), 'INVALID_PROJECTION');
      return { status: 'FRESH_NETWORK_AUTHORIZED', ...structuredClone(projection), selfReported: true };
    },
  };
}
