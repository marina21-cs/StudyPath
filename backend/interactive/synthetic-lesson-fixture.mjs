import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { fixture } from '../catalog/tests/fixture.mjs';
import { summarizeCandidate } from '../catalog/summarize-candidate.mjs';
import { packageCandidate } from '../catalog/package-candidate.mjs';

// Generates bytes only. Every source/review/permission claim is fabricated test metadata.
// Use exclusively in an explicitly isolated test namespace; this never publishes or writes data.
export function syntheticLessonFixture() {
  const raw = fixture();
  const payload = {
    schemaVersion: 1, moduleId: 'fixture_module', version: 1,
    lessons: [{ id: 'lesson_1', title: 'Synthetic interactive test lesson', steps: [
      { id: 'goal', kind: 'READING', title: 'Goal', body: 'Practice synthetic equal parts using four authored test checks.' },
      { id: 'example', kind: 'EXAMPLE', title: 'Worked example', body: 'One synthetic part and two synthetic parts make three.' },
      { id: 'choice', kind: 'CHECK', title: 'Guided check', body: 'Choose a synthetic answer.', questionId: 'q_1' },
      { id: 'ordering', kind: 'CHECK', title: 'Independent ordering', body: 'Put steps in order.', questionId: 'q_order' },
      { id: 'number', kind: 'CHECK', title: 'Independent number', body: 'Count synthetic parts.', questionId: 'q_number' },
      { id: 'short', kind: 'CHECK', title: 'Independent short answer', body: 'Name a synthetic letter.', questionId: 'q_short' }
    ] }],
    questions: [
      { id: 'q_1', prompt: 'Pick A', format: 'selected_response', options: [
        { id: 'a', text: 'A', feedback: 'A follows the example.' },
        { id: 'b', text: 'B', feedback: 'Look again at the example.' }], answer: ['a'],
        hint: 'Read the example.', explanation: 'A follows this synthetic example.' },
      { id: 'q_order', prompt: 'First then second', format: 'ordering', options: [
        { id: 'first', text: 'First', feedback: 'First comes first.' },
        { id: 'second', text: 'Second', feedback: 'Second comes later.' }], answer: ['first', 'second'],
        hint: 'Begin with first.', explanation: 'First precedes second.' },
      { id: 'q_number', prompt: 'One plus two', format: 'bounded_response', responseType: 'numeric',
        unit: 'parts', target: 3, tolerance: 0.05, hint: 'Count the parts.', explanation: 'One plus two makes three.' },
      { id: 'q_short', prompt: 'Name the first letter', format: 'bounded_response', responseType: 'short',
        acceptedAnswers: ['a'], caseSensitive: false, unit: '', hint: 'The alphabet begins here.', explanation: 'A is the first letter.' },
      { id: 'q_review', prompt: 'Name the first letter again', format: 'bounded_response', responseType: 'short',
        acceptedAnswers: ['a'], caseSensitive: false, unit: '', hint: 'The alphabet begins here.', explanation: 'A is the first letter.' }
    ], glossary: [{ term: 'parts', definition: 'Parts of a synthetic fixture.' }]
  };
  const text = Buffer.from(JSON.stringify(payload));
  const manifest = { ...raw.manifest, title: 'Synthetic interactive test lesson',
    objectives: [{ id: 'obj_1', description: 'Practice synthetic equal parts' }],
    lessons: [{ id: 'lesson_1', objectiveIds: ['obj_1'], stepIds: payload.lessons[0].steps.map(step => step.id),
      questionIds: ['q_1', 'q_order', 'q_number', 'q_short'], reviewQuestionIds: ['q_review'], conceptIds: ['parts'] }],
    questions: payload.questions.map(question => ({ id: question.id, objectiveId: 'obj_1', conceptIds: ['parts'], format: question.format })),
    text: { file: 'module.json', bytes: text.length, sha256: createHash('sha256').update(text).digest('hex') }
  };
  const candidate = { ...raw, manifest, manifestBytes: Buffer.from(JSON.stringify(manifest)), files: new Map([['module.json', text]]) };
  const summary = summarizeCandidate(candidate), transport = packageCandidate(candidate);
  return { testOnly: true, warning: 'FABRICATED TEST APPROVAL METADATA. Never publish as approved learner content.',
    catalog: {
      learnerPreferenceOptions: { fixture_v1: { schemaVersion: 1, curriculumVersion: 'fixture_v1', levelId: 'fixture_level',
        subjectIds: { fixture_subject: true }, approved: true, approvalStatus: 'APPROVED' } },
      moduleIndex: { fixture_v1: { fixture_module: { v1: summary } } },
      modulePackages: { fixture_v1: { fixture_module: { v1: transport } } }
    },
    expected: { curriculumVersion: 'fixture_v1', levelId: 'fixture_level', subjectId: 'fixture_subject',
      moduleId: 'fixture_module', version: 1, lessonId: 'lesson_1', packageSha256: summary.packageSha256,
      totalSteps: 6, totalChecks: 4, stepIds: payload.lessons[0].steps.map(step => step.id),
      correctResponses: { q_1: ['a'], q_order: ['first', 'second'], q_number: '3', q_short: 'A' },
      catalogPath: 'catalog/moduleIndex/fixture_v1/fixture_module/v1', packagePath: 'catalog/modulePackages/fixture_v1/fixture_module/v1' }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.stdout.write(JSON.stringify(syntheticLessonFixture(), null, 2) + '\n');
}
