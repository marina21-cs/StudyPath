package com.studypath.app.data

data class LessonReviewSchedule(
    val dueAt: Long,
    val stage: Int,
    val lastEventId: String,
    val reviewLessonId: String,
    val policyVersion: Int = 1,
)

/** Recomputed from immutable UID-owned history; due state is advisory, never a mastery claim. */
object LessonReviewPolicy {
    private const val dayMillis = 86_400_000L
    private const val maximumTimestamp = 4_102_444_800_000L

    /** Preserve local event order when a new run starts after the device clock moves backward. */
    fun nextRunStartedAt(module: InteractiveModule, history: List<LessonRun>, now: Long): Long {
        require(now in 1..maximumTimestamp) { "The device date is outside the supported practice timeline." }
        val completed = validatedHistory(module, history).values.filter { it.completed }
        val latest = completed.maxOfOrNull { requireNotNull(it.completedAt) } ?: return now
        require(latest < maximumTimestamp) { "The saved practice timeline has reached its supported date limit. Existing runs were retained." }
        return maxOf(now, latest + 1)
    }

    fun schedule(module: InteractiveModule, lessonId: String, history: List<LessonRun>): LessonReviewSchedule? {
        val original = requireNotNull(module.lessons.singleOrNull { it.id == lessonId && it.reviewOf == null }) {
            "Choose an original lesson to calculate its private review schedule."
        }
        val unique = validatedHistory(module, history)
        val reviews = module.lessons.filter { it.reviewOf == original.id }
        require(reviews.size <= 1) { "An original lesson must have one deterministic authored review." }
        val review = reviews.singleOrNull() ?: return null
        val ordering = compareBy<LessonRun> { requireNotNull(it.completedAt) }.thenBy { it.runId }
        val anchor = unique.values.filter { it.completed && it.lessonId == original.id }.minWithOrNull(ordering) ?: return null
        var logicalTime = requireNotNull(anchor.completedAt)
        var stage = 0
        var lastEventId = anchor.runId
        unique.values.filter { it.completed && it.lessonId == review.id }.sortedWith(ordering).forEach { event ->
            // A fresh run may start after a device clock rewind. Keep its original prerequisite anchor.
            logicalTime = maxOf(logicalTime, requireNotNull(event.completedAt))
            val independent = event.answers.isNotEmpty() && event.answers.all { (questionId, answer) ->
                answer.status == LessonAnswerStatus.CORRECT && answer.wrongCount == 0 && !answer.hintUsed && !answer.assisted &&
                    answer.firstResponse?.let { LessonEngine.isCorrect(module.questions.getValue(questionId), it) } == true
            }
            stage = if (independent) (stage + 1).coerceAtMost(2) else 0
            lastEventId = event.runId
        }
        val interval = when (stage) { 0 -> dayMillis; 1 -> 3 * dayMillis; else -> 7 * dayMillis }
        val dueAt = if (logicalTime > maximumTimestamp - interval) maximumTimestamp else logicalTime + interval
        return LessonReviewSchedule(dueAt, stage, lastEventId, review.id)
    }

    private fun validatedHistory(module: InteractiveModule, history: List<LessonRun>): Map<String, LessonRun> {
        val unique = linkedMapOf<String, LessonRun>()
        history.forEach { run ->
            LessonEngine.validate(module, run)
            val existing = unique.putIfAbsent(run.runId, run)
            require(existing == null || existing == run) { "A review event ID has conflicting immutable history." }
        }
        return unique
    }
}
