import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMockCurriculum } from './curriculum.mjs';
const seed = await import('./seed-emulator.mjs').catch(error => { if (error.code === 'ERR_MODULE_NOT_FOUND') return {}; throw error; });
const assets = await import('./serve-assets.mjs').catch(error => { if (error.code === 'ERR_MODULE_NOT_FOUND') return {}; throw error; });
const namespace = 'demo-studypath-mock-a1b2c3d4';
const requireApi = (value, name) => { assert.equal(typeof value[name], 'function', `${name} must exist`); return value[name]; };

function memoryTransport() {
  let root = null, revision = 0; const writes = [], calls = [];
  const read = path => path.reduce((value, key) => value?.[key], root) ?? null;
  const fetchImpl = async (url, options = {}) => {
    calls.push({ url, ...options }); const parsed = new URL(url), path = parsed.pathname.slice(1, -5).split('/').filter(Boolean);
    assert.equal(parsed.host, '127.0.0.1:9000'); assert.equal(parsed.searchParams.get('ns'), namespace);
    if (options.method === 'PUT') {
      if (options.headers['If-Match'] && options.headers['If-Match'] !== `"${revision}"`) return new Response('null', { status: 412 });
      const value = JSON.parse(options.body); writes.push(path.join('/'));
      if (!path.length) root = value;
      else { root ??= {}; let current = root; for (const key of path.slice(0, -1)) current = current[key] ??= {}; current[path.at(-1)] = value; }
      revision++;
    }
    return new Response(JSON.stringify(read(path)), { headers: { etag: `"${revision}"`, 'content-type': 'application/json' } });
  };
  return { fetchImpl, writes, calls, root: () => root };
}

test('mock seeding rejects shared/cloud targets before any fetch and seeds only scoped academic data', async () => {
  const run = requireApi(seed, 'seedMockEmulator'); let calls = 0;
  for (const options of [{ namespace: 'demo-studypath-default-rtdb' }, { namespace, host: 'example.com:9000' }, { namespace: namespace + '/bad' }]) {
    await assert.rejects(run({ ...options, fetchImpl: async () => { calls++; } }));
  }
  assert.equal(calls, 0);
  const transport = memoryTransport(), result = await run({ namespace, fetchImpl: transport.fetchImpl });
  assert.equal(result.receipt.syntheticOnly, true);
  assert.deepEqual(transport.writes.sort(), ['catalog/academicPolicies/mock_computing_v1', 'catalog/courses/mock101',
    'catalog/courses/mock102', 'catalog/courses/mock103', 'catalog/learnerPreferenceOptions/mock_computing_v1'].sort());
  await assert.rejects(run({ namespace, fetchImpl: transport.fetchImpl }), /empty/);
});

test('mock publication uses strict prepared publications and ETag CAS, then derives exact trusted lesson keys', async () => {
  const run = requireApi(seed, 'seedMockEmulator'), publish = requireApi(seed, 'publishMockCurriculum');
  const transport = memoryTransport(); await run({ namespace, fetchImpl: transport.fetchImpl });
  const result = await publish({ namespace, fetchImpl: transport.fetchImpl });
  assert.equal(result.receipt.publications.length, 3);
  assert.ok(result.receipt.publications.every(row => row.outcome === 'PUBLISHED' && row.publicationPerformed));
  const puts = transport.calls.filter(row => row.method === 'PUT' && new URL(row.url).pathname === '/catalog.json');
  assert.equal(puts.length, 3); assert.ok(puts.every(row => typeof row.headers['If-Match'] === 'string'));
  const fixture = createMockCurriculum();
  assert.deepEqual(transport.root().catalog.sharingLessonKeys, fixture.catalog.sharingLessonKeys);
  const repeated = await publish({ namespace, fetchImpl: transport.fetchImpl });
  assert.ok(repeated.receipt.publications.every(row => row.outcome === 'UNCHANGED' && !row.publicationPerformed));
  assert.equal(Object.keys(transport.root().catalog.modulePackages.mock_computing_v1).length, 3);
});

test('asset mirror serves only authored PDF bytes over explicit loopback HTTP and never filesystem paths', async () => {
  const start = requireApi(assets, 'startMockAssetServer');
  await assert.rejects(start({ host: '0.0.0.0', port: 0 }));
  const mirror = await start({ port: 0 });
  try {
    const fixture = createMockCurriculum(), origin = `http://127.0.0.1:${mirror.port}`;
    for (const asset of fixture.assets) {
      const response = await fetch(origin + new URL(asset.url).pathname);
      assert.equal(response.status, 200); assert.equal(response.headers.get('content-type'), 'application/pdf');
      assert.equal(Number(response.headers.get('content-length')), asset.bytes.length);
      assert.deepEqual(Buffer.from(await response.arrayBuffer()), asset.bytes);
    }
    for (const path of ['/etc/passwd', '/studypath/../curriculum.mjs', '/studypath/mock_programming-v1.pdf?token=x']) {
      assert.equal((await fetch(origin + path)).status, 404);
    }
    assert.equal((await fetch(origin + '/studypath/mock_programming-v1.pdf', { method: 'POST' })).status, 405);
    assert.equal(mirror.requests.filter(row => row.status === 200).length, 3);
  } finally { await mirror.close(); }
});
