package com.studypath.app

import android.app.Activity
import android.app.NotificationManager
import android.os.Process
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.studypath.app.data.DatabaseRecords
import com.studypath.app.data.PracticeAttempt
import com.studypath.app.data.ReadingContent
import com.studypath.app.data.ReviewProgress
import com.studypath.app.data.ScoreRecord
import com.studypath.app.data.AcademicRecordBinding
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.StudyReminder
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.PracticeDraftStore
import com.studypath.app.ui.ReminderScheduler
import com.studypath.app.ui.StudyReminderWorker
import com.studypath.app.domain.ReminderScheduleIdentity
import com.studypath.app.ui.auth.LoginActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Host-coordinated B07.2 acceptance. Each method is selected separately on the disposable AVD. */
@RunWith(AndroidJUnit4::class)
class SessionIsolationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val workManager get() = WorkManager.getInstance(app)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val fixtureFile get() = File(app.filesDir, "b07-session-fixture.json")
    private val gson = Gson()

    @Before
    fun requireSelectedHostStage() {
        assumeTrue("Use the B07.2 host runner for this staged acceptance suite",
            InstrumentationRegistry.getArguments().getString("class")?.startsWith(
                "com.studypath.app.SessionIsolationTest#") == true)
    }

    @Test
    fun prepareSignedInWorkspace() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            assertNull("A fresh disposable installation is required", repository.boundUid())
            assertTrue("A previous private fixture must not be overwritten", !fixtureFile.exists())
            assertTrue("The host must enable notifications for this disposable test", ReminderScheduler.allowed(app))
            val otherEmail = InstrumentationRegistry.getArguments().getString("otherEmail").orEmpty()
            assertTrue("The host must supply a separate synthetic account", otherEmail.endsWith("@studypath.invalid"))
            val email = "session_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            assertTrue("The synthetic account identities must be distinct", email != otherEmail)
            val session = repository.register(email, TEST_PASSWORD, "Session Learner")
            File(app.filesDir, "b07-setup-identity.json").writeText(JSONObject().put("namespace", testNamespace)
                .put("uid", session.uid).toString())
            awaitValue("the online session") { repository.connection().first { it } }
            assertTrue("Registration must bind the original account", repository.boundUid() == session.uid)
            repository.saveLearnerPreferences(LearnerPreferences("test_college", "test_curriculum_v1", setOf("computing"), onboardingComplete = true))

            repository.saveScore(queuedScore().copy(earnedPoints = 0.0))
            repository.saveProgress(queuedProgress().copy(completed = false, lastSection = 0))
            awaitValue("the complete online score baseline") {
                repository.scores().first { rows -> rows.size == 1 && rows.single().earnedPoints == 0.0 }
            }
            awaitValue("the complete online progress baseline") {
                repository.reviewProgress(MODULE_ID, MODULE_VERSION).first { it?.completed == false && it.lastSection == 0 }
            }
            assertTrue("The complete online practice baseline must be empty",
                awaitValue("the complete online practice baseline") { repository.practiceAttempts().first() }.isEmpty())
            awaitValue("the original profile") { repository.profileName().first { it == "Session Learner" } }
            val reading = repository.saveReading(syntheticReading(), COURSE_ID)
            awaitValue("the complete private saved-reading collection") {
                repository.savedReadings().first { rows -> rows.size == 1 && rows.single().id == reading.id }
            }
            val module = repository.pinModule(MODULE_ID)
            assertTrue("The complete module must be cached before going offline", module.version == MODULE_VERSION && module.sections.size == 2 && module.practice.size == 2)
            awaitValue("the complete course catalog") { repository.courses().first { rows -> rows.any { it.id == COURSE_ID } } }

            val reminder = StudyReminder(REMINDER_ID, MODULE_ID, "B07 synthetic future reminder",
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1), true, "UTC")
            repository.saveReminder(reminder)
            val reminders = awaitValue("the complete reminder collection") { repository.reminders().first { rows -> rows.any { it.id == REMINDER_ID } } }
            ReminderScheduler.reconcile(app, session.uid, reminders)
            val future = awaitFutureReminder(session.uid)
            val postedWork = postRealNotification(session.uid, "b07_initial_notification")

            val fixture = JSONObject()
                .put("schemaVersion", 1).put("uid", session.uid).put("email", email).put("otherEmail", otherEmail)
                .put("namespace", testNamespace)
                .put("moduleId", MODULE_ID).put("moduleVersion", MODULE_VERSION).put("scoreId", SCORE_ID)
                .put("readingId", reading.id).put("reminderId", REMINDER_ID).put("dueAt", reminder.dueAt)
                .put("futureWorkId", future.id.toString()).put("postedWorkId", postedWork.toString())
                .put("expectedScore", JSONObject(DatabaseRecords.score(queuedScore())))
                .put("expectedProgress", JSONObject(DatabaseRecords.progress(queuedProgress())))
                .put("expectedReading", JSONObject(DatabaseRecords.reading(reading)))
                .put("expectedReminder", JSONObject(DatabaseRecords.reminder(reminder)))
                .put("stages", JSONObject()).put("stagePids", JSONObject()).put("observations", JSONObject())
            observe(fixture, "notificationPermissionGranted", true)
            observe(fixture, "completePrivateQueriesLoaded", true)
            observe(fixture, "futureReminderEnqueued", future.state == WorkInfo.State.ENQUEUED)
            observe(fixture, "realWorkerNotificationPosted", true)
            markPassed(fixture, "prepareSignedInWorkspace")
        }
    }

    @Test
    fun queueAndLogoutOffline() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("prepareSignedInWorkspace")
            assertTrue("The original account binding must remain", repository.boundUid() == fixture.getString("uid"))
            // MainActivity owns the single session activation before any private listener starts.
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitActivity(MainActivity::class.java)
                leaveInitialPreferences()
                assertOriginalSession(fixture)
                awaitValue("the real offline connection") { repository.connection().first { !it } }
                val uid = fixture.getString("uid")
                awaitCondition("the pending future reminder") {
                    workInfo(UUID.fromString(fixture.getString("futureWorkId")))?.state == WorkInfo.State.ENQUEUED
                }
                // Android force-stop removes posted notifications between host stages. Establish
                // a new real worker post while offline before testing production logout clearing.
                val offlinePosted = postRealNotification(uid, "b07_offline_pre_logout")
                fixture.put("offlinePostedWorkId", offlinePosted.toString())
                observe(fixture, "offlinePreLogoutNotificationPosted", true)
                val collectors = listOf(
                    track(this, repository.scores()), track(this, repository.reviewProgress(MODULE_ID, MODULE_VERSION)),
                    track(this, repository.practiceAttempts()), track(this, repository.profileName()),
                )
                val writes = mutableListOf<Job>()
                try {
                    awaitCondition("all private observers to receive the original workspace") { collectors.all { it.events.get() > 0 } }
                    val drafts = PracticeDraftStore(app, uid)
                    assertNull("This run must retain one practice submission", drafts.read(MODULE_ID))
                    val attempt = repository.newPracticeAttempt(MODULE_ID, MODULE_VERSION, 2, 2)
                    drafts.retain(attempt)
                    fixture.put("attempt", JSONObject(gson.toJson(attempt)))
                    fixture.getJSONObject("stages").put("queueAndLogoutOffline", "running")
                    writeFixture(fixture)
                    writes += launch { repository.saveScore(queuedScore()) }
                    writes += launch { repository.saveProgress(queuedProgress()) }
                    writes += launch { repository.submitPracticeAttempt(attempt) }
                    assertQueuedRecords(attempt)
                    assertTrue("Offline local writes must still lack server acknowledgement", writes.all { it.isActive && !it.isCompleted })
                    awaitCondition("the existing private observers to consume the queued local echoes") {
                        collectors.take(3).all { it.events.get() >= 2 }
                    }
                    assertTrue("All private observers must remain active until the real logout", collectors.all { it.job.isActive && !it.closedBySession.get() })
                    observe(fixture, "privateObserversActiveWithQueuedEcho", true)
                    observe(fixture, "offlineWritesPendingAtLogout", true)
                    openSignOutConfirmation()
                    onView(withText("Sign out")).perform(scrollTo(), click())
                    val countsAtLogout = collectors.map { it.events.get() }
                    awaitActivity(LoginActivity::class.java)
                    awaitText("Welcome back")
                    observe(fixture, "loginShownAfterLogout", true)
                    assertLoggedOut(fixture)
                    awaitCondition("private listeners to close on logout") { collectors.all { it.job.isCompleted && it.closedBySession.get() } }
                    observe(fixture, "privateListenersClosed", true)
                    delay(500)
                    assertTrue("No private listener may emit after the logout action", collectors.map { it.events.get() } == countsAtLogout)
                    observe(fixture, "noPrivateCallbacksAfterLogout", true)
                    observe(fixture, "lateProfileCallbacksBlocked", collectors.last().events.get() == countsAtLogout.last())
                    assertTrue("Logout must retain the original immutable draft", drafts.read(MODULE_ID) == attempt)
                    observe(fixture, "draftRetainedAtLogout", true)
                    awaitCondition("the future reminder cancellation") {
                        workInfo(UUID.fromString(fixture.getString("futureWorkId")))?.state == WorkInfo.State.CANCELLED
                    }
                    awaitCondition("all account-tagged work to finish cancellation") { taggedWork(uid).all { it.state.isFinished } }
                    assertNoNotificationsFor(500)
                    observe(fixture, "futureReminderCancelled", true)
                    observe(fixture, "postedNotificationsCleared", true)
                    pressBackUnconditionally()
                    delay(300)
                    assertNoPrivateUi()
                    observe(fixture, "backDidNotRevealPrivateUi", true)
                    markPassed(fixture, "queueAndLogoutOffline")
                } finally {
                    withContext(NonCancellable) {
                        writes.forEach { it.cancelAndJoin() }
                        collectors.forEach { it.job.cancelAndJoin() }
                    }
                }
            }
        }
    }

    @Test
    fun verifySignedOutAfterReconnect() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("queueAndLogoutOffline")
            assertLoggedOut(fixture)
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitActivity(LoginActivity::class.java)
                awaitText("Welcome back")
                assertLoggedOut(fixture)
                assertNoPrivateUi()
                assertPrivateAccessDenied()
                val draft = PracticeDraftStore(app, fixture.getString("uid")).read(MODULE_ID)
                assertTrue("The signed-out installation must retain the original draft", draft == fixtureAttempt(fixture))
                assertNoNotificationsFor(1_000)
                observe(fixture, "privateMainRedirectedToLogin", true)
                observe(fixture, "loggedOutAfterReconnect", true)
                observe(fixture, "privateAccessDenied", true)
                observe(fixture, "signedOutDraftRetained", true)
                markPassed(fixture, "verifySignedOutAfterReconnect")
            }
        }
    }

    @Test
    fun rejectOtherAndRestoreOriginal() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("verifySignedOutAfterReconnect")
            assertLoggedOut(fixture)
            ActivityScenario.launch(LoginActivity::class.java).use {
                awaitActivity(LoginActivity::class.java)
                signInThroughUi(fixture.getString("otherEmail"))
                awaitText(WRONG_ACCOUNT_MESSAGE)
                onView(withText(WRONG_ACCOUNT_MESSAGE)).check(matches(isDisplayed()))
                assertLoggedOut(fixture)
                assertNoPrivateUi()
                observe(fixture, "otherAccountRejected", true)
                signInThroughUi(fixture.getString("email"))
                awaitActivity(MainActivity::class.java)
                leaveInitialPreferences()
                assertOriginalSession(fixture)
                awaitValue("the original account reconnecting") { repository.connection().first { it } }
                val attempt = fixtureAttempt(fixture)
                assertQueuedRecords(attempt)
                onView(withId(R.id.nav_saved)).perform(click())
                awaitText(READING_TITLE)
                onView(withText(READING_TITLE)).perform(scrollTo()).check(matches(isDisplayed()))
                observe(fixture, "retainedPrivateReadingVisible", true)
                val drafts = PracticeDraftStore(app, fixture.getString("uid"))
                assertTrue("The same immutable draft must be restored", drafts.read(MODULE_ID) == attempt)
                awaitValue("the exact original practice retry acknowledgement") { repository.submitPracticeAttempt(attempt) }
                drafts.acknowledge(attempt)
                assertNull("The acknowledged draft must be removed", drafts.read(MODULE_ID))
                observe(fixture, "originalAccountRestored", true)
                observe(fixture, "immutablePracticeRetryAcknowledged", true)
                observe(fixture, "draftClearedAfterAck", true)
                markPassed(fixture, "rejectOtherAndRestoreOriginal")
            }
        }
    }

    @Test
    fun workerLogoutOverlap() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("rejectOtherAndRestoreOriginal")
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitActivity(MainActivity::class.java)
                leaveInitialPreferences()
                assertOriginalSession(fixture)
                val uid = fixture.getString("uid")
                val future = awaitFutureReminder(uid)
                fixture.put("overlapFutureWorkId", future.id.toString())
                postRealNotification(uid, "b07_overlap_notification")
                openSignOutConfirmation()
                val confirmation = AtomicReference<View>()
                onView(withText("Sign out")).check { view, error ->
                    if (error != null) throw error
                    assertTrue("The actual sign-out confirmation must be visible", view is TextView && view.text.toString() == "Sign out" && view.isShown)
                    confirmation.set(view)
                }
                val burstIds = ConcurrentHashMap.newKeySet<UUID>()
                val runningObserved = AtomicBoolean(false)
                val attemptedClick = AtomicBoolean(false)
                val clicked = AtomicBoolean(false)
                val clickCompleted = AtomicBoolean(false)
                val live = workManager.getWorkInfosByTagLiveData(accountTag(uid))
                val observer = Observer<List<WorkInfo>> { work ->
                    if (work.orEmpty().any { it.id in burstIds && it.state == WorkInfo.State.RUNNING } && attemptedClick.compareAndSet(false, true)) {
                        runningObserved.set(true)
                        // LiveData runs on main. This invokes the visible production confirmation
                        // button while RUNNING is observed; no worker hooks or artificial sleeps.
                        try {
                            clicked.set(confirmation.get().performClick())
                        } finally {
                            clickCompleted.set(true)
                        }
                    }
                }
                mainThreadValue { live.observeForever(observer) }
                try {
                    repeat(3) {
                        if (!runningObserved.get()) {
                            val burst = (1..8).map {
                                // Explicit local scheduler component fixtures; server target ownership is tested separately.
                                val row = StudyReminder("b07_overlap_${UUID.randomUUID()}", MODULE_ID,
                                    "B07 worker overlap component", System.currentTimeMillis() - 1, true, "UTC")
                                ReminderScheduler.update(app, uid, row.id, row)
                                OneTimeWorkRequestBuilder<StudyReminderWorker>()
                                    .setInputData(workDataOf("uid" to uid, "id" to row.id,
                                        "scheduleIdentity" to ReminderScheduleIdentity.fingerprint(row)))
                                    .addTag(accountTag(uid)).build().also { request -> burstIds += request.id }
                            }
                            workManager.enqueue(burst).result.get(5, TimeUnit.SECONDS)
                            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (!runningObserved.get() && System.nanoTime() < end) delay(25)
                        }
                    }
                    assertTrue("A real reminder worker must be observed RUNNING during the logout check", runningObserved.get())
                    awaitCondition("the actual sign-out confirmation click to complete") { clickCompleted.get() }
                    assertTrue("The observed execution must invoke the actual sign-out confirmation", clicked.get())
                    awaitActivity(LoginActivity::class.java)
                    assertLoggedOut(fixture)
                    awaitCondition("the overlapping future reminder cancellation") { workInfo(future.id)?.state == WorkInfo.State.CANCELLED }
                    awaitCondition("all real overlapping workers to become terminal") { taggedWork(uid).all { it.state.isFinished } }
                    assertNoNotificationsFor(1_500)
                    assertNoPrivateUi()
                    observe(fixture, "overlapRunningObserved", true)
                    observe(fixture, "overlapConfirmationClickedOnRunning", true)
                    observe(fixture, "overlapTaggedWorkTerminal", true)
                    observe(fixture, "overlapNotificationsCleared", true)
                    observe(fixture, "loggedOutAfterOverlap", true)
                    fixture.getJSONObject("observations").put("overlapRequestsEnqueued", burstIds.size)
                    fixture.getJSONObject("observations").put("postTerminalObservationMillis", 1_500)
                    markPassed(fixture, "workerLogoutOverlap")
                } finally {
                    mainThreadValue { live.removeObserver(observer) }
                }
            }
        }
    }

    private class PrivateCollector {
        val events = AtomicInteger()
        val closedBySession = AtomicBoolean(false)
        lateinit var job: Job
    }

    private fun track(scope: CoroutineScope, flow: Flow<*>): PrivateCollector = PrivateCollector().also { result ->
        result.job = scope.launch {
            try { flow.collect { result.events.incrementAndGet() } }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalStateException) { result.closedBySession.set(true) }
        }
    }

    private suspend fun postRealNotification(uid: String, id: String): UUID {
        assertTrue("Study notifications must be enabled for the real worker", ReminderScheduler.allowed(app))
        // Exercise a real worker with a tracked target generation, including while offline.
        // This direct scheduler fixture is not evidence that the repository accepted a server-owned target.
        val row = StudyReminder(id, MODULE_ID, "B07 notification component", System.currentTimeMillis() - 1, true, "UTC")
        ReminderScheduler.update(app, uid, id, row)
        val request = OneTimeWorkRequestBuilder<StudyReminderWorker>()
            .setInputData(workDataOf("uid" to uid, "id" to id, "scheduleIdentity" to ReminderScheduleIdentity.fingerprint(row)))
            .addTag(accountTag(uid)).build()
        workManager.enqueueUniqueWork("study_reminder_${uid}_${id}", ExistingWorkPolicy.REPLACE, request).result.get(5, TimeUnit.SECONDS)
        awaitCondition("the real notification worker to finish") { workInfo(request.id)?.state == WorkInfo.State.SUCCEEDED }
        awaitCondition("a real worker notification to be posted") { notifications.activeNotifications.any { it.id == (uid + id).hashCode() } }
        return request.id
    }

    private suspend fun awaitFutureReminder(uid: String): WorkInfo {
        var value: WorkInfo? = null
        awaitCondition("a future reminder to be ENQUEUED by ReminderScheduler") {
            value = workManager.getWorkInfosForUniqueWork(accountTag(uid) + "_" + REMINDER_ID)
                .get(3, TimeUnit.SECONDS).firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            value != null
        }
        return requireNotNull(value)
    }

    private fun taggedWork(uid: String): List<WorkInfo> = workManager.getWorkInfosByTag(accountTag(uid)).get(3, TimeUnit.SECONDS)
    private fun workInfo(id: UUID): WorkInfo? = workManager.getWorkInfoById(id).get(3, TimeUnit.SECONDS)
    private fun accountTag(uid: String) = "study_reminder_$uid"

    private suspend fun assertNoNotificationsFor(duration: Long) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration)
        do {
            assertTrue("Signed-out study notifications must remain absent", notifications.activeNotifications.isEmpty())
            delay(50)
        } while (System.nanoTime() < end)
    }

    private suspend fun openSignOutConfirmation() {
        onView(withId(R.id.nav_account)).perform(click())
        awaitText("Session Learner")
        onView(withText("Account details")).perform(scrollTo(), click())
        onView(withText("Sign out")).perform(scrollTo(), click())
        onView(withText("Sign out")).check(matches(withText("Sign out")))
    }

    private suspend fun leaveInitialPreferences() {
        // Main activates the original session before installing its navigation/content.
        awaitCondition("the resumed Main workspace") {
            mainThreadValue { resumed(MainActivity::class.java)?.findViewById<View>(R.id.nav_account) != null }
        }
        val preferences = awaitValue("the current learner selection") { repository.learnerPreferences().first() }
        if (preferences.onboardingComplete) {
            assertTrue("The current mock policy selection must remain intact",
                preferences.levelId == "test_college" && preferences.curriculumVersion == "test_curriculum_v1" &&
                    preferences.subjectIds == setOf("computing"))
            awaitText("What will you learn?")
            awaitCondition("visible study navigation") {
                mainThreadValue { resumed(MainActivity::class.java)?.findViewById<View>(R.id.nav_account)?.isShown == true }
            }
            return
        }
        // Preserve the original empty-preference branch for an actual onboarding screen.
        awaitCondition("loaded initial preferences") {
            mainThreadValue {
                val root = resumed(MainActivity::class.java)?.window?.decorView ?: return@mainThreadValue false
                root.findViewWithTag<View>("learner_preferences") != null &&
                    root.findViewWithTag<View>("preference_load_status")?.visibility == View.GONE &&
                    root.findViewWithTag<View>("preference_later")?.isEnabled == true
            }
        }
        onView(withText("Maybe later")).perform(scrollTo(), click())
        awaitText("What will you learn?")
        awaitCondition("visible study navigation") {
            mainThreadValue { resumed(MainActivity::class.java)?.findViewById<View>(R.id.nav_account)?.isShown == true }
        }
    }

    private suspend fun signInThroughUi(email: String) {
        awaitCondition("enabled sign-in controls") {
            mainThreadValue { resumed(LoginActivity::class.java)?.findViewById<View>(R.id.signInButton)?.isEnabled == true }
        }
        mainThreadValue {
            val activity = requireNotNull(resumed(LoginActivity::class.java))
            activity.findViewById<EditText>(R.id.emailInput).setText(email)
            activity.findViewById<EditText>(R.id.passwordInput).setText(TEST_PASSWORD)
        }
        closeSoftKeyboard()
        onView(withId(R.id.signInButton)).perform(scrollTo(), click())
    }

    private suspend fun assertPrivateAccessDenied() {
        suspend fun denied(block: suspend () -> Unit) {
            var rejected = false
            try { awaitValue("the signed-out authorization boundary") { block() } }
            catch (error: IllegalStateException) { rejected = true }
            assertTrue("Signed-out private access must fail before reaching Firebase", rejected)
        }
        denied { repository.connection().first() }
        denied { repository.scores().first() }
        denied { repository.profileName().first() }
        denied { repository.savedReadings().first() }
        denied { repository.saveScore(queuedScore()) }
    }

    private suspend fun assertQueuedRecords(attempt: PracticeAttempt) {
        val scores = awaitValue("the queued score collection") { repository.scores().first { rows -> rows.any { it.id == SCORE_ID && it.earnedPoints == 12.0 } } }
        assertTrue("The original score must retain its queued value", scores.size == 1 && scores.single().copy(updatedAt = null) == queuedScore())
        val progress = awaitValue("the queued review progress") { repository.reviewProgress(MODULE_ID, MODULE_VERSION).first { it?.completed == true } }
        assertTrue("The original versioned progress must remain complete", progress?.copy(updatedAt = null) == queuedProgress())
        val attempts = awaitValue("the queued immutable practice result") { repository.practiceAttempts().first { rows -> rows.any { it.id == attempt.id } } }
        assertTrue("The original immutable practice result must remain unique", attempts.size == 1 && attempts.single() == attempt)
    }

    private fun assertOriginalSession(fixture: JSONObject) {
        assertTrue("Original bound account must resume", repository.currentSession()?.uid == fixture.getString("uid"))
        assertTrue("The original installation binding must remain", repository.boundUid() == fixture.getString("uid"))
    }

    private fun assertLoggedOut(fixture: JSONObject) {
        assertNull("The private repository session must end", repository.currentSession())
        assertNull("Firebase Authentication must also be signed out", FirebaseAuth.getInstance().currentUser)
        assertTrue("Logout must retain the original installation binding", repository.boundUid() == fixture.getString("uid"))
    }

    private fun assertNoPrivateUi() = mainThreadValue {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val activities = Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }
        assertTrue("The private main activity must not remain on the back stack", activities.none { it is MainActivity })
        val root = currentRoot()
        assertTrue("Signed-out screens must not contain private profile or reading text",
            root == null || !containsText(root) { it.contains("Session Learner") || it.contains(READING_TITLE) })
    }

    private suspend fun awaitActivity(type: Class<out Activity>) = awaitCondition("${type.simpleName} to resume") { mainThreadValue { resumed(type) != null } }
    private suspend fun awaitText(text: String) = awaitCondition("the expected screen text") { mainThreadValue { currentRoot()?.let { containsText(it) { value -> value == text } } == true } }
    private fun resumed(type: Class<out Activity>): Activity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
    private fun currentRoot(): View? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
    private fun containsText(view: View, condition: (String) -> Boolean): Boolean =
        (view is TextView && view.isShown && condition(view.text.toString())) ||
            (view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), condition) })

    private fun <T> mainThreadValue(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private suspend fun awaitCondition(description: String, condition: () -> Boolean) = awaitValue(description) {
        while (!condition()) delay(25)
    }

    private suspend fun <T> awaitValue(description: String, block: suspend () -> T): T = try {
        withTimeout(VALUE_TIMEOUT) { block() }
    } catch (error: TimeoutCancellationException) {
        throw AssertionError("B07.2 timed out waiting for $description.", error)
    }

    private fun syntheticReading() = ReadingContent(
        5311, "Computer_programming", READING_TITLE,
        "Synthetic StudyPath test text only. This fixture verifies private offline reading isolation and is not a live API result.",
        1379403934, "2026-10-09T20:17:41Z", "https://en.wikipedia.org/wiki/Computer_programming",
        "https://en.wikipedia.org/w/index.php?oldid=1379403934", "Creative Commons Attribution-Share Alike 4.0",
        "https://creativecommons.org/licenses/by-sa/4.0/deed.en",
        "StudyPath synthetic test content; Wikipedia contributors are attributed for the retained source metadata.",
        "Synthetic original test text; source metadata from the recorded fixture. No live reading fetch was performed.",
        System.currentTimeMillis(),
    )

    private val testNamespace get() = BuildConfig.FIREBASE_DATABASE_URL.removePrefix("https://").removeSuffix(".firebaseio.com")
    private fun queuedScore() = ScoreRecord(SCORE_ID, COURSE_ID, "quiz_1", "quizzes", "SCORED", 20.0, 12.0, planVersion = "demo_linear_v1",
        policyBinding = AcademicRecordBinding("test_curriculum_v1", 1, "test_scores_ref", "demo_linear_v1"))
    private fun queuedProgress() = ReviewProgress(MODULE_ID, MODULE_VERSION, true, 1)
    private fun fixtureAttempt(fixture: JSONObject): PracticeAttempt = gson.fromJson(fixture.getJSONObject("attempt").toString(), PracticeAttempt::class.java)
    private fun requireLocalBuild() {
        assertTrue("This staged acceptance must use the local demonstration Firebase build",
            BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue("Never target preview/default data with mock approvals", testNamespace.matches(Regex("demo-studypath-b07-session-[a-f0-9]{8}")))
        assertTrue("The frozen APK must match this exact host namespace",
            InstrumentationRegistry.getArguments().getString("b07Namespace") == testNamespace)
    }

    private fun readFixture(requiredStage: String): JSONObject {
        assertTrue("The preceding stage must have created its private fixture", fixtureFile.exists())
        return JSONObject(fixtureFile.readText()).also {
            assertTrue("The private fixture version must match", it.getInt("schemaVersion") == 1)
            assertTrue("The private fixture namespace must match", it.getString("namespace") == testNamespace)
            assertTrue("The preceding stage must have passed", it.getJSONObject("stages").optString(requiredStage) == "passed")
            assertTrue("The fixture must retain the intended stable paths", it.getString("scoreId") == SCORE_ID && it.getString("moduleId") == MODULE_ID && it.getInt("moduleVersion") == MODULE_VERSION && it.getString("reminderId") == REMINDER_ID)
        }
    }

    private fun observe(fixture: JSONObject, name: String, value: Boolean) { fixture.getJSONObject("observations").put(name, value) }
    private fun markPassed(fixture: JSONObject, stage: String) {
        fixture.getJSONObject("stages").put(stage, "passed")
        fixture.getJSONObject("stagePids").put(stage, Process.myPid())
        writeFixture(fixture)
    }

    private fun writeFixture(fixture: JSONObject) {
        val target = AtomicFile(fixtureFile)
        val stream = target.startWrite()
        try { stream.write(fixture.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }

    companion object {
        private const val STAGE_TIMEOUT = 90_000L
        private const val VALUE_TIMEOUT = 12_000L
        private const val TEST_PASSWORD = "LocalOffline123!"
        private const val MODULE_ID = "programming_basics"
        private const val MODULE_VERSION = 1
        private const val COURSE_ID = "it101"
        private const val SCORE_ID = "b07_session_score"
        private const val REMINDER_ID = "b07_future_reminder"
        private const val READING_TITLE = "B07 synthetic private reading"
        private const val WRONG_ACCOUNT_MESSAGE = "This installation is linked to its original account. Sign in with that account."
    }
}
