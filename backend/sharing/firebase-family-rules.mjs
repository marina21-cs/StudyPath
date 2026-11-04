import assert from 'node:assert/strict';

// Rule generation keeps long cross-record predicates consistent. Production is always human-policy only.
const and = (...parts) => parts.filter(Boolean).map(part => `(${part})`).join(' && ');
const or = (...parts) => parts.map(part => `(${part})`).join(' || ');
const at = (snapshot, key) => `${snapshot}.child('${key}')`;
const val = (snapshot, key) => `${at(snapshot, key)}.val()`;
const number = (snapshot, min, max) => `${snapshot}.isNumber() && ${snapshot}.val() >= ${min} && ${snapshot}.val() <= ${max} && ${snapshot}.val() % 1 == 0`;
const id = snapshot => `${snapshot}.isString() && ${snapshot}.val().matches(/^[A-Za-z0-9_-]{1,64}$/)`;
const uid = snapshot => `${snapshot}.isString() && ${snapshot}.val().matches(/^[A-Za-z0-9_-]{1,128}$/)`;
const text = snapshot => `${snapshot}.isString() && ${snapshot}.val().length > 0 && ${snapshot}.val().length <= 1000`;
const timestamp = snapshot => number(snapshot, 0, 4102444800000);
const freshTime = snapshot => and(timestamp(snapshot), `${snapshot}.val() <= now`, `${snapshot}.val() >= now - 60000`);
const same = (next, old, keys) => keys.map(key => `${val(next, key)} == ${val(old, key)}`).join(' && ');
const required = (snapshot, keys) => `${snapshot}.hasChildren(${JSON.stringify(keys)})`;
const shape = (fields, validate) => ({ '.validate': validate, ...Object.fromEntries(Object.entries(fields).map(([key, value]) =>
  [key, typeof value === 'string' ? { '.validate': value } : value])), '$other': { '.validate': false } });
const scope = snapshot => and(`${val(snapshot, 'completedLessonCount')} == true`, `${val(snapshot, 'asOf')} == true`,
  `!${at(snapshot, 'dueItemCount')}.exists()`);
const fieldRules = () => shape({ completedLessonCount: 'newData.val() == true', asOf: 'newData.val() == true' },
  required('newData', ['completedLessonCount', 'asOf']));
const P = context => `root.child('catalog/sharingPolicies').child(${context})`;
const E = context => `root.child('sharingEligibility').child($ownerUid).child(${context})`;
const R = (recipient, context) => `root.child('sharingRecipientRoles').child(${recipient}).child(${context})`;
const A = acceptance => `root.child('familyAcceptances').child($ownerUid).child(${acceptance})`;
const policyKeys = ['schemaVersion', 'contextId', 'curriculumVersion', 'levelId', 'approvalStatus', 'approvalReference',
  'reviewer', 'reviewedAt', 'evidenceMode', 'revision', 'validFrom', 'validUntil', 'eligibleLearnerRole',
  'eligibleRecipientRole', 'permissionModelReference', 'consentModelReference', 'allowedFields',
  'maxLifetimeMs', 'maxActiveGrants', 'maxGeneration'];
const eligibilityKeys = ['ownerUid', 'contextId', 'curriculumVersion', 'levelId', 'learnerRole',
  'permissionModelReference', 'consentModelReference', 'consentRecordReference', 'consentValidUntil'];

