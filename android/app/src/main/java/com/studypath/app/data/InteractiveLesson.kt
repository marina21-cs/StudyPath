package com.studypath.app.data

import java.util.UUID
import java.util.Locale
import java.math.BigDecimal
import java.security.MessageDigest
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

enum class LessonStepKind { READING, EXAMPLE, CHECK }
enum class LessonQuestionFormat { SELECTED_RESPONSE, ORDERING, BOUNDED_RESPONSE }
enum class LessonAnswerStatus { AWAITING, CORRECT, INCORRECT, ASSISTED }
data class LessonOption(val id: String, val text: String, val feedback: String)
data class LessonStep(val id: String, val kind: LessonStepKind, val title: String, val body: String, val questionId: String? = null)
data class InteractiveLesson(val id: String, val title: String, val steps: List<LessonStep>, val reviewOf: String? = null)
data class LessonQuestion(val id: String, val prompt: String, val format: LessonQuestionFormat,
    val options: List<LessonOption> = emptyList(), val answer: List<String> = emptyList(), val hint: String,
    val explanation: String, val responseType: String? = null, val acceptedAnswers: List<String> = emptyList(),
    val caseSensitive: Boolean = false, val unit: String = "", val target: Double? = null, val tolerance: Double? = null)
data class InteractiveModule(val summary: PublishedModuleSummary, val objectives: Map<String, String>,
    val lessons: List<InteractiveLesson>, val questions: Map<String, LessonQuestion>, val glossary: Map<String, String>)
data class LessonAnswerState(val draft: List<String> = emptyList(), val firstResponse: List<String>? = null,
    val status: LessonAnswerStatus = LessonAnswerStatus.AWAITING, val wrongCount: Int = 0,
    val hintUsed: Boolean = false, val assisted: Boolean = false)
