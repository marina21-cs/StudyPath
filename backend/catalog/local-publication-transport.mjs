import { CandidateError } from './validate-candidate.mjs';
import { localCatalogUrl } from './publication.mjs';
const LIMIT = 10_000_000;

async function responseJson(response) {
  if (Number(response.headers.get('content-length')) > LIMIT) throw new CandidateError('CATALOG_TOO_LARGE');
  const reader = response.body.getReader();
  const chunks = []; let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > LIMIT) throw new CandidateError('CATALOG_TOO_LARGE');
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks, total)));
  } catch (error) {
    await reader.cancel().catch(() => {});
    if (error instanceof CandidateError) throw error;
    throw new CandidateError('CATALOG_INVALID');
  } finally { reader.releaseLock(); }
}

/** Owner privilege is accepted only on explicit loopback demo namespaces; this cannot target cloud. */
export function createLocalTransport({ host, namespace, fetchImpl = fetch }) {
  const url = localCatalogUrl({ host, namespace });
  const headers = { Authorization: 'Bearer owner', 'content-type': 'application/json' };
  return {
    async read() {
      const response = await fetchImpl(url, { method: 'GET', headers: { ...headers, 'X-Firebase-ETag': 'true' },
        signal: AbortSignal.timeout(10_000), redirect: 'error' });
      if (!response.ok) throw new CandidateError('CATALOG_READ_FAILED');
      const etag = response.headers.get('etag');
      if (!etag || etag.length > 256 || /[\r\n]/.test(etag)) throw new CandidateError('CATALOG_ETAG_REQUIRED');
      return { catalog: await responseJson(response), etag };
    },
    async compareAndSet(etag, catalog) {
      if (typeof etag !== 'string' || !etag || etag.length > 256 || /[\r\n]/.test(etag)) {
        throw new CandidateError('CATALOG_ETAG_REQUIRED');
      }
      const body = JSON.stringify(catalog);
      if (Buffer.byteLength(body) > LIMIT) throw new CandidateError('CATALOG_TOO_LARGE');
      const response = await fetchImpl(url, { method: 'PUT', headers: { ...headers, 'If-Match': etag }, body,
        signal: AbortSignal.timeout(10_000), redirect: 'error' });
      if (response.status === 412) return false;
      if (!response.ok) throw new CandidateError('CATALOG_WRITE_UNCONFIRMED');
      return true;
    },
  };
}
