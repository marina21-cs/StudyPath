#!/usr/bin/env node
/** Real loopback-only publication/withdrawal probe. Never starts services or targets cloud. */
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMockCurriculum } from '../mock/curriculum.mjs';
import { createLocalTransport } from '../catalog/local-publication-transport.mjs';
import { executeCatalogOperation, preparePublication, publishIntoCatalog, withdrawFromCatalog } from '../catalog/publication.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const EVIDENCE_ROOT = path.join(ROOT, 'implementation/phases/04-ppt-spec-alignment/01-backend-learner-catalog/test-results');
const HOST = '127.0.0.1:9000';
const SHARED = ['demo-studypath-default-rtdb', 'demo-studypath'];
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ?
  Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const sha = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(canonical(value))).digest('hex');
const at = (catalog, kind, key) => catalog?.[kind]?.[key.curriculumVersion]?.[key.moduleId]?.['v' + key.version];
const args = process.argv.slice(2);
if (args.length !== 4 || args[0] !== '--namespace' || args[2] !== '--evidence-dir' ||
    !/^demo-studypath-mock-publisher-[a-f0-9]{8}$/.test(args[1] ?? '')) {
  console.error('Usage: node backend/scripts/probe-mock-withdrawal.mjs --namespace demo-studypath-mock-publisher-<8hex> --evidence-dir <new phase test-results folder>');
  process.exit(2);
}
const namespace = args[1], evidence = path.resolve(args[3]);
if (!evidence.startsWith(EVIDENCE_ROOT + path.sep)) {
  console.error('Choose a new evidence directory inside B09 phase test-results.'); process.exit(2);
}
await mkdir(evidence, { recursive: false });
const receipt = { schemaVersion: 1, status: 'FAILED', startedAtUtc: new Date().toISOString(), feature: 'S02',
  namespace, databaseHost: HOST, syntheticOnly: true, approvalMode: 'SYNTHETIC_LOCAL_ONLY',
  humanApprovalEstablished: false, cloudVerified: false, acceptancePromoted: false, servicesStarted: false,
  sourceSha256: {}, checks: [], transportTrace: [], cleanup: { namespaceClaimed: false, ownNamespaceNull: false } };
