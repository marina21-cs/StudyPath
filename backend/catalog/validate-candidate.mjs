import { createHash } from 'node:crypto';
import { isIP } from 'node:net';

// B09 metadata preflight. B11 lesson/answer semantics and S00 human approval are separate gates.
export const TEXT_LIMIT_BYTES = 100_000;
export class CandidateError extends Error {
  constructor(code) { super(code); this.name = 'CandidateError'; this.code = code; }
}
const requireThat = (condition, code) => { if (!condition) throw new CandidateError(code); };
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const exact = (value, fields, code) => requireThat(record(value) &&
  Object.keys(value).length === fields.length && fields.every(key => Object.hasOwn(value, key)), code);
const text = (value, max = 240) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const unique = values => new Set(values).size === values.length;

function ids(values, code, minimum = 1) {
  requireThat(Array.isArray(values) && values.length >= minimum && values.length <= 1000 &&
    values.every(id) && unique(values), code);
}
function rows(values, fields) {
  requireThat(Array.isArray(values) && values.length > 0 && values.length <= 1000, 'INVENTORY_INVALID');
  values.forEach(value => { exact(value, fields, 'INVENTORY_INVALID'); requireThat(id(value.id), 'INVENTORY_INVALID'); });
  requireThat(unique(values.map(value => value.id)), 'INVENTORY_INVALID');
  return new Set(values.map(value => value.id));
}
function json(bytes, code) {
  requireThat(Buffer.isBuffer(bytes), code);
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw new CandidateError(code); }
}

function checkSource(manifest) {
  const source = manifest.source;
  exact(source, ['title', 'reference', 'locator', 'author', 'version'], 'REVIEW_REQUIRED');
  requireThat(Object.values(source).every(value => text(value, 1000)), 'REVIEW_REQUIRED');
  const review = manifest.review;
  exact(review, ['status', 'reviewer', 'reviewedOn', 'approvalReference', 'permissionReference',
    'adaptationAllowed', 'redistributionAllowed'], 'REVIEW_REQUIRED');
  requireThat(review.status === 'APPROVED' && review.adaptationAllowed === true && review.redistributionAllowed === true,
    'REVIEW_REQUIRED');
  requireThat(['reviewer', 'approvalReference', 'permissionReference'].every(key => text(review[key], 1000)), 'REVIEW_REQUIRED');
  requireThat(typeof review.reviewedOn === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(review.reviewedOn), 'REVIEW_REQUIRED');
  const day = new Date(review.reviewedOn);
  requireThat(!Number.isNaN(day.valueOf()) && day.toISOString().slice(0, 10) === review.reviewedOn, 'REVIEW_REQUIRED');
}

function checkInventory(manifest) {
  const objectives = rows(manifest.objectives, ['id', 'description']);
  requireThat(manifest.objectives.every(value => text(value.description, 1000)), 'INVENTORY_INVALID');
  const concepts = rows(manifest.concepts, ['id', 'keywords']);
  manifest.concepts.forEach(value => requireThat(Array.isArray(value.keywords) && value.keywords.length > 0 &&
    value.keywords.length <= 12 && value.keywords.every(word => text(word, 80)) && unique(value.keywords), 'INVENTORY_INVALID'));
  rows(manifest.lessons, ['id', 'objectiveIds', 'stepIds', 'questionIds', 'reviewQuestionIds', 'conceptIds']);
  const questions = rows(manifest.questions, ['id', 'objectiveId', 'conceptIds', 'format']);
  manifest.questions.forEach(value => {
    requireThat(id(value.objectiveId) && ['selected_response', 'ordering', 'bounded_response'].includes(value.format), 'INVENTORY_INVALID');
    ids(value.conceptIds, 'INVENTORY_INVALID');
    requireThat(objectives.has(value.objectiveId) && value.conceptIds.every(key => concepts.has(key)), 'REFERENCE_INVALID');
  });
  const byQuestion = new Map(manifest.questions.map(value => [value.id, value]));
  const usedQuestions = [], usedSteps = [], usedObjectives = new Set();
  manifest.lessons.forEach(lesson => {
    for (const field of ['objectiveIds', 'stepIds', 'questionIds', 'reviewQuestionIds', 'conceptIds']) ids(lesson[field], 'INVENTORY_INVALID');
    requireThat(lesson.objectiveIds.every(key => objectives.has(key)) && lesson.conceptIds.every(key => concepts.has(key)), 'REFERENCE_INVALID');
    const allQuestions = [...lesson.questionIds, ...lesson.reviewQuestionIds];
    requireThat(unique(allQuestions) && allQuestions.every(key => questions.has(key)), 'REFERENCE_INVALID');
    requireThat(allQuestions.every(key => lesson.objectiveIds.includes(byQuestion.get(key).objectiveId) &&
      byQuestion.get(key).conceptIds.every(concept => lesson.conceptIds.includes(concept))), 'REFERENCE_INVALID');
    usedQuestions.push(...allQuestions); usedSteps.push(...lesson.stepIds);
    lesson.objectiveIds.forEach(key => usedObjectives.add(key));
  });
  requireThat(unique(usedQuestions) && usedQuestions.length === questions.size && unique(usedSteps) &&
    usedObjectives.size === objectives.size, 'REFERENCE_INVALID');
}

