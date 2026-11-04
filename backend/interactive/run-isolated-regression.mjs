import { readFile, writeFile, unlink } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

assert.equal(process.env.FIREBASE_DATABASE_EMULATOR_HOST, '127.0.0.1:19000', 'Use firebase.b11-test.json only');
// Execute unchanged legacy expectations against the separate port, never the preview services.
const suites = ['tests/database.rules.test.mjs', 'preferences/tests/preferences.rules.test.mjs',
  'catalog/rules/module-index.rules.test.mjs', 'downloads/offline-packages.rules.test.mjs'];
for (const suite of suites) {
  const original = new URL(`../${suite}`, import.meta.url);
  let code = (await readFile(original, 'utf8')).replaceAll('127.0.0.1:9000', '127.0.0.1:19000').replaceAll('port: 9000', 'port: 19000');
  code = code.replaceAll('demo-studypath-default-rtdb', 'demo-studypath-b11-legacy');
  code = code.replace("const projectId = 'demo-studypath';", "const projectId = 'demo-studypath-b11-legacy';");
  assert.equal(/(^|[^0-9])9000([^0-9]|$)/.test(code), false, 'Legacy source still references preview port');
  assert.equal(code.includes('demo-studypath-default-rtdb'), false, 'Legacy source still references preview namespace');
  code = code.replaceAll('import.meta.url', JSON.stringify(original.href));
  code = code.replace(/from (['"])(\.{1,2}\/[^'"]+)\1/g,
    (_match, quote, relative) => `from ${quote}${new URL(relative, original).href}${quote}`);
  const temporary = new URL(`.isolated-${process.pid}.test.mjs`, import.meta.url);
  try {
    await writeFile(temporary, code);
    const result = spawnSync(process.execPath, ['--test', '--test-reporter=tap', '--test-concurrency=1', fileURLToPath(temporary)],
      { stdio: 'inherit', env: process.env });
    if (result.status !== 0) process.exitCode = result.status || 1;
  } finally { await unlink(temporary); }
  if (process.exitCode) break;
}
