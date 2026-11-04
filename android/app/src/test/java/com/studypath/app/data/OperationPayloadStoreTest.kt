package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OperationPayloadStoreTest {
    private val payload = """{"note":"private managed study note","position":2}"""
    private data class Fixture(val root: java.io.File, val gate: SessionGate, val token: SessionToken,
        val journal: OperationJournal, val payloads: OperationPayloadStore)
    private fun fixture(budget: Long = 16L * 1024 * 1024): Fixture {
        val root = Files.createTempDirectory("operation-payload").toFile()
        val gate = SessionGate({ "alice" }, { "alice" }); val token = gate.activate()
        val journal = OperationJournal(root.resolve("journal"), gate)
        return Fixture(root, gate, token, journal, OperationPayloadStore(root.resolve("payloads"), gate, journal, budget))
    }
    private fun queue(f: Fixture, id: String = "edit", json: String = payload) {
        f.journal.enqueue(f.token, id, "readingAnnotations/r1", json, 100)
        f.payloads.retain(f.token, id, "readingAnnotations/r1", json)
    }

    @Test fun rejectedAndUnknownPayloadSurvivesRestartForExactRetry() {
        for (reject in listOf(false, true)) {
            val f = fixture(); queue(f)
            val ticket = f.journal.awaitingAck(f.token, "edit")
            if (reject) f.journal.result(ticket, OperationSyncState.REJECTED)
            val journal = OperationJournal(f.root.resolve("journal"), f.gate)
            val reopened = OperationPayloadStore(f.root.resolve("payloads"), f.gate, journal)
            assertEquals(payload, reopened.read(f.token, "edit", "readingAnnotations/r1"))
            assertEquals(if (reject) OperationSyncState.REJECTED else OperationSyncState.UNKNOWN_AFTER_RESTART,
                journal.state(f.token, "edit"))
            journal.retry(f.token, "edit", "readingAnnotations/r1", reopened.read(f.token, "edit", "readingAnnotations/r1")!!)
            assertEquals(OperationSyncState.QUEUED, journal.state(f.token, "edit"))
        }
    }

    @Test fun payloadIsRemovedOnlyUsingAuthoritativeConfirmedMetadataAndReceiptRemains() {
        val f = fixture(); queue(f)
        val queued = f.journal.get(f.token, "edit")!!
        assertThrows(IllegalArgumentException::class.java) { f.payloads.removeConfirmed(f.token, queued) }
        assertThrows(IllegalArgumentException::class.java) { f.payloads.removeConfirmed(f.token,
            queued.copy(state = OperationSyncState.CONFIRMED, acknowledgedAt = 200)) }
        assertNotNull(f.payloads.read(f.token, "edit", "readingAnnotations/r1"))
        val ticket = f.journal.awaitingAck(f.token, "edit")
        f.journal.result(ticket, OperationSyncState.CONFIRMED, acknowledgedAt = 200)
        val confirmed = f.journal.get(f.token, "edit")!!
        f.payloads.removeConfirmed(f.token, confirmed)
        assertNull(f.payloads.read(f.token, "edit", "readingAnnotations/r1"))
        assertEquals(OperationSyncState.CONFIRMED, f.journal.state(f.token, "edit"))
        f.payloads.removeConfirmed(f.token, confirmed)
    }

    @Test fun exactRetryPreservesOriginalPayloadWhileChangedPathOrValueFails() {
        val f = fixture(); queue(f)
        f.payloads.retain(f.token, "edit", "readingAnnotations/r1", """{ "position":2.0, "note":"private managed study note" }""")
        assertEquals(payload, f.payloads.read(f.token, "edit", "readingAnnotations/r1"))
        assertThrows(IllegalArgumentException::class.java) { f.payloads.retain(f.token, "edit", "readingAnnotations/r1", "null") }
        assertThrows(IllegalArgumentException::class.java) { f.payloads.read(f.token, "edit", "readingAnnotations/other") }
        assertThrows(IllegalArgumentException::class.java) { f.payloads.retain(f.token, "missing", "readingAnnotations/r1", payload) }
    }

    @Test fun budgetFailureKeepsAllOriginalPendingPayloadsAndDoesNotConfirmNewLedger() {
        val f = fixture(800); queue(f)
        f.journal.enqueue(f.token, "second", "readingAnnotations/r1", """{"note":"${"x".repeat(600)}"}""", 101)
        assertThrows(IllegalArgumentException::class.java) { f.payloads.retain(f.token, "second", "readingAnnotations/r1", """{"note":"${"x".repeat(600)}"}""") }
        assertEquals(payload, f.payloads.read(f.token, "edit", "readingAnnotations/r1"))
        assertEquals(OperationSyncState.QUEUED, f.journal.state(f.token, "second"))
        assertNull(f.payloads.read(f.token, "second", "readingAnnotations/r1"))
    }

    @Test fun corruptPayloadIsRetainedAndCannotBeOverwrittenOrMistakenForRetry() {
        val f = fixture(); queue(f)
        val file = f.root.resolve("payloads/alice/edit.json"); file.writeText("{truncated")
        assertThrows(IllegalArgumentException::class.java) { f.payloads.read(f.token, "edit", "readingAnnotations/r1") }
        assertThrows(IllegalArgumentException::class.java) { f.payloads.retain(f.token, "edit", "readingAnnotations/r1", payload) }
        assertEquals("{truncated", file.readText())
    }

    @Test fun uidPathHashAndSymlinkChangesFailClosedWithoutTouchingOtherBytes() {
        val f = fixture(); queue(f)
        val file = f.root.resolve("payloads/alice/edit.json")
        val original = file.readText(); file.writeText(original.replace("\"alice\"", "\"bob\""))
        assertThrows(IllegalArgumentException::class.java) { f.payloads.read(f.token, "edit", "readingAnnotations/r1") }
        file.writeText(original.replace("private managed study note", "tampered"))
        assertThrows(IllegalArgumentException::class.java) { f.payloads.read(f.token, "edit", "readingAnnotations/r1") }
        val other = SessionGate({ "bob" }, { "bob" }).activate()
        assertThrows(IllegalStateException::class.java) { f.payloads.read(other, "edit", "readingAnnotations/r1") }
        file.delete(); val outside = f.root.resolve("outside"); outside.writeText("outside")
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { f.payloads.retain(f.token, "edit", "readingAnnotations/r1", payload) }
        assertEquals("outside", outside.readText())
    }

    @Test fun credentialAndOversizePayloadCannotBeRetainedOrPersisted() {
        val f = fixture()
        for (bad in listOf("""{"password":"secret"}""", """{"nested":{"idToken":"secret"}}""", "\"${"x".repeat(1_000_001)}\"")) {
            assertThrows(IllegalArgumentException::class.java) { f.journal.enqueue(f.token, "bad", "readingAnnotations/r1", bad, 100) }
            assertThrows(IllegalArgumentException::class.java) { f.payloads.retain(f.token, "bad", "readingAnnotations/r1", bad) }
        }
        assertFalse(f.root.resolve("payloads/alice/bad.json").exists())
    }
}
