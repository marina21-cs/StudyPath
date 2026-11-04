package com.studypath.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.auth.LoginActivity
import com.studypath.app.ui.auth.RegisterActivity
import com.studypath.app.ui.auth.EmailVerificationActivity
import com.studypath.app.ui.auth.WelcomeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.hamcrest.Matchers.`is`
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Run this selected method on a fresh read-only StudyPath_Lite_Test AVD, with local Firebase. */
@RunWith(AndroidJUnit4::class)
class NativeAuthRevisionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val observed = mutableListOf<String>()

    @Before fun selectedLocalTestOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.NativeAuthRevisionDeviceTest") == true)
        assertTrue("This test must never send cloud reset email", BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertNull("Restart only the isolated read-only test AVD before this registration flow", repository.boundUid())
        assertNull(repository.currentSession())
    }

    @Test fun minimalRegistrationSubjectsReadyAndPasswordRecovery() {
        val email = "revision_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
        val password = "LocalRevision123!"
        ActivityScenario.launch(WelcomeActivity::class.java).use {
            awaitActivity(WelcomeActivity::class.java)
            await("Welcome must finish checking the fresh installation") { main { currentRoot()?.findViewById<View>(R.id.signUpButton)?.isEnabled == true } }
            capture("00-welcome")
            onView(withId(R.id.signUpButton)).perform(scrollTo(), click())
            awaitActivity(RegisterActivity::class.java)
            assertEquals("Registration exposes only the two required credentials", 2, main {
                countViews(currentRoot()!!) { it is com.google.android.material.textfield.TextInputEditText && it.isShown }
            })
            onView(withId(R.id.createButton)).perform(scrollTo(), click())
            assertNotNull("Missing email gets inline validation", main { currentRoot()?.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.emailLayout)?.error })
            onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
            closeSoftKeyboard()
            onView(withId(R.id.createButton)).perform(scrollTo(), click())
            awaitActivity(EmailVerificationActivity::class.java)
            onView(withId(R.id.continueStudyingButton)).perform(scrollTo(), click())
            awaitActivity(MainActivity::class.java)
            await("Preferences must load before choosing a subject") { main { tag("preference_subject_programming")?.isEnabled == true } }
            onView(withTagValue(`is`("preference_display_name" as Any))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withTagValue(`is`("preference_next" as Any))).perform(scrollTo(), click())
            observed += "twoFieldRegistrationCreatesFirebaseSession"
            capture("01-subjects")
            onView(withTagValue(`is`("preference_subject_programming" as Any))).perform(scrollTo(), click())
            assertTrue(main { (tag("preference_subject_programming") as com.google.android.material.button.MaterialButton).isChecked })
            onView(withTagValue(`is`("preference_save" as Any))).perform(scrollTo(), click())
            await("Continue must open the ready handoff") { main { tag("onboarding_ready") != null } }
            await("Selected subject must carry into Ready") { main { (tag("ready_subjects") as? TextView)?.text?.contains("Programming") == true } }
            onView(withTagValue(`is`("preference_continue" as Any))).perform(scrollTo()).check(matches(isDisplayed()))
            observed += "subjectSelectionCarriesToReady"
            capture("02-ready")
            onView(withTagValue(`is`("preference_continue" as Any))).perform(click())
            await("Ready must enter the Study destination") { main { tag("study_heading") != null } }
            val owner = repository.currentSession()!!.uid
            assertEquals(owner, repository.boundUid())
            val preferences = runBlocking { withTimeout(30_000) { repository.learnerPreferences().first { "programming" in it.subjectIds } } }
            assertFalse("A subject draft must not become an approved curriculum", preferences.onboardingComplete)
            observed += "studyAvailableWithUnapprovedSubjectDraft"
            main { (resumedActivity(MainActivity::class.java) as MainActivity).logout() }
            awaitActivity(LoginActivity::class.java)
            await("Login must finish checking the logged-out workspace") { main { currentRoot()?.findViewById<View>(R.id.signInButton)?.isEnabled == true } }
            onView(withId(R.id.recoveryButton)).perform(scrollTo(), click())
            assertFalse("Recovery hides the password field", main { currentRoot()?.findViewById<View>(R.id.passwordLayout)?.isShown == true })
            onView(withId(R.id.signInButton)).perform(scrollTo(), click())
            assertNotNull(main { currentRoot()?.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.emailLayout)?.error })
            onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
            closeSoftKeyboard()
            onView(withId(R.id.signInButton)).perform(scrollTo(), click())
            await("Reset request must finish with neutral account-safe copy") { main { currentRoot()?.findViewById<TextView>(R.id.statusText)?.text?.startsWith("If this email is eligible") == true } }
            assertNull("Recovery must not sign in a user", repository.currentSession())
            assertEquals("Recovery must retain the original installation fence", owner, repository.boundUid())
            assertResetEvent(email)
            observed += "resetEmailObservedInLocalAuthEmulator"
            observed += "passwordRecoveryPreservesUidFenceAndLoggedOutState"
            capture("03-recovery-complete")
            onView(withId(R.id.signInButton)).perform(scrollTo(), click())
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
            closeSoftKeyboard()
            onView(withId(R.id.signInButton)).perform(scrollTo(), click())
            awaitActivity(MainActivity::class.java)
            assertEquals("A reset request does not change the password or bound account", owner, repository.currentSession()?.uid)
            observed += "originalPasswordAndBoundWorkspaceStillResume"
            val receipt = JSONObject().put("test", "minimalRegistrationSubjectsReadyAndPasswordRecovery")
                .put("uid", owner).put("localFirebaseOnly", true).put("observed", org.json.JSONArray(observed))
                .put("limits", "Local emulator acceptance only. No real email delivery, reset-link redemption, cloud deployment, or extended offline acceptance.")
            File(app.getExternalFilesDir(null), "native-auth-revision.json").writeText(receipt.toString(2))
        }
    }

    private fun assertResetEvent(email: String) {
        val url = "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9099/emulator/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}/oobCodes"
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertTrue("Auth emulator must expose independent reset evidence", response.isSuccessful)
            val codes = JSONObject(response.body!!.string()).optJSONArray("oobCodes") ?: error("Missing emulator OOB list")
            assertTrue("An actual PASSWORD_RESET event must exist for the synthetic owner", (0 until codes.length()).any {
                val event = codes.getJSONObject(it)
                event.optString("email") == email && event.optString("requestType") == "PASSWORD_RESET"
            })
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val output = File(app.getExternalFilesDir(null), "native-auth-revision-screenshots").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun awaitActivity(type: Class<out Activity>) = await("${type.simpleName} must resume") { main { resumedActivity(type) != null } }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 90_000
        do { instrumentation.waitForIdleSync(); if (condition()) return; SystemClock.sleep(50) } while (SystemClock.elapsedRealtime() < end)
        throw AssertionError(description)
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun resumedActivity(type: Class<out Activity>) = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
    private fun currentRoot() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
    private fun tag(value: String) = currentRoot()?.findViewWithTag<View>(value)
    private fun countViews(view: View, matches: (View) -> Boolean): Int = (if (matches(view)) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { countViews(view.getChildAt(it), matches) } else 0
}
