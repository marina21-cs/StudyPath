package com.studypath.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.CourseAttempt
import com.studypath.app.data.AcademicCapability
import com.studypath.app.data.AcademicRecordBinding
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.domain.PrerequisiteState
import com.studypath.app.ui.planner.PlannerAdapter
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

/** Explicitly selected local test; uses a separate Firebase app, namespace and preferences. */
@RunWith(AndroidJUnit4::class)
class PrerequisiteCatalogDeviceTest {
    @Test fun repositoryPrerequisiteFixture() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("class") == "com.studypath.app.PrerequisiteCatalogDeviceTest#repositoryPrerequisiteFixture")
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        val namespace = requireNotNull(args.getString("b13Namespace"))
        require(Regex("demo-studypath-b13-device-[a-f0-9]{12}").matches(namespace))
        val case = requireNotNull(args.getString("b13Case"))
        require(case in setOf("nested", "empty", "duplicate", "cycle", "malformed", "unavailable"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firebase = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setDatabaseUrl("https://$namespace.firebaseio.com").build(), "b13_${UUID.randomUUID()}")
        val auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
        val database = FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000)
            setPersistenceEnabled(false)
        }
        val preferencesName = "b13_test_${UUID.randomUUID()}"
        val repository = FirebaseStudyRepository(auth, database,
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE), reminderChanged = { _, _, _ -> })
        var primaryFailure: Throwable? = null
        try {
            withTimeout(90_000) {
                val session = repository.register("b13_${UUID.randomUUID()}@studypath.invalid", "B13-local-fixture-42", "Synthetic prerequisite fixture")
                val policy = repository.academicPolicies().first { it.isNotEmpty() }.single()
                assertTrue("This explicit device fixture must remain synthetic", policy.curriculumVersion.startsWith("b13_fixture"))
                val options = repository.learnerPreferenceOptions().first { it.isNotEmpty() }.single { it.curriculumVersion == policy.curriculumVersion }
                repository.saveLearnerPreferences(LearnerPreferences(options.levelId, options.curriculumVersion,
                    options.subjectIds, onboardingComplete = true))
                val courses = repository.courses().first { it.isNotEmpty() }
                val target = courses.single { it.id == "target" }
                val access = repository.academicAccess(target.id, AcademicCapability.PREREQUISITES)
                assertTrue(access.reason, access.allowed)
                val observations = JSONArray()
                if (case == "nested") {
                    assertNotNull("Native repository must parse the versioned nested field", target.prerequisiteExpression)
                    val cases = listOf(
                        listOf("PASSED", "FAILED", "PASSED") to PrerequisiteState.MET,
                        listOf("PASSED", "FAILED", "UNKNOWN") to PrerequisiteState.UNKNOWN,
                        listOf("FAILED", "PASSED", "PASSED") to PrerequisiteState.NOT_MET,
                        listOf("PASSED", "FAILED", "FAILED") to PrerequisiteState.NOT_MET,
                    )
                    for ((states, expected) in cases) {
                        for ((id, completion) in listOf("a", "b", "c").zip(states)) {
                            repository.saveCourseAttempt(CourseAttempt("attempt_$id", id, "synthetic", "COMPLETED", 3.0,
                                completionStatus = completion.takeUnless { it == "UNKNOWN" }))
                        }
                        val attempts = repository.courseAttempts().first { rows -> rows.size == 3 &&
                            listOf("a", "b", "c").zip(states).all { (id, status) ->
                                rows.single { it.courseId == id }.completionStatus == status.takeUnless { it == "UNKNOWN" }
                            } }
                        val result = PlannerAdapter.prerequisites(target, courses, attempts, access)
                        assertEquals(expected, result.state)
                        if (expected != PrerequisiteState.MET) assertTrue(result.reasons.isNotEmpty())
                        observations.put(JSONObject().put("expected", expected.name).put("actual", result.state.name)
                            .put("reasons", JSONArray(result.reasons)))
                    }
                } else {
                    val attempts = courses.map { CourseAttempt("attempt_${it.id}", it.id, "synthetic", "COMPLETED", 3.0, "PASSED",
                        policyBinding = AcademicRecordBinding(policy.curriculumVersion, policy.revision,
                            policy.capabilityApprovalRefs.getValue(AcademicCapability.PREREQUISITES), policy.coursePlans.getValue(it.id).planVersion)) }
                    val result = PlannerAdapter.prerequisites(target, courses, attempts, access)
                    assertEquals("Malformed catalog must not be satisfied by passing records", PrerequisiteState.UNKNOWN, result.state)
                    assertTrue(result.reasons.isNotEmpty())
                    observations.put(JSONObject().put("expected", "UNKNOWN").put("actual", result.state.name)
                        .put("reasons", JSONArray(result.reasons)))
                }
                File(context.filesDir, "b13-prerequisite-$case.json").writeText(JSONObject()
                    .put("case", case).put("namespace", namespace).put("uid", session.uid)
                    .put("courseCount", courses.size).put("observations", observations).toString())
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                auth.currentUser?.delete()?.await()
            } catch (cleanupError: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupError else original.addSuppressed(cleanupError)
            } finally {
                repository.logout()
                database.goOffline()
                firebase.delete()
                context.deleteSharedPreferences(preferencesName)
            }
        }
    }
}
