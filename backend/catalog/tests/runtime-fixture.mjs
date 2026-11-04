import { pathToFileURL } from 'node:url';
import { syntheticLessonFixture } from '../../interactive/synthetic-lesson-fixture.mjs';
import { hash } from './fixture.mjs';
import { summarizeCandidate } from '../summarize-candidate.mjs';

// Fabricated local-only lessons and review evidence; never production content or approval.
export function runtimeCandidate() {
  const fixture = syntheticLessonFixture();
  const transport = fixture.catalog.modulePackages.fixture_v1.fixture_module.v1;
  const manifest = JSON.parse(transport.manifestJson);
  manifest.applicability.classIds = [];
  return { manifest, manifestBytes: Buffer.from(JSON.stringify(manifest)),
    files: new Map([[manifest.text.file, Buffer.from(transport.textJson)]]),
    publishedIndex: { schemaVersion: 1, modules: {} } };
}

export function publicationInput(candidate = runtimeCandidate()) {
  const manifest = JSON.parse(candidate.manifestBytes);
  const evidence = Buffer.from('Synthetic review evidence only');
  return { candidate, actorId: 'local_maintainer', occurredAt: 1793318400000,
    approval: { schemaVersion: 1, mode: 'SYNTHETIC_LOCAL_ONLY',
      curriculumVersion: manifest.applicability.curriculumVersion, levelId: manifest.applicability.levelId,
      subjectId: manifest.applicability.subjectId, institution: 'Synthetic institution', campus: 'Synthetic campus',
      program: 'Synthetic program', cohort: 'test', effectiveYear: '2026',
      policyReferences: ['TEST_ONLY_POLICY'], reviewer: manifest.review.reviewer,
      reviewedOn: manifest.review.reviewedOn, approvalReference: manifest.review.approvalReference,
      permissionReference: manifest.review.permissionReference, manifestSha256: hash(candidate.manifestBytes),
      contentAccuracyReviewed: true, lessonSemanticsReviewed: true, applicabilityReviewed: true,
      adaptationAllowed: true, redistributionAllowed: true,
      evidence: [{ file: 'review.txt', sha256: hash(evidence) }] },
    evidenceFiles: new Map([['review.txt', evidence]]) };
}

