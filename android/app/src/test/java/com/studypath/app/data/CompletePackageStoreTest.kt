package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CompletePackageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun verified(version: Int = 1): VerifiedTextPackage = CompletePackageFixture.candidate(version).let {
        TextPackageVerifier.verify(it.first, it.second, it.third)
    }

    @Test fun metadataAloneCannotStageOrConfirmACompleteAssetPackage() {
        val store = TextPackageStore(File(temporary.root, "packages"))
        assertThrows(IllegalArgumentException::class.java) { store.stage("learner", verified()) }
        assertTrue(store.list("learner").isEmpty())
    }

    private fun input(bytes: ByteArray = CompletePackageFixture.pdf) = temporary.newFile().apply { writeBytes(bytes) }
    private fun store(root: File = File(temporary.root, "packages"), available: Long = Long.MAX_VALUE) =
        TextPackageStore(root, validatePdf = { /* Native PDF parsing is a separate device gate. */ }, availableBytes = { available })
    private fun read(store: TextPackageStore, version: Int = 1) = store.read("learner", "fixture_v1", "fixture_module", version)

    @Test fun everyAssetIsPrivateVerifiedAndPendingAcrossRestartUntilExplicitAcknowledgement() {
        val root = File(temporary.root, "packages"); val store = store(root); val verified = verified()
        val staged = store.stage("learner", verified, 123_000, mapOf("source_pdf" to input()))
        assertFalse(staged.ready); assertNull(store.assetFile("learner", verified.key, "source_pdf"))
        assertFalse(requireNotNull(read(store(root))).ready)
        val ready = store.confirm("learner", verified, 123_100)
        assertTrue(ready.ready)
        val retainedFile = requireNotNull(store.assetFile("learner", verified.key, "source_pdf"))
        assertTrue(retainedFile.canonicalPath.startsWith(root.canonicalPath))
        assertArrayEquals(CompletePackageFixture.pdf, retainedFile.readBytes())
        assertNull(store.assetFile("foreign", verified.key, "source_pdf"))
        assertEquals(123_000L, store.stage("learner", verified, 999_999).downloadedAt)
        assertTrue(requireNotNull(read(store(root))).ready)
    }

    @Test fun missingCorruptTruncatedOrUnexpectedAssetsNeverPublishMetadata() {
        val store = store(); val verified = verified()
        for (files in listOf(emptyMap(), mapOf("wrong" to input()), mapOf("source_pdf" to input("%PDF-1.4\n%%EOF".toByteArray())),
            mapOf("source_pdf" to input(CompletePackageFixture.pdf + 0)))) {
            assertThrows(IllegalArgumentException::class.java) { store.stage("learner", verified, 100, files) }
            assertNull(read(store))
        }
    }

    @Test fun corruptOrMissingRetainedAssetInvalidatesReadyAndCanBeRepairedWithoutLosingOlderVersion() {
        val store = store(); val first = verified(); val second = verified(2)
        store.stage("learner", first, 100, mapOf("source_pdf" to input())); store.confirm("learner", first, 101)
        store.stage("learner", second, 200, mapOf("source_pdf" to input())); store.confirm("learner", second, 201)
        val selected = requireNotNull(store.assetFile("learner", second.key, "source_pdf"))
        selected.writeBytes(CompletePackageFixture.pdf.reversedArray())
        assertNull(read(store, 2)); assertNull(store.assetFile("learner", second.key, "source_pdf"))
        assertTrue(requireNotNull(read(store)).ready)
        assertFalse(store.stage("learner", second, 200, mapOf("source_pdf" to input())).ready)
        store.confirm("learner", second, 202)
        requireNotNull(store.assetFile("learner", second.key, "source_pdf")).delete()
        assertNull(read(store, 2)); assertTrue(requireNotNull(read(store)).ready)
    }

    @Test fun interruptedUpdateLowStorageAndNativeParserRejectionKeepOldCompleteVersion() {
        val root = File(temporary.root, "packages"); val store = store(root); val first = verified(); val second = verified(2)
        store.stage("learner", first, 100, mapOf("source_pdf" to input())); store.confirm("learner", first, 101)
        assertThrows(IllegalArgumentException::class.java) { store(root, available = 1).stage("learner", second, 200, mapOf("source_pdf" to input())) }
        val rejecting = TextPackageStore(root, validatePdf = { throw IllegalArgumentException("Native parser rejected PDF") })
        assertThrows(IllegalArgumentException::class.java) { rejecting.stage("learner", second, 200, mapOf("source_pdf" to input())) }
        val directory = root.walkTopDown().first { it.name == "fixture_module" }
        File(directory, ".v2.interrupted.assets.tmp").mkdir()
        assertTrue(requireNotNull(read(store)).ready); assertNull(read(store, 2))
        assertEquals(listOf(1), store.list("learner").map { it.key.version })
    }

    @Test fun selectedRemovalAndAssetSymlinkGuardPreserveOtherVersionsUsersAndQueue() {
        val root = File(temporary.root, "packages"); val store = store(root); val first = verified(); val second = verified(2)
        val queue = temporary.newFile().apply { writeText("pending learner work") }
        store.stage("learner", first, 100, mapOf("source_pdf" to input())); store.confirm("learner", first, 101)
        store.stage("learner", second, 200, mapOf("source_pdf" to input())); store.confirm("learner", second, 201)
        store.stage("foreign", first, 100, mapOf("source_pdf" to input())); store.confirm("foreign", first, 101)
        store.remove("learner", first.key)
        assertNull(read(store)); assertTrue(requireNotNull(read(store, 2)).ready)
        assertNotNull(store.assetFile("foreign", first.key, "source_pdf")); assertEquals("pending learner work", queue.readText())
        val selected = requireNotNull(store.assetFile("learner", second.key, "source_pdf")); selected.delete()
        Files.createSymbolicLink(selected.toPath(), input().toPath())
        assertNull(read(store, 2)); assertNull(store.assetFile("learner", second.key, "source_pdf"))
    }
}
