import assert from 'node:assert/strict';

/** Explicitly synthetic local fixtures. No account is created and no real consent is asserted. */
export function createFamilyFixture({ ownerUid = 'alice', recipientUid = 'bob', now = Date.now(),
  namespace = 'demo-studypath-family-rules' } = {}) {
  assert.match(namespace, /^demo-studypath-[A-Za-z0-9_-]+$/);
  for (const value of [ownerUid, recipientUid]) assert.match(value, /^[A-Za-z0-9_-]{1,128}$/);
  assert.notEqual(ownerUid, recipientUid);
  assert.ok(Number.isSafeInteger(now) && now >= 60000);
  const contextId = 'mock_computing_v1', levelId = 'mock_college';
  const fields = { completedLessonCount: true, asOf: true };
  const policy = { schemaVersion: 1, contextId, curriculumVersion: contextId, levelId,
    approvalStatus: 'APPROVED', approvalReference: 'synthetic-local-policy-not-real-approval',
    reviewer: 'synthetic-fixture-not-a-person', reviewedAt: now - 10000, evidenceMode: 'SYNTHETIC_LOCAL_ONLY',
    revision: 1, validFrom: now - 10000, validUntil: now + 86400000,
    eligibleLearnerRole: 'synthetic_learner', eligibleRecipientRole: 'synthetic_recipient',
    permissionModelReference: 'synthetic-local-permission', consentModelReference: 'synthetic-local-consent',
    allowedFields: fields, maxLifetimeMs: 86400000, maxActiveGrants: 5, maxGeneration: 1000000 };
  const eligibility = { ownerUid, contextId, curriculumVersion: contextId, levelId,
    learnerRole: policy.eligibleLearnerRole, permissionModelReference: policy.permissionModelReference,
    consentModelReference: policy.consentModelReference, consentRecordReference: 'synthetic-local-consent-record-not-real-consent',
    consentValidUntil: policy.validUntil };
  const recipientRole = { recipientUid, contextId, recipientRole: policy.eligibleRecipientRole, validUntil: policy.validUntil };
  const acceptance = { schemaVersion: 1, ownerUid, recipientUid, contextId, policyRevision: 1, acceptanceId: 'a00',
    consentRecordReference: eligibility.consentRecordReference, fields, acceptedAt: now - 1000, expiresAt: now + 3600000 };
  const grant = { schemaVersion: 1, ownerUid, recipientUid, slotId: 'g0', generation: 1, contextId,
    policyRevision: 1, acceptanceId: 'a00', consentRecordReference: eligibility.consentRecordReference,
    fields, createdAt: now - 500, expiresAt: acceptance.expiresAt, status: 'ACTIVE' };
  const projection = { schemaVersion: 1, ownerUid, recipientUid, slotId: 'g0', generation: 1,
    contextId, policyRevision: 1, expiresAt: grant.expiresAt, summary: { completedLessonCount: 0, asOf: now - 250 } };
  const attempt = { schemaVersion: 1, curriculumVersion: contextId, moduleId: 'mock_programming', version: 1,
    packageSha256: 'a'.repeat(64), lessonId: 'lesson_1', runId: 'run_1', startedAt: now - 10000, completedAt: now - 5000,
    totalSteps: 3, resolvedSteps: 3, totalChecks: 1, firstTryCorrect: 1, assistedChecks: 0, incorrectChecks: 0,
    responsesJson: '{"synthetic":"resolved"}' };
  const entry = Object.fromEntries(['runId', 'curriculumVersion', 'moduleId', 'version', 'lessonId'].map(key => [key, attempt[key]]));
  const key = `${entry.moduleId}:v${entry.version}:${entry.lessonId}`;
  const ownerPreferences = { onboardingComplete: true, curriculumVersion: contextId, levelId };
  const trustedUpdates = {
    [`catalog/sharingPolicies/${contextId}`]: policy,
    'catalog/sharingTestControl': { enabled: true, namespace },
    [`sharingEligibility/${ownerUid}/${contextId}`]: eligibility,
    [`sharingRecipientRoles/${recipientUid}/${contextId}`]: recipientRole,
    [`catalog/sharingLessonKeys/${contextId}/${key}`]: attempt.packageSha256
  };
  return { contextId, levelId, policy, eligibility, recipientRole, acceptance, grant, projection,
    slot: { grant, projection }, attempt, entry, key, ownerPreferences, trustedUpdates,
    index: { count: 1, lastKey: key, entries: { [key]: entry } },
    paths: { policy: `catalog/sharingPolicies/${contextId}`, eligibility: `sharingEligibility/${ownerUid}/${contextId}`,
      role: `sharingRecipientRoles/${recipientUid}/${contextId}`, acceptance: `familyAcceptances/${ownerUid}/a00`,
      slots: `sharedStudy/${ownerUid}`, slot: `sharedStudy/${ownerUid}/g0`, projection: `sharedStudy/${ownerUid}/g0/projection`,
      index: `users/${ownerUid}/lessonCompletionIndex/${contextId}`, attempt: `users/${ownerUid}/lessonAttempts/run_1`,
      preferences: `users/${ownerUid}/profile/learnerPreferences` } };
}
