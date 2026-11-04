package com.studypath.app.domain

import com.studypath.app.data.ReminderTarget
import com.studypath.app.data.StudyReminder
import org.junit.Assert.*
import org.junit.Test

class ReminderPresentationTest {
    private val row = StudyReminder("study", "module", "Fractions", 1000, true, "UTC")
    private fun summary(rows: List<StudyReminder>, now: Long) = ReminderPresentation.summary(rows, now) { "${it.title} at ${it.dueAt}" }
    @Test fun dueSummaryChangesWhenOnlyTheClockPassesTheDueTime() {
        assertEquals("Next: Fractions at 1000", summary(listOf(row), 999))
        assertEquals("1 reminder is due. Open Study reminders to review.", summary(listOf(row), 1000))
        assertEquals("2 reminders are due. Open Study reminders to review.", summary(listOf(row, row.copy(id = "other")), 1001))
    }
    @Test fun emptyDisabledAndMixedRemindersUseTheRightNextEnabledTarget() {
        assertEquals("No study reminders yet.", summary(emptyList(), 1000))
        assertEquals("All study reminders are turned off.", summary(listOf(row.copy(enabled = false)), 1000))
        assertEquals("Next: Fractions at 2000", summary(listOf(row.copy(enabled = false), row.copy(id = "later", dueAt = 2000)), 1000))
    }
    @Test fun targetsAreReadableAndClockRefreshNeverUsesAZeroDelayLoop() {
        assertEquals("Practice module", ReminderPresentation.targetLabel(row))
        assertEquals("Downloaded lesson · version 2", ReminderPresentation.targetLabel(row.copy(target = ReminderTarget.ModuleVersion("curriculum", "module", 2))))
        assertEquals("Saved reading", ReminderPresentation.targetLabel(row.copy(moduleId = null, target = ReminderTarget.SavedReading("reading"))))
        assertEquals(60_000L, ReminderPresentation.millisUntilRefresh(0)); assertEquals(1L, ReminderPresentation.millisUntilRefresh(59_999))
        assertEquals(60_000L, ReminderPresentation.millisUntilRefresh(60_000))
    }
}
