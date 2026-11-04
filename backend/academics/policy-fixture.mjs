import {createHash} from 'node:crypto';
export const overrideKey=(category,assessment)=>'override_'+createHash('sha256').update([category,assessment].map(x=>`${x.length}:${x}`).join('')).digest('hex').slice(0,48);
// Fabricated local test policy. This is never a production approval or seed.
export const academicPreferences = () => ({schemaVersion:1,levelId:'test_college',curriculumVersion:'test_curriculum_v1',subjectIds:{computing:true},onboardingComplete:true});
export const academicPolicy = () => ({schemaVersion:1,curriculumVersion:'test_curriculum_v1',levelId:'test_college',educationSector:'HIGHER_EDUCATION',subjectIds:{computing:true},approvalStatus:'APPROVED',approvalRef:'SYNTHETIC_TEST_ONLY',reviewer:'Fabricated test reviewer',reviewedAt:1792627200000,sourceRef:'test_policy_source',contextRef:'test_context',revision:1,validFrom:0,validUntil:4102444800000,capabilities:{SCORES:true,SCENARIOS:true,GWA:true,PREREQUISITES:true},capabilityApprovalRefs:{SCORES:'test_scores_ref',SCENARIOS:'test_scenarios_ref',GWA:'test_gwa_ref',PREREQUISITES:'test_prerequisite_ref'},gwaPolicy:{scaleId:'demo_scale',minimumGrade:0,maximumGrade:100,attemptInclusion:'EXPLICIT',retakePolicyRef:'test_explicit_retakes',exclusionPolicyRef:'test_explicit_exclusions',roundingMode:'HALF_UP',roundingScale:2},coursePlans:{it101:{subjectId:'computing',planVersion:'demo_linear_v1'}}});
export const academicOptions = () => ({schemaVersion:1,curriculumVersion:'test_curriculum_v1',levelId:'test_college',subjectIds:{computing:true},approved:true,approvalStatus:'APPROVED'});
export const academicBinding = (capability='SCORES') => ({curriculumVersion:'test_curriculum_v1',policyRevision:1,policyRef:academicPolicy().capabilityApprovalRefs[capability],planVersion:'demo_linear_v1'});

export async function seedAcademicFixture(env, uid='alice') {
  const {ref,update}=await import('firebase/database');
  await env.withSecurityRulesDisabled(async context=>update(ref(context.database()),{
    'catalog/academicPolicies/test_curriculum_v1':academicPolicy(),
    'catalog/learnerPreferenceOptions/test_curriculum_v1':academicOptions(),
    'catalog/modules/programming_basics':{moduleId:'programming_basics',title:'Synthetic legacy reminder target'},
    'catalog/courses/it101':{courseId:'it101',title:'Synthetic test only',units:3,gradePlan:{version:'demo_linear_v1',verified:true,assessmentPlanKnown:true,overrideKeys:{[overrideKey('quizzes','quiz_1')]:true,[overrideKey('quizzes','quiz_2')]:true},categories:{quizzes:{weight:1,aggregation:'POOLED_POINTS',assessments:{quiz_1:{title:'Synthetic quiz',possiblePoints:20},quiz_2:{title:'Synthetic second quiz',possiblePoints:20}}}}}},
    [`users/${uid}/profile/learnerPreferences`]:academicPreferences(),
  }));
}
