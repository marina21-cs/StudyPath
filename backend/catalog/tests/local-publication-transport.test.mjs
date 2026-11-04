import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createLocalTransport } from '../local-publication-transport.mjs';
const config = { host: '127.0.0.1:9000', namespace: 'demo-studypath-tooling-test' };

test('GET requests an ETag and conditional PUT keeps exactly that revision on loopback', async () => {
  const requests = [];
  const transport = createLocalTransport({ ...config, fetchImpl: async (url, request) => {
    requests.push({ url, request });
    return new Response('{"unrelated":true}', { headers: { etag: '"catalog-revision"' } });
  } });
  const result = await transport.read();
  assert.deepEqual(result, { catalog: { unrelated: true }, etag: '"catalog-revision"' });
  assert.equal(await transport.compareAndSet(result.etag, result.catalog), true);
  assert.equal(requests[0].request.headers['X-Firebase-ETag'], 'true');
  assert.equal(requests[1].request.headers['If-Match'], '"catalog-revision"');
  assert.equal(requests[1].request.method, 'PUT');
  assert.equal(requests[1].request.redirect, 'error');
  assert.deepEqual(JSON.parse(requests[1].request.body), { unrelated: true });
});

test('HTTP precondition failure is retryable, other write errors stay unconfirmed', async () => {
  const stale = createLocalTransport({ ...config, fetchImpl: async () => new Response('{}', { status: 412 }) });
  assert.equal(await stale.compareAndSet('"etag"', {}), false);
  const failed = createLocalTransport({ ...config, fetchImpl: async () => new Response('{}', { status: 503 }) });
  await assert.rejects(() => failed.compareAndSet('"etag"', {}), error => error.code === 'CATALOG_WRITE_UNCONFIRMED');
});

test('missing ETag, malformed response and body-size overflow are rejected', async () => {
  for (const [response, code] of [
    [new Response('{}'), 'CATALOG_ETAG_REQUIRED'],
    [new Response('bad json', { headers: { etag: '"v1"' } }), 'CATALOG_INVALID'],
    [new Response('{}', { headers: { etag: '"v1"', 'content-length': '10000001' } }), 'CATALOG_TOO_LARGE'],
    [new Response('{}', { status: 403 }), 'CATALOG_READ_FAILED'],
  ]) {
    const transport = createLocalTransport({ ...config, fetchImpl: async () => response });
    await assert.rejects(() => transport.read(), error => error.code === code);
  }
});
