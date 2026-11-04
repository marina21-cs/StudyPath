import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { modules } from './content.mjs';
import { modulePdf } from './pdf.mjs';
import { syntheticLessonFixture } from '../interactive/synthetic-lesson-fixture.mjs';
import { preparePublication, publishIntoCatalog } from '../catalog/publication.mjs';

export const MOCK_CONTEXT = 'mock_computing_v1';
export const MOCK_LEVEL = 'mock_college';
export const MOCK_NOTICE = 'SYNTHETIC LOCAL DEMO ONLY. No real institution, reviewer approval, learner data or course grades.';
const reviewedOn = '2026-10-30', reviewedAt = Date.parse('2026-10-30T00:00:00Z');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const overrideKey = (category, assessment) => 'override_' + sha([category, assessment].map(value => `${value.length}:${value}`).join('')).slice(0, 48);

function course(module, index) {
  return { courseId: module.courseId, title: module.title, units: 3, topicQuery: module.keywords[0], moduleId: module.id,
    prerequisitesKnown: true, ruleKind: index === 0 ? 'NONE' : 'ALL_OF',
    prerequisiteExpression: { schemaVersion: 1, expression: index === 0 ? { kind: 'NONE' } :
      { kind: 'COURSE', courseId: modules[index - 1].courseId } },
    gradePlan: { version: 'mock_linear_v1', assessmentPlanKnown: true, verified: true,
      label: 'MOCK ONLY: 40% pooled quizzes + 60% equal exams; no actual syllabus approval',
      overrideKeys: Object.fromEntries([['quizzes', 'quiz_1'], ['quizzes', 'quiz_2'], ['exams', 'midterm'], ['exams', 'final']].map(([a, b]) => [overrideKey(a, b), true])),
      categories: {
        quizzes: { weight: 0.4, aggregation: 'POOLED_POINTS', assessments: {
          quiz_1: { title: 'MOCK Quiz 1', possiblePoints: 20 }, quiz_2: { title: 'MOCK Quiz 2', possiblePoints: 20 } } },
        exams: { weight: 0.6, aggregation: 'EQUAL_ITEMS', assessments: {
          midterm: { title: 'MOCK Midterm', possiblePoints: 100 }, final: { title: 'MOCK Final', possiblePoints: 100 } } },
      } } };
}

