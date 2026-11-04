import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { preparePublication, publishIntoCatalog } from '../publication.mjs';
import { publicationInput, runtimeCorpus } from './runtime-fixture.mjs';

const corpus = JSON.parse(await readFile(new URL('../../../android/test-fixtures/catalog/interactive-runtime-contract.json', import.meta.url), 'utf8'));
test('shared raw-byte runtime corpus stays reproducible from existing synthetic lessons', () => {
  assert.deepEqual(corpus, runtimeCorpus());
});
for (const row of corpus.cases) test(`publication runtime contract: ${row.id}`, () => {
  const manifest = JSON.parse(row.manifestJson);
  const input = publicationInput({ manifestBytes: Buffer.from(row.manifestJson),
    files: new Map([[manifest.text.file, Buffer.from(row.textJson)]]), publishedIndex: { schemaVersion: 1, modules: {} } });
  if (row.accepted) {
    const prepared = preparePublication(input);
    assert.equal(publishIntoCatalog({}, prepared).outcome, 'PUBLISHED');
    assert.equal(prepared.payload.textJson, row.textJson);
    assert.equal(prepared.summary.packageSha256, row.summary.packageSha256);
  } else assert.throws(() => preparePublication(input), undefined, `Reject ${row.id} before publication`);
});
