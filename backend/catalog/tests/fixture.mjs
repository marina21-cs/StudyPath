import { createHash } from 'node:crypto';
export const hash = bytes => createHash('sha256').update(bytes).digest('hex');
// Every review/source claim below is fabricated test data, never a production approval.
export const fixture = () => {
  const content = Buffer.from(JSON.stringify({ moduleId: 'fixture_module', version: 1,
    teaching: 'Original synthetic test text. Two equal parts plus one equal part make three.' }));
  return { manifest: {
    schemaVersion: 1, moduleId: 'fixture_module', version: 1, title: 'Synthetic test fixture',
    applicability: { curriculumVersion: 'fixture_v1', levelId: 'fixture_level', subjectId: 'fixture_subject', classIds: ['fixture_class'] },
    source: { title: 'Synthetic test source', reference: 'https://example.invalid/test', locator: 'Test only', author: 'Test author', version: 'fixture_v1' },
    review: { status: 'APPROVED', reviewer: 'TEST ONLY', reviewedOn: '2026-10-26', approvalReference: 'TEST ONLY',
      permissionReference: 'TEST ONLY', adaptationAllowed: true, redistributionAllowed: true },
    objectives: [{ id: 'obj_1', description: 'Recognize equal parts' }],
    concepts: [{ id: 'parts', keywords: ['equal parts'] }],
    lessons: [{ id: 'lesson_1', objectiveIds: ['obj_1'], stepIds: ['step_1'], questionIds: ['q_1'], reviewQuestionIds: ['q_review'], conceptIds: ['parts'] }],
    questions: [{ id: 'q_1', objectiveId: 'obj_1', conceptIds: ['parts'], format: 'selected_response' },
      { id: 'q_review', objectiveId: 'obj_1', conceptIds: ['parts'], format: 'bounded_response' }],
    text: { file: 'module.json', bytes: content.length, sha256: hash(content) }, assets: [],
  }, files: new Map([['module.json', content]]), publishedIndex: { schemaVersion: 1, modules: {} } };
};

export function addReviewedPdf(value) {
  const bytes = Buffer.from('%PDF-1.7\nSYNTHETIC TEST FILE - not a display-quality document\n%%EOF\n');
  value.manifest.assets = [{ id: 'pdf_1', file: 'source.pdf', kind: 'PDF', bytes: bytes.length,
    sha256: hash(bytes), url: 'https://materials.example.org/modules/source.pdf' }];
  value.manifest.assetPolicy = { reference: 'TEST ONLY asset policy', approvalReference: 'TEST ONLY',
    reviewer: 'TEST ONLY', reviewedOn: '2026-10-26', access: 'PUBLIC_PREPUBLISHED',
    origins: ['https://materials.example.org'], maxCount: 1, maxFileBytes: 1000, maxTotalBytes: 1000,
    mimeTypes: ['application/pdf'] };
  value.files.set('source.pdf', bytes); value.assetBudgetBytes = 1000;
  return value;
}
