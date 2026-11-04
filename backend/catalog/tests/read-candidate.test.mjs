import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rmdir, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fixture } from './fixture.mjs';
import { checkDirectory } from '../read-candidate.mjs';

async function diskFixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'studypath-catalog-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const directory = join(root, 'candidate'); await mkdir(directory);
  const value = fixture();
  await writeFile(join(directory, 'manifest.json'), JSON.stringify(value.manifest));
  await writeFile(join(directory, 'module.json'), value.files.get('module.json'));
  const indexFile = join(root, 'published-index.json'); await writeFile(indexFile, JSON.stringify(value.publishedIndex));
  return { root, directory, indexFile, value };
}

test('local directory preflight measures real files and leaves them unchanged', async t => {
  const v = await diskFixture(t);
  const before = await readFile(join(v.directory, 'module.json'));
  const result = await checkDirectory(v.directory, v.indexFile);
  assert.equal(result.moduleId, 'fixture_module'); assert.equal(result.scope, 'B09_METADATA_PREFLIGHT');
  assert.deepEqual(await readFile(join(v.directory, 'module.json')), before);
  assert.deepEqual(JSON.parse(await readFile(v.indexFile, 'utf8')), v.value.publishedIndex);
});

test('a symlinked content file is rejected instead of following its target', async t => {
  const v = await diskFixture(t); const content = join(v.directory, 'module.json');
  await rm(content); await writeFile(join(v.root, 'outside.json'), v.value.files.get('module.json'));
  await symlink(join(v.root, 'outside.json'), content);
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'FILE_INVALID');
});

test('a symlinked manifest or index is rejected', async t => {
  const v = await diskFixture(t); const manifest = join(v.directory, 'manifest.json');
  await rm(manifest); await writeFile(join(v.root, 'outside.json'), JSON.stringify(v.value.manifest));
  await symlink(join(v.root, 'outside.json'), manifest);
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'FILE_INVALID');
  await rm(manifest); await writeFile(manifest, JSON.stringify(v.value.manifest));
  await rm(v.indexFile); await symlink(manifest, v.indexFile);
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'FILE_INVALID');
});

test('unexpected directories, missing content and undeclared files fail closed', async t => {
  const v = await diskFixture(t); await mkdir(join(v.directory, 'nested'));
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'FILE_INVALID');
  await rmdir(join(v.directory, 'nested')); await writeFile(join(v.directory, 'extra.json'), '{}');
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'FILE_INVALID');
  await rm(join(v.directory, 'extra.json')); await rm(join(v.directory, 'module.json'));
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'FILE_INVALID');
});

test('oversized files and invalid UTF-8 in history fail before validation', async t => {
  const v = await diskFixture(t);
  await writeFile(join(v.directory, 'module.json'), 'x'.repeat(100000));
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'TEXT_LIMIT');
  await writeFile(join(v.directory, 'module.json'), v.value.files.get('module.json'));
  await writeFile(v.indexFile, Buffer.from([0xff]));
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'INDEX_INVALID');
});

test('PDF bytes require a supplied budget before loading; invalid budgets fail', async t => {
  const v = await diskFixture(t); await writeFile(join(v.directory, 'extra.pdf'), '%PDF-1.7');
  await assert.rejects(checkDirectory(v.directory, v.indexFile), error => error.code === 'ASSET_INVALID');
  await assert.rejects(checkDirectory(v.directory, v.indexFile, -1), error => error.code === 'ASSET_INVALID');
  await assert.rejects(checkDirectory(v.directory, v.indexFile, Infinity), error => error.code === 'ASSET_INVALID');
});
