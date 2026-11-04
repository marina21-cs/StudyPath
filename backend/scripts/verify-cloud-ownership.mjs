#!/usr/bin/env node
// Explicit opt-in cloud probe. --check never invokes Firebase or the network.
import { createHash, randomBytes } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const PROJECT = 'studypath-c8086';
const DATABASE = 'https://studypath-c8086-default-rtdb.asia-southeast1.firebasedatabase.app';
const INSTANCE = 'studypath-c8086-default-rtdb';
const APP_ID = '1:550109956308:android:86e487add353c486c3d341';
const PACKAGE = 'com.studypath.app';
const CONFIG = join(ROOT, '.build/firebase-cloud/studypath-c8086.properties');
const RULES = join(ROOT, 'backend/database.rules.json');
const CLI = join(ROOT, 'backend/node_modules/firebase-tools/lib/bin/firebase.js');
const fail = code => { throw new ProbeError(code); };
class ProbeError extends Error { constructor(code) { super(code); this.code = code; } }
const hash = value => createHash('sha256').update(value).digest('hex');
export const canonical = value => JSON.stringify(sort(value));
function sort(value) {
  if (Array.isArray(value)) return value.map(sort);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, sort(value[k])]));
  return value;
}

export function parseArgs(args) {
  const [mode, ...rest] = args;
  if (mode === '--check' && rest.length === 0) return { mode: 'check' };
  if (mode === '--run' && rest.length === 4 && rest[0] === '--project' && rest[1] === PROJECT &&
      rest[2] === '--rules-sha256' && /^[a-f0-9]{64}$/.test(rest[3])) {
    return { mode: 'run', rulesHash: rest[3] };
  }
  fail('USAGE: --check OR --run --project studypath-c8086 --rules-sha256 <reviewed-check-hash>');
}

export function parseProperties(text) {
  const result = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim() || /^\s*[#!]/.test(line)) continue;
    const match = /^([A-Za-z][A-Za-z0-9]*)=([^\r\n]+)$/.exec(line);
    if (!match || Object.hasOwn(result, match[1])) fail('CONFIG_INVALID_OR_DUPLICATE_PROPERTY');
    result[match[1]] = match[2].trim();
  }
  const expected = ['firebaseApiKey', 'firebaseAppId', 'firebaseDatabaseUrl', 'firebaseProjectId', 'firebaseUseEmulators'];
  if (canonical(Object.keys(result).sort()) !== canonical(expected) || result.firebaseProjectId !== PROJECT ||
      result.firebaseDatabaseUrl !== DATABASE || result.firebaseAppId !== APP_ID ||
      result.firebaseUseEmulators !== 'false' || !/^AIza[A-Za-z0-9_-]{30,60}$/.test(result.firebaseApiKey)) fail('CONFIG_TARGET_OR_KEYS_INVALID');
  return result;
}

export function checkEnvironment(env) {
  // The CLI supports many URL/credential overrides. Do not inherit any of them.
  const forbidden = Object.keys(env).filter(k => /^(FIREBASE_|GOOGLE_|GCLOUD_|CLOUDSDK_)/.test(k) && env[k]);
  if (forbidden.length || env.NODE_OPTIONS || env.NODE_TLS_REJECT_UNAUTHORIZED === '0' || env.DEBUG) fail('ENVIRONMENT_OVERRIDE_PRESENT');
}

export function checkConfiguration() {
  let stat;
  try { stat = lstatSync(CONFIG); } catch { fail('PRIVATE_CONFIG_MISSING'); }
  if (!stat.isFile() || stat.isSymbolicLink() || realpathSync(CONFIG) !== CONFIG ||
      (stat.mode & 0o777) !== 0o600 || stat.uid !== process.getuid() || stat.size > 8192) fail('PRIVATE_CONFIG_MUST_BE_OWNED_REGULAR_0600_FILE');
  const ignored = spawnSync('git', ['check-ignore', '--quiet', CONFIG], { cwd: ROOT, stdio: 'ignore' });
  if (ignored.status !== 0) fail('PRIVATE_CONFIG_NOT_GIT_IGNORED');
  const config = parseProperties(readFileSync(CONFIG, 'utf8'));
  let rules;
  try { rules = JSON.parse(readFileSync(RULES, 'utf8')); } catch { fail('LOCAL_RULES_INVALID'); }
  if (rules.rules?.['.read'] !== false || rules.rules?.['.write'] !== false || rules.rules?.catalog?.['.write'] !== false ||
      !rules.rules?.users?.['$uid']?.profile) fail('LOCAL_RULES_BASELINE_INVALID');
  lstatSync(CLI);
  return { config, rulesHash: hash(canonical(rules)) };
}

