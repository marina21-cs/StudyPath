import { readFile } from 'node:fs/promises';
const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
if (host !== '127.0.0.1:9000') {
  throw new Error('Set FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000. This script only seeds the local demonstration emulator.');
}
const catalog = JSON.parse(await readFile(new URL('../seed/catalog.json', import.meta.url), 'utf8'));
if (catalog.approved !== false || catalog.approvalStatus !== 'DEMONSTRATION_ONLY') throw new Error('The local seed must remain explicitly unapproved demonstration content.');
const response = await fetch('http://127.0.0.1:9000/catalog.json?ns=demo-studypath-default-rtdb', {
  method: 'PUT', headers: { 'content-type': 'application/json', Authorization: 'Bearer owner' }, body: JSON.stringify(catalog)
});
if (!response.ok) throw new Error(`Local seed failed: HTTP ${response.status}`);
console.log('Seeded catalog/ in the local demo-studypath-default-rtdb emulator only.');
