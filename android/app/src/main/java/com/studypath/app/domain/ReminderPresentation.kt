package com.studypath.app.domain

import com.studypath.app.data.ReminderTarget
import com.studypath.app.data.ReminderTargets
import com.studypath.app.data.StudyReminder

object ReminderPresentation {
    fun summary(rows: List<StudyReminder>, now: Long, nextLabel: (StudyReminder) -> String): String {
        val enabled = rows.filter { it.enabled }
        val due = enabled.count { it.dueAt <= now }
        if (due > 0) return "$due ${if (due == 1) "reminder is" else "reminders are"} due. Open Study reminders to review."
        val next = enabled.minByOrNull { it.dueAt }
        return if (next != null) "Next: ${nextLabel(next)}" else if (rows.isEmpty()) "No study reminders yet."
        else "All study reminders are turned off."
    }

    fun targetLabel(row: StudyReminder): String = when (val target = ReminderTargets.target(row)) {
        is ReminderTarget.LegacyModule -> "Practice module"
        is ReminderTarget.ModuleVersion -> "Downloaded lesson · version ${target.version}"
        is ReminderTarget.SavedReading -> "Saved reading"
    }

    fun millisUntilRefresh(now: Long): Long = 60_000L - Math.floorMod(now, 60_000L)
}
