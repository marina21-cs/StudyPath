package com.studypath.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReminderDeliveryGateTest {
    @Test
    fun logoutWaitsForInFlightDeliveryBeforeClearingNotifications() {
        val signedIn = AtomicBoolean(true)
        val notifications = AtomicInteger()
        val deliveryEntered = CountDownLatch(1)
        val finishDelivery = CountDownLatch(1)
        val logoutStarted = CountDownLatch(1)
        val logoutFinished = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val delivery = threads.submit {
                ReminderDeliveryGate.deliverIf({ signedIn.get() }) {
                    deliveryEntered.countDown()
                    check(finishDelivery.await(5, TimeUnit.SECONDS))
                    notifications.incrementAndGet()
                }
            }
            assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS))
            val logout = threads.submit {
                logoutStarted.countDown()
                ReminderDeliveryGate.endSession {
                    signedIn.set(false)
                    notifications.set(0)
                }
                logoutFinished.countDown()
            }
            assertTrue(logoutStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Logout must serialize with an already eligible in-flight post",
                logoutFinished.await(300, TimeUnit.MILLISECONDS))
            finishDelivery.countDown()
            delivery.get(5, TimeUnit.SECONDS)
            logout.get(5, TimeUnit.SECONDS)
            assertFalse(signedIn.get())
            assertEquals("No in-flight post may survive logout cancellation", 0, notifications.get())
        } finally {
            finishDelivery.countDown()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aDeliveryStartingAfterLogoutDoesNotPost() {
        var signedIn = true
        var posts = 0
        ReminderDeliveryGate.endSession { signedIn = false }
        ReminderDeliveryGate.deliverIf({ signedIn }) { posts++ }
        assertEquals(0, posts)
    }
}
