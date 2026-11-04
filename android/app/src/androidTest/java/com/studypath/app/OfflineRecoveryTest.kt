package com.studypath.app

import android.os.Process
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.studypath.app.data.CatalogCourse
import com.studypath.app.data.AcademicRecordBinding
import com.studypath.app.data.DatabaseRecords
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.PracticeAttempt
import com.studypath.app.data.ReviewProgress
import com.studypath.app.data.ScoreRecord
import com.studypath.app.data.StudyModule
import com.studypath.app.ui.PracticeDraftStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Select each method separately, in the documented host-runner order, on StudyPath_Lite_Test.
 * The host removes ADB routes and disables guest networking between the online and offline
 * methods, then kills the app process before recovery. These tests never simulate disconnection,
 * clear app data, purge SDK writes or target a cloud Firebase project.
 *
 * The app-private fixture contains only disposable synthetic identifiers and expected records.
 * It deliberately excludes passwords and tokens. Assertions do not print account identifiers.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRecoveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val fixtureFile get() = File(app.filesDir, "b07-fixture.json")
    private val gson = Gson()

    @Before
    fun requireSelectedHostStage() {
        // A normal connected-test run cannot coordinate transport/process changes.
        // Only the host runner selects one method at a time in the required order.
        assumeTrue("Use android/test-offline.py for this staged acceptance suite",
            InstrumentationRegistry.getArguments().getString("class")?.startsWith(
                "com.studypath.app.OfflineRecoveryTest#") == true)
    }

    @Test
    fun prepareOnlineWorkspace() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            assertNull("This acceptance run needs a fresh disposable workspace", repository.boundUid())
            assertNull("A fresh workspace must not have a resumed account", repository.currentSession())
            assertTrue("A previous acceptance fixture must not be overwritten", !fixtureFile.exists())

            val email = "b07_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            val session = repository.register(email, "LocalOffline123!", "Offline Learner")
            File(app.filesDir, "b07-setup-identity.json").writeText(JSONObject().put("namespace", testNamespace)
                .put("uid", session.uid).toString())
            assertTrue("Registration must retain the original account binding", repository.boundUid() == session.uid)
            awaitValue("the online connection") { repository.connection().first { it } }
            repository.saveLearnerPreferences(LearnerPreferences("test_college", "test_curriculum_v1", setOf("computing"), onboardingComplete = true))

            val expectedScore = queuedScore()
            val expectedProgress = queuedProgress()
            repository.saveScore(expectedScore.copy(earnedPoints = 0.0))
            repository.saveProgress(expectedProgress.copy(completed = false, lastSection = 0))
            // Offline collection listeners need complete cached query results. Acknowledged child
            // writes alone do not prove the parent scores/practiceAttempts queries were loaded.
            val baselineScores = awaitValue("the complete online score collection") {
                repository.scores().first { rows -> rows.any { it.id == SCORE_ID && it.earnedPoints == 0.0 } }
            }
            assertTrue("The complete online score baseline must match", baselineScores.size == 1 && baselineScores.single().copy(updatedAt = null) == expectedScore.copy(earnedPoints = 0.0))
            val baselineProgress = awaitValue("the online progress baseline") {
                repository.reviewProgress(MODULE_ID, MODULE_VERSION).first { it != null }
            }
            assertTrue("The online progress baseline must match", baselineProgress?.copy(updatedAt = null) == expectedProgress.copy(completed = false, lastSection = 0))
            val baselineAttempts = awaitValue("the complete online practice collection") {
                repository.practiceAttempts().first()
            }
            assertTrue("The fresh online practice collection must be empty", baselineAttempts.isEmpty())
            val module = awaitValue("the full online module snapshot") { repository.pinModule(MODULE_ID) }
            assertTrue("The demonstration module must contain both complete sections", module.sections.size == 2 && module.sections.all { it.title.isNotBlank() && it.body.isNotBlank() })
            assertTrue("The demonstration module must contain two practice questions", module.version == MODULE_VERSION && module.practice.size == 2)
            val course = awaitValue("the complete online course catalog") {
                repository.courses().first { courses -> courses.any { it.id == COURSE_ID } }
            }
                .single { it.id == COURSE_ID }
            assertTrue("The seeded course must use the matching demonstration plan", course.moduleId == MODULE_ID && course.gradePlan?.version == PLAN_VERSION)

            val fixture = JSONObject()
                .put("schemaVersion", 1)
                .put("namespace", testNamespace)
                .put("uid", session.uid)
                .put("email", email)
                .put("moduleId", MODULE_ID)
                .put("moduleVersion", MODULE_VERSION)
                .put("scoreId", SCORE_ID)
                .put("expectedScore", JSONObject(DatabaseRecords.score(expectedScore)))
                .put("expectedProgress", JSONObject(gson.toJson(expectedProgress)))
                .put("expectedModule", JSONObject(gson.toJson(module)))
                .put("expectedCourse", JSONObject(gson.toJson(course)))
                .put("stagePids", JSONObject())
                .put("stages", JSONObject())
            markPassed(fixture, "prepareOnlineWorkspace")
        }
    }

    @Test
    fun queueWhileOffline() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("prepareOnlineWorkspace")
            resumeOriginalAccount(fixture)
            awaitValue("the offline connection") { repository.connection().first { !it } }
            val drafts = PracticeDraftStore(app, fixture.getString("uid"))
            assertNull("This run must create exactly one pending practice submission", drafts.read(MODULE_ID))
            val attempt = repository.newPracticeAttempt(MODULE_ID, MODULE_VERSION, 2, 2)
            drafts.retain(attempt)
            fixture.put("attempt", JSONObject(gson.toJson(attempt)))
            fixture.getJSONObject("stages").put("queueWhileOffline", "running")
            writeFixture(fixture)

            val writes = mapOf(
                "score" to async { runCatching { repository.saveScore(queuedScore()) } },
                "progress" to async { runCatching { repository.saveProgress(queuedProgress()) } },
                "practice" to async { runCatching { repository.submitPracticeAttempt(attempt) } },
            )
            val echoes = async { runCatching { assertQueuedRecords(attempt) } }
            try {
                select<Unit> {
                    echoes.onAwait { it.getOrThrow() }
                    writes.forEach { (name, deferred) -> deferred.onAwait { result ->
                        result.getOrThrow()
                        throw AssertionError("The $name write unexpectedly acknowledged while transport is disabled.")
                    } }
                }
                assertTrue("An offline local echo must not be treated as server acknowledgement", writes.values.all { it.isActive && !it.isCompleted })
                assertTrue("The exact immutable practice draft must remain on disk", drafts.read(MODULE_ID) == attempt)
                assertTrue("Firebase must remain disconnected while writes are queued", !awaitValue("the connection after queueing") { repository.connection().first() })
            } catch (error: Throwable) {
                val diagnostic = JSONObject().put("writeWaiters", JSONObject(writes.mapValues { (_, task) ->
                    JSONObject().put("active", task.isActive).put("completed", task.isCompleted)
                }))
                val operations = runCatching { withTimeoutOrNull(1_000) { repository.operationStates().first() }.orEmpty() }.getOrDefault(emptyList())
                diagnostic.put("operations", JSONArray(operations.map { JSONObject().put("path", it.relativePath).put("state", it.state.name) }))
                fixture.put("queueDiagnostic", diagnostic); writeFixture(fixture)
                throw AssertionError("B07 offline write/local echo failed: $diagnostic", error)
            } finally {
                // Cancellation stops awaiting the SDK Tasks; it does not purge their queued writes.
                echoes.cancelAndJoin()
                writes.values.forEach { it.cancelAndJoin() }
            }
            markPassed(fixture, "queueWhileOffline")
        }
    }

    @Test
    fun verifyQueuedAfterColdRestart() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("queueWhileOffline")
            assertTrue("Recovery must run in a different app process", Process.myPid() != fixture.getJSONObject("stagePids").getInt("queueWhileOffline"))
            resumeOriginalAccount(fixture)
            awaitValue("the offline connection after restart") { repository.connection().first { !it } }
            val attempt = fixtureAttempt(fixture)
            assertTrue("Process death must preserve the same immutable practice draft", PracticeDraftStore(app, fixture.getString("uid")).read(MODULE_ID) == attempt)
            assertQueuedRecords(attempt)

            val module = awaitValue("the complete cached module after restart") { repository.pinModule(MODULE_ID) }
            val expectedModule = gson.fromJson(fixture.getJSONObject("expectedModule").toString(), StudyModule::class.java)
            assertTrue("The complete pinned module must survive an offline cold restart", module == expectedModule)
            val expectedCourse = gson.fromJson(fixture.getJSONObject("expectedCourse").toString(), CatalogCourse::class.java)
            val course = awaitValue("the cached course catalog after restart") {
                repository.courses().first { rows -> rows.any { it.id == COURSE_ID } }
            }.single { it.id == COURSE_ID }
            assertTrue("The cached course and full grade plan must survive a cold restart", course == expectedCourse)
            assertTrue("Recovery must remain offline until the host reconnects", !awaitValue("the connection after cache recovery") { repository.connection().first() })
            markPassed(fixture, "verifyQueuedAfterColdRestart")
        }
    }

    @Test
    fun reconnectWithoutRetry() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("verifyQueuedAfterColdRestart")
            resumeOriginalAccount(fixture)
            awaitValue("the reconnected Firebase session") { repository.connection().first { it } }
            assertQueuedRecords(fixtureAttempt(fixture))
            assertTrue("Automatic SDK reconciliation must not discard the retry draft", PracticeDraftStore(app, fixture.getString("uid")).read(MODULE_ID) == fixtureAttempt(fixture))
            // No write is replayed here. The host independently inspects the emulator's records
            // after this method to prove automatic SDK queue flushing under the original UID.
            markPassed(fixture, "reconnectWithoutRetry")
        }
    }

    @Test
    fun retryExactPracticeAttempt() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("reconnectWithoutRetry")
            resumeOriginalAccount(fixture)
            awaitValue("the online session for the immutable retry") { repository.connection().first { it } }
            val drafts = PracticeDraftStore(app, fixture.getString("uid"))
            val attempt = requireNotNull(drafts.read(MODULE_ID)) { "The original retry draft must remain until its write is acknowledged" }
            assertTrue("Retry must preserve every immutable attempt field", attempt == fixtureAttempt(fixture))
            awaitValue("the exact practice retry acknowledgement") { repository.submitPracticeAttempt(attempt) }
            drafts.acknowledge(attempt)
            assertNull("Only an acknowledged submission may clear its retry draft", drafts.read(MODULE_ID))
            assertQueuedRecords(attempt)
            markPassed(fixture, "retryExactPracticeAttempt")
        }
    }

    private suspend fun resumeOriginalAccount(fixture: JSONObject) {
        val expectedUid = fixture.getString("uid")
        assertTrue("The original installation binding must survive", repository.boundUid() == expectedUid)
        val session = repository.resumeSession()
        assertTrue("Original bound account must resume", session?.uid == expectedUid)
        assertTrue("The original synthetic sign-in identity must remain", session?.email == fixture.getString("email"))
    }

    private suspend fun assertQueuedRecords(attempt: PracticeAttempt) {
        val score = awaitValue("the queued score collection") {
            repository.scores().first { rows -> rows.any { it.id == SCORE_ID && it.earnedPoints == 12.0 } }
        }
        assertTrue("Exactly one matching score must be present", score.size == 1 && score.single().copy(updatedAt = null) == queuedScore())
        val progress = awaitValue("the queued progress snapshot") {
            repository.reviewProgress(MODULE_ID, MODULE_VERSION).first { it?.completed == true }
        }
        assertTrue("The complete versioned review progress must remain", progress?.copy(updatedAt = null) == queuedProgress())
        val attempts = awaitValue("the queued practice collection") {
            repository.practiceAttempts().first { rows -> rows.any { it.id == attempt.id } }
        }
        assertTrue("Exactly one unchanged practice attempt must be present", attempts.size == 1 && attempts.single() == attempt)
    }

    private suspend fun <T> awaitValue(description: String, block: suspend () -> T): T = try {
        withTimeout(VALUE_TIMEOUT) { block() }
    } catch (error: TimeoutCancellationException) {
        throw AssertionError("B07 timed out waiting for $description.", error)
    }

    private fun requireLocalBuild() {
        assertTrue("B07 acceptance must use the local Firebase emulator build", BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue("Never target preview/default data with mock approvals", testNamespace.matches(Regex("demo-studypath-b07-offline-[a-f0-9]{8}")))
        assertTrue("The frozen APK must match this exact host namespace",
            InstrumentationRegistry.getArguments().getString("b07Namespace") == testNamespace)
    }

    private val testNamespace get() = BuildConfig.FIREBASE_DATABASE_URL.removePrefix("https://").removeSuffix(".firebaseio.com")
    private fun queuedScore() = ScoreRecord(SCORE_ID, COURSE_ID, "quiz_1", "quizzes", "SCORED", 20.0, 12.0, planVersion = PLAN_VERSION,
        policyBinding = AcademicRecordBinding("test_curriculum_v1", 1, "test_scores_ref", PLAN_VERSION))
    private fun queuedProgress() = ReviewProgress(MODULE_ID, MODULE_VERSION, completed = true, lastSection = 1)
    private fun fixtureAttempt(fixture: JSONObject): PracticeAttempt = gson.fromJson(fixture.getJSONObject("attempt").toString(), PracticeAttempt::class.java)

    private fun readFixture(requiredStage: String): JSONObject {
        assertTrue("The preceding B07 stage must have created its private fixture", fixtureFile.exists())
        return JSONObject(fixtureFile.readText()).also { fixture ->
            assertTrue("The B07 fixture format must match", fixture.getInt("schemaVersion") == 1)
            assertTrue("The B07 fixture namespace must match", fixture.getString("namespace") == testNamespace)
            assertTrue("The preceding B07 stage must have passed", fixture.getJSONObject("stages").optString(requiredStage) == "passed")
            assertTrue("The fixture must reference the intended seeded module and score", fixture.getString("moduleId") == MODULE_ID && fixture.getInt("moduleVersion") == MODULE_VERSION && fixture.getString("scoreId") == SCORE_ID)
        }
    }

    private fun markPassed(fixture: JSONObject, stage: String) {
        fixture.getJSONObject("stagePids").put(stage, Process.myPid())
        fixture.getJSONObject("stages").put(stage, "passed")
        writeFixture(fixture)
    }

    private fun writeFixture(fixture: JSONObject) {
        val target = AtomicFile(fixtureFile)
        val stream = target.startWrite()
        try {
            stream.write(fixture.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    companion object {
        private const val STAGE_TIMEOUT = 30_000L
        private const val VALUE_TIMEOUT = 8_000L
        private const val MODULE_ID = "programming_basics"
        private const val MODULE_VERSION = 1
        private const val COURSE_ID = "it101"
        private const val PLAN_VERSION = "demo_linear_v1"
        private const val SCORE_ID = "b07_score"
    }
}
