import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const LOCAL_CONFIG = Object.freeze({
  projectId: 'demo-studypath-scale', namespace: 'demo-studypath-scale-default-rtdb',
  authOrigin: 'http://127.0.0.1:9199', databaseOrigin: 'http://127.0.0.1:9100'
});

export function assertLocalConfig(config) {
  for (const [key, value] of Object.entries(LOCAL_CONFIG)) {
    if (config[key] !== value) throw new Error(`Requires dedicated local emulator ${key}`);
  }
}

export async function mapLimit(values, concurrency, action) {
  assert.ok(Number.isInteger(concurrency) && concurrency >= 1 && concurrency <= 100, 'Invalid concurrency');
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, values.length) }, async () => {
    while (next < values.length) await action(values[next++]);
  }));
}

export function percentile(values, quantile) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return Math.round(sorted[Math.max(0, Math.ceil(quantile * sorted.length) - 1)] * 100) / 100;
}

export function summarizeSamples(samples) {
  const result = {};
  for (const phase of [...new Set(samples.map(sample => sample.phase))]) {
    const group = samples.filter(sample => sample.phase === phase);
    const failures = {};
    for (const sample of group.filter(sample => !sample.ok)) failures[sample.code] = (failures[sample.code] ?? 0) + 1;
    result[phase] = { count: group.length, passed: group.filter(sample => sample.ok).length,
      failed: group.filter(sample => !sample.ok).length, p50Ms: percentile(group.map(sample => sample.ms), .5),
      p95Ms: percentile(group.map(sample => sample.ms), .95), failures };
  }
  return result;
}

export function makeFixtures(prefix, time) {
  const reading = { pageId: 123, pageKey: 'Algorithm', title: 'Algorithm',
    excerpt: 'An algorithm is a finite sequence of instructions.',
    sourceUrl: 'https://en.wikipedia.org/wiki/Algorithm', revisionId: 456,
    revisionUrl: 'https://en.wikipedia.org/w/index.php?oldid=456', revisionTimestamp: '2026-10-22T00:00:00Z',
    licenseTitle: 'CC BY-SA 4.0', licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
    modificationNotice: 'Synthetic local test excerpt; formatting removed.', attribution: 'Wikipedia contributors',
    savedAt: time, retrievedAt: time };
  return { profile: { displayName: 'Synthetic scale learner', selectedCurriculumVersion: 'unselected_scale',
    learnerPreferences: { schemaVersion: 1, onboardingComplete: false }, createdAt: time, updatedAt: time },
  attemptId: `${prefix}_attempt`, attempt: { moduleId: 'scale_fixture', version: 1, correct: 2, total: 3, submittedAt: time },
  readingId: `${prefix}_reading`, reading,
  annotation: { snapshotRevisionId: reading.revisionId, excerptLength: reading.excerpt.length,
    note: 'Synthetic local note', position: 7, editId: `${prefix}_edit`, modifiedAt: time } };
}