function policyValid(context, namespace) {
  const p = P(context), e = E(context);
  const evidence = or(`${val(p, 'evidenceMode')} == 'HUMAN_REVIEW_RECORDED'`, namespace && and(
    `${val(p, 'evidenceMode')} == 'SYNTHETIC_LOCAL_ONLY'`, `${context} == 'mock_computing_v1'`,
    `root.child('catalog/sharingTestControl/enabled').val() == true`,
    `root.child('catalog/sharingTestControl/namespace').val() == '${namespace}'`) || 'false');
  return and(required(p, policyKeys), `${val(p, 'schemaVersion')} == 1`, `${val(p, 'approvalStatus')} == 'APPROVED'`,
    `${val(p, 'contextId')} == ${context}`, ...['contextId', 'curriculumVersion', 'levelId', 'eligibleLearnerRole', 'eligibleRecipientRole'].map(key => id(at(p, key))),
    ...['approvalReference', 'reviewer', 'permissionModelReference', 'consentModelReference'].map(key => text(at(p, key))),
    timestamp(at(p, 'reviewedAt')), `${val(p, 'reviewedAt')} <= now`, evidence,
    number(at(p, 'revision'), 1, 1000000), timestamp(at(p, 'validFrom')), `${val(p, 'validFrom')} <= now`,
    timestamp(at(p, 'validUntil')), `${val(p, 'validUntil')} > now`, scope(at(p, 'allowedFields')),
    number(at(p, 'maxLifetimeMs'), 1, 2592000000), number(at(p, 'maxActiveGrants'), 1, 5), number(at(p, 'maxGeneration'), 1, 1000000),
    required(e, eligibilityKeys), `${val(e, 'ownerUid')} == $ownerUid`,
    ...['contextId', 'curriculumVersion', 'levelId', 'permissionModelReference', 'consentModelReference'].map(key => `${val(e, key)} == ${val(p, key)}`),
    `${val(e, 'learnerRole')} == ${val(p, 'eligibleLearnerRole')}`, text(at(e, 'consentRecordReference')),
    timestamp(at(e, 'consentValidUntil')), `${val(e, 'consentValidUntil')} > now`,
    `root.child('users').child($ownerUid).child('profile/learnerPreferences/onboardingComplete').val() == true`,
    `root.child('users').child($ownerUid).child('profile/learnerPreferences/curriculumVersion').val() == ${val(p, 'curriculumVersion')}`,
    `root.child('users').child($ownerUid).child('profile/learnerPreferences/levelId').val() == ${val(p, 'levelId')}`);
}
function recipientValid(recipient, context) {
  const r = R(recipient, context), p = P(context);
  return and(uid(at(r, 'recipientUid')), `${val(r, 'recipientUid')} == ${recipient}`, `${recipient} != $ownerUid`,
    `${val(r, 'contextId')} == ${context}`, `${val(r, 'recipientRole')} == ${val(p, 'eligibleRecipientRole')}`,
    timestamp(at(r, 'validUntil')), `${val(r, 'validUntil')} > now`);
}
function acceptanceValid(a, recipient, context) {
  return and(`${val(a, 'schemaVersion')} == 1`, `${val(a, 'ownerUid')} == $ownerUid`, `${val(a, 'recipientUid')} == ${recipient}`,
    `${val(a, 'contextId')} == ${context}`, `${val(a, 'policyRevision')} == ${val(P(context), 'revision')}`,
    `${val(a, 'consentRecordReference')} == ${val(E(context), 'consentRecordReference')}`, scope(at(a, 'fields')),
    timestamp(at(a, 'acceptedAt')), `${val(a, 'acceptedAt')} <= now`, timestamp(at(a, 'expiresAt')), `${val(a, 'expiresAt')} > now`);
}
const acceptanceKeys = ['schemaVersion', 'ownerUid', 'recipientUid', 'contextId', 'policyRevision', 'acceptanceId',
  'consentRecordReference', 'acceptedAt', 'expiresAt'];
function acceptanceRules(namespace) {
  const n = 'newData', c = val(n, 'contextId'), recipient = val(n, 'recipientUid'), p = P(c), e = E(c), r = R(recipient, c);
  return {
    '$ownerUid': { '.read': 'auth != null && auth.uid == $ownerUid', '$acceptanceId': {
      '.read': `auth != null && data.child('recipientUid').val() == auth.uid`,
      '.write': and('auth != null', `auth.uid == ${recipient}`, `auth.uid != $ownerUid`, 'newData.exists()'),
      ...shape({ schemaVersion: 'newData.val() == 1', ownerUid: 'newData.val() == $ownerUid', recipientUid: uid('newData'),
        contextId: id('newData'), policyRevision: number('newData', 1, 1000000), acceptanceId: 'newData.val() == $acceptanceId',
        consentRecordReference: text('newData'), fields: fieldRules(), acceptedAt: timestamp('newData'), expiresAt: timestamp('newData') },
      and(`$ownerUid.matches(/^[A-Za-z0-9_-]{1,128}$/)`, `$acceptanceId.matches(/^a[0-4][0-9]$/)`,
        required(n, [...acceptanceKeys, 'fields']), policyValid(c, namespace), recipientValid(recipient, c), acceptanceValid(n, recipient, c),
        `${val(n, 'expiresAt')} <= ${val(p, 'validUntil')}`, `${val(n, 'expiresAt')} <= ${val(e, 'consentValidUntil')}`,
        `${val(n, 'expiresAt')} <= ${val(r, 'validUntil')}`, `${val(n, 'expiresAt')} <= ${val(n, 'acceptedAt')} + ${val(p, 'maxLifetimeMs')}`,
        or(and('!data.exists()', freshTime(at(n, 'acceptedAt'))), same(n, 'data', acceptanceKeys))))
    } }
  };
}
const grantKeys = ['schemaVersion', 'ownerUid', 'recipientUid', 'slotId', 'generation', 'contextId', 'policyRevision',
  'acceptanceId', 'consentRecordReference', 'createdAt', 'expiresAt'];
