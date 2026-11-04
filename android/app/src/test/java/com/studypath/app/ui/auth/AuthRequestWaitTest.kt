package com.studypath.app.ui.auth

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRequestWaitTest {
    @Test fun `a completed request preserves its value including no saved session`() = runTest {
        assertEquals("signed in", awaitAuthRequest { "signed in" })
        assertNull(awaitAuthRequest<String?> { null })
    }

    @Test fun `an unacknowledged Firebase request stops waiting without cancelling its task`() = runTest {
        val pending = TaskCompletionSource<String>()
        var repositoryCleanupRan = false
        try {
            awaitAuthRequest(timeoutMillis = 1_000) {
                try { pending.task.await() }
                catch (error: CancellationException) { repositoryCleanupRan = true; throw error }
            }
            fail("An unacknowledged request must reach the recoverable deadline")
        } catch (_: AuthRequestTimeoutException) { }
        assertTrue("The repository must receive cancellation for its existing cleanup", repositoryCleanupRan)
        assertFalse("The underlying Firebase operation may still complete", pending.task.isComplete)
        pending.setResult("late acknowledgement")
        assertEquals("late acknowledgement", pending.task.result)
    }

    @Test fun `leaving the screen remains lifecycle cancellation rather than a timeout error`() = runTest {
        var lifecycleCancelled = false
        var timedOut = false
        val request = launch {
            try { awaitAuthRequest { awaitCancellation() } }
            catch (_: AuthRequestTimeoutException) { timedOut = true }
            catch (error: CancellationException) { lifecycleCancelled = true; throw error }
        }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(lifecycleCancelled)
        assertFalse(timedOut)
    }

    @Test fun `a nested operation timeout is not mistaken for this screen deadline`() = runTest {
        try {
            awaitAuthRequest(timeoutMillis = 1_000) { withTimeout(10) { awaitCancellation() } }
            fail("The inner timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
    }

    @Test fun `an explicit service rejection preserves its type reason and causal origin`() = runTest {
        val rejected = IllegalStateException("Profile permission denied")
        try {
            awaitAuthRequest<Unit> { throw rejected }
            fail("An explicit rejection must reach the existing error handler")
        } catch (error: IllegalStateException) {
            assertEquals(rejected.javaClass, error.javaClass)
            assertEquals(rejected.message, error.message)
            // Coroutine stack-trace recovery may copy the exception and retain its original as the cause.
            assertTrue("The service rejection must remain the causal origin",
                generateSequence<Throwable>(error) { it.cause }.any { it === rejected })
        }
    }

    @Test fun `registration timeout preserves uncertainty and original account recovery`() {
        val unbound = authTimeoutMessage(AuthRequestKind.REGISTRATION, useEmulators = false)
        assertTrue(unbound.contains("may already exist"))
        assertTrue(unbound.contains("same email"))
        val linked = authTimeoutMessage(AuthRequestKind.REGISTRATION, useEmulators = false, accountLinked = true)
        assertTrue(linked.contains("changes are kept"))
        assertTrue(linked.contains("same account"))
    }

    @Test fun `emulator connection failure explains the build requirement for a phone`() {
        val local = authConnectionMessage(useEmulators = true)
        assertTrue(local.contains("test build"))
        assertTrue(local.contains("test computer"))
        val cloud = authConnectionMessage(useEmulators = false)
        assertTrue(cloud.contains("internet connection"))
        assertFalse(cloud.contains("local Firebase"))
    }

    @Test fun `password recovery timeout allows that the email request can still finish`() {
        val message = authTimeoutMessage(AuthRequestKind.PASSWORD_RESET, useEmulators = false)
        assertTrue(message.contains("email may still arrive"))
        assertTrue(message.contains("inbox"))
    }
}
