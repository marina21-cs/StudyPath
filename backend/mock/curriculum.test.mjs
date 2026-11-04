import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { validateCandidate } from '../catalog/validate-candidate.mjs';
import { validateRuntimeLesson } from '../catalog/validate-runtime-lesson.mjs';
import { preparePublication } from '../catalog/publication.mjs';
const api = await import('./curriculum.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return {};
  throw error;
});
const build = options => {
  assert.equal(typeof api.createMockCurriculum, 'function', 'An isolated mock curriculum builder must exist');
  return api.createMockCurriculum(options);
};

test('three distinct original mock modules publish only with synthetic evidence in memory', () => {
  const value = build();
  assert.equal(value.syntheticOnly, true); assert.equal(value.contextId, 'mock_computing_v1');
  assert.equal(value.levelId, 'mock_college'); assert.equal(value.candidates.length, 3);
  assert.deepEqual(value.candidates.map(row => row.moduleId), ['mock_programming', 'mock_data_structures', 'mock_databases']);
  assert.equal(new Set(value.candidates.map(row => JSON.parse(row.candidate.manifestBytes).objectives[0].description)).size, 3);
  for (const row of value.candidates) {
    const measured = validateCandidate(row.candidate), manifest = JSON.parse(row.candidate.manifestBytes);
    const text = row.candidate.files.get(manifest.text.file).toString();
    validateRuntimeLesson(row.candidate.manifestBytes.toString(), text);
    assert.ok(measured.textBytes < 100_000); assert.ok(measured.assetBytes > 0);
    assert.match(manifest.title, /^MOCK/); assert.match(manifest.review.reviewer, /MOCK/);
    assert.equal(row.approval.mode, 'SYNTHETIC_LOCAL_ONLY');
    assert.equal(preparePublication(row.publicationInput).history.evidenceMode, 'SYNTHETIC_LOCAL_ONLY');
    assert.equal(value.catalog.modulePublicationHistory[value.curriculumVersion][row.moduleId].v1.evidenceMode, 'SYNTHETIC_LOCAL_ONLY');
  }
});

test('mock PDF inventories bind deterministic full files and all supported interaction families', () => {
  const first = build(), second = build(); const formats = new Set();
  assert.equal(first.assets.length, 3);
  for (const row of first.candidates) {
    const manifest = JSON.parse(row.candidate.manifestBytes), text = JSON.parse(row.candidate.files.get(manifest.text.file));
    text.questions.forEach(q => formats.add(q.format));
    assert.ok(text.lessons[0].steps.some(step => step.kind === 'EXAMPLE'));
    assert.ok(manifest.lessons[0].reviewQuestionIds.length > 0); assert.ok(text.glossary.length >= 3);
    for (const asset of manifest.assets) {
      const file = row.candidate.files.get(asset.file);
      assert.equal(file.length, asset.bytes); assert.equal(createHash('sha256').update(file).digest('hex'), asset.sha256);
      assert.match(file.toString('ascii', 0, 9), /^%PDF-1\.4/); assert.ok(file.toString('ascii').trimEnd().endsWith('%%EOF'));
      assert.match(file.toString('ascii'), /\/BaseFont \/Courier \/Encoding \/WinAnsiEncoding/);
    }
    const other = second.candidates.find(item => item.moduleId === row.moduleId);
    assert.deepEqual(row.candidate.manifestBytes, other.candidate.manifestBytes);
    assert.equal(row.summary.packageSha256, other.summary.packageSha256);
  }
  assert.deepEqual([...formats].sort(), ['bounded_response', 'ordering', 'selected_response']);
});

test('configurable public asset origin rebinds package identity and rejects unsafe origins', () => {
  const before = build(), after = build({ assetOrigin: 'https://demo-assets.example.com' });
  assert.notEqual(before.candidates[0].summary.packageSha256, after.candidates[0].summary.packageSha256);
  assert.match(after.assets[0].url, /^https:\/\/demo-assets\.example\.com\//);
  for (const assetOrigin of ['http://localhost', 'https://mock-assets.studypath.invalid', 'https://example.org/path']) {
    assert.throws(() => build({ assetOrigin }));
  }
});

test('mock preferences policies courses and record bindings agree without real approval names', () => {
  const value = build(), academic = value.academic;
  assert.equal(academic.preferences.curriculumVersion, value.curriculumVersion);
  assert.equal(academic.policy.contextRef, value.contextId); assert.match(academic.policy.reviewer, /MOCK/);
  assert.equal(academic.options.approved, true);
  for (const [courseId, course] of Object.entries(value.catalog.courses)) {
    assert.equal(academic.policy.coursePlans[courseId].planVersion, course.gradePlan.version);
    assert.equal(Object.values(course.gradePlan.categories).reduce((sum, item) => sum + item.weight, 0), 1);
    for (const capability of ['SCORES', 'SCENARIOS', 'GWA', 'PREREQUISITES']) {
      const binding = value.binding(courseId, capability);
      assert.equal(binding.policyRef, academic.policy.capabilityApprovalRefs[capability]);
      assert.equal(binding.curriculumVersion, value.curriculumVersion);
    }
  }
  assert.equal(academic.policy.gwaPolicy.scaleId, 'mock_5point');
  assert.equal(academic.policy.gwaPolicy.attemptInclusion, 'EXPLICIT');
  assert.equal(value.catalog.approved, false);
  assert.equal(value.catalog.approvalStatus, 'DEMONSTRATION_ONLY');
  assert.equal(Object.hasOwn(value.catalog, 'familyPolicies'), false);
  assert.deepEqual(Object.keys(value.catalog.sharingLessonKeys[value.contextId]).sort(), value.candidates.map(row => `${row.moduleId}:v1:lesson_1`).sort());
  for (const row of value.candidates) assert.equal(value.catalog.sharingLessonKeys[value.contextId][`${row.moduleId}:v1:lesson_1`], row.summary.packageSha256);
});
