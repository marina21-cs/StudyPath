package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class TextPackageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = File(temporary.root, "offline-text-packages")
    private fun store() = TextPackageStore(root())
    private fun read(store: TextPackageStore, uid: String = "learner_1", version: Int = 1) =
        store.read(uid, "fixture_v1", "fixture_module", version)

    @Test fun downloadedBytesRemainPendingAfterRestartUntilExplicitAcknowledgement() {
        val verified = TextPackageFixture.verified()
        val staged = store().stage("learner_1", verified)
        assertFalse(staged.ready); assertEquals(TextPackageState.PENDING, staged.state); assertNull(staged.confirmedAt)
        assertTrue(staged.downloadedAt > 0)
        val restarted = requireNotNull(read(store()))
        assertFalse(restarted.ready); assertEquals(staged.downloadedAt, restarted.downloadedAt)
        val confirmed = store().confirm("learner_1", verified, staged.downloadedAt + 100)
        assertTrue(confirmed.ready); assertEquals(staged.downloadedAt + 100, confirmed.confirmedAt)
        assertTrue(requireNotNull(read(store())).ready)
    }

    @Test fun retriesKeepDownloadIdentityAndNeverDowngradeAReadyVersion() {
        val store = store(); val verified = TextPackageFixture.verified()
        val staged = store.stage("learner_1", verified)
        assertEquals(staged.downloadedAt, store.stage("learner_1", verified).downloadedAt)
        store.confirm("learner_1", verified, staged.downloadedAt + 100)
        val retry = store.stage("learner_1", verified)
        assertTrue(retry.ready); assertEquals(staged.downloadedAt, retry.downloadedAt)
        assertEquals(staged.downloadedAt + 100, retry.confirmedAt)
    }

    @Test fun confirmingMissingOrCorruptBytesCannotPromoteThem() {
        val store = store(); val verified = TextPackageFixture.verified()
        assertThrows(IllegalArgumentException::class.java) { store.confirm("learner_1", verified, 10) }
        val staged = store.stage("learner_1", verified)
        val file = root().walkTopDown().single { it.isFile && it.extension == "json" }
        file.writeText(file.readText().replace("three.", "WRONG."))
        assertNull(read(store))
        assertTrue(store.list("learner_1").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.confirm("learner_1", verified, staged.downloadedAt + 1) }
    }

    @Test fun unfinishedTemporaryWritesDoNotHideTheOldReadyVersion() {
        val store = store(); val first = TextPackageFixture.verified(); val second = TextPackageFixture.verified(2)
        val staged = store.stage("learner_1", first); store.confirm("learner_1", first, staged.downloadedAt + 1)
        val pending = store.stage("learner_1", second)
        val directory = root().walkTopDown().first { it.isFile }.parentFile
        File(directory, "interrupted.tmp").writeText("{partial")
        assertTrue(requireNotNull(read(store())).ready)
        assertFalse(requireNotNull(read(store(), version = 2)).ready)
        assertEquals(pending.downloadedAt, read(store(), version = 2)?.downloadedAt)
        assertEquals(setOf(1, 2), store.list("learner_1").map { it.key.version }.toSet())
    }

    @Test fun corruptDownloadCanBeRestagedWithItsOriginalManagedReceiptTimestamp() {
        val store = store(); val verified = TextPackageFixture.verified()
        val first = store.stage("learner_1", verified, 123_000)
        val file = root().walkTopDown().single { it.isFile && it.extension == "json" }
        file.writeText("{interrupted")
        assertNull(read(store))
        val retry = store.stage("learner_1", verified, first.downloadedAt)
        assertEquals(123_000L, retry.downloadedAt); assertFalse(retry.ready)
        assertTrue(store.confirm("learner_1", verified, 123_100).ready)
        assertThrows(IllegalArgumentException::class.java) { store.stage("learner_2", verified, 0) }
    }

    @Test fun selectedRemovalPreservesOtherVersionsOtherUidsAndUnrelatedQueuedWork() {
        val store = store(); val first = TextPackageFixture.verified(); val second = TextPackageFixture.verified(2)
        val queue = File(temporary.root, "pending-attempts.json").apply { writeText("retained") }
        store.stage("learner_1", first); store.stage("learner_1", second); store.stage("learner_2", first)
        store.remove("learner_1", first.key)
        assertNull(read(store)); assertNotNull(read(store, version = 2)); assertNotNull(read(store, uid = "learner_2"))
        assertEquals("retained", queue.readText())
        store.remove("learner_1", first.key)
        assertEquals(listOf(2), store.list("learner_1").map { it.key.version })
    }

    @Test fun unsafePathsAndSymlinkEscapesAreRejected() {
        val store = store(); val verified = TextPackageFixture.verified()
        for (uid in listOf("../other", "/tmp/other", "", "a/b")) {
            assertThrows(IllegalArgumentException::class.java) { store.stage(uid, verified) }
        }
        assertThrows(IllegalArgumentException::class.java) { store.read("learner_1", "../other", "fixture_module", 1) }
        assertThrows(IllegalArgumentException::class.java) { store.read("learner_1", "fixture_v1", "fixture_module", 0) }
        root().mkdirs()
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(root(), "learner_1").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.stage("learner_1", verified) }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test fun storageFailureIsReportedWithoutClaimingReady() {
        root().writeText("Not a directory")
        assertThrows(Exception::class.java) { store().stage("learner_1", TextPackageFixture.verified()) }
        assertEquals("Not a directory", root().readText())
    }

    @Test fun managedReceiptTimestampCeilingAppliesToStageConfirmationAndDiskReads() {
        val store = store(); val verified = TextPackageFixture.verified(); val max = 4_102_444_800_000L
        assertThrows(IllegalArgumentException::class.java) { store.stage("learner_1", verified, max + 1) }
        val staged = store.stage("learner_1", verified, max)
        assertThrows(IllegalArgumentException::class.java) { store.confirm("learner_1", verified, max + 1) }
        assertTrue(store.confirm("learner_1", verified, max).ready)
        val file = root().walkTopDown().single { it.isFile && it.extension == "json" }
        val fields = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
        fields.addProperty("confirmedAt", max + 1); file.writeText(fields.toString())
        assertNull(read(store))
        fields.addProperty("confirmedAt", max); fields.addProperty("downloadedAt", max + 1); file.writeText(fields.toString())
        assertNull(read(store))
        assertEquals(max, staged.downloadedAt)
    }

    @Test fun serverAcknowledgementMayPrecedeLocalDownloadTimeWhenDeviceClockIsAhead() {
        val store = store(); val verified = TextPackageFixture.verified()
        val staged = store.stage("learner_1", verified, 2_000)
        val confirmed = store.confirm("learner_1", verified, 1_000)
        assertTrue(confirmed.ready)
        assertEquals(2_000L, confirmed.downloadedAt); assertEquals(1_000L, confirmed.confirmedAt)
        val restored = requireNotNull(read(store()))
        assertTrue(restored.ready)
        assertEquals(staged.downloadedAt, restored.downloadedAt); assertEquals(1_000L, restored.confirmedAt)
    }
}
