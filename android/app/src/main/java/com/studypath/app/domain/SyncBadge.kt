package com.studypath.app.domain

import com.studypath.app.data.OperationSyncState

/** A badge describes observed persistence state, never connectivity alone. */
object SyncBadge {
    const val WAITING = "Saved on this phone"
    const val UNKNOWN = "Save status unavailable"
    const val RETRY = "Needs attention"
    const val CONFIRMED = "Saved"

    fun forOperation(state: OperationSyncState): String = when (state) {
        OperationSyncState.QUEUED, OperationSyncState.AWAITING_ACK -> WAITING
        OperationSyncState.UNKNOWN_AFTER_RESTART -> UNKNOWN
        OperationSyncState.REJECTED, OperationSyncState.AUTH_REQUIRED -> RETRY
        OperationSyncState.CONFIRMED -> CONFIRMED
    }
}
