package com.studypath.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.studypath.app.data.*
import com.studypath.app.domain.AcademicGwa
import com.studypath.app.domain.AcademicScenarios
import com.studypath.app.ui.planner.PlannerAdapter
import com.studypath.app.ui.MainActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.hamcrest.Matchers.equalTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Explicit local-only fixture: separate Firebase app, namespace, preferences, synthetic policies and accounts. */
@RunWith(AndroidJUnit4::class)
class AcademicPolicyDeviceTest {
    @Test fun nativePolicyAndPlannerContract() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("class") == "com.studypath.app.AcademicPolicyDeviceTest#nativePolicyAndPlannerContract")
        require(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        val namespace = requireNotNull(args.getString("v1AcademicNamespace"))
        require(namespace.matches(Regex("demo-studypath-v1-academic-[a-f0-9]{12}")))
        val fixtureCase = requireNotNull(args.getString("v1AcademicCase"))
        val caseToken = requireNotNull(args.getString("v1AcademicCaseToken"))
        require(caseToken.matches(Regex("[a-f0-9]{32}")))
        val uiCases = setOf("prerequisite_only", "gwa_only", "scenario_policy_rebase", "scenario_plan_rebase")
        require(fixtureCase in uiCases + setOf("missing", "mismatch", "withdrawn", "expired", "valid"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firebase = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setDatabaseUrl("https://$namespace.firebaseio.com").build(),
            "v1_academic_${UUID.randomUUID()}")
        val auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
        val database = FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000); setPersistenceEnabled(false)
        }
        val preferencesName = "v1_academic_test_${UUID.randomUUID()}"
        val fixtureRoot = File(context.filesDir, "v1-academic-${namespace.takeLast(12)}-$fixtureCase-$caseToken")
        val client = OkHttpClient()
        val repository = FirebaseStudyRepository(auth, database,
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
            packageStore = TextPackageStore(File(fixtureRoot, "packages")),
            lessonRunStore = LessonRunStore(File(fixtureRoot, "runs")),
            readingStore = ReadingStateStore(File(fixtureRoot, "readings")),
            readingContextNetwork = ReadingContextNetwork(client,
                "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000/?ns=$namespace".toHttpUrl()),
            packageAssetDownloader = PackageAssetDownloader(client, BuildConfig.API_USER_AGENT),
            packageDownloadDirectory = File(fixtureRoot, "downloads"),
            operationDirectory = File(fixtureRoot, "operations"), reminderChanged = { _, _, _ -> })
        val prefix = "v1-academic-${namespace.takeLast(12)}-$fixtureCase"
        val marker = File(context.filesDir, "$prefix-seed.json")
        val ready = File(context.filesDir, "$prefix-ready")
        val identityFile = File(context.filesDir, "$prefix-identity.json")
        val observations = JSONArray()
        var primary: Throwable? = null
        suspend fun denied(label: String, operation: suspend () -> Unit) {
            var failed = false
            try { operation() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
            assertTrue("Expected policy denial: $label", failed)
            observations.put(JSONObject().put("check", label).put("outcome", "DENIED"))
        }
        try {
            withTimeout(120_000) {
                marker.delete(); ready.delete()
                val session = repository.register("v1_academic_${UUID.randomUUID()}@studypath.invalid",
                    "V1-local-synthetic-42", "Synthetic academic fixture")
                val identity = JSONObject().put("namespace", namespace).put("case", fixtureCase).put("caseToken", caseToken)
                    .put("uid", session.uid).put("email", session.email).put("preferencesName", preferencesName)
                    .put("firebaseAppName", firebase.name).put("fixtureDirectory", fixtureRoot.name)
                identityFile.writeText(identity.toString())
                val options = repository.learnerPreferenceOptions().first { it.isNotEmpty() }
                    .single { it.curriculumVersion == "v1_academic_fixture" }
                repository.saveLearnerPreferences(LearnerPreferences(options.levelId, options.curriculumVersion,
                    options.subjectIds, onboardingComplete = true))
                val courses = repository.courses().first { it.size == 3 }
                val course = courses.single { it.id == "course" }
                val score = ScoreRecord("actual", course.id, "one", "quiz", "SCORED", 10.0, 8.0,
                    planVersion = "fixture_plan")
                val scoreAccess = repository.academicAccess(course.id, AcademicCapability.SCORES, "fixture_plan")
                if (fixtureCase in uiCases) {
                    marker.writeText(identity.toString())
                    withTimeout(30_000) { while (!ready.exists()) delay(100) }
                    exercisePlannerForms(context, repository, database, session.uid, fixtureCase, observations)
                } else if (fixtureCase != "valid") {
                    assertFalse("Unsupported policy must fail closed", scoreAccess.allowed)
                    assertNull(PlannerAdapter.evaluate(course, emptyList(), scoreAccess).summary)
                    denied("$fixtureCase-direct-score") { repository.saveScore(score) }
                    assertFalse(database.getReference("users/${session.uid}/scores/actual").get().await().exists())
                } else {
                    assertTrue(scoreAccess.reason, scoreAccess.allowed)
                    val policy = repository.academicPolicies().first { it.isNotEmpty() }.single()
                    assertTrue(policy.contextRef.startsWith("SYNTHETIC_TEST_ONLY"))
                    fun binding(capability: AcademicCapability) = AcademicRecordBinding(policy.curriculumVersion,
                        policy.revision, policy.capabilityApprovalRefs.getValue(capability), "fixture_plan")
                    val zero = score.copy(id = "score_zero", courseId = "zero_course", earnedPoints = 0.0)
                    repository.saveScore(zero)
                    val retainedZero = repository.scores().first { rows -> rows.any { it.id == zero.id } }.single { it.id == zero.id }
                    assertEquals(0.0, retainedZero.earnedPoints!!, 0.0)
                    assertEquals(binding(AcademicCapability.SCORES), retainedZero.policyBinding)
                    observations.put(JSONObject().put("check", "zero-preserved-exact-binding").put("outcome", "PASSED"))
                    denied("unknown-assessment") { repository.saveScore(score.copy(id = "unknown", assessmentId = "absent")) }
                    denied("possible-points-mismatch") { repository.saveScore(score.copy(id = "points", possiblePoints = 11.0)) }
                    denied("forged-client-policy-reference") { repository.saveScore(score.copy(id = "forged",
                        policyBinding = binding(AcademicCapability.SCORES).copy(policyRef = "forged-policy"))) }
                    val privateRef = database.getReference("users/${session.uid}")
                    denied("direct-rule-forged-policy-reference") {
                        privateRef.child("scores/rule_forged").setValue(DatabaseRecords.score(score) + mapOf(
                            "curriculumVersion" to policy.curriculumVersion, "policyRevision" to policy.revision,
                            "policyRef" to "forged-policy", "updatedAt" to ServerValue.TIMESTAMP)).await()
                    }
                    denied("client-cannot-publish-policy") {
                        database.getReference("catalog/academicPolicies/${policy.curriculumVersion}/approvalStatus").setValue("WITHDRAWN").await()
                    }
                    // Host owns the emulator Admin seed; the app never gains Admin permission.
                    marker.writeText(identity.toString())
                    withTimeout(30_000) { while (!ready.exists()) delay(100) }
                    val legacyBefore = privateRef.child("scores/legacy").get().await().value
                    assertNotNull(legacyBefore)
                    denied("legacy-record-cannot-rebind") { repository.saveScore(score.copy(id = "legacy", courseId = "legacy_course")) }
                    assertEquals(legacyBefore, privateRef.child("scores/legacy").get().await().value)

                    repository.saveScore(score)
                    val actual = repository.scores().first { rows -> rows.any { it.id == "actual" } }
                    val before = privateRef.child("scores/actual").get().await().value
                    val evaluation = PlannerAdapter.evaluate(course, actual, scoreAccess)
                    val scenarioAccess = repository.academicAccess(course.id, AcademicCapability.SCENARIOS)
                    val key = AcademicScenarios.overrideKey("quiz", "two")
                    val scenario = GradeScenario("scenario", course.id, "Synthetic what if", 75.0,
                        evaluation.fingerprint, overrides = mapOf(key to 50.0))
                    repository.saveScenario(scenario)
                    denied("direct-rule-unknown-override") {
                        privateRef.child("scenarios/rule_unknown_override").setValue(DatabaseRecords.scenario(scenario.copy(
                            overrides = mapOf("unknown_override" to 50.0))) + mapOf("curriculumVersion" to policy.curriculumVersion,
                            "policyRevision" to policy.revision, "policyRef" to policy.capabilityApprovalRefs.getValue(AcademicCapability.SCENARIOS),
                            "planVersion" to "fixture_plan", "updatedAt" to ServerValue.TIMESTAMP)).await()
                    }
                    val retainedScenario = repository.scenarios().first { it.any { row -> row.id == "scenario" } }.single { it.id == "scenario" }
                    assertEquals(binding(AcademicCapability.SCENARIOS), retainedScenario.policyBinding)
                    val hypothetical = AcademicScenarios.overlay(course, actual, retainedScenario.overrides, scoreAccess, scenarioAccess)
                    // Independently worked fixture arithmetic: (8 + 50% of 30)/(10+30)*100 = 57.5.
                    assertEquals(57.5, PlannerAdapter.evaluate(course, hypothetical, scoreAccess).summary!!.minimumFinalPercent!!, 1e-9)
                    assertEquals(before, privateRef.child("scores/actual").get().await().value)
                    assertEquals(evaluation.fingerprint, PlannerAdapter.evaluate(course, actual, scoreAccess).fingerprint)
                    observations.put(JSONObject().put("check", "actual-scenario-actual-unchanged").put("outcome", "PASSED")
                        .put("workedExpectedPercent", 57.5).put("overrideKey", key))
                    repository.saveScore(score.copy(earnedPoints = 5.0))
                    val edited = repository.scores().first { rows -> rows.any { it.id == "actual" && it.earnedPoints == 5.0 } }
                    val newFingerprint = PlannerAdapter.evaluate(course, edited, scoreAccess).fingerprint
                    assertFalse(AcademicScenarios.isCurrent(retainedScenario, newFingerprint, scenarioAccess))
                    denied("stale-scenario-save") { repository.saveScenario(retainedScenario.copy(title = "Stale replay")) }
                    observations.put(JSONObject().put("check", "base-edit-makes-scenario-stale").put("outcome", "PASSED"))

                    val attempt = CourseAttempt("gwa_one", course.id, "Synthetic term", "COMPLETED", 3.0,
                        grade = 2.0, scaleId = "fixture_numeric", includeInGwa = true)
                    denied("gwa-scale-mismatch") { repository.saveCourseAttempt(attempt.copy(id = "gwa_bad", scaleId = "other_scale")) }
                    repository.saveCourseAttempt(attempt)
                    repository.saveCourseAttempt(attempt.copy(id = "gwa_two", courseId = "zero_course", units = 2.0, grade = 1.5))
                    val attempts = repository.courseAttempts().first { it.size == 2 }
                    assertTrue(attempts.all { it.policyBinding == binding(AcademicCapability.GWA) })
                    val gwa = AcademicGwa.evaluate(attempts, courses.associate { it.id to repository.academicAccess(it.id, AcademicCapability.GWA) })
                    // Independently worked: (3*2.0 + 2*1.5)/(3+2) = 1.8.
                    assertEquals(1.8, gwa.average!!, 1e-9)
                    observations.put(JSONObject().put("check", "included-matching-gwa").put("outcome", "PASSED").put("workedExpectedGwa", 1.8))
                }
                File(context.filesDir, "$prefix-observation.json").writeText(JSONObject().put("case", fixtureCase)
                    .put("namespace", namespace).put("uid", session.uid).put("syntheticOnly", true)
                    .put("observations", observations).toString())
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try { auth.currentUser?.delete()?.await() }
            catch (error: Throwable) { val original = primary; if (original == null) throw error else original.addSuppressed(error) }
            finally {
                repository.logout(); database.goOffline(); firebase.delete()
                context.deleteSharedPreferences(preferencesName); marker.delete(); ready.delete()
            }
        }
    }

    /** Swap only the in-process graph; the normal installation's account binding and Auth instance stay untouched. */
    private suspend fun exercisePlannerForms(context: Context, repository: FirebaseStudyRepository,
                                            database: FirebaseDatabase, uid: String, fixtureCase: String,
                                            observations: JSONArray) {
        val app = context.applicationContext as StudyPathApplication
        val original = app.graph
        val graphField = StudyPathApplication::class.java.getDeclaredField("graph").apply { isAccessible = true }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { graphField.set(app, StudyPathGraph(repository, original.readings)) }
        val privateRef = database.getReference("users/$uid")
        val scoresBefore = privateRef.child("scores").get().await().value
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(20_000) {
                    var initialized = false
                    while (!initialized) {
                        scenario.onActivity { initialized = it.findViewById<BottomNavigationView>(R.id.navigation) != null }
                        if (!initialized) delay(100)
                    }
                }
                scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_planner }
                when (fixtureCase) {
                    "prerequisite_only" -> {
                        assertFalse(repository.academicAccess("course", AcademicCapability.SCORES).allowed)
                        assertFalse(repository.academicAccess("course", AcademicCapability.GWA).allowed)
                        awaitDisplayed("Add course attempt")
                        tap("Add course attempt")
                        onView(withHint("Term or school year")).perform(scrollTo(), replaceText("Prerequisite-only in progress"), closeSoftKeyboard())
                        onView(withContentDescription("Course attempt status")).perform(scrollTo(), click())
                        onData(equalTo("IN PROGRESS")).inRoot(isPlatformPopup()).perform(click())
                        tap("Save attempt")
                        val attempt = withTimeout(20_000) { repository.courseAttempts().first { rows ->
                            rows.any { it.term == "Prerequisite-only in progress" }
                        } }.single { it.term == "Prerequisite-only in progress" }
                        assertEquals("IN_PROGRESS", attempt.status)
                        assertFalse(attempt.includeInGwa)
                        assertNull(attempt.completionStatus)
                        assertEquals("SYNTHETIC_TEST_ONLY_PREREQUISITES", attempt.policyBinding?.policyRef)
                        observations.put(JSONObject().put("check", "ui-prerequisite-only-noncompleted-attempt").put("outcome", "PASSED")
                            .put("attemptId", attempt.id))
                    }
                    "gwa_only" -> {
                        assertFalse(repository.academicAccess("course", AcademicCapability.SCORES).allowed)
                        assertFalse(repository.academicAccess("course", AcademicCapability.PREREQUISITES).allowed)
                        val before = privateRef.child("courseAttempts/gwa_only").get().await()
                        assertEquals(true, before.child("includeInGwa").value)
                        awaitDisplayed("Edit attempt")
                        tap("Edit attempt")
                        onView(withText("Explicitly include this attempt in GWA")).check(matches(isChecked()))
                            .perform(scrollTo(), click())
                        tap("Save attempt")
                        val changed = withTimeout(20_000) { repository.courseAttempts().first { rows ->
                            rows.any { it.id == "gwa_only" && !it.includeInGwa }
                        } }.single { it.id == "gwa_only" }
                        assertEquals("SYNTHETIC_TEST_ONLY_GWA", changed.policyBinding?.policyRef)
                        assertEquals("COMPLETED", changed.status)
                        assertEquals(2.0, requireNotNull(changed.grade), 0.0)
                        assertNull(changed.completionStatus)
                        observations.put(JSONObject().put("check", "ui-gwa-only-uncheck-inclusion").put("outcome", "PASSED"))
                    }
                    else -> {
                        val old = privateRef.child("scenarios/retained_scenario").get().await().value
                        assertNotNull(old)
                        awaitDisplayed("What-if")
                        tap("What-if"); tap("Saved scenarios")
                        tap("Review and rebase scenario")
                        tap("Review current base")
                        tap("Save scenario")
                        val scenarios = withTimeout(20_000) { repository.scenarios().first { it.size == 2 } }
                        val replacement = scenarios.single { it.id != "retained_scenario" }
                        assertEquals("Retained policy scenario", replacement.title)
                        assertEquals(old, privateRef.child("scenarios/retained_scenario").get().await().value)
                        assertEquals(if (fixtureCase == "scenario_policy_rebase") 2 else 1, replacement.policyBinding?.policyRevision)
                        assertEquals(if (fixtureCase == "scenario_plan_rebase") "fixture_plan2" else "fixture_plan", replacement.policyBinding?.planVersion)
                        observations.put(JSONObject().put("check", "ui-$fixtureCase-new-id-old-unchanged").put("outcome", "PASSED")
                            .put("newScenarioId", replacement.id))
                    }
                }
                assertEquals(scoresBefore, privateRef.child("scores").get().await().value)
                awaitDisplayed("Saved", exact = true)
            }
        } finally {
            instrumentation.runOnMainSync { graphField.set(app, original) }
        }
    }

    private fun tap(text: String) {
        val target = onView(withText(text))
        try { target.check(matches(isCompletelyDisplayed())) }
        catch (_: AssertionError) { target.perform(scrollTo()) }
        target.perform(click())
    }

    private suspend fun awaitDisplayed(text: String, exact: Boolean = true) {
        withTimeout(20_000) {
            while (true) {
                try {
                    onView(if (exact) withText(text) else withText(org.hamcrest.Matchers.containsString(text)))
                        .perform(scrollTo()).check(matches(isDisplayed()))
                    return@withTimeout
                } catch (_: AssertionError) { delay(100) }
                catch (_: androidx.test.espresso.NoMatchingViewException) { delay(100) }
            }
        }
    }
}
