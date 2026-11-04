import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { syntheticLessonFixture } from '../../backend/interactive/synthetic-lesson-fixture.mjs';
import { summarizeCandidate } from '../../backend/catalog/summarize-candidate.mjs';
import { packageCandidate } from '../../backend/catalog/package-candidate.mjs';

// Byte-only local fixture generation. No production approval and no network/publication action.
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const [pdfPath, namespace] = process.argv.slice(2);
if (!pdfPath || !/^demo-studypath-v1-fixes-[a-f0-9]{8}$/.test(namespace)) throw new Error('Select a synthetic PDF and isolated v1 namespace.');
const pdf = await readFile(pdfPath);
const original = syntheticLessonFixture();
const originalTransport = original.catalog.modulePackages.fixture_v1.fixture_module.v1;
const result = { testOnly: true, namespace, syntheticAssetTransport: true, pdfBase64: pdf.toString('base64'),
  pdfSha256: sha(pdf), packages: {}, catalog: { learnerPreferenceOptions: original.catalog.learnerPreferenceOptions,
    moduleIndex: { fixture_v1: { fixture_module: {} } }, modulePackages: { fixture_v1: { fixture_module: {} } } } };
for (let version = 1; version <= 7; version++) {
  const manifest = JSON.parse(originalTransport.manifestJson);
  const payload = JSON.parse(originalTransport.textJson);
  manifest.version = version; payload.version = version;
  const text = Buffer.from(JSON.stringify(payload));
  manifest.text = { file: 'module.json', bytes: text.length, sha256: sha(text) };
  const files = new Map([['module.json', text]]);
  if (version >= 4 && version <= 6) {
    manifest.assets = [{ id: 'pdf_1', file: 'source.pdf', kind: 'PDF', bytes: pdf.length, sha256: sha(pdf),
      url: `https://materials.example.org/v${version}.pdf` }];
    manifest.assetPolicy = { reference: 'SYNTHETIC TEST ONLY asset policy', approvalReference: manifest.review.approvalReference,
      reviewer: manifest.review.reviewer, reviewedOn: manifest.review.reviewedOn, access: 'PUBLIC_PREPUBLISHED',
      origins: ['https://materials.example.org'], maxCount: 1, maxFileBytes: 1_048_576, maxTotalBytes: 1_048_576,
      mimeTypes: ['application/pdf'] };
    files.set('source.pdf', pdf);
  }
  const candidate = { manifest, manifestBytes: Buffer.from(JSON.stringify(manifest)), files,
    publishedIndex: { schemaVersion: 1, modules: {} }, assetBudgetBytes: 1_048_576 };
  const summary = summarizeCandidate(candidate), transport = packageCandidate(candidate);
  result.packages[`v${version}`] = { summary, ...transport };
  result.catalog.moduleIndex.fixture_v1.fixture_module[`v${version}`] = summary;
  result.catalog.modulePackages.fixture_v1.fixture_module[`v${version}`] = transport;
}
process.stdout.write(JSON.stringify(result) + '\n');
