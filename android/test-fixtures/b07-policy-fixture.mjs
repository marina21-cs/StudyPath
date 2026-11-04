import { readFile } from 'node:fs/promises';
import { academicBinding, academicOptions, academicPolicy, academicPreferences, overrideKey } from '../../backend/academics/policy-fixture.mjs';

// Copies the legacy content only into an isolated disposable test namespace.
// All approval fields are synthetic; the demonstration seed itself stays unchanged.
const catalog = JSON.parse(await readFile(new URL('../../backend/seed/catalog.json', import.meta.url), 'utf8'));
// RTDB REST returns contiguous integer-keyed sections as JSON arrays.
for (const module of Object.values(catalog.modules)) {
  const keys = Object.keys(module.sections);
  if (keys.some((key, index) => key !== String(index))) throw new Error('Expected contiguous section indices.');
  module.sections = keys.map(key => module.sections[key]);
}
const course = catalog.courses.it101;
course.gradePlan.verified = true;
course.gradePlan.label = 'MOCK TEST ONLY reviewed plan; no real syllabus approval';
course.gradePlan.overrideKeys = Object.fromEntries(Object.entries(course.gradePlan.categories).flatMap(([category, value]) =>
  Object.keys(value.assessments).map(assessment => [overrideKey(category, assessment), true])));
// Native policy timestamps are positive; leave the shared rules fixture unchanged.
catalog.academicPolicies = { test_curriculum_v1: { ...academicPolicy(), validFrom: 1 } };
catalog.learnerPreferenceOptions = { test_curriculum_v1: academicOptions() };
process.stdout.write(JSON.stringify({ mockOnly: true, evidenceMode: 'SYNTHETIC_LOCAL_ONLY',
  realApprovalEstablished: false, catalog, preferences: academicPreferences(), scoreBinding: academicBinding() }) + '\n');
