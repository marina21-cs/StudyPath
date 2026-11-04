package com.studypath.app.ui.planner

import com.studypath.app.data.CatalogCourse
import com.studypath.app.data.CourseAttempt
import com.studypath.app.data.ScoreRecord
import com.studypath.app.data.AcademicCapability
import com.studypath.app.domain.*
import java.security.MessageDigest

data class PlannedAssessmentState(
    val categoryId: String,
    val title: String,
    val assessment: Assessment,
    val record: ScoreRecord?,
)

data class PlannerEvaluation(
    val summary: GradeSummary?,
    val assessments: List<PlannedAssessmentState>,
    val fingerprint: String,
    val warnings: List<String>,
)

/** Joins a versioned complete plan to actual records. It never invents a future assessment plan. */
object PlannerAdapter {
    fun evaluate(course: CatalogCourse, allScores: List<ScoreRecord>, access: AcademicAccess? = null): PlannerEvaluation {
        if (!course.gradePlan.let { it != null && it.verified && access?.allows(course.id, AcademicCapability.SCORES, it.version) == true }) {
            return PlannerEvaluation(null, emptyList(), hash(listOf(course.id, "UNAVAILABLE_POLICY")),
                listOf(access?.reason?.takeIf { access?.allowed == false }
                    ?: "Academic planning is unavailable without a separately reviewed matching curriculum and assessment policy."))
        }
        val catalogPlan = course.gradePlan ?: return PlannerEvaluation(null, emptyList(),
            hash(listOf(course.id, "NO_PLAN")), listOf("No grading plan is available. Exact projections are unavailable."))
        val courseScores = allScores.filter { it.courseId == course.id }
        val resolved = AcademicPlanFingerprint.resolve(course, allScores, requireNotNull(access))
        val rows = resolved.categories.flatMap { category -> category.assessments.map { assessment ->
            val title = catalogPlan.categories.single { it.id == category.id }.assessments.single { it.id == assessment.id }.title
            PlannedAssessmentState(category.id, title, assessment, resolved.records[category.id to assessment.id])
        } }
        val missingWork = rows.any { it.assessment.status == AssessmentStatus.MISSING }
        val warnings = buildList {
            if (missingWork) add("Grade and target results are unavailable while missing work has no explicitly reviewed treatment. Missing is neither ungraded nor an assumed zero.")
            if (!catalogPlan.assessmentPlanKnown) add("The full assessment plan is unknown. Only a graded-work snapshot can be shown.")
            val older = courseScores.size - resolved.records.size
            if (older > 0) add("$older score record(s) have a different plan version or policy binding. They are retained and excluded from this calculation.")
        }
        return PlannerEvaluation(resolved.summary, rows, resolved.fingerprint, warnings)
    }

    fun scoreId(courseId: String, planVersion: String, categoryId: String, assessmentId: String, policyIdentity: String? = null): String =
        "score_" + hash(listOf(courseId, planVersion, categoryId, assessmentId) + listOfNotNull(policyIdentity)).take(48)

    fun completionId(courseId: String, policyIdentity: String? = null): String = "completion_" + hash(listOf(courseId) + listOfNotNull(policyIdentity)).take(40)

    /** Conflicting attempts need review; no university repeat/replacement policy is assumed. */
    fun completionStates(attempts: List<CourseAttempt>): Map<String, CompletionStatus> = attempts.groupBy { it.courseId }
        .mapValues { (_, rows) ->
            val states = rows.map { row ->
                when (row.status) {
                    "IN_PROGRESS" -> CompletionStatus.IN_PROGRESS
                    "INCOMPLETE" -> CompletionStatus.INCOMPLETE
                    "NOT_TAKEN" -> CompletionStatus.NOT_TAKEN
                    "COMPLETED" -> when (row.completionStatus) {
                        "PASSED" -> CompletionStatus.PASSED
                        "FAILED" -> CompletionStatus.FAILED
                        "CREDITED_EQUIVALENT" -> CompletionStatus.CREDITED_EQUIVALENT
                        else -> CompletionStatus.UNKNOWN
                    }
                    else -> CompletionStatus.UNKNOWN
                }
            }.distinct()
            states.singleOrNull() ?: CompletionStatus.UNKNOWN
        }

    fun prerequisites(course: CatalogCourse, catalog: List<CatalogCourse>, attempts: List<CourseAttempt>,
                      access: AcademicAccess? = null): PrerequisiteResult {
        val expression = prerequisiteTree(course, catalog, access)
        return PrerequisiteEvaluator.evaluate(expression, catalog.map { it.id }.toSet(),
            completionStates(attempts.filter { access?.matchesAttempt(it.policyBinding, it.courseId) == true }))
    }

    /** The map and summary share the same bounded catalog validation and policy gate. */
    fun prerequisiteTree(course: CatalogCourse, catalog: List<CatalogCourse>, access: AcademicAccess?): Prerequisite {
        if (access?.allows(course.id, AcademicCapability.PREREQUISITES, course.gradePlan?.version) != true) {
            return Prerequisite.Unknown(access?.reason?.takeIf { !access.allowed }
                ?: "Prerequisite results are unavailable without a separately reviewed matching curriculum policy.")
        }
        return try {
            require(catalog.any { it == course }) { "Selected course does not match the current prerequisite catalog" }
            val expressions = PrerequisiteCodec.validateCatalog(catalog.map { it.id to prerequisiteDocument(it) })
            if (course.prerequisitesKnown) expressions.getValue(course.id)
            else Prerequisite.Unknown("No approved prerequisite rule is recorded for ${course.id}.")
        } catch (error: IllegalArgumentException) {
            Prerequisite.Unknown("Prerequisite data needs review: ${error.message}")
        }
    }

    private fun prerequisiteDocument(course: CatalogCourse): PrerequisiteDocument {
        course.prerequisiteExpression?.let { return it }
        if (!course.prerequisitesKnown) return PrerequisiteDocument(
            Prerequisite.Unknown("No approved prerequisite rule is recorded for ${course.id}."))
        val refs = course.prerequisiteCourseIds
        val expression = when (course.ruleKind) {
            "NONE" -> {
                require(refs.isEmpty()) { "NONE prerequisite rule cannot contain references" }
                Prerequisite.AllOf(emptyList())
            }
            "ALL_OF", "ANY_OF" -> {
                require(refs.isNotEmpty()) { "${course.ruleKind} prerequisite rule cannot be empty" }
                if (course.ruleKind == "ALL_OF") Prerequisite.AllOf(refs.map(Prerequisite::Course))
                else Prerequisite.AnyOf(refs.map(Prerequisite::Course))
            }
            else -> Prerequisite.Unknown("This prerequisite rule requires review: ${course.ruleKind}.")
        }
        return PrerequisiteDocument(expression)
    }


    private fun hash(parts: List<String>): String {
        val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
