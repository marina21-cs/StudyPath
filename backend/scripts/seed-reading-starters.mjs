import { isDeepStrictEqual } from 'node:util';
import { validateReadingStarters, planReadingStarterSeed, assertLocalEmulatorHost } from '../materials/reading-starters.mjs';
import { readReadingStarterBytes } from '../materials/read-reading-starters.mjs';

// Fixed emulator namespace: this is a local supplemental catalog, never an approved lesson publication tool.
const base = assertLocalEmulatorHost(process.env.FIREBASE_DATABASE_EMULATOR_HOST);
if (process.argv.length !== 3) throw new Error('Usage: node scripts/seed-reading-starters.mjs PATH_TO_READING_STARTERS_JSON');
const records = validateReadingStarters(await readReadingStarterBytes(process.argv[2]));
const endpoint = `${base}/catalog/readingStarters`;
const suffix = '.json?ns=demo-studypath-default-rtdb';

async function request(url, method = 'GET', body) {
  const response = await fetch(url, {
    method, redirect: 'error', signal: AbortSignal.timeout(12_000),
    headers: { Authorization: 'Bearer owner', 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  if (!response.ok) throw new Error(`READING_STARTER_LOCAL_${method}_FAILED: HTTP ${response.status}`);
  return response.json();
}

const existingById = {};
for (const id of Object.keys(records)) existingById[id] = await request(`${endpoint}/${id}${suffix}`);
const plan = planReadingStarterSeed(records, existingById);
if (Object.keys(plan.newEntries).length > 0) await request(`${endpoint}${suffix}`, 'PATCH', plan.newEntries);
for (const [id, expected] of Object.entries(records)) {
  if (!isDeepStrictEqual(await request(`${endpoint}/${id}${suffix}`), expected)) throw new Error(`READING_STARTER_READBACK_FAILED:${id}`);
}
console.log(JSON.stringify({
  scope: 'LOCAL_SUPPLEMENTAL_READING_STARTERS', namespace: 'demo-studypath-default-rtdb',
  inserted: Object.keys(plan.newEntries).length, reused: plan.reusedIds.length, verified: plan.total,
  curriculumApprovalEstablished: false,
  concurrency: 'Preflight plus granular PATCH; concurrent admin publication is not transactional.'
}, null, 2));