for (const name of ['backend/scripts/probe-mock-withdrawal.mjs', 'backend/catalog/publication.mjs',
  'backend/catalog/local-publication-transport.mjs', 'backend/mock/curriculum.mjs']) {
  receipt.sourceSha256[name] = sha(await readFile(path.join(ROOT, name)));
}
let claim = null;
let sharedBefore = null;
const fail = message => { throw new Error(message); };
async function databaseRequest(selectedNamespace, method = 'GET', { value, etag, etagRead = false } = {}) {
  assert(SHARED.includes(selectedNamespace) && method === 'GET' || selectedNamespace === namespace,
    'Mutations are restricted to the exact owned namespace.');
  const response = await fetch(`http://${HOST}/.json?ns=${selectedNamespace}`, { method,
    headers: { Authorization: 'Bearer owner', 'content-type': 'application/json',
      ...(etagRead ? { 'X-Firebase-ETag': 'true' } : {}), ...(etag ? { 'If-Match': etag } : {}) },
    ...(value === undefined ? {} : { body: JSON.stringify(value) }), redirect: 'error', signal: AbortSignal.timeout(10_000) });
  if (!response.ok) fail(`Scoped database ${method} failed: HTTP ${response.status}`);
  return { value: await response.json(), etag: response.headers.get('etag') };
}
async function authDigest() {
  const users = []; let pageToken = '';
  do {
    const url = new URL('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchGet');
    url.searchParams.set('maxResults', '1000'); if (pageToken) url.searchParams.set('nextPageToken', pageToken);
    const response = await fetch(url, { headers: { Authorization: 'Bearer owner' },
      redirect: 'error', signal: AbortSignal.timeout(10_000) });
    if (!response.ok) fail(`Read-only Auth digest failed: HTTP ${response.status}`);
    const body = await response.json(); users.push(...(body.users ?? [])); pageToken = body.nextPageToken ?? '';
    if (users.length > 10_000) fail('Read-only Auth digest exceeded the bounded local inventory.');
  } while (pageToken);
  users.sort((a, b) => String(a.localId).localeCompare(String(b.localId)));
  return sha(users);
}
async function sharedDigests() {
  const values = {};
  for (const shared of SHARED) values[shared] = sha((await databaseRequest(shared)).value);
  values.auth = await authDigest(); return values;
}
async function save() {
  await writeFile(path.join(evidence, 'verification.json'), JSON.stringify(receipt, null, 2) + '\n');
}
const checked = (name, detail = {}) => receipt.checks.push({ name, status: 'PASSED', ...detail });
try {
  sharedBefore = await sharedDigests(); receipt.sharedBefore = sharedBefore;
  const empty = await databaseRequest(namespace, 'GET', { etagRead: true });
  assert.equal(empty.value, null, 'Refuse a nonempty namespace.'); assert(empty.etag, 'Require ETag before claiming.');
  claim = { purpose: 'S02 synthetic local withdrawal probe', nonce: randomBytes(16).toString('hex') };
  await databaseRequest(namespace, 'PUT', { value: { probeOwnership: claim }, etag: empty.etag });
  receipt.cleanup.namespaceClaimed = true;
  assert.deepEqual((await databaseRequest(namespace)).value.probeOwnership, claim); checked('empty namespace claimed by exact root CAS');
  const tracedFetch = async (url, options) => {
    const expected = `http://${HOST}/catalog.json?ns=${namespace}`;
    assert.equal(String(url), expected, 'Catalog adapter must remain in the owned loopback namespace.');
    assert(['GET', 'PUT'].includes(options.method));
    if (options.method === 'PUT') assert(options.headers['If-Match'], 'Every catalog mutation requires If-Match.');
    if (options.method === 'GET') assert.equal(options.headers['X-Firebase-ETag'], 'true');
    const response = await fetch(url, options);
    receipt.transportTrace.push({ method: options.method, path: 'catalog', status: response.status,
      etagRequested: options.method === 'GET', ifMatchPresent: Boolean(options.headers['If-Match']),
      ...(options.body ? { requestBodySha256: sha(options.body) } : {}) });
    return response;
  };
  const transport = createLocalTransport({ host: HOST, namespace, fetchImpl: tracedFetch });
  const fixture = createMockCurriculum();
  for (const [index, row] of fixture.candidates.entries()) {
    const prepared = preparePublication(row.publicationInput), key = prepared.history;
    const publication = await executeCatalogOperation({ transport, operation: current => publishIntoCatalog(current, prepared), apply: true });
    assert.equal(publication.outcome, 'PUBLISHED'); assert.equal(publication.publicationPerformed, true);
    const published = await transport.read();
    assert.deepEqual(at(published.catalog, 'moduleIndex', key), prepared.summary);
    assert.deepEqual(at(published.catalog, 'modulePackages', key), prepared.payload);
    assert.deepEqual(at(published.catalog, 'modulePublicationHistory', key), prepared.history);
    const historyBefore = structuredClone(at(published.catalog, 'modulePublicationHistory', key));
    checked('publication exact independent readback', { moduleId: key.moduleId, packageSha256: key.packageSha256, ...publication });
    const request = { curriculumVersion: key.curriculumVersion, moduleId: key.moduleId, version: key.version,
      actorId: 'mock_withdrawal_probe', occurredAt: Math.max(Date.now(), key.publishedAt),
      reason: 'Synthetic local withdrawal lifecycle probe; no real content decision.', reference: 'SYNTHETIC_TEST_ONLY_WITHDRAWAL',
      retention: index % 2 ? 'REVIEW_REQUIRED_FOR_RETAINED_COPIES' : 'RETAIN_EXISTING_PRIVATE_COPIES' };
    const withdrawal = await executeCatalogOperation({ transport, operation: current => withdrawFromCatalog(current, request), apply: true });
    assert.equal(withdrawal.outcome, 'WITHDRAWN'); assert.equal(withdrawal.withdrawalPerformed, true);
    const withdrawn = await transport.read(), historyAfter = at(withdrawn.catalog, 'modulePublicationHistory', key);
    assert.equal(at(withdrawn.catalog, 'moduleIndex', key), undefined); assert.equal(at(withdrawn.catalog, 'modulePackages', key), undefined);
    const { withdrawal: retained, ...immutable } = historyAfter;
    assert.deepEqual(immutable, historyBefore); assert.deepEqual(retained, {
      actorId: request.actorId, occurredAt: request.occurredAt, reason: request.reason, reference: request.reference, retention: request.retention });
    assert.notEqual(withdrawn.etag, published.etag);
    checked('atomic availability withdrawal and immutable history readback', { moduleId: key.moduleId, retention: request.retention, ...withdrawal });
    const putsBeforeRepeat = receipt.transportTrace.filter(call => call.method === 'PUT').length;
    const repeat = await executeCatalogOperation({ transport, operation: current => withdrawFromCatalog(current, request), apply: true });
    assert.equal(repeat.outcome, 'UNCHANGED'); assert.equal(repeat.publicationPerformed, false);
    const repeated = await transport.read(); assert.deepEqual(repeated, withdrawn);
    assert.equal(receipt.transportTrace.filter(call => call.method === 'PUT').length, putsBeforeRepeat);
    checked('exact withdrawal repeat unchanged with no write', { moduleId: key.moduleId, ...repeat });
    await assert.rejects(executeCatalogOperation({ transport, operation: current => publishIntoCatalog(current, prepared), apply: true }),
      error => error.code === 'VERSION_WITHDRAWN');
    const denied = await transport.read(); assert.deepEqual(denied, withdrawn);
    assert.equal(receipt.transportTrace.filter(call => call.method === 'PUT').length, putsBeforeRepeat);
    checked('republish withdrawn immutable version denied with no write', { moduleId: key.moduleId, code: 'VERSION_WITHDRAWN' });
    await save();
  }
  assert.equal(receipt.transportTrace.filter(call => call.method === 'PUT').length, fixture.candidates.length * 2);
  assert(receipt.transportTrace.every(call => call.status === 200));
  receipt.catalogCompareAndSetWrites = fixture.candidates.length * 2; receipt.moduleAdminImports = 0;
  receipt.modules = fixture.candidates.length; receipt.status = 'PASSED';
} catch (error) {
  receipt.failure = error.code ?? error.message;
  process.exitCode = 1;
} finally {
  if (receipt.cleanup.namespaceClaimed) {
    try {
      const current = await databaseRequest(namespace, 'GET', { etagRead: true });
      assert.deepEqual(current.value?.probeOwnership, claim, 'Never delete an unowned namespace.');
      await databaseRequest(namespace, 'DELETE', { etag: current.etag });
      receipt.cleanup.ownNamespaceNull = (await databaseRequest(namespace)).value === null;
      assert.equal(receipt.cleanup.ownNamespaceNull, true); checked('owned namespace cleanup readback null');
    } catch (error) { receipt.cleanup.failure = error.code ?? error.message; receipt.status = 'FAILED'; process.exitCode = 1; }
  }
  if (sharedBefore) {
    try {
      receipt.sharedAfter = await sharedDigests(); assert.deepEqual(receipt.sharedAfter, sharedBefore);
      receipt.sharedControlsPreserved = true; checked('two shared database namespaces and Auth digests preserved');
    } catch (error) { receipt.sharedControlsPreserved = false; receipt.preservationFailure = error.code ?? error.message;
      receipt.status = 'FAILED'; process.exitCode = 1; }
  }
  receipt.finishedAtUtc = new Date().toISOString(); receipt.exitCode = process.exitCode ?? 0;
  await save();
  console.log(JSON.stringify({ status: receipt.status, exitCode: receipt.exitCode, namespace,
    modules: receipt.modules ?? 0, checksPassed: receipt.checks.length, ownNamespaceNull: receipt.cleanup.ownNamespaceNull,
    sharedControlsPreserved: receipt.sharedControlsPreserved ?? false, cloudVerified: false, evidence }));
}
