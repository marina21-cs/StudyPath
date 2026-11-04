package com.studypath.app.domain

import com.studypath.app.data.OperationRecord
import com.studypath.app.data.OperationSyncState
import org.junit.Assert.*
import org.junit.Test

class OperationPresentationTest {
    private fun record(state: OperationSyncState) = OperationRecord("owner", "edit_${state.name}", "set/reminders/study",
        "a".repeat(64), state, 1000, null, "writer", null, null)

    @Test fun emptyAndConfirmedOnlyHistoryNeverClaimsUntrackedDataIsSynchronized() {
        assertEquals("No saved changes yet.", OperationPresentation.summary(emptyList()))
        assertEquals("Recent changes saved.", OperationPresentation.summary(listOf(record(OperationSyncState.CONFIRMED))))
    }
    @Test fun pendingUnknownRejectedAndAuthRequiredRemainVisibleTogether() {
        val records = OperationSyncState.entries.map(::record)
        val summary = OperationPresentation.summary(records)
        assertEquals("5 changes need attention.", summary)
        assertEquals(6, OperationSyncState.entries.map(OperationPresentation::status).distinct().size)
        OperationSyncState.entries.filter { it != OperationSyncState.CONFIRMED }.forEach {
            assertNotEquals("Saved", OperationPresentation.status(it))
        }
    }
    @Test fun ActiveAndConfirmedOperationsCannotBeRetriedFromThePresentation() {
        OperationSyncState.entries.forEach { state ->
            assertEquals(state.name, state !in setOf(OperationSyncState.AWAITING_ACK, OperationSyncState.CONFIRMED), OperationPresentation.retryable(record(state)))
        }
    }
    @Test fun withdrawnResultsRemainVisibleWithoutPromisingAnImpossibleRetryOrServerAcknowledgement() {
        val kept = record(OperationSyncState.QUEUED).copy(relativePath = "withdrawnLessonAttempts/run1")
        assertFalse(OperationPresentation.retryable(kept))
        assertEquals("Result kept · lesson unavailable", OperationPresentation.status(kept))
        val original = record(OperationSyncState.REJECTED).copy(relativePath = "set/lessonAttempts/run1")
        val summary = OperationPresentation.summary(listOf(kept, original))
        assertTrue(summary.contains("1 result kept on this phone"))
        assertFalse(summary.contains("server confirmed"))
        assertFalse(summary.contains("confirmation"))
    }

    @Test fun recoveryChoicesIdentifyTheKindOfWorkWithoutExposingStoragePaths() {
        val reminder = record(OperationSyncState.QUEUED)
        assertEquals("Reminder", OperationPresentation.title(reminder))
        val unknown = reminder.copy(relativePath = "merge/internal_branch/private_record_id")
        assertEquals("Saved change", OperationPresentation.title(unknown))
        assertEquals("Previous lesson result", OperationPresentation.title(reminder.copy(relativePath = "withdrawnLessonAttempts/private_run_id")))
    }
}
