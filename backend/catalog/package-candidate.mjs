import { CandidateError, validateCandidate } from './validate-candidate.mjs';

// Raw text transport plus reviewed external asset descriptors. It does not attest human approval.
export function packageCandidate(candidate) {
  validateCandidate(candidate);
  const decode = bytes => {
    const value = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    if (!Buffer.from(value, 'utf8').equals(bytes)) throw new CandidateError('UTF8_ROUND_TRIP');
    return value;
  };
  const manifestJson = decode(candidate.manifestBytes);
  const manifest = JSON.parse(manifestJson);
  return { schemaVersion: 1, manifestJson, textJson: decode(candidate.files.get(manifest.text.file)) };
}
