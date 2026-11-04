import { validateCandidate } from './validate-candidate.mjs';

// Local metadata projection only. This never imports content or establishes human approval.
export function summarizeCandidate(candidate) {
  const measured = validateCandidate(candidate);
  const manifest = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(candidate.manifestBytes));
  const { curriculumVersion, levelId, subjectId, classIds } = manifest.applicability;
  return { schemaVersion: 1, moduleId: measured.moduleId, version: measured.version, title: manifest.title,
    visibility: 'PUBLIC', applicability: { curriculumVersion, levelId, subjectId,
      ...(classIds.length ? { classIds: Object.fromEntries(classIds.map(id => [id, true])) } : {}) },
    source: manifest.source, review: manifest.review, textBytes: measured.textBytes,
    assetBytes: measured.assetBytes, packageSha256: measured.packageSha256 };
}
