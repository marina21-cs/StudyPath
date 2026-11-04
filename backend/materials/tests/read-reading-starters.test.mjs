import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readReadingStarterBytes } from '../read-reading-starters.mjs';

let directory;
before(async () => { directory = await mkdtemp(join(tmpdir(), 'studypath-reading-input-')); });
after(async () => { await rm(directory, { recursive: true, force: true }); });

test('reads a small local metadata file without changing its bytes', async () => {
  const expected = Buffer.from('{"title":"学习"}');
  const file = join(directory, 'small.json'); await writeFile(file, expected);
  assert.ok((await readReadingStarterBytes(file)).equals(expected));
});

test('reads up to 99999 raw bytes', async () => {
  const expected = Buffer.alloc(99_999, 32);
  const file = join(directory, 'below-cap.json'); await writeFile(file, expected);
  assert.ok((await readReadingStarterBytes(file)).equals(expected));
});

test('rejects files at or beyond 100000 bytes', async () => {
  for (const size of [100_000, 250_000]) {
    const file = join(directory, `large-${size}.json`); await writeFile(file, Buffer.alloc(size, 32));
    await assert.rejects(readReadingStarterBytes(file), /READING_STARTER_INPUT_LIMIT/);
  }
});

test('rejects nonregular inputs and missing files', async () => {
  await assert.rejects(readReadingStarterBytes(directory), /READING_STARTER_FILE_INVALID/);
  await assert.rejects(readReadingStarterBytes(join(directory, 'missing.json')));
});

test('rejects a symbolic link even when its target is a regular file', async () => {
  const target = join(directory, 'target.json'), link = join(directory, 'link.json');
  await writeFile(target, '{}'); await symlink(target, link);
  await assert.rejects(readReadingStarterBytes(link), /READING_STARTER_FILE_INVALID/);
});
