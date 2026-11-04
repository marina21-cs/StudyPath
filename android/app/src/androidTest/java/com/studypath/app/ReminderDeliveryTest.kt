package com.studypath.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.DatePicker
import android.widget.TextView
import android.widget.TimePicker
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.studypath.app.data.DatabaseRecords
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.ReadingContent
import com.studypath.app.data.ReminderTarget
import com.studypath.app.data.StudyReminder
import com.studypath.app.domain.ReminderScheduleIdentity
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.ReminderScheduler
import com.studypath.app.ui.StudyReminderWorker
import com.studypath.app.ui.dateLabel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.allOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/** API 36 acceptance selected one method at a time by the Linux host runner. */
@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val workManager get() = WorkManager.getInstance(app)
    private val fixtureFile get() = File(app.filesDir, "b07-reminders-fixture.json")

    @Before
    fun requireSelectedHostStage() {
        assumeTrue("Use the B07.3 host runner for coordinated permission/process boundaries",
            InstrumentationRegistry.getArguments().getString("class")?.startsWith(
                "com.studypath.app.ReminderDeliveryTest#") == true)
    }

    @Test
    fun prepareAndDenyRuntimePermission() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            assertNull("This suite requires a fresh disposable workspace", repository.boundUid())
            assertTrue("A prior private fixture must not be overwritten", !fixtureFile.exists())
            assertTrue("The host must revoke the runtime permission before this stage", !runtimePermissionGranted())
            val email = "reminders_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            // Persist intent before Auth: a crash after remote creation can still clean exactly this new email/UID.
            val setupIdentity = JSONObject().put("namespace", testNamespace).put("runToken", runToken).put("email", email)
            writeAtomic(File(app.filesDir, "b07-reminders-setup-identity.json"), setupIdentity)
            val session = repository.register(email, "LocalOffline123!", "Reminder Learner")
            writeAtomic(File(app.filesDir, "b07-reminders-setup-identity.json"), setupIdentity.put("uid", session.uid))
            repository.saveLearnerPreferences(LearnerPreferences("test_college", "test_curriculum_v1", setOf("computing"), onboardingComplete = true))
            val reading = repository.saveReading(syntheticReading())
            val retargetReading = repository.saveReading(syntheticReading(retarget = true))
            assertTrue("Retarget sources must have distinct acknowledged reading identities", reading.id != retargetReading.id)
            awaitValue("the complete empty reminder baseline") { repository.reminders().first { it.isEmpty() } }
            val fixture = JSONObject().put("schemaVersion", 1).put("uid", session.uid).put("email", email)
                .put("namespace", testNamespace).put("runToken", runToken).put("readingId", reading.id)
                .put("retargetReadingId", retargetReading.id).put("androidApi", Build.VERSION.SDK_INT)
                .put("expectedReminders", JSONObject()).put("deliveryObservations", JSONArray())
                .put("stages", JSONObject()).put("stagePids", JSONObject()).put("observations", JSONObject())
            withMain(fixture) {
                // Current product preserves due items without notification permission. This requests the real
                // OS prompt from test code; it does not claim the module UI emitted that prompt.
                mainThreadValue { requireNotNull(resumed(MainActivity::class.java)).requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301) }
                val deny = awaitSystemNode("the actual runtime permission dialog") {
                    it.viewIdResourceName == "com.android.permissioncontroller:id/permission_deny_button" ||
                        it.text?.toString() in setOf("Don't allow", "Don’t allow")
                }
                clickSystemNode(deny)
                awaitCondition("the denied permission state") { !runtimePermissionGranted() && !ReminderScheduler.allowed(app) }
                val reminder = createThroughReadingUi(nextMinuteAtLeast(TimeUnit.HOURS.toMillis(1)), dismissConfirmation = false)
                assertBlockedDueReminder(reminder)
                onView(withText("Notification settings")).inRoot(isDialog()).perform(click())
                awaitNotificationRecovery()
                assertTrue("Runtime denial must not produce a notification", manager.activeNotifications.isEmpty())
                observe(fixture, "testRequestedRuntimePromptActuallyDenied", true)
                observe(fixture, "runtimeDenialRecoveryVisible", true)
                observe(fixture, "runtimeDenialDueRecordRetainedWithoutNotification", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "prepareAndDenyRuntimePermission")
        }
    }

    @Test
    fun verifyAppNotificationsBlocked() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("prepareAndDenyRuntimePermission")
            withMain(fixture) {
                val runtimeGranted = runtimePermissionGranted()
                val managerEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled()
                println("B07.3 app-setting state: runtimeGranted=$runtimeGranted managerEnabled=$managerEnabled")
                assertTrue("The actual app-level switch must block notification eligibility", !runtimeGranted || !managerEnabled)
                assertTrue("The app must recognize its blocked notification state", !ReminderScheduler.allowed(app))
                val reminder = createThroughReadingUi(nextMinuteAtLeast(TimeUnit.HOURS.toMillis(1)), dismissConfirmation = false)
                assertBlockedDueReminder(reminder)
                onView(withText("Notification settings")).inRoot(isDialog()).perform(click())
                awaitNotificationRecovery()
                onView(withText("Open settings")).perform(click())
                awaitSettings()
                assertTrue("App-level blocking must not post a notification", manager.activeNotifications.isEmpty())
                backToApp()
                observe(fixture, "appNotificationsActuallyBlocked", true)
                observe(fixture, "appBlockRecoveryOpenedSystemSettings", true)
                observe(fixture, "appBlockDueRecordRetainedWithoutNotification", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "verifyAppNotificationsBlocked")
        }
    }

    @Test
    fun verifyChannelNotificationsBlocked() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("verifyAppNotificationsBlocked")
            assertTrue("The host must restore app notifications before the channel case", runtimePermissionGranted() && NotificationManagerCompat.from(app).areNotificationsEnabled())
            manager.createNotificationChannel(NotificationChannel("study", "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
            try {
                withMain(fixture) {
                    openChannelSettings()
                    setChannelSwitch(false)
                    awaitCondition("the actual disabled study channel") { manager.getNotificationChannel("study")?.importance == NotificationManager.IMPORTANCE_NONE }
                    backToApp()
                    val reminder = createThroughReadingUi(nextMinuteAtLeast(TimeUnit.HOURS.toMillis(1)), dismissConfirmation = false)
                    assertBlockedDueReminder(reminder)
                    onView(withText("Notification settings")).inRoot(isDialog()).perform(click())
                    awaitNotificationRecovery()
                    onView(withText("Open settings")).perform(click())
                    awaitSettings()
                    assertTrue("Recovery must leave the actual channel blocked until the user changes it", manager.getNotificationChannel("study")?.importance == NotificationManager.IMPORTANCE_NONE)
                    assertTrue("A blocked study channel must not post", manager.activeNotifications.isEmpty())
                    observe(fixture, "studyChannelActuallyBlocked", true)
                    observe(fixture, "channelRecoveryOpenedSystemSettings", true)
                    observe(fixture, "channelBlockDueRecordRetainedWithoutNotification", true)
                }
            } finally {
                withContext(NonCancellable) {
                    if (manager.getNotificationChannel("study")?.importance == NotificationManager.IMPORTANCE_NONE) {
                        openChannelSettings()
                        setChannelSwitch(true)
                    }
                    awaitCondition("the restored study channel") { manager.getNotificationChannel("study")?.importance != NotificationManager.IMPORTANCE_NONE }
                    globalBack()
                }
            }
            assertTrue("The channel must be usable after the settings case", ReminderScheduler.allowed(app))
            observe(fixture, "channelRestoredByUi", true)
            snapshotExpected(fixture)
            markPassed(fixture, "verifyChannelNotificationsBlocked")
        }
    }

    @Test
    fun saveAndDeliverAllowedReminder() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("verifyChannelNotificationsBlocked")
            withMain(fixture) {
                assertTrue("Notifications must be allowed for real delivery", ReminderScheduler.allowed(app))
                val requestedAt = System.currentTimeMillis()
                val reminder = createThroughReadingUi(nextMinuteAtLeast(30_000))
                val work = awaitScheduled(fixture, reminder.id)
                fixture.put("allowedReminderId", reminder.id).put("allowedWorkId", work.id.toString())
                val notification = awaitNotification(fixture, reminder, work.id)
                assertGenericPrivateNotification(notification)
                recordDelivery(fixture, "allowed-ui", reminder, requestedAt, notification, work.id)
                tapActualNotification(notification.id)
                assertOriginalAccount(fixture)
                observe(fixture, "allowedReminderSavedThroughUi", true)
                observe(fixture, "realScheduledReminderDelivered", true)
                observe(fixture, "notificationGenericAndPrivate", true)
                observe(fixture, "notificationTapOpenedAuthenticatedApp", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "saveAndDeliverAllowedReminder")
        }
    }

    @Test
    fun editSameReminder() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("saveAndDeliverAllowedReminder")
            withMain(fixture) {
                val original = createThroughReadingUi(nextMinuteAtLeast(30_000))
                val oldWork = awaitScheduled(fixture, original.id)
                openReminderActions(original)
                onView(withText("Edit reminder")).perform(click())
                chooseDateTime(original.dueAt + TimeUnit.MINUTES.toMillis(1))
                awaitSaveAcknowledgement(message = "Reminder updated")
                val changed = awaitValue("the edited reminder with the same logical ID") {
                    repository.reminders().first { rows -> rows.any { it.id == original.id && it.dueAt > original.dueAt } }
                }.single { it.id == original.id }
                val replacement = awaitScheduled(fixture, original.id, excluded = oldWork.id)
                awaitCondition("the old reminder work cancellation or replacement pruning") {
                    info(oldWork.id)?.state.let { it == null || it == WorkInfo.State.CANCELLED }
                }
                observe(fixture, "editedOldWorkPrunedByReplacement", info(oldWork.id) == null)
                assertTrue("Editing must preserve the reminder identity and content", changed.id == original.id && changed.moduleId == original.moduleId && changed.title == original.title && changed.enabled)
                fixture.put("editedReminderId", original.id).put("editedWorkId", replacement.id.toString())
                    .put("editedOldWorkId", oldWork.id.toString()).put("editedOldDueAt", original.dueAt).put("editedDueAt", changed.dueAt)
                awaitUntil(original.dueAt + 1_500)
                assertTrue("Replaced old work must not post at its former due time", manager.activeNotifications.none { it.id == notificationId(fixture, original.id) })
                assertTrue("The replacement must still be pending before its later time", info(replacement.id)?.state == WorkInfo.State.ENQUEUED)
                val readingTarget = ReminderTarget.SavedReading(fixture.getString("retargetReadingId"))
                assertTrue("Retarget must change from reading A to distinct reading B", changed.target == ReminderTarget.SavedReading(fixture.getString("readingId")) && changed.target != readingTarget)
                repository.saveReminder(changed.copy(moduleId = null, target = readingTarget))
                val typed = awaitValue("the acknowledged typed reading retarget") {
                    repository.reminders().first { rows -> rows.any { it.id == changed.id && it.target == readingTarget } }
                }.single { it.id == changed.id }
                val typedWork = awaitScheduled(fixture, changed.id, excluded = replacement.id)
                awaitCondition("the prior-target work cancellation or replacement pruning") {
                    info(replacement.id)?.state.let { it == null || it == WorkInfo.State.CANCELLED }
                }
                assertTrue("Retarget must retain the same reminder identity and due time", typed.id == changed.id && typed.dueAt == changed.dueAt && typed.title == changed.title)
                assertTrue("Typed navigation must resolve the acknowledged owned reading", repository.reminderTarget(typed.id) == readingTarget)
                fixture.put("editedWorkId", typedWork.id.toString()).put("retargetedOldWorkId", replacement.id.toString())
                observe(fixture, "editPreservedLogicalReminderId", true)
                observe(fixture, "editReplacedAndCancelledOldWork", true)
                observe(fixture, "oldDueTimeProducedNoStaleNotification", true)
                observe(fixture, "typedRetargetPreservedIdAndDueTime", true)
                observe(fixture, "typedRetargetCancelledPriorWork", true)
                observe(fixture, "typedTargetResolvedFromAcknowledgedSource", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "editSameReminder")
        }
    }

    @Test
    fun disableAndDeleteReminder() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("editSameReminder")
            withMain(fixture) {
                val id = fixture.getString("editedReminderId")
                var reminder = awaitValue("the edited future reminder") { repository.reminders().first { rows -> rows.any { it.id == id } } }.single { it.id == id }
                val old = UUID.fromString(fixture.getString("editedWorkId"))
                assertTrue("The edited reminder must still be future-dated for cancellation", reminder.dueAt > System.currentTimeMillis())
                openReminderActions(reminder)
                onView(withText("Turn off")).perform(click())
                awaitSaveAcknowledgement(message = "Reminder turned off")
                reminder = awaitValue("the disabled record") { repository.reminders().first { rows -> rows.any { it.id == id && !it.enabled } } }.single { it.id == id }
                awaitCondition("disabled work to cancel") { info(old)?.state == WorkInfo.State.CANCELLED }
                openReminderActions(reminder)
                onView(withText("Turn on")).perform(click())
                awaitSaveAcknowledgement(message = "Reminder turned on")
                reminder = awaitValue("the re-enabled original reminder") { repository.reminders().first { rows -> rows.any { it.id == id && it.enabled } } }.single { it.id == id }
                val reenabled = awaitScheduled(fixture, id, excluded = old)
                openReminderActions(reminder)
                onView(withText("Remove reminder")).perform(scrollTo(), click())
                onView(withId(android.R.id.button1)).perform(click())
                awaitSaveAcknowledgement(message = "Removed")
                awaitValue("the removed reminder record") { repository.reminders().first { rows -> rows.none { it.id == id } } }
                awaitCondition("deleted work to cancel") { info(reenabled.id)?.state == WorkInfo.State.CANCELLED }
                awaitUntil(reminder.dueAt + 1_500)
                assertTrue("Disabled/deleted generations must not post after their former due time", manager.activeNotifications.none { it.id == notificationId(fixture, id) })
                fixture.put("reenabledWorkId", reenabled.id.toString())
                observe(fixture, "disableCancelledScheduledWork", true)
                observe(fixture, "reenableRetainedLogicalIdAndScheduledNewWork", true)
                observe(fixture, "deleteRemovedRecordAndCancelledWork", true)
                observe(fixture, "cancelledDueTimeProducedNoNotification", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "disableAndDeleteReminder")
        }
    }

    @Test
    fun verifyOverdueReconciliation() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("disableAndDeleteReminder")
            withMain(fixture) {
                val requestedAt = System.currentTimeMillis()
                val reminder = StudyReminder("b07_controlled_overdue", null, "B07 controlled delayed-work fixture",
                    requestedAt + 3_000, true, "UTC", target = ReminderTarget.SavedReading(fixture.getString("readingId")))
                repository.saveReminder(reminder)
                ReminderScheduler.reconcile(app, fixture.getString("uid"), repository.reminders().first())
                awaitScheduled(fixture, reminder.id)
                val key = uniqueName(fixture, reminder.id)
                val request = OneTimeWorkRequestBuilder<StudyReminderWorker>()
                    .setInputData(workDataOf("uid" to fixture.getString("uid"), "id" to reminder.id,
                        "scheduleIdentity" to ReminderScheduleIdentity.fingerprint(reminder)))
                    .setInitialDelay(12, TimeUnit.SECONDS).addTag("study_reminder_${fixture.getString("uid")}").build()
                workManager.enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, request).result.get(5, TimeUnit.SECONDS)
                awaitCondition("the controlled delayed request to be enqueued") { info(request.id)?.state == WorkInfo.State.ENQUEUED }
                awaitUntil(reminder.dueAt + 1_000)
                ReminderScheduler.reconcile(app, fixture.getString("uid"), repository.reminders().first())
                delay(500)
                assertTrue("Reconciliation must preserve the same undelivered overdue request", info(request.id)?.state == WorkInfo.State.ENQUEUED)
                assertTrue("The controlled reminder must still be undelivered at overdue reconciliation",
                    manager.activeNotifications.none { it.id == notificationId(fixture, reminder.id) })
                val notification = awaitNotification(fixture, reminder, request.id)
                assertTrue("The observed post must follow the controlled worker delay", notification.postTime >= requestedAt + 12_000)
                assertGenericPrivateNotification(notification)
                recordDelivery(fixture, "controlled-overdue-component", reminder, requestedAt, notification, request.id)
                val postedAt = notification.postTime
                ReminderScheduler.reconcile(app, fixture.getString("uid"), repository.reminders().first())
                delay(500)
                val generations = uniqueWork(fixture, reminder.id)
                assertTrue("A delivered overdue reminder must not be scheduled a second time", generations.count { it.state == WorkInfo.State.SUCCEEDED } == 1 && generations.none { !it.state.isFinished })
                assertTrue("The overdue fixture must have one active notification", manager.activeNotifications.count { it.id == notificationId(fixture, reminder.id) } == 1)
                assertTrue("Repeated reconciliation must retain the same notification post time", manager.activeNotifications.single { it.id == notificationId(fixture, reminder.id) }.postTime == postedAt)
                tapActualNotification(notification.id)
                fixture.put("overdueReminderId", reminder.id).put("overdueWorkId", request.id.toString())
                observe(fixture, "overdueOriginalDelayedRequestPreserved", true)
                observe(fixture, "overdueDeliveredExactlyOnce", true)
                observe(fixture, "deliveredOverdueNotRescheduled", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "verifyOverdueReconciliation")
        }
    }

    @Test
    fun prepareBackgroundReminder() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("verifyOverdueReconciliation")
            withMain(fixture) {
                val requestedAt = System.currentTimeMillis()
                val reminder = createThroughReadingUi(nextMinuteAtLeast(90_000))
                val work = awaitScheduled(fixture, reminder.id)
                fixture.put("background", JSONObject().put("reminderId", reminder.id).put("workId", work.id.toString())
                    .put("dueAt", reminder.dueAt).put("requestedAt", requestedAt).put("notificationId", notificationId(fixture, reminder.id)))
                observe(fixture, "backgroundReminderSavedThroughUi", true)
                observe(fixture, "backgroundReminderEnqueuedBeforeHandoff", true)
            }
            snapshotExpected(fixture)
            markPassed(fixture, "prepareBackgroundReminder")
        }
    }

    @Test
    fun verifyBackgroundDeliveryAfterReclaim() = runBlocking<Unit> {
        withTimeout(STAGE_TIMEOUT) {
            requireLocalBuild()
            val fixture = readFixture("prepareBackgroundReminder")
            val background = fixture.getJSONObject("background")
            val proofFile = File(app.filesDir, "b07-reminders-host-proof.json")
            assertTrue("The host must supply actual process and notification observations", proofFile.exists())
            val proof = JSONObject(proofFile.readText())
            assertTrue("The host proof format must match", proof.getInt("schemaVersion") == 1)
            assertTrue("Background recovery must use ordinary reclaim rather than force-stop", proof.getBoolean("backgroundRecovered") && proof.getBoolean("ordinaryAmKill") && !proof.getBoolean("forceStopped"))
            assertTrue("The worker must execute in a new observed process", proof.getInt("priorPid") != proof.getInt("workerPid"))
            assertTrue("The host must observe the matching due time and notification", proof.getLong("dueAt") == background.getLong("dueAt") && proof.getInt("notificationId") == background.getInt("notificationId"))
            assertTrue("Actual delivery and notification tap timestamps must follow the requested time", proof.getLong("postTime") >= background.getLong("dueAt") && proof.getLong("observedAt") >= proof.getLong("postTime") && proof.getLong("tappedAt") >= proof.getLong("observedAt"))
            assertTrue("The host must observe one generic private notification and actual authenticated navigation", proof.getInt("notificationCount") == 1 && proof.getBoolean("genericContent") && proof.getBoolean("privateVisibility") && proof.getBoolean("mainActivityOpened"))
            withMain(fixture) {
                val id = UUID.fromString(background.getString("workId"))
                awaitCondition("the recovered worker's successful completion") { info(id)?.state == WorkInfo.State.SUCCEEDED }
                val generations = uniqueWork(fixture, background.getString("reminderId"))
                assertTrue("Ordinary reclaim must preserve a single scheduled generation", generations.size == 1 && generations.single().id == id)
                assertOriginalAccount(fixture)
                assertTrue("The tapped notification must not remain active", manager.activeNotifications.none { it.id == background.getInt("notificationId") })
            }
            fixture.getJSONArray("deliveryObservations").put(JSONObject().put("case", "background-ordinary-am-kill")
                .put("reminderId", background.getString("reminderId")).put("workId", background.getString("workId"))
                .put("requestedAt", background.getLong("requestedAt")).put("dueAt", background.getLong("dueAt"))
                .put("observedAt", proof.getLong("observedAt")).put("notificationPostTime", proof.getLong("postTime"))
                .put("notificationId", background.getInt("notificationId")).put("pid", proof.getInt("workerPid")))
            observe(fixture, "ordinaryBackgroundProcessReclaimObserved", true)
            observe(fixture, "backgroundWorkerRanInNewProcess", true)
            observe(fixture, "backgroundRealNotificationDeliveredAndTapped", true)
            observe(fixture, "backgroundAuthenticatedNavigationAndPrivacy", true)
            observe(fixture, "backgroundNoDuplicateWorkGeneration", true)
            snapshotExpected(fixture)
            markPassed(fixture, "verifyBackgroundDeliveryAfterReclaim")
        }
    }

    private suspend fun withMain(fixture: JSONObject, block: suspend () -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var launched: MainActivity? = null
        var launchIntent: Intent? = null
        var originalFailure: Throwable? = null
        try {
            scenario.onActivity { launched = it; launchIntent = Intent(it.intent) }
            awaitActivity(MainActivity::class.java)
            awaitView(R.id.nav_study)
            assertOriginalAccount(fixture)
            block()
        } catch (error: Throwable) {
            originalFailure = error
            throw error
        } finally {
            val persist = runCatching { writeAtomic(fixtureFile, fixture) }
            val close = runCatching {
                // AndroidX matches lifecycle callbacks against the original launch Intent.
                // Main legitimately replaces its Intent on a real notification tap.
                instrumentation.runOnMainSync { launched?.takeUnless { it.isDestroyed }?.intent = launchIntent }
                scenario.close()
            }
            var cleanupFailure: Throwable? = null
            listOfNotNull(persist.exceptionOrNull(), close.exceptionOrNull()).forEach { error ->
                if (originalFailure != null) originalFailure.addSuppressed(error)
                else if (cleanupFailure == null) cleanupFailure = error
                else cleanupFailure?.addSuppressed(error)
            }
            if (originalFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private suspend fun openReading() {
        ensureNavigation()
        onView(withId(R.id.nav_saved)).perform(click())
        awaitText(READING_TITLE)
        onView(withContentDescription("Open $READING_TITLE")).perform(scrollTo(), click())
        awaitText("Notes & position")
        onView(withText("Notes & position")).perform(scrollTo(), click())
        awaitText("Add study reminder")
    }

    private suspend fun createThroughReadingUi(dueAt: Long, dismissConfirmation: Boolean = true): StudyReminder {
        val existing = repository.reminders().first().map { it.id }.toSet()
        openReading()
        onView(withText("Add study reminder")).perform(scrollTo(), click())
        chooseDateTime(dueAt)
        awaitSaveAcknowledgement(dismissConfirmation)
        return awaitValue("the new UI-created reminder") { repository.reminders().first { rows -> rows.any { it.id !in existing } } }
            .single { it.id !in existing }.also { assertTrue("The UI must save the selected future time", kotlin.math.abs(it.dueAt - dueAt) < 1_100 && it.enabled) }
    }

    private suspend fun chooseDateTime(dueAt: Long) {
        val selected = Calendar.getInstance().apply { timeInMillis = dueAt }
        onView(isAssignableFrom(DatePicker::class.java)).check { view, error ->
            if (error != null) throw error
            (view as DatePicker).updateDate(selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH))
        }
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).check { view, error ->
            if (error != null) throw error
            (view as TimePicker).apply { hour = selected.get(Calendar.HOUR_OF_DAY); minute = selected.get(Calendar.MINUTE) }
        }
        onView(withId(android.R.id.button1)).perform(click())
    }

    private suspend fun awaitSaveAcknowledgement(dismissConfirmation: Boolean = false, message: String = "Reminder saved") {
        awaitTextContaining(message)
        if (dismissConfirmation) onView(withText("Close")).inRoot(isDialog()).perform(click())
    }

    private suspend fun awaitNotificationRecovery() {
        awaitText("Enable study notifications")
        onView(withText("Enable study notifications")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Open settings")).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private suspend fun openReminderActions(reminder: StudyReminder) {
        ensureNavigation()
        onView(withId(R.id.nav_account)).perform(click())
        awaitText("Study reminders")
        onView(withText("Study reminders")).perform(scrollTo(), click())
        onView(allOf(withText(containsString(reminder.title)), withText(containsString(dateLabel(reminder.dueAt))))).perform(click())
    }

    private suspend fun ensureNavigation() {
        if (!mainThreadValue { resumed(MainActivity::class.java)?.findViewById<View>(R.id.nav_saved)?.isShown == true }) pressBack()
        awaitView(R.id.nav_saved)
    }

    private suspend fun awaitScheduled(fixture: JSONObject, reminderId: String, excluded: UUID? = null): WorkInfo {
        var result: WorkInfo? = null
        awaitCondition("the scheduled reminder work") {
            result = uniqueWork(fixture, reminderId).firstOrNull { it.state == WorkInfo.State.ENQUEUED && it.id != excluded }
            result != null
        }
        return requireNotNull(result)
    }

    private suspend fun awaitNotification(fixture: JSONObject, reminder: StudyReminder, workId: UUID): StatusBarNotification {
        var value: StatusBarNotification? = null
        awaitCondition("actual notification delivery", DELIVERY_TIMEOUT) {
            value = manager.activeNotifications.singleOrNull { it.id == notificationId(fixture, reminder.id) }
            value != null && info(workId)?.state == WorkInfo.State.SUCCEEDED
        }
        return requireNotNull(value).also { assertTrue("A reminder must not post before its due time", it.postTime >= reminder.dueAt) }
    }

    private fun assertGenericPrivateNotification(notification: StatusBarNotification) {
        val value = notification.notification
        assertTrue("Study notification content must remain generic and private", value.visibility == Notification.VISIBILITY_PRIVATE &&
            value.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Time for a study session" &&
            value.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Open StudyPath to continue your review.")
    }

    private suspend fun tapActualNotification(id: Int) {
        assertTrue("The Android notification shade must open", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        clickSystemNode(awaitSystemNode("the actual study notification in the shade") { it.text?.toString() == "Time for a study session" })
        awaitActivity(MainActivity::class.java)
        awaitCondition("auto-cancellation after the real notification tap") { manager.activeNotifications.none { it.id == id } }
    }

    private fun recordDelivery(fixture: JSONObject, case: String, reminder: StudyReminder, requestedAt: Long,
        notification: StatusBarNotification, workId: UUID) {
        fixture.getJSONArray("deliveryObservations").put(JSONObject().put("case", case).put("reminderId", reminder.id)
            .put("requestedAt", requestedAt).put("dueAt", reminder.dueAt).put("observedAt", System.currentTimeMillis())
            .put("notificationPostTime", notification.postTime).put("workId", workId.toString())
            .put("notificationId", notification.id).put("pid", Process.myPid()))
    }

    private suspend fun openChannelSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "study")
        mainThreadValue {
            val activity = resumed(MainActivity::class.java)
            if (activity != null) activity.startActivity(intent)
            else app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        awaitSettings()
        awaitSystemNode("the channel settings page to finish opening") {
            it.viewIdResourceName == "com.android.settings:id/switch_text" && it.text?.toString() == "Show notifications"
        }
    }

    private suspend fun setChannelSwitch(enabled: Boolean) {
        val label = awaitSystemNode("the channel's Show notifications control") {
            it.viewIdResourceName == "com.android.settings:id/switch_text" && it.text?.toString() == "Show notifications"
        }
        val node = requireNotNull(findNode(label.parent) {
            it.isCheckable && it.viewIdResourceName == "android:id/switch_widget"
        }) { "The channel master switch must belong to its Show notifications row" }
        if (node.isChecked != enabled) clickSystemNode(node)
        awaitCondition("the requested system channel setting") {
            val importance = manager.getNotificationChannel("study")?.importance
            if (enabled) importance != null && importance != NotificationManager.IMPORTANCE_NONE else importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private suspend fun awaitSettings() = awaitCondition("the real Android settings screen") { systemRoot()?.packageName?.toString() == "com.android.settings" }
    private fun globalBack() { assertTrue("Android Back must return from settings", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) }
    private suspend fun backToApp() {
        repeat(4) {
            globalBack()
            val returned = withTimeoutOrNull(3_000) {
                while (systemRoot()?.packageName?.toString() != app.packageName) delay(25)
                true
            } == true
            if (returned) { awaitActivity(MainActivity::class.java); return }
        }
        throw AssertionError("Android Back did not return from Settings to the app")
    }
    private fun systemRoot(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
    private suspend fun awaitSystemNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var value: AccessibilityNodeInfo? = null
        awaitCondition(description) { value = findNode(systemRoot(), predicate); value != null }
        return requireNotNull(value)
    }
    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && predicate(root)) return root
        for (index in 0 until root.childCount) findNode(root.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun clickSystemNode(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("The actual Android control must accept its click", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private suspend fun assertBlockedDueReminder(reminder: StudyReminder) {
        assertTrue("The blocked state must retain its acknowledged enabled due item", reminder.enabled &&
            repository.reminders().first().any { it.id == reminder.id && it.dueAt == reminder.dueAt })
        awaitTextContaining("Notifications are off. This item remains available in your due list.")
        assertTrue("Blocked notifications must not enqueue new delivery work", uniqueWork(readSetupFixture(), reminder.id).none { !it.state.isFinished })
    }
    private suspend fun snapshotExpected(fixture: JSONObject) {
        val rows = awaitValue("the current complete reminder collection") { repository.reminders().first() }
        fixture.put("expectedReminders", JSONObject().apply { rows.forEach { put(it.id, JSONObject(DatabaseRecords.reminder(it)).apply { remove("updatedAt") }) } })
    }
    private fun uniqueName(fixture: JSONObject, id: String) = "study_reminder_${fixture.getString("uid")}_$id"
    private fun uniqueWork(fixture: JSONObject, id: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(uniqueName(fixture, id)).get(3, TimeUnit.SECONDS)
    private fun info(id: UUID): WorkInfo? = workManager.getWorkInfoById(id).get(3, TimeUnit.SECONDS)
    private fun notificationId(fixture: JSONObject, id: String) = (fixture.getString("uid") + id).hashCode()
    private fun runtimePermissionGranted() = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun nextMinuteAtLeast(delayMillis: Long): Long = ((System.currentTimeMillis() + delayMillis + 59_999) / 60_000) * 60_000
    private suspend fun awaitUntil(timestamp: Long) { while (System.currentTimeMillis() < timestamp) delay((timestamp - System.currentTimeMillis()).coerceIn(1, 250)) }

    private fun assertOriginalAccount(fixture: JSONObject) = assertTrue("The original bound account must remain authenticated", repository.boundUid() == fixture.getString("uid") && repository.currentSession()?.uid == fixture.getString("uid"))
    private suspend fun awaitActivity(type: Class<out Activity>) = awaitCondition("${type.simpleName} to resume in the active app window") {
        mainThreadValue { resumed(type) != null } && systemRoot()?.packageName?.toString() == app.packageName
    }
    private suspend fun awaitView(id: Int) = awaitCondition("the main navigation") { mainThreadValue { resumed(MainActivity::class.java)?.findViewById<View>(id)?.isShown == true } }
    private suspend fun awaitText(text: String) = awaitCondition("application text '$text'") {
        mainThreadValue { currentRoot()?.let { containsText(it) { value -> value == text } } == true } || findNode(systemRoot()) { it.text?.toString() == text } != null
    }
    private suspend fun awaitTextContaining(text: String) = awaitCondition("the expected application explanation") {
        mainThreadValue { currentRoot()?.let { containsText(it) { value -> value.contains(text, ignoreCase = true) } } == true } || findNode(systemRoot()) { it.text?.toString()?.contains(text, ignoreCase = true) == true } != null
    }
    private fun resumed(type: Class<out Activity>): Activity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
    private fun currentRoot(): View? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
    private fun containsText(view: View, predicate: (String) -> Boolean): Boolean = (view is TextView && view.isShown && predicate(view.text.toString())) || (view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), predicate) })
    private fun <T> mainThreadValue(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private suspend fun awaitCondition(description: String, timeout: Long = VALUE_TIMEOUT, condition: () -> Boolean) = awaitValue(description, timeout) { while (!condition()) delay(25) }
    private suspend fun <T> awaitValue(description: String, timeout: Long = VALUE_TIMEOUT, block: suspend () -> T): T = try { withTimeout(timeout) { block() } }
    catch (error: TimeoutCancellationException) { throw AssertionError("B07.3 timed out waiting for $description.", error) }
    private val testNamespace get() = BuildConfig.FIREBASE_DATABASE_URL.removePrefix("https://").removeSuffix(".firebaseio.com")
    private val runToken get() = requireNotNull(InstrumentationRegistry.getArguments().getString("b07RunToken")).also {
        require(it.matches(Regex("[a-f0-9]{32}"))) { "The host must provide a fresh invocation token." }
    }
    private fun requireLocalBuild() {
        assertTrue("This suite targets only the local demo Firebase build on API 36", BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath" && Build.VERSION.SDK_INT == 36)
        assertTrue("Never target preview/default data with mock approvals", testNamespace.matches(Regex("demo-studypath-b07-reminders-[a-f0-9]{8}")))
        assertTrue("The frozen APK must match the host namespace", InstrumentationRegistry.getArguments().getString("b07Namespace") == testNamespace)
        runToken
    }
    private fun readFixture(stage: String): JSONObject {
        assertTrue("The preceding stage must create its private fixture", fixtureFile.exists())
        return JSONObject(fixtureFile.readText()).also {
            assertTrue("The fixture must match the exact namespace/invocation", it.getString("namespace") == testNamespace && it.getString("runToken") == runToken)
            assertTrue("The preceding coordinated stage must pass", it.getInt("schemaVersion") == 1 && it.getJSONObject("stages").optString(stage) == "passed")
        }
    }
    private fun readSetupFixture() = JSONObject(File(app.filesDir, "b07-reminders-setup-identity.json").readText()).also {
        assertTrue("The setup identity must belong to this invocation", it.getString("namespace") == testNamespace && it.getString("runToken") == runToken)
    }
    private fun syntheticReading(retarget: Boolean = false): ReadingContent {
        val pageId = if (retarget) 5312L else 5311L
        val revision = if (retarget) 1379403935L else 1379403934L
        val page = if (retarget) "Algorithm" else "Computer_programming"
        return ReadingContent(pageId, page, if (retarget) "B07 synthetic reminder reading B" else READING_TITLE,
            "Original synthetic test text for typed reminder retargeting. This is not a live API result.",
            revision, "2026-10-09T20:17:41Z", "https://en.wikipedia.org/wiki/$page",
            "https://en.wikipedia.org/w/index.php?oldid=$revision", "Creative Commons Attribution-Share Alike 4.0",
            "https://creativecommons.org/licenses/by-sa/4.0/deed.en", "StudyPath synthetic original test text; retained source metadata attributed to Wikipedia contributors.",
            "Synthetic fixture only; no live reading fetch was performed.", System.currentTimeMillis())
    }
    private fun observe(fixture: JSONObject, name: String, value: Boolean) { fixture.getJSONObject("observations").put(name, value) }
    private fun markPassed(fixture: JSONObject, stage: String) {
        fixture.getJSONObject("stages").put(stage, "passed")
        fixture.getJSONObject("stagePids").put(stage, Process.myPid())
        writeAtomic(fixtureFile, fixture)
    }
    private fun writeAtomic(target: File, value: JSONObject) {
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    companion object {
        private const val READING_TITLE = "B07 synthetic reminder reading A"
        private const val STAGE_TIMEOUT = 180_000L
        private const val VALUE_TIMEOUT = 15_000L
        private const val DELIVERY_TIMEOUT = 150_000L
    }
}
