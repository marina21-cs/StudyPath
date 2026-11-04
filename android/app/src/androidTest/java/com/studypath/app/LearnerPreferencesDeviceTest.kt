package com.studypath.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.*
import com.studypath.app.ui.PracticeDraftStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Host selects stages separately, partitions transport and inspects exact local server records. */
@RunWith(AndroidJUnit4::class)
class LearnerPreferencesDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val database by lazy {
        FirebaseDatabase.getInstance().apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000) }
    }
    private val fixtureFile get() = File(app.filesDir, "b09-preferences-fixture.json")
    private val draft = LearnerPreferences("draft_level", "unselected_v1", setOf("draft_subject"), "unassigned_class")

    @Before fun selectedLocalStageOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.LearnerPreferencesDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
    }

    @Test fun prepareLegacyAndQueue() = runBlocking<Unit> {
        withTimeout(90_000) {
            assertNull(repository.boundUid())
            val email = "b09_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            val session = repository.register(email, PASSWORD, "Synthetic preference learner")
            val fixture = JSONObject().put("schemaVersion", 1).put("uid", session.uid).put("email", email)
                .put("attemptTime", System.currentTimeMillis()).put("observations", JSONObject())
            save(fixture)
            val owner = database.getReference("users").child(session.uid)
            val initial = owner.child("profile/learnerPreferences").get().await()
            assertEquals("New registration must persist explicit incomplete preferences", 1L, initial.child("schemaVersion").getValue(Long::class.java))
            assertEquals(false, initial.child("onboardingComplete").getValue(Boolean::class.java))
            observed(fixture, "newProfileIncomplete")
            repository.saveScore(score(0.0))
            repository.saveProgress(ReviewProgress(MODULE, 1, false, 0))
            repository.submitPracticeAttempt(PracticeAttempt("b09_existing_attempt", MODULE, 1, 1, 2, 42))
            repository.saveReading(reading())
            val numericSubjects = LearnerPreferences(subjectIds = setOf("0", "1"))
            repository.saveLearnerPreferences(numericSubjects)
            repository.learnerPreferences().first { it == numericSubjects }
            observed(fixture, "numericSubjectIdsRetained")
            owner.child("profile/learnerPreferences").removeValue().await()
            repository.learnerPreferences().first { it == LearnerPreferences() }
            assertEquals("bsit_demo_v1", owner.child("profile/selectedCurriculumVersion").get().await().value)
            assertFalse(owner.child("profile/learnerPreferences").get().await().exists())
            observed(fixture, "legacyReadDidNotRewriteProfile")
            // Warm complete collection queries before taking the SDK offline and killing its process.
            repository.scores().first { rows -> rows.any { it.id == SCORE } }
            repository.practiceAttempts().first { rows -> rows.size == 1 }
            repository.savedReadings().first { rows -> rows.size == 1 }
            repository.reviewProgress(MODULE, 1).first { it != null }
            repository.connection().first { it }
            fixture.put("readyForPartition", true); save(fixture)
            // The host disables actual guest transport before these writes and the next process.
            repository.connection().first { !it }
            val attempt = pending(fixture)
            PracticeDraftStore(app, session.uid).retain(attempt)
            val prefWrite = launch { repository.saveLearnerPreferences(draft) }
            val scoreWrite = launch { repository.saveScore(score(12.0)) }
            val attemptWrite = launch { repository.submitPracticeAttempt(attempt) }
            try {
                repository.learnerPreferences().first { it == draft }
                repository.scores().first { rows -> rows.any { it.id == SCORE && it.earnedPoints == 12.0 } }
                repository.practiceAttempts().first { rows -> rows.any { it.id == attempt.id } }
                assertFalse(prefWrite.isCompleted || scoreWrite.isCompleted || attemptWrite.isCompleted)
                observed(fixture, "writesQueuedWithoutAcknowledgement")
                fixture.put("stage1Pid", android.os.Process.myPid()); save(fixture)
            } finally { prefWrite.cancelAndJoin(); scoreWrite.cancelAndJoin(); attemptWrite.cancelAndJoin() }
        }
    }

    @Test fun verifyOfflineProcessRecovery() = runBlocking<Unit> {
        withTimeout(60_000) {
            val fixture = read()
            assertTrue(repository.resumeSession()?.uid == fixture.getString("uid"))
            assertTrue(android.os.Process.myPid() != fixture.getInt("stage1Pid"))
            repository.connection().first { !it }
            assertEquals(draft, repository.learnerPreferences().first())
            assertEquals("Synthetic preference learner", repository.profileName().first())
            assertTrue(repository.scores().first().any { it.id == SCORE && it.earnedPoints == 12.0 })
            assertTrue(repository.practiceAttempts().first().contains(pending(fixture)))
            assertEquals(pending(fixture), PracticeDraftStore(app, fixture.getString("uid")).read(MODULE))
            assertEquals(1, repository.savedReadings().first().size)
            assertEquals(ReviewProgress(MODULE, 1, false, 0), repository.reviewProgress(MODULE, 1).first()?.copy(updatedAt = null))
            observed(fixture, "offlinePreferencesAndExistingDataRecovered")
            observed(fixture, "pendingAttemptAndBindingRetained")
            fixture.put("stage2Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun reconcileAndVerifySessionBoundary() = runBlocking<Unit> {
        withTimeout(90_000) {
            val fixture = read(); val uid = fixture.getString("uid")
            assertTrue(repository.resumeSession()?.uid == uid)
            assertTrue(android.os.Process.myPid() != fixture.getInt("stage2Pid"))
            repository.connection().first { it }
            assertEquals(draft, repository.learnerPreferences().first())
            repository.saveLearnerPreferences(draft)
            repository.submitPracticeAttempt(pending(fixture))
            PracticeDraftStore(app, uid).acknowledge(pending(fixture))
            assertNull(PracticeDraftStore(app, uid).read(MODULE))
            var rejected = false
            try { repository.saveLearnerPreferences(draft.copy(onboardingComplete = true)) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Unpublished selections must not complete onboarding", rejected)
            assertEquals(draft, repository.learnerPreferences().first())
            observed(fixture, "exactRetryAcknowledgedAndUnsupportedCompletionRejected")
            val observerClosed = AtomicBoolean(false); val observerEvents = AtomicInteger()
            val observer = launch {
                try { repository.learnerPreferences().collect { observerEvents.incrementAndGet() } }
                catch (_: IllegalStateException) { observerClosed.set(true) }
            }
            while (observerEvents.get() == 0) delay(25)
            val eventsBeforeLogout = observerEvents.get()
            repository.logout()
            withTimeout(5_000) { observer.join() }
            assertTrue(observerClosed.get())
            var privateReadRejected = false; var privateWriteRejected = false
            try { repository.learnerPreferences().first() } catch (_: IllegalStateException) { privateReadRejected = true }
            try { repository.saveLearnerPreferences(draft) } catch (_: IllegalStateException) { privateWriteRejected = true }
            assertTrue(privateReadRejected && privateWriteRejected)
            assertTrue(repository.boundUid() == uid)
            assertTrue(repository.login(fixture.getString("email"), PASSWORD).uid == uid)
            assertEquals(draft, repository.learnerPreferences().first())
            assertEquals(eventsBeforeLogout, observerEvents.get())
            observed(fixture, "activePreferenceObserverClosedAtLogout")
            observed(fixture, "signedOutPreferenceAccessDeniedAndSameUidRestored")
            fixture.put("stage3Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    private fun observed(fixture: JSONObject, key: String) { fixture.getJSONObject("observations").put(key, true); save(fixture) }
    private fun save(fixture: JSONObject) { fixtureFile.writeText(fixture.toString()) }
    private fun read() = JSONObject(fixtureFile.readText())
    private fun pending(fixture: JSONObject) = PracticeAttempt("b09_pending_attempt", MODULE, 1, 0, 2, fixture.getLong("attemptTime"))
    private fun score(earned: Double) = ScoreRecord(SCORE, "it101", "b09_quiz", "quizzes", "SCORED", 20.0, earned, planVersion = "demo_linear_v1")
    private fun reading() = ReadingContent(5311, "Computer_programming", "B09 synthetic retained reading",
        "Original synthetic text for preference migration only; not a live API result.", 1379403934,
        "2026-10-09T20:17:41Z", "https://en.wikipedia.org/wiki/Computer_programming",
        "https://en.wikipedia.org/w/index.php?oldid=1379403934", "CC BY-SA 4.0",
        "https://creativecommons.org/licenses/by-sa/4.0/deed.en", "Synthetic test; Wikipedia contributors for source metadata.",
        "Synthetic text; retained source metadata only.", 42)

    companion object {
        private const val PASSWORD = "LocalPreference123!"
        private const val MODULE = "programming_basics"
        private const val SCORE = "b09_score"
    }
}
