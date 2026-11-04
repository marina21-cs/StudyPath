import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { CandidateError, validateCandidate } from './validate-candidate.mjs';
import { summarizeCandidate } from './summarize-candidate.mjs';
import { packageCandidate } from './package-candidate.mjs';
import { validateRuntimeLesson } from './validate-runtime-lesson.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ?
  Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const objectHash = value => hash(Buffer.from(JSON.stringify(canonical(value))));
const fail = (condition, code) => { if (!condition) throw new CandidateError(code); };
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value) &&
  !['__proto__', 'constructor', 'prototype'].includes(value);
const text = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 1000;
const timestamp = value => Number.isSafeInteger(value) && value >= 0 && value <= 4102444800000;
const exact = (value, fields, code) => fail(record(value) && Object.keys(value).length === fields.length &&
  fields.every(field => Object.hasOwn(value, field)), code);
const preparedPublications = new WeakSet();
const freeze = value => {
  for (const nested of Object.values(value)) if (record(nested)) freeze(nested);
  return Object.freeze(value);
};
const version = value => Number.isInteger(value) && value >= 1 && value <= 1_000_000;
const at = (catalog, kind, key) => catalog[kind]?.[key.curriculumVersion]?.[key.moduleId]?.['v' + key.version];
const set = (catalog, kind, key, value) => {
  catalog[kind] ??= {}; catalog[kind][key.curriculumVersion] ??= {};
  catalog[kind][key.curriculumVersion][key.moduleId] ??= {};
  catalog[kind][key.curriculumVersion][key.moduleId]['v' + key.version] = value;
};
const remove = (catalog, kind, key) => {
  const curriculum = catalog[kind]?.[key.curriculumVersion];
  if (!curriculum?.[key.moduleId]) return;
  delete curriculum[key.moduleId]['v' + key.version];
  if (!Object.keys(curriculum[key.moduleId]).length) delete curriculum[key.moduleId];
  if (!Object.keys(curriculum).length) delete catalog[kind][key.curriculumVersion];
  if (!Object.keys(catalog[kind]).length) delete catalog[kind];
};

/** Validate supplied evidence bindings; only a responsible human can establish its authenticity. */
export function preparePublication({ candidate, approval, evidenceFiles, actorId, occurredAt }) {
  fail(id(actorId) && timestamp(occurredAt), 'PUBLICATION_AUDIT_INVALID');
  const measured = validateCandidate(candidate);
  const manifest = JSON.parse(candidate.manifestBytes.toString('utf8'));
  fail(manifest.applicability.classIds.length === 0, 'PUBLICATION_PUBLIC_SCOPE_REQUIRED');
  const code = 'PUBLICATION_APPROVAL_REQUIRED';
  exact(approval, ['schemaVersion', 'mode', 'curriculumVersion', 'levelId', 'subjectId', 'institution', 'campus',
    'program', 'cohort', 'effectiveYear', 'policyReferences', 'reviewer', 'reviewedOn', 'approvalReference',
    'permissionReference', 'manifestSha256', 'contentAccuracyReviewed', 'lessonSemanticsReviewed',
    'applicabilityReviewed', 'adaptationAllowed', 'redistributionAllowed', 'evidence'], code);
  fail(approval.schemaVersion === 1 && ['HUMAN_REVIEW_RECORDED', 'SYNTHETIC_LOCAL_ONLY'].includes(approval.mode), code);
  for (const field of ['curriculumVersion', 'levelId', 'subjectId']) {
    fail(approval[field] === manifest.applicability[field], code);
  }
  for (const field of ['reviewer', 'reviewedOn', 'approvalReference', 'permissionReference']) {
    fail(approval[field] === manifest.review[field], code);
  }
  fail(['institution', 'campus', 'program', 'cohort', 'effectiveYear'].every(field => text(approval[field])) &&
    /^\d{4}$/.test(approval.effectiveYear) && approval.manifestSha256 === measured.manifestSha256, code);
  fail(['contentAccuracyReviewed', 'lessonSemanticsReviewed', 'applicabilityReviewed', 'adaptationAllowed',
    'redistributionAllowed'].every(field => approval[field] === true), code);
  fail(Array.isArray(approval.policyReferences) && approval.policyReferences.length >= 1 &&
    approval.policyReferences.length <= 20 && approval.policyReferences.every(text) &&
    new Set(approval.policyReferences).size === approval.policyReferences.length, code);
  fail(Array.isArray(approval.evidence) && approval.evidence.length >= 1 && approval.evidence.length <= 5 &&
    evidenceFiles instanceof Map && evidenceFiles.size === approval.evidence.length, code);
  let evidenceBytes = 0;
  const names = new Set();
  for (const proof of approval.evidence) {
    exact(proof, ['file', 'sha256'], code);
    fail(typeof proof.file === 'string' && /^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}$/.test(proof.file) && !names.has(proof.file), code);
    names.add(proof.file);
    const bytes = evidenceFiles.get(proof.file);
    fail(Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= 1_000_000 && hash(bytes) === proof.sha256, code);
    evidenceBytes += bytes.length;
  }
  fail(evidenceBytes <= 5_000_000, code);
  const summary = summarizeCandidate(candidate);
  const payload = packageCandidate(candidate);
  validateRuntimeLesson(payload.manifestJson, payload.textJson);
  const history = { schemaVersion: 1, moduleId: measured.moduleId, version: measured.version,
    curriculumVersion: manifest.applicability.curriculumVersion, packageSha256: measured.packageSha256,
    manifestSha256: measured.manifestSha256, approvalSha256: objectHash(approval),
    summarySha256: objectHash(summary), payloadSha256: objectHash(payload),
    evidenceMode: approval.mode, publishedBy: actorId, publishedAt: occurredAt };
  const result = freeze({ summary, payload, history, contentApprovalEstablished: false });
  preparedPublications.add(result);
  return result;
}

