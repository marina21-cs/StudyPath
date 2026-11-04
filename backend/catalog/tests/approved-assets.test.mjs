import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture, hash, addReviewedPdf } from './fixture.mjs';
import { validateCandidate } from '../validate-candidate.mjs';
import { packageCandidate } from '../package-candidate.mjs';

export function assetFixture() {
  return addReviewedPdf(fixture());
}
const candidate = v => ({ ...v, manifestBytes: Buffer.from(JSON.stringify(v.manifest)) });
const check = v => validateCandidate(candidate(v));

test('reviewed asset policy bounds public prepublished PDF bytes and transport preserves the full raw manifest', () => {
  const value = assetFixture(); const measured = check(value);
  assert.equal(measured.assetBytes, value.files.get('source.pdf').length);
  const transport = packageCandidate(candidate(value));
  assert.deepEqual(JSON.parse(transport.manifestJson).assetPolicy, value.manifest.assetPolicy);
  assert.equal(JSON.parse(transport.manifestJson).assets[0].url, value.manifest.assets[0].url);
});

test('asset policy, exact reviewer binding, declared bounds and access mode are mandatory', () => {
  for (const mutate of [v => { delete v.manifest.assetPolicy; }, v => { v.manifest.assetPolicy.maxCount = 0; },
    v => { v.manifest.assetPolicy.maxFileBytes = 0; }, v => { v.manifest.assetPolicy.maxFileBytes = 20971521; },
    v => { v.manifest.assetPolicy.maxTotalBytes = 104857601; }, v => { v.manifest.assetPolicy.maxTotalBytes = 1; },
    v => { v.manifest.assetPolicy.reviewer = 'Other'; }, v => { v.manifest.assetPolicy.approvalReference = 'Other'; },
    v => { v.manifest.assetPolicy.reviewedOn = '2026-02-30'; }, v => { v.manifest.assetPolicy.reference = ''; },
    v => { v.manifest.assetPolicy.access = 'PAID_UPLOAD'; }, v => { v.manifest.assetPolicy.mimeTypes = ['text/html']; },
    v => { v.manifest.assetPolicy.allowRedirects = true; }, v => { v.manifest.assets[0].url = 'https://other.example.org/source.pdf'; }]) {
    const value = assetFixture(); mutate(value);
    assert.throws(() => check(value), error => ['ASSET_POLICY_INVALID', 'ASSET_INVALID'].includes(error.code));
  }
});

test('unsafe origins and asset URLs cannot pass local publication validation', () => {
  for (const url of ['http://materials.example.org/source.pdf', 'https://materials.example.org:444/source.pdf',
    'https://user@materials.example.org/source.pdf', 'https://materials.example.org/source.pdf#x',
    'https://materials.example.org/source.html', 'https://127.0.0.1/source.pdf']) {
    const value = assetFixture(); value.manifest.assets[0].url = url;
    assert.throws(() => check(value), error => error.code === 'ASSET_POLICY_INVALID');
  }
  for (const origin of ['https://127.0.0.1', 'https://localhost', 'https://materials.example.org/path',
    'https://materials.example.org:444', 'https://user@materials.example.org', 'https://materials.example.org?x=1']) {
    const value = assetFixture(); value.manifest.assetPolicy.origins = [origin];
    assert.throws(() => check(value), error => error.code === 'ASSET_POLICY_INVALID');
  }
});

test('PDF magic, EOF, exact hash and limits are verified against actual bytes', () => {
  for (const bytes of [Buffer.from('<html>not a PDF</html>'), Buffer.from('%PDF-1.7\ntruncated'),
    Buffer.from('%PDF-9.9\nunsupported\n%%EOF')]) {
    const value = assetFixture(); value.files.set('source.pdf', bytes);
    Object.assign(value.manifest.assets[0], { bytes: bytes.length, sha256: hash(bytes) });
    assert.throws(() => check(value), error => error.code === 'ASSET_INVALID');
  }
  const value = assetFixture(); value.assetBudgetBytes = 1;
  assert.throws(() => check(value), error => error.code === 'ASSET_INVALID');
});

test('empty text-only packages preserve schema and reject an unused asset policy', () => {
  const value = fixture(); value.manifest.assetPolicy = assetFixture().manifest.assetPolicy;
  assert.throws(() => check(value), error => error.code === 'ASSET_POLICY_INVALID');
});
