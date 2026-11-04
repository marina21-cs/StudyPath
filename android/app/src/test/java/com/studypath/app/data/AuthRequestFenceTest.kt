package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class AuthRequestFenceTest {
    @Test fun `cancelled auth task stays pending until its late completion is cleaned`() {
        val fence = AuthRequestFence()
        val oldRequest = fence.epoch()
        fence.attach(oldRequest)
        fence.cancel()
        assertFalse(fence.isCurrent(oldRequest))
        assertTrue(fence.hasPending())
        try { fence.attach(fence.epoch()); fail("A new sign-in must wait for late cleanup") }
        catch (_: IllegalStateException) { }
        fence.complete(oldRequest)
        val nextRequest = fence.epoch()
        fence.attach(nextRequest)
        assertTrue(fence.isCurrent(nextRequest))
    }

    @Test fun `a duplicate old callback cannot clear a newer pending request`() {
        val fence = AuthRequestFence()
        val oldRequest = fence.epoch()
        fence.attach(oldRequest)
        fence.cancel()
        fence.complete(oldRequest)
        val nextRequest = fence.epoch()
        fence.attach(nextRequest)
        fence.complete(oldRequest)
        assertTrue(fence.hasPending())
        assertTrue(fence.isCurrent(nextRequest))
    }

    @Test fun `successful task completion releases pending work without cancelling its owner`() {
        val fence = AuthRequestFence()
        val request = fence.epoch()
        fence.attach(request)
        fence.complete(request)
        assertFalse(fence.hasPending())
        assertTrue(fence.isCurrent(request))
    }
}
