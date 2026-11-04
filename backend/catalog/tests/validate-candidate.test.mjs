import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture, hash, addReviewedPdf } from './fixture.mjs';
import { validateCandidate } from '../validate-candidate.mjs';

const check = value => validateCandidate({ manifestBytes: Buffer.from(JSON.stringify(value.manifest)),
  files: value.files, publishedIndex: value.publishedIndex, assetBudgetBytes: value.assetBudgetBytes ?? 0 });
const reject = (mutate, code) => {
  const value = fixture(); mutate(value);
  assert.throws(() => check(value), error => error.code === code);
};

test('reviewed inventory yields measured bytes and hashes without mutating inputs', () => {
  const value = fixture(); const before = JSON.stringify(value.manifest);
  const result = check(value);
  assert.equal(result.moduleId, 'fixture_module'); assert.equal(result.version, 1);
  assert.equal(result.textBytes, Buffer.byteLength(before) + value.files.get('module.json').length);
  assert.match(result.packageSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.assetBytes, 0); assert.equal(result.scope, 'B09_METADATA_PREFLIGHT');
  assert.equal(JSON.stringify(value.manifest), before);
});

test('drafts and demonstration records cannot pass the review gate', () => {
  for (const status of ['DRAFT', 'DEMONSTRATION_ONLY', 'REVIEW_PENDING', true, null]) {
    reject(v => { v.manifest.review.status = status; }, 'REVIEW_REQUIRED');
  }
  for (const field of ['reviewer', 'reviewedOn', 'approvalReference', 'permissionReference']) {
    reject(v => { delete v.manifest.review[field]; }, 'REVIEW_REQUIRED');
  }
  reject(v => { v.manifest.review.adaptationAllowed = false; }, 'REVIEW_REQUIRED');
  reject(v => { v.manifest.review.redistributionAllowed = false; }, 'REVIEW_REQUIRED');
  reject(v => { v.manifest.review.reviewedOn = '2026-02-30'; }, 'REVIEW_REQUIRED');
});

test('missing objectives and incompatible applicability are rejected', () => {
  reject(v => { v.manifest.objectives = []; }, 'INVENTORY_INVALID');
  for (const field of ['curriculumVersion', 'levelId', 'subjectId']) {
    reject(v => { delete v.manifest.applicability[field]; }, 'APPLICABILITY_INVALID');
  }
  reject(v => { v.manifest.applicability.classIds = ['one', 'one']; }, 'APPLICABILITY_INVALID');
  reject(v => { v.manifest.applicability.membershipRole = 'teacher'; }, 'APPLICABILITY_INVALID');
});

test('unknown fields, unsupported schema versions and malformed identifiers fail closed', () => {
  reject(v => { v.manifest.schemaVersion = 2; }, 'SCHEMA_INVALID');
  reject(v => { v.manifest.version = 1.5; }, 'SCHEMA_INVALID');
  reject(v => { v.manifest.moduleId = '../other'; }, 'SCHEMA_INVALID');
  reject(v => { v.manifest.clientCanPublish = true; }, 'SCHEMA_INVALID');
  reject(v => { v.manifest.lessons[0].unexpected = true; }, 'INVENTORY_INVALID');
});

test('duplicate stable IDs and unresolved references are rejected', () => {
  for (const field of ['objectives', 'concepts', 'lessons', 'questions']) {
    reject(v => { v.manifest[field].push(structuredClone(v.manifest[field][0])); }, 'INVENTORY_INVALID');
  }
  for (const field of ['objectiveIds', 'questionIds', 'reviewQuestionIds', 'conceptIds']) {
    reject(v => { v.manifest.lessons[0][field] = ['missing']; }, 'REFERENCE_INVALID');
  }
  reject(v => { v.manifest.questions[0].objectiveId = 'missing'; }, 'REFERENCE_INVALID');
  reject(v => { v.manifest.questions[0].conceptIds = ['missing']; }, 'REFERENCE_INVALID');
  reject(v => { v.manifest.lessons[0].stepIds.push('step_1'); }, 'INVENTORY_INVALID');
  reject(v => { v.manifest.lessons.push({ ...v.manifest.lessons[0], id: 'lesson_2' }); }, 'REFERENCE_INVALID');
});

