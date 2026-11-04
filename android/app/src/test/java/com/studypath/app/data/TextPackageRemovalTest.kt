package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextPackageRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun removalIntentSurvivesRestartAndHidesReadableBytesUntilAcknowledgedCleanup() {
        val pack = TextPackageFixture.verified()
        val store = TextPackageStore(folder.root)
        store.stage("owner", pack, 100); store.confirm("owner", pack, 200)
        store.beginRemoval("owner", pack.key)
        val reopened = TextPackageStore(folder.root)
        assertTrue(reopened.isRemoving("owner", pack.key))
        assertFalse(requireNotNull(reopened.read("owner", "fixture_v1", "fixture_module", 1)).ready)
        assertTrue(folder.root.walkTopDown().any { it.name == "v1.json" })
        reopened.remove("owner", pack.key)
        assertNull(reopened.read("owner", "fixture_v1", "fixture_module", 1))
        assertFalse(reopened.isRemoving("owner", pack.key))
    }
    @Test fun rejectedRemovalRestoresTheSameReadyPackageWithoutAffectingOtherVersions() {
        val first = TextPackageFixture.verified(); val other = TextPackageFixture.verified(2)
        val store = TextPackageStore(folder.root)
        for (pack in listOf(first, other)) { store.stage("owner", pack, 100); store.confirm("owner", pack, 200) }
        store.beginRemoval("owner", first.key)
        assertTrue(requireNotNull(store.read("owner", "fixture_v1", "fixture_module", 2)).ready)
        store.cancelRemoval("owner", first.key)
        assertTrue(requireNotNull(store.read("owner", "fixture_v1", "fixture_module", 1)).ready)
        assertFalse(store.isRemoving("other", first.key))
    }

    @Test fun finishingAnOldOperationCannotDeleteANewDownloadOrItsNewRemovalMarker() {
        val pack = TextPackageFixture.verified()
        val store = TextPackageStore(folder.root)
        store.stage("owner", pack, 100); store.confirm("owner", pack, 200)
        store.beginRemoval("owner", pack.key, "old_operation")
        assertTrue(store.finishRemoval("owner", pack.key, "old_operation"))
        store.stage("owner", pack, 300); store.confirm("owner", pack, 400)
        assertFalse(store.finishRemoval("owner", pack.key, "old_operation"))
        assertTrue(requireNotNull(store.read("owner", "fixture_v1", "fixture_module", 1)).ready)
        store.beginRemoval("owner", pack.key, "new_operation")
        assertFalse(store.finishRemoval("owner", pack.key, "old_operation"))
        assertEquals("new_operation", store.removalOperationId("owner", pack.key))
        assertNotNull(store.read("owner", "fixture_v1", "fixture_module", 1))
        assertTrue(store.finishRemoval("owner", pack.key, "new_operation"))
        assertFalse(store.finishRemoval("owner", pack.key, "new_operation"))
    }

    @Test fun competingFinishersCannotRemoveAReplacementAfterTheFirstFinishes() {
        val pack = TextPackageFixture.verified()
        val store = TextPackageStore(folder.root)
        store.stage("owner", pack, 100); store.confirm("owner", pack, 200)
        store.beginRemoval("owner", pack.key, "old_operation")
        val worker = java.util.concurrent.Executors.newFixedThreadPool(2)
        val firstFinished = java.util.concurrent.CountDownLatch(1)
        val replacementReady = java.util.concurrent.CountDownLatch(1)
        try {
            val stale = worker.submit<Boolean> {
                firstFinished.await()
                replacementReady.await()
                store.finishRemoval("owner", pack.key, "old_operation")
            }
            worker.submit {
                assertTrue(store.finishRemoval("owner", pack.key, "old_operation"))
                firstFinished.countDown()
                store.stage("owner", pack, 300); store.confirm("owner", pack, 400)
                replacementReady.countDown()
            }.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(stale.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(requireNotNull(store.read("owner", "fixture_v1", "fixture_module", 1)).ready)
        } finally { worker.shutdownNow() }
    }
}
