package com.studypath.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Three process-separated stages. PDFs transfer by actual HTTP through an explicit local test mirror. */
@RunWith(AndroidJUnit4::class)
class MockCurriculumDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val namespace get() = requireNotNull(arguments.getString("mockNamespace"))
    private val root get() = File(app.filesDir, "mock-curriculum-${namespace.takeLast(8)}")
    private val marker get() = File(app.filesDir, "mock-curriculum-device.json")
    private val expected get() = JSONObject(File(app.filesDir, "mock-curriculum-fixture.json").readText()).also {
        assertEquals(namespace, it.getString("namespace")); assertTrue(it.getBoolean("syntheticOnly"))
    }
    private val moduleIds = listOf("mock_programming", "mock_data_structures", "mock_databases")
    private val firebase by lazy {
        FirebaseApp.initializeApp(app, FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setDatabaseUrl("https://$namespace.firebaseio.com").build(), "MockCurriculum-${namespace.takeLast(8)}")
    }
    private val auth by lazy { FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) } }
    private val database by lazy { FirebaseDatabase.getInstance(firebase).apply {
        useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000); setPersistenceEnabled(true)
    } }
    private val repository by lazy {
        val port = requireNotNull(arguments.getString("mirrorPort")).toInt()
        require(port in 1..65535)
        FirebaseStudyRepository(auth, database, app.getSharedPreferences("mock-curriculum-${namespace.takeLast(8)}", 0),
            TextPackageStore(File(root, "packages")), LessonRunStore(File(root, "runs")),
            packageAssetDownloader = MockPackageAssets.downloader("https://mock-assets.example.org".toHttpUrl(),
                "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:$port".toHttpUrl()),
            packageDownloadDirectory = File(root, "downloads"), operationDirectory = File(root, "operations"),
            reminderChanged = { _, _, _ -> })
    }

    @Before fun isolatedOnly() {
        assertTrue(BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue(namespace.matches(Regex("demo-studypath-mock-[a-f0-9]{8}")))
        assertTrue(arguments.getString("class")?.startsWith("com.studypath.app.MockCurriculumDeviceTest#") == true)
    }

    @Test fun downloadThreePublishedModulesAndCompleteFirst() = runBlocking<Unit> {
        withTimeout(180_000) {
            assertFalse("Use a fresh read-only Test AVD; do not replace prior evidence", marker.exists())
            assertNull(repository.boundUid())
            val user = repository.register("mock_curriculum_${UUID.randomUUID()}@studypath.invalid", "Mock-curriculum-42", "MOCK local learner")
            val receipt = JSONObject().put("namespace", namespace).put("uid", user.uid).put("syntheticOnly", true)
                .put("observations", JSONObject()).put("attempts", JSONObject()).put("stage1Pid", Process.myPid())
            save(receipt)
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences("mock_college", "mock_computing_v1", setOf("computing"), null, true))
            val summaries = repository.moduleLibrary().first { it.modules.size == 3 }.modules
            for (id in moduleIds) {
                val retained = repository.downloadTextPackage(summaries.single { it.moduleId == id })
                assertTrue(retained.ready); assertNotNull(retained.confirmedAt)
                assertEquals(expected.getJSONObject("packages").getJSONObject(id).getString("packageSha256"), retained.verified.summary.packageSha256)
                assertNotNull(repository.openPackageAsset(key(id), "companion_pdf"))
            }
            observed(receipt, "threeCompleteRepositoryDownloadsRequireRuleAcknowledgements")
            val content = module(moduleIds[0])
            val run = complete(content, wrongFirst = true)
            repository.saveLessonRun(content, run); repository.submitLessonRun(content, run)
            assertNotNull(repository.lessonRunConfirmedAt(content, run))
            receipt.getJSONObject("attempts").put(run.runId, JSONObject(LessonEngine.attempt(content, run)))
            receipt.put("firstRunId", run.runId)
            observed(receipt, "originalInstructionAndThreeInteractionFormatsCompleteWithCorrectiveFeedback")
        }
    }

    @Test fun restoreAllPdfsAndCompleteRemainingLessonsOffline() = runBlocking<Unit> {
        withTimeout(150_000) {
            val receipt = read(); assertNotEquals(receipt.getInt("stage1Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { !it }
            for (id in moduleIds) renderPdf(id, receipt)
            val first = module(moduleIds[0]); assertTrue(requireNotNull(repository.lessonRun(first, "lesson_1")).completed)
            assertEquals(receipt.getString("firstRunId"), repository.lessonRun(first, "lesson_1")?.runId)
            for (id in moduleIds.drop(1)) {
                val content = module(id)
                val run = complete(content)
                repository.saveLessonRun(content, run)
                receipt.getJSONObject("attempts").put(run.runId, JSONObject(LessonEngine.attempt(content, run))); save(receipt)
                val submission = launch { repository.submitLessonRun(content, run) }
                repository.operationStates().first { rows -> rows.any { it.relativePath == "set/lessonAttempts/${run.runId}" && it.state != OperationSyncState.CONFIRMED } }
                assertTrue(submission.isActive); submission.cancelAndJoin()
                assertNull(repository.lessonRunConfirmedAt(content, run))
            }
            receipt.put("stage2Pid", Process.myPid())
            observed(receipt, "offlineProcessRestartRestoresThreeNativePdfReadsAndCompletedLesson")
            observed(receipt, "twoOfflineOriginalCompletionsRetainPendingExactResults")
        }
    }

    @Test fun reconnectAndConfirmExactlyThreeOriginalResults() = runBlocking<Unit> {
        withTimeout(120_000) {
            val receipt = read(); assertNotEquals(receipt.getInt("stage2Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { it }
            for (id in moduleIds) {
                val content = module(id)
                val run = requireNotNull(repository.lessonRun(content, "lesson_1"))
                assertTrue(run.completed)
                assertEquals(receipt.getJSONObject("attempts").getJSONObject(run.runId).toString(), JSONObject(LessonEngine.attempt(content, run)).toString())
                repository.syncPendingLessonRuns(content)
                assertNotNull(repository.lessonRunConfirmedAt(content, run))
            }
            receipt.put("stage3Pid", Process.myPid())
            observed(receipt, "reconnectConfirmsExactlyThreeVersionBoundUnchangedResults")
        }
    }

    @Test fun cleanupOwnedSyntheticAuth() = runBlocking<Unit> {
        withTimeout(30_000) {
            val receipt = read(); assertEquals(receipt.getString("uid"), auth.currentUser?.uid)
            requireNotNull(auth.currentUser).delete().await(); auth.signOut(); database.goOffline()
            observed(receipt, "ownedSyntheticAuthDeleted")
        }
    }

    private fun key(id: String) = TextPackageKey("mock_computing_v1", id, 1)
    private fun module(id: String) = InteractiveLessonParser.parse(requireNotNull(repository.openTextPackage(key(id))).verified)
    private fun complete(content: InteractiveModule, wrongFirst: Boolean = false): LessonRun {
        var now = System.currentTimeMillis()
        var run = LessonEngine.start(content, "lesson_1", now++, "mock_${content.summary.moduleId}_${UUID.randomUUID()}")
        while (!run.completed) {
            val step = requireNotNull(LessonEngine.currentStep(content, run))
            step.questionId?.let { id ->
                val question = content.questions.getValue(id)
                if (wrongFirst && id == "guided") {
                    run = LessonEngine.select(content, run, step.id, listOf("read"), now++)
                    run = LessonEngine.check(content, run, step.id, now++)
                    assertEquals(LessonAnswerStatus.INCORRECT, run.answers.getValue(id).status)
                    assertTrue(question.options.single { it.id == "read" }.feedback.isNotBlank())
                    run = LessonEngine.retry(content, run, step.id, now++)
                }
                val response = if (question.format == LessonQuestionFormat.BOUNDED_RESPONSE) listOf(requireNotNull(question.target).toInt().toString()) else question.answer
                run = LessonEngine.select(content, run, step.id, response, now++)
                run = LessonEngine.check(content, run, step.id, now++)
                assertEquals(LessonAnswerStatus.CORRECT, run.answers.getValue(id).status)
            }
            run = LessonEngine.continueStep(content, run, step.id, now++)
        }
        assertEquals(3, run.answers.size)
        return run
    }
    private fun renderPdf(id: String, receipt: JSONObject) {
        val asset = requireNotNull(repository.openPackageAsset(key(id), "companion_pdf"))
        assertEquals(expected.getJSONObject("packages").getJSONObject(id).getString("pdfSha256"), sha(asset.readBytes()))
        NativePdfAssetValidator.validate(asset)
        ParcelFileDescriptor.open(asset, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertTrue("Original PDF must render authored ink", pixels.count { it != Color.WHITE } > 100)
                        File(app.filesDir, "mock-$id-pdf.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
        receipt.put("pdfReadCount", receipt.optInt("pdfReadCount") + 1); save(receipt)
    }
    private fun observed(receipt: JSONObject, flag: String) { receipt.getJSONObject("observations").put(flag, true); save(receipt) }
    private fun read() = JSONObject(marker.readText()).also { assertEquals(namespace, it.getString("namespace")) }
    private fun save(value: JSONObject) {
        val atomic = AtomicFile(marker); val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
