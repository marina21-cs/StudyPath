package com.studypath.app.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class InteractiveLessonTest {
    @Test fun `authored package resolves references and preserves objective glossary`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        assertEquals("Test objective", module.objectives["obj_1"])
        assertEquals("Parts of a synthetic fixture.", module.glossary["parts"])
        assertEquals(5, module.lessons.single { it.id == "lesson_1" }.steps.size)
        assertEquals(4, module.questions.size)
    }
    @Test fun `wrong feedback retry and duplicate actions preserve first response and denominator`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        var run = LessonEngine.start(module, "lesson_1", 100, "run_test")
        run = LessonEngine.continueStep(module, run, "goal", 101)
        assertEquals(run, LessonEngine.continueStep(module, run, "goal", 102))
        run = LessonEngine.continueStep(module, run, "example", 103)
        assertEquals(run, LessonEngine.check(module, run, "choice", 104))
        run = LessonEngine.select(module, run, "choice", listOf("b"), 105)
        run = LessonEngine.check(module, run, "choice", 106)
        assertEquals(LessonAnswerStatus.INCORRECT, run.answers["q_1"]!!.status)
        assertEquals(run, LessonEngine.check(module, run, "choice", 107))
        assertEquals(run, LessonEngine.continueStep(module, run, "choice", 108))
        run = LessonEngine.retry(module, run, "choice", 109)
        run = LessonEngine.select(module, run, "choice", listOf("a"), 110)
        run = LessonEngine.check(module, run, "choice", 111)
        assertEquals(listOf("b"), run.answers["q_1"]!!.firstResponse)
        assertEquals(1, run.answers["q_1"]!!.wrongCount)
        assertEquals(LessonAnswerStatus.CORRECT, run.answers["q_1"]!!.status)
        run = LessonEngine.continueStep(module, run, "choice", 112)
        assertEquals(3, run.position)
        assertEquals(5, module.lessons.single { it.id == "lesson_1" }.steps.size)
    }
    @Test fun `ordering and numeric validators and assisted completion remain distinct`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        var run = LessonEngine.start(module, "lesson_1", 100, "run_assisted")
        run = LessonEngine.continueStep(module, run, "goal", 101)
        run = LessonEngine.continueStep(module, run, "example", 102)
        run = LessonEngine.reveal(module, run, "choice", 103)
        run = LessonEngine.continueStep(module, run, "choice", 104)
        run = LessonEngine.select(module, run, "ordering", listOf("second", "first"), 105)
        run = LessonEngine.check(module, run, "ordering", 106)
        assertEquals(LessonAnswerStatus.INCORRECT, run.answers["q_order"]!!.status)
        run = LessonEngine.retry(module, run, "ordering", 107)
        run = LessonEngine.select(module, run, "ordering", listOf("first", "second"), 108)
        run = LessonEngine.check(module, run, "ordering", 109)
        run = LessonEngine.continueStep(module, run, "ordering", 110)
        run = LessonEngine.select(module, run, "number", listOf("NaN"), 111)
        assertEquals(run, LessonEngine.check(module, run, "number", 111))
        run = LessonEngine.select(module, run, "number", listOf("3.04"), 112)
        run = LessonEngine.check(module, run, "number", 113)
        assertEquals(LessonAnswerStatus.CORRECT, run.answers["q_number"]!!.status)
        run = LessonEngine.continueStep(module, run, "number", 114)
        assertTrue(run.completed)
        assertEquals(run, LessonEngine.continueStep(module, run, "number", 115))
        val attempt = LessonEngine.attempt(module, run)
        assertEquals(1, attempt["assistedChecks"])
        assertEquals(1, attempt["firstTryCorrect"])
        assertEquals(1, attempt["incorrectChecks"])
        assertEquals(114L, attempt["completedAt"])
        assertEquals(attempt, LessonEngine.attempt(module, run))
        assertNotEquals(run.runId, LessonEngine.start(module, "lesson_1", 200).runId)
    }
    @Test fun `bounded validators freeze normalization and inclusive numeric tolerance`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val short = module.questions.getValue("q_review")
        assertTrue(LessonEngine.isCorrect(short, listOf(" A ")))
        assertFalse(LessonEngine.isCorrect(short, listOf("a b")))
        assertFalse(LessonEngine.isValidResponse(short, listOf("a".repeat(81))))
        val numeric = module.questions.getValue("q_number")
        assertTrue(LessonEngine.isCorrect(numeric, listOf("3.05")))
        assertFalse(LessonEngine.isCorrect(numeric, listOf("3.051")))
        assertFalse(LessonEngine.isValidResponse(numeric, listOf("3 parts")))
        assertFalse(LessonEngine.isValidResponse(numeric, listOf("Infinity")))
        assertFalse(LessonEngine.isValidResponse(numeric, listOf("1e999")))
        val ordering = module.questions.getValue("q_order")
        assertFalse(LessonEngine.isValidResponse(ordering, listOf("first", "first")))
    }
    @Test fun `authored review variants form a separate deterministic review lesson`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        val review = module.lessons.single { it.reviewOf == "lesson_1" }
        assertTrue(review.id.length <= 64)
        assertEquals(listOf(LessonStepKind.READING, LessonStepKind.EXAMPLE, LessonStepKind.CHECK), review.steps.map { it.kind })
        assertEquals(listOf("q_review"), review.steps.mapNotNull { it.questionId })
        assertEquals(review, InteractiveLessonParser.parse(LessonFixture.verified()).lessons.single { it.reviewOf == "lesson_1" })
        var run = LessonEngine.start(module, review.id, 100, "review_run")
        for (step in review.steps) {
            if (step.questionId != null) {
                run = LessonEngine.select(module, run, step.id, listOf("A"), 101)
                run = LessonEngine.check(module, run, step.id, 102)
            }
            run = LessonEngine.continueStep(module, run, step.id, 103)
        }
        assertTrue(run.completed)
        assertEquals(1, LessonEngine.attempt(module, run)["firstTryCorrect"])
    }
    @Test fun `backward device clock does not make an immutable completion server invalid`() {
        val module = InteractiveLessonParser.parse(LessonFixture.verified())
        var run = LessonEngine.start(module, "lesson_1", 1000, "clock_run")
        for (step in module.lessons.single { it.id == "lesson_1" }.steps) {
            if (step.questionId != null) run = LessonEngine.reveal(module, run, step.id, 500)
            run = LessonEngine.continueStep(module, run, step.id, 501)
        }
        assertEquals(1000L, run.completedAt)
        assertEquals(1000L, LessonEngine.attempt(module, run)["completedAt"])
        assertThrows(IllegalArgumentException::class.java) { LessonEngine.validate(module, run.copy(completedAt = 999)) }
    }
    @Test fun `strict schema rejects dangling references unknown fields and changed identity`() {
        val verified = LessonFixture.verified()
        val bad = verified.payload.deepCopy().apply { addProperty("extra", true) }
        assertThrows(IllegalArgumentException::class.java) { InteractiveLessonParser.parse(verified.copy(payload = bad)) }
        val badText = JsonParser.parseString(verified.textJson).asJsonObject.apply {
            getAsJsonArray("lessons")[0].asJsonObject.getAsJsonArray("steps")[2].asJsonObject.addProperty("questionId", "missing")
        }
        assertThrows(IllegalArgumentException::class.java) { InteractiveLessonParser.parse(LessonFixture.verified(badText.toString())) }
        val module = InteractiveLessonParser.parse(verified)
        val run = LessonEngine.start(module, "lesson_1", 100)
        assertThrows(IllegalArgumentException::class.java) { LessonEngine.validate(module, run.copy(packageSha256 = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { LessonEngine.validate(module, run.copy(position = 999)) }
    }
}
