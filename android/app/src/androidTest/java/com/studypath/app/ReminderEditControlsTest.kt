package com.studypath.app

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
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
import com.studypath.app.data.StudyReminder
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.ReminderScheduler
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderEditControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()

    @Before fun selectedOnly() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.ReminderEditControlsTest#") == true)

    @Test fun existingReminderExposesEditAndDisable() {
        val repository = app.graph.repository
        assertTrue("Only a fresh local test installation may run this check",
            BuildConfig.USE_FIREBASE_EMULATORS && repository.boundUid() == null)
        runBlocking {
            withTimeout(30_000) {
                repository.register("edit_${UUID.randomUUID()}@studypath.invalid", "LocalReminder123!", "Edit Learner")
                repository.saveReminder(StudyReminder("edit_check", "programming_basics",
                    "Reminder editing check", System.currentTimeMillis() + 300_000, true, "UTC"))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("What will you learn?")
            onView(withId(R.id.nav_account)).perform(click())
            awaitText("Study reminders")
            onView(withText("Study reminders")).perform(scrollTo(), click())
            onView(withText(containsString("Reminder editing check"))).perform(click())
            onView(withText("Edit reminder")).check(matches(isDisplayed()))
            onView(withText("Turn off")).check(matches(isDisplayed()))
        }
    }

    @Test fun reminderMutationUpdatesWorkWithoutAVisibleActivity() = runBlocking {
        val repository = app.graph.repository
        assertTrue("Use only a prepared local test workspace", BuildConfig.USE_FIREBASE_EMULATORS && repository.boundUid() != null)
        val uid = requireNotNull(repository.resumeSession()).uid
        assertTrue("This component check needs notification eligibility", ReminderScheduler.allowed(app))
        val row = StudyReminder("background_mutation", "programming_basics", "Background mutation check",
            System.currentTimeMillis() + 120_000, true, "UTC")
        repository.saveReminder(row)
        ReminderScheduler.reconcile(app, uid, repository.reminders().first())
        val key = "study_reminder_${uid}_${row.id}"
        val work = WorkManager.getInstance(app)
        val old = work.getWorkInfosForUniqueWork(key).get(5, TimeUnit.SECONDS).single { !it.state.isFinished }
        val changed = row.copy(dueAt = row.dueAt + 120_000)
        repository.saveReminder(changed)
        assertTrue("An acknowledged edit must replace its schedule without a visible Activity observer",
            app.getSharedPreferences("reminder_schedule", 0).getLong(key, -1) == changed.dueAt)
        withTimeout(10_000) {
            while (work.getWorkInfoById(old.id).get(5, TimeUnit.SECONDS)?.state.let {
                    it != null && it != WorkInfo.State.CANCELLED
                }) delay(25)
        }
        repository.deleteReminder(row.id)
        assertTrue("An acknowledged deletion must cancel its schedule without an Activity observer",
            app.getSharedPreferences("reminder_schedule", 0).getLong(key, 0) == -1L)
    }

    private fun awaitText(text: String) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.waitForIdleSync()
            var found = false
            instrumentation.runOnMainSync {
                val activity: Activity? = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull()
                found = activity?.window?.decorView?.let { contains(it, text) } == true
            }
            if (found) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Expected reminder test screen did not appear")
    }

    private fun contains(view: View, text: String): Boolean =
        view is TextView && view.text.toString() == text ||
            view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it), text) }
}
