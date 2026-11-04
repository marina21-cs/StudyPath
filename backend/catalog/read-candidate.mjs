import { constants } from 'node:fs';
import { open, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { CandidateError, TEXT_LIMIT_BYTES, validateCandidate } from './validate-candidate.mjs';

export async function readBounded(path, limit, limitCode) {
  let handle;
  try {
    // Refuse symlinks at open, including a replacement after directory enumeration.
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const stat = await handle.stat();
    if (!stat.isFile()) throw new CandidateError('FILE_INVALID');
    if (stat.size > limit) throw new CandidateError(limitCode);
    const chunks = []; let total = 0;
    while (true) {
      const chunk = Buffer.alloc(Math.min(65536, limit - total + 1));
      const { bytesRead } = await handle.read(chunk, 0, chunk.length, null);
      if (!bytesRead) break;
      total += bytesRead;
      if (total > limit) throw new CandidateError(limitCode);
      chunks.push(chunk.subarray(0, bytesRead));
    }
    return Buffer.concat(chunks, total);
  } catch (error) {
    if (error instanceof CandidateError) throw error;
    throw new CandidateError('FILE_INVALID');
  } finally { await handle?.close(); }
}

/** Reads a flat, bounded local candidate directory without network or database writes. */
export async function readCandidateDirectory(directory, assetBudgetBytes = 0) {
  if (!Number.isSafeInteger(assetBudgetBytes) || assetBudgetBytes < 0) throw new CandidateError('ASSET_INVALID');
  let entries;
  try { entries = await readdir(directory, { withFileTypes: true }); }
  catch { throw new CandidateError('FILE_INVALID'); }
  if (entries.length < 2 || entries.length > 22 || entries.some(entry => !entry.isFile() ||
    !/^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}$/.test(entry.name))) throw new CandidateError('FILE_INVALID');
  const names = entries.map(entry => entry.name);
  const contentNames = names.filter(name => name !== 'manifest.json' && name.endsWith('.json'));
  if (!names.includes('manifest.json') || contentNames.length !== 1 ||
    names.some(name => name !== 'manifest.json' && !name.endsWith('.json') && !name.endsWith('.pdf'))) {
    throw new CandidateError('FILE_INVALID');
  }
  const manifestBytes = await readBounded(join(directory, 'manifest.json'), TEXT_LIMIT_BYTES - 1, 'TEXT_LIMIT');
  const files = new Map();
  files.set(contentNames[0], await readBounded(join(directory, contentNames[0]),
    TEXT_LIMIT_BYTES - manifestBytes.length - 1, 'TEXT_LIMIT'));
  let remaining = assetBudgetBytes;
  for (const name of names.filter(name => name.endsWith('.pdf')).sort()) {
    const bytes = await readBounded(join(directory, name), remaining, 'ASSET_INVALID');
    remaining -= bytes.length; files.set(name, bytes);
  }
  return { manifestBytes, files, assetBudgetBytes, publishedIndex: { schemaVersion: 1, modules: {} } };
}

/** Retains the original metadata preflight CLI contract. */
export async function checkDirectory(directory, indexFile, assetBudgetBytes = 0) {
  const candidate = await readCandidateDirectory(directory, assetBudgetBytes);
  const indexBytes = await readBounded(indexFile, 1_000_000, 'INDEX_INVALID');
  let publishedIndex;
  try { publishedIndex = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(indexBytes)); }
  catch { throw new CandidateError('INDEX_INVALID'); }
  return validateCandidate({ ...candidate, publishedIndex });
}