function checkPrepared(prepared) {
  fail(preparedPublications.has(prepared) && record(prepared.summary) && record(prepared.payload) && record(prepared.history), 'PUBLICATION_STATE_CONFLICT');
  const { history, summary, payload } = prepared;
  fail(id(history.curriculumVersion) && id(history.moduleId) && version(history.version) &&
    history.packageSha256 === summary.packageSha256 && history.summarySha256 === objectHash(summary) &&
    history.payloadSha256 === objectHash(payload), 'PUBLICATION_STATE_CONFLICT');
}

/** Pure state transition. It never mutates the input or writes to a database. */
export function publishIntoCatalog(current, prepared) {
  checkPrepared(prepared);
  fail(current === null || record(current), 'PUBLICATION_STATE_CONFLICT');
  const catalog = structuredClone(current ?? {});
  const key = prepared.history;
  const history = at(catalog, 'modulePublicationHistory', key);
  const summary = at(catalog, 'moduleIndex', key), payload = at(catalog, 'modulePackages', key);
  if (history) {
    fail(history.packageSha256 === key.packageSha256 && history.approvalSha256 === key.approvalSha256,
      'VERSION_CONFLICT');
    fail(!history.withdrawal, 'VERSION_WITHDRAWN');
    fail(isDeepStrictEqual(summary, prepared.summary) && isDeepStrictEqual(payload, prepared.payload) &&
      history.summarySha256 === key.summarySha256 && history.payloadSha256 === key.payloadSha256,
    'PUBLICATION_STATE_CONFLICT');
    return { outcome: 'UNCHANGED', catalog };
  }
  fail(!summary && !payload, 'PUBLICATION_STATE_CONFLICT');
  set(catalog, 'moduleIndex', key, prepared.summary);
  set(catalog, 'modulePackages', key, prepared.payload);
  set(catalog, 'modulePublicationHistory', key, prepared.history);
  return { outcome: 'PUBLISHED', catalog };
}

/** Withdrawal changes availability metadata, never a previously issued owner record or local copy. */
export function withdrawFromCatalog(current, request) {
  const code = 'WITHDRAWAL_INVALID';
  exact(request, ['curriculumVersion', 'moduleId', 'version', 'actorId', 'occurredAt', 'reason', 'reference', 'retention'], code);
  fail(id(request.curriculumVersion) && id(request.moduleId) && version(request.version) &&
    id(request.actorId) && timestamp(request.occurredAt) && text(request.reason) && text(request.reference) &&
    ['RETAIN_EXISTING_PRIVATE_COPIES', 'REVIEW_REQUIRED_FOR_RETAINED_COPIES'].includes(request.retention), code);
  fail(current === null || record(current), 'PUBLICATION_STATE_CONFLICT');
  const catalog = structuredClone(current ?? {});
  const history = at(catalog, 'modulePublicationHistory', request);
  fail(history, 'PUBLICATION_NOT_FOUND');
  const summary = at(catalog, 'moduleIndex', request), payload = at(catalog, 'modulePackages', request);
  if (history.withdrawal) {
    fail(!summary && !payload, 'PUBLICATION_STATE_CONFLICT');
    return { outcome: 'UNCHANGED', catalog };
  }
  fail(summary && payload && objectHash(summary) === history.summarySha256 &&
    objectHash(payload) === history.payloadSha256, 'PUBLICATION_STATE_CONFLICT');
  fail(request.occurredAt >= history.publishedAt, code);
  history.withdrawal = { actorId: request.actorId, occurredAt: request.occurredAt, reason: request.reason,
    reference: request.reference, retention: request.retention };
  remove(catalog, 'moduleIndex', request); remove(catalog, 'modulePackages', request);
  return { outcome: 'WITHDRAWN', catalog };
}

export function localCatalogUrl({ host, namespace }) {
  fail(typeof host === 'string' && /^127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(host) &&
    Number(host.split(':')[1]) <= 65535 && typeof namespace === 'string' &&
    /^demo-studypath-[a-z0-9-]{1,64}$/.test(namespace), 'LOCAL_EMULATOR_REQUIRED');
  return `http://${host}/catalog.json?ns=${namespace}`;
}

/** Bounded whole-catalog compare-and-set prevents partial publication and lost concurrent edits. */
export async function executeCatalogOperation({ transport, operation, apply = false }) {
  fail(typeof apply === 'boolean' && typeof operation === 'function' &&
    typeof transport?.read === 'function' && typeof transport?.compareAndSet === 'function', 'PUBLICATION_STATE_CONFLICT');
  for (let attempts = 1; attempts <= 3; attempts++) {
    const { catalog, etag } = await transport.read();
    const next = operation(catalog);
    if (!apply || next.outcome === 'UNCHANGED') return { outcome: next.outcome, attempts,
      publicationPerformed: false, dryRun: !apply };
    if (await transport.compareAndSet(etag, next.catalog)) return { outcome: next.outcome,
      attempts, publicationPerformed: next.outcome === 'PUBLISHED', withdrawalPerformed: next.outcome === 'WITHDRAWN', dryRun: false };
  }
  throw new CandidateError('PUBLICATION_RETRY_EXHAUSTED');
}
