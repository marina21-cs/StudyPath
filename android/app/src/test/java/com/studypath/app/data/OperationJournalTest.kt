package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OperationJournalTest {
    private data class Fixture(val root: java.io.File, val gate: SessionGate, val token: SessionToken, val store: OperationJournal)
    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("operation-journal").toFile()
        val gate = SessionGate({ "alice" }, { "alice" })
        return Fixture(root, gate, gate.activate(), OperationJournal(root, gate))
    }
    private val payload = """{"enabled":true,"dueAt":1793318400000,"label":"PRIVATE_SENTINEL"}"""

    @Test fun enqueuePersistsOnlyIdentityAndFingerprintInUidOwnedAtomicFile() {
        val f = fixture()
        val entry = f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
        assertEquals(OperationSyncState.QUEUED, entry.state)
        assertEquals("alice", entry.uid)
        assertTrue(entry.payloadSha256.matches(Regex("[a-f0-9]{64}")))
        val bytes = f.root.resolve("alice/edit_1.json").readText()
        assertFalse(bytes.contains("PRIVATE_SENTINEL"))
        assertFalse(bytes.contains("dueAt"))
        assertEquals(listOf("edit_1"), f.store.records(f.token).map { it.operationId })
        assertTrue(f.root.resolve("alice").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun reopeningQueuedOrAwaitingAckReportsUnknownRatherThanConfirmed() {
        for (awaiting in listOf(false, true)) {
            val f = fixture()
            f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
            if (awaiting) f.store.awaitingAck(f.token, "edit_1")
            val reopened = OperationJournal(f.root, f.gate)
            assertEquals(OperationSyncState.UNKNOWN_AFTER_RESTART, reopened.state(f.token, "edit_1"))
            assertNull(reopened.get(f.token, "edit_1")!!.acknowledgedAt)
        }
    }

    @Test fun actualMatchingCallbackRetainsObservedAcknowledgementAndOptionalIndependentServerTimestamp() {
        val f = fixture()
        f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
        val callback = f.store.awaitingAck(f.token, "edit_1")
        assertEquals(OperationSyncState.AWAITING_ACK, f.store.state(f.token, "edit_1"))
        assertTrue(f.store.result(callback, OperationSyncState.CONFIRMED, acknowledgedAt = 200, verifiedServerTimestamp = 190))
        val restored = OperationJournal(f.root, f.gate).get(f.token, "edit_1")!!
        assertEquals(OperationSyncState.CONFIRMED, restored.state)
        assertEquals(200L, restored.acknowledgedAt)
        assertEquals(190L, restored.verifiedServerTimestamp)
        assertFalse(f.store.result(callback, OperationSyncState.REJECTED))
    }

    @Test fun callbackAfterLogoutOrSameUidReactivationCannotChangePendingRecord() {
        val f = fixture()
        f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
        val callback = f.store.awaitingAck(f.token, "edit_1")
        f.gate.invalidate()
        assertFalse(f.store.result(callback, OperationSyncState.CONFIRMED, acknowledgedAt = 200))
        val fresh = f.gate.activate()
        assertFalse(f.store.result(callback, OperationSyncState.CONFIRMED, acknowledgedAt = 200))
        assertEquals(OperationSyncState.UNKNOWN_AFTER_RESTART, f.store.state(fresh, "edit_1"))
        f.store.retry(fresh, "edit_1", "reminders/r1", payload)
        assertEquals(OperationSyncState.QUEUED, f.store.state(fresh, "edit_1"))
    }

    @Test fun foreignUidOrWrongProcessCallbackNeverConfirmsTheOriginalWorkspace() {
        val f = fixture()
        f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
        val callback = f.store.awaitingAck(f.token, "edit_1")
        val reopened = OperationJournal(f.root, f.gate)
        assertFalse(reopened.result(callback, OperationSyncState.CONFIRMED, acknowledgedAt = 200))
        val otherGate = SessionGate({ "bob" }, { "bob" })
        val otherToken = otherGate.activate()
        assertThrows(IllegalStateException::class.java) { f.store.get(otherToken, "edit_1") }
        assertNull(OperationJournal(f.root, otherGate).get(otherToken, "edit_1"))
        assertTrue(f.root.resolve("alice/edit_1.json").exists())
    }

    @Test fun exactRetryRequiresSamePathAndCanonicalPayloadAndNewEditUsesSeparateIdentity() {
        val f = fixture()
        val first = f.store.enqueue(f.token, "edit_1", "reminders/r1", """{"b":2,"a":1}""", 100)
        assertEquals(first, f.store.enqueue(f.token, "edit_1", "reminders/r1", """{"a":1.0,"b":2}""", 999))
        assertThrows(IllegalArgumentException::class.java) { f.store.retry(f.token, "edit_1", "reminders/r1", """{"a":2,"b":2}""") }
        assertThrows(IllegalArgumentException::class.java) { f.store.retry(f.token, "edit_1", "reminders/other", """{"a":1,"b":2}""") }
        f.store.enqueue(f.token, "edit_2", "reminders/r1", """{"a":1,"b":2}""", 101)
        assertEquals(2, f.store.records(f.token).size)
        assertEquals(OperationSyncState.QUEUED, f.store.state(f.token, "edit_1"))
    }

    @Test fun rejectionAndAuthExpirySurviveRestartAndRetryGetsANewCallbackIdentity() {
        for (outcome in listOf(OperationSyncState.REJECTED, OperationSyncState.AUTH_REQUIRED)) {
            val f = fixture()
            f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
            val old = f.store.awaitingAck(f.token, "edit_1")
            assertTrue(f.store.result(old, outcome))
            val reopened = OperationJournal(f.root, f.gate)
            assertEquals(outcome, reopened.state(f.token, "edit_1"))
            reopened.retry(f.token, "edit_1", "reminders/r1", payload)
            val current = reopened.awaitingAck(f.token, "edit_1")
            assertFalse(reopened.result(old, OperationSyncState.CONFIRMED, acknowledgedAt = 200))
            assertTrue(reopened.result(current, OperationSyncState.CONFIRMED, acknowledgedAt = 201))
        }
    }

    @Test fun truncatedUnknownOrMismatchedUidFilesAreRetainedAndBlockReplacement() {
        for (corruption in listOf("{truncated", """{"schemaVersion":99}""")) {
            val f = fixture()
            f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
            val file = f.root.resolve("alice/edit_1.json"); file.writeText(corruption)
            assertThrows(IllegalArgumentException::class.java) { f.store.get(f.token, "edit_1") }
            assertThrows(IllegalArgumentException::class.java) { f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100) }
            assertEquals(corruption, file.readText())
        }
        val f = fixture(); f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
        val file = f.root.resolve("alice/edit_1.json"); val corrupted = file.readText().replace("\"alice\"", "\"bob\""); file.writeText(corrupted)
        assertThrows(IllegalArgumentException::class.java) { f.store.get(f.token, "edit_1") }
        assertEquals(corrupted, file.readText())
    }

    @Test fun traversalSymlinksAndCredentialsCannotEnterJournal() {
        val f = fixture()
        for (path in listOf("../outside", "/reminders/r1", "users/bob/scores/a", "catalog/courses/a", "auth/tokens")) {
            assertThrows(IllegalArgumentException::class.java) { f.store.enqueue(f.token, "edit_1", path, payload, 100) }
        }
        for (secret in listOf("""{"password":"secret"}""", """{"nested":{"idToken":"secret"}}""", """{"authorization":"Bearer secret"}""")) {
            assertThrows(IllegalArgumentException::class.java) { f.store.enqueue(f.token, "edit_1", "reminders/r1", secret, 100) }
        }
        f.root.resolve("alice").mkdirs()
        val target = f.root.resolve("outside.json"); target.writeText("outside")
        Files.createSymbolicLink(f.root.resolve("alice/edit_1.json").toPath(), target.toPath())
        assertThrows(IllegalArgumentException::class.java) { f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100) }
        assertEquals("outside", target.readText())
    }

    @Test fun primitiveDeletionAndOrderedArraysHaveStableButDistinctFingerprints() {
        val f = fixture()
        val deletion = f.store.enqueue(f.token, "delete", "reminders/r1", "null", 100)
        val first = f.store.enqueue(f.token, "array_1", "lessonProgress/r1", "[1,2]", 100)
        val second = f.store.enqueue(f.token, "array_2", "lessonProgress/r1", "[2,1]", 100)
        assertNotEquals(deletion.payloadSha256, first.payloadSha256)
        assertNotEquals(first.payloadSha256, second.payloadSha256)
        assertThrows(IllegalArgumentException::class.java) { f.store.enqueue(f.token, "bad", "reminders/r1", "{\"a\":1,\"a\":2}", 100) }
        assertThrows(IllegalArgumentException::class.java) { f.store.enqueue(f.token, "bad", "reminders/r1", "null,\"extra\":1", 100) }
    }

    @Test fun invalidCallbackStateOrTimestampCannotFabricateReceiptAndConfirmedDoesNotRetry() {
        val f = fixture(); f.store.enqueue(f.token, "edit_1", "reminders/r1", payload, 100)
        val callback = f.store.awaitingAck(f.token, "edit_1")
        assertThrows(IllegalArgumentException::class.java) { f.store.result(callback, OperationSyncState.QUEUED) }
        assertThrows(IllegalArgumentException::class.java) { f.store.result(callback, OperationSyncState.CONFIRMED) }
        assertTrue(f.store.result(callback, OperationSyncState.CONFIRMED, acknowledgedAt = 200))
        assertThrows(IllegalArgumentException::class.java) { f.store.retry(f.token, "edit_1", "reminders/r1", payload) }
    }

    private fun confirm(f: Fixture, id: String, path: String, createdAt: Long) {
        f.store.enqueue(f.token, id, path, payload, createdAt)
        assertTrue(f.store.result(f.store.awaitingAck(f.token, id), OperationSyncState.CONFIRMED, 10_000 + createdAt))
    }

    @Test fun confirmedCompactionFreesCapacityBeyondFiveThousandSuccessfulChanges() {
        val f = fixture()
        confirm(f, "template", "set/reminders/r1", 1)
        val template = f.root.resolve("alice/template.json").readText()
        for (index in 2..5000) {
            val id = "confirmed_$index"
            f.root.resolve("alice/$id.json").writeText(template.replace("\"template\"", "\"$id\"")
                .replace("\"createdAt\":1,", "\"createdAt\":$index,"))
        }
        assertEquals(5000, f.store.records(f.token).size)
        val released = mutableListOf<String>()
        assertEquals(4997, f.store.compactConfirmed(f.token, keepRecent = 3) { released += it.operationId })
        assertEquals(4997, released.size)
        f.store.enqueue(f.token, "next", "set/reminders/r1", payload, 6000)
        assertEquals(4, f.store.records(f.token).size)
        assertEquals(setOf("confirmed_4998", "confirmed_4999", "confirmed_5000", "next"),
            f.store.records(f.token).map { it.operationId }.toSet())
    }

    @Test fun compactionKeepsEveryUnresolvedStateAndItsNewestOverlappingBarrier() {
        val f = fixture()
        f.store.enqueue(f.token, "queued", "set/scores/one", payload, 1)
        f.store.enqueue(f.token, "awaiting", "set/scores/two", payload, 2)
        f.store.awaitingAck(f.token, "awaiting")
        for ((id, state) in listOf("rejected" to OperationSyncState.REJECTED, "auth" to OperationSyncState.AUTH_REQUIRED)) {
            f.store.enqueue(f.token, id, "set/readingAnnotations/$id", payload, 3)
            f.store.result(f.store.awaitingAck(f.token, id), state)
        }
        confirm(f, "old_barrier", "merge/scores", 4)
        confirm(f, "new_barrier", "set/scores/one", 5)
        confirm(f, "unrelated", "set/reminders/other", 6)
        assertEquals(1, f.store.compactConfirmed(f.token, keepRecent = 0) {})
        assertEquals(setOf("queued", "awaiting", "rejected", "auth", "old_barrier", "new_barrier"),
            f.store.records(f.token).map { it.operationId }.toSet())
        assertEquals(OperationSyncState.AWAITING_ACK, f.store.state(f.token, "awaiting"))
        val reopened = OperationJournal(f.root, f.gate)
        assertEquals(OperationSyncState.UNKNOWN_AFTER_RESTART, reopened.state(f.token, "queued"))
        assertEquals(0, reopened.compactConfirmed(f.token, keepRecent = 0) {})
        assertNotNull(reopened.get(f.token, "queued"))
    }

    @Test fun workspaceAndDeletionAliasesRemainBarriersAfterCompaction() {
        val f = fixture()
        f.store.enqueue(f.token, "reading", "set/savedReadings/r1", payload, 1)
        f.store.enqueue(f.token, "package", "set/offlinePackages/c/m/v1", payload, 2)
        confirm(f, "delete_reading", "deleteReading/r1", 3)
        confirm(f, "remove_package", "removePackage/c/m/v1", 4)
        assertEquals(0, f.store.compactConfirmed(f.token, keepRecent = 0) {})
        assertTrue(OperationJournal.overlaps(f.store.get(f.token, "reading")!!, f.store.get(f.token, "delete_reading")!!))
        assertTrue(OperationJournal.overlaps(f.store.get(f.token, "package")!!, f.store.get(f.token, "remove_package")!!))
        confirm(f, "workspace", "merge/_workspace", 5)
        assertEquals(2, f.store.compactConfirmed(f.token, keepRecent = 0) {})
        assertEquals(setOf("reading", "package", "workspace"), f.store.records(f.token).map { it.operationId }.toSet())
        assertTrue(OperationJournal.overlaps(f.store.get(f.token, "reading")!!, f.store.get(f.token, "workspace")!!))
        assertTrue(OperationJournal.overlaps(f.store.get(f.token, "package")!!, f.store.get(f.token, "workspace")!!))
    }

    @Test fun payloadCleanupMustSucceedWhileAuthoritativeMetadataStillExists() {
        val f = fixture()
        val payloads = OperationPayloadStore(f.root.resolve("payloads"), f.gate, f.store)
        f.store.enqueue(f.token, "done", "set/reminders/r1", payload, 1)
        payloads.retain(f.token, "done", "set/reminders/r1", payload)
        f.store.result(f.store.awaitingAck(f.token, "done"), OperationSyncState.CONFIRMED, 200)
        assertThrows(IllegalStateException::class.java) {
            f.store.compactConfirmed(f.token, keepRecent = 0) { error("cleanup failed") }
        }
        assertNotNull(f.store.get(f.token, "done"))
        assertEquals(payload, payloads.read(f.token, "done", "set/reminders/r1"))
        assertEquals(1, f.store.compactConfirmed(f.token, keepRecent = 0) {
            assertEquals(it, f.store.get(f.token, it.operationId))
            payloads.removeConfirmed(f.token, it)
        })
        assertNull(f.store.get(f.token, "done"))
        assertFalse(f.root.resolve("payloads/alice/done.json").exists())
    }

    @Test fun compactionCannotUseAForeignSession() {
        val f = fixture()
        confirm(f, "retained", "set/reminders/r1", 1)
        val foreign = SessionGate({ "bob" }, { "bob" }).activate()
        assertThrows(IllegalStateException::class.java) { f.store.compactConfirmed(foreign, keepRecent = 0) {} }
        assertNotNull(f.store.get(f.token, "retained"))
    }

    @Test fun payloadCleanupCanReadJournalFromAnotherThreadWithoutDeadlocking() {
        val f = fixture()
        confirm(f, "done", "set/reminders/r1", 1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, f.store.compactConfirmed(f.token, keepRecent = 0) { record ->
                assertEquals(record, worker.submit<OperationRecord?> { f.store.get(f.token, record.operationId) }
                    .get(2, java.util.concurrent.TimeUnit.SECONDS))
            })
        } finally { worker.shutdownNow() }
    }

    @Test fun aNewPendingEditDuringPayloadCleanupKeepsItsConfirmedBarrier() {
        val f = fixture()
        confirm(f, "barrier", "set/scores/one", 10)
        assertEquals(0, f.store.compactConfirmed(f.token, keepRecent = 0) {
            f.store.enqueue(f.token, "backdated", "merge/scores", payload, 1)
        })
        assertEquals(setOf("barrier", "backdated"), f.store.records(f.token).map { it.operationId }.toSet())
    }
}
