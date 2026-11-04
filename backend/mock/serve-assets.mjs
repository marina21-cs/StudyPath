import { createServer } from 'node:http';
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';
import { createMockCurriculum } from './curriculum.mjs';

/** Explicit local HTTP mirror for an isolated demo. It never impersonates public HTTPS or reads arbitrary files. */
export async function startMockAssetServer({ host = '127.0.0.1', port = 8765, assetOrigin } = {}) {
  if (host !== '127.0.0.1' || !Number.isInteger(port) || port < 0 || port > 65535) throw new Error('A loopback mirror port is required.');
  const fixture = createMockCurriculum(assetOrigin ? { assetOrigin } : undefined);
  const inventory = new Map(fixture.assets.map(asset => [new URL(asset.url).pathname, asset]));
  const requests = [];
  const server = createServer((request, response) => {
    const asset = inventory.get(request.url), allowed = ['GET', 'HEAD'].includes(request.method);
    const status = !allowed ? 405 : asset ? 200 : 404;
    requests.push({ method: request.method, path: request.url, status, ...(asset && status === 200 ? { bytes: asset.bytes.length, sha256: asset.sha256 } : {}) });
    response.statusCode = status;
    response.setHeader('Cache-Control', 'no-store');
    // Each download may wait for a Firebase acknowledgement before the next file.
    // Close explicitly so Android never reuses a socket past Node's idle timeout.
    response.setHeader('Connection', 'close');
    response.setHeader('X-StudyPath-Evidence', 'SYNTHETIC_LOCAL_HTTP_MIRROR');
    if (status !== 200) { response.end(); return; }
    response.setHeader('Content-Type', asset.mime); response.setHeader('Content-Length', asset.bytes.length);
    response.setHeader('ETag', `"${asset.sha256}"`);
    response.end(request.method === 'HEAD' ? undefined : asset.bytes);
  });
  server.listen(port, host); await once(server, 'listening');
  return { server, port: server.address().port, requests,
    inventory: fixture.assets.map(({ id, file, url, bytes, sha256 }) => ({ id, file, url, bytes: bytes.length, sha256 })),
    close: () => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = process.argv[2] === '--port' && process.argv.length === 4 ? Number(process.argv[3]) : process.argv.length === 2 ? 8765 : NaN;
  const mirror = await startMockAssetServer({ port });
  console.log(JSON.stringify({ status: 'LISTENING', localOnly: true, syntheticOnly: true, host: '127.0.0.1', port: mirror.port, inventory: mirror.inventory }));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => {
    await mirror.close(); console.log(JSON.stringify({ status: 'CLOSED', requests: mirror.requests }));
  });
}
