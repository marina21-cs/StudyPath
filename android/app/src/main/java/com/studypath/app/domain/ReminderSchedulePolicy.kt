package com.studypath.app.domain

import com.studypath.app.data.StudyReminder
import com.studypath.app.data.DatabaseRecords

object ReminderSchedulePolicy {
    fun active(reminders: List<StudyReminder>): List<StudyReminder> =
        reminders.filter { it.enabled && runCatching { DatabaseRecords.reminder(it) }.isSuccess }

    fun needsDelivery(dueAt: Long, deliveredDueAt: Long?): Boolean = dueAt != deliveredDueAt

    fun delayMillis(dueAt: Long, now: Long): Long = (dueAt - now).coerceAtLeast(0)
}
