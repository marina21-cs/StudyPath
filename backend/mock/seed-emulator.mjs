import { isDeepStrictEqual } from 'node:util';
import { pathToFileURL } from 'node:url';
import { createMockCurriculum, MOCK_CONTEXT } from './curriculum.mjs';
import { createLocalTransport } from '../catalog/local-publication-transport.mjs';
import { executeCatalogOperation, preparePublication, publishIntoCatalog } from '../catalog/publication.mjs';

export function mockTarget({ host = '127.0.0.1:9000', namespace }) {
  if (!['127.0.0.1:9000', '127.0.0.1:19000'].includes(host) || !/^demo-studypath-mock-(?:publisher-)?[a-f0-9]{8}$/.test(namespace ?? '')) {
    throw new Error('Only explicit local test database ports and an exact demo-studypath-mock-[publisher-]<8hex> namespace are allowed.');
  }
  return `http://${host}`;
}
const digestible = value => JSON.parse(JSON.stringify(value));
async function localRequest(target, path, method = 'GET', value) {
  const response = await target.fetchImpl(`${mockTarget(target)}/${path}.json?ns=${target.namespace}`, {
    method, headers: { Authorization: 'Bearer owner', 'content-type': 'application/json' },
    ...(method === 'PUT' ? { body: JSON.stringify(value) } : {}), redirect: 'error', signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`Mock local ${method} failed: HTTP ${response.status}`);
  return response.json();
}

/** Explicit empty-namespace local setup. No module index, payload or history is imported. */
export async function seedMockEmulator({ host = '127.0.0.1:9000', namespace, assetOrigin, fetchImpl = fetch }) {
  const target = { host, namespace, fetchImpl }; mockTarget(target);
  const fixture = createMockCurriculum(assetOrigin ? { assetOrigin } : undefined);
  if (await localRequest(target, '') !== null) throw new Error('Mock seeding requires an empty isolated namespace.');
  const values = [
    ...Object.entries(fixture.catalog.courses).map(([id, value]) => [`catalog/courses/${id}`, value]),
    [`catalog/learnerPreferenceOptions/${MOCK_CONTEXT}`, fixture.academic.options],
    [`catalog/academicPolicies/${MOCK_CONTEXT}`, fixture.academic.policy],
  ];
  for (const [path, value] of values) {
    await localRequest(target, path, 'PUT', value);
    if (!isDeepStrictEqual(await localRequest(target, path), digestible(value))) throw new Error('Mock academic seed readback differs.');
  }
  return { fixture, receipt: { syntheticOnly: true, namespace, localOnly: true,
    academicPaths: values.map(([path]) => path), moduleAdminImports: 0, acceptancePromoted: false } };
}

/** Uses the existing prepared publication + whole-catalog ETag/CAS adapter for every module. */
export async function publishMockCurriculum({ host = '127.0.0.1:9000', namespace, assetOrigin, fetchImpl = fetch }) {
  const target = { host, namespace, fetchImpl }; mockTarget(target);
  const fixture = createMockCurriculum(assetOrigin ? { assetOrigin } : undefined);
  const prepared = fixture.candidates.map(row => preparePublication(row.publicationInput));
  const transport = createLocalTransport(target), publications = [];
  for (const publication of prepared) {
    const outcome = await executeCatalogOperation({ transport, operation: current => publishIntoCatalog(current, publication), apply: true });
    publications.push({ moduleId: publication.history.moduleId, packageSha256: publication.summary.packageSha256, ...outcome });
  }
  const published = (await transport.read()).catalog;
  for (const row of prepared) {
    const suffix = [row.history.curriculumVersion, row.history.moduleId, 'v1'];
    const at = root => suffix.reduce((value, key) => value?.[key], root);
    if (!isDeepStrictEqual(at(published.modulePackages), row.payload) || !isDeepStrictEqual(at(published.moduleIndex), row.summary)) {
      throw new Error('Mock publication independent readback differs.');
    }
  }
  // Trusted source proof is derived only after strict publication readback, never from client-authored IDs.
  const inventoryPath = `catalog/sharingLessonKeys/${MOCK_CONTEXT}`;
  const inventory = fixture.catalog.sharingLessonKeys[MOCK_CONTEXT];
  await localRequest(target, inventoryPath, 'PUT', inventory);
  if (!isDeepStrictEqual(await localRequest(target, inventoryPath), inventory)) throw new Error('Mock lesson inventory readback differs.');
  return { fixture, receipt: { syntheticOnly: true, localOnly: true, namespace,
    publications, moduleAdminImports: 0, publicationTransport: 'existing localCatalogUrl + ETag/CAS',
    trustedLessonKeyCount: Object.keys(inventory).length, acceptancePromoted: false } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [mode, namespace] = process.argv.slice(2);
  if (!['--seed', '--publish'].includes(mode) || !namespace || process.argv.length !== 4) {
    console.error('Usage: node backend/mock/seed-emulator.mjs <--seed|--publish> demo-studypath-mock-<8hex>'); process.exitCode = 2;
  } else {
    const result = await (mode === '--seed' ? seedMockEmulator : publishMockCurriculum)({ namespace });
    console.log(JSON.stringify(result.receipt, null, 2));
  }
}
