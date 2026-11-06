package com.studypath.app

import kotlinx.coroutines.flow.first

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.auth.LoginActivity
import com.studypath.app.ui.auth.RegisterActivity
import com.studypath.app.ui.auth.EmailVerificationActivity
import com.studypath.app.ui.auth.WelcomeActivity
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.`is`
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/**
 * Real local Firebase Auth/RTDB smoke test. The default run has no network API dependency.
 * Pass -e verifyLiveReading true to additionally verify the real Wikipedia-to-saved-reading flow.
 * Prerequisites: fresh app data, running local Auth/RTDB emulators, seeded demonstration catalog.
 * The synthetic user is intentionally disposable. Never run this against a cloud Firebase project.
 */
@RunWith(AndroidJUnit4::class)
class StudyPathSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private var cleanupUid: String? = null
    private var cleanupCreatedAt: Long? = null
    private var cleanupStartedAt = 0L
    private var cleanupProfile: String? = null
    private var signupSubmitted = false
    private var cleanupStep = "not-started"
    private var flowStage = "guards"
    private var lastAwaitFailure: String? = null
    private var timeoutCaptureFailure: String? = null
    private val cleanupClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    @Test
    fun registerBrowseSeededCatalogLogoutAndSignInToBoundWorkspace() {
        assertTrue("Smoke tests are restricted to the local Firebase emulator configuration", BuildConfig.USE_FIREBASE_EMULATORS)
        assertEquals("demo-studypath", BuildConfig.FIREBASE_PROJECT_ID)
        assertEquals("https://demo-studypath-default-rtdb.firebaseio.com", BuildConfig.FIREBASE_DATABASE_URL)
        val avd = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
            .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        assertEquals("Never register or clean the persistent preview", "StudyPath_Lite_Test", avd)
        assertNull("Install with fresh app data before running this smoke test", app.graph.repository.boundUid())
        assertNull(app.graph.repository.currentSession())
        assertNull(FirebaseAuth.getInstance().currentUser)
        val email = "smoke_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
        val password = "LocalSmoke123!"
        cleanupStartedAt = System.currentTimeMillis()
        var flowFailure: String? = null
        var flowFailureLocation: String? = null
        var cleanup = JSONObject().put("status", "NOT_ATTEMPTED")
        try {
        ActivityScenario.launch(WelcomeActivity::class.java).use {
            awaitActivity(WelcomeActivity::class.java)
            await("Welcome sign-up action must finish checking the fresh workspace") {
                mainThreadValue { resumedActivity(WelcomeActivity::class.java)?.findViewById<View>(R.id.signUpButton)?.isEnabled == true }
            }
            onView(withId(R.id.signUpButton)).check(matches(isCompletelyDisplayed()))
            onView(withId(R.id.loginButton)).check(matches(isCompletelyDisplayed()))
            captureScreen("00-welcome")
            onView(withId(R.id.signUpButton)).perform(scrollTo(), click())
            awaitActivity(RegisterActivity::class.java)
            captureScreen("01-register-empty")
            onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
            closeSoftKeyboard()
            signupSubmitted = true
            onView(withId(R.id.createButton)).perform(scrollTo(), click())

            awaitActivity(EmailVerificationActivity::class.java)
            val createdUser = requireSmokeOwner(email, capture = true)
            val initial = smokeTree(smokeToken(createdUser)).body
            cleanupProfile = canonical(JSONObject(initial).getJSONObject("profile"))
            onView(withId(R.id.continueStudyingButton)).perform(scrollTo(), click())
            awaitActivity(MainActivity::class.java)
            leaveLoadedPreferences()
            awaitTag("study_heading")
            onView(withTagValue(`is`("study_heading" as Any))).check(matches(isDisplayed()))
            captureScreen("02-study-home")
            val firstSession = app.graph.repository.currentSession()
            assertNotNull("UI registration must create a real Firebase-backed repository session", firstSession)
            assertEquals(email, firstSession!!.email)
            assertEquals(firstSession.uid, app.graph.repository.boundUid())

            tapNavigation(R.id.nav_saved)
            awaitTag("saved_lessons_tab")
            onView(withTagValue(`is`("saved_lessons_tab" as Any))).perform(scrollTo(), click())
            awaitText("0 ready offline")
            assertTrue("Saved must not expose legacy demonstration lessons", mainThreadValue {
                currentRoot()?.let { !containsText(it) { value -> value == "Algorithms and programming" } } == true
            })
            captureScreen("03-study-shelf")

            if (InstrumentationRegistry.getArguments().getString("verifyPersonalWorkspace") == "true") verifyPersonalWorkspace()
            flowStage = "planner-catalog-and-policy"
            tapNavigation(R.id.nav_planner)
            onView(withText("Grades")).perform(click())
            onView(withTagValue(`is`("planner_reviewed_tools" as Any))).perform(scrollTo(), click())
            awaitText("Programming Foundations")
            awaitText("Planning unavailable")
            onView(withText("Planning unavailable")).perform(scrollTo()).check(matches(isDisplayed()))
            assertTrue("The demonstration catalog must not expose score or target controls", mainThreadValue {
                currentRoot()?.let { root -> !containsText(root) { value -> value == "Add scores" || value == "Set a target" } } == true
            })
            captureScreen("04-planner")
            flowStage = "planner-to-supported-preferences"
            awaitText("Change learning preferences")
            onView(withText("Change learning preferences")).perform(scrollTo(), click())
            awaitTag("learner_preferences")
            onView(withTagValue(`is`("preference_heading" as Any))).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals("Opening preferences must retain the original workspace", firstSession.uid, app.graph.repository.boundUid())
            captureScreen("04b-planner-policy-preferences")
            androidx.test.espresso.Espresso.pressBack()
            awaitText("Planning unavailable")
            onView(withText("Planning unavailable")).perform(scrollTo()).check(matches(isDisplayed()))

            pressBack() // Reviewed tools return to the compact Planner.
            tapNavigation(R.id.nav_account)
            awaitTag("profile_name")
            onView(withTagValue(`is`("profile_name" as Any))).check(matches(isDisplayed()))
            onView(withText("Change picture")).perform(scrollTo(), click())
            onView(withTagValue(`is`("avatar_choice_hoodie" as Any))).perform(scrollTo(), click())
            assertEquals("hoodie", com.studypath.app.ui.ProfileAvatars.selected(app, firstSession.uid))
            tapNavigation(R.id.nav_saved)
            tapNavigation(R.id.nav_account)
            awaitTag("profile_avatar")
            onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Blue hoodie")))
            captureScreen("05-account")
            onView(withTagValue(`is`("profile_demo" as Any))).perform(scrollTo(), click())
            awaitActivity(com.studypath.app.ui.DemoActivity::class.java)
            onView(withText("Exit")).perform(click())
            onView(withText("Exit demo")).perform(click())
            awaitActivity(MainActivity::class.java)
            assertEquals("Demo exit preserves the signed-in account", firstSession.uid, app.graph.repository.currentSession()?.uid)
            onView(withTagValue(`is`("profile_settings" as Any))).perform(scrollTo(), click())
            onView(withTagValue(`is`("settings_reminders" as Any))).perform(scrollTo(), click())
            onView(withTagValue(`is`("profile_reminders_action" as Any))).perform(scrollTo(), click())
            onView(withText(containsString("Reminders are off until you add one from a lesson or saved reading"))).check(matches(isDisplayed()))
            onView(withText("Got it")).perform(click())
            pressBack()
            onView(withTagValue(`is`("settings_account" as Any))).perform(scrollTo(), click())
            onView(withTagValue(`is`("profile_account_action" as Any))).perform(scrollTo(), click())
            onView(withText(containsString(email))).check(matches(isDisplayed()))
            onView(withTagValue(`is`("profile_account_signout" as Any))).perform(scrollTo(), click())
            onView(withTagValue(`is`("profile_confirm_signout" as Any))).perform(scrollTo(), click())

            awaitActivity(LoginActivity::class.java)
            awaitText("Welcome back.")
            await("The repository must end its signed-in session") { app.graph.repository.currentSession() == null }
            assertEquals("Logout must retain the original installation binding", firstSession.uid, app.graph.repository.boundUid())
            onView(withId(R.id.accountDetailsButton)).perform(scrollTo(), click())
            onView(withId(R.id.accountNotice)).perform(scrollTo())
                .check(matches(withText(containsString("original account"))))
            onView(withId(R.id.registerButton)).perform(scrollTo()).check(matches(not(isEnabled())))
            captureScreen("06-login-bound-workspace")

            await("Sign-in controls must finish checking the logged-out workspace") {
                mainThreadValue { resumedActivity(LoginActivity::class.java)?.findViewById<View>(R.id.signInButton)?.isEnabled == true }
            }
            onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
            closeSoftKeyboard()
            onView(withId(R.id.signInButton)).perform(scrollTo(), click())

            awaitActivity(MainActivity::class.java)
            leaveLoadedPreferences()
            awaitTag("study_heading")
            assertEquals("Same-account sign-in must restore the bound UID", firstSession.uid, app.graph.repository.currentSession()?.uid)
            assertEquals(email, app.graph.repository.currentSession()?.email)
            tapNavigation(R.id.nav_saved)
            awaitTag("saved_lessons_tab")
            onView(withTagValue(`is`("saved_lessons_tab" as Any))).perform(scrollTo(), click())
            awaitText("0 ready offline")
            if (InstrumentationRegistry.getArguments().getString("verifyLiveReading") == "true") {
                verifyLiveReading()
            }
        }
        } catch (error: Throwable) {
            runCatching { captureTimeout("Action failure at $flowStage", 0) }
            flowFailure = error.javaClass.simpleName
            flowFailureLocation = error.stackTrace.firstOrNull { it.className == javaClass.name }
                ?.let { "${it.fileName}:${it.lineNumber}" }
        } finally {
            try { cleanup = cleanSmokeOwner(email, password) }
            catch (error: Throwable) { cleanup = JSONObject().put("status", "FAILED_CLOSED")
                .put("step", cleanupStep).put("failureType", error.javaClass.simpleName) }
            val passed = flowFailure == null && cleanup.optString("status") == "CONFIRMED"
            File(app.getExternalFilesDir(null), "baseline-owned-cleanup.json").writeText(JSONObject()
                .put("status", if (passed) "PASSED" else "FAILED").put("localFirebaseOnly", true)
                .put("uidSha256", cleanupUid?.let(::smokeDigest) ?: JSONObject.NULL)
                .put("emailSha256", smokeDigest(email)).put("flowFailureType", flowFailure ?: JSONObject.NULL)
                .put("flowFailureStage", if (flowFailure == null) JSONObject.NULL else flowStage)
                .put("flowFailureLocation", flowFailureLocation ?: JSONObject.NULL)
                .put("flowAwaitFailure", lastAwaitFailure ?: JSONObject.NULL)
                .put("timeoutCaptureFailureType", timeoutCaptureFailure ?: JSONObject.NULL)
                .put("cleanup", cleanup).toString(2))
            if (!passed) throw AssertionError("Baseline failed at $flowStage (${flowFailure ?: "cleanup"}); " +
                "cleanup=${cleanup.optString("status")}; inspect baseline-owned-cleanup.json")
        }
    }

    private fun requireSmokeOwner(email: String, capture: Boolean = false): FirebaseUser {
        val user = FirebaseAuth.getInstance().currentUser ?: throw AssertionError("Owned smoke account unavailable")
        assertTrue("Only the generated smoke address is owned", email.startsWith("smoke_") &&
            email.endsWith("@studypath.invalid") && user.email == email && !user.isAnonymous)
        val created = user.metadata?.creationTimestamp ?: throw AssertionError("Creation identity unavailable")
        assertTrue(created in (cleanupStartedAt - 120_000)..(System.currentTimeMillis() + 60_000))
        if (cleanupUid == null) { assertTrue("Only signup may capture ownership", capture); cleanupUid = user.uid; cleanupCreatedAt = created }
        assertEquals(cleanupUid, user.uid); assertEquals(cleanupCreatedAt, created)
        assertEquals(cleanupUid, app.graph.repository.boundUid())
        return user
    }

    private fun smokeToken(user: FirebaseUser): String {
        val result = Tasks.await(user.getIdToken(true), 30, TimeUnit.SECONDS)
        assertEquals(cleanupUid, result.claims["sub"])
        return result.token ?: throw AssertionError("Fresh owned token unavailable")
    }

    private fun cleanSmokeOwner(email: String, password: String): JSONObject {
        cleanupStep = "require-captured-identity"
        val auth = FirebaseAuth.getInstance()
        if (cleanupUid == null) return if (!signupSubmitted && auth.currentUser == null)
            JSONObject().put("status", "CONFIRMED").put("accountCreated", false)
        else JSONObject().put("status", "FAILED_CLOSED").put("reason", "Signup identity was not captured; nothing deleted")
        if (auth.currentUser == null) Tasks.await(auth.signInWithEmailAndPassword(email, password), 30, TimeUnit.SECONDS)
        var user = requireSmokeOwner(email)
        Tasks.await(user.reload(), 30, TimeUnit.SECONDS); user = requireSmokeOwner(email)
        Tasks.await(user.reauthenticate(EmailAuthProvider.getCredential(email, password)), 30, TimeUnit.SECONDS)
        val token = smokeToken(user)
        cleanupStep = "verify-server-identity"
        val lookup = smokeAuth("lookup", token)
        assertEquals(200, lookup.code)
        val identities = JSONObject(lookup.body).getJSONArray("users")
        assertEquals(1, identities.length())
        val identity = identities.getJSONObject(0)
        assertEquals(cleanupUid, identity.getString("localId")); assertEquals(email, identity.getString("email"))
        assertEquals(cleanupCreatedAt, identity.getString("createdAt").toLong())
        mainThreadValue { app.graph.repository.logout() }
        assertNull(app.graph.repository.currentSession())
        cleanupStep = "validate-baseline-only-tree"
        val snapshot = smokeTree(token, etag = true)
        if (snapshot.body != "null") {
            val tree = JSONObject(snapshot.body)
            val live = InstrumentationRegistry.getArguments().getString("verifyLiveReading") == "true"
            val allowed = setOf("profile") + if (live) setOf("readingShelfVersion", "savedReadings", "savedReadingSlots", "readingAnnotations") else emptySet()
            assertTrue("Unexpected private collections stop cleanup", keys(tree).all { it in allowed })
            assertEquals("Changed profile stops cleanup", cleanupProfile, canonical(tree.getJSONObject("profile")))
            val readings = tree.optJSONObject("savedReadings")
            if (readings != null) {
                assertEquals("Baseline only saves one selected reading", 1, readings.length())
                val id = keys(readings).single(); val reading = readings.getJSONObject(id)
                assertEquals(setOf("pageId", "pageKey", "title", "excerpt", "sourceUrl", "revisionId", "revisionUrl",
                    "revisionTimestamp", "licenseTitle", "licenseUrl", "attribution", "modificationNotice", "retrievedAt", "savedAt"), keys(reading))
                assertEquals("Computer programming", reading.getString("title"))
                assertEquals("Computer_programming", reading.getString("pageKey"))
                assertEquals("wiki_${reading.getLong("pageId")}_${reading.getLong("revisionId")}", id)
                assertEquals(1, tree.getInt("readingShelfVersion"))
                val slots = tree.getJSONObject("savedReadingSlots")
                assertEquals(setOf("s00"), keys(slots)); assertEquals(id, slots.getString("s00"))
                tree.optJSONObject("readingAnnotations")?.let { annotations ->
                    assertEquals(setOf(id), keys(annotations))
                    val note = annotations.getJSONObject(id)
                    assertEquals(setOf("snapshotRevisionId", "excerptLength", "note", "position", "editId", "modifiedAt"), keys(note))
                    assertEquals("", note.getString("note"))
                    assertEquals(reading.getLong("revisionId"), note.getLong("snapshotRevisionId"))
                    assertEquals(reading.getString("excerpt").length, note.getInt("excerptLength"))
                    assertTrue(note.getInt("position") in 0..note.getInt("excerptLength"))
                }
            } else assertEquals("No orphan reading metadata may be deleted", setOf("profile"), keys(tree))
            assertNotNull(snapshot.etag)
            cleanupStep = "conditional-exact-tree-deletion"
            assertEquals(200, smokeTree(token, method = "DELETE", ifMatch = snapshot.etag).code)
        }
        assertEquals("Owned tree must be absent on fresh readback", "null", smokeTree(token).body)
        cleanupStep = "delete-exact-auth-account"
        assertEquals(200, smokeAuth("delete", token).code)
        val absent = smokeAuth("lookup", token)
        assertEquals(400, absent.code)
        assertEquals("USER_NOT_FOUND", JSONObject(absent.body).getJSONObject("error").getString("message"))
        assertNull(auth.currentUser)
        return JSONObject().put("status", "CONFIRMED").put("exactOwnedTreeDeletedAndReadBack", true)
            .put("authDeletedAndLookupDenied", true).put("originalBindingRetained", app.graph.repository.boundUid() == cleanupUid)
    }

    private data class SmokeResponse(val code: Int, val body: String, val etag: String?)
    private fun smokeTree(token: String, method: String = "GET", etag: Boolean = false, ifMatch: String? = null): SmokeResponse {
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        val uid = cleanupUid ?: throw AssertionError("No captured cleanup UID")
        val url = "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000".toHttpUrl().newBuilder()
            .addPathSegment("users").addPathSegment("$uid.json").addQueryParameter("ns", "demo-studypath-default-rtdb")
            .addQueryParameter("auth", token).build()
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
        if (etag) request.header("X-Firebase-ETag", "true")
        if (ifMatch != null) request.header("if-match", ifMatch)
        return smokeExecute(request.method(method, null).build()).also { if (method == "GET") assertEquals(200, it.code) }
    }
    private fun smokeAuth(action: String, token: String): SmokeResponse {
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue(action == "lookup" || action == "delete")
        val url = "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/accounts:$action"
            .toHttpUrl().newBuilder().addQueryParameter("key", BuildConfig.FIREBASE_API_KEY).build()
        return smokeExecute(Request.Builder().url(url).post(JSONObject().put("idToken", token).toString()
            .toRequestBody("application/json".toMediaType())).build())
    }
    private fun smokeExecute(request: Request): SmokeResponse = cleanupClient.newCall(request).execute().use { response ->
        val body = response.body ?: throw AssertionError("Cleanup response unavailable")
        assertTrue(body.contentLength() <= 262_144)
        val source = body.source(); source.request(262_145)
        assertTrue("Cleanup response exceeds bound", source.buffer.size <= 262_144)
        SmokeResponse(response.code, source.buffer.readByteArray().toString(Charsets.UTF_8), response.header("ETag"))
    }
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun canonical(value: JSONObject): String = keys(value).sorted().joinToString("|", "{", "}") { key ->
        val item = value.get(key); "$key:${if (item is JSONObject) canonical(item) else item}" }
    private fun smokeDigest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun verifyLiveReading() {
        tapNavigation(R.id.nav_study)
        awaitTag("reading_search_query")
        onView(withTagValue(`is`("reading_search_query" as Any))).perform(scrollTo(), replaceText("Computer programming"))
        closeSoftKeyboard()
        onView(withTagValue(`is`("reading_search_submit" as Any))).perform(scrollTo(), click())
        awaitText("Read excerpt →")
        onView(allOf(withText("Read excerpt →"), hasSibling(withText("Computer programming"))))
            .perform(scrollTo(), click())
        awaitTag("reading_excerpt")
        onView(withText("Wikipedia excerpt")).perform(scrollTo()).check(matches(isDisplayed()))
        captureScreen("07-live-reading")
        onView(withTagValue(`is`("reading_save_place" as Any))).perform(scrollTo(), click())
        await("Excerpt and its saved place must each receive confirmation") {
            mainThreadValue {
                val root = currentRoot() ?: return@mainThreadValue false
                (root.findViewWithTag<TextView>("reading_copy_status")?.text?.toString() == "Ready offline") &&
                    (root.findViewWithTag<TextView>("reading_position_status")?.text?.startsWith("Place · saved ·") == true)
            }
        }
        onView(withTagValue(`is`("reading_source" as Any))).perform(scrollTo(), click())
        onView(withText("Open article revision")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
        onView(withText("Open license")).perform(scrollTo()).check(matches(isCompletelyDisplayed()))
        captureScreen("08-reading-attribution")
        onView(withText("Close")).perform(click())
        pressBack()
        tapNavigation(R.id.nav_saved)
        awaitTag("saved_readings_tab")
        onView(withTagValue(`is`("saved_readings_tab" as Any))).perform(scrollTo(), click())
        awaitText("Computer programming")
        onView(allOf(withText("Resume reading →"), hasSibling(withText("Computer programming"))))
            .perform(scrollTo(), click())
        awaitTag("reading_excerpt")
        assertTrue("Reopened reading must retain its excerpt", mainThreadValue {
            currentRoot()?.findViewWithTag<TextView>("reading_excerpt")?.text?.isNotBlank() == true
        })
        onView(withTagValue(`is`("reading_copy_status" as Any))).perform(scrollTo()).check(matches(isDisplayed()))
        captureScreen("09-saved-reading")
    }

    private fun verifyPersonalWorkspace() {
        fun tagged(tag: String) = onView(withTagValue(`is`(tag as Any)))
        tapNavigation(R.id.nav_account)
        tagged("profile_edit_direct").perform(scrollTo(), click())
        awaitTag("edit_profile_name")
        assertTrue(mainThreadValue { currentRoot()?.findViewWithTag<View>("preference_later") == null })
        captureScreen("personal-01-edit-profile")
        val originalName = JSONObject(smokeTree(smokeToken(requireNotNull(FirebaseAuth.getInstance().currentUser))).body)
            .getJSONObject("profile").getString("displayName")
        tagged("edit_profile_name").perform(replaceText("Focus learner")); closeSoftKeyboard()
        tagged("edit_profile_save").perform(scrollTo(), click())
        awaitTag("profile_edit_direct")
        awaitText("Focus learner")
        tagged("profile_edit_direct").perform(scrollTo(), click())
        awaitTag("edit_profile_name")
        tagged("edit_profile_name").perform(replaceText(originalName)); closeSoftKeyboard()
        tagged("edit_profile_save").perform(scrollTo(), click())
        awaitTag("profile_name")
        tapNavigation(R.id.nav_planner)
        onView(withText("Subjects")).perform(click())
        onView(withText("Add or import subjects")).perform(scrollTo(),click())
        onView(withText("Manage subjects")).perform(click())
        awaitTag("personal_add_subject")
        tagged("personal_add_subject").perform(scrollTo(), click())
        onView(withHint("Subject name")).perform(replaceText("Physics")); closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        awaitTag("personal_add_task")
        assertEquals("Task and lesson tabs must align even when a label wraps", mainThreadValue { currentRoot()!!.findViewWithTag<View>("personal_tab_tasks").height },
            mainThreadValue { currentRoot()!!.findViewWithTag<View>("personal_tab_lessons").height })
        tagged("personal_choose_subject").perform(scrollTo(), click())
        onView(withText("Choose subject")).check(matches(isDisplayed()))
        onView(withText("Physics")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        tagged("personal_subject_options").perform(scrollTo(), click())
        onView(withText("Add subject")).check(matches(isDisplayed()))
        onView(withText("Edit subject")).perform(click())
        onView(withHint("Subject name")).check(matches(withText("Physics")))
        onView(withText("Cancel")).perform(click())
        captureScreen("focused-01-subject-header")
        tagged("personal_add_task").perform(scrollTo(), click())
        onView(withHint("Task")).perform(replaceText("Read chapter two")); closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        awaitText("Read chapter two")
        onView(withContentDescription("Mark done: Read chapter two")).perform(scrollTo(), click())
        onView(withContentDescription("Mark unfinished: Read chapter two")).check(matches(isDisplayed()))
        tagged("personal_tab_lessons").perform(scrollTo(), click())
        tagged("personal_create_lesson").perform(scrollTo(), click())
        onView(withHint("Lesson title")).perform(replaceText("Motion basics"))
        onView(withHint("Your lesson text")).perform(scrollTo(), replaceText("Speed compares distance with elapsed time."))
        onView(withHint("Practice question (optional)")).perform(scrollTo(), replaceText("What is speed?"))
        onView(withHint("Answer")).perform(scrollTo(), replaceText("Distance divided by time.")); closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        awaitText("Motion basics")
        onView(withContentDescription("Study Motion basics")).perform(scrollTo(), click())
        awaitTag("personal_reveal_answer")
        tagged("personal_reveal_answer").perform(scrollTo(), click())
        tagged("personal_answer").check(matches(isDisplayed()))
        captureScreen("personal-02-study-card")
        pressBack()
        tagged("personal_import_pdf").perform(scrollTo(), click())
        await("The system PDF picker must open") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
        }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitTag("personal_import_pdf")
        val uid = requireNotNull(app.graph.repository.currentSession()?.uid)
        val store = com.studypath.app.data.PersonalStudyStore(File(app.filesDir, "personal-study"), uid)
        val subjectId = store.read().subjects.single().id
        val fixture = File(app.cacheDir, "personal-native-fixture.pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawText("Personal module page ${index + 1}", 30f, 60f, Paint().apply { textSize = 20f })
                document.finishPage(page)
            }
            fixture.outputStream().use(document::writeTo)
        } finally { document.close() }
        // Exercise the production URI-copy and native PDF validation after the picker result.
        // External DocumentsUI itself is not automated by this in-app Espresso case.
        instrumentation.runOnMainSync {
            val fragment = (resumedActivity(MainActivity::class.java) as MainActivity).supportFragmentManager.findFragmentById(R.id.main_content)!!
            fragment.javaClass.getDeclaredField("pendingSubject").apply { isAccessible = true }.set(fragment, subjectId)
            fragment.javaClass.getDeclaredMethod("importPdf", android.net.Uri::class.java).apply { isAccessible = true }
                .invoke(fragment, android.net.Uri.fromFile(fixture))
        }
        await("The personal PDF must be validated and committed") { store.read().materials.any { it.pdf } }
        val pdf = store.read().materials.single { it.pdf }
        awaitText(pdf.title)
        onView(withContentDescription("Study ${pdf.title}")).perform(scrollTo(), click())
        awaitText("Page 1 of 2 · Available offline")
        tagged("offline_pdf_next").perform(click())
        awaitText("Page 2 of 2 · Available offline")
        tagged("offline_pdf_zoom").perform(click())
        tagged("offline_pdf_jump").perform(click())
        onView(withHint("Page number")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click(), replaceText("1")); closeSoftKeyboard()
        onView(withText("Go")).perform(click())
        awaitText("Page 1 of 2 · Available offline")
        tagged("offline_pdf_zoom").perform(click())
        captureScreen("personal-03-pdf-page-two")
        pressBack()
        // The manual workspace does not depend on a live database or a published course pack.
        com.google.firebase.database.FirebaseDatabase.getInstance().goOffline()
        pressBack()
        onView(withText("Subjects")).perform(click())
        onView(withText("Add or import subjects")).perform(scrollTo(),click())
        onView(withText("Manage subjects")).perform(click())
        awaitTextContaining("Physics")
        assertEquals(2, store.read().materials.size)
        tagged("personal_tab_tasks").perform(scrollTo(), click())
        tagged("personal_add_task").perform(scrollTo(), click())
        onView(withHint("Task")).perform(replaceText("Review offline")); closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        awaitText("Review offline")
        captureScreen("personal-04-offline-workspace")
        pressBack()
        onView(withText("Overview")).perform(click())
        awaitTextContaining("Review offline")
        captureScreen("personal-05-manual-planner")
        com.google.firebase.database.FirebaseDatabase.getInstance().goOnline()
        fixture.delete()
        onView(withText("Grades")).perform(click())
        tagged("planner_reviewed_tools").perform(scrollTo(),click())
        val catalogUrl = "http://127.0.0.1:9000/catalog/courses.json?ns=demo-studypath-default-rtdb"
        val original = cleanupClient.newCall(Request.Builder().url(catalogUrl).header("Authorization", "Bearer owner").build()).execute().use {
            check(it.isSuccessful); it.body!!.string()
        }
        fun setCourses(json: String) {
            cleanupClient.newCall(Request.Builder().url(catalogUrl).header("Authorization", "Bearer owner")
                .put(json.toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.isSuccessful) }
        }
        try {
            setCourses("null")
            awaitText("No course pack yet")
            tagged("planner_personal_workspace").perform(scrollTo()).check(matches(isDisplayed()))
            captureScreen("personal-06-empty-catalog-planner")
            tagged("planner_course_packs").perform(scrollTo(), click())
            awaitTag("course_packs_empty")
            captureScreen("connected-01-course-packs")
            tagged("course_packs_personal").perform(scrollTo(), click())
            awaitTag("personal_tab_tasks")
            pressBack(); pressBack()
        } finally { setCourses(original) }
        // Exercise a published choice only in the isolated emulator; remove this owned fixture afterward.
        val optionUrl = "http://127.0.0.1:9000/catalog/learnerPreferenceOptions/connected-test-pack.json?ns=demo-studypath-default-rtdb"
        fun writeOption(body: String) {
            cleanupClient.newCall(Request.Builder().url(optionUrl).header("Authorization", "Bearer owner")
                .put(body.toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.isSuccessful) }
        }
        val beforePackProfile = JSONObject(smokeTree(smokeToken(requireNotNull(FirebaseAuth.getInstance().currentUser))).body).getJSONObject("profile")
        val previousPreferences = kotlinx.coroutines.runBlocking { app.graph.repository.learnerPreferences().first() }
        try {
            writeOption("""{"schemaVersion":1,"curriculumVersion":"connected-test-pack","levelId":"college","subjectIds":{"test-math":true},"approved":true,"approvalStatus":"APPROVED"}""")
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10000) { app.graph.repository.learnerPreferenceOptions().first { rows -> rows.any { it.curriculumVersion == "connected-test-pack" } } } }
            tagged("planner_course_packs").perform(scrollTo(), click())
            awaitTag("course_pack_connected-test-pack")
            tagged("course_pack_connected-test-pack").perform(scrollTo(), click())
            onView(withText("Use pack")).perform(click())
            awaitTextContaining("Selected")
            assertEquals("connected-test-pack", kotlinx.coroutines.runBlocking { app.graph.repository.learnerPreferences().first() }.curriculumVersion)
            captureScreen("connected-08-selected-test-pack")
            pressBack()
        } finally {
            try {
                kotlinx.coroutines.runBlocking { app.graph.repository.saveLearnerPreferences(previousPreferences) }
                val restored = JSONObject(smokeTree(smokeToken(requireNotNull(FirebaseAuth.getInstance().currentUser))).body).getJSONObject("profile")
                assertTrue(restored.getLong("updatedAt") > 0L)
                val expected = JSONObject(beforePackProfile.toString())
                    .put("learnerPreferences", JSONObject(com.studypath.app.data.LearnerPreferenceRecords.preferences(previousPreferences)))
                    .put("updatedAt", restored.getLong("updatedAt"))
                assertEquals("Pack test may only change the explicitly restored preferences and timestamp", canonical(expected), canonical(restored))
                cleanupProfile = canonical(expected)
            } finally { writeOption("null") }
        }
        pressBack() // Leave reviewed tools before using root navigation.
        tapNavigation(R.id.nav_saved)
        tagged("saved_lessons_tab").perform(scrollTo(), click())
        awaitTag("saved_material_${pdf.id}")
        tagged("saved_material_${pdf.id}").perform(scrollTo()).check(matches(isDisplayed()))
        captureScreen("connected-02-saved-materials")
        tapNavigation(R.id.nav_study)
        awaitTag("subject_$subjectId")
        tagged("subject_$subjectId").perform(scrollTo()).check(matches(isDisplayed()))
        captureScreen("connected-03-study-subjects")
        tapNavigation(R.id.nav_planner)
        onView(withText("Subjects")).perform(click())
        tagged("planner_subject_$subjectId").perform(scrollTo(), click())
        tagged("personal_tab_grades").perform(scrollTo(), click())
        tagged("personal_edit_academics").perform(scrollTo(), click())
        onView(withHint("Course code")).perform(replaceText("PHY001"))
        onView(withHint("Units")).perform(replaceText("3"))
        onView(withHint("Recorded grade")).perform(replaceText("1.75")); closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        awaitText("1.75")
        assertEquals("1.75", store.read().subjects.single().grade)
        captureScreen("connected-04-recorded-grade")
        pressBack()
        awaitTextContaining("Physics")
        onView(withText("Add or import subjects")).perform(scrollTo(),click())
        onView(withText("Import COR")).perform(click())
        tagged("cor_sample").perform(scrollTo(), click())
        awaitText("Sample COR preview")
        assertEquals(1, store.read().subjects.size)
        captureScreen("connected-05-cor-review")
        tagged("cor_confirm").perform(scrollTo(), click())
        await("COR subjects must commit only after review") { store.read().subjects.size == 4 }
        val calculus = store.read().subjects.first { it.code == "MATH101" }
        tagged("planner_subject_$subjectId").perform(scrollTo(), click())
        tagged("personal_tab_grades").perform(scrollTo(), click())
        tagged("personal_edit_academics").perform(scrollTo(), click())
        tagged("personal_choose_prerequisites").perform(scrollTo(), click())
        onView(withText("Calculus I")).perform(click())
        onView(withText("Done")).perform(click())
        onView(withText("Save")).perform(click())
        awaitText("Calculus I")
        assertEquals(listOf(calculus.id), store.read().subjects.first { it.id == subjectId }.prerequisiteIds)
        captureScreen("connected-06-prerequisites")
        pressBack()
        captureScreen("connected-07-planner")
        instrumentation.runOnMainSync {
            (resumedActivity(MainActivity::class.java) as MainActivity).openDetails(
                com.studypath.app.ui.FullArticleFragment.open("Computer_programming", "https://en.wikipedia.org/wiki/Computer_programming"))
        }
        awaitTag("full_article_text")
        val firstPage = mainThreadValue { currentRoot()!!.findViewWithTag<TextView>("full_article_text").text.toString() }
        tagged("full_article_next").perform(scrollTo(), click())
        assertTrue(mainThreadValue { currentRoot()!!.findViewWithTag<TextView>("full_article_text").text.toString() != firstPage })
        captureScreen("personal-07-full-article-page-two")
        pressBack()
        // 0.1.7: actual native toolkit destinations, arithmetic, graph and URL review.
        fun openAcademic(mode: String) {
            instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).openDetails(com.studypath.app.ui.PersonalAcademicFragment.open(mode,subjectId)) }
            awaitTag("academic_panel")
            onView(withContentDescription("Back")).check(matches(isDisplayed()))
            onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
        }
        openAcademic("grades")
        awaitText("Every score counts.")
        tagged("academic_template").perform(scrollTo(),click())
        tagged("academic_add_category").perform(scrollTo(),click())
        onView(withHint("Category name")).perform(replaceText("Quizzes"))
        onView(withHint("Weight (%)")).perform(replaceText("100"));closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        assertEquals(1,store.read().subjects.first { it.id==subjectId }.gradeDraft!!.categories.size)
        onView(withText("Back to scores")).perform(scrollTo(),click())
        onView(withText("Add Quizzes score")).perform(scrollTo(),click())
        onView(withHint("Assessment title")).perform(replaceText("Quiz 1"))
        onView(withHint("Possible points")).perform(replaceText("20"))
        onView(withHint("Earned points (blank = ungraded)")).perform(replaceText("16"));closeSoftKeyboard()
        onView(withText("Save")).perform(click())
        awaitText("80.00 / 100")
        captureScreen("feedback-01-native-grades")
        instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).recreate() }
        awaitText("80.00 / 100")
        pressBack()
        openAcademic("gwa")
        onView(withText("These grades use the same numeric scale")).perform(scrollTo(),click())
        onView(withText("Physics\n3.0 units · Grade 1.75")).perform(scrollTo(),click())
        awaitText("1.75")
        captureScreen("feedback-02-native-gwa")
        pressBack()
        openAcademic("what-if")
        onView(withHint("Weighted points already earned")).perform(replaceText("41"))
        onView(withHint("Remaining exam weight (%)")).perform(replaceText("40"))
        onView(withHint("Target overall (%)")).perform(replaceText("75"));closeSoftKeyboard()
        onView(withText("Calculate scenario")).perform(scrollTo(),click())
        awaitText("75.00 / 100")
        captureScreen("feedback-03-native-scenario")
        pressBack()
        openAcademic("map")
        awaitText("My course path")
        captureScreen("feedback-04-native-map")
        onView(withText("Map / accessible list")).perform(scrollTo(),click())
        awaitTextContaining("Requires all: Calculus I")
        captureScreen("feedback-05-native-map-list")
        pressBack()
        instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).openDetails(com.studypath.app.ui.PdfLinkFragment.open(subjectId)) }
        awaitTag("pdf_url_input")
        tagged("pdf_url_input").perform(replaceText("/Downloads/test.pdf"));closeSoftKeyboard()
        onView(withText("Check link →")).perform(scrollTo(),click())
        awaitText("Paste a complete HTTPS PDF link.")
        tagged("pdf_url_input").perform(replaceText("https://127.0.0.1:1/unavailable.pdf"));closeSoftKeyboard()
        onView(withText("Check link →")).perform(scrollTo(),click())
        tagged("pdf_download").perform(scrollTo(),click())
        awaitText("Download not completed")
        onView(withText("Retry download")).check(matches(isDisplayed()))
        assertTrue(store.read().materials.none { it.title=="Linked PDF" })
        onView(withText("Edit link")).perform(scrollTo(),click())
        tagged("pdf_url_input").perform(replaceText("https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"));closeSoftKeyboard()
        onView(withText("Check link →")).perform(scrollTo(),click())
        awaitText("Give it a home.")
        tagged("pdf_download").perform(scrollTo(),click())
        awaitText("Available offline")
        val linked=store.read().materials.single { it.title=="Linked PDF" }
        assertTrue(store.pdf(linked.id).isFile)
        onView(withText("Open PDF")).perform(scrollTo(),click())
        awaitTextContaining("Available offline")
        captureScreen("feedback-06-linked-offline-pdf")
        instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).recreate() }
        awaitTextContaining("Available offline")
        pressBack();pressBack()
        // Synthetic PDF validates the actual crop → bundled OCR → review → add flow.
        val scanFile=File(app.cacheDir,"synthetic-cor-scan.pdf")
        val scanPdf=android.graphics.pdf.PdfDocument()
        try {
            val page=scanPdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(1000,1400,1).create())
            page.canvas.drawColor(Color.WHITE)
            val ink=Paint().apply { color=Color.BLACK; textSize=46f; typeface=android.graphics.Typeface.DEFAULT_BOLD }
            page.canvas.drawText("STUDENT PRIVATE HEADER",150f,100f,ink)
            page.canvas.drawText("Organic Chemistry",180f,400f,ink)
            page.canvas.drawText("World History",180f,500f,ink)
            scanPdf.finishPage(page); scanFile.outputStream().use { scanPdf.writeTo(it) }
        } finally { scanPdf.close() }
        instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).openDetails(com.studypath.app.ui.CorScanFragment.open(android.net.Uri.fromFile(scanFile))) }
        awaitTag("cor_scan_read")
        captureScreen("tide-01-cor-crop")
        instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).recreate() }
        awaitTag("cor_scan_read")
        tagged("cor_scan_read").perform(scrollTo(),click())
        awaitTag("cor_table")
        val recognized=mainThreadValue { currentRoot()!!.findViewWithTag<EditText>("cor_table").text.toString() }
        assertTrue("Recognized selected course names",recognized.contains("Organic Chemistry") && recognized.contains("World History"))
        assertTrue("Crop excludes private header",!recognized.contains("PRIVATE") && !recognized.contains("STUDENT"))
        tagged("cor_table").perform(replaceText("Organic Chemistry\nWorld History")); closeSoftKeyboard()
        tagged("cor_review").perform(scrollTo(),click())
        captureScreen("tide-02-cor-review")
        tagged("cor_confirm").perform(scrollTo(),click())
        awaitTag("recommend_subject")
        assertTrue(store.read().subjects.any { it.title=="Organic Chemistry" && it.units==null })
        captureScreen("tide-03-material-search")
        pressBack(); pressBack()
        // Show the actual native subject tiles.
        instrumentation.runOnMainSync { (resumedActivity(MainActivity::class.java) as MainActivity).openDetails(com.studypath.app.ui.SubjectsFragment()) }
        awaitTag("subject_library")
        captureScreen("tide-04-subject-tiles")
        pressBack()
    }

    private fun leaveLoadedPreferences() {
        closeSoftKeyboard()
        val ownerId = app.graph.repository.currentSession()?.uid
        if (ownerId != null && app.getSharedPreferences("optional_onboarding", android.content.Context.MODE_PRIVATE)
                .getBoolean("skipped_$ownerId", false)) {
            awaitTag("study_heading")
            return
        }
        await("Saved preferences and curriculum status must finish loading and layout before leaving") {
            mainThreadValue {
                val owner = resumedActivity(MainActivity::class.java) ?: return@mainThreadValue false
                val root = owner.window.decorView
                val preferences = root.findViewWithTag<View>("learner_preferences") ?: return@mainThreadValue false
                val load = root.findViewWithTag<View>("preference_load_status") ?: return@mainThreadValue false
                val catalog = root.findViewWithTag<TextView>("preference_catalog_status") ?: return@mainThreadValue false
                val later = root.findViewWithTag<View>("preference_later") ?: return@mainThreadValue false
                val catalogText = catalog.text.toString()
                currentRoot() === root && load.visibility == View.GONE && catalogText.isNotBlank() &&
                    !catalogText.startsWith("Loading curricula") && !catalogText.startsWith("Checking curricula") &&
                    later.isAttachedToWindow && later.isLaidOut && later.isShown && later.isEnabled && later.width > 0 && later.height > 0 &&
                    !later.isLayoutRequested && !preferences.isLayoutRequested && !root.isLayoutRequested
            }
        }
        onView(withTagValue(`is`("preference_display_name" as Any)))
            .perform(scrollTo(), replaceText("Focus learner"))
        closeSoftKeyboard()
        captureScreen("01b-compact-study-level")
        onView(withTagValue(`is`("preference_next" as Any))).perform(scrollTo(), click())
        await("Only the subjects step is visible after advancing") {
            mainThreadValue {
                val root = currentRoot() ?: return@mainThreadValue false
                root.findViewWithTag<View>("preference_profile_step")?.visibility == View.GONE &&
                    root.findViewWithTag<View>("preference_subject_step")?.let { it.isShown && it.alpha == 1f && it.translationX == 0f } == true
            }
        }
        captureScreen("01c-compact-subjects")
        onView(withTagValue(`is`("preference_back" as Any))).perform(scrollTo(), click())
        onView(withTagValue(`is`("preference_display_name" as Any)))
            .perform(scrollTo()).check(matches(withText("Focus learner")))
        assertEquals(View.GONE, mainThreadValue { currentRoot()!!.findViewWithTag<View>("preference_subject_step").visibility })
        onView(withTagValue(`is`("preference_later" as Any))).perform(scrollTo())
            .check(matches(isCompletelyDisplayed())).perform(click())
    }

    private fun tapNavigation(id: Int) {
        await("Navigation item must finish becoming visible") {
            mainThreadValue {
                val view = currentRoot()?.findViewById<View>(id)
                val visible = Rect()
                view != null && view.isShown && view.width > 0 && view.height > 0 &&
                    view.getGlobalVisibleRect(visible) &&
                    visible.width().toLong() * visible.height() >= view.width.toLong() * view.height * 9 / 10
            }
        }
        onView(withId(id)).perform(click())
    }

    /** Screenshots contain empty auth forms or a disposable synthetic account, never passwords. */
    private fun captureScreen(name: String) {
        flowStage = "after-$name"
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("The device must provide a screenshot for $name", bitmap)
        val directory = File(app.getExternalFilesDir(null), "smoke-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            assertTrue("Screenshot PNG must be written for $name", bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap!!.recycle()
        // UTP uninstalls the disposable app after a run. Preserve only these diagnostic PNGs
        // outside its app-private directory until the host pulls them from this read-only AVD.
        shell("mkdir -p /sdcard/Pictures/StudyPathSmoke")
        shell("cp ${File(directory, "$name.png").absolutePath} /sdcard/Pictures/StudyPathSmoke/$name.png")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun awaitActivity(type: Class<out Activity>) = await("${type.simpleName} must become resumed") {
        mainThreadValue { resumedActivity(type) != null }
    }

    private fun awaitTag(tag: String) = await("UI must render $tag") {
        mainThreadValue { currentRoot()?.findViewWithTag<View>(tag)?.isAttachedToWindow == true }
    }

    private fun awaitText(text: String) = await("UI must render '$text'") {
        mainThreadValue { currentRoot()?.let { containsText(it) { value -> value == text } } == true }
    }

    private fun awaitTextContaining(text: String) = await("UI must render text containing '$text'") {
        mainThreadValue { currentRoot()?.let { containsText(it) { value -> value.contains(text) } } == true }
    }

    /** Test-thread polling is bounded and yields; every condition reads live UI/repository state. */
    private fun await(description: String, timeoutMillis: Long = 90_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        lastAwaitFailure = redact(description)
        try { captureTimeout(description, timeoutMillis) }
        catch (error: Exception) { timeoutCaptureFailure = error.javaClass.simpleName }
        throw AssertionError("Timed out after ${timeoutMillis}ms: $description. Ensure both Firebase emulators are running and the catalog is seeded.")
    }

    /** Capture before ActivityScenario closes; edit fields and account identifiers never enter artifacts. */
    private fun captureTimeout(description: String, timeoutMillis: Long) {
        val redactions = mutableListOf<Rect>()
        val receipt = mainThreadValue {
            val activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            val nodes = JSONArray()
            fun visit(view: View, depth: Int) {
                if (nodes.length() >= 600) return
                val bounds = Rect()
                val visible = view.getGlobalVisibleRect(bounds)
                val text = (view as? TextView)?.text?.toString().orEmpty()
                val descriptionText = view.contentDescription?.toString().orEmpty()
                if (visible && (view is EditText || redact(text) != text || redact(descriptionText) != descriptionText))
                    redactions += Rect(bounds)
                nodes.put(JSONObject().put("depth", depth).put("class", view.javaClass.name)
                    .put("id", if (view.id == View.NO_ID) JSONObject.NULL else runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("unknown"))
                    .put("tag", view.tag?.toString()?.let(::redact) ?: JSONObject.NULL)
                    .put("visibility", view.visibility).put("shown", view.isShown).put("enabled", view.isEnabled)
                    .put("attached", view.isAttachedToWindow).put("laidOut", view.isLaidOut)
                    .put("width", view.width).put("height", view.height)
                    .put("visibleBounds", if (visible) bounds.toShortString() else JSONObject.NULL)
                    .put("text", if (view is EditText) "[input redacted]" else redact(text).take(500))
                    .put("contentDescription", redact(descriptionText).take(500)))
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
            activities.forEach { visit(it.window.decorView, 0) }
            JSONObject().put("expectedWait", redact(description)).put("timeoutMillis", timeoutMillis)
                .put("flowStage", flowStage).put("capturedAtDeviceMillis", System.currentTimeMillis())
                .put("resumedActivities", JSONArray(activities.map { it.javaClass.name }))
                .put("fragments", JSONArray(activities.filterIsInstance<androidx.fragment.app.FragmentActivity>()
                    .flatMap { it.supportFragmentManager.fragments }.map { it.javaClass.name }))
                .put("repositorySessionPresent", app.graph.repository.currentSession() != null)
                .put("originalBindingRetained", cleanupUid != null && app.graph.repository.boundUid() == cleanupUid)
                .put("viewNodeLimit", 600).put("viewTree", nodes)
        }
        val directory = File(app.getExternalFilesDir(null), "smoke-timeout-diagnostics").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) {
            val safe = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)) { "A mutable diagnostic screenshot is unavailable" }
            bitmap.recycle()
            try {
                val canvas = Canvas(safe)
                val paint = Paint().apply { color = Color.BLACK }
                redactions.forEach { canvas.drawRect(it, paint) }
                File(directory, "timeout.png").outputStream().use { safe.compress(Bitmap.CompressFormat.PNG, 100, it) }
                receipt.put("screenshot", "timeout.png").put("redactedViewCount", redactions.size)
            } finally { safe.recycle() }
        } else receipt.put("screenshot", JSONObject.NULL)
        File(directory, "timeout.json").writeText(receipt.toString(2))
        shell("mkdir -p /sdcard/Pictures/StudyPathSmoke")
        shell("cp ${File(directory, "timeout.json").absolutePath} /sdcard/Pictures/StudyPathSmoke/timeout.json")
        if (File(directory, "timeout.png").isFile)
            shell("cp ${File(directory, "timeout.png").absolutePath} /sdcard/Pictures/StudyPathSmoke/timeout.png")
    }

    private fun redact(value: String): String {
        val withoutEmails = value.replace(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), "[email redacted]")
        return cleanupUid?.takeIf { it.isNotBlank() }?.let { withoutEmails.replace(it, "[uid redacted]") } ?: withoutEmails
    }

    private fun <T> mainThreadValue(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun resumedActivity(type: Class<out Activity>): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }

    private fun currentRoot(): View? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView

    private fun containsText(view: View, predicate: (String) -> Boolean): Boolean {
        if (view is TextView && predicate(view.text.toString())) return true
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), predicate) }
    }
}
