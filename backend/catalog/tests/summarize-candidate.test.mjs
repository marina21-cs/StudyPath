import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './fixture.mjs';
import { validateCandidate } from '../validate-candidate.mjs';
import { summarizeCandidate } from '../summarize-candidate.mjs';

const candidate = () => {
  const value = fixture();
  return { ...value, manifestBytes: Buffer.from(JSON.stringify(value.manifest)) };
};
test('summary binds immutable module/version and exact measured package identity', () => {
  const value = candidate(), measured = validateCandidate(value), summary = summarizeCandidate(value);
  assert.deepEqual(Object.keys(summary).sort(), ['schemaVersion', 'moduleId', 'version', 'title', 'visibility',
    'applicability', 'source', 'review', 'textBytes', 'assetBytes', 'packageSha256'].sort());
  for (const field of ['moduleId', 'version', 'textBytes', 'assetBytes', 'packageSha256']) assert.equal(summary[field], measured[field]);
  assert.equal(summary.schemaVersion, 1); assert.equal(summary.visibility, 'PUBLIC');
  assert.deepEqual(summary.applicability.classIds, { fixture_class: true });
  assert.deepEqual(summary.source, value.manifest.source); assert.deepEqual(summary.review, value.manifest.review);
});
test('empty public class applicability is omitted for RTDB compatibility', () => {
  const value = candidate(); value.manifest.applicability.classIds = [];
  value.manifestBytes = Buffer.from(JSON.stringify(value.manifest));
  assert.equal('classIds' in summarizeCandidate(value).applicability, false);
});
test('projection revalidates bytes review and immutable identity instead of trusting caller objects', () => {
  const value = candidate(); value.manifest.title = 'Untrusted stale caller object';
  assert.equal(summarizeCandidate(value).title, 'Synthetic test fixture');
  for (const change of [m => { m.review.adaptationAllowed = false; }, m => { m.text.bytes += 1; },
    m => { m.lessons[0].questionIds = ['unresolved']; }]) {
    const changed = candidate(); change(changed.manifest);
    changed.manifestBytes = Buffer.from(JSON.stringify(changed.manifest));
    assert.throws(() => summarizeCandidate(changed));
  }
});
