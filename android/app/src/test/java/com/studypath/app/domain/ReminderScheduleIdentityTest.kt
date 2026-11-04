package com.studypath.app.domain

import com.studypath.app.data.ReminderTarget
import com.studypath.app.data.StudyReminder
import org.junit.Assert.*
import org.junit.Test

class ReminderScheduleIdentityTest {
    private val legacy = StudyReminder("study", "module", "Study", 1000, true, "UTC")
    private val module = legacy.copy(target = ReminderTarget.ModuleVersion("curriculum", "module", 1))
    private val reading = legacy.copy(moduleId = null, target = ReminderTarget.SavedReading("reading"))

    @Test fun dueTimeAndEveryImmutableTargetDimensionChangeTheScheduleIdentity() {
        val rows = listOf(legacy, module, module.copy(target = ReminderTarget.ModuleVersion("curriculum", "module", 2)),
            module.copy(target = ReminderTarget.ModuleVersion("other", "module", 1)), reading,
            reading.copy(target = ReminderTarget.SavedReading("other")), reading.copy(dueAt = 1001))
        assertEquals(rows.size, rows.map(ReminderScheduleIdentity::fingerprint).toSet().size)
        assertEquals(ReminderScheduleIdentity.fingerprint(legacy), ReminderScheduleIdentity.fingerprint(legacy.copy(target = ReminderTarget.LegacyModule("module"))))
        assertEquals(ReminderScheduleIdentity.fingerprint(module), ReminderScheduleIdentity.fingerprint(module.copy(title = "Edited title", updatedAt = 900)))
    }

    @Test fun oldUntrackedCancelledWrongTargetAndOldDueWorkersFailClosed() {
        val current = ReminderScheduleIdentity.fingerprint(module)
        for ((expected, stored) in listOf(null to null, null to current, current to null,
            ReminderScheduleIdentity.fingerprint(legacy) to current, ReminderScheduleIdentity.fingerprint(module.copy(dueAt = 999)) to current)) {
            assertFalse(ReminderScheduleIdentity.canDeliver(stored, expected, 1000, 1000, null, null))
        }
        assertFalse(ReminderScheduleIdentity.canDeliver(current, current, 999, 1000, null, null))
        assertFalse(ReminderScheduleIdentity.canDeliver(current, current, 1000, 999, null, null))
        assertTrue(ReminderScheduleIdentity.canDeliver(current, current, 1000, 1000, null, null))
    }

    @Test fun repeatedDeliveryIsSuppressedButSameDueForANewTargetStillDelivers() {
        val first = ReminderScheduleIdentity.fingerprint(module)
        val next = ReminderScheduleIdentity.fingerprint(reading)
        assertFalse(ReminderScheduleIdentity.canDeliver(first, first, 1000, 1000, first, null))
        assertTrue(ReminderScheduleIdentity.canDeliver(next, next, 1000, 1000, first, 1000))
        assertTrue(ReminderScheduleIdentity.needsDelivery(module, null, 1000))
        assertFalse(ReminderScheduleIdentity.needsDelivery(legacy, null, 1000))
        assertTrue(ReminderScheduleIdentity.needsDelivery(legacy.copy(dueAt = 1001), null, 1000))
    }
}
