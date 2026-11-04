package com.studypath.app.data

class SessionToken internal constructor(val uid: String, internal val generation: Long)

class SessionGate(private val boundUid: () -> String?, private val currentUid: () -> String?) {
    private var generation = 0L
    private var active = false

    @Synchronized fun activate(): SessionToken {
        val uid = currentUid()
        check(!uid.isNullOrEmpty() && uid == boundUid()) { "This installation is bound to a different account. Sign in with its original account." }
        generation++
        active = true
        return SessionToken(uid, generation)
    }

    @Synchronized fun capture(): SessionToken {
        check(active) { "Sign in to open this workspace." }
        val uid = currentUid()
        check(!uid.isNullOrEmpty() && uid == boundUid()) { "The account session has changed." }
        return SessionToken(uid, generation)
    }

    @Synchronized fun check(token: SessionToken) {
        check(active && token.generation == generation && token.uid == boundUid() && token.uid == currentUid()) {
            "The account session has changed. Open this workspace again."
        }
    }

    @Synchronized fun invalidate() {
        active = false
        generation++
    }
}
