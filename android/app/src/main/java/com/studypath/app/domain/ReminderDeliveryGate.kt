package com.studypath.app.domain

/** Serializes reminder delivery with schedule changes and session termination. */
object ReminderDeliveryGate {
    private val monitor = Any()

    fun deliverIf(allowed: () -> Boolean, post: () -> Unit) {
        synchronized(monitor) {
            if (allowed()) post()
        }
    }

    fun <T> endSession(action: () -> T): T = synchronized(monitor) { action() }

    fun <T> updateSchedule(action: () -> T): T = synchronized(monitor) { action() }
}