data class LessonRun(val runId: String, val curriculumVersion: String, val moduleId: String, val version: Int,
    val packageSha256: String, val lessonId: String, val startedAt: Long, val updatedAt: Long,
    val position: Int = 0, val answers: Map<String, LessonAnswerState> = emptyMap(), val completedAt: Long? = null, val revision: Int = 0) {
    val completed get() = completedAt != null
}
/** B11 semantics are checked after B10 verifies the immutable transport bytes. */
object InteractiveLessonParser {
    fun parse(verified: VerifiedTextPackage): InteractiveModule {
        val checked = TextPackageVerifier.verify(verified.summary, verified.manifestJson, verified.textJson)
        require(checked.payload == verified.payload) { "Payload differs from retained bytes." }
        val payload = checked.payload
        fields(payload, setOf("schemaVersion", "moduleId", "version", "lessons", "questions", "glossary"))
        require(TextPackageJson.integer(payload["schemaVersion"]) == 1L)
        val manifest = TextPackageJson.objectValue(checked.manifestJson)
        val objectives = array(manifest, "objectives", 1, 100).associate { entry ->
            fields(entry, setOf("id", "description")); key(entry) to text(entry, "description", 500)
        }
        require(objectives.size == manifest["objectives"].asJsonArray.size())
        val conceptIds = array(manifest, "concepts", 1, 100).map { key(it) }.also { unique(it) }.toSet()
        val questions = array(payload, "questions", 1, 500).map(::question).also { unique(it.map { q -> q.id }) }.associateBy { it.id }
        val metadata = array(manifest, "questions", 1, 500).associate { entry ->
            fields(entry, setOf("id", "objectiveId", "conceptIds", "format"))
            require(text(entry, "objectiveId", 64) in objectives)
            require(strings(entry, "conceptIds", 1, 100).all { it in conceptIds })
            key(entry) to text(entry, "format", 40)
        }
        require(metadata.keys == questions.keys && metadata.size == manifest["questions"].asJsonArray.size())
        questions.forEach { (id, q) -> require(metadata[id] == q.format.name.lowercase(Locale.ROOT)) }
        val lessons = array(payload, "lessons", 1, 50).map { lesson ->
            fields(lesson, setOf("id", "title", "steps"))
            val steps = array(lesson, "steps", 1, 1000).map { step ->
                val kind = LessonStepKind.valueOf(text(step, "kind", 20))
                fields(step, setOf("id", "kind", "title", "body") + if (kind == LessonStepKind.CHECK) setOf("questionId") else emptySet())
                LessonStep(key(step), kind, text(step, "title", 160), text(step, "body", 8000),
                    if (kind == LessonStepKind.CHECK) text(step, "questionId", 64).also { require(it in questions) } else null)
            }
            unique(steps.map { it.id })
            require(steps.any { it.kind == LessonStepKind.READING } && steps.any { it.kind == LessonStepKind.EXAMPLE } && steps.count { it.kind == LessonStepKind.CHECK } >= 2)
            InteractiveLesson(key(lesson), text(lesson, "title", 160), steps)
        }.also { unique(it.map { lesson -> lesson.id }); unique(it.flatMap { lesson -> lesson.steps.map { step -> step.id } }) }
        val primary = mutableListOf<String>(); val review = mutableListOf<String>()
        val inventories = array(manifest, "lessons", 1, 50)
        require(inventories.size == lessons.size)
        inventories.zip(lessons).forEach { (inventory, lesson) ->
            fields(inventory, setOf("id", "objectiveIds", "stepIds", "questionIds", "reviewQuestionIds", "conceptIds"))
            require(key(inventory) == lesson.id && strings(inventory, "stepIds", 1, 1000) == lesson.steps.map { it.id })
            require(strings(inventory, "objectiveIds", 1, 100).all { it in objectives })
            require(strings(inventory, "conceptIds", 1, 100).all { it in conceptIds })
            val checks = strings(inventory, "questionIds", 1, 500)
            require(checks == lesson.steps.mapNotNull { it.questionId })
            primary.addAll(checks); review.addAll(strings(inventory, "reviewQuestionIds", 1, 500))
        }
        unique(primary + review); require((primary + review).toSet() == questions.keys)
        val glossary = array(payload, "glossary", 1, 100).associate { entry ->
            fields(entry, setOf("term", "definition")); text(entry, "term", 80) to text(entry, "definition", 1000)
        }
        require(glossary.size == payload["glossary"].asJsonArray.size())
        val reviewLessons = inventories.zip(lessons).map { (inventory, original) ->
            val reviewId = "review_" + MessageDigest.getInstance("SHA-256").digest(TextPackageJson.utf8(original.id))
                .joinToString("") { "%02x".format(it) }.take(24)
            val recap = original.steps.first { it.kind == LessonStepKind.READING }.copy(id = "${reviewId}_goal")
            val example = original.steps.first { it.kind == LessonStepKind.EXAMPLE }.copy(id = "${reviewId}_example")
            val checks = strings(inventory, "reviewQuestionIds", 1, 500).mapIndexed { index, questionId ->
                LessonStep("${reviewId}_q${index + 1}", LessonStepKind.CHECK, "Review check ${index + 1}",
                    "Apply the idea independently.", questionId)
            }
            InteractiveLesson(reviewId, "Review: ${original.title}", listOf(recap, example) + checks, original.id)
        }
        unique((lessons + reviewLessons).map { it.id })
        unique((lessons + reviewLessons).flatMap { it.steps.map { step -> step.id } })
        return InteractiveModule(checked.summary, objectives, lessons + reviewLessons, questions, glossary)
    }