function cliAccount() {
  const path = join(process.env.XDG_CONFIG_HOME || join(homedir(), '.config'), 'configstore/firebase-tools.json');
  let state;
  try { state = JSON.parse(readFileSync(path, 'utf8')); } catch { fail('FIREBASE_CLI_ACCOUNT_REQUIRED'); }
  if (typeof state.user?.email !== 'string' || !state.user.email.includes('@') || !state.tokens?.refresh_token) fail('FIREBASE_CLI_ACCOUNT_REQUIRED');
  return state.user.email;
}

function cli(args, account) {
  const child = spawnSync(process.execPath, [CLI, ...args, '--project', PROJECT, '--account', account, '--non-interactive', '--json'], {
    cwd: join(ROOT, 'backend'), encoding: 'utf8', timeout: 90_000, maxBuffer: 4 * 1024 * 1024,
    // CLI SDK-config output contains the client key: capture privately and discard debug logs.
    // DEBUG prevents firebase-tools from unlinking its log target on exit. --json
    // suppresses its console logger, so debug/config/token output stays in /dev/null.
    env: { ...process.env, DEBUG: 'true', FIREBASE_DEBUG_PATH: '/dev/null', FIREBASE_CLI_DISABLE_USAGE_ANALYTICS: 'true', CI: 'true' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (child.status !== 0 || child.error) fail('FRESH_CLI_READ_FAILED');
  const text = child.stdout;
  // firebase-tools 15 prints a final JSON envelope; database:get precedes it with raw JSON.
  const marker = /(?:^|\n)(\{\s*\n\s*"status":\s*"success")/g;
  let match, start = -1;
  while ((match = marker.exec(text))) start = match.index + (text[match.index] === '\n' ? 1 : 0);
  if (start < 0) fail('CLI_RESPONSE_NOT_RECOGNIZED');
  let envelope;
  try { envelope = JSON.parse(text.slice(start)); } catch { fail('CLI_RESPONSE_NOT_RECOGNIZED'); }
  return { result: envelope.result, prefix: text.slice(0, start).trim() };
}

async function verifyPrerequisites(config, rulesHash, expectedHash, receipt) {
  checkEnvironment(process.env);
  if (rulesHash !== expectedHash) fail('REVIEWED_RULES_HASH_MISMATCH');
  const account = cliAccount();
  const projects = cli(['projects:list'], account).result;
  if (!Array.isArray(projects) || !projects.some(p => p.projectId === PROJECT && String(p.projectNumber) === '550109956308')) fail('CLI_TARGET_PROJECT_NOT_CONFIRMED');
  const sdk = cli(['apps:sdkconfig', 'ANDROID', APP_ID], account).result;
  let app;
  try { app = JSON.parse(sdk.fileContents); } catch { fail('CLI_APP_CONFIG_NOT_CONFIRMED'); }
  const client = app.client?.find(c => c.client_info?.mobilesdk_app_id === APP_ID && c.client_info?.android_client_info?.package_name === PACKAGE);
  if (app.project_info?.project_id !== PROJECT || String(app.project_info?.project_number) !== '550109956308' ||
      app.project_info?.firebase_url !== DATABASE || !client?.api_key?.some(k => k.current_key === config.firebaseApiKey)) fail('CLI_APP_CONFIG_TARGET_MISMATCH');
  const deployed = cli(['database:get', '/.settings/rules', '--instance', INSTANCE], account);
  let remote;
  try { remote = JSON.parse(deployed.prefix); } catch { fail('DEPLOYED_RULES_NOT_READABLE'); }
  if (hash(canonical(remote)) !== rulesHash) fail('DEPLOYED_RULES_DO_NOT_MATCH_REVIEWED_LOCAL_RULES');
  receipt.prerequisites = { freshCliAccountSha256: hash(account), projectConfirmed: true, appConfigConfirmed: true, deployedRulesConfirmed: true };
}

async function request(url, method = 'GET', value) {
  let response;
  try {
    response = await fetch(url, { method, redirect: 'error', signal: AbortSignal.timeout(20_000),
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      ...(value === undefined ? {} : { body: JSON.stringify(value) }) });
    const text = await response.text();
    if (text.length > 1_048_576) fail('HTTP_RESPONSE_TOO_LARGE');
    return { status: response.status, value: text ? JSON.parse(text) : null };
  } catch (error) { if (error instanceof ProbeError) throw error; fail('HTTP_TRANSPORT_OR_JSON_FAILURE'); }
}

function auth(config, operation, body) {
  const url = new URL(`https://identitytoolkit.googleapis.com/v1/accounts:${operation}`);
  url.searchParams.set('key', config.firebaseApiKey);
  return request(url, 'POST', body);
}
function database(path, token, method = 'GET', value) {
  const url = new URL(`${DATABASE}/${path.map(encodeURIComponent).join('/')}.json`);
  if (token) url.searchParams.set('auth', token);
  return request(url, method, value);
}
function expect(result, status, code) { if (result.status !== status) fail(code); return result.value; }
function equal(actual, expected, code) { if (canonical(actual) !== canonical(expected)) fail(code); }
function denial(result, code) {
  if (![401, 403].includes(result.status) || result.value?.error !== 'Permission denied') fail(code);
}

// Payload decoding is an identity/target guard, not local JWT signature verification.
// Auth/RTDB verify the actual ID token; lookup confirms the same newly created account.
function tokenIdentity(token, intent) {
  let claims;
  try { claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')); } catch { fail('OWNED_TOKEN_IDENTITY_INVALID'); }
  if (claims.aud !== PROJECT || claims.iss !== `https://securetoken.google.com/${PROJECT}` ||
      !/^[A-Za-z0-9_-]{1,128}$/.test(claims.sub) || claims.email !== intent.email ||
      claims.firebase?.sign_in_provider !== 'password' || (intent.uid && intent.uid !== claims.sub)) fail('OWNED_TOKEN_IDENTITY_INVALID');
  return claims.sub;
}

async function identify(config, intent, token, startedAt) {
  const uid = tokenIdentity(token, intent);
  const result = await auth(config, 'lookup', { idToken: token });
  const users = expect(result, 200, 'OWNED_ACCOUNT_LOOKUP_FAILED')?.users;
  const user = users?.[0];
  if (users?.length !== 1 || user.localId !== uid || user.email !== intent.email ||
      !/^\d+$/.test(user.createdAt) || Number(user.createdAt) < startedAt - 5000 || Number(user.createdAt) > Date.now() + 5000 ||
      (intent.createdAt && user.createdAt !== intent.createdAt)) fail('OWNED_ACCOUNT_CREATION_IDENTITY_NOT_CONFIRMED');
  intent.uid = uid; intent.createdAt = user.createdAt; intent.token = token;
}

async function recoverIdentity(config, intent, startedAt) {
  const result = await auth(config, 'signInWithPassword', { email: intent.email, password: intent.password, returnSecureToken: true });
  if (result.status !== 200 || !result.value?.idToken) fail('OWNED_ACCOUNT_RECOVERY_FAILED');
  await identify(config, intent, result.value.idToken, startedAt);
}

async function createAccount(config, intent, startedAt) {
  intent.attempted = true;
  const result = await auth(config, 'signUp', { email: intent.email, password: intent.password, returnSecureToken: true });
  // An explicit collision never grants cleanup ownership of that pre-existing account.
  if (result.value?.error?.message === 'EMAIL_EXISTS') { intent.collision = true; fail('SYNTHETIC_EMAIL_COLLISION'); }
  if (result.status >= 400 && result.status < 500) {
    intent.attempted = false; // A definite rejection created no account to clean.
    fail('SYNTHETIC_ACCOUNT_CREATION_REJECTED');
  }
  const value = expect(result, 200, 'SYNTHETIC_ACCOUNT_CREATION_FAILED');
  if (!value?.idToken || typeof value.localId !== 'string') fail('SYNTHETIC_ACCOUNT_RESPONSE_INVALID');
  await identify(config, intent, value.idToken, startedAt);
  if (intent.uid !== value.localId) fail('SYNTHETIC_ACCOUNT_UID_MISMATCH');
  const tree = await database(['users', intent.uid], intent.token);
  equal(expect(tree, 200, 'NEW_UID_EMPTY_READ_FAILED'), null, 'NEW_UID_ALREADY_HAS_DATA');
}

async function exercise(config, intents, receipt) {
  const [owner, other] = intents;
  if (owner.uid === other.uid) fail('DISTINCT_SYNTHETIC_UIDS_REQUIRED');
  for (const intent of intents) {
    const path = ['users', intent.uid, 'profile'];
    const first = intent.profiles[0], changed = intent.profiles[1];
    equal(expect(await database(path, intent.token, 'PUT', first), 200, 'OWNER_CREATE_DENIED'), first, 'OWNER_CREATE_ACK_MISMATCH');
    equal(expect(await database(path, intent.token), 200, 'OWNER_READ_DENIED'), first, 'OWNER_READ_MISMATCH');
    expect(await database(path, intent.token, 'PATCH', { displayName: changed.displayName }), 200, 'OWNER_UPDATE_DENIED');
    equal(expect(await database(path, intent.token), 200, 'OWNER_UPDATE_READ_DENIED'), changed, 'OWNER_UPDATE_MISMATCH');
    expect(await database(path, intent.token, 'DELETE'), 200, 'OWNER_DELETE_DENIED');
    equal(expect(await database(path, intent.token), 200, 'OWNER_DELETE_READ_DENIED'), null, 'OWNER_DELETE_NOT_CONFIRMED');
    expect(await database(path, intent.token, 'PUT', first), 200, 'OWNER_RECREATE_DENIED');
    receipt.checks.push({ check: `${intent.role}:owner-create-read-update-delete`, passed: true });
  }
  for (const [target, foreign] of [[owner, other], [other, owner]]) {
    const path = ['users', target.uid, 'profile'];
    for (const [label, token] of [['cross-uid', foreign.token], ['unauthenticated', undefined]]) {
      denial(await database(path, token), `${label.toUpperCase()}_READ_NOT_DENIED`);
      denial(await database(path, token, 'PUT', target.profiles[1]), `${label.toUpperCase()}_WRITE_NOT_DENIED`);
      denial(await database(path, token, 'DELETE'), `${label.toUpperCase()}_DELETE_NOT_DENIED`);
      equal(expect(await database(path, target.token), 200, 'OWNER_POST_DENIAL_READ_FAILED'), target.profiles[0], 'DENIAL_PROBE_CHANGED_OWNER_PROFILE');
      receipt.checks.push({ check: `${target.role}:${label}-read-write-delete-denied`, passed: true });
    }
  }
  // Fresh absent path + null body: even an unexpected success cannot seed catalog data.
  const catalogPath = ['catalog', 'cloudOwnershipProbes', receipt.runId];
  equal(expect(await database(catalogPath, owner.token), 200, 'CATALOG_PROBE_ABSENCE_NOT_READABLE'), null, 'CATALOG_PROBE_PATH_EXISTS');
  for (const intent of intents) denial(await database(catalogPath, intent.token, 'PUT', null), 'CATALOG_NULL_WRITE_NOT_DENIED');
  receipt.checks.push({ check: 'catalog-null-write-denied-for-both-users', passed: true });
}

async function cleanup(config, intent, startedAt) {
  if (!intent.attempted || intent.collision) return { role: intent.role, status: 'not-owned-no-delete' };
  // Refresh recent authentication and recheck exact UID/email/creation identity before deletion.
  await recoverIdentity(config, intent, startedAt);
  const tree = expect(await database(['users', intent.uid], intent.token), 200, 'CLEANUP_OWNER_TREE_UNREADABLE');
  if (tree !== null && (canonical(Object.keys(tree)) !== canonical(['profile']) ||
      !intent.profiles.some(p => canonical(p) === canonical(tree.profile)))) fail('CLEANUP_PROFILE_IDENTITY_CHANGED_NO_DELETE');
  expect(await database(['users', intent.uid, 'profile'], intent.token, 'DELETE'), 200, 'CLEANUP_PROFILE_DELETE_FAILED');
  equal(expect(await database(['users', intent.uid], intent.token), 200, 'CLEANUP_PROFILE_VERIFY_FAILED'), null, 'CLEANUP_UID_NOT_EMPTY_NO_AUTH_DELETE');
  const deleted = await auth(config, 'delete', { idToken: intent.token });
  expect(deleted, 200, 'CLEANUP_AUTH_DELETE_FAILED');
  const lookup = await auth(config, 'lookup', { idToken: intent.token });
  if (!((lookup.status === 200 && (!lookup.value.users || lookup.value.users.length === 0)) ||
      (lookup.status === 400 && lookup.value?.error?.message === 'USER_NOT_FOUND'))) fail('CLEANUP_AUTH_DELETE_NOT_CONFIRMED');
  return { role: intent.role, uidSha256: hash(intent.uid), status: 'profile-and-auth-deleted' };
}

export async function main(args) {
  const options = parseArgs(args);
  checkEnvironment(process.env);
  const { config, rulesHash } = checkConfiguration();
  if (options.mode === 'check') {
    console.log(JSON.stringify({ mode: 'check', project: PROJECT, databaseUrl: DATABASE, privateConfig: 'owned-0600-ignored',
      emulators: false, reviewedRulesSha256: rulesHash, networkRequests: 0, cloudWrites: 0,
      runPrerequisites: ['fresh Firebase CLI account', 'remote project/app configuration match', 'deployed reviewed rules hash match'],
      nativeCloudAcceptance: 'open' }, null, 2));
    return;
  }
  const startedAt = Date.now();
  const receipt = { mode: 'run', project: PROJECT, databaseUrl: DATABASE, runId: randomBytes(16).toString('hex'),
    startedAt: new Date(startedAt).toISOString(), reviewedRulesSha256: rulesHash, checks: [], cleanup: [], nativeCloudAcceptance: 'open' };
  const intents = ['owner', 'control'].map(role => ({ role, email: `studypath-cloud-${receipt.runId}-${role}@studypath.invalid`,
    password: `V1!${randomBytes(32).toString('base64url')}`, profiles: [0, 1].map(n => ({
      displayName: `Cloud probe ${receipt.runId} ${role} ${n}`, selectedCurriculumVersion: 'cloud_probe_v1',
    })) }));
  let failure;
  try {
    await verifyPrerequisites(config, rulesHash, options.rulesHash, receipt);
    for (const intent of intents) await createAccount(config, intent, startedAt);
    await exercise(config, intents, receipt);
  } catch (error) { failure = error instanceof ProbeError ? error.code : 'UNEXPECTED_FAILURE_DETAILS_REDACTED'; }
  finally {
    for (const intent of intents) {
      try { receipt.cleanup.push(await cleanup(config, intent, startedAt)); }
      catch (error) {
        receipt.cleanup.push({ role: intent.role, ...(intent.uid ? { uidSha256: hash(intent.uid) } : {}), status: 'FAILED_REQUIRES_EXACT_RUN_ACCOUNT_REVIEW',
          reason: error instanceof ProbeError ? error.code : 'CLEANUP_FAILURE_DETAILS_REDACTED' });
        failure ||= 'CLEANUP_INCOMPLETE';
      }
      intent.token = undefined; intent.password = undefined;
    }
  }
  receipt.finishedAt = new Date().toISOString(); receipt.result = failure ? 'failed' : 'passed';
  if (failure) receipt.failure = failure;
  console.log(JSON.stringify(receipt, null, 2));
  if (failure) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => {
    console.error(JSON.stringify({ result: 'refused', reason: error instanceof ProbeError ? error.code : 'LOCAL_CHECK_FAILED_DETAILS_REDACTED', cloudWrites: 0 }));
    process.exitCode = 1;
  });
}
