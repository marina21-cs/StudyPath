import { checkDirectory } from '../catalog/read-candidate.mjs';
import { CandidateError } from '../catalog/validate-candidate.mjs';

const args = process.argv.slice(2);
if (args.length < 2 || args.length > 3 || (args[2] !== undefined && !/^(0|[1-9][0-9]*)$/.test(args[2]))) {
  console.error(JSON.stringify({ status: 'blocked', code: 'USAGE',
    usage: 'node scripts/check-catalog-candidate.mjs CANDIDATE_DIRECTORY PUBLISHED_INDEX_JSON [REVIEWED_ASSET_BUDGET_BYTES]' }));
  process.exitCode = 2;
} else {
  try {
    const result = await checkDirectory(args[0], args[1], args[2] === undefined ? 0 : Number(args[2]));
    console.log(JSON.stringify({ status: 'metadata_preflight_passed', ...result,
      publicationPerformed: false, contentApprovalEstablished: false, lessonSemanticsValidated: false }, null, 2));
  } catch (error) {
    console.error(JSON.stringify({ status: 'blocked', code: error instanceof CandidateError ? error.code : 'CHECK_FAILED' }));
    process.exitCode = 1;
  }
}
