package com.studypath.app.domain

enum class CompletionStatus { PASSED, CREDITED_EQUIVALENT, FAILED, IN_PROGRESS, INCOMPLETE, NOT_TAKEN, UNKNOWN }
enum class PrerequisiteState { MET, NOT_MET, UNKNOWN }

sealed interface Prerequisite {
    data class Course(val courseId: String) : Prerequisite
    data class AllOf(val children: List<Prerequisite>) : Prerequisite
    data class AnyOf(val children: List<Prerequisite>) : Prerequisite
    data class Unknown(val reason: String = "No approved prerequisite specification") : Prerequisite
}

data class PrerequisiteResult(val state: PrerequisiteState, val reasons: List<String>)

/** Evaluates recorded completion only; MET does not certify enrollment eligibility. */
object PrerequisiteEvaluator {
    fun evaluate(
        expression: Prerequisite,
        catalogCourseIds: Set<String>,
        completionByCourse: Map<String, CompletionStatus>,
    ): PrerequisiteResult = when (expression) {
        is Prerequisite.Unknown -> PrerequisiteResult(PrerequisiteState.UNKNOWN, listOf(expression.reason))
        is Prerequisite.Course -> evaluateCourse(expression.courseId, catalogCourseIds, completionByCourse)
        is Prerequisite.AllOf -> combine(expression.children, true, catalogCourseIds, completionByCourse)
        is Prerequisite.AnyOf -> combine(expression.children, false, catalogCourseIds, completionByCourse)
    }

    private fun evaluateCourse(id: String, catalog: Set<String>, records: Map<String, CompletionStatus>): PrerequisiteResult {
        if (id.isBlank() || id !in catalog) {
            return PrerequisiteResult(PrerequisiteState.UNKNOWN, listOf("Unknown catalog course reference: $id"))
        }
        return when (val status = records[id] ?: CompletionStatus.UNKNOWN) {
            CompletionStatus.PASSED, CompletionStatus.CREDITED_EQUIVALENT -> PrerequisiteResult(PrerequisiteState.MET, emptyList())
            CompletionStatus.UNKNOWN -> PrerequisiteResult(PrerequisiteState.UNKNOWN, listOf("$id completion is UNKNOWN"))
            else -> PrerequisiteResult(PrerequisiteState.NOT_MET, listOf("$id is $status; recorded passing completion is required"))
        }
    }

    private fun combine(children: List<Prerequisite>, all: Boolean, catalog: Set<String>,
                        records: Map<String, CompletionStatus>): PrerequisiteResult {
        if (children.isEmpty()) {
            return if (all) PrerequisiteResult(PrerequisiteState.MET, emptyList())
            else PrerequisiteResult(PrerequisiteState.UNKNOWN, listOf("Empty alternative list needs an approved rule"))
        }
        val results = children.map { evaluate(it, catalog, records) }
        val decisive = if (all) PrerequisiteState.NOT_MET else PrerequisiteState.MET
        val state = when {
            results.any { it.state == decisive } -> decisive
            results.any { it.state == PrerequisiteState.UNKNOWN } -> PrerequisiteState.UNKNOWN
            all -> PrerequisiteState.MET
            else -> PrerequisiteState.NOT_MET
        }
        return PrerequisiteResult(state, if (state == PrerequisiteState.MET) emptyList()
            else results.filter { it.state != PrerequisiteState.MET }.flatMap { it.reasons }.distinct())
    }
}
