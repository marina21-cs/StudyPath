#!/usr/bin/env node
// Explicit opt-in rules compile check. --check never touches the network. --run sends one
// `PUT /.settings/rules.json?dryRun=true` to the chosen instance: the server compiles the
// ruleset and reports errors without releasing it. Active rules are never changed here.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { canonical, checkEnvironment } from './verify-cloud-ownership.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const PROJECT = 'studypath-c8086';
const PROJECT_NUMBER = '550109956308';
const INSTANCE = 'studypath-c8086-default-rtdb';
const DATABASE = 'https://studypath-c8086-default-rtdb.asia-southeast1.firebasedatabase.app';
const RULES = join(ROOT, 'backend/database.rules.json');
const SOURCE_LIMIT = 256_000;
const require = createRequire(import.meta.url);
class ProbeError extends Error { constructor(code, detail) { super(code); this.code = code; this.detail = detail; } }
const fail = (code, detail) => { throw new ProbeError(code, detail); };

export function parseArgs(args) {
  if (args.length === 1 && args[0] === '--check') return { mode: 'check' };
  if (args.length === 3 && args[0] === '--run' && args[1] === '--project' && args[2] === PROJECT) return { mode: 'run' };
  fail('USAGE: --check OR --run --project studypath-c8086');
}

export function inspectRules(source) {
  let rules;
  try { rules = JSON.parse(source); } catch { fail('LOCAL_RULES_INVALID'); }
  if (rules.rules?.['.read'] !== false || rules.rules?.['.write'] !== false || rules.rules?.catalog?.['.write'] !== false ||
      !rules.rules?.users?.['$uid']?.profile) fail('LOCAL_RULES_BASELINE_INVALID');
  const sourceBytes = Buffer.byteLength(source, 'utf8');
  if (sourceBytes > SOURCE_LIMIT) fail('LOCAL_RULES_SOURCE_OVER_LIMIT');
  let longestExpression = 0;
  const walk = node => { for (const value of Object.values(node)) {
    if (typeof value === 'string') longestExpression = Math.max(longestExpression, value.length);
    else if (value && typeof value === 'object') walk(value);
  } };
  walk(rules.rules);
  return { sourceBytes, sourceLimit: SOURCE_LIMIT, longestExpression,
    canonicalSha256: createHash('sha256').update(canonical(rules)).digest('hex') };
}

function cliTooling() {
  const version = require(join(ROOT, 'backend/node_modules/firebase-tools/package.json')).version;
  if (!/^15\./.test(version)) fail('FIREBASE_TOOLS_MAJOR_UNSUPPORTED');
  const lib = name => require(join(ROOT, 'backend/node_modules/firebase-tools/lib', name));
  return { version, auth: lib('auth.js'), requireAuth: lib('requireAuth.js').requireAuth,
    database: lib('management/database.js'), rtdb: lib('rtdb.js') };
}

async function dryRun(source, receipt) {
  checkEnvironment(process.env);
  const tooling = cliTooling();
  const account = tooling.auth.selectAccount(undefined, join(ROOT, 'backend'));
  if (!account?.user?.email || !account.tokens?.refresh_token) fail('FIREBASE_CLI_ACCOUNT_REQUIRED');
  const options = { project: PROJECT, projectId: PROJECT, instance: INSTANCE, user: account.user, tokens: account.tokens, nonInteractive: true };
  await tooling.requireAuth(options);
  receipt.cliAccountSha256 = createHash('sha256').update(account.user.email).digest('hex');
  await tooling.database.populateInstanceDetails(options);
  const details = options.instanceDetails;
  // The management API reports the owning project by number (`projects/<number>`).
  const owner = String(details?.project ?? '').replace(/^projects\//, '');
  if (details?.name !== INSTANCE || details.databaseUrl !== DATABASE || ![PROJECT, PROJECT_NUMBER].includes(owner)) fail('CLOUD_INSTANCE_TARGET_MISMATCH');
  receipt.instanceConfirmed = true;
  try { await tooling.rtdb.updateRules(PROJECT, INSTANCE, source, { dryRun: true }); }
  catch (error) { fail('CLOUD_RULES_COMPILE_REJECTED', String(error?.message ?? error).slice(0, 2000)); }
  receipt.compileAccepted = true;
}

export async function main(args) {
  const options = parseArgs(args);
  const source = readFileSync(RULES, 'utf8');
  const local = inspectRules(source);
  const receipt = { mode: options.mode, project: PROJECT, instance: INSTANCE, databaseUrl: DATABASE, ...local,
    dryRun: true, released: false, cloudWrites: 0 };
  if (options.mode === 'check') { console.log(JSON.stringify({ ...receipt, networkRequests: 0 }, null, 2)); return; }
  receipt.startedAt = new Date().toISOString();
  try { await dryRun(source, receipt); receipt.result = 'passed'; }
  catch (error) {
    receipt.result = 'failed';
    receipt.failure = error instanceof ProbeError ? error.code : 'UNEXPECTED_FAILURE_DETAILS_REDACTED';
    if (error instanceof ProbeError && error.detail) receipt.detail = error.detail;
    process.exitCode = 1;
  }
  receipt.finishedAt = new Date().toISOString();
  console.log(JSON.stringify(receipt, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => {
    console.error(JSON.stringify({ result: 'refused', reason: error instanceof ProbeError ? error.code : 'LOCAL_CHECK_FAILED_DETAILS_REDACTED', cloudWrites: 0 }));
    process.exitCode = 1;
  });
}
