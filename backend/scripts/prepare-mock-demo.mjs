import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMockCurriculum, MOCK_CONTEXT } from '../mock/curriculum.mjs';
import { publishMockCurriculum } from '../mock/seed-emulator.mjs';
import { createFamilyFixture } from '../sharing/family-fixture.mjs';
import { createLocalFamilyRules } from '../sharing/firebase-family-rules.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [mode, namespace, ...flags] = process.argv.slice(2);
assert(['--check', '--prepare', '--family', '--cleanup'].includes(mode), 'Use --check, --prepare, --family or --cleanup NAMESPACE.');
assert.match(namespace ?? '', /^demo-studypath-mock-[a-f0-9]{8}$/, 'Use exact demo-studypath-mock-<8hex>; shared/cloud/publisher namespaces are refused.');
const options = {};
for (let i = 0; i < flags.length; i++) {
  const flag = flags[i]; assert(['--receipt', '--family-rules', '--owner-uid', '--recipient-uid'].includes(flag) && !(flag in options), 'Unexpected/duplicate option.');
  options[flag] = flag === '--family-rules' ? true : flags[++i];
  assert(options[flag], 'Missing option value.');
}
assert(mode === '--prepare' || !options['--family-rules'], '--family-rules is a preparation choice.');
assert(mode === '--family' || !options['--owner-uid'] && !options['--recipient-uid'], 'Identity inputs are only accepted by --family.');
const receiptPath = path.resolve(options['--receipt'] ?? path.join(root, '.build/mock-demo', namespace, 'setup-receipt.json'));
assert(receiptPath.startsWith(path.join(root, '.build/mock-demo') + path.sep), 'Keep setup receipts inside ignored .build/mock-demo.');
const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest('hex');
const origin = 'http://127.0.0.1:9000';
const admin = { Authorization: 'Bearer owner', 'content-type': 'application/json' };
async function request(relative = '', method = 'GET', value, etag, etagRead = false) {
  assert(!relative.startsWith('/') && !relative.includes('..') && !relative.includes('?'));
  const response = await fetch(`${origin}/${relative}.json?ns=${namespace}`, { method,
    headers: { ...admin, ...(etag ? { 'If-Match': etag } : {}), ...(etagRead ? { 'X-Firebase-ETag': 'true' } : {}) },
    ...(value === undefined ? {} : { body: JSON.stringify(value) }), redirect: 'error', signal: AbortSignal.timeout(10_000) });
  assert(response.ok, `Scoped local ${method} failed: HTTP ${response.status}`);
  return { value: await response.json(), etag: response.headers.get('etag') };
}
async function save(receipt) {
  await mkdir(path.dirname(receiptPath), { recursive: true });
  await writeFile(receiptPath, JSON.stringify(receipt, null, 2) + '\n', { mode: 0o600 });
}
async function owned() {
  const receipt = JSON.parse(await readFile(receiptPath, 'utf8'));
  assert.equal(receipt.namespace, namespace); assert.equal(receipt.syntheticOnly, true);
  const snapshot = await request('', 'GET', undefined, undefined, true);
  assert.deepEqual(snapshot.value?.mockDemoSetup, receipt.claim, 'Ownership marker differs; no existing namespace may be overwritten or cleaned.');
  return { receipt, snapshot };
}
async function identities(ownerUid, recipientUid) {
  for (const uid of [ownerUid, recipientUid]) assert.match(uid ?? '', /^[A-Za-z0-9_-]{1,128}$/);
  assert.notEqual(ownerUid, recipientUid, 'The recipient must be independently authenticated.');
  const found = new Map(), seen = new Set(); let token = '', count = 0;
  do {
    const url = new URL('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchGet');
    url.searchParams.set('maxResults', '1000'); if (token) url.searchParams.set('nextPageToken', token);
    const response = await fetch(url, { headers: { Authorization: 'Bearer owner' }, redirect: 'error', signal: AbortSignal.timeout(10_000) });
    assert(response.ok, 'Read-only local Auth ownership lookup failed.');
    const body = await response.json(); count += (body.users ?? []).length; assert(count <= 10_000, 'Bounded local Auth inventory exceeded.');
    for (const row of body.users ?? []) if ([ownerUid, recipientUid].includes(row.localId)) found.set(row.localId, row);
    token = body.nextPageToken ?? ''; assert(!token || !seen.has(token), 'Repeated Auth page.'); seen.add(token);
  } while (token);
  for (const [uid, label] of [[ownerUid, 'owner'], [recipientUid, 'recipient']]) {
    const user = found.get(uid); assert(user && user.disabled !== true, 'Both selected local Auth identities must exist and be enabled.');
    assert.equal(user.email, `mock_${label}_${namespace.slice(-8)}@studypath.invalid`, 'Auth identity is not the exact namespace-owned synthetic account.');
    const profile = (await request(`users/${uid}/profile`)).value;
    assert(profile && typeof profile === 'object', 'Each identity must have registered in this exact mock namespace through the app.');
  }
  const preferences = (await request(`users/${ownerUid}/profile/learnerPreferences`)).value;
  assert(preferences?.onboardingComplete === true && preferences.curriculumVersion === MOCK_CONTEXT && preferences.levelId === 'mock_college', 'Owner must explicitly save the matching mock learning context in the app.');
}

