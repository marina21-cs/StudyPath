package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LessonRunStoreTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `restart restores checked feedback exactly and ownership and hash stay isolated`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        var run = LessonEngine.start(module, "lesson_1", 100, "persisted_run")
        run = LessonEngine.continueStep(module, run, "goal", 101)
        run = LessonEngine.continueStep(module, run, "example", 102)
        run = LessonEngine.select(module, run, "choice", listOf("b"), 103)
        run = LessonEngine.check(module, run, "choice", 104)
        LessonRunStore(folder.root).save("owner", module, run)
        val restarted = LessonRunStore(folder.root)
        assertEquals(run, restarted.read("owner", module, "lesson_1"))
        assertNull(restarted.read("other", module, "lesson_1"))
        assertThrows(IllegalArgumentException::class.java) { restarted.save("owner", module, run.copy(position = 99)) }
        assertEquals(run, restarted.read("owner", module, "lesson_1"))
        val changed = module.copy(summary = module.summary.copy(packageSha256 = "0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { restarted.read("owner", changed, "lesson_1") }
        assertThrows(IllegalArgumentException::class.java) { restarted.read("../owner", module, "lesson_1") }
    }
    @Test fun `completion receipt is immutable across restart and new run retains previous attempt`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        var run = LessonEngine.start(module, "lesson_1", 100, "completed_run")
        for (step in module.lessons.single { it.id == "lesson_1" }.steps) {
            if (step.questionId != null) run = LessonEngine.reveal(module, run, step.id, 101)
            run = LessonEngine.continueStep(module, run, step.id, 102)
        }
        val store = LessonRunStore(folder.root)
        store.save("owner", module, run)
        assertNull(store.confirmedAt("owner", module, run))
        store.confirm("owner", module, run, 500)
        val restarted = LessonRunStore(folder.root)
        assertEquals(500L, restarted.confirmedAt("owner", module, run))
        restarted.confirm("owner", module, run, 600)
        assertEquals(500L, restarted.confirmedAt("owner", module, run))
        val next = LessonEngine.start(module, "lesson_1", 700, "next_run")
        restarted.save("owner", module, next)
        assertEquals(next, restarted.read("owner", module, "lesson_1"))
        assertEquals(500L, restarted.confirmedAt("owner", module, run))
        assertTrue(folder.root.walkTopDown().any { it.isFile && it.name == "completed_run.json" })
        assertEquals(listOf(run), restarted.history("owner", module))
    }
    @Test fun `stale saves cannot roll back progress and pending runs retain exact payload`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val start = LessonEngine.start(module, "lesson_1", 100, "stale_run")
        val advanced = LessonEngine.continueStep(module, start, "goal", 101)
        val store = LessonRunStore(folder.root)
        store.save("owner", module, start)
        store.save("owner", module, advanced)
        assertThrows(IllegalArgumentException::class.java) { store.save("owner", module, start) }
        assertEquals(advanced, store.get("owner", module, "stale_run"))
        assertEquals(emptyList<LessonRun>(), store.pending("owner", module))
    }
    @Test fun `every committed lesson state restores after a fresh store instance`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        var run = LessonEngine.start(module, "lesson_1", 100, "every_state")
        fun persist() {
            LessonRunStore(folder.root).save("owner", module, run)
            assertEquals(run, LessonRunStore(folder.root).read("owner", module, "lesson_1"))
        }
        persist()
        run = LessonEngine.continueStep(module, run, "goal", 101); persist()
        run = LessonEngine.continueStep(module, run, "example", 102); persist()
        run = LessonEngine.select(module, run, "choice", listOf("b"), 103); persist()
        run = LessonEngine.check(module, run, "choice", 104); persist()
        run = LessonEngine.retry(module, run, "choice", 105); persist()
        run = LessonEngine.hint(module, run, "choice", 106); persist()
        run = LessonEngine.reveal(module, run, "choice", 107); persist()
        run = LessonEngine.continueStep(module, run, "choice", 108); persist()
        run = LessonEngine.select(module, run, "ordering", listOf("first", "second"), 109); persist()
        run = LessonEngine.check(module, run, "ordering", 110); persist()
        run = LessonEngine.continueStep(module, run, "ordering", 111); persist()
        run = LessonEngine.select(module, run, "number", listOf("-"), 112); persist()
        run = LessonEngine.select(module, run, "number", listOf("3"), 113); persist()
        run = LessonEngine.check(module, run, "number", 114); persist()
        run = LessonEngine.continueStep(module, run, "number", 115); persist()
        assertEquals(listOf(run), LessonRunStore(folder.root).pending("owner", module))
        val payload = LessonEngine.attempt(module, run)
        assertEquals(payload, LessonEngine.attempt(module, LessonRunStore(folder.root).get("owner", module, run.runId)!!))
    }
    @Test fun `snapshot rejects JSON type coercion and integer overflow`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val run = LessonEngine.start(module, "lesson_1", 100, "100")
        val store = LessonRunStore(folder.root)
        store.save("owner", module, run)
        val snapshot = folder.root.walkTopDown().single { it.name == "100.json" }
        val valid = snapshot.readText()
        snapshot.writeText(valid.replace("\"runId\":\"100\"", "\"runId\":100"))
        assertThrows(IllegalArgumentException::class.java) { store.get("owner", module, "100") }
        snapshot.writeText(valid.replace("\"position\":0", "\"position\":4294967296"))
        assertThrows(IllegalArgumentException::class.java) { store.get("owner", module, "100") }
    }
    @Test fun `unreadable active pointer prevents starting over silently`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val run = LessonEngine.start(module, "lesson_1", 100, "pointer_run")
        val store = LessonRunStore(folder.root)
        store.save("owner", module, run)
        val pointer = folder.root.walkTopDown().single { it.name == "active.json" }
        pointer.writeText("{broken")
        assertThrows(IllegalArgumentException::class.java) { store.read("owner", module, "lesson_1") }
        assertThrows(IllegalArgumentException::class.java) { store.save("owner", module, LessonEngine.start(module, "lesson_1", 200, "replacement_run")) }
        assertEquals(run, store.get("owner", module, "pointer_run"))
    }
    @Test fun `malformed snapshots are rejected without destroying retained files`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val run = LessonEngine.start(module, "lesson_1", 100, "corrupt_run")
        LessonRunStore(folder.root).save("owner", module, run)
        val snapshot = folder.root.walkTopDown().single { it.name == "corrupt_run.json" }
        snapshot.writeText(snapshot.readText().replace("\"position\":0", "\"position\":99"))
        assertThrows(IllegalArgumentException::class.java) { LessonRunStore(folder.root).read("owner", module, "lesson_1") }
        assertThrows(IllegalArgumentException::class.java) { LessonRunStore(folder.root).pending("owner", module) }
        assertTrue(snapshot.exists())
        File(folder.root, "owner").deleteRecursively()
        java.nio.file.Files.createSymbolicLink(File(folder.root, "owner").toPath(), folder.newFolder("outside").toPath())
        assertThrows(IllegalArgumentException::class.java) { LessonRunStore(folder.root).save("owner", module, run) }
    }
    private fun completed(module: InteractiveModule, id: String): LessonRun {
        var run = LessonEngine.start(module, "lesson_1", 100, id)
        module.lessons.single { it.id == "lesson_1" }.steps.forEach { step ->
            if (step.questionId != null) run = LessonEngine.reveal(module, run, step.id, 101)
            run = LessonEngine.continueStep(module, run, step.id, 102)
        }
        return run
    }
    private fun operation(module: InteractiveModule, run: LessonRun, uid: String = "owner") = OperationRecord(
        uid, "original_operation", "set/lessonAttempts/${run.runId}",
        OperationJournal.payloadFingerprint(com.google.gson.Gson().toJson(LessonEngine.attempt(module, run))),
        OperationSyncState.UNKNOWN_AFTER_RESTART, 100, null, "writer", null, null)

    @Test fun exactHistoricalRecoveryKeepsNewerActiveRunAndRejectsChangedPayload() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val store = LessonRunStore(folder.root)
        val run = completed(module, "historical")
        store.save("owner", module, run)
        val newer = LessonEngine.start(module, "lesson_1", 700, "new_active")
        store.save("owner", module, newer)
        val original = operation(module, run)
        assertEquals(run, store.recoverAttempt("owner", module, original))
        assertEquals(newer, store.read("owner", module, "lesson_1"))
        assertThrows(IllegalArgumentException::class.java) {
            store.recoverAttempt("owner", module, original.copy(payloadSha256 = "0".repeat(64)))
        }
        assertEquals(run, store.get("owner", module, run.runId))
    }

    @Test fun missingForeignAndIncompleteRecoveryNeverManufacturesACompletion() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val store = LessonRunStore(folder.root)
        val run = completed(module, "missing")
        assertThrows(IllegalArgumentException::class.java) { store.recoverAttempt("owner", module, operation(module, run)) }
        store.save("owner", module, run)
        assertThrows(IllegalArgumentException::class.java) { store.recoverAttempt("other", module, operation(module, run)) }
        val unfinished = LessonEngine.start(module, "lesson_1", 700, "unfinished")
        store.save("owner", module, unfinished)
        assertThrows(IllegalArgumentException::class.java) {
            store.recoverAttempt("owner", module, operation(module, run).copy(relativePath = "set/lessonAttempts/unfinished"))
        }
        assertEquals(unfinished, store.read("owner", module, "lesson_1"))
    }

}
