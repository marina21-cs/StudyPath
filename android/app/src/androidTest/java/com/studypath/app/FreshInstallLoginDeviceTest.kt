package com.studypath.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.android.gms.tasks.Tasks
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.auth.AuthUi
import com.studypath.app.ui.auth.LoginActivity
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

/** Existing-account first login: REST setup deliberately leaves the primary SDK cache empty. */
@RunWith(AndroidJUnit4::class)
class FreshInstallLoginDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val workspace get() = app.getSharedPreferences("workspace", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val runId = UUID.randomUUID().toString().replace("-", "")
    private val email = "first_login_$runId@studypath.invalid"
    private val password = "Local!${UUID.randomUUID()}"
    private val checks = JSONArray()
    private var ownerUid: String? = null
    private var setupToken: String? = null
    private var initialProfile: JSONObject? = null
    private var stage = "guards"
    private var startedAt = 0L
    private var unknownAuth = JSONObject()
    private var unknownFeedbackActionable = false
    private var unknownUiActionable = false
    private var signupSubmitted = false
    private var cleanupStep = "not-started"

    @Before fun selectedFreshTestAvdOnly() {
        val selected = InstrumentationRegistry.getArguments().getString("class")
        assertTrue("Select this class or its single method explicitly", selected == javaClass.name ||
            selected == "${javaClass.name}#existingAccountFirstLoginThenOriginalAccountRelogin")
        assertTrue("Only debug test candidates may run", BuildConfig.DEBUG)
        if (BuildConfig.USE_FIREBASE_EMULATORS) {
            assertEquals("demo-studypath", BuildConfig.FIREBASE_PROJECT_ID)
            assertEquals("https://demo-studypath-default-rtdb.firebaseio.com", BuildConfig.FIREBASE_DATABASE_URL)
        } else {
            assertEquals("Cloud synthetic setup requires explicit project opt-in", CLOUD_PROJECT,
                InstrumentationRegistry.getArguments().getString("allowCloudRegistration"))
            assertEquals(CLOUD_PROJECT, BuildConfig.FIREBASE_PROJECT_ID)
            assertEquals(CLOUD_DATABASE, BuildConfig.FIREBASE_DATABASE_URL)
            assertEquals("", BuildConfig.MOCK_ASSET_MIRROR_URL)
        }
        assertEquals("Never use the persistent preview or a real phone", "StudyPath_Lite_Test", avdName())
        assertNull("Restart the read-only Test AVD; never clear or rebind a workspace", repository.boundUid())
        assertNull(repository.currentSession())
        assertNull(FirebaseAuth.getInstance().currentUser)
        assertFalse("A fresh installation must not already have migrated reading state", workspace.getBoolean("readingJournalV1Ready", false))
        startedAt = System.currentTimeMillis()
    }

    @Test fun existingAccountFirstLoginThenOriginalAccountRelogin() {
        var failure: Throwable? = null
        var cleanup = JSONObject().put("status", "NOT_ATTEMPTED")
        try {
            stage = "unknown-account-feedback"
            probeUnknownAccountFeedback()
            stage = "rest-existing-account-setup"
            signupSubmitted = true
            val signup = auth("signUp", JSONObject().put("email", email).put("password", password).put("returnSecureToken", true))
            assertEquals("Synthetic account setup must succeed", 200, signup.code)
            val identity = JSONObject(signup.body)
            ownerUid = identity.getString("localId")
            setupToken = identity.getString("idToken")
            val profile = JSONObject().put("displayName", "Login regression").put("selectedCurriculumVersion", "bsit_demo_v1")
                .put("createdAt", startedAt).put("updatedAt", startedAt)
                .put("learnerPreferences", JSONObject().put("schemaVersion", 1).put("onboardingComplete", false)
                    .put("levelId", "college"))
            initialProfile = profile
            assertEquals(200, tree(setupToken!!, "PUT", JSONObject().put("profile", profile).toString()).code)
            assertNull("REST setup must not bind the primary SDK installation", repository.boundUid())
            assertNull(FirebaseAuth.getInstance().currentUser)
            record("existingSyntheticAccountSeededWithoutPrimarySdkCache")

            ActivityScenario.launch(LoginActivity::class.java).use {
                stage = "first-native-login"
                signIn()
                assertEquals(ownerUid, repository.boundUid())
                assertEquals(ownerUid, repository.currentSession()?.uid)
                assertTrue("First login must initialize the reading journal", workspace.getBoolean("readingJournalV1Ready", false))
                assertProfileUnchanged()
                record("freshInstallationOpensExistingAccountAndInitializesReadingJournal")

                stage = "native-logout"
                await("Main navigation must load") { main { root()?.findViewById<View>(R.id.nav_account)?.isShown == true } }
                onView(withId(R.id.nav_account)).perform(click())
                onView(withTagValue(`is`("profile_settings" as Any))).perform(scrollTo(), click())
                onView(withTagValue(`is`("settings_account" as Any))).perform(scrollTo(), click())
                await("Account actions must load") { main { tag("profile_account_action") != null } }
                // Account section is opened from Settings.
                onView(withTagValue(`is`("profile_account_action" as Any))).perform(scrollTo(), click())
                onView(withTagValue(`is`("profile_account_signout" as Any))).perform(scrollTo(), click())
                onView(withTagValue(`is`("profile_confirm_signout" as Any))).perform(scrollTo(), click())
                await("Logout must open Login") { main { resumed(LoginActivity::class.java) != null } }
                assertNull(repository.currentSession())
                assertEquals("Logout must preserve the original installation binding", ownerUid, repository.boundUid())

                stage = "original-account-relogin"
                signIn()
                assertEquals(ownerUid, repository.currentSession()?.uid)
                assertEquals(ownerUid, repository.boundUid())
                assertProfileUnchanged()
                record("logoutAndOriginalAccountReloginPreserveBindingAndRemoteProfile")
                stage = "unknown-account-feedback-assertions"
                assertTrue("Real SDK credential rejection must explain credentials and account creation", unknownFeedbackActionable)
                assertTrue("Native unknown-account feedback must explain credentials and account creation", unknownUiActionable)
                record("unknownSyntheticAccountGetsActionableSdkAndNativeUiFeedback")
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try { cleanup = cleanupOwnedSyntheticAccount() }
            catch (error: Throwable) {
                cleanup = JSONObject().put("status", "FAILED_UNCONFIRMED").put("step", cleanupStep)
                    .put("signupSubmitted", signupSubmitted).put("failureType", error.javaClass.simpleName)
                if (failure == null) failure = error
            }
            val receipt = JSONObject().put("status", if (failure == null) "PASSED" else "FAILED")
                .put("test", "existingAccountFirstLoginThenOriginalAccountRelogin").put("runId", runId)
                .put("project", BuildConfig.FIREBASE_PROJECT_ID).put("emulatorMode", BuildConfig.USE_FIREBASE_EMULATORS).put("avd", avdName())
                .put("startedAtDeviceMillis", startedAt).put("finishedAtDeviceMillis", System.currentTimeMillis())
                .put("apkSha256", digest(File(app.applicationInfo.sourceDir)))
                .put("checks", checks).put("cleanup", cleanup)
                .put("failureStage", if (failure == null) JSONObject.NULL else stage)
                .put("failureType", failure?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("originalBindingRetained", ownerUid != null && ownerUid == repository.boundUid())
                .put("readingJournalReady", workspace.getBoolean("readingJournalV1Ready", false))
                .put("unknownAccountAuth", unknownAuth).put("unknownAccountUiActionable", unknownUiActionable)
                .put("verificationOrResetEmailRequested", false)
                .put("limits", "Selected Test AVD synthetic-account regression only; no real account, full cloud acceptance, or phone verification.")
            File(app.getExternalFilesDir(null), "fresh-install-login-device.json").writeText(receipt.toString(2))
        }
        failure?.let { throw AssertionError("Fresh-install login regression failed at $stage; inspect sanitized fresh-install-login-device.json", it) }
    }

    private fun probeUnknownAccountFeedback() {
        val rejected = try {
            Tasks.await(FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password), 30, TimeUnit.SECONDS)
            null
        } catch (error: Exception) {
            generateSequence(error as Throwable) { it.cause }.take(8).filterIsInstance<FirebaseAuthException>().firstOrNull() ?: error
        }
        assertNotNull("The generated identity must not exist before setup", rejected)
        val failure = requireNotNull(rejected)
        val sdkError = failure as? FirebaseAuthException
        val code = sdkError?.errorCode?.takeIf { it.matches(Regex("ERROR_[A-Z0-9_]{1,80}")) } ?: "NONE_OR_UNRECOGNIZED"
        val feedback = AuthUi.errorMessage(failure)
        unknownFeedbackActionable = feedback.contains("email and password") && feedback.contains("Create an account")
        unknownAuth = JSONObject().put("exceptionClass", failure.javaClass.name).put("code", code)
            .put("mappedFeedbackActionable", unknownFeedbackActionable)
        ActivityScenario.launch(LoginActivity::class.java).use {
            submitCredentials()
            await("Unknown-account request must restore controls with feedback") { main {
                val activity = resumed(LoginActivity::class.java)
                val message = activity?.findViewById<TextView>(R.id.statusText)?.text?.toString().orEmpty()
                activity?.findViewById<View>(R.id.signInButton)?.isEnabled == true && message.isNotBlank() && message != "Signing in…"
            } }
            val message = main { resumed(LoginActivity::class.java)?.findViewById<TextView>(R.id.statusText)?.text?.toString().orEmpty() }
            unknownUiActionable = message.contains("email and password") && message.contains("Create an account")
            assertNull("Rejected credentials must not bind the installation", repository.boundUid())
            assertNull(FirebaseAuth.getInstance().currentUser)
        }
    }

    private fun signIn() {
        submitCredentials()
        await("Native sign-in must reach Main") {
            val state = main {
                val login = resumed(LoginActivity::class.java)
                val status = login?.findViewById<TextView>(R.id.statusText)?.text?.toString().orEmpty()
                status.startsWith("Couldn’t finish") to
                    (resumed(MainActivity::class.java) != null && root()?.findViewById<View>(R.id.nav_account)?.isShown == true)
            }
            assertFalse("Existing-account login must not show the generic failure", state.first)
            state.second
        }
    }

    private fun submitCredentials() {
        await("Login must enable sign-in") { main { resumed(LoginActivity::class.java)?.findViewById<View>(R.id.signInButton)?.isEnabled == true } }
        onView(withId(R.id.emailInput)).perform(scrollTo(), replaceText(email))
        onView(withId(R.id.passwordInput)).perform(scrollTo(), replaceText(password))
        closeSoftKeyboard()
        onView(withId(R.id.signInButton)).perform(scrollTo(), click())
    }

    private fun assertProfileUnchanged() {
        val response = tree(setupToken!!)
        assertEquals(200, response.code)
        val value = JSONObject(response.body)
        assertEquals("Login may not create or overwrite private records", setOf("profile"), value.keys().asSequence().toSet())
        assertEquals(canonical(initialProfile!!), canonical(value.getJSONObject("profile")))
    }

    private fun cleanupOwnedSyntheticAccount(): JSONObject {
        cleanupStep = "recover-exact-generated-identity"
        if (setupToken == null && !signupSubmitted)
            return JSONObject().put("status", "CONFIRMED").put("accountCreated", false)
        var recoveredAfterUnconfirmedSignup = false
        if (setupToken == null) {
            val recovery = auth("signInWithPassword", JSONObject().put("email", email).put("password", password)
                .put("returnSecureToken", true))
            assertEquals("Submitted signup has an uncertain outcome; cleanup requires the exact generated identity", 200, recovery.code)
            val recovered = JSONObject(recovery.body)
            val recoveredUid = recovered.getString("localId")
            ownerUid?.let { assertEquals("Recovery may not replace an already captured UID", it, recoveredUid) }
            ownerUid = recoveredUid
            setupToken = recovered.getString("idToken")
            recoveredAfterUnconfirmedSignup = true
        }
        val token = requireNotNull(setupToken)
        cleanupStep = "verify-owned-uid-email-and-creation-window"
        val identity = auth("lookup", JSONObject().put("idToken", token))
        assertEquals(200, identity.code)
        val users = JSONObject(identity.body).getJSONArray("users")
        assertEquals(1, users.length())
        assertEquals(ownerUid, users.getJSONObject(0).getString("localId"))
        assertEquals(email, users.getJSONObject(0).getString("email"))
        val createdAt = users.getJSONObject(0).getString("createdAt").toLong()
        assertTrue("Only a synthetic identity created during this run is owned",
            createdAt in (startedAt - 120_000)..(System.currentTimeMillis() + 60_000))
        main { repository.logout() }
        cleanupStep = "verify-exact-owned-profile-snapshot"
        val snapshot = tree(token, etag = true)
        assertEquals(200, snapshot.code)
        if (snapshot.body != "null") {
            val value = JSONObject(snapshot.body)
            assertEquals("Unknown private data stops cleanup", setOf("profile"), value.keys().asSequence().toSet())
            assertNotNull("A signup without profile setup must still have an empty private tree", initialProfile)
            assertEquals(canonical(initialProfile!!), canonical(value.getJSONObject("profile")))
            assertNotNull(snapshot.etag)
            cleanupStep = "conditional-owned-tree-deletion"
            assertEquals(200, tree(token, "DELETE", ifMatch = snapshot.etag).code)
        }
        assertEquals("null", tree(token).body)
        cleanupStep = "delete-exact-owned-auth-and-confirm-absence"
        assertEquals(200, auth("delete", JSONObject().put("idToken", token)).code)
        val missing = auth("lookup", JSONObject().put("idToken", token))
        assertEquals(400, missing.code)
        assertEquals("USER_NOT_FOUND", JSONObject(missing.body).getJSONObject("error").getString("message"))
        return JSONObject().put("status", "CONFIRMED").put("exactOwnedTreeDeletedAndReadBack", true)
            .put("exactOwnedAuthDeletedAndLookupDenied", true).put("creationWindowConfirmed", true)
            .put("recoveredAfterUnconfirmedSignup", recoveredAfterUnconfirmedSignup)
    }

    private data class Response(val code: Int, val body: String, val etag: String?)
    private fun auth(action: String, body: JSONObject): Response {
        assertTrue(action in setOf("signUp", "signInWithPassword", "lookup", "delete"))
        val origin = if (BuildConfig.USE_FIREBASE_EMULATORS) "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9099/identitytoolkit.googleapis.com" else "https://identitytoolkit.googleapis.com"
        val url = "$origin/v1/accounts:$action"
            .toHttpUrl().newBuilder().addQueryParameter("key", BuildConfig.FIREBASE_API_KEY).build()
        val request = Request.Builder().url(url)
        if (!BuildConfig.USE_FIREBASE_EMULATORS) {
            @Suppress("DEPRECATION")
            val signature = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures?.single()
                ?: throw AssertionError("APK signer unavailable")
            val cert = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }
            request.header("X-Android-Package", app.packageName).header("X-Android-Cert", cert)
        }
        return execute(request.post(body.toString().toRequestBody("application/json".toMediaType())).build())
    }
    private fun tree(token: String, method: String = "GET", body: String? = null, etag: Boolean = false, ifMatch: String? = null): Response {
        val uid = requireNotNull(ownerUid)
        val origin = if (BuildConfig.USE_FIREBASE_EMULATORS) "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000" else CLOUD_DATABASE
        val builder = origin.toHttpUrl().newBuilder().addPathSegment("users").addPathSegment("$uid.json")
        if (BuildConfig.USE_FIREBASE_EMULATORS) builder.addQueryParameter("ns", "demo-studypath-default-rtdb")
        val url = builder
            .addQueryParameter("auth", token).build()
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
        if (etag) request.header("X-Firebase-ETag", "true")
        if (ifMatch != null) request.header("if-match", ifMatch)
        return execute(request.method(method, body?.toRequestBody("application/json".toMediaType())).build())
    }
    private fun execute(request: Request): Response = client.newCall(request).execute().use { response ->
        val body = response.body ?: throw AssertionError("Local response unavailable")
        val source = body.source(); source.request(262_145)
        assertTrue("Local response exceeds this bounded regression probe", source.buffer.size <= 262_144)
        Response(response.code, source.buffer.readByteArray().toString(Charsets.UTF_8), response.header("ETag"))
    }
    private fun record(name: String) { checks.put(JSONObject().put("name", name).put("status", "PASSED")) }
    private fun canonical(value: JSONObject): String = value.keys().asSequence().sorted().joinToString("|", "{", "}") { key ->
        val item = value.get(key); "$key:${if (item is JSONObject) canonical(item) else item}" }
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(65_536); while (true) {
            val count = stream.read(buffer); if (count < 0) break; sha.update(buffer, 0, count)
        } }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun avdName() = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30_000
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
        private const val CLOUD_PROJECT = "studypath-c8086"
        private const val CLOUD_DATABASE = "https://studypath-c8086-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}