try {
  if (mode === '--check') {
    const fixture = createMockCurriculum();
    console.log(JSON.stringify({ status: 'CONFIGURATION_VALIDATED_SERVICES_UNTOUCHED', namespace, syntheticOnly: true,
      modules: fixture.expected.moduleIds, assetOrigin: 'https://mock-assets.example.org', mirror: 'http://10.0.2.2:8765/',
      moduleAdminImports: 0, usersSeeded: false, cloudTouched: false }));
  } else if (mode === '--prepare') {
    const fixture = createMockCurriculum();
    const empty = await request('', 'GET', undefined, undefined, true);
    assert.equal(empty.value, null, 'Refuse any nonempty namespace.'); assert(empty.etag, 'Require root ETag before claiming.');
    const claim = { schemaVersion: 1, purpose: 'StudyPath synthetic interactive mock demo', namespace, claimId: randomUUID(),
      preparedAt: Date.now(), familyRules: options['--family-rules'] === true };
    // Explicit fixture projection: these are the same five academic paths as seedMockEmulator, no module/private import.
    const catalog = { courses: fixture.catalog.courses, learnerPreferenceOptions: { [MOCK_CONTEXT]: fixture.academic.options },
      academicPolicies: { [MOCK_CONTEXT]: fixture.academic.policy } };
    if (claim.familyRules) {
      const family = createFamilyFixture({ namespace });
      catalog.sharingPolicies = { [MOCK_CONTEXT]: family.policy }; catalog.sharingTestControl = { enabled: true, namespace };
    }
    const receipt = { schemaVersion: 1, namespace, syntheticOnly: true, accepted: false, status: 'PREPARING', claim,
      startedAtUtc: new Date().toISOString(), moduleAdminImports: 0, usersSeeded: false, grantsSeeded: false,
      setupKind: 'whitelisted academic fixture projection + empty-root ETag/CAS claim', sourceSha256: {} };
    // Refuse an existing receipt as well, preserving the only local proof needed for scoped cleanup.
    await mkdir(path.dirname(receiptPath), { recursive: true });
    await writeFile(receiptPath, JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    try {
      await request('', 'PUT', { mockDemoSetup: claim, catalog }, empty.etag);
      assert.deepEqual((await request('mockDemoSetup')).value, claim);
      const source = await readFile(path.join(root, 'backend/database.rules.json'), 'utf8');
      const rules = claim.familyRules ? JSON.parse(createLocalFamilyRules(source, namespace)) : JSON.parse(source);
      await request('.settings/rules', 'PUT', rules); receipt.localRulesSha256 = hash(rules);
      receipt.publication = (await publishMockCurriculum({ namespace })).receipt;
      assert.equal(receipt.publication.publications.length, 3);
      assert(receipt.publication.publications.every(row => row.publicationPerformed === true));
      for (const file of ['backend/scripts/prepare-mock-demo.mjs', 'backend/mock/curriculum.mjs', 'backend/mock/content.mjs', 'backend/mock/pdf.mjs', 'backend/mock/seed-emulator.mjs', 'backend/database.rules.json', 'backend/sharing/firebase-family-rules.mjs']) {
        receipt.sourceSha256[file] = hash(await readFile(path.join(root, file)));
      }
      receipt.status = 'PREPARED_INTERACTIVE_DEVICE_UNVERIFIED'; receipt.finishedAtUtc = new Date().toISOString();
      await save(receipt); console.log(JSON.stringify({ status: receipt.status, namespace, receipt: receiptPath, syntheticOnly: true, accepted: false }));
    } catch (error) { receipt.status = 'FAILED_KEEP_OWNERSHIP_FOR_SCOPED_CLEANUP'; receipt.failure = error.message; await save(receipt); throw error; }
  } else if (mode === '--family') {
    const { receipt, snapshot } = await owned(); assert(receipt.claim.familyRules, 'Prepare with --family-rules before enabling synthetic family eligibility.');
    const ownerUid = options['--owner-uid'], recipientUid = options['--recipient-uid']; await identities(ownerUid, recipientUid);
    const policy = snapshot.value.catalog?.sharingPolicies?.[MOCK_CONTEXT];
    assert(policy?.approvalStatus === 'APPROVED' && policy.evidenceMode === 'SYNTHETIC_LOCAL_ONLY' && policy.validUntil > Date.now(), 'Prepared mock family policy is not current.');
    const fixture = createFamilyFixture({ namespace, ownerUid, recipientUid });
    const records = [[`sharingEligibility/${ownerUid}/${MOCK_CONTEXT}`, { ...fixture.eligibility, consentValidUntil: policy.validUntil }],
      [`sharingRecipientRoles/${recipientUid}/${MOCK_CONTEXT}`, { ...fixture.recipientRole, validUntil: policy.validUntil }]];
    for (const [relative, value] of records) {
      const current = await request(relative, 'GET', undefined, undefined, true); assert.equal(current.value, null, 'Existing trusted identity records are never overwritten.');
      assert(current.etag); await request(relative, 'PUT', value, current.etag); assert.deepEqual((await request(relative)).value, value);
    }
    receipt.familyEligibility = { status: 'SYNTHETIC_TRUSTED_ROLES_ADDED', updatedAt: Date.now(),
      identitySha256: hash([ownerUid, recipientUid]), privateRecordsWritten: false, acceptanceWritten: false, grantWritten: false };
    await save(receipt); console.log(JSON.stringify(receipt.familyEligibility));
  } else {
    const { receipt, snapshot } = await owned(); assert(snapshot.etag);
    await request('', 'DELETE', undefined, snapshot.etag); assert.equal((await request()).value, null);
    receipt.status = 'OWNED_NAMESPACE_CLEANED'; receipt.cleanedAtUtc = new Date().toISOString(); await save(receipt);
    console.log(JSON.stringify({ status: receipt.status, namespace, authAccountsDeleted: false, appDataCleared: false }));
  }
} catch (error) { console.error(error.message); process.exitCode = 1; }
