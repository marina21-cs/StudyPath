package com.studypath.app.data

/** Keeps a cancelled Firebase auth Task fenced until its underlying completion is cleaned. */
class AuthRequestFence {
    private var generation = 0L
    private var pendingEpoch: Long? = null
    @Synchronized fun epoch(): Long = generation
    @Synchronized fun attach(epoch: Long) {
        check(pendingEpoch == null && generation == epoch) { "The previous sign-in request is still finishing. Try again shortly." }
        pendingEpoch = epoch
    }
    @Synchronized fun cancel() { generation++ }
    @Synchronized fun isCurrent(epoch: Long): Boolean = generation == epoch
    @Synchronized fun hasPending(): Boolean = pendingEpoch != null
    @Synchronized fun complete(epoch: Long) { if (pendingEpoch == epoch) pendingEpoch = null }
}
