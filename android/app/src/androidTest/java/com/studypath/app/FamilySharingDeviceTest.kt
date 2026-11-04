package com.studypath.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.*
import com.studypath.app.ui.FamilySharingFragment
import com.studypath.app.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.hamcrest.Matchers.`is`
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Two independent Firebase Auth identities, real token REST and selected native sharing journeys. */
@RunWith(AndroidJUnit4::class)
class FamilySharingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val namespace get() = requireNotNull(InstrumentationRegistry.getArguments().getString("familyNamespace"))
    private val marker get() = File(app.filesDir, "family-sharing-device.json")
    private val owner by lazy { Side("owner") }
    private val recipient by lazy { Side("recipient") }

    private inner class Side(val label: String) {
        private val root = File(app.filesDir, "family-device-${namespace.takeLast(8)}-$label")
        private val firebase = FirebaseApp.initializeApp(app, FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setDatabaseUrl("https://$namespace.firebaseio.com").build(), "FamilyDevice-${namespace.takeLast(8)}-$label")
        val auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
        private val database = FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000); setPersistenceEnabled(true)
        }
        val repository = FirebaseStudyRepository(auth, database, app.getSharedPreferences("family-device-${namespace.takeLast(8)}-$label", 0),
            TextPackageStore(File(root, "packages")), LessonRunStore(File(root, "runs")),
            packageAssetDownloader = MockPackageAssets.downloader("https://mock-assets.example.org".toHttpUrl(),
                "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:8765".toHttpUrl()),
            packageDownloadDirectory = File(root, "downloads"), operationDirectory = File(root, "operations"), reminderChanged = { _, _, _ -> })
        val network = FamilySharingNetwork(OkHttpClient(), "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000/?ns=$namespace".toHttpUrl(), true)
        var credentialRefreshes = 0
        val client = FamilySharingClient(network, {
            repository.familyCredentials().also { credentialRefreshes++; assertEquals(auth.currentUser?.uid, it.uid) }
        }, allowSynthetic = true)
    }

    @Before fun isolatedOnly() {
        assertTrue(BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue(namespace.matches(Regex("demo-studypath-family-device-[a-f0-9]{8}")))
        assertTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith("com.studypath.app.FamilySharingDeviceTest#") == true)
    }

    @Test fun independentOwnerAndRecipientNativeJourneys() = runBlocking<Unit> {
        withTimeout(360_000) {
            assertFalse("Use a fresh read-only Test AVD; never overwrite prior family evidence", marker.exists())
            assertNull(owner.repository.boundUid()); assertNull(recipient.repository.boundUid())
            val ownerSession = owner.repository.register("family_owner_${UUID.randomUUID()}@studypath.invalid", "Family-local-only-42", "MOCK owner")
            val recipientSession = recipient.repository.register("family_recipient_${UUID.randomUUID()}@studypath.invalid", "Family-local-only-42", "MOCK recipient")
            assertNotEquals(ownerSession.uid, recipientSession.uid)
            val receipt = JSONObject().put("namespace", namespace).put("ownerUid", ownerSession.uid).put("recipientUid", recipientSession.uid)
                .put("ownerEmail", ownerSession.email).put("recipientEmail", recipientSession.email)
                .put("syntheticOnly", true).put("observations", JSONObject()); save(receipt)
            request(receipt, "seed_trusted")
            for (side in listOf(owner, recipient)) {
                side.repository.connection().first { it }
                side.repository.saveLearnerPreferences(LearnerPreferences("mock_college", "mock_computing_v1", setOf("computing"), null, true))
            }
            val summary = owner.repository.moduleLibrary().first { it.modules.size == 3 }.modules.single { it.moduleId == "mock_programming" }
            owner.repository.downloadTextPackage(summary)
            val module = InteractiveLessonParser.parse(requireNotNull(owner.repository.openTextPackage(TextPackageKey("mock_computing_v1", "mock_programming", 1))).verified)
            val run = complete(module); owner.repository.saveLessonRun(module, run); owner.repository.submitLessonRun(module, run)
            assertNotNull(owner.repository.lessonRunConfirmedAt(module, run))
            receipt.put("attempt", JSONObject(LessonEngine.attempt(module, run))); save(receipt)
            observed(receipt, "realAcknowledgedAuthoredLessonProvidesProtectedNonzeroSource")
            // Preserve the public client's exact availability decision before the UI can replace its text.
            val preflight = runCatching { owner.client.ownerState() }
            receipt.put("ownerStateBeforeUi", preflight.fold(onSuccess = { state ->
                JSONObject().put("synthetic", state.synthetic).put("unavailableReason", state.unavailableReason ?: JSONObject.NULL)
                    .put("grantCount", state.grants.size).put("acceptanceCount", state.acceptances.size)
            }, onFailure = { error -> JSONObject().put("exception", error.javaClass.name).put("message", error.message ?: "") }))
            val clockProof = owner.network.request(listOf("catalog", "sharingPolicies", "mock_computing_v1"), owner.repository.familyCredentials().idToken)
            receipt.put("familyResponseTimeSource", clockProof.timeSource.name)
            save(receipt)

            lateinit var invitationCode: String
            familyUi(owner) {
                await("Owner may choose to create an invitation") { text("family_status").contains("Mock demonstration permission is active") }
                tap("Create invitation")
                await("Owner invitation code") { text("family_invitation_code").startsWith("SPI1.") }
                invitationCode = text("family_invitation_code"); capture("01-owner-invitation")
            }
            val invitation = FamilySharingRecords.invitation(invitationCode)
            familyUi(recipient) {
                tap("View")
                fill("family_accept_code", invitationCode); tap("Accept invitation"); confirm()
                await("Independent recipient acceptance acknowledged") { text("family_status").contains("accepted. The student still needs") }
                capture("02-recipient-acceptance")
            }
            observed(receipt, "independentRecipientExplicitlyAcceptsWithoutReceivingAccess")
            lateinit var viewingCode: String
            familyUi(owner) {
                await("Owner sees independently accepted recipient") { allText().contains("Share with this recipient") }
                tap("Share with this recipient"); confirm()
                await("Owner grant acknowledgement and viewing code") { text("family_viewing_code_g0").startsWith("SPF1.") }
                viewingCode = text("family_viewing_code_g0"); capture("03-owner-explicit-grant")
            }
            val firstGrant = owner.client.ownerState().grants.single { it.slotId == "g0" }
            assertEquals(1L, firstGrant.generation)
            val first = recipient.client.readSummary(viewingCode)
            assertEquals(1, first.completedLessonCount); assertTrue(first.asOf <= first.serverNow && first.expiresAt > first.serverNow)
            receipt.put("initialAsOf", first.asOf).put("initialExpiresAt", first.expiresAt).put("viewingCode", viewingCode)
            familyUi(recipient) {
                tap("View")
                fill("family_view_code", viewingCode); tap("Open shared summary")
                await("Fresh recipient count and timestamps visible") { text("family_summary").contains("Self-reported completed lessons: 1") && text("family_summary").contains("Updated ") && text("family_summary").contains("Access expires ") }
                assertSummaryDisplayed()
                capture("04-recipient-fresh-summary")
                observed(receipt, "freshRecipientViewShowsOnlyCountAsOfAndExpiry")
                request(receipt, "partition")
                await("Visible summary clears on actual transport partition") { !text("family_summary").contains("Self-reported completed lessons:") }
                tap("Open shared summary")
                await("Offline fresh read refuses cache", 35_000) { text("family_summary").contains("Summary unavailable") }
                assertSummaryDisplayed()
                assertTrue(runCatching { withTimeout(20_000) { recipient.client.readSummary(viewingCode) } }.isFailure)
                capture("05-offline-no-cached-summary")
                observed(receipt, "offlineRecipientClearsAndCannotReusePreviouslyViewedSummary")
                request(receipt, "online")
                // Host radio commands can finish before guest networking/Auth is reachable again.
                // Prove readiness, then explicitly retry the UI action; no automatic app retry is claimed.
                val reconnect = withTimeout(60_000) {
                    recipient.repository.connection().first { it }
                    var response: FamilyRestResponse? = null
                    while (response == null) {
                        try {
                            val credentials = recipient.repository.familyCredentials()
                            response = recipient.network.request(listOf("catalog", "sharingPolicies", "mock_computing_v1"), credentials.idToken)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            delay(250)
                        }
                    }
                    requireNotNull(response)
                }
                receipt.put("reconnectReadiness", JSONObject().put("firebaseConnectionObserved", true)
                    .put("freshAuthCredentialObtained", true).put("freshPolicyResponseObtained", true)
                    .put("timeSource", reconnect.timeSource.name).put("beforeExplicitUiRetry", true))
                observed(receipt, "hostReconnectWaitsForFreshAuthAndPolicyBeforeExplicitUiRetry")
                tap("Open shared summary")
                await("Reconnect performs fresh read") { text("family_summary").contains("Self-reported completed lessons: 1") }
                assertSummaryDisplayed()
                observed(receipt, "explicitRecipientReadRevealsSummaryWithoutTestScrolling")
            }
            familyUi(owner) {
                await("Owner controls initial load completes after reconnect") {
                    val status = text("family_status")
                    status.isNotBlank() && !status.contains("Checking current sharing permission") && !status.contains("Loading sharing controls")
                }
                val initialStatus = text("family_status")
                val networkError = initialStatus.startsWith("Could not reach the reading service.") ||
                    initialStatus.contains("fresh sharing connection is unavailable", ignoreCase = true)
                receipt.put("ownerControlsAfterReconnect", JSONObject().put("initialStatus", initialStatus)
                    .put("explicitRefreshPerformed", networkError)); save(receipt)
                if (networkError) {
                    tap("Refresh sharing controls")
                    observed(receipt, "ownerExplicitlyRefreshesControlsAfterPostPartitionNetworkError")
                }
                await("Owner active grant") { text("family_viewing_code_g0").isNotBlank() }
                tap("Update summary"); await("Explicit summary refresh acknowledged") { text("family_status").contains("Summary update confirmed") }
                tap("Revoke sharing"); confirm()
                await("Revocation is server acknowledged") { text("family_status").contains("Revocation confirmed") }
                capture("06-owner-confirmed-revoke")
            }
            familyUi(recipient) {
                tap("View")
                fill("family_view_code", viewingCode); tap("Open shared summary")
                await("Fresh recipient read denied after revoke") { text("family_summary").contains("Summary unavailable") }
                assertSummaryDisplayed()
                assertFalse(text("family_summary").contains("Self-reported completed lessons:")); capture("07-recipient-revoked")
            }
            assertTrue(runCatching { recipient.client.readSummary(viewingCode) }.isFailure)
            observed(receipt, "ownerExplicitRevokeDeniesFreshIndependentRecipientRead")

            val second = owner.client.grant(invitation.acceptanceId, "g0")
            assertEquals(2L, second.generation)
            assertTrue(runCatching { owner.client.revoke("g0", firstGrant.generation) }.isFailure)
            assertTrue(runCatching { recipient.client.readSummary(viewingCode) }.isFailure)
            assertEquals(1, recipient.client.readSummary(second.link.code()).completedLessonCount)
            assertEquals(2L, owner.client.ownerState().grants.single { it.slotId == "g0" }.generation)
            owner.client.revoke("g0", second.generation)
            observed(receipt, "staleGenerationCannotRevokeOrReadRegrantedSlot")

            val now = owner.network.request(listOf("catalog", "sharingPolicies", "mock_computing_v1"), owner.repository.familyCredentials().idToken).serverNow
            val expiring = owner.client.createInvitation().copy(expiresAt = now + 15_000)
            recipient.client.acceptInvitation(expiring.code())
            val short = owner.client.grant(expiring.acceptanceId, "g0")
            familyUi(recipient) {
                tap("View")
                fill("family_view_code", short.link.code()); tap("Open shared summary")
                await("Short-lived summary initially authorized") { text("family_summary").contains("Self-reported completed lessons: 1") }
                assertSummaryDisplayed()
                val snapshot = recipient.client.readSummary(short.link.code())
                delay((snapshot.expiresAt - snapshot.serverNow + 1500).coerceAtLeast(1))
                await("Expiry clears displayed summary without manual refresh", 25_000) { text("family_summary").contains("Summary unavailable") }
                assertSummaryDisplayed()
                assertTrue(runCatching { recipient.client.readSummary(short.link.code()) }.isFailure)
                capture("08-expired-summary-cleared")
            }
            owner.client.revoke("g0", short.generation)
            observed(receipt, "actualExpiryClearsVisibleSummaryAndDeniesFreshRead")
            receipt.put("ownerCredentialRefreshes", owner.credentialRefreshes).put("recipientCredentialRefreshes", recipient.credentialRefreshes)
            assertTrue(owner.credentialRefreshes >= 8 && recipient.credentialRefreshes >= 8); save(receipt)
        }
    }

    @Test fun cleanupOwnedSyntheticAccounts() = runBlocking<Unit> {
        withTimeout(45_000) {
            val receipt = read()
            for ((side, field) in listOf(owner to "ownerUid", recipient to "recipientUid")) {
                assertEquals(receipt.getString(field), side.auth.currentUser?.uid)
                requireNotNull(side.auth.currentUser).delete().await(); side.auth.signOut()
            }
            observed(receipt, "bothOwnedSyntheticAuthIdentitiesDeleted")
        }
    }

    private suspend fun familyUi(side: Side, block: suspend () -> Unit) {
        val original = app.graph
        val graphField = StudyPathApplication::class.java.getDeclaredField("graph").apply { isAccessible = true }
        instrumentation.runOnMainSync { graphField.set(app, StudyPathGraph(side.repository, original.readings, side.client)) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("Selected named repository opens MainActivity") {
                    var ready = false; scenario.onActivity { ready = it.findViewById<View>(R.id.main_content) != null }; ready
                }
                scenario.onActivity { it.supportFragmentManager.beginTransaction().replace(R.id.main_content, FamilySharingFragment()).commitNow() }
                await("Family view present") { text("family_summary").isNotBlank() }
                try { block() }
                catch (error: Throwable) {
                    // Capture while the scenario and current fragment still exist, before use closes it.
                    runCatching {
                        val diagnostic = JSONObject().put("namespace", namespace).put("side", side.label)
                            .put("exception", error.javaClass.name).put("message", error.message ?: "")
                            .put("familyStatus", text("family_status")).put("familySummary", text("family_summary"))
                            .put("visibleText", allText())
                        File(app.filesDir, "family-sharing-ui-failure.json").writeText(diagnostic.toString())
                        val receipt = read(); receipt.put("uiFailure", diagnostic); save(receipt)
                    }
                    runCatching { capture("ui-failure") }
                    throw error
                }
            }
        } finally { instrumentation.runOnMainSync { graphField.set(app, original) } }
    }
    private fun complete(module: InteractiveModule): LessonRun {
        var now = System.currentTimeMillis(); var run = LessonEngine.start(module, "lesson_1", now++, "family_source_${UUID.randomUUID()}")
        while (!run.completed) {
            val step = requireNotNull(LessonEngine.currentStep(module, run))
            step.questionId?.let { id ->
                val q = module.questions.getValue(id)
                val answer = if (q.format == LessonQuestionFormat.BOUNDED_RESPONSE) listOf(requireNotNull(q.target).toInt().toString()) else q.answer
                run = LessonEngine.select(module, run, step.id, answer, now++); run = LessonEngine.check(module, run, step.id, now++)
                assertEquals(LessonAnswerStatus.CORRECT, run.answers.getValue(id).status)
            }
            run = LessonEngine.continueStep(module, run, step.id, now++)
        }
        return run
    }
    private fun text(tag: String): String {
        var result = ""; instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()?.let {
                result = it.findViewById<View>(android.R.id.content)?.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty()
            }
        }; return result
    }
    private fun allText(): String {
        var result = ""; instrumentation.runOnMainSync {
            fun visit(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { visit(view.getChildAt(it)) } else emptyList())
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()?.let {
                result = visit(it.findViewById(android.R.id.content)).joinToString("\n")
            }
        }; return result
    }
    private fun tap(label: String) { onView(withText(label)).perform(scrollTo(), click()) }
    private fun assertSummaryDisplayed() {
        onView(withTagValue(`is`("family_summary" as Any))).check(matches(isDisplayed()))
    }
    private fun fill(tag: String, value: String) { onView(withTagValue(`is`(tag as Any))).perform(scrollTo(), replaceText(value)); closeSoftKeyboard() }
    private fun confirm() { onView(withId(android.R.id.button1)).perform(click()) }
    private fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!predicate()) { assertTrue(label, SystemClock.uptimeMillis() < deadline); SystemClock.sleep(75) }
    }
    private fun capture(name: String) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.filesDir, "family-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
    private suspend fun request(receipt: JSONObject, action: String) {
        receipt.put("hostAction", action); save(receipt)
        withTimeout(45_000) {
            while (true) {
                val ack = runCatching { JSONObject(File(app.filesDir, "family-sharing-host-ack.json").readText()) }.getOrNull()
                if (ack != null && ack.optString("namespace") == namespace && ack.optString("action") == action) break
                delay(50)
            }
        }
        receipt.put("hostActionAcknowledged", action); save(receipt)
    }
    private fun observed(receipt: JSONObject, flag: String) { receipt.getJSONObject("observations").put(flag, true); save(receipt) }
    private fun read() = JSONObject(marker.readText()).also { assertEquals(namespace, it.getString("namespace")) }
    private fun save(value: JSONObject) {
        val atomic = AtomicFile(marker); val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
