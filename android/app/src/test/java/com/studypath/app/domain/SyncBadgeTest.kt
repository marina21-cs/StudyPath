package com.studypath.app.domain

import com.studypath.app.data.OperationSyncState
import org.junit.Assert.*
import org.junit.Test

class SyncBadgeTest {
    @Test fun restartAndRejectedWritesNeverClaimServerConfirmation() {
        assertEquals(SyncBadge.UNKNOWN, SyncBadge.forOperation(OperationSyncState.UNKNOWN_AFTER_RESTART))
        for (state in listOf(OperationSyncState.REJECTED, OperationSyncState.AUTH_REQUIRED))
            assertEquals(SyncBadge.RETRY, SyncBadge.forOperation(state))
        for (state in listOf(OperationSyncState.QUEUED, OperationSyncState.AWAITING_ACK))
            assertEquals(SyncBadge.WAITING, SyncBadge.forOperation(state))
        assertEquals(SyncBadge.CONFIRMED, SyncBadge.forOperation(OperationSyncState.CONFIRMED))
    }
}