test('questions must belong to the lesson objective and independent review set', () => {
  reject(v => { v.manifest.lessons[0].reviewQuestionIds = ['q_1']; }, 'REFERENCE_INVALID');
  reject(v => { v.manifest.lessons[0].questionIds = ['q_review']; }, 'REFERENCE_INVALID');
  reject(v => { v.manifest.objectives.push({ id: 'other', description: 'Other objective' });
    v.manifest.questions[0].objectiveId = 'other'; }, 'REFERENCE_INVALID');
  reject(v => { v.manifest.questions[0].format = 'ai_grading'; }, 'INVENTORY_INVALID');
});

test('payload hash, actual bytes, UTF-8 and module/version binding are verified', () => {
  reject(v => { v.files.set('module.json', Buffer.from('changed')); }, 'TEXT_INVALID');
  reject(v => { v.manifest.text.bytes += 1; }, 'TEXT_INVALID');
  reject(v => { const data = Buffer.from([0xff]); v.files.set('module.json', data);
    Object.assign(v.manifest.text, { bytes: 1, sha256: hash(data) }); }, 'TEXT_INVALID');
  reject(v => { const data = Buffer.from('{"moduleId":"other","version":1}');
    v.files.set('module.json', data); Object.assign(v.manifest.text, { bytes: data.length, sha256: hash(data) }); }, 'TEXT_INVALID');
});

test('complete raw UTF-8 text is strictly below 100000 bytes including metadata', () => {
  const value = fixture(); const data = Buffer.from(JSON.stringify({ moduleId: 'fixture_module', version: 1, body: '界'.repeat(34000) }));
  value.files.set('module.json', data); Object.assign(value.manifest.text, { bytes: data.length, sha256: hash(data) });
  assert.throws(() => check(value), error => error.code === 'TEXT_LIMIT');
  const atSize = target => {
    const v = fixture(); const base = JSON.stringify({ moduleId: 'fixture_module', version: 1, body: '' });
    let padding = target - Buffer.byteLength(JSON.stringify(v.manifest)) - Buffer.byteLength(base);
    for (let i = 0; i < 4; i++) {
      const bytes = Buffer.from(base.replace('"body":""', '"body":"' + 'x'.repeat(padding) + '"'));
      v.files.set('module.json', bytes); Object.assign(v.manifest.text, { bytes: bytes.length, sha256: hash(bytes) });
      padding += target - Buffer.byteLength(JSON.stringify(v.manifest)) - bytes.length;
    }
    return v;
  };
  assert.equal(check(atSize(99999)).textBytes, 99999);
  assert.throws(() => check(atSize(100000)), error => error.code === 'TEXT_LIMIT');
});

test('published versions permit exact retry and reject changed content or metadata', () => {
  const value = fixture(); const first = check(value);
  value.publishedIndex.modules.fixture_module = { v1: { packageSha256: first.packageSha256 } };
  assert.equal(check(value).packageSha256, first.packageSha256);
  value.manifest.title = 'Changed';
  assert.throws(() => check(value), error => error.code === 'VERSION_CONFLICT');
  value.manifest.version = 2;
  const data = Buffer.from(JSON.stringify({ moduleId: 'fixture_module', version: 2 }));
  value.files.set('module.json', data); Object.assign(value.manifest.text, { bytes: data.length, sha256: hash(data) });
  assert.equal(check(value).version, 2);
});

test('unsafe filenames, undeclared files and malformed publication history fail closed', () => {
  for (const file of ['../module.json', '/module.json', 'sub/module.json']) reject(v => { v.manifest.text.file = file; }, 'FILE_INVALID');
  reject(v => { v.files.set('extra.json', Buffer.from('{}')); }, 'FILE_INVALID');
  reject(v => { v.publishedIndex = {}; }, 'INDEX_INVALID');
  reject(v => { v.publishedIndex.modules.fixture_module = { v1: { packageSha256: 'bad' } }; }, 'INDEX_INVALID');
});

test('PDF assets need an explicit separate budget and exact hash/size inventory', () => {
  const attach = addReviewedPdf;
  const value = fixture(); attach(value);
  assert.equal(check(value).assetBytes, value.files.get('source.pdf').length);
  reject(v => { attach(v); v.assetBudgetBytes = 0; }, 'ASSET_INVALID');
  reject(v => { attach(v); v.manifest.assets[0].sha256 = '0'.repeat(64); }, 'ASSET_INVALID');
  reject(v => { attach(v); v.manifest.assets[0].kind = 'UPLOAD'; }, 'ASSET_INVALID');
});
