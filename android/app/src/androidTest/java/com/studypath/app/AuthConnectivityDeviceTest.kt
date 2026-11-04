package com.studypath.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.firebase.auth.FirebaseAuth
import com.studypath.app.ui.auth.LoginActivity
import com.studypath.app.ui.auth.RegisterActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Fresh test AVD only: local loopback build, with Auth port 9099 deliberately not forwarded. */
@RunWith(AndroidJUnit4::class)
class AuthConnectivityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository

    @Before fun requireIsolatedUnreachableLocalAuth() {
        val selected = InstrumentationRegistry.getArguments().getString("class")?.substringBefore('#')
        assumeTrue("Select this connectivity probe explicitly", selected == javaClass.name)
        assertTrue("Only local Firebase test configuration is allowed",
            BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertEquals("Build this probe for unforwarded device loopback", "127.0.0.1", BuildConfig.FIREBASE_EMULATOR_HOST)
        assertTrue("Use the isolated Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val avd = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("Never run against the persistent preview", "StudyPath_Lite_Test", avd)
        assertNull("Restart the read-only test AVD for a fresh registration attempt", repository.boundUid())
        assertNull(repository.currentSession())
        assertNull(FirebaseAuth.getInstance().currentUser)
        val authReachable = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9099), 1_000) }
            true
        } catch (_: IOException) { false }
        assertFalse("Remove only this test AVD's reverse mapping for 9099 before running", authReachable)
    }

    @Test fun unreachableAuthExplainsLocalBuildAndRestoresRegistrationControls() {
        val email = "connectivity_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
        ActivityScenario.launch(RegisterActivity::class.java).use { scenario ->
            lateinit var registration: RegisterActivity
            scenario.onActivity { registration = it }
            onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText("LocalConnectivity123!"))
            closeSoftKeyboard()
            val startedAt = SystemClock.elapsedRealtime()
            onView(withId(R.id.createButton)).perform(scrollTo(), click())
            await("Unreachable Auth must end the busy state with local-backend recovery guidance",
                diagnostics = { captureFailure(registration, startedAt, "registration recovery") }) {
                main {
                    val message = registration.findViewById<TextView>(R.id.statusText).text.toString()
                    message.contains("local Firebase test services") && message.contains("cloud-connected build") &&
                        registration.findViewById<View>(R.id.progress).visibility == View.GONE &&
                        registration.findViewById<View>(R.id.createButton).isEnabled &&
                        registration.findViewById<View>(R.id.signInButton).isEnabled
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            onView(withId(R.id.emailInput)).perform(scrollTo())
                .check(matches(isEnabled())).check(matches(withText(email)))
            onView(withId(R.id.passwordInput)).perform(scrollTo())
                .check(matches(isEnabled())).check(matches(withText("")))
            onView(withId(R.id.createButton)).perform(scrollTo()).check(matches(isEnabled()))
            onView(withId(R.id.signInButton)).perform(scrollTo()).check(matches(isEnabled()))
            assertNull("A refused Auth connection must not bind an account", repository.boundUid())
            assertNull("Registration must not invent a repository session", repository.currentSession())
            assertNull("No Firebase account may be authenticated", FirebaseAuth.getInstance().currentUser)
            val message = main { registration.findViewById<TextView>(R.id.statusText).text.toString() }
            onView(withId(R.id.statusText)).perform(scrollTo())
            capture("registration-recovered")
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText("RetryConnectivity123!"))
                .check(matches(withText("RetryConnectivity123!")))
            closeSoftKeyboard()
            onView(withId(R.id.signInButton)).perform(scrollTo(), click())
            await("The recovered sign-in action must open usable Login controls",
                diagnostics = { captureFailure(registration, startedAt, "login navigation") }) {
                main {
                    val login = resumedActivity(LoginActivity::class.java)
                    login != null && login.findViewById<View>(R.id.progress).visibility == View.GONE &&
                        login.findViewById<View>(R.id.signInButton).isEnabled
                }
            }
            assertNull(repository.boundUid())
            assertNull(repository.currentSession())
            File(app.getExternalFilesDir(null), "auth-connectivity-device.json").writeText(JSONObject()
                .put("test", "unreachableAuthExplainsLocalBuildAndRestoresRegistrationControls")
                .put("localFirebaseOnly", true).put("host", BuildConfig.FIREBASE_EMULATOR_HOST)
                .put("authPortInitiallyUnreachable", true).put("elapsedMillis", elapsed)
                .put("statusMessage", message).put("progressGone", true)
                .put("registrationControlsRecovered", true).put("sameEmailRetained", true)
                .put("passwordDraftCleared", true).put("passwordCanBeEditedAgain", true)
                .put("signInActionOpenedLogin", true).put("uidUnbound", true).put("sessionAbsent", true)
                .put("limits", "Local unreachable-Auth UI recovery only. No cloud account, profile acknowledgement, successful registration or timeout-specific device acceptance.")
                .toString(2))
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(app.getExternalFilesDir(null), "auth-connectivity-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun captureFailure(registration: RegisterActivity, startedAt: Long, phase: String): String {
        val diagnostic = JSONObject().put("phase", phase)
            .put("elapsedMillis", SystemClock.elapsedRealtime() - startedAt)
            .put("localFirebaseOnly", BuildConfig.USE_FIREBASE_EMULATORS)
            .put("emulatorHost", BuildConfig.FIREBASE_EMULATOR_HOST)
        runCatching { main {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            diagnostic.put("originalRegistration", activitySnapshot(registration))
                .put("resumedActivities", JSONArray(resumed.map { activitySnapshot(it) }))
                .put("boundUidPresent", repository.boundUid() != null)
                .put("repositorySessionPresent", repository.currentSession() != null)
                .put("firebaseUserPresent", FirebaseAuth.getInstance().currentUser != null)
        } }.exceptionOrNull()?.let { diagnostic.put("snapshotErrorType", it.javaClass.name) }
        val file = File(app.getExternalFilesDir(null), "auth-connectivity-failure.json")
        file.writeText(diagnostic.toString(2))
        val screenshot = runCatching { capture("registration-failure") }
        diagnostic.put("screenshotSaved", screenshot.isSuccess)
        screenshot.exceptionOrNull()?.let { diagnostic.put("screenshotErrorType", it.javaClass.name) }
        file.writeText(diagnostic.toString(2))
        return diagnostic.toString()
    }

    private fun activitySnapshot(activity: Activity): JSONObject {
        val status = activity.findViewById<TextView>(R.id.statusText)?.text?.toString()
            ?.replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+", RegexOption.IGNORE_CASE), "<email>")
        fun enabled(id: Int): Any = activity.findViewById<View>(id)?.isEnabled ?: JSONObject.NULL
        return JSONObject().put("activity", activity.javaClass.name)
            .put("lifecycleStage", runCatching {
                ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity).name
            }.getOrDefault("UNKNOWN"))
            .put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
            .put("statusText", status ?: JSONObject.NULL)
            .put("statusVisibility", activity.findViewById<View>(R.id.statusText)?.visibility ?: JSONObject.NULL)
            .put("progressVisibility", activity.findViewById<View>(R.id.progress)?.visibility ?: JSONObject.NULL)
            .put("createEnabled", enabled(R.id.createButton)).put("signInEnabled", enabled(R.id.signInButton))
            .put("emailEnabled", enabled(R.id.emailInput)).put("passwordEnabled", enabled(R.id.passwordInput))
    }

    private fun await(description: String, diagnostics: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        val evidence = diagnostics?.let { collect -> runCatching(collect).getOrElse {
            "Diagnostic collection failed: ${it.javaClass.name}"
        } }
        throw AssertionError(if (evidence == null) description else "$description\n$evidence")
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun resumedActivity(type: Class<out Activity>): Activity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
}