function activeValid(g, namespace) {
  const c = val(g, 'contextId'), recipient = val(g, 'recipientUid'), p = P(c), e = E(c), a = A(val(g, 'acceptanceId')), r = R(recipient, c);
  const slotBound = or(...[0, 1, 2, 3, 4].map(slot => and(`$slotId == 'g${slot}'`, `${val(p, 'maxActiveGrants')} > ${slot}`)));
  return and(policyValid(c, namespace), recipientValid(recipient, c), slotBound, acceptanceValid(a, recipient, c),
    `${val(a, 'acceptanceId')} == ${val(g, 'acceptanceId')}`, `${val(g, 'status')} == 'ACTIVE'`,
    `${val(g, 'policyRevision')} == ${val(p, 'revision')}`, `${val(g, 'consentRecordReference')} == ${val(e, 'consentRecordReference')}`,
    `${val(g, 'generation')} <= ${val(p, 'maxGeneration')}`, `${val(g, 'createdAt')} <= now`, `${val(g, 'expiresAt')} > now`,
    `${val(g, 'expiresAt')} <= ${val(g, 'createdAt')} + ${val(p, 'maxLifetimeMs')}`, `${val(g, 'expiresAt')} <= ${val(p, 'validUntil')}`,
    `${val(g, 'expiresAt')} <= ${val(e, 'consentValidUntil')}`, `${val(g, 'expiresAt')} <= ${val(a, 'expiresAt')}`,
    `${val(g, 'expiresAt')} <= ${val(r, 'validUntil')}`);
}
function projectionValid(projection, grant, writing) {
  const c = val(grant, 'contextId');
  const source = `root.child('users').child($ownerUid).child('lessonCompletionIndex').child(${val(P(c), 'curriculumVersion')}).child('count')`;
  return and(required(projection, ['schemaVersion', 'ownerUid', 'recipientUid', 'slotId', 'generation', 'contextId', 'policyRevision', 'expiresAt', 'summary']),
    same(projection, grant, ['schemaVersion', 'ownerUid', 'recipientUid', 'slotId', 'generation', 'contextId', 'policyRevision', 'expiresAt']),
    number(at(projection, 'summary/completedLessonCount'), 0, 1000000), timestamp(at(projection, 'summary/asOf')),
    `${val(projection, 'summary/asOf')} >= ${val(grant, 'createdAt')}`, `${val(projection, 'summary/asOf')} <= now`,
    writing && and(`${val(projection, 'summary/asOf')} >= now - 60000`,
      or(and(`!${source}.exists()`, `${val(projection, 'summary/completedLessonCount')} == 0`),
        `${val(projection, 'summary/completedLessonCount')} == ${source}.val()`)));
}
function sharedRules(namespace) {
  const g = "newData.child('grant')", old = "data.child('grant')", projection = "newData.child('projection')";
  const immutable = same(g, old, grantKeys);
  const revoked = and(`${val(g, 'status')} == 'REVOKED'`, '!newData.child(\'projection\').exists()',
    `${val(g, 'revokedAt')} >= ${val(g, 'createdAt')}`, `${val(g, 'revokedAt')} <= now`);
  const active = and(activeValid(g, namespace), projectionValid(projection, g, true));
  const transition = or(
    and('!data.exists()', `${val(g, 'generation')} == 1`, freshTime(at(g, 'createdAt')), active),
    and(`${val(old, 'status')} == 'ACTIVE'`, immutable, or(
      and(`${val(g, 'status')} == 'ACTIVE'`, active,
        `${val(projection, 'summary/asOf')} >= data.child('projection/summary/asOf').val()`),
      and(revoked, freshTime(at(g, 'revokedAt'))))),
    and(`${val(old, 'status')} == 'REVOKED'`, or(
      and(immutable, revoked, `${val(g, 'revokedAt')} == ${val(old, 'revokedAt')}`),
      and(`${val(g, 'generation')} == ${val(old, 'generation')} + 1`, freshTime(at(g, 'createdAt')), active))));
  return { '$ownerUid': { '.read': 'auth != null && auth.uid == $ownerUid', '$slotId': {
    '.write': 'auth != null && auth.uid == $ownerUid && newData.exists()',
    ...shape({ grant: shape({ schemaVersion: 'newData.val() == 1', ownerUid: 'newData.val() == $ownerUid',
      recipientUid: uid('newData'), slotId: 'newData.val() == $slotId', generation: number('newData', 1, 1000000),
      contextId: id('newData'), policyRevision: number('newData', 1, 1000000), acceptanceId: 'newData.isString() && newData.val().matches(/^a[0-4][0-9]$/)',
      consentRecordReference: text('newData'), fields: fieldRules(), createdAt: timestamp('newData'), expiresAt: timestamp('newData'),
      status: "newData.val() == 'ACTIVE' || newData.val() == 'REVOKED'", revokedAt: timestamp('newData') },
    and(required('newData', [...grantKeys, 'fields', 'status']),
      or(and("newData.child('status').val() == 'ACTIVE'", "!newData.child('revokedAt').exists()"),
        and("newData.child('status').val() == 'REVOKED'", "newData.child('revokedAt').exists()")))),
    projection: { '.read': and('auth != null', "auth.uid == data.child('recipientUid').val()", activeValid("data.parent().child('grant')", namespace),
        projectionValid('data', "data.parent().child('grant')", false)),
      ...shape({ schemaVersion: 'newData.val() == 1', ownerUid: 'newData.val() == $ownerUid', recipientUid: uid('newData'),
        slotId: 'newData.val() == $slotId', generation: number('newData', 1, 1000000), contextId: id('newData'),
        policyRevision: number('newData', 1, 1000000), expiresAt: timestamp('newData'),
        summary: shape({ completedLessonCount: number('newData', 0, 1000000), asOf: timestamp('newData') },
          required('newData', ['completedLessonCount', 'asOf'])) },
      projectionValid('newData', "newData.parent().child('grant')", true)) }
    }, and(`$ownerUid.matches(/^[A-Za-z0-9_-]{1,128}$/)`, `$slotId.matches(/^g[0-4]$/)`, required('newData', ['grant']), transition))
  } } };
}

