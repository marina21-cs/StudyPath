import { readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { CandidateError } from '../catalog/validate-candidate.mjs';
import { readBounded, readCandidateDirectory } from '../catalog/read-candidate.mjs';
import { preparePublication, publishIntoCatalog, withdrawFromCatalog, executeCatalogOperation } from '../catalog/publication.mjs';
import { createLocalTransport } from '../catalog/local-publication-transport.mjs';

const usage = 'publish-catalog.mjs publish CANDIDATE_DIR APPROVAL_DIR --namespace demo-studypath-OWNED --actor ID --at UNIX_MS [--asset-budget BYTES] [--catalog LOCAL_JSON] [--allow-synthetic] [--apply] | withdraw REQUEST_JSON --namespace demo-studypath-OWNED [--catalog LOCAL_JSON] [--apply]';
const json = async (path, limit, code) => {
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(await readBounded(path, limit, code))); }
  catch (error) { if (error instanceof CandidateError) throw error; throw new CandidateError(code); }
};
try {
  const args = process.argv.slice(2), mode = args.shift();
  if (!['publish', 'withdraw'].includes(mode)) throw new CandidateError('USAGE');
  const directory = args.shift(), approvalDirectory = mode === 'publish' ? args.shift() : undefined;
  if (!directory || (mode === 'publish' && !approvalDirectory)) throw new CandidateError('USAGE');
  const options = {};
  while (args.length) {
    const key = args.shift();
    if (!['--namespace', '--actor', '--at', '--asset-budget', '--catalog', '--allow-synthetic', '--apply'].includes(key) ||
      Object.hasOwn(options, key)) throw new CandidateError('USAGE');
    options[key] = ['--apply', '--allow-synthetic'].includes(key) ? true : args.shift();
    if (options[key] === undefined) throw new CandidateError('USAGE');
  }
  if (options['--catalog'] && options['--apply']) throw new CandidateError('LOCAL_SNAPSHOT_READ_ONLY');
  if (mode === 'withdraw' && ['--actor', '--at', '--asset-budget', '--allow-synthetic'].some(key => key in options)) {
    throw new CandidateError('USAGE');
  }
  let operation;
  if (mode === 'publish') {
    const budget = options['--asset-budget'] ?? '0';
    if (!/^(0|[1-9][0-9]*)$/.test(budget) || !/^(0|[1-9][0-9]*)$/.test(options['--at'] ?? '')) throw new CandidateError('USAGE');
    const candidate = await readCandidateDirectory(directory, Number(budget));
    const approval = await json(join(approvalDirectory, 'approval.json'), 100_000, 'PUBLICATION_APPROVAL_REQUIRED');
    const entries = await readdir(approvalDirectory, { withFileTypes: true });
    if (!Array.isArray(approval.evidence) || entries.length !== approval.evidence.length + 1 || entries.some(entry => !entry.isFile()) ||
      entries.some(entry => entry.name !== 'approval.json' && !approval.evidence.some(proof => proof.file === entry.name))) {
      throw new CandidateError('PUBLICATION_APPROVAL_REQUIRED');
    }
    const evidenceFiles = new Map();
    for (const proof of approval.evidence) {
      if (!/^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}$/.test(proof.file)) throw new CandidateError('PUBLICATION_APPROVAL_REQUIRED');
      evidenceFiles.set(proof.file, await readBounded(join(approvalDirectory, proof.file), 1_000_000, 'PUBLICATION_APPROVAL_REQUIRED'));
    }
    const prepared = preparePublication({ candidate, approval, evidenceFiles, actorId: options['--actor'], occurredAt: Number(options['--at']) });
    if (approval.mode === 'SYNTHETIC_LOCAL_ONLY' && !options['--allow-synthetic']) throw new CandidateError('SYNTHETIC_OPT_IN_REQUIRED');
    operation = catalog => publishIntoCatalog(catalog, prepared);
  } else {
    const request = await json(directory, 100_000, 'WITHDRAWAL_INVALID');
    operation = catalog => withdrawFromCatalog(catalog, request);
  }
  let transport;
  if (options['--catalog']) {
    const catalog = await json(options['--catalog'], 10_000_000, 'CATALOG_INVALID');
    transport = { async read() { return { catalog, etag: 'local-snapshot' }; },
      async compareAndSet() { throw new CandidateError('LOCAL_SNAPSHOT_READ_ONLY'); } };
  } else {
    transport = createLocalTransport({ host: process.env.FIREBASE_DATABASE_EMULATOR_HOST, namespace: options['--namespace'] });
  }
  const result = await executeCatalogOperation({ transport, operation, apply: options['--apply'] === true });
  console.log(JSON.stringify({ status: 'validated', ...result, contentApprovalEstablished: false,
    scope: 'B09_LOCAL_PUBLICATION_TOOLING', cloudPublicationAllowed: false }, null, 2));
} catch (error) {
  const code = error instanceof CandidateError ? error.code : 'PUBLICATION_FAILED';
  console.error(JSON.stringify({ status: 'blocked', code, ...(code === 'USAGE' ? { usage } : {}) }));
  process.exitCode = code === 'USAGE' ? 2 : 1;
}
