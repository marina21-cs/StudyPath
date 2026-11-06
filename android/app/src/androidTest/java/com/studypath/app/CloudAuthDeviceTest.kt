package com.studypath.app

import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.auth.EmailVerificationActivity
import com.studypath.app.ui.auth.LoginActivity
import com.studypath.app.ui.auth.RegisterActivity
import com.studypath.app.ui.auth.WelcomeActivity
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.hamcrest.Matchers.`is`
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit, opt-in selected-cloud smoke. Never sends verification or password-reset mail. */
@RunWith(AndroidJUnit4::class)
class CloudAuthDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val auth get() = FirebaseAuth.getInstance()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val checks = JSONArray()
    private val runId = UUID.randomUUID().toString().replace("-", "")
    private val email = "studypath-native-$runId@studypath.invalid"
    private val password = "Native!${UUID.randomUUID()}"
    private var ownerUid: String? = null
    private var initialProfile: JSONObject? = null
    private var startedAt = 0L
    private var creationTime: Long? = null
    private var stage = "guards"
    private var guardsPassed = false
    private var registrationSubmitted = false
    private var cleanupStep = "not-started"

    @Before fun exactCloudTestAvdAndFreshWorkspaceOnly() {
        val selected = InstrumentationRegistry.getArguments().getString("class").orEmpty()
        assertTrue("Select this cloud class or its single method explicitly", selected == javaClass.name ||
            selected == "${javaClass.name}#registerConfirmProfileLogoutLoginAndCleanOwnedAccount")
        assertEquals("Cloud account creation needs the explicit project opt-in", PROJECT,
            InstrumentationRegistry.getArguments().getString("allowCloudRegistration"))
        assertTrue("Only the debug cloud candidate may run", BuildConfig.DEBUG && !BuildConfig.USE_FIREBASE_EMULATORS)
        assertEquals(PROJECT, BuildConfig.FIREBASE_PROJECT_ID)
        assertEquals(DATABASE, BuildConfig.FIREBASE_DATABASE_URL)
        assertEquals("A local asset mirror cannot appear in a cloud test", "", BuildConfig.MOCK_ASSET_MIRROR_URL)
        assertEquals("Never use the persistent preview or a real phone", "StudyPath_Lite_Test", avdName())
        assertNull("Restart the read-only Test AVD; never clear or rebind another workspace", repository.boundUid())
        assertNull(repository.currentSession())
        assertNull(auth.currentUser)
        startedAt = System.currentTimeMillis()
        guardsPassed = true
    }

    @Test fun registerConfirmProfileLogoutLoginAndCleanOwnedAccount() {
        var failureType: String? = null
        var cleanup = JSONObject().put("status", "NOT_ATTEMPTED")
        try {
            ActivityScenario.launch(WelcomeActivity::class.java).use {
                stage = "ui-registration"
                awaitActivity(WelcomeActivity::class.java)
                await("Fresh Welcome must enable registration") { main { root()?.findViewById<View>(R.id.signUpButton)?.isEnabled == true } }
                onView(withId(R.id.signUpButton)).perform(scrollTo(), click())
                awaitActivity(RegisterActivity::class.java)
                onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
                onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
                closeSoftKeyboard()
                registrationSubmitted = true
                onView(withId(R.id.createButton)).perform(scrollTo(), click())
                awaitActivity(EmailVerificationActivity::class.java)
                val user = requireOwnedUser(allowFirstCapture = true)
                assertEquals(user.uid, repository.boundUid())
                assertEquals(user.uid, repository.currentSession()?.uid)
                assertFalse("A synthetic account must not silently become verified", user.isEmailVerified)
                record("nativeSignupReachedExplicitVerificationCheckpoint")

                stage = "fresh-profile-readback"
                val profileTree = readTree(freshToken(user))
                initialProfile = validateProfileOnlyTree(profileTree)
                record("freshIdTokenRestReadConfirmsExactNativeProfileAndServerTimestamps")

                // Deliberately do not tap Send, Check, Open email or password recovery.
                onView(withId(R.id.continueStudyingButton)).perform(scrollTo(), click())
                awaitActivity(MainActivity::class.java)
                leaveOptionalPreferences()
                record("continueStudyingWorksWithoutSendingOrRedeemingEmail")

                stage = "ui-logout"
                onView(withId(R.id.nav_account)).perform(click())
                onView(withTagValue(`is`("profile_settings" as Any))).perform(scrollTo(), click())
                onView(withTagValue(`is`("settings_account" as Any))).perform(scrollTo(), click())
                await("Account must render") { main { tag("profile_account_action") != null } }
                // Account section is opened from Settings.
                onView(withTagValue(`is`("profile_account_action" as Any))).perform(scrollTo(), click())
                onView(withTagValue(`is`("profile_account_signout" as Any))).perform(scrollTo(), click())
                onView(withTagValue(`is`("profile_confirm_signout" as Any))).perform(scrollTo(), click())
                awaitActivity(LoginActivity::class.java)
                await("Logout must end the repository session") { repository.currentSession() == null }
                assertEquals(ownerUid, repository.boundUid())
                record("nativeLogoutEndsSessionAndRetainsOriginalUidBinding")

                stage = "ui-original-account-login"
                await("Original-account sign-in must become available") { main { root()?.findViewById<View>(R.id.signInButton)?.isEnabled == true } }
                onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
                onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
                closeSoftKeyboard()
                onView(withId(R.id.signInButton)).perform(scrollTo(), click())
                awaitActivity(MainActivity::class.java)
                leaveOptionalPreferences()
                val restored = requireOwnedUser()
                assertEquals(ownerUid, repository.currentSession()?.uid)
                assertEquals(ownerUid, repository.boundUid())
                val afterLogin = validateProfileOnlyTree(readTree(freshToken(restored)))
                assertEquals("Sign-in and Skip may not change the profile", canonical(initialProfile!!), canonical(afterLogin))
                record("nativeLoginRestoresOriginalUidAndUnchangedAcknowledgedProfile")
            }
        } catch (error: Throwable) {
            failureType = error.javaClass.simpleName
        } finally {
            if (guardsPassed) {
                try { cleanup = cleanOnlyThisRun() }
                catch (error: Throwable) { cleanup = JSONObject().put("status", "FAILED_CLOSED")
                    .put("step", cleanupStep).put("failureType", error.javaClass.simpleName) }
                val passed = failureType == null && cleanup.optString("status") == "CONFIRMED"
                val receipt = JSONObject().put("status", if (passed) "PASSED" else "FAILED")
                    .put("test", "registerConfirmProfileLogoutLoginAndCleanOwnedAccount").put("runId", runId)
                    .put("project", PROJECT).put("exactDatabaseGuardPassed", true).put("emulatorMode", false)
                    .put("avd", avdName()).put("apiLevel", Build.VERSION.SDK_INT).put("model", Build.MODEL)
                    .put("apkSha256", digest(File(app.applicationInfo.sourceDir)))
                    .put("startedAtDeviceMillis", startedAt).put("finishedAtDeviceMillis", System.currentTimeMillis())
                    .put("checks", checks).put("cleanup", cleanup).put("failureStage", if (failureType == null) JSONObject.NULL else stage)
                    .put("failureType", failureType ?: JSONObject.NULL).put("emailSha256", digest(email.toByteArray()))
                    .put("uidSha256", ownerUid?.let { digest(it.toByteArray()) } ?: JSONObject.NULL)
                    .put("verificationOrResetEmailRequested", false).put("releaseAccepted", false)
                    .put("limits", "Selected Test AVD cloud Auth/profile flow only. No mail delivery, Inbox placement, reviewed content, physical phone, or full B08 acceptance.")
                File(app.getExternalFilesDir(null), "cloud-auth-device.json").writeText(receipt.toString(2))
                if (!passed) throw AssertionError("Cloud native flow or owned cleanup failed; inspect sanitized cloud-auth-device.json")
            }
        }
    }

    private fun requireOwnedUser(allowFirstCapture: Boolean = false): FirebaseUser {
        val user = auth.currentUser ?: throw AssertionError("Expected this run's signed-in account")
        assertEquals("Never act on a different account", email, user.email)
        assertFalse(user.isAnonymous)
        val created = user.metadata?.creationTimestamp ?: throw AssertionError("Creation identity unavailable")
        assertTrue("Only a newly created account is owned", created in (startedAt - 120_000)..(System.currentTimeMillis() + 60_000))
        if (ownerUid == null) {
            assertTrue("UID may first be captured only for this generated signup", allowFirstCapture)
            ownerUid = user.uid; creationTime = created
        }
        assertEquals(ownerUid, user.uid)
        assertEquals(creationTime, created)
        return user
    }

    private fun freshToken(user: FirebaseUser): String {
        val result = Tasks.await(user.getIdToken(true), 30, TimeUnit.SECONDS)
        assertEquals(ownerUid, result.claims["sub"])
        return result.token?.takeIf { it.isNotBlank() } ?: throw AssertionError("Fresh ID token unavailable")
    }

    private fun readTree(token: String): JSONObject? {
        val response = database("users/${ownerUid ?: throw AssertionError("No owned UID")}", token)
        assertEquals("Fresh private read must be acknowledged", 200, response.code)
        return if (response.body == "null") null else JSONObject(response.body)
    }

    private fun validateProfileOnlyTree(tree: JSONObject?): JSONObject {
        assertNotNull("Native registration must create its profile", tree)
        assertEquals("Unexpected user children stop cleanup", setOf("profile"), keys(tree!!))
        val profile = tree.getJSONObject("profile")
        assertEquals(setOf("displayName", "selectedCurriculumVersion", "createdAt", "updatedAt", "learnerPreferences"), keys(profile))
        assertEquals("Student", profile.getString("displayName"))
        assertEquals("bsit_demo_v1", profile.getString("selectedCurriculumVersion"))
        val prefs = profile.getJSONObject("learnerPreferences")
        assertEquals(setOf("schemaVersion", "onboardingComplete"), keys(prefs))
        assertEquals(1, prefs.getInt("schemaVersion")); assertFalse(prefs.getBoolean("onboardingComplete"))
        for (field in listOf("createdAt", "updatedAt")) {
            assertTrue("Server timestamp must belong to this new account", profile.getLong(field) in
                (startedAt - 120_000)..(System.currentTimeMillis() + 60_000))
        }
        return profile
    }

    private fun cleanOnlyThisRun(): JSONObject {
        cleanupStep = "recover-exact-generated-identity"
        var user = auth.currentUser
        if (user == null && ownerUid == null && !registrationSubmitted)
            return JSONObject().put("status", "CONFIRMED").put("accountCreated", false)
        if (user == null) {
            user = Tasks.await(auth.signInWithEmailAndPassword(email, password), 30, TimeUnit.SECONDS).user
                ?: throw AssertionError("Cannot recover this run's cleanup identity")
        }
        user = requireOwnedUser(allowFirstCapture = ownerUid == null)
        Tasks.await(user.reload(), 30, TimeUnit.SECONDS)
        user = requireOwnedUser()
        Tasks.await(user.reauthenticate(EmailAuthProvider.getCredential(email, password)), 30, TimeUnit.SECONDS)
        val token = freshToken(user)
        cleanupStep = "validate-profile-only-tree"
        val tree = readTree(token)
        if (tree != null) {
            val profile = validateProfileOnlyTree(tree)
            initialProfile?.let { assertEquals("Changed profile stops cleanup", canonical(it), canonical(profile)) }
            val snapshot = database("users/${user.uid}/profile", token, etag = true)
            assertEquals(200, snapshot.code)
            assertEquals(canonical(profile), canonical(JSONObject(snapshot.body)))
            assertNotNull("Conditional deletion requires an ETag", snapshot.etag)
            cleanupStep = "conditional-owned-profile-deletion"
            assertEquals("Changed profile must reject conditional deletion", 200,
                database("users/${user.uid}/profile", token, method = "DELETE", ifMatch = snapshot.etag).code)
        }
        assertNull("No unknown private data may remain before Auth deletion", readTree(token))
        cleanupStep = "detach-private-session"
        main { repository.logout() }
        // Repository logout detaches private work; reauthenticate only the already verified run identity.
        val cleanupUser = Tasks.await(auth.signInWithEmailAndPassword(email, password), 30, TimeUnit.SECONDS).user
            ?: throw AssertionError("Cleanup sign-in unavailable")
        assertEquals(ownerUid, cleanupUser.uid)
        requireOwnedUser()
        val lookupToken = freshToken(cleanupUser)
        cleanupStep = "exact-auth-account-deletion-and-absence"
        Tasks.await(cleanupUser.delete(), 30, TimeUnit.SECONDS)
        assertNull("Auth SDK must observe the acknowledged deletion", auth.currentUser)
        val lookup = authLookup(lookupToken)
        assertEquals("Deleted Auth identity must fail lookup", 400, lookup.code)
        assertEquals("USER_NOT_FOUND", JSONObject(lookup.body).getJSONObject("error").getString("message"))
        return JSONObject().put("status", "CONFIRMED").put("profileDeletionReadBack", true)
            .put("authDeletionAcknowledged", true).put("deletedAuthLookupDenied", true)
    }

    private data class Response(val code: Int, val body: String, val etag: String?)
    private fun database(path: String, token: String, method: String = "GET", etag: Boolean = false, ifMatch: String? = null): Response {
        assertEquals(DATABASE, BuildConfig.FIREBASE_DATABASE_URL)
        val url = DATABASE.toHttpUrl().newBuilder().addPathSegments("$path.json").addQueryParameter("auth", token).build()
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
        if (etag) request.header("X-Firebase-ETag", "true")
        if (ifMatch != null) request.header("if-match", ifMatch)
        request.method(method, null)
        return execute(request.build())
    }

    private fun authLookup(token: String): Response {
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:lookup".toHttpUrl().newBuilder()
            .addQueryParameter("key", BuildConfig.FIREBASE_API_KEY).build()
        @Suppress("DEPRECATION")
        val signature = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures?.single()
            ?: throw AssertionError("APK signer unavailable")
        val cert = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
        return execute(Request.Builder().url(url).header("X-Android-Package", app.packageName).header("X-Android-Cert", cert)
            .post(JSONObject().put("idToken", token).toString().toRequestBody("application/json".toMediaType())).build())
    }

    private fun execute(request: Request): Response = client.newCall(request).execute().use { response ->
        val body = response.body ?: throw AssertionError("Response body unavailable")
        assertTrue("Cloud response exceeds this bounded profile probe", body.contentLength() <= 262_144)
        val source = body.source()
        source.request(262_145)
        assertTrue("Cloud response exceeds this bounded profile probe", source.buffer.size <= 262_144)
        val bytes = source.buffer.readByteArray()
        Response(response.code, bytes.toString(Charsets.UTF_8), response.header("ETag"))
    }

    private fun leaveOptionalPreferences() {
        await("Study or loaded optional preferences must render") { main {
            tag("study_heading") != null || (tag("preference_later")?.let { it.isShown && it.isEnabled } == true &&
                tag("preference_load_status")?.visibility == View.GONE &&
                (tag("preference_catalog_status") as? TextView)?.text?.isNotBlank() == true)
        } }
        if (main { tag("study_heading") == null }) onView(withTagValue(`is`("preference_later" as Any))).perform(scrollTo(), click())
        await("Study must be available without an approved curriculum") { main { tag("study_heading") != null } }
    }
    private fun record(name: String) { checks.put(JSONObject().put("name", name).put("status", "PASSED")) }
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun canonical(value: JSONObject): String = keys(value).sorted().joinToString("|", "{", "}") { key ->
        val item = value.get(key); "$key:${if (item is JSONObject) canonical(item) else item}" }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val bytes = ByteArray(65_536); while (true) {
            val size = stream.read(bytes); if (size < 0) break; sha.update(bytes, 0, size)
        } }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun avdName() = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private fun awaitActivity(type: Class<out Activity>) = await("${type.simpleName} must resume") { main { resumed(type) != null } }
    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 60_000
        do { instrumentation.waitForIdleSync(); if (condition()) return; SystemClock.sleep(50) } while (SystemClock.elapsedRealtime() < until)
        throw AssertionError(description)
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun resumed(type: Class<out Activity>) = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
    private fun root() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
    private fun tag(value: String) = root()?.findViewWithTag<View>(value)

    companion object {
        private const val PROJECT = "studypath-c8086"
        private const val DATABASE = "https://studypath-c8086-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}