export function completionIndexRules() {
  const n = 'newData', old = 'data';
  const nextKey = val(n, 'lastKey');
  const priorCount = or(and(`!${at(old, 'count')}.exists()`, `${val(n, 'count')} == 1`), `${val(n, 'count')} == ${val(old, 'count')} + 1`);
  const append = and(priorCount, `!data.child('entries').child(${nextKey}).exists()`, `newData.child('entries').child(${nextKey}).exists()`);
  const entry = 'newData', run = `root.child('users').child($uid).child('lessonAttempts').child(${val(entry, 'runId')})`;
  const entryKeys = ['runId', 'curriculumVersion', 'moduleId', 'version', 'lessonId'];
  return { '$curriculumVersion': {
    ...shape({ count: { '.write': 'auth != null && auth.uid == $uid && newData.exists()', '.validate': number('newData', 1, 1000000) },
      lastKey: { '.write': 'auth != null && auth.uid == $uid && newData.exists()', '.validate': 'newData.isString() && newData.val().length <= 148' },
      entries: { '$key': {
        '.write': 'auth != null && auth.uid == $uid && newData.exists()',
        ...shape({ runId: id('newData'), curriculumVersion: 'newData.val() == $curriculumVersion', moduleId: id('newData'),
          version: number('newData', 1, 1000000), lessonId: id('newData') },
        and(required(entry, entryKeys), `$key == ${val(entry, 'moduleId')} + ':v' + ${val(entry, 'version')} + ':' + ${val(entry, 'lessonId')}`,
          `${val(entry, 'curriculumVersion')} == $curriculumVersion`, `${val(run, 'schemaVersion')} == 1`, same(entry, run, entryKeys),
          `root.child('catalog/sharingLessonKeys').child($curriculumVersion).child($key).val() == ${val(run, 'packageSha256')}`,
          `${at(run, 'packageSha256')}.isString()`,
          timestamp(at(run, 'completedAt')), `${val(run, 'completedAt')} <= now`,
          `${val(run, 'resolvedSteps')} == ${val(run, 'totalSteps')}`, `${val(run, 'resolvedSteps')} > 0`,
          or(and('!data.exists()', 'newData.parent().parent().child(\'lastKey\').val() == $key'), same(entry, 'data', entryKeys))))
      } } }, and('$curriculumVersion.matches(/^[A-Za-z0-9_-]{1,64}$/)', required(n, ['count', 'lastKey', 'entries']),
        or(same(n, old, ['count', 'lastKey']), append)))
  } };
}

export function familyRuleAdditions({ namespace = null } = {}) {
  if (namespace !== null) assert.match(namespace, /^demo-studypath-[A-Za-z0-9_-]+$/, 'Synthetic rules require an isolated demo namespace');
  return {
    sharingEligibility: { '$ownerUid': { '$contextId': { '.read': 'auth != null && auth.uid == $ownerUid', '.write': false } } },
    sharingRecipientRoles: { '$recipientUid': { '$contextId': { '.read': 'auth != null && auth.uid == $recipientUid', '.write': false } } },
    familyAcceptances: acceptanceRules(namespace), sharedStudy: sharedRules(namespace)
  };
}

/** Local harness only. Never deploy this result: production database.rules.json denies synthetic policy. */
export function createLocalFamilyRules(productionRules, namespace) {
  const rules = typeof productionRules === 'string' ? JSON.parse(productionRules) : structuredClone(productionRules);
  Object.assign(rules.rules, familyRuleAdditions({ namespace }));
  return JSON.stringify(rules, null, 2) + '\n';
}