    private fun question(entry: JsonObject): LessonQuestion {
        val common = setOf("id", "prompt", "format", "hint", "explanation")
        val format = when (text(entry, "format", 40)) {
            "selected_response" -> LessonQuestionFormat.SELECTED_RESPONSE
            "ordering" -> LessonQuestionFormat.ORDERING
            "bounded_response" -> LessonQuestionFormat.BOUNDED_RESPONSE
            else -> throw IllegalArgumentException("Unsupported question format.")
        }
        val id = key(entry); val prompt = text(entry, "prompt", 1000)
        val hint = text(entry, "hint", 1000); val explanation = text(entry, "explanation", 2000)
        if (format != LessonQuestionFormat.BOUNDED_RESPONSE) {
            fields(entry, common + setOf("options", "answer"))
            val options = array(entry, "options", 2, 12).map { option ->
                fields(option, setOf("id", "text", "feedback"))
                LessonOption(key(option), text(option, "text", 500), text(option, "feedback", 1000))
            }.also { unique(it.map { o -> o.id }) }
            val answer = strings(entry, "answer", 1, 12)
            require(answer.all { it in options.map { o -> o.id } })
            require(if (format == LessonQuestionFormat.SELECTED_RESPONSE) answer.size == 1 else answer.toSet() == options.map { it.id }.toSet())
            return LessonQuestion(id, prompt, format, options, answer, hint, explanation)
        }
        val responseType = text(entry, "responseType", 20)
        val unit = TextPackageJson.string(entry["unit"]).also { require(it.length <= 40) }
        return when (responseType) {
            "numeric" -> {
                fields(entry, common + setOf("responseType", "unit", "target", "tolerance"))
                val target = number(entry, "target"); val tolerance = number(entry, "tolerance")
                require(target in -1e9..1e9 && tolerance in 0.0..1e6)
                LessonQuestion(id, prompt, format, hint = hint, explanation = explanation, responseType = responseType, unit = unit, target = target, tolerance = tolerance)
            }
            "short" -> {
                fields(entry, common + setOf("responseType", "unit", "acceptedAnswers", "caseSensitive"))
                require(entry["caseSensitive"].isJsonPrimitive && entry["caseSensitive"].asJsonPrimitive.isBoolean)
                val caseSensitive = entry["caseSensitive"].asBoolean
                val answers = strings(entry, "acceptedAnswers", 1, 20, 80)
                unique(answers.map { if (caseSensitive) it.trim() else it.trim().lowercase(Locale.ROOT) })
                LessonQuestion(id, prompt, format, hint = hint, explanation = explanation, responseType = responseType,
                    unit = unit, acceptedAnswers = answers, caseSensitive = caseSensitive)
            }
            else -> throw IllegalArgumentException("Unsupported bounded response validator.")
        }
    }
    private fun number(o: JsonObject, field: String): Double {
        require(o[field].isJsonPrimitive && o[field].asJsonPrimitive.isNumber)
        return o[field].asDouble.also { require(it.isFinite()) }
    }
    private fun fields(o: JsonObject, expected: Set<String>) { require(o.keySet() == expected) { "Unexpected or missing instructional fields." } }
    private fun text(o: JsonObject, field: String, max: Int) = TextPackageJson.string(o[field]).also { require(it.isNotBlank() && it.length <= max) }
    private fun key(o: JsonObject) = text(o, "id", 64).also(DatabaseRecords::requireKey)
    private fun array(o: JsonObject, field: String, min: Int, max: Int): List<JsonObject> {
        require(o[field].isJsonArray && o[field].asJsonArray.size() in min..max)
        return o[field].asJsonArray.map { require(it.isJsonObject); it.asJsonObject }
    }
    private fun strings(o: JsonObject, field: String, min: Int, max: Int, length: Int = 64): List<String> {
        require(o[field].isJsonArray && o[field].asJsonArray.size() in min..max)
        return o[field].asJsonArray.map { TextPackageJson.string(it).also { s -> require(s.isNotBlank() && s.length <= length) } }.also(::unique)
    }
    private fun unique(values: List<String>) { require(values.size == values.toSet().size) { "Repeated instructional identity or answer." } }
}

