package com.studypath.app.domain

import com.studypath.app.data.StudyReminder
import com.studypath.app.data.ReminderTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulePolicyTest {
    private fun reminder(due: Long, enabled: Boolean = true) =
        StudyReminder("study1", "programming_basics", "Review", due, enabled, "UTC")

    @Test fun missingOrInconsistentTargetNeverBecomesAnActiveSchedule() {
        val missing = reminder(1000).copy(moduleId = null)
        val inconsistent = reminder(1000).copy(target = ReminderTarget.ModuleVersion("curriculum", "different_module", 1))
        assertTrue("An enabled row still needs a valid immutable target identity", ReminderSchedulePolicy.active(listOf(missing, inconsistent)).isEmpty())
    }

    @Test fun anEnabledOverdueReminderRemainsActive() {
        val late = reminder(1_000)
        assertEquals(listOf(late), ReminderSchedulePolicy.active(listOf(late)))
    }

    @Test fun anEnabledReminderAtItsDueTimeRemainsActive() {
        val due = reminder(2_000)
        assertEquals(listOf(due), ReminderSchedulePolicy.active(listOf(due)))
    }

    @Test fun disabledRemindersAreExcluded() {
        assertTrue(ReminderSchedulePolicy.active(listOf(reminder(3_000, false))).isEmpty())
    }

    @Test fun enabledFutureRemindersRemainActive() {
        val future = reminder(3_000)
        assertEquals(listOf(future), ReminderSchedulePolicy.active(listOf(future)))
    }

    @Test fun anAlreadyDeliveredDueTimeIsNotRepeated() {
        assertFalse(ReminderSchedulePolicy.needsDelivery(1_000, 1_000))
    }

    @Test fun anEditedOrNeverDeliveredDueTimeCanBeScheduled() {
        assertTrue(ReminderSchedulePolicy.needsDelivery(3_000, 1_000))
        assertTrue(ReminderSchedulePolicy.needsDelivery(3_000, null))
    }

    @Test fun overdueWorkIsEligibleWithoutAnAdditionalDelay() {
        assertEquals(0, ReminderSchedulePolicy.delayMillis(1_000, 2_000))
        assertEquals(1_000, ReminderSchedulePolicy.delayMillis(3_000, 2_000))
    }
}