function packageInput(module, assetOrigin) {
  // Start from the existing verified fixture contract, then replace all teaching and identities with original mock content.
  const fixture = syntheticLessonFixture(), template = JSON.parse(fixture.catalog.modulePackages.fixture_v1.fixture_module.v1.manifestJson);
  const questions = [
    { id: 'guided', format: 'selected_response', ...module.guided },
    { id: 'ordering', format: 'ordering', ...module.ordering },
    { id: 'independent', format: 'bounded_response', ...module.independent },
    ...module.reviews.map((review, index) => ({ id: `review_${index + 1}`, format: 'bounded_response', ...review })),
  ];
  const steps = [
    { id: 'goal', kind: 'READING', title: 'Goal and explanation', body: module.introduction },
    { id: 'example', kind: 'EXAMPLE', title: 'Worked example', body: module.example },
    { id: 'guided', kind: 'CHECK', title: 'Guided check', body: 'Apply the explanation, then inspect the corrective feedback.', questionId: 'guided' },
    { id: 'bridge', kind: 'READING', title: 'Explain the rule', body: module.bridge },
    { id: 'ordering', kind: 'CHECK', title: 'Trace the order', body: 'Arrange the steps using the labeled movement controls.', questionId: 'ordering' },
    { id: 'independent', kind: 'CHECK', title: 'Independent application', body: 'Use the rule in this new small example.', questionId: 'independent' },
    { id: 'summary', kind: 'READING', title: 'Summary and later review', body: module.summary },
  ];
  const payload = { schemaVersion: 1, moduleId: module.id, version: 1,
    lessons: [{ id: 'lesson_1', title: module.title, steps }], questions, glossary: module.glossary };
  const text = Buffer.from(JSON.stringify(payload)), pdf = modulePdf(module);
  const file = `${module.id}-v1.pdf`, url = `${assetOrigin}/studypath/${file}`;
  const review = { status: 'APPROVED', reviewer: 'MOCK FIXTURE - NO HUMAN REVIEW', reviewedOn,
    approvalReference: `MOCK_ONLY_${module.id}_v1`, permissionReference: 'MOCK_ORIGINAL_TEXT_NO_REAL_RIGHTS_REVIEW',
    adaptationAllowed: true, redistributionAllowed: true };
  const manifest = { ...template, moduleId: module.id, title: module.title,
    applicability: { curriculumVersion: MOCK_CONTEXT, levelId: MOCK_LEVEL, subjectId: 'computing', classIds: [] },
    source: { title: `${module.title} - original synthetic teaching`, reference: `mock://studypath/${module.id}/v1`,
      locator: 'Original mock lesson and complete companion PDF, version 1', author: 'StudyPath synthetic fixture author', version: 'mock-1' },
    review, objectives: [{ id: 'objective_1', description: module.objective }],
    concepts: [{ id: module.concept, keywords: module.keywords }],
    lessons: [{ id: 'lesson_1', objectiveIds: ['objective_1'], stepIds: steps.map(step => step.id),
      questionIds: ['guided', 'ordering', 'independent'], reviewQuestionIds: ['review_1', 'review_2'], conceptIds: [module.concept] }],
    questions: questions.map(question => ({ id: question.id, objectiveId: 'objective_1', conceptIds: [module.concept], format: question.format })),
    text: { file: 'module.json', bytes: text.length, sha256: sha(text) },
    assets: [{ id: 'companion_pdf', file, kind: 'PDF', bytes: pdf.length, sha256: sha(pdf), url }],
    assetPolicy: { reference: 'MOCK_PUBLIC_ASSET_BUDGET_V1', approvalReference: review.approvalReference,
      reviewer: review.reviewer, reviewedOn, access: 'PUBLIC_PREPUBLISHED', origins: [assetOrigin],
      maxCount: 1, maxFileBytes: 1_048_576, maxTotalBytes: 1_048_576, mimeTypes: ['application/pdf'] } };
  const candidate = { manifestBytes: Buffer.from(JSON.stringify(manifest)), files: new Map([['module.json', text], [file, pdf]]),
    publishedIndex: { schemaVersion: 1, modules: {} }, assetBudgetBytes: 1_048_576 };
  const evidence = Buffer.from(`${MOCK_NOTICE}\nOriginal authored mock module: ${module.id}, version 1.\nAll approval/rights fields are fabricated fixture flags, not signatures.\n`);
  const approval = { schemaVersion: 1, mode: 'SYNTHETIC_LOCAL_ONLY', curriculumVersion: MOCK_CONTEXT,
    levelId: MOCK_LEVEL, subjectId: 'computing', institution: 'MOCK INSTITUTION - NONE REAL', campus: 'MOCK LOCAL',
    program: 'MOCK COMPUTING', cohort: 'MOCK DEMO', effectiveYear: '2026',
    policyReferences: ['MOCK_ONLY_ACADEMIC_POLICY_V1'], reviewer: review.reviewer, reviewedOn,
    approvalReference: review.approvalReference, permissionReference: review.permissionReference,
    manifestSha256: sha(candidate.manifestBytes), contentAccuracyReviewed: true, lessonSemanticsReviewed: true,
    applicabilityReviewed: true, adaptationAllowed: true, redistributionAllowed: true,
    evidence: [{ file: 'mock-evidence.txt', sha256: sha(evidence) }] };
  const evidenceFiles = new Map([['mock-evidence.txt', evidence]]);
  return { moduleId: module.id, ...candidate, candidate, approval, evidenceFiles,
    publicationInput: { candidate, approval, evidenceFiles, actorId: 'mock_fixture_author', occurredAt: reviewedAt + 1 },
    asset: { id: 'companion_pdf', file, url, mime: 'application/pdf', bytes: pdf, sha256: sha(pdf) } };
}

