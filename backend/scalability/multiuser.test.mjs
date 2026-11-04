import test from 'node:test';
import assert from 'node:assert/strict';
import { LOCAL_CONFIG, assertLocalConfig, mapLimit, percentile, summarizeSamples, makeFixtures, assertDenied } from './multiuser.mjs';

test('only the dedicated loopback emulator project and namespace are accepted', () => {
  assert.doesNotThrow(() => assertLocalConfig(LOCAL_CONFIG));
  for (const patch of [
    { projectId: 'studypath-c8086' }, { namespace: 'demo-studypath-default-rtdb' },
    { authOrigin: 'https://identitytoolkit.googleapis.com' }, { databaseOrigin: 'https://example.firebaseio.com' },
    { authOrigin: 'http://127.0.0.1:9099' }, { databaseOrigin: 'http://127.0.0.1:9000' },
    { databaseOrigin: 'http://localhost:9100' }, { authOrigin: 'http://example.com:9199' }
  ]) assert.throws(() => assertLocalConfig({ ...LOCAL_CONFIG, ...patch }), /dedicated local emulator/);
});

test('bounded workers execute every task once without exceeding concurrency', async () => {
  let active = 0, peak = 0;
  const seen = [];
  await mapLimit(Array.from({ length: 27 }, (_, index) => index), 4, async index => {
    active++; peak = Math.max(peak, active);
    await new Promise(resolve => setTimeout(resolve, 2));
    seen.push(index); active--;
  });
  assert.equal(peak, 4);
  assert.deepEqual(seen.sort((a, b) => a - b), Array.from({ length: 27 }, (_, index) => index));
});

test('invalid worker limits reject instead of silently skipping work', async () => {
  for (const limit of [0, -1, 1.5, 101]) await assert.rejects(mapLimit([1], limit, async () => {}), /concurrency/);
});

test('nearest-rank percentiles and grouped failures preserve every request', () => {
  assert.equal(percentile([10, 2, 1, 5], .5), 2);
  assert.equal(percentile([10, 2, 1, 5], .95), 10);
  assert.equal(percentile([], .95), null);
  const summary = summarizeSamples([
    { phase: 'login', ms: 2, ok: true }, { phase: 'login', ms: 10, ok: false, code: 'TIMEOUT' },
    { phase: 'read', ms: 5, ok: true }
  ]);
  assert.deepEqual(summary.login, { count: 2, passed: 1, failed: 1, p50Ms: 2, p95Ms: 10, failures: { TIMEOUT: 1 } });
  assert.equal(summary.read.failed, 0);
});

test('synthetic fixtures retain stable IDs, draft preferences and provenance bounds', () => {
  const fixture = makeFixtures('scale_run_7', 1793740800000);
  assert.equal(fixture.profile.learnerPreferences.onboardingComplete, false);
  assert.equal(fixture.annotation.excerptLength, fixture.reading.excerpt.length);
  assert.equal(fixture.annotation.snapshotRevisionId, fixture.reading.revisionId);
  assert.equal(fixture.attemptId, 'scale_run_7_attempt');
  assert.equal(fixture.readingId, 'scale_run_7_reading');
  assert.ok(fixture.attempt.correct <= fixture.attempt.total);
  assert.match(fixture.reading.sourceUrl, /^https:\/\/en\.wikipedia\.org\/wiki\//);
});

test('only a permission-denied response counts as the expected security rejection', () => {
  assert.doesNotThrow(() => assertDenied({ status: 401, data: { error: 'Permission denied' } }));
  assert.doesNotThrow(() => assertDenied({ status: 403, data: { error: 'Permission denied.' } }));
  for (const response of [
    { status: 200, data: null }, { status: 500, data: { error: 'Internal error' } },
    { status: 401, data: { error: 'Invalid token' } }
  ]) assert.throws(() => assertDenied(response), /Expected permission denial/);
});
