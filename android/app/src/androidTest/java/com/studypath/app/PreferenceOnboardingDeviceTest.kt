package com.studypath.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.studypath.app.data.LearnerPreferenceRecords
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.DatabaseRecords
import com.studypath.app.data.StudyReminder
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.auth.LoginActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.`is`
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UI edits plus host-observed process death, actual transport partition and independent server reads. */
@RunWith(AndroidJUnit4::class)
class PreferenceOnboardingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val fixtureFile get() = File(app.filesDir, "u03-onboarding-fixture.json")
    private val first = LearnerPreferences(levelId = "grade_7", subjectIds = setOf("mathematics"), classSectionId = "pilot_a")
    private val queued = LearnerPreferences(levelId = "college", subjectIds = setOf("programming"), classSectionId = "independent_b")

    @Before fun selectedLocalStageOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.PreferenceOnboardingDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
    }

    /** Run alone against the preceding production APK to record the missing UI before implementation. */
    @Test fun initialOnboardingRequired() = runBlocking<Unit> {
        withTimeout(60_000) {
            register()
            ActivityScenario.launch(MainActivity::class.java).use {
                try {
                    await("First login must show the learner-preference editor", 15_000) { main { tag("learner_preferences") != null } }
                } finally { capture("00-initial-onboarding-probe") }
            }
        }
    }

    @Test fun uiPreferencesAndRecreation() = runBlocking<Unit> {
        withTimeout(150_000) {
            val fixture = register()
            repository.connection().first { it }
            assertTrue("This fixture requires absent matching approved options; never publish test approval.",
                repository.learnerPreferenceOptions().first().none {
                    it.approved && it.approvalStatus == "APPROVED" &&
                        ((it.levelId == "grade_7" && "mathematics" in it.subjectIds) ||
                            (it.levelId == "college" && "programming" in it.subjectIds))
                })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("learner_preferences")
                observed(fixture, "firstLoginAutomaticallyShowsPreferences")
                capture("01-first-login-preferences")
                chooseLevel("Grade 7")
                chooseSubjects(listOf("Mathematics"))
                tagged("preference_class").perform(scrollTo(), replaceText("Pilot A"))
                hideKeyboardAndAwait()
                scenario.recreate()
                awaitText("Level: Grade 7")
                awaitText("Subjects: Mathematics")
                tagged("preference_class").check(matches(withText("Pilot A")))
                assertFalse(main { tag("preference_continue")!!.isEnabled })
                observed(fixture, "unsavedLevelSubjectsAndClassSurviveRecreation")
                capture("02-recreated-draft")
                assertPreferenceCopy(fixture, "normal")
                assertTargets(fixture, "normalTextTargets")
                assertSaveReachableWithIme(fixture, "normal", "02b-normal-preferences-save-keyboard")
                recordSaveDiagnostic(fixture, "beforeTap")
                tagged("preference_save").perform(scrollTo(), click())
                recordSaveDiagnostic(fixture, "afterTap")
                try { awaitText(CONFIRMED) }
                finally { recordSaveDiagnostic(fixture, "afterAwait"); capture("02c-preference-save-result") }
                assertEquals(first, repository.learnerPreferences().first())
                tagged("preference_continue").check(matches(isEnabled()))
                observed(fixture, "uiDraftSaveWaitsForServerAcknowledgement")
                capture("03-server-confirmed-preferences")
                tagged("preference_continue").perform(scrollTo(), click())
                awaitText("What will you learn?")
                assertGatedLibrary("featured_module")
                onView(withHint("Search Wikipedia")).perform(scrollTo(), replaceText("Fractions"))
                hideKeyboardAndAwait()
                onView(withText("Find readings")).perform(scrollTo()).check(matches(isCompletelyDisplayed())).check(matches(isEnabled()))
                observed(fixture, "wikipediaSearchRemainsReachableWithoutLessonCatalog")
                capture("04-study-independent-search")
                navigate(R.id.nav_saved)
                awaitText("Readings · 0/20")
                assertGatedLibrary("modules")
                onView(withText("Downloaded lessons · 0")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
                capture("05-saved-preference-gating")
                observed(fixture, "studyAndSavedDoNotExposeLegacyDemoLessons")
                navigate(R.id.nav_account)
                assertProfileControls(fixture, "normal")
                awaitText("No study reminders yet.")
                tagged("profile_reminders_action").perform(scrollTo(), click())
                onView(withText("Add a reminder from a lesson or saved reading. Android may delay delivery to save battery."))
                    .check(matches(isCompletelyDisplayed()))
                capture("04c-profile-empty-reminders")
                onView(withText("Got it")).perform(click())
                observed(fixture, "profileEmptyReminderGuidanceReachable")
                val reminder = StudyReminder(PROFILE_REMINDER_ID, "programming_basics", PROFILE_REMINDER_TITLE,
                    System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2), true, "Asia/Manila")
                repository.saveReminder(reminder)
                fixture.put("profileReminderId", reminder.id)
                    .put("profileReminderBefore", JSONObject(DatabaseRecords.reminder(reminder)))
                    .put("profileReminderAfter", JSONObject(DatabaseRecords.reminder(reminder.copy(enabled = false))))
                observed(fixture, "profileFutureReminderServerConfirmed")
            }
            fixture.put("stage1Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun queueOfflineAtLargeText() = runBlocking<Unit> {
        withTimeout(150_000) {
            val fixture = read()
            assertEquals(fixture.getString("uid"), repository.resumeSession()?.uid)
            assertNotEquals(fixture.getInt("stage1Pid"), android.os.Process.myPid())
            repository.connection().first { !it }
            assertTrue("Host must set actual system font scale to 200%.", app.resources.configuration.fontScale >= 1.99f)
            assertEquals(first, repository.learnerPreferences().first())
            ActivityScenario.launch(MainActivity::class.java).use {
                await("Main must show preferences or Study") { main { tag("learner_preferences") != null || uiTexts().contains("What will you learn?") } }
                if (main { tag("learner_preferences") != null }) tagged("preference_later").perform(scrollTo(), click())
                navigate(R.id.nav_account)
                assertProfileControls(fixture, "large")
                assertNotificationSettingsIntent(fixture)
                tagged("profile_account_action").perform(scrollTo(), click())
                assertAccountDialog(fixture, "large")
                onView(withText("Sign out")).perform(scrollTo(), click())
                onView(withText("Reminders stop. Pending changes stay with this account for your next sign-in."))
                    .check(matches(isCompletelyDisplayed()))
                capture("05e-profile-signout-large")
                onView(withText("Stay")).perform(click())
                assertEquals(fixture.getString("uid"), repository.currentSession()?.uid)
                observed(fixture, "profileLargeTextSignOutCancelKeepsSession")
                tagged("profile_reminders_action").perform(scrollTo(), click())
                onView(withText(org.hamcrest.Matchers.startsWith(PROFILE_REMINDER_TITLE))).perform(click())
                onView(withText("Turn off")).perform(scrollTo(), click())
                awaitText("Updating · awaiting server confirmation…")
                awaitText("All study reminders are turned off.")
                tagged("reminder_status").perform(scrollTo()).check(matches(isCompletelyDisplayed()))
                assertReadable("reminder_status")
                assertFalse(main { uiTexts().any { it == "Reminder turned off" } })
                repository.reminders().first { rows -> rows.singleOrNull()?.let { it.id == PROFILE_REMINDER_ID && !it.enabled } == true }
                capture("05d-profile-offline-reminder-pending")
                observed(fixture, "profileOfflineReminderPendingSurvivesReminderRerender")
                openEditor()
                awaitText("Level: Grade 7")
                awaitText("Subjects: Mathematics")
                tagged("preference_class").check(matches(withText("Pilot A")))
                assertFalse("Reopening cached choices is not an acknowledged new save", main { uiTexts().contains(CONFIRMED) })
                assertPreferenceCopy(fixture, "large")
                assertTargets(fixture, "largeTextTargets")
                observed(fixture, "sameUidOfflineEditorRecoversPriorPreferencesAt200PercentText")
                chooseLevel("College")
                chooseSubjects(listOf("Mathematics", "Programming"))
                tagged("preference_class").perform(scrollTo(), replaceText("Independent B"))
                hideKeyboardAndAwait()
                assertSaveReachableWithIme(fixture, "large", "05c-large-preferences-save-keyboard")
                tagged("preference_save").perform(scrollTo(), click())
                awaitText("Offline · preference save queued. Waiting for server confirmation.")
                repository.learnerPreferences().first { it == queued }
                assertFalse(main { uiTexts().contains(CONFIRMED) })
                assertFalse(main { tag("preference_continue")!!.isEnabled })
                assertFalse(main { tag("preference_save")!!.isEnabled })
                assertTrue(main { uiTexts().none { it.equals("Ready offline", true) || it.equals("Downloaded", true) } })
                tagged("preference_save_status").perform(scrollTo()).check(matches(isCompletelyDisplayed()))
                capture("06-offline-save-pending-large-text")
                observed(fixture, "offlineUiWriteQueuesWithoutServerConfirmedOrDownloadClaim")
            }
            assertEquals(queued, repository.learnerPreferences().first())
            fixture.put("stage2Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun recoverAndRelogin() = runBlocking<Unit> {
        withTimeout(150_000) {
            val fixture = read(); val uid = fixture.getString("uid")
            assertEquals(uid, repository.resumeSession()?.uid)
            assertNotEquals(fixture.getInt("stage2Pid"), android.os.Process.myPid())
            repository.connection().first { it }
            assertEquals(queued, repository.learnerPreferences().first { it == queued })
            repository.reminders().first { rows -> rows.singleOrNull()?.let { it.id == PROFILE_REMINDER_ID && !it.enabled } == true }
            observed(fixture, "profileQueuedReminderRecoveredOffAfterProcessDeath")
            ActivityScenario.launch(MainActivity::class.java).use {
                openEditor()
                assertQueuedEditor()
                assertFalse("A prior-process queued write is not this editor's confirmation", main { uiTexts().contains(CONFIRMED) })
                tagged("preference_save_status").perform(scrollTo())
                capture("07-reconnected-queued-preferences")
                observed(fixture, "queuedUiPreferencesRecoverAfterProcessDeathWithoutNewWrite")
                tagged("preference_later").perform(scrollTo(), click())
                navigate(R.id.nav_account)
                onView(withText("Account details")).perform(scrollTo(), click())
                assertAccountDialog(fixture, "normal")
                onView(withText("Sign out")).perform(scrollTo(), click())
                onView(withText("Sign out")).perform(scrollTo(), click())
                await("Logout must open login") { main { activity() is LoginActivity } }
                assertNull(repository.currentSession())
                assertEquals(uid, repository.boundUid())
                await("Login must finish checking the original workspace") { main { activity()?.findViewById<View>(R.id.signInButton)?.isEnabled == true } }
                onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(fixture.getString("email")))
                onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(PASSWORD))
                hideKeyboardAndAwait()
                onView(withId(R.id.signInButton)).perform(scrollTo(), click())
                await("Original UID must reopen Main") { main { activity() is MainActivity } }
                assertEquals(uid, repository.currentSession()?.uid)
                assertEquals(queued, repository.learnerPreferences().first())
                openEditor()
                assertQueuedEditor()
                capture("08-same-uid-relogin-preferences")
                tagged("preference_later").perform(scrollTo(), click())
                navigate(R.id.nav_saved)
                awaitText("Readings · 0/20")
                assertGatedLibrary("modules")
                observed(fixture, "uiLogoutAndOriginalUidReloginPreserveQueuedPreferencesAndGating")
            }
            fixture.put("stage3Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    private suspend fun register(): JSONObject {
        assertNull("Use a fresh read-only test AVD; never clear a bound preview.", repository.boundUid())
        val email = "onboarding_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
        val session = repository.register(email, PASSWORD, "Synthetic preference explorer")
        val fixture = JSONObject().put("schemaVersion", 1).put("uid", session.uid).put("email", email)
            .put("observations", JSONObject()).put("firstPreferences", JSONObject(LearnerPreferenceRecords.preferences(first)))
            .put("queuedPreferences", JSONObject(LearnerPreferenceRecords.preferences(queued)))
        save(fixture)
        return fixture
    }

    private fun chooseLevel(label: String) {
        await("Stored preferences must load before editing") { main { tag("preference_level")?.isEnabled == true } }
        tagged("preference_level").perform(scrollTo(), click())
        onData(`is`(label as Any)).perform(click())
        awaitText("Level: $label")
    }

    /** Toggles these dialog rows; stage two unchecks Mathematics and checks Programming. */
    private fun chooseSubjects(toggles: List<String>) {
        tagged("preference_subjects").perform(scrollTo(), click())
        toggles.forEach { onData(`is`(it as Any)).perform(click()) }
        onView(withText("Apply")).perform(click())
    }

    private fun openEditor() {
        await("Main must show preferences, Study or Profile") { main {
            tag("learner_preferences") != null || tag("profile_preferences_action") != null || uiTexts().contains("What will you learn?")
        } }
        if (main { tag("learner_preferences") != null }) return
        navigate(R.id.nav_account)
        onView(withText("Edit learning preferences")).perform(scrollTo(), click())
        awaitTag("learner_preferences")
    }

    private fun assertQueuedEditor() {
        awaitText("Level: College"); awaitText("Subjects: Programming")
        tagged("preference_class").check(matches(withText("Independent B")))
    }

    private fun assertGatedLibrary(section: String) {
        val expected = if (section == "modules") "Downloaded lessons · 0"
            else "No matching lesson catalog yet. You can keep exploring supplemental readings."
        await("The current library state must replace demo lesson cards") {
            main { tag(section)?.let { expected in texts(it) } == true }
        }
        if (section == "modules") assertTrue("A fresh Saved shelf must have no ready packages", repository.downloadedTextPackages().isEmpty())
        main {
            val nodes = descendants(requireNotNull(tag(section)))
            assertTrue(nodes.none { it.tag?.toString()?.startsWith("published_lesson_") == true })
            if (section == "modules") {
                assertTrue(texts(requireNotNull(tag(section))).contains("Download a text lesson from Study to use it here offline. Incomplete or damaged downloads are not listed as ready."))
                assertTrue(nodes.none { it.tag?.toString()?.startsWith("package_open_") == true })
            }
            assertTrue(nodes.filterIsInstance<TextView>().none { it.text.toString() in listOf(
                "Algorithms and programming", "Choosing a data structure", "Keys and related records", "Ready offline", "Downloaded") })
        }
    }

    private fun assertPreferenceCopy(fixture: JSONObject, mode: String) {
        awaitText("No approved curriculum matches yet. Save a draft and explore Wikipedia.")
        val rows = JSONArray()
        for (key in listOf("preference_heading", "preference_intro", "preference_catalog_status")) {
            tagged(key).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
            rows.put(assertReadable(key))
        }
        append(fixture, "preferenceGeometry", JSONObject().put("mode", mode)
            .put("fontScale", app.resources.configuration.fontScale).put("text", rows))
        observed(fixture, "${mode}TextPreferencesKeepConciseUnsupportedExplanationReadable")
    }

    private fun assertTargets(fixture: JSONObject, key: String) {
        val measures = JSONArray()
        for (key in listOf("preference_level", "preference_subjects", "preference_save", "preference_continue", "preference_later")) {
            tagged(key).perform(scrollTo()).check(matches(isCompletelyDisplayed())).check { view, error ->
                if (error != null) throw error
                val text = requireNotNull(view) as TextView
                val density = text.resources.displayMetrics.density
                val layout = requireNotNull(text.layout)
                assertTrue("Preference actions need a 48dp target", text.height + 1 >= 48 * density && text.width + 1 >= 48 * density)
                assertTrue("Action labels must fit vertically", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
                for (line in 0 until layout.lineCount) assertEquals("Action labels must not be ellipsized", 0, layout.getEllipsisCount(line))
                val area = Rect(); assertTrue(text.getGlobalVisibleRect(area))
                measures.put(JSONObject().put("tag", key).put("widthDp", text.width / density)
                    .put("heightDp", text.height / density).put("lineCount", layout.lineCount).put("completelyDisplayed", true))
            }
        }
        fixture.put(key, measures); save(fixture)
        if (key == "largeTextTargets") capture("05b-large-text-editor-actions")
    }

    private fun assertSaveReachableWithIme(fixture: JSONObject, mode: String, screenshot: String) {
        tagged("preference_class").perform(scrollTo(), click())
        await("The actual software keyboard must open for $mode preferences") { main {
            activity()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true
        } }
        tagged("preference_save").perform(scrollTo()).check(matches(isCompletelyDisplayed())).check(matches(isEnabled()))
        val geometry = assertReadable("preference_save", target = true)
        main {
            val decor = requireNotNull(activity()).window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            val action = requireNotNull(tag("preference_save"))
            val bounds = Rect(); assertTrue(action.getGlobalVisibleRect(bounds))
            val visibleWindow = Rect(); decor.getWindowVisibleDisplayFrame(visibleWindow)
            assertTrue("Save must remain above the visible keyboard", bounds.bottom <= visibleWindow.bottom + 1)
            assertTrue("IME must remain open after scrolling to Save", insets.isVisible(WindowInsetsCompat.Type.ime()))
            assertTrue("IME must have a nonzero inset", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0)
            geometry.put("mode", mode).put("fontScale", app.resources.configuration.fontScale)
                .put("imeVisible", true).put("imeBottomPx", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                .put("visibleWindowBottomPx", visibleWindow.bottom)
                .put("actionBoundsPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        }
        append(fixture, "preferenceSaveImeGeometry", geometry)
        capture(screenshot)
        tagged("preference_class").perform(scrollTo(), pressImeActionButton())
        await("The preference field's Done action must close the software keyboard") { main {
            activity()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == false
        } }
        tagged("preference_save").check(matches(isCompletelyDisplayed())).check(matches(isEnabled()))
        geometry.put("imeDoneClosesKeyboardAndRevealsSave", true); save(fixture)
        observed(fixture, "${mode}TextImeDoneClosesKeyboardAndRevealsPreferenceSave")
        observed(fixture, "${mode}TextPreferenceSaveReachableAboveActualIme")
    }

    private fun hideKeyboardAndAwait() {
        main {
            val decor = requireNotNull(activity()).window.decorView
            if (requireNotNull(ViewCompat.getRootWindowInsets(decor)).isVisible(WindowInsetsCompat.Type.ime())) {
                requireNotNull(ViewCompat.getWindowInsetsController(decor)).hide(WindowInsetsCompat.Type.ime())
            }
        }
        await("The software keyboard must be hidden before continuing") { main {
            activity()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == false
        } }
    }

    private fun recordSaveDiagnostic(fixture: JSONObject, point: String) {
        val diagnostic = main {
            val action = requireNotNull(tag("preference_save"))
            val area = Rect(); action.getGlobalVisibleRect(area)
            JSONObject().put("point", point)
                .put("status", (tag("preference_save_status") as TextView).text.toString())
                .put("enabled", action.isEnabled).put("bounds", area.toShortString())
                .put("layoutRequested", action.isLayoutRequested)
                .put("imeVisible", ViewCompat.getRootWindowInsets(requireNotNull(activity()).window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()))
        }
        append(fixture, "saveAttemptDiagnostics", diagnostic)
    }

    private fun assertProfileControls(fixture: JSONObject, mode: String) {
        awaitTag("profile_reminders_action")
        val measures = JSONArray()
        for (key in listOf("profile_study_heading", "profile_preferences_action", "profile_reminder_summary",
            "profile_reminders_action", "profile_account_heading", "profile_account_action")) {
            tagged(key).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
            measures.put(assertReadable(key, target = key.endsWith("_action")))
        }
        main {
            val study = requireNotNull(tag("profile_study_heading")); val account = requireNotNull(tag("profile_account_heading"))
            val preferences = requireNotNull(tag("profile_preferences_action")); val reminders = requireNotNull(tag("profile_reminders_action"))
            val studyPosition = IntArray(2); study.getLocationOnScreen(studyPosition)
            val accountPosition = IntArray(2); account.getLocationOnScreen(accountPosition)
            val preferencesPosition = IntArray(2); preferences.getLocationOnScreen(preferencesPosition)
            val remindersPosition = IntArray(2); reminders.getLocationOnScreen(remindersPosition)
            assertTrue("Study actions must follow their heading and precede account management",
                studyPosition[1] < preferencesPosition[1] && preferencesPosition[1] < remindersPosition[1] && remindersPosition[1] < accountPosition[1])
        }
        append(fixture, "profileGeometry", JSONObject().put("mode", mode)
            .put("fontScale", app.resources.configuration.fontScale).put("targetsAndText", measures)
            .put("studyActionsPrecedeAccountManagement", true))
        if (mode == "normal") tagged("profile_study_heading").perform(scrollTo())
        capture(if (mode == "normal") "04b-profile-normal-controls" else "05a-profile-large-controls")
        observed(fixture, "${mode}TextProfileSeparatesReachableStudyAndAccountActions")
    }

    private fun assertNotificationSettingsIntent(fixture: JSONObject) {
        val captured = java.util.concurrent.atomic.AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Settings.ACTION_APP_NOTIFICATION_SETTINGS) return null
                captured.set(Intent(intent))
                return Instrumentation.ActivityResult(0, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            tagged("profile_notification_settings").perform(scrollTo()).check(matches(isCompletelyDisplayed())).perform(click())
            onView(withText("Enable study notifications")).check(matches(isCompletelyDisplayed()))
            onView(withText("Open settings")).perform(click())
            await("Notification recovery must launch the app's Android settings intent") { captured.get() != null }
            assertEquals(app.packageName, captured.get()!!.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(fixture.getString("uid"), repository.currentSession()?.uid)
            fixture.put("notificationSettingsIntent", JSONObject().put("action", captured.get()!!.action)
                .put("packageMatchesApp", true).put("intercepted", true).put("externalSettingsUiExercised", false))
            observed(fixture, "profileBlockedNotificationsOfferCorrectSettingsIntent")
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun assertAccountDialog(fixture: JSONObject, mode: String) {
        tagged("profile_account_notifications").perform(scrollTo()).check(matches(isCompletelyDisplayed()))
        append(fixture, "accountDialogGeometry", assertReadable("profile_account_notifications", target = true)
            .put("mode", mode).put("fontScale", app.resources.configuration.fontScale))
        tagged("profile_account_signout").perform(scrollTo()).check(matches(isCompletelyDisplayed()))
        append(fixture, "accountDialogGeometry", assertReadable("profile_account_signout", target = true)
            .put("mode", mode).put("fontScale", app.resources.configuration.fontScale))
        onView(withText("Sign out")).check(matches(isCompletelyDisplayed())).check(matches(isEnabled()))
        onView(withText("Close")).check(matches(isCompletelyDisplayed())).check(matches(isEnabled()))
        observed(fixture, "${mode}TextAccountDialogActionsRemainReachable")
    }

    private fun assertReadable(key: String, target: Boolean = false): JSONObject {
        var result: JSONObject? = null
        tagged(key).check { view, error ->
            if (error != null) throw error
            val text = requireNotNull(view) as TextView
            val density = text.resources.displayMetrics.density
            val layout = requireNotNull(text.layout)
            if (target) assertTrue("$key must retain a 48dp touch target", text.width + 1 >= 48 * density && text.height + 1 >= 48 * density)
            assertTrue("$key text must fit vertically", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
            for (line in 0 until layout.lineCount) {
                assertEquals("$key text must not be ellipsized", 0, layout.getEllipsisCount(line))
                assertTrue("$key text must fit horizontally", layout.getLineWidth(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 2)
            }
            val area = Rect(); assertTrue("$key must be visible", text.getGlobalVisibleRect(area))
            assertTrue("$key must be at least 90% visible", area.width() >= text.width * .9 && area.height() >= text.height * .9)
            result = JSONObject().put("tag", key).put("widthDp", text.width / density).put("heightDp", text.height / density)
                .put("lineCount", layout.lineCount).put("ellipsized", false).put("atLeast90PercentVisible", true)
        }
        return requireNotNull(result)
    }

    private fun append(fixture: JSONObject, key: String, row: JSONObject) {
        val rows = fixture.optJSONArray(key) ?: JSONArray().also { fixture.put(key, it) }
        rows.put(row); save(fixture)
    }

    private fun navigate(id: Int) {
        await("Navigation must be visible") { main { activity()?.findViewById<View>(id)?.isShown == true } }
        onView(withId(id)).perform(click())
    }
    private fun tagged(value: String) = onView(withTagValue(`is`(value as Any)))
    private fun tag(value: String): View? = activity()?.window?.decorView?.findViewWithTag(value)
    private fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun uiTexts() = activity()?.window?.decorView?.let(::texts).orEmpty()
    private fun awaitTag(value: String) = await("UI must render $value") { main { tag(value) != null } }
    private fun awaitText(value: String) = await("UI must render '$value'") { main { value in uiTexts() } }
    private fun await(message: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val frames = CountDownLatch(1)
        main { requireNotNull(activity()).window.decorView.postOnAnimation { requireNotNull(activity()).window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5, TimeUnit.SECONDS)); SystemClock.sleep(100)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(app.filesDir, "onboarding-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun read() = JSONObject(fixtureFile.readText())
    private fun save(value: JSONObject) { fixtureFile.writeText(value.toString()) }
    private fun observed(value: JSONObject, key: String) { value.getJSONObject("observations").put(key, true); save(value) }

    companion object {
        private const val PASSWORD = "LocalOnboarding123!"
        private const val CONFIRMED = "Preferences saved"
        private const val PROFILE_REMINDER_ID = "ui_profile_pending_reminder"
        private const val PROFILE_REMINDER_TITLE = "Synthetic profile reminder"
    }
}
