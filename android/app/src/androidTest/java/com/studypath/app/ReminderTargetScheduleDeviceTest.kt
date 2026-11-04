package com.studypath.app

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studypath.app.data.StudyReminder
import com.studypath.app.domain.ReminderScheduleIdentity
import com.studypath.app.ui.ReminderScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Actual tracked-worker component proof. Repository/server target existence is an independent gate. */
@RunWith(AndroidJUnit4::class)
class ReminderTargetScheduleDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    @Before fun explicitOwnedTestOnly() {
        assumeTrue("Run explicitly after the owned Test AVD baseline with notifications allowed",
            InstrumentationRegistry.getArguments().getString("class")?.startsWith("com.studypath.app.ReminderTargetScheduleDeviceTest") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS)
        assertNotNull(runBlocking { withTimeout(20_000) { app.graph.repository.resumeSession() } })
        assertTrue(ReminderScheduler.allowed(app))
    }

    @Test fun collidingNotificationHashesKeepDistinctTargetsAndSelectedCancellation() {
        val uid = requireNotNull(app.graph.repository.currentSession()).uid
        val prefix = "b14_${UUID.randomUUID().toString().replace("-", "")}_"
        val first = StudyReminder(prefix + "Aa", "programming_basics", "B14 worker component A", System.currentTimeMillis() - 1, true, "UTC")
        val second = first.copy(id = prefix + "BB", title = "B14 worker component B")
        assertEquals("This fixture must exercise the hash collision", (uid + first.id).hashCode(), (uid + second.id).hashCode())
        val manager = app.getSystemService(NotificationManager::class.java)
        val tags = listOf(first, second).map { "studypath://reminder/$uid/${it.id}" }.toSet()
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(50) }
            assertTrue(label, condition())
        }
        try {
            assertNull("Unknown or pre-migration work cannot post", ReminderScheduler.deliveryDue(app, uid, prefix + "unknown", "untracked"))
            ReminderScheduler.update(app, uid, first.id, first)
            ReminderScheduler.update(app, uid, second.id, second)
            await("Both real fingerprinted workers must post distinct tagged notifications") { manager.activeNotifications.count { it.tag in tags } == 2 }
            val posted = manager.activeNotifications.filter { it.tag in tags }
            assertNotEquals("PendingIntents must retain distinct UID/record targets", posted[0].notification.contentIntent, posted[1].notification.contentIntent)
            assertNull("An unidentified old job cannot replay a current reminder", ReminderScheduler.deliveryDue(app, uid, first.id))
            assertNull("An already delivered generation cannot post twice", ReminderScheduler.deliveryDue(app, uid, first.id, ReminderScheduleIdentity.fingerprint(first)))
            ReminderScheduler.update(app, uid, first.id, null)
            await("Selected cancellation must preserve the other collision-tagged notification") {
                manager.activeNotifications.count { it.tag in tags } == 1 && manager.activeNotifications.any { it.tag == "studypath://reminder/$uid/${second.id}" }
            }
        } finally {
            ReminderScheduler.update(app, uid, first.id, null)
            ReminderScheduler.update(app, uid, second.id, null)
        }
    }
}
