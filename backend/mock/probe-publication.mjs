import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { seedMockEmulator, publishMockCurriculum, mockTarget } from './seed-emulator.mjs';
const [namespace, host, receiptFile] = process.argv.slice(2);
if (!namespace || !host || !receiptFile || process.argv.length !== 5) throw new Error('Usage: node backend/mock/probe-publication.mjs NAMESPACE 127.0.0.1:<9000|19000> RECEIPT');
const origin = mockTarget({ namespace, host }), requests = [];
const fetchImpl = async (url, options = {}) => {
  const response = await fetch(url, options);
  requests.push({ method: options.method ?? 'GET', path: new URL(url).pathname, status: response.status,
    etagRequested: options.headers?.['X-Firebase-ETag'] === 'true', compareAndSet: typeof options.headers?.['If-Match'] === 'string' });
  return response;
};
const rootUrl = `${origin}/.json?ns=${namespace}`;
const receipt = { syntheticOnly: true, localOnly: true, namespace, host, status: 'FAILED', acceptancePromoted: false,
  command: [process.execPath, ...process.argv.slice(1)], startedAtUtc: new Date().toISOString() };
let owned = false;
try {
  const initial = await fetch(rootUrl, { headers: { Authorization: 'Bearer owner' } });
  if (!initial.ok || await initial.json() !== null) throw new Error('An unused isolated namespace is required.');
  owned = true;
  receipt.seed = (await seedMockEmulator({ namespace, host, fetchImpl })).receipt;
  const published = await publishMockCurriculum({ namespace, host, fetchImpl }); receipt.first = published.receipt;
  receipt.repeated = (await publishMockCurriculum({ namespace, host, fetchImpl })).receipt;
  const catalogResponse = await fetch(`${origin}/catalog.json?ns=${namespace}`, { headers: { Authorization: 'Bearer owner' } });
  if (!catalogResponse.ok) throw new Error('Independent catalog read failed.');
  const catalog = await catalogResponse.json();
  receipt.catalogSha256 = createHash('sha256').update(JSON.stringify(catalog)).digest('hex');
  receipt.moduleIds = Object.keys(catalog.modulePackages.mock_computing_v1).sort();
  receipt.historyModes = Object.values(catalog.modulePublicationHistory.mock_computing_v1).map(value => value.v1.evidenceMode);
  if (receipt.first.publications.some(row => !row.publicationPerformed) || receipt.repeated.publications.some(row => row.outcome !== 'UNCHANGED')) {
    throw new Error('Publication/readback/idempotence differs.');
  }
  receipt.status = 'PASSED'; receipt.exitCode = 0;
} catch (error) { receipt.failure = error.message; receipt.exitCode = 1; }
finally {
  if (owned) {
    const removed = await fetch(rootUrl, { method: 'DELETE', headers: { Authorization: 'Bearer owner' }, signal: AbortSignal.timeout(10_000) });
    const after = await fetch(rootUrl, { headers: { Authorization: 'Bearer owner' }, signal: AbortSignal.timeout(10_000) });
    receipt.ownedNamespaceCleaned = removed.ok && after.ok && await after.json() === null;
    if (!receipt.ownedNamespaceCleaned) { receipt.status = 'FAILED'; receipt.exitCode = 1; }
  }
  receipt.requests = requests; receipt.finishedAtUtc = new Date().toISOString();
  await mkdir(dirname(receiptFile), { recursive: true }); await writeFile(receiptFile, JSON.stringify(receipt, null, 2) + '\n');
}
console.log(JSON.stringify({ status: receipt.status, receiptFile, exitCode: receipt.exitCode })); process.exitCode = receipt.exitCode;
