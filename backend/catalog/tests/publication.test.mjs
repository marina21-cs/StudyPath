import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hash } from './fixture.mjs';
import { runtimeCandidate } from './runtime-fixture.mjs';
const api = await import('../publication.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});

function input() {
  const value = runtimeCandidate();
  value.manifest.applicability.classIds = [];
  const candidate = { manifestBytes: Buffer.from(JSON.stringify(value.manifest)), files: value.files,
    publishedIndex: value.publishedIndex };
  return { candidate, actorId: 'local_maintainer', occurredAt: 1793318400000,
    approval: { schemaVersion: 1, mode: 'SYNTHETIC_LOCAL_ONLY', curriculumVersion: 'fixture_v1',
      levelId: 'fixture_level', subjectId: 'fixture_subject', institution: 'Synthetic institution',
      campus: 'Synthetic campus', program: 'Synthetic program', cohort: 'test', effectiveYear: '2026',
      policyReferences: ['TEST_ONLY_POLICY'], reviewer: 'TEST ONLY', reviewedOn: '2026-10-26',
      approvalReference: 'TEST ONLY', permissionReference: 'TEST ONLY',
      manifestSha256: hash(candidate.manifestBytes), contentAccuracyReviewed: true,
      lessonSemanticsReviewed: true, applicabilityReviewed: true, adaptationAllowed: true,
      redistributionAllowed: true, evidence: [{ file: 'review.txt', sha256: hash(Buffer.from('Synthetic review evidence only')) }] },
    evidenceFiles: new Map([['review.txt', Buffer.from('Synthetic review evidence only')]]) };
}
function prepare(value = input()) {
  assert.equal(typeof api.preparePublication, 'function', 'trusted publication preparation must exist');
  return api.preparePublication(value);
}
const identity = () => ({ curriculumVersion: 'fixture_v1', moduleId: 'fixture_module', version: 1 });
const withdrawal = () => ({ ...identity(), actorId: 'local_maintainer', occurredAt: 1793318400001,
  reason: 'Synthetic test withdrawal', reference: 'TEST_ONLY_WITHDRAWAL', retention: 'RETAIN_EXISTING_PRIVATE_COPIES' });

test('publication validates exact bytes and recorded human-review evidence without asserting authenticity', () => {
  const prepared = prepare();
  assert.equal(prepared.summary.visibility, 'PUBLIC');
  assert.equal(prepared.payload.manifestJson, input().candidate.manifestBytes.toString());
  assert.equal(prepared.history.packageSha256, prepared.summary.packageSha256);
  assert.match(prepared.history.approvalSha256, /^[a-f0-9]{64}$/);
  assert.equal(prepared.contentApprovalEstablished, false);
});

test('missing or mismatched review, permissions, context, evidence hashes and unknown fields fail closed', () => {
  for (const change of [
    v => { delete v.approval.reviewer; },
    v => { v.approval.curriculumVersion = 'other'; },
    v => { v.approval.subjectId = 'other'; },
    v => { v.approval.manifestSha256 = '0'.repeat(64); },
    v => { v.approval.reviewer = 'Other'; },
    v => { v.approval.contentAccuracyReviewed = false; },
    v => { v.approval.lessonSemanticsReviewed = false; },
    v => { v.approval.redistributionAllowed = false; },
    v => { v.approval.policyReferences = []; },
    v => { v.approval.evidence[0].sha256 = '0'.repeat(64); },
    v => { v.evidenceFiles.set('other.txt', Buffer.from('unexpected')); },
    v => { v.approval.clientApproved = true; },
  ]) {
    const value = input(); change(value);
    assert.throws(() => prepare(value), error => error.code === 'PUBLICATION_APPROVAL_REQUIRED');
  }
});

test('public-only publication rejects section-filtered candidates until membership scope is reviewed', () => {
  const value = input();
  const manifest = JSON.parse(value.candidate.manifestBytes);
  manifest.applicability.classIds = ['restricted_class'];
  value.candidate.manifestBytes = Buffer.from(JSON.stringify(manifest));
  value.approval.manifestSha256 = hash(value.candidate.manifestBytes);
  assert.throws(() => prepare(value), error => error.code === 'PUBLICATION_PUBLIC_SCOPE_REQUIRED');
});

test('invalid actor and time cannot enter an Admin audit record', () => {
  for (const change of [v => { v.actorId = '../root'; }, v => { v.occurredAt = NaN; },
    v => { v.occurredAt = -1; }, v => { v.occurredAt = 4102444800001; }]) {
    const value = input(); change(value);
    assert.throws(() => prepare(value), error => error.code === 'PUBLICATION_AUDIT_INVALID');
  }
});

test('one catalog transition exposes coherent index, transport and immutable history and preserves other content', () => {
  const prepared = prepare();
  const original = { approved: false, unrelated: { keep: true } };
  const result = api.publishIntoCatalog(original, prepared);
  assert.equal(result.outcome, 'PUBLISHED');
  assert.deepEqual(original, { approved: false, unrelated: { keep: true } });
  assert.deepEqual(result.catalog.unrelated, original.unrelated);
  assert.deepEqual(result.catalog.moduleIndex.fixture_v1.fixture_module.v1, prepared.summary);
  assert.deepEqual(result.catalog.modulePackages.fixture_v1.fixture_module.v1, prepared.payload);
  assert.deepEqual(result.catalog.modulePublicationHistory.fixture_v1.fixture_module.v1, prepared.history);
});

test('publication transitions reject caller-forged prepared objects and prepared objects cannot be modified', () => {
  const prepared = prepare();
  assert.throws(() => api.publishIntoCatalog({}, structuredClone(prepared)), error => error.code === 'PUBLICATION_STATE_CONFLICT');
  assert.throws(() => { prepared.summary.title = 'tampered'; }, TypeError);
  assert.throws(() => { prepared.history.packageSha256 = '0'.repeat(64); }, TypeError);
});

test('exact retry preserves first audit identity and returns unchanged catalog', () => {
  const first = api.publishIntoCatalog({}, prepare());
  const value = input(); value.actorId = 'other_maintainer'; value.occurredAt += 1000;
  const retry = api.publishIntoCatalog(first.catalog, prepare(value));
  assert.equal(retry.outcome, 'UNCHANGED');
  assert.deepEqual(retry.catalog, first.catalog);
});

test('server JSON property ordering does not change immutable content identity or block withdrawal', () => {
  const sorted = value => Array.isArray(value) ? value.map(sorted) : value && typeof value === 'object' ?
    Object.fromEntries(Object.keys(value).sort().map(key => [key, sorted(value[key])])) : value;
  const first = api.publishIntoCatalog({}, prepare());
  const roundTrip = sorted(first.catalog);
  assert.equal(api.publishIntoCatalog(roundTrip, prepare()).outcome, 'UNCHANGED');
  assert.equal(api.withdrawFromCatalog(roundTrip, withdrawal()).outcome, 'WITHDRAWN');
  const value = input(); value.approval = sorted(value.approval);
  assert.equal(api.publishIntoCatalog(roundTrip, prepare(value)).outcome, 'UNCHANGED');
});

test('same version with changed bytes or changed approval binding is rejected', () => {
  const first = api.publishIntoCatalog({}, prepare());
  for (const mutate of [v => {
    const manifest = JSON.parse(v.candidate.manifestBytes); manifest.title = 'Changed title';
    v.candidate.manifestBytes = Buffer.from(JSON.stringify(manifest));
    v.approval.manifestSha256 = hash(v.candidate.manifestBytes);
  }, v => { v.approval.institution = 'Different institution'; }]) {
    const value = input(); mutate(value);
    assert.throws(() => api.publishIntoCatalog(first.catalog, prepare(value)), error => error.code === 'VERSION_CONFLICT');
  }
});

test('existing orphan or inconsistent publication records cannot be silently overwritten', () => {
  const prepared = prepare();
  const first = api.publishIntoCatalog({}, prepared);
  for (const mutate of [v => { delete v.modulePackages; }, v => { delete v.modulePublicationHistory; },
    v => { v.moduleIndex.fixture_v1.fixture_module.v1.title = 'Tampered'; }]) {
    const catalog = structuredClone(first.catalog); mutate(catalog);
    assert.throws(() => api.publishIntoCatalog(catalog, prepared), error => error.code === 'PUBLICATION_STATE_CONFLICT');
  }
});

test('withdrawal removes new selection and transport together while retaining version identity and unrelated data', () => {
  const first = api.publishIntoCatalog({ unrelated: { keep: true } }, prepare());
  const result = api.withdrawFromCatalog(first.catalog, withdrawal());
  assert.equal(result.outcome, 'WITHDRAWN');
  assert.equal(result.catalog.moduleIndex?.fixture_v1?.fixture_module?.v1, undefined);
  assert.equal(result.catalog.modulePackages?.fixture_v1?.fixture_module?.v1, undefined);
  assert.deepEqual(result.catalog.unrelated, { keep: true });
  const history = result.catalog.modulePublicationHistory.fixture_v1.fixture_module.v1;
  assert.equal(history.packageSha256, first.catalog.modulePublicationHistory.fixture_v1.fixture_module.v1.packageSha256);
  assert.equal(history.withdrawal.retention, 'RETAIN_EXISTING_PRIVATE_COPIES');
  assert.deepEqual(first.catalog.moduleIndex.fixture_v1.fixture_module.v1, prepare().summary);
});

test('withdrawal retry is unchanged, unknown and inconsistent targets fail, and withdrawn versions cannot be republished', () => {
  const first = api.publishIntoCatalog({}, prepare());
  const withdrawn = api.withdrawFromCatalog(first.catalog, withdrawal());
  assert.equal(api.withdrawFromCatalog(withdrawn.catalog, { ...withdrawal(), occurredAt: 1793318400999 }).outcome, 'UNCHANGED');
  assert.throws(() => api.publishIntoCatalog(withdrawn.catalog, prepare()), error => error.code === 'VERSION_WITHDRAWN');
  assert.throws(() => api.withdrawFromCatalog({}, withdrawal()), error => error.code === 'PUBLICATION_NOT_FOUND');
  const inconsistent = structuredClone(first.catalog); delete inconsistent.modulePackages;
  assert.throws(() => api.withdrawFromCatalog(inconsistent, withdrawal()), error => error.code === 'PUBLICATION_STATE_CONFLICT');
});

test('withdrawal validates identity, retention and audit input before mutation', () => {
  const first = api.publishIntoCatalog({}, prepare());
  for (const mutate of [v => { v.moduleId = '../module'; }, v => { v.version = 0; },
    v => { v.retention = 'DELETE_ALL_COPIES'; }, v => { v.reason = ''; }, v => { v.force = true; }]) {
    const request = withdrawal(); mutate(request);
    assert.throws(() => api.withdrawFromCatalog(first.catalog, request), error => error.code === 'WITHDRAWAL_INVALID');
  }
});

test('emulator transport rejects nonloopback, cloud namespaces and malformed endpoints', () => {
  prepare();
  for (const target of [
    { host: 'example.com:9000', namespace: 'demo-studypath-test' },
    { host: '127.0.0.1:9000', namespace: 'real-production-default-rtdb' },
    { host: '127.0.0.1:9000/path', namespace: 'demo-studypath-test' },
    { host: '127.0.0.1:9000', namespace: 'demo-studypath-test?x=1' },
  ]) assert.throws(() => api.localCatalogUrl(target), error => error.code === 'LOCAL_EMULATOR_REQUIRED');
  assert.equal(api.localCatalogUrl({ host: '127.0.0.1:9000', namespace: 'demo-studypath-owned-test' }),
    'http://127.0.0.1:9000/catalog.json?ns=demo-studypath-owned-test');
});

test('CAS retry re-reads concurrent catalog edits, publishes once, and sends no write during dry-run', async () => {
  const prepared = prepare(); let current = { unrelated: 'before' }; let version = 1; let writes = 0;
  const transport = {
    async read() { return { catalog: structuredClone(current), etag: String(version) }; },
    async compareAndSet(etag, catalog) {
      writes++;
      if (writes === 1) { current.concurrent = true; version++; return false; }
      assert.equal(etag, String(version)); current = catalog; version++; return true;
    },
  };
  const dry = await api.executeCatalogOperation({ transport, operation: catalog => api.publishIntoCatalog(catalog, prepared), apply: false });
  assert.equal(dry.publicationPerformed, false); assert.equal(writes, 0);
  const applied = await api.executeCatalogOperation({ transport, operation: catalog => api.publishIntoCatalog(catalog, prepared), apply: true });
  assert.equal(applied.publicationPerformed, true); assert.equal(applied.attempts, 2);
  assert.equal(current.concurrent, true); assert.equal(writes, 2);
  const retried = await api.executeCatalogOperation({ transport, operation: catalog => api.publishIntoCatalog(catalog, prepared), apply: true });
  assert.equal(retried.outcome, 'UNCHANGED'); assert.equal(writes, 2);
});

test('CAS exhaustion is bounded and cannot claim publication', async () => {
  const prepared = prepare(); let writes = 0;
  const transport = { async read() { return { catalog: {}, etag: 'v1' }; },
    async compareAndSet() { writes++; return false; } };
  await assert.rejects(() => api.executeCatalogOperation({ transport,
    operation: catalog => api.publishIntoCatalog(catalog, prepared), apply: true }),
  error => error.code === 'PUBLICATION_RETRY_EXHAUSTED');
  assert.equal(writes, 3);
});
