import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { LOCAL_CONFIG, assertLocalConfig } from './multiuser.mjs';

const config = LOCAL_CONFIG;
assertLocalConfig(config);
const output = resolve(process.argv[2] ?? 'implementation/phases/02-ui/20261104-backend-scale-audit/multiuser');
const report = JSON.parse(await readFile(resolve(output, 'results.json'), 'utf8'));
const sourceText = await readFile(new URL('../database.rules.json', import.meta.url), 'utf8');
const sourceRules = JSON.parse(sourceText);
const sha = text => createHash('sha256').update(text).digest('hex');
const canonical = value => value && typeof value === 'object'
  ? (Array.isArray(value) ? value.map(canonical) : Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]))) : value;
async function read(url) {
  const response = await fetch(url, { method: 'GET', redirect: 'error', headers: { Authorization: 'Bearer owner' }, signal: AbortSignal.timeout(20000) });
  assert.equal(response.status, 200, `Read-only postflight returned HTTP ${response.status}`);
  return response.json();
}
const query = `?ns=${config.namespace}`;
const [users, deployedRules, accounts] = await Promise.all([
  read(`${config.databaseOrigin}/users.json${query}`),
  read(`${config.databaseOrigin}/.settings/rules.json${query}`),
  read(`${config.authOrigin}/identitytoolkit.googleapis.com/v1/projects/${config.projectId}/accounts:batchGet?maxResults=1000`)
]);
const result = {
  checkedAt: new Date().toISOString(), projectId: config.projectId, namespace: config.namespace,
  method: 'Read-only GET; local emulator owner bypass used only to inspect test cleanup and rules',
  remainingUserSubtrees: Object.keys(users ?? {}).length,
  remainingAuthAccounts: (accounts.users ?? []).length,
  authPaginationRemaining: Boolean(accounts.nextPageToken),
  recordedCleanupCount: report.cleanup.length,
  allRecordedCleanupPassed: report.cleanup.every(item => item.databaseDeleted && item.authDeleted),
  currentSourceFileSha256: sha(sourceText),
  sourceCompactJsonSha256: sha(JSON.stringify(sourceRules)),
  preRunSourceCompactJsonSha256: report.sourceRulesSha256,
  sourceCanonicalRulesSha256: sha(JSON.stringify(canonical(sourceRules))),
  loadedCanonicalRulesSha256: sha(JSON.stringify(canonical(deployedRules))),
  loadedRulesEqualCurrentSource: JSON.stringify(canonical(sourceRules)) === JSON.stringify(canonical(deployedRules)),
  sourceUnchangedSinceRun: sha(JSON.stringify(sourceRules)) === report.sourceRulesSha256
};
await writeFile(resolve(output, 'postflight.json'), JSON.stringify(result, null, 2) + '\n');
assert.equal(result.remainingUserSubtrees, 0);
assert.equal(result.remainingAuthAccounts, 0);
assert.equal(result.authPaginationRemaining, false);
assert.equal(result.recordedCleanupCount, 187);
assert.equal(result.allRecordedCleanupPassed, true);
assert.equal(result.loadedRulesEqualCurrentSource, true);
assert.equal(result.sourceUnchangedSinceRun, true);
console.log(JSON.stringify(result, null, 2));