/** Pure transitions. Callers durably save the returned state before rendering feedback or motion. */
object LessonEngine {
    private const val maxTimestamp = 4_102_444_800_000L
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    fun start(module: InteractiveModule, lessonId: String, now: Long, runId: String = UUID.randomUUID().toString()): LessonRun {
        val lesson = module.lessons.single { it.id == lessonId }; val s = module.summary
        return LessonRun(runId, s.curriculumVersion, s.moduleId, s.version, s.packageSha256, lessonId, now, now,
            answers = lesson.steps.mapNotNull { it.questionId }.associateWith { LessonAnswerState() }).also { validate(module, it) }
    }
    fun currentStep(module: InteractiveModule, run: LessonRun): LessonStep? {
        validate(module, run)
        return module.lessons.single { it.id == run.lessonId }.steps.getOrNull(run.position)
    }
    fun validate(module: InteractiveModule, run: LessonRun) {
        val s = module.summary
        DatabaseRecords.requireKey(run.runId)
        require(run.curriculumVersion == s.curriculumVersion && run.moduleId == s.moduleId && run.version == s.version && run.packageSha256 == s.packageSha256) { "Run belongs to another immutable package." }
        require(run.startedAt in 1..maxTimestamp && run.updatedAt in 1..maxTimestamp && run.revision in 0..1_000_000)
        val steps = module.lessons.singleOrNull { it.id == run.lessonId }?.steps ?: throw IllegalArgumentException("Unknown lesson.")
        require(run.position in 0..steps.size && run.completed == (run.position == steps.size))
        require(run.completedAt == null || run.completedAt in run.startedAt..maxTimestamp)
        require(run.answers.keys == steps.mapNotNull { it.questionId }.toSet())
        steps.forEachIndexed { index, step -> step.questionId?.let { id ->
            val q = requireNotNull(module.questions[id]); val a = requireNotNull(run.answers[id])
            require(a.wrongCount in 0..10000 && draftValid(q, a.draft))
            require(a.firstResponse == null || isValidResponse(q, a.firstResponse))
            require(a.wrongCount == 0 || a.firstResponse != null)
            when (a.status) {
                LessonAnswerStatus.AWAITING -> require(!a.assisted)
                LessonAnswerStatus.CORRECT -> require(!a.assisted && a.firstResponse != null && isCorrect(q, a.draft))
                LessonAnswerStatus.INCORRECT -> require(!a.assisted && a.firstResponse != null && a.wrongCount > 0 && isValidResponse(q, a.draft) && !isCorrect(q, a.draft))
                LessonAnswerStatus.ASSISTED -> require(a.assisted && a.hintUsed)
            }
            if (index < run.position) require(a.status in setOf(LessonAnswerStatus.CORRECT, LessonAnswerStatus.ASSISTED))
            if (index > run.position) require(a == LessonAnswerState())
        } }
    }
    fun isValidResponse(question: LessonQuestion, response: List<String>): Boolean = when (question.format) {
        LessonQuestionFormat.SELECTED_RESPONSE -> response.size == 1 && response.single() in question.options.map { it.id }
        LessonQuestionFormat.ORDERING -> response.size == question.options.size && response.toSet() == question.options.map { it.id }.toSet()
        LessonQuestionFormat.BOUNDED_RESPONSE -> response.size == 1 && response.single().length <= 80 && response.single().isNotBlank() &&
            (question.responseType == "short" || numeric(response.single()) != null)
    }
    fun isCorrect(question: LessonQuestion, response: List<String>): Boolean {
        if (!isValidResponse(question, response)) return false
        return when (question.format) {
            LessonQuestionFormat.SELECTED_RESPONSE, LessonQuestionFormat.ORDERING -> response == question.answer
            LessonQuestionFormat.BOUNDED_RESPONSE -> if (question.responseType == "numeric") {
                val delta = requireNotNull(numeric(response.single())).subtract(BigDecimal.valueOf(requireNotNull(question.target))).abs()
                delta <= BigDecimal.valueOf(requireNotNull(question.tolerance))
            } else {
                fun normalize(value: String) = value.trim().let { if (question.caseSensitive) it else it.lowercase(Locale.ROOT) }
                normalize(response.single()) in question.acceptedAnswers.map(::normalize)
            }
        }
    }
    fun select(module: InteractiveModule, run: LessonRun, stepId: String, response: List<String>, now: Long): LessonRun = answerAction(module, run, stepId, now) { q, a ->
        if (a.status != LessonAnswerStatus.AWAITING || !draftValid(q, response)) a else a.copy(draft = response.toList())
    }
    fun check(module: InteractiveModule, run: LessonRun, stepId: String, now: Long): LessonRun = answerAction(module, run, stepId, now) { q, a ->
        if (a.status != LessonAnswerStatus.AWAITING || !isValidResponse(q, a.draft)) a else {
            val correct = isCorrect(q, a.draft)
            a.copy(firstResponse = a.firstResponse ?: a.draft.toList(), status = if (correct) LessonAnswerStatus.CORRECT else LessonAnswerStatus.INCORRECT,
                wrongCount = a.wrongCount + if (correct) 0 else 1)
        }
    }
    fun retry(module: InteractiveModule, run: LessonRun, stepId: String, now: Long): LessonRun = answerAction(module, run, stepId, now) { _, a ->
        if (a.status == LessonAnswerStatus.INCORRECT) a.copy(status = LessonAnswerStatus.AWAITING) else a
    }
    fun hint(module: InteractiveModule, run: LessonRun, stepId: String, now: Long): LessonRun = answerAction(module, run, stepId, now) { _, a ->
        if (a.status in setOf(LessonAnswerStatus.AWAITING, LessonAnswerStatus.INCORRECT)) a.copy(hintUsed = true) else a
    }
    fun reveal(module: InteractiveModule, run: LessonRun, stepId: String, now: Long): LessonRun = answerAction(module, run, stepId, now) { _, a ->
        if (a.status in setOf(LessonAnswerStatus.AWAITING, LessonAnswerStatus.INCORRECT)) a.copy(status = LessonAnswerStatus.ASSISTED, assisted = true, hintUsed = true) else a
    }
    fun continueStep(module: InteractiveModule, run: LessonRun, stepId: String, now: Long): LessonRun {
        val step = currentStep(module, run) ?: return run
        if (step.id != stepId) return run
        if (step.questionId != null && run.answers.getValue(step.questionId).status !in setOf(LessonAnswerStatus.CORRECT, LessonAnswerStatus.ASSISTED)) return run
        timestamp(now)
        val next = run.position + 1
        return run.copy(position = next, updatedAt = now, completedAt = if (next == module.lessons.single { it.id == run.lessonId }.steps.size) maxOf(now, run.startedAt) else null,
            revision = run.revision + 1).also { validate(module, it) }
    }
    fun attempt(module: InteractiveModule, run: LessonRun): Map<String, Any> {
        validate(module, run); require(run.completed) { "Finish the lesson before preparing an immutable attempt." }
        val steps = module.lessons.single { it.id == run.lessonId }.steps
        val states = run.answers.values
        val responsesJson = gson.toJson(run.answers.toSortedMap())
        require(responsesJson.length <= 60000)
        return linkedMapOf("schemaVersion" to 1, "curriculumVersion" to run.curriculumVersion, "moduleId" to run.moduleId,
            "version" to run.version, "packageSha256" to run.packageSha256, "lessonId" to run.lessonId, "runId" to run.runId,
            "startedAt" to run.startedAt, "completedAt" to requireNotNull(run.completedAt), "totalSteps" to steps.size,
            "resolvedSteps" to run.position, "totalChecks" to states.size,
            "firstTryCorrect" to states.count { it.status == LessonAnswerStatus.CORRECT && it.wrongCount == 0 && !it.hintUsed && !it.assisted },
            "assistedChecks" to states.count { it.assisted }, "incorrectChecks" to states.count { it.wrongCount > 0 }, "responsesJson" to responsesJson)
    }
    private fun answerAction(module: InteractiveModule, run: LessonRun, stepId: String, now: Long, change: (LessonQuestion, LessonAnswerState) -> LessonAnswerState): LessonRun {
        val step = currentStep(module, run) ?: return run
        if (step.id != stepId || step.questionId == null) return run
        val old = run.answers.getValue(step.questionId)
        val changed = change(module.questions.getValue(step.questionId), old)
        if (old == changed) return run
        timestamp(now)
        return run.copy(answers = run.answers + (step.questionId to changed), updatedAt = now, revision = run.revision + 1).also { validate(module, it) }
    }
    private fun draftValid(q: LessonQuestion, response: List<String>) = response.isEmpty() ||
        if (q.format == LessonQuestionFormat.BOUNDED_RESPONSE) response.size == 1 && response.single().length <= 80 else isValidResponse(q, response)
    private fun numeric(value: String): BigDecimal? = value.trim().takeIf { it.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?")) }
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }?.takeIf { it.abs() <= BigDecimal("1000000000") }
    private fun timestamp(now: Long) { require(now in 1..maxTimestamp) }
}
