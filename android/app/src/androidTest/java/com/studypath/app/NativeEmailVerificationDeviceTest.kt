package com.studypath.app

import android.app.Activity
import android.os.SystemClock
import android.os.ParcelFileDescriptor
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.EmailAuthProvider
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.auth.EmailVerificationActivity
import com.studypath.app.ui.auth.RegisterActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicitly select on a fresh read-only Test AVD. This test never sends a real email. */
@RunWith(AndroidJUnit4::class)
class NativeEmailVerificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val auth get() = FirebaseAuth.getInstance()
    private val observed = mutableListOf<String>()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private var startedAt = 0L
    private var ownerUid: String? = null
    private var creationTime: Long? = null
    private var initialTree: String? = null
    private var registrationSubmitted = false
    private var stage = "registration"
    private var cleanupStep = "not-started"

    @Before fun localTestInstallationOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.NativeEmailVerificationDeviceTest") == true)
        assertTrue("Never exercise real email delivery from this test",
            BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertEquals("Cleanup is restricted to the exact default local database",
            "https://demo-studypath-default-rtdb.firebaseio.com", BuildConfig.FIREBASE_DATABASE_URL)
        assertTrue("Only the configured local emulator host may be contacted",
            BuildConfig.FIREBASE_EMULATOR_HOST in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        assertEquals("Never run against the persistent preview", "StudyPath_Lite_Test", avdName())
        assertNull("Restart the isolated read-only Test AVD before this signup flow", repository.boundUid())
        assertNull(repository.currentSession())
        assertNull(auth.currentUser)
    }

    @Test fun explicitVerificationLinkDeferralAndAccountResume() {
        val email = "verification_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
        val password = "LocalVerify123!"
        startedAt = System.currentTimeMillis()
        var failureType: String? = null
        var cleanup = JSONObject().put("status", "NOT_ATTEMPTED")
        try {
        ActivityScenario.launch(RegisterActivity::class.java).use {
            awaitActivity(RegisterActivity::class.java)
            onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
            onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
            closeSoftKeyboard()
            registrationSubmitted = true
            onView(withId(R.id.createButton)).perform(scrollTo(), click())
            awaitActivity(EmailVerificationActivity::class.java)
            val registered = requireOwnedUser(email, allowCapture = true)
            val owner = registered.uid
            assertEquals(owner, repository.boundUid())
            assertEquals(owner, repository.currentSession()?.uid)
            initialTree = canonical(validateProfileTree(ownedTree(freshToken(registered)).body))
            assertFalse(auth.currentUser!!.isEmailVerified)
            assertTrue("No automatic signup email", verificationEvents(email).isEmpty())
            observed += "signupCheckpointDoesNotAutomaticallySendEmail"

            stage = "defer-and-resume-from-account"
            onView(withId(R.id.continueStudyingButton)).perform(scrollTo(), click())
            awaitActivity(MainActivity::class.java)
            await("Optional preferences must load before the learner chooses Later") {
                main { tag("preference_later")?.let { it.isShown && it.isEnabled } == true }
            }
            onView(withTagValue(`is`("preference_later" as Any))).perform(scrollTo(), click())
            await("Unverified original owner can continue studying") { main { tag("study_heading") != null } }
            main { root()?.findViewById<BottomNavigationView>(R.id.navigation)?.selectedItemId = R.id.nav_account }
            onView(withTagValue(`is`("profile_settings" as Any))).perform(scrollTo(), click())
            onView(withTagValue(`is`("settings_account" as Any))).perform(scrollTo(), click())
            await("Account must show its verification control") { main { tag("profile_email_verification_action") != null } }
            // Account settings are already expanded.
            assertTrue(main { (tag("profile_email_verification_status") as TextView).text.contains("not verified") })
            onView(withTagValue(`is`("profile_email_verification_action" as Any))).perform(scrollTo(), click())
            awaitActivity(EmailVerificationActivity::class.java)
            assertTrue("Returning through Account must not send", verificationEvents(email).isEmpty())
            observed += "verifyLaterPreservesOriginalWorkspaceAndAccountCanResume"

            stage = "explicit-local-verification"
            onView(withId(R.id.checkVerificationButton)).perform(scrollTo(), click())
            await("An unverified address must remain explicitly pending") { statusContains("still unverified") }
            assertTrue("Checking must not send an email", verificationEvents(email).isEmpty())
            val initialToken = runBlocking { withTimeout(30_000) { auth.currentUser!!.getIdToken(false).await() } }
            assertEquals(false, initialToken.claims["email_verified"])
            onView(withId(R.id.sendVerificationButton)).perform(scrollTo(), click())
            await("Local email request must finish honestly") { statusContains("No real email was sent") }
            val event = verificationEvents(email).single()
            assertFalse(main { root()?.findViewById<View>(R.id.sendVerificationButton)?.isEnabled == true })
            observed += "explicitSendProducesOneRealLocalVerifyEmailEventAndCooldown"
            main { (resumed(EmailVerificationActivity::class.java) as EmailVerificationActivity).recreate() }
            awaitActivity(EmailVerificationActivity::class.java)
            assertFalse(main { root()?.findViewById<View>(R.id.sendVerificationButton)?.isEnabled == true })
            assertEquals("Recreation must not request another email", 1, verificationEvents(email).size)
            observed += "recreationRetainsCooldownWithoutResending"

            stage = "redeem-reload-and-token-refresh"
            runBlocking { withTimeout(30_000) { auth.applyActionCode(event.getString("oobCode")).await() } }
            onView(withId(R.id.checkVerificationButton)).perform(scrollTo(), click())
            await("Reload must observe the actual redeemed verification link") { statusContains("Email verified.") }
            val refreshed = runBlocking { withTimeout(30_000) { auth.currentUser!!.getIdToken(false).await() } }
            assertEquals(true, refreshed.claims["email_verified"])
            assertEquals(owner, auth.currentUser?.uid)
            assertEquals(owner, repository.boundUid())
            observed += "redeemedLocalLinkReloadsVerifiedStateAndRefreshesTokenForSameUid"
            onView(withId(R.id.continueStudyingButton)).perform(scrollTo(), click())
            awaitActivity(MainActivity::class.java)
            await("Account reflects the refreshed email status") {
                main { (tag("profile_email_verification_status") as? TextView)?.text == "Email verified" }
            }
        }
        } catch (error: Throwable) {
            failureType = error.javaClass.simpleName
        } finally {
            try { cleanup = cleanOnlyCapturedAccount(email, password) }
            catch (error: Throwable) {
                cleanup = JSONObject().put("status", "FAILED_CLOSED").put("step", cleanupStep)
                    .put("failureType", error.javaClass.simpleName)
            }
            val passed = failureType == null && cleanup.optString("status") == "CONFIRMED"
            val receipt = JSONObject().put("test", "explicitVerificationLinkDeferralAndAccountResume")
                .put("status", if (passed) "PASSED" else "FAILED").put("localFirebaseOnly", true)
                .put("emailSha256", digest(email)).put("uidSha256", ownerUid?.let(::digest) ?: JSONObject.NULL)
                .put("observed", JSONArray(observed)).put("cleanup", cleanup)
                .put("failureStage", if (failureType == null) JSONObject.NULL else stage)
                .put("failureType", failureType ?: JSONObject.NULL)
                .put("limits", "Local Auth/RTDB emulator only. No real mail, Inbox placement, cloud rules, phone or broad offline acceptance.")
            File(app.getExternalFilesDir(null), "native-email-verification.json").writeText(receipt.toString(2))
            if (!passed) throw AssertionError("Local verification flow or owned cleanup failed; inspect sanitized native-email-verification.json")
        }
    }

    private fun requireOwnedUser(email: String, allowCapture: Boolean = false): FirebaseUser {
        val user = auth.currentUser ?: throw AssertionError("Owned Auth identity is unavailable")
        assertTrue("Only this run's generated address is owned", email.startsWith("verification_") &&
            email.endsWith("@studypath.invalid") && user.email == email && !user.isAnonymous)
        val created = user.metadata?.creationTimestamp ?: throw AssertionError("Auth creation identity is unavailable")
        assertTrue("Only this newly created identity is owned",
            created in (startedAt - 120_000)..(System.currentTimeMillis() + 60_000))
        if (ownerUid == null) {
            assertTrue("Only the acknowledged signup checkpoint can capture ownership", allowCapture)
            ownerUid = user.uid
            creationTime = created
        }
        assertTrue("A different UID stops cleanup", user.uid == ownerUid)
        assertTrue("Changed Auth creation identity stops cleanup", created == creationTime)
        assertTrue("The original workspace binding must be retained", repository.boundUid() == ownerUid)
        return user
    }

    private fun freshToken(user: FirebaseUser): String {
        val result = runBlocking { withTimeout(30_000) { user.getIdToken(true).await() } }
        assertTrue("The token must name this run's captured UID", result.claims["sub"] == ownerUid)
        return result.token?.takeIf { it.isNotBlank() } ?: throw AssertionError("Fresh cleanup token is unavailable")
    }

    private fun validateProfileTree(body: String): JSONObject {
        val tree = JSONObject(body)
        assertTrue("Unexpected user data stops cleanup", keys(tree) == setOf("profile"))
        val profile = tree.getJSONObject("profile")
        assertTrue("Unexpected profile fields stop cleanup", keys(profile) == setOf("displayName",
            "selectedCurriculumVersion", "createdAt", "updatedAt", "learnerPreferences"))
        assertTrue("The profile must match this registration fixture", profile.getString("displayName") == "Student" &&
            profile.getString("selectedCurriculumVersion") == "bsit_demo_v1")
        val preferences = profile.getJSONObject("learnerPreferences")
        assertTrue("Changed preferences stop cleanup", keys(preferences) == setOf("schemaVersion", "onboardingComplete") &&
            preferences.getInt("schemaVersion") == 1 && !preferences.getBoolean("onboardingComplete"))
        for (field in listOf("createdAt", "updatedAt")) {
            assertTrue("Profile timestamps must belong to this new account", profile.getLong(field) in
                (startedAt - 120_000)..(System.currentTimeMillis() + 60_000))
        }
        return tree
    }

    private fun cleanOnlyCapturedAccount(email: String, password: String): JSONObject {
        cleanupStep = "require-captured-new-identity"
        if (ownerUid == null) return if (!registrationSubmitted && auth.currentUser == null)
            JSONObject().put("status", "CONFIRMED").put("accountCreated", false)
        else JSONObject().put("status", "FAILED_CLOSED").put("step", cleanupStep)
            .put("reason", "Signup identity was not captured; no account or data was deleted")
        var user = requireOwnedUser(email)
        runBlocking { withTimeout(30_000) {
            user.reload().await()
            user = requireOwnedUser(email)
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        } }
        user = requireOwnedUser(email)
        val token = freshToken(user)
        cleanupStep = "confirm-server-auth-creation-identity"
        val lookup = authRequest("lookup", token)
        assertEquals("Owned Auth lookup must be acknowledged", 200, lookup.code)
        val users = JSONObject(lookup.body).getJSONArray("users")
        assertEquals("Lookup must identify exactly one owned account", 1, users.length())
        val identity = users.getJSONObject(0)
        assertTrue("Server UID, address or creation identity changed", identity.getString("localId") == ownerUid &&
            identity.getString("email") == email && identity.getString("createdAt").toLong() == creationTime)

        cleanupStep = "pause-owned-workspace"
        main { repository.logout() }
        assertTrue("Logout must preserve the binding", repository.boundUid() == ownerUid)
        assertNull("Logout must detach the private workspace", repository.currentSession())
        cleanupStep = "validate-exact-owned-profile-tree"
        val snapshot = ownedTree(token, etag = true)
        if (snapshot.body != "null") {
            val actual = canonical(validateProfileTree(snapshot.body))
            initialTree?.let { assertTrue("Changed acknowledged profile stops cleanup", actual == it) }
            assertNotNull("Conditional deletion requires the owned tree ETag", snapshot.etag)
            cleanupStep = "conditional-exact-owned-tree-deletion"
            assertEquals("Concurrent data changes must reject deletion", 200,
                ownedTree(token, method = "DELETE", ifMatch = snapshot.etag).code)
        }
        assertTrue("Owned data removal must have fresh null readback", ownedTree(token).body == "null")
        cleanupStep = "delete-exact-auth-identity"
        assertEquals("Owned Auth deletion must be acknowledged", 200, authRequest("delete", token).code)
        val absent = authRequest("lookup", token)
        assertEquals("Deleted identity must fail fresh lookup", 400, absent.code)
        assertTrue("Only USER_NOT_FOUND confirms absence", JSONObject(absent.body).getJSONObject("error")
            .getString("message") == "USER_NOT_FOUND")
        assertNull(auth.currentUser)
        return JSONObject().put("status", "CONFIRMED").put("exactProfileTreeDeletionReadBack", true)
            .put("authDeletionAcknowledged", true).put("deletedAuthLookupDenied", true)
            .put("originalBindingRetained", repository.boundUid() == ownerUid)
            .put("cleanupScope", "Captured UID only; no global clears or shared-control writes")
    }

    private data class Response(val code: Int, val body: String, val etag: String?)
    private fun ownedTree(token: String, method: String = "GET", etag: Boolean = false, ifMatch: String? = null): Response {
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        val uid = ownerUid ?: throw AssertionError("No captured cleanup UID")
        val url = "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000".toHttpUrl().newBuilder()
            .addPathSegment("users").addPathSegment("$uid.json")
            .addQueryParameter("ns", "demo-studypath-default-rtdb").addQueryParameter("auth", token).build()
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
        if (etag) request.header("X-Firebase-ETag", "true")
        if (ifMatch != null) request.header("if-match", ifMatch)
        val result = execute(request.method(method, null).build())
        if (method == "GET") assertEquals("Fresh owned-tree read must be acknowledged", 200, result.code)
        return result
    }
    private fun authRequest(action: String, token: String): Response {
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue("Only scoped lookup and deletion are allowed", action == "lookup" || action == "delete")
        val url = "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/accounts:$action"
            .toHttpUrl().newBuilder().addQueryParameter("key", BuildConfig.FIREBASE_API_KEY).build()
        return execute(Request.Builder().url(url).post(JSONObject().put("idToken", token).toString()
            .toRequestBody("application/json".toMediaType())).build())
    }
    private fun execute(request: Request): Response = client.newCall(request).execute().use { response ->
        val body = response.body ?: throw AssertionError("Cleanup response body unavailable")
        assertTrue("Cleanup response exceeds the bounded identity probe", body.contentLength() <= 262_144)
        val source = body.source()
        source.request(262_145)
        assertTrue("Cleanup response exceeds the bounded identity probe", source.buffer.size <= 262_144)
        Response(response.code, source.buffer.readByteArray().toString(Charsets.UTF_8), response.header("ETag"))
    }

    private fun verificationEvents(email: String): List<JSONObject> {
        val url = "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9099/emulator/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}/oobCodes"
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertTrue("Independent Auth OOB evidence must be available", response.isSuccessful)
            val events = JSONObject(response.body!!.string()).optJSONArray("oobCodes") ?: JSONArray()
            (0 until events.length()).map { events.getJSONObject(it) }.filter {
                it.optString("email") == email && it.optString("requestType") == "VERIFY_EMAIL"
            }
        }
    }

    private fun statusContains(text: String): Boolean = main {
        root()?.findViewById<TextView>(R.id.statusText)?.text?.contains(text) == true
    }
    private fun awaitActivity(type: Class<out Activity>) = await("${type.simpleName} must resume") { main { resumed(type) != null } }
    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 60_000
        do { instrumentation.waitForIdleSync(); if (condition()) return; SystemClock.sleep(50) }
        while (SystemClock.elapsedRealtime() < until)
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
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun canonical(value: JSONObject): String = keys(value).sorted().joinToString("|", "{", "}") { key ->
        val item = value.get(key); "$key:${if (item is JSONObject) canonical(item) else item}"
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun avdName() = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name"))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }
}
