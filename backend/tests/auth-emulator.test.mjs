import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execFileAsync = promisify(execFile);
const authBase = 'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:';
const dbBase = 'http://127.0.0.1:9000';
const ns = 'demo-studypath-default-rtdb';
const post = async (action, body) => fetch(`${authBase}${action}?key=demo-api-key`, {
  method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body)
});

test('email/password registration and login issue a token accepted only for the owning database subtree', async () => {
  const email = `test-${Date.now()}@studypath.invalid`;
  const password = 'local-test-password-123';
  const signupResponse = await post('signUp', { email, password, returnSecureToken: true });
  assert.equal(signupResponse.status, 200);
  const signup = await signupResponse.json();
  assert.ok(signup.localId);
  const loginResponse = await post('signInWithPassword', { email, password, returnSecureToken: true });
  assert.equal(loginResponse.status, 200);
  const login = await loginResponse.json();
  assert.equal(login.localId, signup.localId);
  const wrongPassword = await post('signInWithPassword', { email, password: 'incorrect-password', returnSecureToken: true });
  assert.equal(wrongPassword.status, 400);
  const profileUrl = `${dbBase}/users/${login.localId}/profile.json?ns=${ns}&auth=${encodeURIComponent(login.idToken)}`;
  const value = { displayName: 'Local test', selectedCurriculumVersion: 'bsit_demo_v1' };
  const own = await fetch(profileUrl, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(value) });
  assert.equal(own.status, 200);
  const cross = await fetch(`${dbBase}/users/somebody_else/profile.json?ns=${ns}&auth=${encodeURIComponent(login.idToken)}`);
  assert.equal(cross.status, 401);
  const secretWrite = await fetch(`${dbBase}/users/${login.localId}/profile/password.json?ns=${ns}&auth=${encodeURIComponent(login.idToken)}`, {
    method: 'PUT', body: JSON.stringify(password), headers: { 'content-type': 'application/json' }
  });
  assert.equal(secretWrite.status, 401);
  await fetch(profileUrl, { method: 'DELETE' });
});

test('catalog seed script is limited to local emulator and produces unapproved demo catalog', async () => {
  await assert.rejects(execFileAsync(process.execPath, ['scripts/seed-emulator.mjs'], { env: { ...process.env, FIREBASE_DATABASE_EMULATOR_HOST: 'production.example:443' } }));
  await execFileAsync(process.execPath, ['scripts/seed-emulator.mjs'], { env: { ...process.env, FIREBASE_DATABASE_EMULATOR_HOST: '127.0.0.1:9000' } });
  const denied = await fetch(`${dbBase}/catalog.json?ns=${ns}`);
  assert.equal(denied.status, 401);
  const admin = await fetch(`${dbBase}/catalog.json?ns=${ns}`, { headers: { Authorization: 'Bearer owner' } });
  assert.equal(admin.status, 200);
  const catalog = await admin.json();
  assert.equal(catalog.approved, false);
  assert.equal(catalog.approvalStatus, 'DEMONSTRATION_ONLY');
  assert.equal(Object.keys(catalog.modules).length, 3);
  for (const module of Object.values(catalog.modules)) assert.equal(module.approved, false);
  const introductoryCourse = catalog.courses.it101;
  assert.equal(introductoryCourse.prerequisitesKnown, true);
  assert.equal(introductoryCourse.ruleKind, 'NONE');
  // RTDB removes empty maps; NONE must remain distinguishable from an unknown rule.
  assert.equal(introductoryCourse.prerequisiteCourseIds, undefined);
  assert.equal(catalog.courses.it102.ruleKind, 'ALL_OF');
  assert.deepEqual(catalog.courses.it102.prerequisiteCourseIds, { it101: true });
  for (const course of Object.values(catalog.courses)) {
    assert.equal(course.prerequisitesKnown, true);
    const plan = course.gradePlan;
    assert.equal(plan.version, 'demo_linear_v1');
    assert.equal(plan.assessmentPlanKnown, true);
    assert.equal(plan.verified, false);
    assert.ok(plan.label.includes('not an actual syllabus'));
    const categories = Object.values(plan.categories);
    assert.equal(categories.reduce((sum, category) => sum + category.weight, 0), 1);
    assert.ok(categories.every(category => category.weight > 0 && category.weight < 1));
    const assessmentIds = new Set();
    for (const category of categories) {
      assert.ok(['POOLED_POINTS', 'EQUAL_ITEMS', 'EXPLICIT_WEIGHTS'].includes(category.aggregation));
      assert.ok(Object.keys(category.assessments).length > 0);
      for (const [id, assessment] of Object.entries(category.assessments)) {
        assert.ok(!assessmentIds.has(id)); assessmentIds.add(id);
        assert.ok(assessment.possiblePoints > 0);
        assert.equal(assessment.earnedPoints, undefined);
      }
    }
    assert.equal(assessmentIds.size, 4);
  }
});
