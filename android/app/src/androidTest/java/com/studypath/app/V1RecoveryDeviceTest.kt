package com.studypath.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.studypath.app.data.*
import com.studypath.app.domain.OperationPresentation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Host partitions/kills between stages. PDF bytes use an explicit injected synthetic transport. */
@RunWith(AndroidJUnit4::class)
class V1RecoveryDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val namespace get() = requireNotNull(InstrumentationRegistry.getArguments().getString("v1RecoveryNamespace"))
    private val root get() = File(app.filesDir, "v1-recovery-${namespace.takeLast(8)}")
    private val marker get() = File(app.filesDir, "v1-recovery-device.json")
    private val expected get() = JSONObject(File(app.filesDir, "v1-recovery-fixture.json").readText()).also {
        assertEquals(namespace, it.getString("namespace")); assertTrue(it.getBoolean("testOnly"))
    }
    private val firebase by lazy {
        FirebaseApp.initializeApp(app, FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL).build(), "V1Recovery-${namespace.takeLast(8)}")
    }
    private val auth by lazy { FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) } }
    private val database by lazy { FirebaseDatabase.getInstance(firebase).apply {
        useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000); setPersistenceEnabled(true)
    } }
    private val packages by lazy { TextPackageStore(File(root, "packages")) }
    private val runs by lazy { LessonRunStore(File(root, "runs")) }
    private val repository by lazy {
        val bytes = Base64.decode(expected.getString("pdfBase64"), Base64.DEFAULT)
        val transport = PackageAssetTransport { asset ->
            if (asset.url.endsWith("/v6.pdf")) throw IOException("Explicit synthetic missing-asset response")
            val response = if (asset.url.endsWith("/v5.pdf")) bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() } else bytes
            PackageAssetResponse(200, "application/pdf", response.size.toLong(), response.inputStream()) {}
        }
        FirebaseStudyRepository(auth, database, app.getSharedPreferences("v1-recovery-${namespace.takeLast(8)}", 0),
            packages, runs, packageAssetDownloader = PackageAssetDownloader(transport),
            packageDownloadDirectory = File(root, "downloads"), operationDirectory = File(root, "operations"),
            reminderChanged = { _, _, _ -> })
    }

    @Before fun explicitIsolatedBuildOnly() {
        assertTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.V1RecoveryDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue(namespace.matches(Regex("demo-studypath-v1-fixes-[a-f0-9]{8}")))
        assertEquals("https://$namespace.firebaseio.com", BuildConfig.FIREBASE_DATABASE_URL)
    }

    @Test fun downloadPdfAndQueueCancelledRecovery() = runBlocking<Unit> {
        withTimeout(240_000) {
            assertFalse("Restart Test AVD; never overwrite a previous recovery workspace", marker.exists())
            assertNull(repository.boundUid())
            val user = repository.register("v1_recovery_${UUID.randomUUID()}@studypath.invalid",
                "V1-recovery-synthetic-42", "Synthetic recovery learner")
            val receipt = JSONObject().put("schemaVersion", 1).put("namespace", namespace).put("uid", user.uid)
                .put("observations", JSONObject()).put("attempts", JSONObject()).put("operations", JSONObject())
            save(receipt)
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences("fixture_level", "fixture_v1", setOf("fixture_subject"), "fixture_class", true))
            val summaries = repository.moduleLibrary().first { it.modules.size == 7 }.modules
            for (version in listOf(1, 2, 3, 4, 7)) {
                val stored = repository.downloadTextPackage(summaries.single { it.version == version })
                assertTrue(stored.ready); assertNotNull(stored.confirmedAt)
            }
            assertTrue(requireNotNull(repository.openTextPackage(key(4))).verified.summary.assetBytes > 0)
            renderRetainedPdf(receipt)
            observed(receipt, "pdfRepositoryDownloadAcknowledgedBeforeReady")
            for (version in listOf(5, 6)) {
                assertTrue("Corrupt/missing synthetic PDF must fail", runCatching {
                    repository.downloadTextPackage(summaries.single { it.version == version })
                }.isFailure)
                assertNull(repository.openTextPackage(key(version)))
            }
            observed(receipt, "corruptAndMissingPdfNeverBecomeReady")

            // Registration writes profile and downloads read only their exact catalog/receipt paths.
            // This fresh named database has never listened to the reminders parent, including absence.
            val beforeUnknownRemoval = requireNotNull(repository.openTextPackage(key(2)))
            val journalBeforeUnknownRemoval = repository.operationStates().first()
            request(receipt, "partition_unknown_reminders"); repository.connection().first { !it }
            val unknownRemovalError = runCatching { withTimeout(10_000) { repository.removeTextPackage(key(2)) } }.exceptionOrNull()
            assertTrue("An unknown reminders parent must return the explicit reconnect error; actual=$unknownRemovalError",
                unknownRemovalError is IllegalStateException &&
                    unknownRemovalError.message?.contains("Reconnect and retry removal") == true)
            assertEquals(beforeUnknownRemoval, packages.read(user.uid, "fixture_v1", "fixture_module", 2))
            assertNotNull(repository.openTextPackage(key(2)))
            assertFalse(packages.isRemoving(user.uid, key(2)))
            assertEquals(journalBeforeUnknownRemoval, repository.operationStates().first())
            receipt.put("unknownReminderRemovalError", unknownRemovalError?.message)
            observed(receipt, "unknownReminderParentRejectsRemovalAndPreservesReadyBytesAndJournal")
            request(receipt, "reconnect_prime_reminders"); repository.connection().first { it }
            repository.saveProgress(progress(false)); repository.reminders().first()
            val module = module(1)
            val original = complete(module, "cancelled_waiter")
            repository.saveLessonRun(module, original)
            val active = LessonEngine.start(module, "lesson_1", requireNotNull(original.completedAt) + 1, "newer_active")
            repository.saveLessonRun(module, active)
            receipt.put("activeRunId", active.runId)
            receipt.getJSONObject("attempts").put(original.runId, JSONObject(LessonEngine.attempt(module, original)))
            request(receipt, "partition_one"); repository.connection().first { !it }
            val waiter = launch { repository.submitLessonRun(module, original) }
            val operation = operation("set/lessonAttempts/${original.runId}")
            receipt.getJSONObject("operations").put("cancelledWaiter", operation.operationId); save(receipt)
            assertTrue(waiter.isActive); waiter.cancelAndJoin()
            assertNull(repository.lessonRunConfirmedAt(module, original))
            val retry = launch { repository.retryOperation(operation.operationId) }
            assertTrue(retry.isActive)
            request(receipt, "reconnect_one")
            withTimeout(60_000) { retry.join() }
            assertNotNull("Joining the existing SDK Task must finish domain readback and confirmation",
                repository.lessonRunConfirmedAt(module, original))
            assertEquals(active, repository.lessonRun(module, "lesson_1"))
            observed(receipt, "cancelledWaiterExactRetryConfirmsOriginalAndPreservesNewerActive")
            request(receipt, "partition_two"); repository.connection().first { !it }
            val restart = complete(module, "restart_original")
            repository.saveLessonRun(module, restart)
            val newest = LessonEngine.start(module, "lesson_1", requireNotNull(restart.completedAt) + 1, "restart_newer_active")
            repository.saveLessonRun(module, newest); receipt.put("activeRunId", newest.runId)
            val pending = listOf(1 to restart, 3 to complete(module(3), "review_required_result"),
                7 to complete(module(7), "retained_copy_result"))
            for ((version, run) in pending) {
                val content = module(version)
                if (version != 1) repository.saveLessonRun(content, run)
                val submission = launch { repository.submitLessonRun(content, run) }
                val queued = operation("set/lessonAttempts/${run.runId}")
                assertTrue(submission.isActive); submission.cancelAndJoin()
                assertNull(repository.lessonRunConfirmedAt(content, run))
                receipt.getJSONObject("operations").put("v$version", queued.operationId)
                receipt.getJSONObject("attempts").put(run.runId, JSONObject(LessonEngine.attempt(content, run)))
                save(receipt)
                assertPayload(queued, receipt)
            }
            val removal = launch { repository.removeTextPackage(key(2)) }
            val removing = operation("removePackage/fixture_v1/fixture_module/v2")
            assertTrue(removal.isActive); removal.cancelAndJoin()
            receipt.getJSONObject("operations").put("removal", removing.operationId)
            save(receipt)
            assertTrue(packages.isRemoving(user.uid, key(2)))
            assertFalse(requireNotNull(packages.read(user.uid, "fixture_v1", "fixture_module", 2)).ready)
            observed(receipt, "cancelledRemovalPersistsUnavailableIntent")
            val progressWrite = launch { repository.saveProgress(progress(true)) }
            val progressOperation = operation("set/reviewProgress/fixture_module/v1")
            receipt.getJSONObject("operations").put("progress", progressOperation.operationId); save(receipt)
            progressWrite.cancelAndJoin()
            receipt.put("stage1Pid", Process.myPid()); save(receipt)
        }
    }

    @Test fun restoreOfflineAndRenderPdfAfterProcessDeath() = runBlocking<Unit> {
        withTimeout(90_000) {
            val receipt = read(); assertNotEquals(receipt.getInt("stage1Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { !it }
            val pending = repository.operationStates().first()
            for (version in listOf(1, 3, 7)) {
                val record = pending.single { it.operationId == receipt.getJSONObject("operations").getString("v$version") }
                assertEquals(OperationSyncState.UNKNOWN_AFTER_RESTART, record.state)
                assertPayload(record, receipt)
            }
            assertEquals(receipt.getString("activeRunId"), repository.lessonRun(module(1), "lesson_1")?.runId)
            assertTrue(packages.isRemoving(receipt.getString("uid"), key(2)))
            assertFalse(requireNotNull(packages.read(receipt.getString("uid"), "fixture_v1", "fixture_module", 2)).ready)
            renderRetainedPdf(receipt)
            observed(receipt, "offlineProcessDeathPreservesExactPayloadsRemovalIntentAndPdf")
            receipt.put("stage2Pid", Process.myPid()); save(receipt)
        }
    }

    @Test fun reconnectResolveRetainedResultsAndRemoval() = runBlocking<Unit> {
        withTimeout(150_000) {
            val receipt = read(); assertNotEquals(receipt.getInt("stage2Pid"), Process.myPid())
            val uid = receipt.getString("uid")
            assertEquals(uid, repository.resumeSession()?.uid); repository.connection().first { it }
            val module = module(1)
            val original = requireNotNull(runs.get(uid, module, "restart_original"))
            repository.retryOperation(receipt.getJSONObject("operations").getString("v1"))
            assertNotNull(repository.lessonRunConfirmedAt(module, original))
            assertEquals(receipt.getString("activeRunId"), repository.lessonRun(module, "lesson_1")?.runId)
            observed(receipt, "restartRetryUsesOriginalIdentityPayloadAndDomainConfirmation")
            repository.retryOperation(receipt.getJSONObject("operations").getString("removal"))
            assertNull(packages.read(uid, "fixture_v1", "fixture_module", 2))
            observed(receipt, "acknowledgedCancelledRemovalReconcilesLocalFiles")

            val reviewModule = module(3)
            val reviewRun = requireNotNull(runs.get(uid, reviewModule, "review_required_result"))
            val denied = runCatching { withTimeout(20_000) {
                repository.retryOperation(receipt.getJSONObject("operations").getString("v3"))
            } }.exceptionOrNull()
            assertNotNull("Review-required withdrawal must reject its new result", denied)
            assertFalse("A timeout is not a verified server rejection", denied is kotlinx.coroutines.TimeoutCancellationException)
            val reviewRecord = repository.operationStates().first().single {
                it.operationId == receipt.getJSONObject("operations").getString("v3") }
            assertEquals(OperationSyncState.REJECTED, reviewRecord.state); assertPayload(reviewRecord, receipt)
            repository.removeTextPackage(key(3))
            assertNull(packages.read(uid, "fixture_v1", "fixture_module", 3))
            assertEquals(reviewRun, runs.get(uid, reviewModule, reviewRun.runId))
            assertPayload(reviewRecord, receipt)
            val archived = repository.operationStates().first().single { it.relativePath == "withdrawnLessonAttempts/${reviewRun.runId}" }
            assertPayload(archived, receipt)
            assertEquals("Result kept · lesson unavailable", OperationPresentation.status(archived))
            assertFalse(OperationPresentation.retryable(archived))
            observed(receipt, "reviewRequiredRemovalPreservesRejectedRunAndExactPayload")

            val retainedModule = module(7)
            val retained = requireNotNull(runs.get(uid, retainedModule, "retained_copy_result"))
            repository.retryOperation(receipt.getJSONObject("operations").getString("v7"))
            assertNotNull(repository.lessonRunConfirmedAt(retainedModule, retained))
            repository.removeTextPackage(key(7))
            assertNull(packages.read(uid, "fixture_v1", "fixture_module", 7))
            observed(receipt, "retainedCopyWithdrawalConfirmsPrivateResultBeforeRemoval")
            assertTrue(repository.reviewProgress("fixture_module", 1).first { it?.completed == true }!!.completed)
            assertNotNull(repository.openTextPackage(key(1))); renderRetainedPdf(receipt)
            observed(receipt, "otherPackagesAndQueuedProgressSurviveRecovery")
            receipt.put("stage3Pid", Process.myPid()); save(receipt)
        }
    }

    @Test fun verifyRemovalAndRejectedHistoryAfterAnotherRestart() = runBlocking<Unit> {
        withTimeout(60_000) {
            val receipt = read(); assertNotEquals(receipt.getInt("stage3Pid"), Process.myPid())
            val uid = receipt.getString("uid")
            assertEquals(uid, repository.resumeSession()?.uid)
            for (version in listOf(2, 3, 7)) assertNull(packages.read(uid, "fixture_v1", "fixture_module", version))
            val rejected = repository.operationStates().first().single {
                it.operationId == receipt.getJSONObject("operations").getString("v3") }
            assertEquals(OperationSyncState.REJECTED, rejected.state); assertPayload(rejected, receipt)
            val archived = repository.operationStates().first().single { it.relativePath == "withdrawnLessonAttempts/review_required_result" }
            assertPayload(archived, receipt)
            assertFalse(OperationPresentation.retryable(archived))
            assertNotNull(runs.get(uid, expectedModule(3), "review_required_result"))
            renderRetainedPdf(receipt)
            observed(receipt, "secondRestartKeepsDeletionsAndRejectedRecoveryHistory")
            receipt.put("stage4Pid", Process.myPid()); save(receipt)
        }
    }

    @Test fun cleanupOwnedSyntheticAuth() = runBlocking<Unit> {
        withTimeout(30_000) {
            val receipt = read()
            assertEquals(receipt.getString("uid"), auth.currentUser?.uid)
            requireNotNull(auth.currentUser).delete().await(); auth.signOut(); database.goOffline()
            observed(receipt, "ownedSyntheticAuthDeleted")
        }
    }

    private fun key(version: Int) = TextPackageKey("fixture_v1", "fixture_module", version)
    private fun module(version: Int) = InteractiveLessonParser.parse(requireNotNull(repository.openTextPackage(key(version))).verified)
    @Suppress("UNCHECKED_CAST")
    private fun expectedModule(version: Int): InteractiveModule {
        val content = expected.getJSONObject("packages").getJSONObject("v$version")
        val fields = Gson().fromJson(content.getJSONObject("summary").toString(), Map::class.java) as Map<String, Any?>
        val summary = PublishedModuleCatalog.parse("fixture_v1", "fixture_module", "v$version", fields)
        return InteractiveLessonParser.parse(TextPackageVerifier.verify(summary, content.getString("manifestJson"), content.getString("textJson")))
    }
    private fun complete(module: InteractiveModule, id: String): LessonRun {
        var now = System.currentTimeMillis()
        var run = LessonEngine.start(module, "lesson_1", now++, id)
        while (!run.completed) {
            val step = requireNotNull(LessonEngine.currentStep(module, run))
            if (step.questionId != null) run = LessonEngine.reveal(module, run, step.id, now++)
            run = LessonEngine.continueStep(module, run, step.id, now++)
        }
        return run
    }
    private suspend fun operation(path: String): OperationRecord {
        try {
            return withTimeout(20_000) {
                repository.operationStates().first { rows -> rows.any { it.relativePath == path && it.state != OperationSyncState.CONFIRMED } }
                    .last { it.relativePath == path && it.state != OperationSyncState.CONFIRMED }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val states = repository.operationStates().first().joinToString { "${it.relativePath}=${it.state}" }
            throw AssertionError("Timed out waiting for operation path=$path; current operations=[$states]", error)
        }
    }
    private fun assertPayload(record: OperationRecord, receipt: JSONObject) {
        val file = File(root, "operations/payloads/${receipt.getString("uid")}/${record.operationId}.json")
        val fields = JSONObject(file.readText())
        assertEquals(record.payloadSha256, OperationJournal.payloadFingerprint(fields.getString("payloadJson")))
        assertEquals(record.relativePath, fields.getString("relativePath"))
        val runId = record.relativePath.substringAfterLast('/')
        assertEquals(OperationJournal.payloadFingerprint(receipt.getJSONObject("attempts").getJSONObject(runId).toString()), record.payloadSha256)
    }
    private fun renderRetainedPdf(receipt: JSONObject) {
        val asset = requireNotNull(repository.openPackageAsset(key(4), "pdf_1"))
        assertEquals(expected.getString("pdfSha256"), sha(asset.readBytes()))
        NativePdfAssetValidator.validate(asset)
        ParcelFileDescriptor.open(asset, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertTrue("Native PDF render must produce visible authored text", pixels.any { it != Color.WHITE })
                        File(app.filesDir, "v1-recovery-pdf.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
        receipt.put("pdfPages", 2).put("pdfSha256", sha(asset.readBytes()))
    }
    private fun progress(completed: Boolean) = ReviewProgress("fixture_module", 1, completed, if (completed) 6 else 0)
    private suspend fun request(receipt: JSONObject, action: String) {
        receipt.put("hostAction", action); save(receipt)
        val acknowledgement = File(app.filesDir, "v1-recovery-host-ack.json")
        withTimeout(30_000) {
            while (true) {
                val ack = runCatching { JSONObject(acknowledgement.readText()) }.getOrNull()
                if (ack != null && ack.optString("namespace") == namespace && ack.optString("action") == action) break
                delay(50)
            }
        }
        receipt.put("hostActionAcknowledged", action); save(receipt)
    }
    private fun observed(receipt: JSONObject, flag: String) { receipt.getJSONObject("observations").put(flag, true); save(receipt) }
    private fun read() = JSONObject(marker.readText()).also { assertEquals(namespace, it.getString("namespace")) }
    private fun save(value: JSONObject) {
        val atomic = AtomicFile(marker); val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
