package com.studypath.app.domain

import com.studypath.app.data.DatabaseRecords
import com.studypath.app.data.ReminderTarget
import com.studypath.app.data.ReminderTargets
import com.studypath.app.data.StudyReminder

/** One delivery generation binds both its due time and immutable study target. */
object ReminderScheduleIdentity {
    fun fingerprint(reminder: StudyReminder): String {
        DatabaseRecords.reminder(reminder)
        return "v2:${reminder.dueAt}:${ReminderTargets.identity(reminder)}"
    }

    fun needsDelivery(reminder: StudyReminder, deliveredFingerprint: String?, legacyDeliveredDueAt: Long?): Boolean {
        if (deliveredFingerprint != null) return fingerprint(reminder) != deliveredFingerprint
        // Old delivery rows have no target proof. They suppress only an unchanged legacy row, never a new typed target.
        return ReminderTargets.target(reminder) !is ReminderTarget.LegacyModule || legacyDeliveredDueAt != reminder.dueAt
    }

    fun canDeliver(current: String?, expected: String?, dueAt: Long, now: Long, delivered: String?, legacyDeliveredDueAt: Long?): Boolean {
        if (current == null || expected == null || current != expected || !current.startsWith("v2:$dueAt:") || dueAt > now || dueAt <= 0) return false
        if (current == delivered) return false
        return delivered != null || !current.startsWith("v2:$dueAt:legacy:") || legacyDeliveredDueAt != dueAt
    }
}