function checkFileDescriptor(value, code, asset = false) {
  exact(value, asset ? ['id', 'file', 'kind', 'bytes', 'sha256', 'url'] : ['file', 'bytes', 'sha256'], code);
  requireThat(typeof value.file === 'string' && /^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}$/.test(value.file), 'FILE_INVALID');
  requireThat(Number.isSafeInteger(value.bytes) && value.bytes > 0 && digest(value.sha256), code);
  if (asset) requireThat(id(value.id) && value.kind === 'PDF' && value.file.endsWith('.pdf'), code);
  else requireThat(value.file.endsWith('.json'), code);
}

function checkAssetPolicy(manifest) {
  const code = 'ASSET_POLICY_INVALID';
  if (!manifest.assets.length) {
    requireThat(!Object.hasOwn(manifest, 'assetPolicy'), code);
    return;
  }
  const policy = manifest.assetPolicy;
  exact(policy, ['reference', 'approvalReference', 'reviewer', 'reviewedOn', 'access', 'origins',
    'maxCount', 'maxFileBytes', 'maxTotalBytes', 'mimeTypes'], code);
  requireThat(text(policy.reference, 1000) && policy.approvalReference === manifest.review.approvalReference &&
    policy.reviewer === manifest.review.reviewer && policy.reviewedOn === manifest.review.reviewedOn &&
    policy.access === 'PUBLIC_PREPUBLISHED', code);
  requireThat(Number.isSafeInteger(policy.maxCount) && policy.maxCount >= 1 && policy.maxCount <= 20 &&
    Number.isSafeInteger(policy.maxFileBytes) && policy.maxFileBytes >= 1 && policy.maxFileBytes <= 20971520 &&
    Number.isSafeInteger(policy.maxTotalBytes) && policy.maxTotalBytes >= policy.maxFileBytes &&
    policy.maxTotalBytes <= 104857600 && manifest.assets.length <= policy.maxCount &&
    Array.isArray(policy.mimeTypes) && policy.mimeTypes.length === 1 && policy.mimeTypes[0] === 'application/pdf', code);
  requireThat(Array.isArray(policy.origins) && policy.origins.length >= 1 && policy.origins.length <= 20 &&
    unique(policy.origins), code);
  for (const origin of policy.origins) {
    let url; try { url = new URL(origin); } catch { throw new CandidateError(code); }
    requireThat(typeof origin === 'string' && origin.length <= 2048 && origin === url.origin && url.protocol === 'https:' &&
      !url.port && !url.username && !url.password && !isIP(url.hostname) &&
      /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/.test(url.hostname) &&
      !/\.(?:localhost|local|internal|test|invalid)$/.test(url.hostname), code);
  }
  for (const asset of manifest.assets) {
    let url; try { url = new URL(asset.url); } catch { throw new CandidateError(code); }
    requireThat(typeof asset.url === 'string' && asset.url.length <= 2048 && url.protocol === 'https:' &&
      !url.username && !url.password && !url.hash && !url.port && url.pathname.endsWith('.pdf') &&
      policy.origins.includes(url.origin) && asset.bytes <= policy.maxFileBytes, code);
  }
  requireThat(manifest.assets.reduce((sum, asset) => sum + asset.bytes, 0) <= policy.maxTotalBytes, code);
}

function checkHistory(index) {
  exact(index, ['schemaVersion', 'modules'], 'INDEX_INVALID');
  requireThat(index.schemaVersion === 1 && record(index.modules), 'INDEX_INVALID');
  for (const [moduleId, versions] of Object.entries(index.modules)) {
    requireThat(id(moduleId) && record(versions), 'INDEX_INVALID');
    for (const [version, entry] of Object.entries(versions)) {
      requireThat(/^v[1-9][0-9]{0,6}$/.test(version) && Number(version.slice(1)) <= 1_000_000, 'INDEX_INVALID');
      exact(entry, ['packageSha256'], 'INDEX_INVALID'); requireThat(digest(entry.packageSha256), 'INDEX_INVALID');
    }
  }
}

