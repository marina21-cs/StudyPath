package com.studypath.app.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.snapshot.IndexedNode
import com.google.firebase.database.snapshot.Node
import com.google.firebase.database.snapshot.NodeUtilities
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor

class InitialProfileTransactionTest {
    private val initial = mapOf("displayName" to "Student", "selectedCurriculumVersion" to "bsit_demo_v1",
        "createdAt" to 100L, "updatedAt" to 100L,
        "learnerPreferences" to mapOf("schemaVersion" to 1, "onboardingComplete" to false))
    private val newer = initial + mapOf("displayName" to "Updated name", "updatedAt" to 200L,
        "learnerPreferences" to mapOf("schemaVersion" to 1, "onboardingComplete" to false,
            "levelId" to "college", "subjectIds" to mapOf("programming" to true)))

    @Test fun `absent profile receives the retained original setup and waits for acknowledgement`() {
        val handler = InitialProfileTransaction(initial) {}
        val data = mutable(null)
        assertTrue(handler.doTransaction(data).isSuccess)
        assertEquals(node(initial).value, data.value)
        assertFalse(handler.task.isComplete)
        handler.onComplete(null, true, snapshot(data.value))
        assertTrue(handler.task.isSuccessful)
    }

    @Test fun `existing valid profile remains semantically unchanged`() {
        val handler = InitialProfileTransaction(initial) {}
        val data = mutable(newer)
        val before = data.value
        assertTrue("Unchanged success must still request server CAS acknowledgement", handler.doTransaction(data).isSuccess)
        assertEquals(before, data.value)
        assertFalse(handler.task.isComplete)
        handler.onComplete(null, true, snapshot(newer))
        assertTrue(handler.task.isSuccessful)
    }

    @Test fun `server retry preserves a concurrent newer profile after an initially empty cache`() {
        val handler = InitialProfileTransaction(initial) {}
        assertTrue(handler.doTransaction(mutable(null)).isSuccess)
        val server = mutable(newer)
        val before = server.value
        assertTrue(handler.doTransaction(server).isSuccess)
        assertEquals(before, server.value)
    }

    @Test fun `malformed existing profile is preserved and cannot be acknowledged`() {
        for (value in listOf<Any>("invalid profile", mapOf("displayName" to "Missing context"))) {
            val handler = InitialProfileTransaction(initial) {}
            val data = mutable(value)
            val before = data.value
            assertFalse(handler.doTransaction(data).isSuccess)
            assertEquals(before, data.value)
            handler.onComplete(null, false, snapshot(value))
            assertFalse(handler.task.isSuccessful)
        }
    }

    @Test fun `legacy retained set profile retry uses create only semantics`() {
        val root = Files.createTempDirectory("initial-profile-retry").toFile()
        val gate = SessionGate({ "alice" }, { "alice" })
        val token = gate.activate()
        val journal = OperationJournal(root, gate)
        val payload = Gson().toJson(initial)
        journal.enqueue(token, "setup", "set/profile", payload, 100)
        val reopened = OperationJournal(root, gate)
        val retained = requireNotNull(reopened.get(token, "setup"))
        assertEquals(OperationSyncState.UNKNOWN_AFTER_RESTART, retained.state)
        assertTrue(isInitialProfileCreation(retained.relativePath))
        assertFalse(isInitialProfileCreation("merge/profile"))
        assertFalse(isInitialProfileCreation("set/profile/displayName"))
        val handler = InitialProfileTransaction(Gson().fromJson(payload, Any::class.java)) { gate.check(token) }
        val server = mutable(newer)
        val before = server.value
        assertTrue(handler.doTransaction(server).isSuccess)
        assertEquals(before, server.value)
    }

    @Test fun `server error retains unconfirmed setup metadata and payload`() {
        val root = Files.createTempDirectory("initial-profile-rejection").toFile()
        val gate = SessionGate({ "alice" }, { "alice" })
        val token = gate.activate()
        val journal = OperationJournal(root.resolve("metadata"), gate)
        val payloads = OperationPayloadStore(root.resolve("payloads"), gate, journal)
        val payload = Gson().toJson(initial)
        journal.enqueue(token, "setup", "set/profile", payload, 100)
        payloads.retain(token, "setup", "set/profile", payload)
        val callback = journal.awaitingAck(token, "setup")
        val handler = InitialProfileTransaction(initial) { gate.check(token) }
        handler.task.addOnCompleteListener(Executor { it.run() }) {
            journal.result(callback, if (it.isSuccessful) OperationSyncState.CONFIRMED else OperationSyncState.REJECTED,
                if (it.isSuccessful) 200L else null)
        }
        handler.doTransaction(mutable(null))
        handler.onComplete(DatabaseError.fromCode(DatabaseError.PERMISSION_DENIED), false, snapshot(null))
        assertEquals(OperationSyncState.REJECTED, journal.get(token, "setup")!!.state)
        assertNull(journal.get(token, "setup")!!.acknowledgedAt)
        assertEquals(payload, payloads.read(token, "setup", "set/profile"))
    }

    @Test fun `changed owner cannot propose data or confirm a previous transaction`() {
        val gate = SessionGate({ "alice" }, { "alice" })
        val token = gate.activate()
        val handler = InitialProfileTransaction(initial) { gate.check(token) }
        val data = mutable(null)
        assertTrue(handler.doTransaction(data).isSuccess)
        gate.invalidate()
        handler.onComplete(null, true, snapshot(initial))
        assertFalse(handler.task.isSuccessful)
        val next = InitialProfileTransaction(initial) { gate.check(token) }
        val untouched = mutable(null)
        assertFalse(next.doTransaction(untouched).isSuccess)
        assertNull(untouched.value)
    }

    private fun node(value: Any?): Node = NodeUtilities.NodeFromJSON(value)
    private fun mutable(value: Any?): MutableData = MutableData::class.java.getDeclaredConstructor(Node::class.java)
        .apply { isAccessible = true }.newInstance(node(value))
    private fun snapshot(value: Any?): DataSnapshot = DataSnapshot::class.java
        .getDeclaredConstructor(DatabaseReference::class.java, IndexedNode::class.java)
        .apply { isAccessible = true }.newInstance(null, IndexedNode.from(node(value)))
}
