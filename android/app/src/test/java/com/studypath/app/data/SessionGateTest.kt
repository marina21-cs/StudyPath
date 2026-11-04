package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class SessionGateTest {
    @Test fun `different user cannot activate a bound installation`() {
        val gate = SessionGate({ "alice" }, { "bob" })
        expectFailure { gate.activate() }
    }

    @Test fun `a callback from before logout cannot access the new session`() {
        val gate = SessionGate({ "alice" }, { "alice" })
        val oldCallback = gate.activate()
        gate.invalidate()
        val newCallback = gate.activate()
        expectFailure { gate.check(oldCallback) }
        gate.check(newCallback)
    }

    @Test fun `auth changes invalidate a captured owner token`() {
        var currentUid: String? = "alice"
        val gate = SessionGate({ "alice" }, { currentUid })
        val token = gate.activate()
        currentUid = "bob"
        expectFailure { gate.check(token) }
    }

    @Test fun `unbound or signed out installations cannot open private paths`() {
        expectFailure { SessionGate({ null }, { "alice" }).activate() }
        expectFailure { SessionGate({ "alice" }, { null }).activate() }
    }

    @Test fun `keys reject path traversal and Firebase reserved characters`() {
        listOf("../alice", "a/b", "a.b", "a#b", "a\$b", "", "x".repeat(65)).forEach { key ->
            expectFailure { DatabaseRecords.requireKey(key) }
        }
        DatabaseRecords.requireKey("course_101-v1")
    }

    @Test fun `ungraded score stays absent while a recorded zero stays zero`() {
        val pending = ScoreRecord("quiz1", "it101", "quiz1", "quiz", "UNGRADED", 10.0)
        val zero = pending.copy(status = "SCORED", earnedPoints = 0.0)
        assertFalse(DatabaseRecords.score(pending).containsKey("earnedPoints"))
        assertEquals(0.0, DatabaseRecords.score(zero)["earnedPoints"])
    }

    @Test fun `invalid score and ungraded numeric value are rejected before a write`() {
        val score = ScoreRecord("quiz1", "it101", "quiz1", "quiz", "SCORED", 10.0, 11.0)
        expectFailure { DatabaseRecords.score(score) }
        expectFailure { DatabaseRecords.score(score.copy(status = "UNGRADED", earnedPoints = 0.0)) }
        expectFailure { DatabaseRecords.score(score.copy(earnedPoints = Double.NaN)) }
    }

    @Test fun `practice attempt retries serialize to exactly the same immutable record`() {
        val attempt = PracticeAttempt("attempt_001", "arrays", 2, 2, 3, 1_700_000_000_000)
        assertEquals(DatabaseRecords.attempt(attempt), DatabaseRecords.attempt(attempt))
        assertEquals(2, DatabaseRecords.attempt(attempt)["version"])
        assertEquals("attempt_001", attempt.id)
    }

    @Test fun `recorded score identifies its assessment plan`() {
        val score = ScoreRecord("quiz1", "it101", "quiz1", "quiz", "SCORED", 10.0, 8.0)
        assertEquals("demo_linear_v1", DatabaseRecords.score(score)["planVersion"])
    }

    @Test fun `GWA inclusion must be explicit and have a completed grade scale`() {
        val attempt = CourseAttempt("a1", "it101", "Term 1", "COMPLETED", 3.0, grade = 1.75)
        assertEquals(false, DatabaseRecords.courseAttempt(attempt)["includeInGwa"])
        expectFailure { DatabaseRecords.courseAttempt(attempt.copy(includeInGwa = true)) }
        assertEquals(true, DatabaseRecords.courseAttempt(attempt.copy(includeInGwa = true, scaleId = "demo_scale"))["includeInGwa"])
    }

    private fun expectFailure(action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        fail("Expected invalid data or a rejected owner session")
    }
}