/** Pure deterministic fixture builder. It never contacts a service or establishes real approval. */
export function createMockCurriculum({ assetOrigin = 'https://mock-assets.example.org' } = {}) {
  const candidates = modules.map(module => packageInput(module, assetOrigin));
  const courses = Object.fromEntries(modules.map((module, index) => [module.courseId, course(module, index)]));
  const options = { schemaVersion: 1, curriculumVersion: MOCK_CONTEXT, levelId: MOCK_LEVEL,
    subjectIds: { computing: true }, approved: true, approvalStatus: 'APPROVED' };
  const preferences = { schemaVersion: 1, curriculumVersion: MOCK_CONTEXT, levelId: MOCK_LEVEL,
    subjectIds: { computing: true }, onboardingComplete: true };
  const refs = Object.fromEntries(['SCORES', 'SCENARIOS', 'GWA', 'PREREQUISITES'].map(capability => [capability, `MOCK_ONLY_${capability}_V1`]));
  const policy = { schemaVersion: 1, curriculumVersion: MOCK_CONTEXT, levelId: MOCK_LEVEL,
    educationSector: 'HIGHER_EDUCATION', subjectIds: { computing: true }, approvalStatus: 'APPROVED',
    approvalRef: 'MOCK_ONLY_ACADEMIC_POLICY_V1', reviewer: 'MOCK FIXTURE - NO HUMAN REVIEW', reviewedAt,
    sourceRef: 'MOCK_ORIGINAL_POLICY_NOT_A_SYLLABUS', contextRef: MOCK_CONTEXT, revision: 1,
    validFrom: 1, validUntil: Date.parse('2030-01-01T00:00:00Z'),
    capabilities: { SCORES: true, SCENARIOS: true, GWA: true, PREREQUISITES: true }, capabilityApprovalRefs: refs,
    gwaPolicy: { scaleId: 'mock_5point', minimumGrade: 1, maximumGrade: 5, attemptInclusion: 'EXPLICIT',
      retakePolicyRef: 'MOCK_EXPLICIT_RETAKE_CHOICE', exclusionPolicyRef: 'MOCK_EXPLICIT_INCLUDE_EXCLUDE',
      roundingMode: 'HALF_UP', roundingScale: 2 },
    coursePlans: Object.fromEntries(Object.keys(courses).map(id => [id, { subjectId: 'computing', planVersion: 'mock_linear_v1' }])) };
  let catalog = { schemaVersion: 1, curriculumVersion: MOCK_CONTEXT, title: 'MOCK Computing Learning Lab',
    approved: false, approvalStatus: 'DEMONSTRATION_ONLY', notice: MOCK_NOTICE, courses,
    learnerPreferenceOptions: { [MOCK_CONTEXT]: options }, academicPolicies: { [MOCK_CONTEXT]: policy } };
  for (const row of candidates) {
    const prepared = preparePublication(row.publicationInput);
    catalog = publishIntoCatalog(catalog, prepared).catalog;
    row.summary = prepared.summary; row.transport = prepared.payload;
  }
  // Only authored payload lessons are trusted; parser-generated reviews do not create new identities.
  catalog.sharingLessonKeys = { [MOCK_CONTEXT]: Object.fromEntries(candidates.flatMap(row =>
    JSON.parse(row.transport.textJson).lessons.map(lesson => [`${row.moduleId}:v1:${lesson.id}`, row.summary.packageSha256]))) };
  const binding = (courseId, capability = 'SCORES') => {
    if (!Object.hasOwn(courses, courseId) || !Object.hasOwn(refs, capability)) throw new Error('Unknown mock course or capability.');
    return { curriculumVersion: MOCK_CONTEXT, policyRevision: 1, policyRef: refs[capability], planVersion: courses[courseId].gradePlan.version };
  };
  return { syntheticOnly: true, contextId: MOCK_CONTEXT, curriculumVersion: MOCK_CONTEXT, levelId: MOCK_LEVEL,
    notice: MOCK_NOTICE, catalog, candidates, assets: candidates.map(row => row.asset),
    academic: { preferences, options, policy }, binding,
    expected: { moduleIds: modules.map(module => module.id), courseIds: modules.map(module => module.courseId),
      originalContent: true, approvedForRealLearners: false, independentExternalWorkedCaseCount: 0 } };
}

/** Explicit local artifact export only. Import/serving/authentication belong to the caller's isolated harness. */
export async function writeMockCurriculum(directory, options) {
  const value = createMockCurriculum(options); await mkdir(directory, { recursive: true });
  await writeFile(join(directory, 'catalog.json'), JSON.stringify(value.catalog, null, 2) + '\n');
  await writeFile(join(directory, 'preferences.json'), JSON.stringify(value.academic.preferences, null, 2) + '\n');
  for (const row of value.candidates) {
    const candidateDir = join(directory, row.moduleId, 'candidate'), approvalDir = join(directory, row.moduleId, 'approval');
    await mkdir(candidateDir, { recursive: true }); await mkdir(approvalDir, { recursive: true });
    await writeFile(join(candidateDir, 'manifest.json'), row.manifestBytes);
    for (const [file, bytes] of row.files) await writeFile(join(candidateDir, file), bytes);
    await writeFile(join(approvalDir, 'approval.json'), JSON.stringify(row.approval, null, 2) + '\n');
    for (const [file, bytes] of row.evidenceFiles) await writeFile(join(approvalDir, file), bytes);
  }
  return value;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv[2] !== '--output' || !process.argv[3] || process.argv.length > 5) {
    console.error('Usage: node backend/mock/curriculum.mjs --output LOCAL_DIRECTORY [PUBLIC_HTTPS_ASSET_ORIGIN]'); process.exitCode = 2;
  } else {
    const value = await writeMockCurriculum(process.argv[3], process.argv[4] ? { assetOrigin: process.argv[4] } : undefined);
    console.log(JSON.stringify({ syntheticOnly: true, serviceWrites: false, modules: value.expected.moduleIds,
      outputDirectory: process.argv[3], packageSha256: Object.fromEntries(value.candidates.map(row => [row.moduleId, row.summary.packageSha256])) }, null, 2));
  }
}