/** Pure local validation. The caller supplies bytes, never network locations or executable content. */
export function validateCandidate({ manifestBytes, files, publishedIndex, assetBudgetBytes = 0 }) {
  requireThat(Buffer.isBuffer(manifestBytes) && manifestBytes.length < TEXT_LIMIT_BYTES, 'TEXT_LIMIT');
  const manifest = json(manifestBytes, 'SCHEMA_INVALID');
  exact(manifest, ['schemaVersion', 'moduleId', 'version', 'title', 'applicability', 'source', 'review',
    'objectives', 'concepts', 'lessons', 'questions', 'text', 'assets',
    ...(Object.hasOwn(manifest, 'assetPolicy') ? ['assetPolicy'] : [])], 'SCHEMA_INVALID');
  requireThat(manifest.schemaVersion === 1 && id(manifest.moduleId) && Number.isInteger(manifest.version) &&
    manifest.version >= 1 && manifest.version <= 1_000_000 && text(manifest.title), 'SCHEMA_INVALID');
  const applicability = manifest.applicability;
  exact(applicability, ['curriculumVersion', 'levelId', 'subjectId', 'classIds'], 'APPLICABILITY_INVALID');
  requireThat(['curriculumVersion', 'levelId', 'subjectId'].every(key => id(applicability[key])), 'APPLICABILITY_INVALID');
  ids(applicability.classIds, 'APPLICABILITY_INVALID', 0);
  checkSource(manifest); checkInventory(manifest); checkHistory(publishedIndex);
  checkFileDescriptor(manifest.text, 'TEXT_INVALID');
  requireThat(Array.isArray(manifest.assets) && manifest.assets.length <= 20, 'ASSET_INVALID');
  manifest.assets.forEach(value => checkFileDescriptor(value, 'ASSET_INVALID', true));
  checkAssetPolicy(manifest);
  requireThat(unique(manifest.assets.map(value => value.id)), 'ASSET_INVALID');
  const descriptors = [manifest.text, ...manifest.assets];
  requireThat(files instanceof Map && unique(descriptors.map(value => value.file)) && files.size === descriptors.length &&
    descriptors.every(value => files.has(value.file)) && !files.has('manifest.json'), 'FILE_INVALID');
  requireThat(Number.isSafeInteger(assetBudgetBytes) && assetBudgetBytes >= 0, 'ASSET_INVALID');
  const content = files.get(manifest.text.file);
  requireThat(Buffer.isBuffer(content) && content.length === manifest.text.bytes && sha(content) === manifest.text.sha256, 'TEXT_INVALID');
  const textBytes = manifestBytes.length + content.length;
  requireThat(textBytes < TEXT_LIMIT_BYTES, 'TEXT_LIMIT');
  const payload = json(content, 'TEXT_INVALID');
  requireThat(record(payload) && payload.moduleId === manifest.moduleId && payload.version === manifest.version, 'TEXT_INVALID');
  let assetBytes = 0;
  for (const descriptor of manifest.assets) {
    const bytes = files.get(descriptor.file);
    requireThat(Buffer.isBuffer(bytes) && bytes.length === descriptor.bytes && sha(bytes) === descriptor.sha256, 'ASSET_INVALID');
    requireThat(/^%PDF-(?:1\.[0-7]|2\.0)(?:\r|\n)/.test(bytes.subarray(0, 16).toString('latin1')) &&
      /%%EOF\s*$/.test(bytes.subarray(Math.max(0, bytes.length - 1024)).toString('latin1')), 'ASSET_INVALID');
    assetBytes += bytes.length;
    requireThat(Number.isSafeInteger(assetBytes) && assetBytes <= assetBudgetBytes, 'ASSET_INVALID');
  }
  const packageSha256 = sha(JSON.stringify({ manifestSha256: sha(manifestBytes), textSha256: sha(content),
    assets: manifest.assets.map(value => ({ id: value.id, sha256: value.sha256, bytes: value.bytes })) }));
  const existing = Object.hasOwn(publishedIndex.modules, manifest.moduleId) && publishedIndex.modules[manifest.moduleId]['v' + manifest.version];
  requireThat(!existing || existing.packageSha256 === packageSha256, 'VERSION_CONFLICT');
  return { scope: 'B09_METADATA_PREFLIGHT', moduleId: manifest.moduleId, version: manifest.version,
    textBytes, assetBytes, manifestSha256: sha(manifestBytes), textSha256: sha(content), packageSha256 };
}
