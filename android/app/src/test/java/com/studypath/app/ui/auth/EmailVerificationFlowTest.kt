package com.studypath.app.ui.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationFlowTest {
    @Test fun `opening or recreating verification never requests email automatically`() = runTest {
        val account = FakeAccount()
        var deadline = 0L
        val initial = EmailVerificationFlow("owner", account, { 100_000L }, deadline) { deadline = it }
        assertFalse(initial.current().verified)
        assertEquals(0, account.sends)
        assertEquals(VerificationSendResult.REQUESTED, initial.send())
        val recreated = EmailVerificationFlow("owner", account, { 100_001L }, deadline) { deadline = it }
        assertEquals(VerificationSendResult.WAIT, recreated.send())
        assertEquals(60L, recreated.resendSeconds)
        assertEquals(1, account.sends)
    }

    @Test fun `resend becomes available only after sixty seconds`() = runTest {
        val account = FakeAccount()
        var now = 100_000L
        val flow = EmailVerificationFlow("owner", account, { now })
        flow.send()
        now += 59_999
        assertEquals(VerificationSendResult.WAIT, flow.send())
        assertEquals(1L, flow.resendSeconds)
        now += 1
        assertEquals(VerificationSendResult.REQUESTED, flow.send())
        assertEquals(2, account.sends)
        assertFalse("Sending must not verify an address", flow.current().verified)
    }

    @Test fun `a failed send preserves the account and offers a later retry`() = runTest {
        val account = FakeAccount()
        val offline = IllegalStateException("offline")
        account.onSend = { throw offline }
        var now = 100_000L
        val flow = EmailVerificationFlow("owner", account, { now })
        try { flow.send(); fail("The failed request must not be labelled requested") }
        catch (error: IllegalStateException) {
            assertEquals(offline.javaClass, error.javaClass)
            assertEquals(offline.message, error.message)
            assertTrue("Coroutine recovery must preserve the original service rejection",
                generateSequence(error as Throwable) { it.cause }.any { it === offline })
        }
        assertEquals("owner", flow.current().uid)
        assertFalse(flow.current().verified)
        assertEquals(VerificationSendResult.WAIT, flow.send())
        assertEquals("Cooldown must not issue another request", 1, account.sends)
        now += 60_000
        account.onSend = {}
        assertEquals(VerificationSendResult.REQUESTED, flow.send())
        assertEquals(2, account.sends)
        assertEquals("owner", flow.current().uid)
        assertFalse("A successful retry still requires actual email verification", flow.current().verified)
    }

    @Test fun `checking an unverified address does not refresh a verified token`() = runTest {
        val account = FakeAccount()
        val flow = EmailVerificationFlow("owner", account, { 100_000L })
        assertFalse(flow.refresh())
        assertEquals(1, account.reloads)
        assertEquals(0, account.tokenRefreshes)
        assertEquals(0, account.sends)
    }

    @Test fun `a verified server reload refreshes the ID token before reporting success`() = runTest {
        val account = FakeAccount()
        account.onReload = { account.value = account.value.copy(verified = true) }
        val flow = EmailVerificationFlow("owner", account, { 100_000L })
        assertTrue(flow.refresh())
        assertEquals(1, account.tokenRefreshes)
        assertEquals(VerificationSendResult.ALREADY_VERIFIED, flow.send())
        assertEquals(0, account.sends)
    }

    @Test fun `reload and token failures do not become verification success and can be retried`() = runTest {
        val account = FakeAccount()
        account.onReload = { throw IllegalStateException("reload offline") }
        val flow = EmailVerificationFlow("owner", account, { 100_000L })
        try { flow.refresh(); fail("An unavailable reload must remain unconfirmed") }
        catch (_: IllegalStateException) { }
        assertEquals(0, account.tokenRefreshes)
        account.onReload = { account.value = account.value.copy(verified = true) }
        account.onToken = { throw IllegalStateException("token offline") }
        try { flow.refresh(); fail("A failed token refresh must remain unconfirmed") }
        catch (_: IllegalStateException) { }
        account.onToken = {}
        assertTrue(flow.refresh())
    }

    @Test fun `a changed owner during reload cannot refresh another account token`() = runTest {
        val account = FakeAccount()
        account.onReload = { account.value = VerificationAccount("other", "other@example.invalid", true) }
        val flow = EmailVerificationFlow("owner", account, { 100_000L })
        try { flow.refresh(); fail("A different UID cannot finish this owner's verification") }
        catch (_: VerificationSessionChangedException) { }
        assertEquals(0, account.tokenRefreshes)
        try { flow.send(); fail("A different UID cannot receive this owner's send request") }
        catch (_: VerificationSessionChangedException) { }
        assertEquals(0, account.sends)
    }

    @Test fun `a changed owner after token refresh cannot report success`() = runTest {
        val account = FakeAccount()
        account.value = account.value.copy(verified = true)
        account.onToken = { account.value = account.value.copy(uid = "other") }
        val flow = EmailVerificationFlow("owner", account, { 100_000L })
        try { flow.refresh(); fail("Late completion cannot verify a replacement session") }
        catch (_: VerificationSessionChangedException) { }
    }

    @Test fun `a request that never replies reaches the thirty second recoverable deadline`() = runTest {
        val account = FakeAccount()
        account.onSend = { awaitCancellation() }
        var deadline = 0L
        val flow = EmailVerificationFlow("owner", account, { 100_000L + testScheduler.currentTime }, deadline) { deadline = it }
        try { flow.send(); fail("The screen must stop waiting") }
        catch (_: AuthRequestTimeoutException) { }
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(160_000L, deadline)
        assertEquals(30L, flow.resendSeconds)
        assertEquals("owner", flow.current().uid)
    }

    @Test fun `leaving the screen cancels its wait while keeping the resend deadline`() = runTest {
        val account = FakeAccount()
        account.onSend = { awaitCancellation() }
        var deadline = 0L
        var cancelled = false
        val flow = EmailVerificationFlow("owner", account, { 100_000L }, deadline) { deadline = it }
        val job = launch {
            try { flow.send() } catch (error: CancellationException) { cancelled = true; throw error }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(160_000L, deadline)
        assertEquals(VerificationSendResult.WAIT, flow.send())
        assertEquals(1, account.sends)
    }

    @Test fun `rapid taps cannot start two requests while the first is waiting`() = runTest {
        val account = FakeAccount()
        account.onSend = { awaitCancellation() }
        val flow = EmailVerificationFlow("owner", account, { 100_000L })
        val first = launch { flow.send() }
        runCurrent()
        try { flow.send(); fail("A second request must not enter Firebase") }
        catch (_: IllegalStateException) { }
        assertEquals(1, account.sends)
        first.cancelAndJoin()
    }

    @Test fun `a backwards device clock cannot extend a restored countdown beyond one minute`() {
        var now = 100_000L
        var saved = 9_000_000L
        val flow = EmailVerificationFlow("owner", FakeAccount(), { now }, saved) { saved = it }
        assertEquals(60L, flow.resendSeconds)
        assertEquals(160_000L, saved)
        now += 60_000
        assertEquals(0L, flow.resendSeconds)
    }

    private class FakeAccount : EmailVerificationAccount {
        var value = VerificationAccount("owner", "owner@example.invalid", false)
        var sends = 0
        var reloads = 0
        var tokenRefreshes = 0
        var onSend: suspend () -> Unit = {}
        var onReload: suspend () -> Unit = {}
        var onToken: suspend () -> Unit = {}
        override fun current() = value
        override suspend fun send() { sends++; onSend() }
        override suspend fun reload() { reloads++; onReload() }
        override suspend fun refreshToken() { tokenRefreshes++; onToken() }
    }
}
