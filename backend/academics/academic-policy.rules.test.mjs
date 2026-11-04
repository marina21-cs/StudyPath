import {readFile} from 'node:fs/promises';
import {before,after,beforeEach,test} from 'node:test';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {ref,set,update,get,remove} from 'firebase/database';
import assert from 'node:assert/strict';
import {academicBinding,seedAcademicFixture} from './policy-fixture.mjs';

let env;
const db=(uid='alice')=>env.authenticatedContext(uid).database();
const score=()=>({courseId:'it101',assessmentId:'quiz_1',categoryId:'quizzes',status:'SCORED',possiblePoints:20,earnedPoints:0,...academicBinding()});
const scoreRef=()=>ref(db(),'users/alice/scores/s1');
const admin=async(path,value)=>env.withSecurityRulesDisabled(c=>set(ref(c.database(),path),value));
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-studypath-academic-policy',database:{host:'127.0.0.1',port:9000,rules:await readFile(new URL('../database.rules.json',import.meta.url),'utf8')}});});
after(async()=>{await env?.cleanup();});
beforeEach(async()=>{await env.clearDatabase();await seedAcademicFixture(env);});

test('academic writes require trusted policy binding; legacy values cannot activate a plan',async()=>{
 const value=score(); delete value.curriculumVersion;delete value.policyRevision;delete value.policyRef;
 await assertFails(set(scoreRef(),value));
});
test('matching approved policy permits scored zero and exact retry',async()=>{
 const value=score();await assertSucceeds(set(scoreRef(),value));await assertSucceeds(set(scoreRef(),value));assert.deepEqual((await get(scoreRef())).val(),value);
});
test('missing, pending, withdrawn and expired policies deny writes',async()=>{
 for(const [child,value] of [['approvalStatus','REVIEW_PENDING'],['approvalStatus','WITHDRAWN'],['validUntil',1],['validFrom',4102444800000],['reviewer',''],['sourceRef',''],['contextRef','']]){
  await seedAcademicFixture(env);await admin(`catalog/academicPolicies/test_curriculum_v1/${child}`,value);await assertFails(set(scoreRef(),score()));
 }
 await admin('catalog/academicPolicies/test_curriculum_v1',null);await assertFails(set(scoreRef(),score()));
});
test('missing capability and policy reference fail closed independently of curriculum approval',async()=>{
 await admin('catalog/academicPolicies/test_curriculum_v1/capabilities/SCORES',false);await assertFails(set(scoreRef(),score()));
 await seedAcademicFixture(env);await admin('catalog/academicPolicies/test_curriculum_v1/capabilityApprovalRefs/SCORES',null);await assertFails(set(scoreRef(),score()));
});
test('forged policy revision reference curriculum or plan is rejected',async()=>{
 for(const changes of [{policyRevision:2},{policyRef:'forged'},{curriculumVersion:'bsit_demo_v1'},{planVersion:'other_plan'}])await assertFails(set(scoreRef(),{...score(),...changes}));
});
test('unsupported preferences and unapproved option index cannot enable calculations',async()=>{
 for(const [child,value] of [['onboardingComplete',false],['levelId','other_level'],['curriculumVersion','bsit_demo_v1'],['subjectIds', {foreign:true}]]){
  await seedAcademicFixture(env);await admin(`users/alice/profile/learnerPreferences/${child}`,value);await assertFails(set(scoreRef(),score()));
 }
 await seedAcademicFixture(env);await admin('catalog/learnerPreferenceOptions/test_curriculum_v1/approved',false);await assertFails(set(scoreRef(),score()));
});
test('foreign assessment category possible points and course are rejected',async()=>{
 for(const changes of [{courseId:'foreign'},{assessmentId:'foreign'},{categoryId:'foreign'},{possiblePoints:21}])await assertFails(set(scoreRef(),{...score(),...changes}));
});
test('client cannot forge catalog approval or bypass gate through ancestor write',async()=>{
 await assertFails(set(ref(db(),'catalog/academicPolicies/test_curriculum_v1/revision'),2));
 await admin('catalog/academicPolicies/test_curriculum_v1/approvalStatus','WITHDRAWN');
 await assertFails(update(ref(db(),'users/alice'),{'scores/s1':score()}));
});
test('scenario capability cannot mutate actual scores and has separate policy reference',async()=>{
 const scenario={courseId:'it101',title:'Test scenario',targetPercent:75,baseVersion:'base_fingerprint',...academicBinding('SCENARIOS')};
 await assertSucceeds(set(ref(db(),'users/alice/scenarios/sc1'),scenario));
 await assertFails(set(scoreRef(),{...score(),policyRef:scenario.policyRef}));
 await admin('catalog/academicPolicies/test_curriculum_v1/capabilities/SCENARIOS',false);await assertFails(set(ref(db(),'users/alice/scenarios/sc1'),scenario));
});
test('college GWA and prerequisites cannot follow a basic-education policy',async()=>{
 await admin('catalog/academicPolicies/test_curriculum_v1/educationSector','BASIC_EDUCATION');
 const attempt={courseId:'it101',term:'test',status:'COMPLETED',units:3,grade:1.5,scaleId:'test_scale',includeInGwa:true,...academicBinding('GWA')};
 await assertFails(set(ref(db(),'users/alice/courseAttempts/a1'),attempt));
 await assertFails(set(ref(db(),'users/alice/courseAttempts/a2'),{...attempt,includeInGwa:false,completionStatus:'PASSED',...academicBinding('PREREQUISITES')}));
});
test('withdrawal never prevents owner deletion of retained academic records',async()=>{
 await assertSucceeds(set(scoreRef(),score()));await admin('catalog/academicPolicies/test_curriculum_v1/approvalStatus','WITHDRAWN');await assertSucceeds(remove(scoreRef()));
});

test('GWA inclusion requires a separately reviewed scale, valid range and catalog units',async()=>{
 const value={courseId:'it101',term:'test',status:'COMPLETED',units:3,grade:1.75,scaleId:'demo_scale',includeInGwa:true,...academicBinding('GWA')};
 await assertSucceeds(set(ref(db(),'users/alice/courseAttempts/gwa'),value));
 for(const changes of [{scaleId:'unknown_scale'},{units:4},{grade:101}]) await assertFails(set(ref(db(),'users/alice/courseAttempts/gwa'),{...value,...changes}));
 for(const [field,bad] of [['retakePolicyRef',''],['exclusionPolicyRef',''],['roundingMode','GUESS'],['minimumGrade',99],['roundingScale',7]]){
  await seedAcademicFixture(env); await admin(`catalog/academicPolicies/test_curriculum_v1/gwaPolicy/${field}`,bad);
  await assertFails(set(ref(db(),'users/alice/courseAttempts/gwa'),value));
 }
});
test('retained legacy and earlier policy records cannot be silently rebound',async()=>{
 const old=score();delete old.curriculumVersion;delete old.policyRevision;delete old.policyRef;
 await admin('users/alice/scores/s1',old); await assertFails(set(scoreRef(),score()));
 await admin('users/alice/scores/s1',{...score(),policyRevision:2}); await assertFails(set(scoreRef(),score()));
 await assertSucceeds(remove(scoreRef()));
});

test('scenario overrides must belong to a trusted published assessment key index',async()=>{
 const value={courseId:'it101',title:'Scenario',targetPercent:75,baseVersion:'test_base',...academicBinding('SCENARIOS')};
 await assertFails(set(ref(db(),'users/alice/scenarios/invalid_override'),{...value,overrides:{unknown:50}}));
});
