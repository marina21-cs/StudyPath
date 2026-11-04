import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { hash } from './fixture.mjs';
import { runtimeCandidate } from './runtime-fixture.mjs';
import { preparePublication, publishIntoCatalog } from '../publication.mjs';

async function disk(t) {
  const root = await mkdtemp(join(tmpdir(), 'studypath-publication-cli-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const candidateDir = join(root, 'candidate'), approvalDir = join(root, 'approval');
  await mkdir(candidateDir); await mkdir(approvalDir);
  const value = runtimeCandidate(); value.manifest.applicability.classIds = [];
  const manifestBytes = Buffer.from(JSON.stringify(value.manifest));
  await writeFile(join(candidateDir, 'manifest.json'), manifestBytes);
  await writeFile(join(candidateDir, 'module.json'), value.files.get('module.json'));
  const proof = Buffer.from('Synthetic test evidence only');
  await writeFile(join(approvalDir, 'review.txt'), proof);
  const approval = { schemaVersion: 1, mode: 'SYNTHETIC_LOCAL_ONLY', curriculumVersion: 'fixture_v1',
    levelId: 'fixture_level', subjectId: 'fixture_subject', institution: 'Synthetic institution',
    campus: 'Synthetic campus', program: 'Synthetic program', cohort: 'test', effectiveYear: '2026',
    policyReferences: ['TEST_ONLY_POLICY'], reviewer: 'TEST ONLY', reviewedOn: '2026-10-26',
    approvalReference: 'TEST ONLY', permissionReference: 'TEST ONLY', manifestSha256: hash(manifestBytes),
    contentAccuracyReviewed: true, lessonSemanticsReviewed: true, applicabilityReviewed: true,
    adaptationAllowed: true, redistributionAllowed: true, evidence: [{ file: 'review.txt', sha256: hash(proof) }] };
  await writeFile(join(approvalDir, 'approval.json'), JSON.stringify(approval));
  const snapshot = join(root, 'catalog.json'); await writeFile(snapshot, '{}');
  const prepared = preparePublication({ candidate: { manifestBytes, files: value.files, publishedIndex: value.publishedIndex },
    approval, evidenceFiles: new Map([['review.txt', proof]]), actorId: 'local_maintainer', occurredAt: 1793318400000 });
  const base = ['publish', candidateDir, approvalDir, '--actor', 'local_maintainer', '--at', '1793318400000',
    '--catalog', snapshot, '--allow-synthetic'];
  return { root, snapshot, base, candidateDir, approvalDir, prepared };
}
const run = args => {
  const result = spawnSync(process.execPath, [new URL('../../scripts/publish-catalog.mjs', import.meta.url).pathname, ...args],
    { encoding: 'utf8', env: { ...process.env, FIREBASE_DATABASE_EMULATOR_HOST: '' } });
  return { exit: result.status, message: JSON.parse((result.stdout || result.stderr).trim()) };
};

test('CLI defaults to dry-run, reports no publication and leaves the snapshot byte-identical', async t => {
  const value = await disk(t); const before = await readFile(value.snapshot);
  const result = run(value.base);
  assert.equal(result.exit, 0); assert.equal(result.message.dryRun, true);
  assert.equal(result.message.publicationPerformed, false); assert.equal(result.message.cloudPublicationAllowed, false);
  assert.deepEqual(await readFile(value.snapshot), before);
});

test('CLI blocks apply to file snapshots, missing synthetic opt-in and duplicate/unknown flags', async t => {
  const value = await disk(t);
  for (const [args, code] of [[value.base.concat('--apply'), 'LOCAL_SNAPSHOT_READ_ONLY'],
    [value.base.slice(0, -1), 'SYNTHETIC_OPT_IN_REQUIRED'],
    [value.base.concat('--force'), 'USAGE'], [value.base.concat('--actor', 'other'), 'USAGE']]) {
    const result = run(args);
    assert.notEqual(result.exit, 0); assert.equal(result.message.code, code);
  }
});

test('CLI rejects incomplete review records and unexpected evidence files without printing their contents', async t => {
  const value = await disk(t);
  await writeFile(join(value.approvalDir, 'unexpected.txt'), 'PRIVATE_TEST_SENTINEL');
  const result = run(value.base);
  assert.equal(result.exit, 1); assert.equal(result.message.code, 'PUBLICATION_APPROVAL_REQUIRED');
  assert.equal(JSON.stringify(result).includes('PRIVATE_TEST_SENTINEL'), false);
});

test('CLI rejects metadata-only teaching before publication without leaking lesson content', async t => {
  const value = await disk(t), before = await readFile(value.snapshot);
  const manifestFile = join(value.candidateDir, 'manifest.json');
  const manifest = JSON.parse(await readFile(manifestFile, 'utf8'));
  const content = Buffer.from(JSON.stringify({ moduleId: manifest.moduleId, version: manifest.version,
    teaching: 'PRIVATE_TEST_SENTINEL' }));
  manifest.text.bytes = content.length; manifest.text.sha256 = hash(content);
  const manifestBytes = Buffer.from(JSON.stringify(manifest));
  await writeFile(manifestFile, manifestBytes); await writeFile(join(value.candidateDir, manifest.text.file), content);
  const approvalFile = join(value.approvalDir, 'approval.json');
  const approval = JSON.parse(await readFile(approvalFile, 'utf8'));
  approval.manifestSha256 = hash(manifestBytes); await writeFile(approvalFile, JSON.stringify(approval));
  const result = run(value.base);
  assert.equal(result.exit, 1); assert.equal(result.message.code, 'LESSON_RUNTIME_INVALID');
  assert.equal(JSON.stringify(result).includes('PRIVATE_TEST_SENTINEL'), false);
  assert.deepEqual(await readFile(value.snapshot), before);
});

test('withdraw CLI validates an exact retained version without mutating a local snapshot', async t => {
  const value = await disk(t);
  await writeFile(value.snapshot, JSON.stringify(publishIntoCatalog({}, value.prepared).catalog));
  const before = await readFile(value.snapshot);
  const requestFile = join(value.root, 'withdrawal.json');
  await writeFile(requestFile, JSON.stringify({ curriculumVersion: 'fixture_v1', moduleId: 'fixture_module', version: 1,
    actorId: 'local_maintainer', occurredAt: 1793318400001, reason: 'Test withdrawal', reference: 'TEST ONLY',
    retention: 'RETAIN_EXISTING_PRIVATE_COPIES' }));
  const result = run(['withdraw', requestFile, '--catalog', value.snapshot]);
  assert.equal(result.exit, 0); assert.equal(result.message.outcome, 'WITHDRAWN'); assert.equal(result.message.dryRun, true);
  assert.deepEqual(await readFile(value.snapshot), before);
});
