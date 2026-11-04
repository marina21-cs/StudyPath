import { spawn } from 'node:child_process';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Each existing integration test has one owning stage. Names are exact so that
// a renamed, removed or newly unassigned test cannot silently weaken a gate.
const stages = {
  foundation: [
    'catalog seed script is limited to local emulator and produces unapproved demo catalog',
    'authenticated users can read published catalog; no client can write it',
    'unrecognized top-level and publication paths are denied'
  ],
  auth: [
    'email/password registration and login issue a token accepted only for the owning database subtree',
    'unauthenticated user cannot read or write private data',
    'another UID cannot read, write, update or delete owner data',
    'private collection listing and root writes are denied'
  ],
  records: [
    'owner can store and retrieve every documented record shape, including a scored zero',
    'unknown user collections and unknown record fields are rejected',
    'record collections cannot be replaced by scalar values',
    'missing required values, wrong types, oversized values and invalid IDs are rejected',
    'owners can delete each record and their entire private subtree',
    'required child removal is rejected and multi-location writes remain atomic'
  ],
  readings: [
    'accepted saved reading snapshots cannot be edited but exact retries succeed',
    'live Wikipedia English deed metadata saves unchanged and permits only exact retries',
    'saved readings reject unsafe license schemes, domains and unapproved paths'
  ],
  study: [
    'accepted practice attempts are immutable, retry-safe and bounded',
    'progress preserves module versions and rejects mismatched version keys'
  ],
  academics: [
    'score ranges enforce nonnegative earnedPoints <= possiblePoints and positive possiblePoints',
    'course outcome and scenario percentages reject out-of-range values',
    'UNGRADED and EXCUSED scores omit earnedPoints; a scored zero stays explicit',
    'scenarios validate hypothetical overrides and keep an explicit base fingerprint',
    'course completion remains self-reported and cannot attach results to an in-progress attempt',
    'scores require a stable plan version and cannot silently drop it on updates',
    'GWA inclusion is explicit and requires a completed numeric grade with a scale'
  ]
};

const backendDirectory = fileURLToPath(new URL('../', import.meta.url));
const testsDirectory = new URL('../tests/', import.meta.url);
const files = (await readdir(testsDirectory)).filter(name => name.endsWith('.test.mjs')).sort();
const inventory = [];
for (const file of files) {
  const source = await readFile(new URL(file, testsDirectory), 'utf8');
  for (const match of source.matchAll(/\btest\(\s*(['"])(.*?)\1\s*,/g)) {
    inventory.push({ name: match[2], file: `tests/${file}` });
  }
}

const selectedNames = Object.values(stages).flat();
for (const name of new Set([...selectedNames, ...inventory.map(test => test.name)])) {
  const assigned = selectedNames.filter(selected => selected === name).length;
  const declared = inventory.filter(test => test.name === name).length;
  if (assigned !== 1 || declared !== 1) {
    throw new Error(`Stage coverage mismatch for ${JSON.stringify(name)}: ${assigned} assignments, ${declared} declarations.`);
  }
}

const [stage, ...extra] = process.argv.slice(2);
if (stage === '--list' && extra.length === 0) {
  console.log(JSON.stringify(Object.fromEntries(Object.entries(stages).map(([name, tests]) => [name, {
    expectedTests: tests.length,
    files: [...new Set(inventory.filter(test => tests.includes(test.name)).map(test => test.file))],
    tests
  }])), null, 2));
} else {
  if (!Object.hasOwn(stages, stage) || extra.length !== 0) {
    throw new Error(`Usage: node scripts/test-stage.mjs <${Object.keys(stages).join('|')}> or --list`);
  }
  const names = stages[stage];
  const startedAt = new Date().toISOString();
  const receiptDirectory = new URL('../test-results/stages/', import.meta.url);
  const receiptFile = new URL(`${stage}.json`, receiptDirectory);
  const receipt = { stage, expectedTests: names.length, selectedCount: names.length, passed: 0, failed: 0, skipped: 0, status: 'running', startedAt };
  await mkdir(receiptDirectory, { recursive: true });
  await writeFile(receiptFile, `${JSON.stringify(receipt, null, 2)}\n`);
  const selectedFiles = [...new Set(inventory.filter(test => names.includes(test.name)).map(test => test.file))];
  const escapePattern = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = `^(?:${names.map(escapePattern).join('|')})$`;
  console.log(`Backend stage: ${stage}; expecting ${names.length} existing integration tests.`);
  const child = spawn(process.execPath, [
    '--test', '--test-concurrency=1', '--test-reporter=tap',
    `--test-name-pattern=${pattern}`, ...selectedFiles
  ], { cwd: backendDirectory, stdio: ['inherit', 'pipe', 'inherit'] });
  let output = '';
  child.stdout.on('data', chunk => {
    output += chunk.toString();
    process.stdout.write(chunk);
  });
  const exitCode = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (code, signal) => resolve(signal ? 1 : code));
  });
  const passes = Number(output.match(/^# pass (\d+)\s*$/m)?.[1] ?? -1);
  const failures = Number(output.match(/^# fail (\d+)\s*$/m)?.[1] ?? -1);
  const skipped = Number(output.match(/^# skipped (\d+)\s*$/m)?.[1] ?? -1);
  const successful = exitCode === 0 && passes === names.length && failures === 0 && skipped === 0;
  await writeFile(receiptFile, `${JSON.stringify({
    ...receipt, passed: passes, failed: failures, skipped,
    status: successful ? 'passed' : 'failed', exitCode, finishedAt: new Date().toISOString()
  }, null, 2)}\n`);
  if (!successful) {
    console.error(`FAIL ${stage}: expected ${names.length} passing tests; observed ${passes} passes and ${failures} failures (exit ${exitCode}).`);
    process.exitCode = 1;
  } else {
    console.log(`PASS ${stage}: ${passes}/${names.length} tests passed.`);
  }
}