export function assertDenied(response) {
  assert.ok([401, 403].includes(response.status) && /permission denied/i.test(String(response.data?.error)),
    `Expected permission denial; received HTTP ${response.status}`);
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');

async function request(url, method = 'GET', body, headers = {}) {
  const response = await fetch(url, { method, redirect: 'error', headers: { 'Content-Type': 'application/json', ...headers },
    ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
    signal: AbortSignal.timeout(20000) });
  const text = await response.text();
  return { status: response.status, data: text ? JSON.parse(text) : null };
}

function requireSuccess(response) {
  if (response.status < 200 || response.status >= 300) {
    const detail = typeof response.data?.error === 'object' ? response.data.error.message : response.data?.error;
    const error = new Error(`HTTP ${response.status}: ${String(detail ?? 'request failed').slice(0, 160)}`);
    error.code = `HTTP_${response.status}`;
    throw error;
  }
  return response.data;
}

function endpoints(config) {
  assertLocalConfig(config);
  return {
    auth: action => `${config.authOrigin}/identitytoolkit.googleapis.com/v1/accounts:${action}?key=${config.projectId}`,
    refresh: `${config.authOrigin}/securetoken.googleapis.com/v1/token?key=${config.projectId}`,
    db: (path, token) => {
      const url = new URL(`${config.databaseOrigin}/${path}.json`);
      url.searchParams.set('ns', config.namespace);
      if (token) url.searchParams.set('auth', token);
      return url.toString();
    }
  };
}

function parseOptions(args) {
  const options = { levels: [1, 10, 25, 50, 100], concurrency: 10, holdMs: 1500,
    output: resolve('implementation/phases/02-ui/20261104-backend-scale-audit/multiuser') };
  for (let index = 0; index < args.length; index += 2) {
    const [key, value] = args.slice(index, index + 2);
    if (!value) throw new Error(`Missing value for ${key}`);
    if (key === '--levels') options.levels = value.split(',').map(Number);
    else if (key === '--concurrency') options.concurrency = Number(value);
    else if (key === '--hold-ms') options.holdMs = Number(value);
    else if (key === '--out') options.output = resolve(value);
    else throw new Error(`Unknown option ${key}`);
  }
  assert.ok(options.levels.length && options.levels.every(n => Number.isInteger(n) && n >= 1 && n <= 100), 'Levels must be 1–100');
  assert.ok(Number.isInteger(options.concurrency) && options.concurrency >= 1 && options.concurrency <= 20, 'Concurrency must be 1–20');
  assert.ok(options.holdMs >= 500 && options.holdMs <= 10000, 'Hold must be 500–10000 ms');
  return options;
}

async function persistentConnections(users, options, measure, config) {
  const { initializeApp, deleteApp } = await import('firebase/app');
  const { getAuth, connectAuthEmulator, signInWithEmailAndPassword, signOut } = await import('firebase/auth');
  const { getDatabase, connectDatabaseEmulator, ref, onValue, get, goOffline } = await import('firebase/database');
  const contexts = [], events = [];
  let connected = 0, peak = 0;
  const started = performance.now();
  try {
    await mapLimit(users, options.concurrency, user => measure('sdk_connect_and_owned_read', user, async () => {
      const app = initializeApp({ apiKey: config.projectId, projectId: config.projectId,
        authDomain: 'scale-local.invalid', databaseURL: `${config.databaseOrigin}?ns=${config.namespace}` }, user.label);
      const auth = getAuth(app);
      connectAuthEmulator(auth, config.authOrigin, { disableWarnings: true });
      const db = getDatabase(app);
      connectDatabaseEmulator(db, '127.0.0.1', 9100);
      const context = { app, auth, db, connected: false };
      contexts.push(context);
      const credentials = await signInWithEmailAndPassword(auth, user.email, user.password);
      assert.equal(credentials.user.uid, user.uid);
      await new Promise((resolveConnected, reject) => {
        const timeout = setTimeout(() => reject(new Error('RTDB connection timeout')), 20000);
        context.unsubscribe = onValue(ref(db, '.info/connected'), snapshot => {
          const current = snapshot.val() === true;
          if (current !== context.connected) {
            connected += current ? 1 : -1;
            context.connected = current;
            peak = Math.max(peak, connected);
            events.push({ ms: Math.round(performance.now() - started), connected });
          }
          if (current) { clearTimeout(timeout); resolveConnected(); }
        }, error => { clearTimeout(timeout); reject(error); });
      });
      assert.deepEqual((await get(ref(db, `users/${user.uid}/profile`))).val(), user.fixtures.profile);
    }));
    const holdCounts = [connected];
    const holdStart = performance.now();
    while (performance.now() - holdStart < options.holdMs) { await sleep(100); holdCounts.push(connected); }
    const result = { requested: users.length, appContexts: contexts.length, peakActualConnected: peak,
      minimumConnectedDuringHold: Math.min(...holdCounts), holdMs: Math.round(performance.now() - holdStart),
      connectedAtEnd: connected, events };
    await measure('simultaneous_connection_hold', null, async () => {
      assert.equal(result.peakActualConnected, users.length);
      assert.equal(result.minimumConnectedDuringHold, users.length);
    });
    return result;
  } finally {
    await mapLimit(contexts, options.concurrency, async context => {
      context.unsubscribe?.();
      goOffline(context.db);
      await signOut(context.auth);
      await deleteApp(context.app);
    });
  }
}

async function runWave(count, options, config, api, runId, ownedUsers, sentinel) {
  const samples = [];
  const start = performance.now();
  async function measure(phase, user, action) {
    const began = performance.now();
    try {
      const value = await action();
      samples.push({ phase, user: user?.label ?? null, ms: performance.now() - began, ok: true });
      return value;
    } catch (error) {
      samples.push({ phase, user: user?.label ?? null, ms: performance.now() - began, ok: false,
        code: error.code ?? error.name, message: String(error.message).slice(0, 200) });
      return null;
    }
  }
  const users = Array.from({ length: count }, (_, index) => ({ label: `${runId}_${count}_${index}`,
    email: `scale-${runId}-${count}-${index}@example.invalid`, password: `Scale_${randomBytes(18).toString('hex')}!`,
    fixtures: makeFixtures(`s_${runId}_${index}`, Date.now()) }));
  await mapLimit(users, options.concurrency, user => measure('register', user, async () => {
    const registered = requireSuccess(await request(api.auth('signUp'), 'POST', { email: user.email, password: user.password, returnSecureToken: true }));
    user.uid = registered.localId; user.token = registered.idToken; user.refreshToken = registered.refreshToken;
    ownedUsers.push(user);
  }));
  const registered = users.filter(user => user.uid);
  await mapLimit(registered, options.concurrency, user => measure('login', user, async () => {
    const session = requireSuccess(await request(api.auth('signInWithPassword'), 'POST', { email: user.email, password: user.password, returnSecureToken: true }));
    assert.equal(session.localId, user.uid); user.token = session.idToken; user.refreshToken = session.refreshToken;
  }));
  await mapLimit(registered, options.concurrency, user => measure('refresh_token', user, async () => {
    const session = requireSuccess(await request(api.refresh, 'POST', new URLSearchParams({ grant_type: 'refresh_token', refresh_token: user.refreshToken }).toString(),
      { 'Content-Type': 'application/x-www-form-urlencoded' }));
    assert.equal(session.user_id, user.uid); user.token = session.id_token;
  }));
  await mapLimit(registered, options.concurrency, async user => {
    const path = `users/${user.uid}`;
    const { profile, attemptId, attempt, readingId, reading, annotation } = user.fixtures;
    const db = async (suffix, method = 'GET', body) => request(api.db(`${path}/${suffix}`, user.token), method, body);
    await measure('profile_write', user, async () => requireSuccess(await db('profile', 'PUT', profile)));
    await measure('profile_read_after_refresh', user, async () => assert.deepEqual(requireSuccess(await db('profile')), profile));
    await measure('stable_attempt_retry', user, async () => {
      requireSuccess(await db(`practiceAttempts/${attemptId}`, 'PUT', attempt));
      requireSuccess(await db(`practiceAttempts/${attemptId}`, 'PUT', attempt));
      const actual = requireSuccess(await db('practiceAttempts'));
      assert.deepEqual(actual, { [attemptId]: attempt });
    });
    await measure('attempt_mutation_denied', user, async () => {
      assertDenied(await db(`practiceAttempts/${attemptId}`, 'PATCH', { correct: 3 }));
      assert.deepEqual(requireSuccess(await db(`practiceAttempts/${attemptId}`)), attempt);
    });
    await measure('saved_reading_atomic_and_retry', user, async () => {
      requireSuccess(await request(api.db(path, user.token), 'PATCH', { readingShelfVersion: 1,
        [`savedReadings/${readingId}`]: reading, 'savedReadingSlots/s00': readingId }));
      requireSuccess(await db(`savedReadings/${readingId}`, 'PUT', reading));
      assert.deepEqual(requireSuccess(await db('savedReadings')), { [readingId]: reading });
    });
    await measure('reading_mutation_denied', user, async () => {
      assertDenied(await db(`savedReadings/${readingId}`, 'PATCH', { excerpt: 'Changed snapshot' }));
      assert.deepEqual(requireSuccess(await db(`savedReadings/${readingId}`)), reading);
    });
    await measure('annotation_write_read_retry', user, async () => {
      requireSuccess(await db(`readingAnnotations/${readingId}`, 'PUT', annotation));
      requireSuccess(await db(`readingAnnotations/${readingId}`, 'PUT', annotation));
      assert.deepEqual(requireSuccess(await db(`readingAnnotations/${readingId}`)), annotation);
    });
    await measure('unapproved_score_denied', user, async () => assertDenied(await db('scores/scale_score', 'PUT', {
      courseId: 'scale_fixture', assessmentId: 'scale_score', categoryId: 'quizzes', planVersion: 'unapproved_scale',
      status: 'SCORED', earnedPoints: 1, possiblePoints: 2, updatedAt: Date.now() })));
    await measure('relogin_retains_owned_records', user, async () => {
      const session = requireSuccess(await request(api.auth('signInWithPassword'), 'POST', { email: user.email, password: user.password, returnSecureToken: true }));
      assert.equal(session.localId, user.uid); user.token = session.idToken;
      assert.deepEqual(requireSuccess(await db('profile')), profile);
      assert.deepEqual(requireSuccess(await db(`readingAnnotations/${readingId}`)), annotation);
    });
  });
  await mapLimit(registered, options.concurrency, async (user) => {
    const peer = registered.length > 1 ? registered[(registered.indexOf(user) + 1) % registered.length] : sentinel;
    const peerPath = `users/${peer.uid}`;
    await measure('cross_user_read_denied', user, async () => assertDenied(await request(api.db(`${peerPath}/profile`, user.token))));
    await measure('cross_user_write_denied', user, async () => assertDenied(await request(api.db(`${peerPath}/profile`, user.token), 'PATCH', { displayName: 'Unauthorized' })));
    await measure('cross_user_records_denied', user, async () => {
      assertDenied(await request(api.db(`${peerPath}/practiceAttempts`, user.token)));
      assertDenied(await request(api.db(`${peerPath}/practiceAttempts/forged`, user.token), 'PUT', user.fixtures.attempt));
    });
    await measure('owner_state_preserved_after_denials', user, async () => {
      assert.deepEqual(requireSuccess(await request(api.db(`users/${user.uid}/profile`, user.token))), user.fixtures.profile);
    });
  });
  const persistent = await persistentConnections(registered, options, measure, config);
  return { requestedUsers: count, registeredUsers: registered.length, boundedRequestConcurrency: options.concurrency,
    durationMs: Math.round(performance.now() - start), persistent, phases: summarizeSamples(samples), samples };
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  const config = LOCAL_CONFIG, api = endpoints(config);
  const runId = `${Date.now()}_${randomBytes(3).toString('hex')}`;
  await mkdir(options.output, { recursive: true });
  const rules = JSON.parse(await readFile(new URL('../database.rules.json', import.meta.url), 'utf8'));
  const deployedRules = requireSuccess(await request(api.db('.settings/rules'), 'GET', undefined, { Authorization: 'Bearer owner' }));
  assert.deepEqual(deployedRules, rules, 'Isolated emulator rules differ from current source');
  const ownedUsers = [], cleanup = [], waves = [];
  const report = { runId, startedAt: new Date().toISOString(), environment: { ...config, node: process.version },
    sourceRulesSha256: digest(rules), options, waves, cleanup,
    scope: 'Local Firebase emulator correctness and connection exercise only; not cloud capacity or Android device proof. Academic approval and eligibility remain untested.' };
  try {
    const sentinel = { label: `${runId}_security_sentinel`, email: `scale-${runId}-sentinel@example.invalid`, password: `Scale_${randomBytes(18).toString('hex')}!` };
    const account = requireSuccess(await request(api.auth('signUp'), 'POST', { email: sentinel.email, password: sentinel.password, returnSecureToken: true }));
    sentinel.uid = account.localId; sentinel.token = account.idToken; ownedUsers.push(sentinel);
    requireSuccess(await request(api.db(`users/${sentinel.uid}/profile`, sentinel.token), 'PUT', makeFixtures('sentinel', Date.now()).profile));
    for (const count of options.levels) {
      const wave = await runWave(count, options, config, api, runId, ownedUsers, sentinel);
      waves.push(wave);
      console.log(JSON.stringify({ users: count, connected: wave.persistent.peakActualConnected,
        minimumDuringHold: wave.persistent.minimumConnectedDuringHold,
        failed: Object.values(wave.phases).reduce((sum, phase) => sum + phase.failed, 0), durationMs: wave.durationMs }));
      await writeFile(resolve(options.output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
    }
  } catch (error) {
    report.fatal = { code: error.code ?? error.name, message: String(error.message).slice(0, 200) };
    console.error(`Scale harness stopped: ${report.fatal.code}: ${report.fatal.message}`);
  } finally {
    await mapLimit(ownedUsers, options.concurrency, async user => {
      try {
        requireSuccess(await request(api.db(`users/${user.uid}`, user.token), 'DELETE'));
        assert.equal(requireSuccess(await request(api.db(`users/${user.uid}`, user.token))), null);
        requireSuccess(await request(api.auth('delete'), 'POST', { idToken: user.token }));
        cleanup.push({ user: user.label, databaseDeleted: true, authDeleted: true });
      } catch (error) { cleanup.push({ user: user.label, failed: true, code: error.code ?? error.name, message: String(error.message).slice(0, 160) }); }
    });
    report.finishedAt = new Date().toISOString();
    report.failedChecks = waves.reduce((sum, wave) => sum + Object.values(wave.phases).reduce((n, phase) => n + phase.failed, 0), 0);
    report.cleanupFailures = cleanup.filter(item => item.failed).length;
    await writeFile(resolve(options.output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
  }
  if (report.fatal || report.failedChecks || report.cleanupFailures || waves.length !== options.levels.length) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