export function runtimeCorpus() {
  const base = runtimeCandidate(), baseline = summarizeCandidate(base);
  const originalText = base.files.get('module.json').toString();
  const cases = [];
  const add = (id, accepted, mutate = () => {}, raw = null) => {
    const manifest = structuredClone(base.manifest), payload = JSON.parse(originalText);
    mutate(payload, manifest);
    const textJson = raw ?? JSON.stringify(payload);
    manifest.text.bytes = Buffer.byteLength(textJson); manifest.text.sha256 = hash(Buffer.from(textJson));
    const manifestJson = JSON.stringify(manifest);
    const summary = { ...baseline, textBytes: Buffer.byteLength(manifestJson) + Buffer.byteLength(textJson),
      packageSha256: hash(JSON.stringify({ manifestSha256: hash(Buffer.from(manifestJson)),
        textSha256: hash(Buffer.from(textJson)), assets: [] })) };
    cases.push({ id, accepted, summary, manifestJson, textJson });
  };
  add('all_three_formats_and_short_review', true);
  add('unicode_and_numeric_boundary', true, p => {
    p.lessons[0].steps[0].body = 'Unicode 界 🧠';
    p.questions[2].target = -1e9; p.questions[2].tolerance = 1e6;
  });
  add('integral_decimal_and_exponent_identity', true, () => {},
    originalText.replace('"schemaVersion":1', '"schemaVersion":1.0').replace('"version":1', '"version":0.1e1'));
  add('metadata_only_payload', false, () => {}, '{"moduleId":"fixture_module","version":1,"teaching":"Synthetic metadata only"}');
  add('missing_glossary', false, p => { delete p.glossary; });
  add('unknown_payload_field', false, p => { p.extra = true; });
  add('wrong_schema_type', false, p => { p.schemaVersion = '1'; });
  add('unknown_step_kind', false, p => { p.lessons[0].steps[0].kind = 'VIDEO'; });
  add('missing_worked_example', false, p => { p.lessons[0].steps[1].kind = 'READING'; });
  add('dangling_check', false, p => { p.lessons[0].steps[2].questionId = 'missing'; });
  add('duplicate_step_id', false, p => { p.lessons[0].steps[1].id = 'goal'; });
  add('inventory_order_mismatch', false, p => { p.lessons[0].steps.reverse(); });
  add('missing_option_feedback', false, p => { delete p.questions[0].options[0].feedback; });
  add('unknown_correct_option', false, p => { p.questions[0].answer = ['missing']; });
  add('duplicate_question', false, p => { p.questions.push(p.questions[0]); });
  add('format_mismatch', false, p => { p.questions[0].format = 'ordering'; p.questions[0].answer = ['a', 'b']; });
  add('incomplete_ordering_answer', false, p => { p.questions[1].answer = ['first']; });
  add('negative_tolerance', false, p => { p.questions[2].tolerance = -1; });
  add('numeric_target_out_of_bounds', false, p => { p.questions[2].target = 1e9 + 1; });
  add('non_boolean_case_sensitivity', false, p => { p.questions[3].caseSensitive = 'false'; });
  add('normalized_duplicate_short_answers', false, p => { p.questions[3].acceptedAnswers = ['a', ' A ']; });
  add('unsupported_validator', false, p => { p.questions[3].responseType = 'regex'; });
  add('duplicate_glossary_term', false, p => { p.glossary.push(p.glossary[0]); });
  add('overlong_objective', false, (_p, m) => { m.objectives[0].description = 'a'.repeat(501); });
  add('overlong_step_body', false, p => { p.lessons[0].steps[0].body = 'a'.repeat(8001); });
  add('invalid_question_key', false, p => { p.questions[0].id = 'bad key'; });
  add('numeric_overflow', false, () => {}, originalText.replace('"target":3', '"target":1e999'));
  add('fractional_schema_hidden_by_js_rounding', false, () => {}, originalText.replace('"schemaVersion":1', '"schemaVersion":1.0000000000000001'));
  add('fractional_version_hidden_by_js_rounding', false, () => {}, originalText.replace('"version":1', '"version":1.0000000000000001'));
  add('duplicate_json_member', false, () => {}, originalText.replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1'));
  add('escaped_duplicate_json_member', false, () => {}, originalText.replace('"schemaVersion":1', '"schemaVersion":1,"schema\\u0056ersion":1'));
  add('unpaired_unicode_escape', false, () => {}, originalText.replace('Practice synthetic equal parts', 'Unpaired \\uD800'));
  add('overdeep_json', false, () => {}, originalText.replace('"glossary":[', '"extra":' + '['.repeat(64) + '0' + ']'.repeat(64) + ',"glossary":['));
  add('trailing_json', false, () => {}, originalText + '{}');
  const duplicateManifest = structuredClone(cases[0]);
  duplicateManifest.id = 'duplicate_manifest_member'; duplicateManifest.accepted = false;
  duplicateManifest.manifestJson = duplicateManifest.manifestJson.replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1');
  duplicateManifest.summary.textBytes = Buffer.byteLength(duplicateManifest.manifestJson) + Buffer.byteLength(duplicateManifest.textJson);
  duplicateManifest.summary.packageSha256 = hash(JSON.stringify({ manifestSha256: hash(Buffer.from(duplicateManifest.manifestJson)),
    textSha256: hash(Buffer.from(duplicateManifest.textJson)), assets: [] }));
  cases.push(duplicateManifest);
  return { schemaVersion: 1, warning: 'FABRICATED TEST DATA ONLY', cases };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.stdout.write(JSON.stringify(runtimeCorpus(), null, 2) + '\n');
}
