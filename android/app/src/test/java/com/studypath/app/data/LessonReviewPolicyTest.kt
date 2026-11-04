package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class LessonReviewPolicyTest {
    private val day = 86_400_000L
    private val maximumTimestamp = 4_102_444_800_000L
    private fun module() = InteractiveLessonParser.parse(LessonFixture.verified())
    private fun original(module: InteractiveModule) = module.lessons.single { it.reviewOf == null }
    private fun review(module: InteractiveModule) = module.lessons.single { it.reviewOf == original(module).id }

    private fun completed(module: InteractiveModule, lessonId: String, runId: String, at: Long,
        assistance: Boolean = false, hint: Boolean = false, mistake: Boolean = false): LessonRun {
        var run = LessonEngine.start(module, lessonId, (at - 100).coerceAtLeast(1), runId)
        for (step in module.lessons.single { it.id == lessonId }.steps) {
            step.questionId?.let { id ->
                val question = module.questions.getValue(id)
                val answer = when (question.format) {
                    LessonQuestionFormat.SELECTED_RESPONSE, LessonQuestionFormat.ORDERING -> question.answer
                    LessonQuestionFormat.BOUNDED_RESPONSE -> listOf(if (question.responseType == "numeric") question.target.toString() else question.acceptedAnswers.first())
                }
                if (assistance) run = LessonEngine.reveal(module, run, step.id, at)
                else {
                    if (hint) run = LessonEngine.hint(module, run, step.id, at)
                    if (mistake) {
                        val wrong = when (question.format) {
                            LessonQuestionFormat.SELECTED_RESPONSE -> listOf(question.options.first { it.id != answer.single() }.id)
                            LessonQuestionFormat.ORDERING -> answer.reversed()
                            LessonQuestionFormat.BOUNDED_RESPONSE -> listOf(if (question.responseType == "numeric") "9999" else "incorrect")
                        }
                        run = LessonEngine.select(module, run, step.id, wrong, at)
                        run = LessonEngine.check(module, run, step.id, at)
                        run = LessonEngine.retry(module, run, step.id, at)
                    }
                    run = LessonEngine.select(module, run, step.id, answer, at)
                    run = LessonEngine.check(module, run, step.id, at)
                }
            }
            run = LessonEngine.continueStep(module, run, step.id, at)
        }
        assertTrue(run.completed)
        return run
    }

    @Test fun `schedule is absent until the original lesson is complete`() {
        val module = module(); val original = original(module)
        assertNull(LessonReviewPolicy.schedule(module, original.id, emptyList()))
        val draft = LessonEngine.start(module, original.id, 100, "draft")
        assertNull(LessonReviewPolicy.schedule(module, original.id, listOf(draft)))
        val orphanReview = completed(module, review(module).id, "orphan", 200)
        assertNull(LessonReviewPolicy.schedule(module, original.id, listOf(orphanReview)))
    }

    @Test fun `original completion schedules one elapsed day and identifies its authored review`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val schedule = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(base)))
        assertEquals(1000 + day, schedule.dueAt)
        assertEquals(0, schedule.stage); assertEquals("base", schedule.lastEventId)
        assertEquals(1, schedule.policyVersion); assertEquals(review(module).id, schedule.reviewLessonId)
    }

    @Test fun `independent authored reviews advance through one three seven days and cap stage two`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val one = completed(module, review(module).id, "one", 1000 + day)
        val two = completed(module, review(module).id, "two", 1000 + 4 * day)
        val three = completed(module, review(module).id, "three", 1000 + 11 * day)
        val first = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, one)))
        assertEquals(1, first.stage); assertEquals(one.completedAt!! + 3 * day, first.dueAt)
        val second = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, one, two)))
        assertEquals(2, second.stage); assertEquals(two.completedAt!! + 7 * day, second.dueAt)
        val capped = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, one, two, three)))
        assertEquals(2, capped.stage); assertEquals(three.completedAt!! + 7 * day, capped.dueAt)
    }

    @Test fun `wrong first responses hints and revealed answers reset to next day without mastery claims`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val one = completed(module, review(module).id, "one", 2000)
        val two = completed(module, review(module).id, "two", 3000)
        val difficult = listOf(
            completed(module, review(module).id, "assisted", 4000, assistance = true),
            completed(module, review(module).id, "hinted", 4000, hint = true),
            completed(module, review(module).id, "retried", 4000, mistake = true))
        difficult.forEach { event ->
            val result = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, one, two, event)))
            assertEquals(0, result.stage); assertEquals(4000 + day, result.dueAt); assertEquals(event.runId, result.lastEventId)
        }
    }

    @Test fun `same event replay after restart and repeated sync advances exactly once`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val event = completed(module, review(module).id, "review_event", 2000)
        val expected = LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, event))
        val replayed = LessonReviewPolicy.schedule(module, base.lessonId, listOf(event.copy(), base, event, base.copy(), event.copy()))
        assertEquals(expected, replayed); assertEquals(1, replayed!!.stage)
    }

    @Test fun `conflicting event identifiers reject the complete history`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val first = completed(module, review(module).id, "same_event", 2000)
        val conflicting = completed(module, review(module).id, "same_event", 3000)
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, first, conflicting)) }
    }

    @Test fun `foreign curriculum module version or package hashes are never folded into a schedule`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        listOf(base.copy(curriculumVersion = "other"), base.copy(moduleId = "other"), base.copy(version = 2), base.copy(packageSha256 = "0".repeat(64))).forEach { foreign ->
            assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.schedule(module, base.lessonId, listOf(foreign)) }
        }
        val newer = module.copy(summary = module.summary.copy(version = 2, packageSha256 = "0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.schedule(newer, base.lessonId, listOf(base)) }
        assertEquals(0, LessonReviewPolicy.schedule(module, base.lessonId, listOf(base))!!.stage)
    }

    @Test fun `backward device clock keeps original anchor and each event deterministic`() {
        val module = module(); val base = completed(module, original(module).id, "base", 5000)
        val earlierClock = completed(module, review(module).id, "rewound_review", 2000)
        val result = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(earlierClock, base)))
        assertEquals(5000 + 3 * day, result.dueAt); assertEquals(1, result.stage)
        assertEquals("rewound_review", result.lastEventId)
        assertEquals(result, LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, earlierClock)))
    }

    @Test fun `equal timestamps use event identifier ordering and ignore original replays`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val difficulty = completed(module, review(module).id, "a_difficulty", 2000, assistance = true)
        val success = completed(module, review(module).id, "z_success", 2000)
        val replay = completed(module, original(module).id, "original_replay", 3000)
        val result = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(replay, success, base, difficulty)))
        assertEquals(1, result.stage); assertEquals(2000 + 3 * day, result.dueAt); assertEquals(success.runId, result.lastEventId)
    }

    @Test fun `timezone changes cannot alter elapsed day due times`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1_800_000_000_000)
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val before = LessonReviewPolicy.schedule(module, base.lessonId, listOf(base))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(before, LessonReviewPolicy.schedule(module, base.lessonId, listOf(base)))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun `timestamp arithmetic saturates at the accepted timestamp bound`() {
        val module = module(); val base = completed(module, original(module).id, "base", maximumTimestamp - 100)
        val result = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, listOf(base)))
        assertEquals(maximumTimestamp, result.dueAt)
        val event = completed(module, review(module).id, "late", maximumTimestamp)
        assertEquals(maximumTimestamp, LessonReviewPolicy.schedule(module, base.lessonId, listOf(base, event))!!.dueAt)
    }

    @Test fun `latest assisted review resets next day after clock rewind and independent reviews`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val one = completed(module, review(module).id, "one", 2000)
        val two = completed(module, review(module).id, "two", 3000)
        val history = listOf(base, one, two)
        val logicalStart = LessonReviewPolicy.nextRunStartedAt(module, history, 500)
        assertEquals(3001L, logicalStart)
        var latest = LessonEngine.start(module, review(module).id, logicalStart, "latest_assisted")
        for (step in review(module).steps) {
            if (step.questionId != null) latest = LessonEngine.reveal(module, latest, step.id, 600)
            latest = LessonEngine.continueStep(module, latest, step.id, 601)
        }
        val result = requireNotNull(LessonReviewPolicy.schedule(module, base.lessonId, history + latest))
        assertEquals(0, result.stage)
        assertEquals(latest.completedAt!! + day, result.dueAt)
        assertEquals(latest.runId, result.lastEventId)
        assertEquals(result, LessonReviewPolicy.schedule(module, base.lessonId, (history + latest + latest).reversed()))
    }

    @Test fun `new run clock validates history and bounds before assigning monotonic time`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        assertEquals(2000L, LessonReviewPolicy.nextRunStartedAt(module, listOf(base), 2000))
        assertEquals(1001L, LessonReviewPolicy.nextRunStartedAt(module, listOf(base), 500))
        assertEquals(500L, LessonReviewPolicy.nextRunStartedAt(module, emptyList(), 500))
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.nextRunStartedAt(module, listOf(base.copy(version = 2)), 500) }
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.nextRunStartedAt(module, emptyList(), 0) }
        val maximum = completed(module, original(module).id, "maximum", maximumTimestamp)
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.nextRunStartedAt(module, listOf(maximum), 500) }
    }

    @Test fun `unknown originals and review identifiers are not accepted as original lesson targets`() {
        val module = module()
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.schedule(module, "unknown", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { LessonReviewPolicy.schedule(module, review(module).id, emptyList()) }
    }

    @Test fun `unavailable authored review content does not create a schedule`() {
        val module = module(); val base = completed(module, original(module).id, "base", 1000)
        val withoutReview = module.copy(lessons = module.lessons.filter { it.reviewOf == null })
        assertNull(LessonReviewPolicy.schedule(withoutReview, base.lessonId, listOf(base)))
    }
}
