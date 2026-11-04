import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture, hash, addReviewedPdf } from './fixture.mjs';
import { validateCandidate } from '../validate-candidate.mjs';
import { packageCandidate as project } from '../package-candidate.mjs';
const candidate = () => {
  const value = fixture();
  return { ...value, manifestBytes: Buffer.from(JSON.stringify(value.manifest, null, 2) + '\n') };
};

test('text transport preserves exact original bytes and contains only schema and raw JSON', () => {
  const value = candidate(), before = Buffer.from(value.manifestBytes);
  const result = project(value);
  assert.deepEqual(Object.keys(result).sort(), ['schemaVersion', 'manifestJson', 'textJson'].sort());
  assert.equal(result.schemaVersion, 1);
  assert.deepEqual(Buffer.from(result.manifestJson), before);
  assert.deepEqual(Buffer.from(result.textJson), value.files.get('module.json'));
  assert.equal(validateCandidate({ ...value, manifestBytes: Buffer.from(result.manifestJson),
    files: new Map([['module.json', Buffer.from(result.textJson)]]) }).packageSha256,
  validateCandidate(value).packageSha256);
  assert.deepEqual(value.manifestBytes, before);
});

test('projection reads validated raw manifest rather than a stale caller object', () => {
  const value = candidate(); value.manifest.text.file = 'stale.json';
  assert.equal(JSON.parse(project(value).manifestJson).text.file, 'module.json');
});

test('Unicode and whitespace round-trip without changing byte identity', () => {
  const value = candidate();
  const text = Buffer.from('{ "moduleId":"fixture_module", "version":1, "body":"Equal parts: 界 🧠" }\n');
  value.files.set('module.json', text);
  Object.assign(value.manifest.text, { bytes: text.length, sha256: hash(text) });
  value.manifestBytes = Buffer.from(JSON.stringify(value.manifest, null, 1));
  assert.deepEqual(Buffer.from(project(value).textJson), text);
});

test('revalidation rejects absent bytes hash mismatch and unapproved review', () => {
  for (const [code, change] of [['FILE_INVALID', v => { v.files.delete('module.json'); }],
    ['TEXT_INVALID', v => { v.files.set('module.json', Buffer.from('changed')); }],
    ['REVIEW_REQUIRED', v => { const m = JSON.parse(v.manifestBytes); m.review.adaptationAllowed = false;
      v.manifestBytes = Buffer.from(JSON.stringify(m)); }]]) {
    const value = candidate(); change(value);
    assert.throws(() => project(value), error => error.code === code);
  }
});

test('complete reviewed PDF descriptors remain in raw transport without embedding binary assets', () => {
  const value = addReviewedPdf(candidate());
  value.manifestBytes = Buffer.from(JSON.stringify(value.manifest));
  assert.equal(validateCandidate(value).assetBytes, value.files.get('source.pdf').length);
  assert.deepEqual(JSON.parse(project(value).manifestJson).assets, value.manifest.assets);
  assert.deepEqual(Object.keys(project(value)).sort(), ['manifestJson', 'schemaVersion', 'textJson']);
});

test('UTF-8 BOM that would be dropped while decoding is rejected instead of silently changing identity', () => {
  const value = candidate(); value.manifestBytes = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), value.manifestBytes]);
  assert.throws(() => project(value), error => error.code === 'UTF8_ROUND_TRIP');
});
