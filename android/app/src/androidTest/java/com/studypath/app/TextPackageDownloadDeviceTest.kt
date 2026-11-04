package com.studypath.app

import android.os.Process
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.studypath.app.data.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Host-coordinated B10 text-integrity gate, using fabricated approval data in a unique local
 * namespace. It proves repository/file recovery only; interactive lessons, PDF assets, actual
 * teacher approval and screen acceptance are separate gates. Never targets the normal workspace.
 */
@RunWith(AndroidJUnit4::class)
class TextPackageDownloadDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val namespace get() = InstrumentationRegistry.getArguments().getString("packageNamespace")!!.also {
        require(it.matches(Regex("demo-studypath-b10-text-[a-f0-9]{8}")))
    }
    private val firebase by lazy {
        FirebaseApp.initializeApp(app, FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setDatabaseUrl("https://$namespace.firebaseio.com").build(), "B10TextPackageDevice")
    }
    private val auth by lazy {
        FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
    }
    private val database by lazy {
        FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000)
            setPersistenceEnabled(true)
        }
    }
    private val packageStore by lazy { TextPackageStore(File(app.filesDir, "b10-text-package-test-store")) }
    private val repository by lazy {
        FirebaseStudyRepository(auth, database, app.getSharedPreferences("b10-text-package-workspace", 0),
            packageStore) { _, _, _ -> }
    }
    private val fixtureFile get() = File(app.filesDir, "b10-text-package-fixture.json")
    private val expected get() = JSONObject(File(app.filesDir, "b10-text-package-expected.json").readText()).also {
        assertEquals(namespace, it.getString("namespace"))
    }.getJSONObject("packages")
    private fun key(version: Int) = TextPackageKey("fixture_v1", "fixture_module", version)
    private fun read() = JSONObject(fixtureFile.readText()).also { assertEquals(namespace, it.getString("namespace")) }
    private fun save(value: JSONObject) {
        val file = AtomicFile(fixtureFile)
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun observed(value: JSONObject, flag: String) { value.getJSONObject("observations").put(flag, true); save(value) }
    private fun stage(value: JSONObject, number: Int) { value.put("stage${number}Pid", Process.myPid()); save(value) }

    @Before fun selectedLocalStageOnly() {
        assumeTrue("Use android/test-text-packages.py for transport/process coordination",
            InstrumentationRegistry.getArguments().getString("class")?.startsWith(
                "com.studypath.app.TextPackageDownloadDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        namespace
    }

    @Test fun downloadAndQueueOffline() = runBlocking<Unit> {
        withTimeout(120_000) {
            assertFalse("Do not overwrite a previous test workspace", fixtureFile.exists())
            assertNull(repository.boundUid())
            val email = "text_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            val user = repository.register(email, PASSWORD, "Synthetic text package learner")
            val fixture = JSONObject().put("schemaVersion", 1).put("namespace", namespace)
                .put("uid", user.uid).put("email", email).put("observations", JSONObject())
                .put("downloads", JSONObject())
            save(fixture)
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences("fixture_level", "fixture_v1",
                setOf("fixture_subject"), "fixture_class", true))
            val summaries = repository.moduleLibrary().first { it.modules.size == 4 }.modules
            assertTrue(repository.downloadedTextPackages().isEmpty())
            for (version in listOf(1, 2)) {
                val stored = repository.downloadTextPackage(summaries.single { it.version == version })
                assertPackage(stored, version)
                assertTrue("Only a durable acknowledgement may mark a download ready", stored.ready)
                assertNotNull(stored.confirmedAt)
                fixture.getJSONObject("downloads").put("v$version", JSONObject()
                    .put("downloadedAt", stored.downloadedAt).put("confirmedAt", stored.confirmedAt))
                save(fixture)
            }
            assertEquals(setOf(key(1), key(2)), repository.downloadedTextPackages().map { it.key }.toSet())
            observed(fixture, "twoVersionsDownloadedAndServerConfirmed")
            assertTrue("Altered content must fail closed", runCatching {
                repository.downloadTextPackage(summaries.single { it.version == 3 })
            }.isFailure)
            assertNull(repository.openTextPackage(key(3)))
            assertEquals(setOf(key(1), key(2)), repository.downloadedTextPackages().map { it.key }.toSet())
            observed(fixture, "corruptTextRejectedWithoutReadyPackage")

            // Reconstruct the real interruption boundary after a receipt acknowledgement but
            // before the final local READY write. There is no test-only production shortcut:
            // the ordinary verifier/store retain bytes and the named SDK writes the receipt.
            // This does not claim a deterministic process kill between two production lines.
            val original = expected.getJSONObject("v4")
            val verified = TextPackageVerifier.verify(summaries.single { it.version == 4 },
                original.getString("manifestJson"), original.getString("textJson"))
            // The device clock can be ahead of the service clock. Both timestamps retain their
            // own meaning; acknowledgement must not reject a valid cross-clock ordering.
            val pending = packageStore.stage(user.uid, verified, System.currentTimeMillis() + 60 * 60_000L)
            assertFalse(pending.ready)
            assertNull(pending.confirmedAt)
            val pendingReceipt = database.getReference("users").child(user.uid)
                .child("offlinePackages/fixture_v1/fixture_module/v4")
            pendingReceipt.setValue(mapOf("schemaVersion" to 1, "moduleId" to "fixture_module", "version" to 4,
                    "curriculumVersion" to "fixture_v1", "packageSha256" to verified.summary.packageSha256,
                    "textBytes" to verified.summary.textBytes, "downloadedAt" to pending.downloadedAt,
                    "confirmedAt" to ServerValue.TIMESTAMP)).await()
            val serverConfirmedAt = requireNotNull(pendingReceipt.get().await().child("confirmedAt")
                .getValue(Long::class.java))
            assertTrue(pending.downloadedAt > serverConfirmedAt)
            fixture.getJSONObject("downloads").put("v4", JSONObject()
                .put("downloadedAt", pending.downloadedAt).put("confirmedAt", serverConfirmedAt))
            save(fixture)
            assertNull(repository.openTextPackage(key(4)))
            assertEquals(setOf(key(1), key(2)), repository.downloadedTextPackages().map { it.key }.toSet())
            observed(fixture, "acknowledgedReceiptWithPendingLocalFinalizationRemainsUnavailable")

            repository.saveScore(score(0.0))
            repository.saveProgress(progress(false))
            repository.scores().first { rows -> rows.singleOrNull()?.earnedPoints == 0.0 }
            repository.reviewProgress("fixture_module", 2).first { it?.completed == false }
            fixture.put("readyForPartition", true); save(fixture)
            repository.connection().first { !it }
            for (version in listOf(1, 2)) assertPackage(requireNotNull(repository.openTextPackage(key(version))), version)
            observed(fixture, "completeVersionedTextReadableDuringActualPartition")
            val writes = listOf(launch { repository.saveScore(score(12.0)) },
                launch { repository.saveProgress(progress(true)) })
            try {
                assertQueued()
                assertTrue("Offline local echoes must retain unacknowledged Tasks", writes.all { it.isActive && !it.isCompleted })
                assertFalse(repository.connection().first())
            } finally { writes.forEach { it.cancelAndJoin() } }
            observed(fixture, "unrelatedScoreAndProgressQueuedOffline")
            stage(fixture, 1)
        }
    }

    @Test fun recoverLocalPackages() = runBlocking<Unit> {
        withTimeout(60_000) {
            val fixture = read()
            assertNotEquals(fixture.getInt("stage1Pid"), Process.myPid())
            assertEquals(fixture.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { !it }
            val downloaded = repository.downloadedTextPackages()
            assertEquals(setOf(key(1), key(2)), downloaded.map { it.key }.toSet())
            for (version in listOf(1, 2)) {
                val stored = requireNotNull(repository.openTextPackage(key(version)))
                assertPackage(stored, version)
                val recorded = fixture.getJSONObject("downloads").getJSONObject("v$version")
                assertEquals(recorded.getLong("downloadedAt"), stored.downloadedAt)
                assertEquals(recorded.getLong("confirmedAt"), requireNotNull(stored.confirmedAt))
                assertTrue(stored.ready)
            }
            assertNull(repository.openTextPackage(key(3)))
            observed(fixture, "exactTextAndDurableReceiptsRecoverAfterOfflineProcessDeath")
            val pending = requireNotNull(packageStore.read(fixture.getString("uid"), "fixture_v1", "fixture_module", 4))
            assertPackage(pending, 4)
            assertEquals(fixture.getJSONObject("downloads").getJSONObject("v4").getLong("downloadedAt"), pending.downloadedAt)
            assertFalse(pending.ready)
            assertNull(pending.confirmedAt)
            assertNull(repository.openTextPackage(key(4)))
            assertEquals(setOf(key(1), key(2)), repository.downloadedTextPackages().map { it.key }.toSet())
            observed(fixture, "pendingFinalizationBytesRecoverOfflineWithoutReadyClaim")
            assertQueued()
            assertFalse(repository.connection().first())
            observed(fixture, "pendingScoreAndProgressSurviveOfflineProcessDeath")
            stage(fixture, 2)
        }
    }

    @Test fun removeAndCheckSession() = runBlocking<Unit> {
        withTimeout(90_000) {
            val fixture = read()
            assertNotEquals(fixture.getInt("stage2Pid"), Process.myPid())
            assertEquals(fixture.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { it }
            assertQueued()
            val summary = repository.moduleLibrary().first { it.modules.size == 4 }.modules.single { it.version == 4 }
            val retried = repository.downloadTextPackage(summary)
            assertPackage(retried, 4)
            assertTrue(retried.ready)
            assertEquals(fixture.getJSONObject("downloads").getJSONObject("v4").getLong("downloadedAt"), retried.downloadedAt)
            val originalServerConfirmation = fixture.getJSONObject("downloads").getJSONObject("v4").getLong("confirmedAt")
            assertTrue(requireNotNull(retried.confirmedAt) >= originalServerConfirmation)
            fixture.getJSONObject("downloads").getJSONObject("v4").put("confirmedAt", retried.confirmedAt)
            save(fixture)
            assertTrue(retried.downloadedAt > requireNotNull(retried.confirmedAt))
            observed(fixture, "independentLocalAndServerClocksAccepted")
            observed(fixture, "retryFinalizesOriginalAcknowledgedReceiptWithoutDuplicatingVersion")
            repository.removeTextPackage(key(1))
            assertNull(repository.openTextPackage(key(1)))
            assertPackage(requireNotNull(repository.openTextPackage(key(2))), 2)
            assertPackage(requireNotNull(repository.openTextPackage(key(4))), 4)
            assertEquals(setOf(key(2), key(4)), repository.downloadedTextPackages().map { it.key }.toSet())
            assertQueued()
            observed(fixture, "removingOneVersionPreservesOtherVersionAndUnrelatedQueuedRecords")
            repository.logout()
            assertTrue(runCatching { repository.openTextPackage(key(2)) }.isFailure)
            assertTrue(runCatching { repository.downloadedTextPackages() }.isFailure)
            observed(fixture, "signedOutLocalPackageOpenAndListAreDenied")
            assertEquals(fixture.getString("uid"), repository.login(fixture.getString("email"), PASSWORD).uid)
            assertPackage(requireNotNull(repository.openTextPackage(key(2))), 2)
            assertPackage(requireNotNull(repository.openTextPackage(key(4))), 4)
            assertEquals(fixture.getJSONObject("downloads").getJSONObject("v4").getLong("confirmedAt"),
                requireNotNull(repository.openTextPackage(key(4))?.confirmedAt))
            assertNull(repository.openTextPackage(key(1)))
            assertEquals(setOf(key(2), key(4)), repository.downloadedTextPackages().map { it.key }.toSet())
            assertQueued()
            observed(fixture, "sameUidRestoresRetainedPackageAndQueuedRecords")
            stage(fixture, 3)
        }
    }

    private fun assertPackage(value: StoredTextPackage, version: Int) {
        val original = expected.getJSONObject("v$version")
        assertEquals(key(version), value.key)
        assertEquals(original.getString("manifestJson"), value.verified.manifestJson)
        assertEquals(original.getString("textJson"), value.verified.textJson)
        assertEquals(original.getJSONObject("summary").getString("packageSha256"), value.verified.summary.packageSha256)
        assertEquals(original.getJSONObject("summary").getLong("textBytes"), value.verified.summary.textBytes)
        assertEquals(version, value.verified.payload.get("version").asInt)
        assertTrue(value.downloadedAt > 0)
    }

    private suspend fun assertQueued() {
        val rows = repository.scores().first { it.singleOrNull()?.earnedPoints == 12.0 }
        assertEquals(score(12.0), rows.single().copy(updatedAt = null))
        assertEquals(progress(true), repository.reviewProgress("fixture_module", 2)
            .first { it?.completed == true }?.copy(updatedAt = null))
    }

    private fun score(earned: Double) = ScoreRecord("b10_score", "fixture_course", "quiz_1", "quizzes",
        "SCORED", 20.0, earned, planVersion = "fixture_plan_v1")
    private fun progress(completed: Boolean) = ReviewProgress("fixture_module", 2, completed,
        if (completed) 1 else 0)

    companion object { private const val PASSWORD = "SyntheticTextOnly123!" }
}
