package com.studypath.app.domain

import com.studypath.app.data.*
import java.security.MessageDigest

data class ResolvedAcademicPlan(val categories: List<GradeCategory>, val records: Map<Pair<String, String>, ScoreRecord>,
                                val summary: GradeSummary?, val fingerprint: String)

/** One canonical, policy-bound actual snapshot for both repository stale-write checks and Planner presentation. */
object AcademicPlanFingerprint {
    fun compute(course: CatalogCourse, scores: List<ScoreRecord>, scoreAccess: AcademicAccess): String =
        resolve(course, scores, scoreAccess).fingerprint

    fun resolve(course: CatalogCourse, scores: List<ScoreRecord>, scoreAccess: AcademicAccess): ResolvedAcademicPlan {
        val catalogPlan = requireNotNull(course.gradePlan) { "No assessment plan is available." }
        require(catalogPlan.verified && scoreAccess.allows(course.id, AcademicCapability.SCORES, catalogPlan.version)) { scoreAccess.reason }
        val current = scores.filter { it.courseId == course.id && it.planVersion == catalogPlan.version && scoreAccess.matches(it.policyBinding) }
        val grouped = current.groupBy { it.categoryId to it.assessmentId }
        require(grouped.values.none { it.size > 1 }) { "Conflicting score records exist for the same assessment. Resolve the duplicate before calculating." }
        val expected = catalogPlan.categories.flatMap { category -> category.assessments.map { category.id to it.id } }.toSet()
        require(grouped.keys.all { it in expected }) { "A score references an assessment outside the current plan. Review the stored record." }
        val records = grouped.mapValues { it.value.single() }
        val categories = catalogPlan.categories.map { category ->
            val aggregation = runCatching { Aggregation.valueOf(category.aggregation) }
                .getOrElse { throw IllegalArgumentException("Unsupported assessment weighting: ${category.aggregation}") }
            GradeCategory(category.id, category.weight, aggregation, category.assessments.map { planned ->
                val record = records[category.id to planned.id]
                require(record == null || record.possiblePoints == planned.possiblePoints) {
                    "${planned.title} has a recorded maximum that differs from this plan. Review the score before calculating."
                }
                val status = record?.let { AssessmentStatus.valueOf(it.status) } ?: AssessmentStatus.UNGRADED
                require(status != AssessmentStatus.MISSING || record?.earnedPoints == null) { "Missing work cannot have an assumed numeric score." }
                Assessment(planned.id, planned.possiblePoints, record?.earnedPoints, status, planned.explicitWeight)
            })
        }
        val missing = categories.any { category -> category.assessments.any { it.status == AssessmentStatus.MISSING } }
        val summary = if (missing) {
            // Validate plan structure without giving MISSING an active grade treatment.
            GradeEngine.evaluate(GradePlan(categories.map { category -> category.copy(assessments = category.assessments.map {
                if (it.status == AssessmentStatus.MISSING) it.copy(status = AssessmentStatus.UNGRADED) else it
            }) }, catalogPlan.assessmentPlanKnown))
            null
        } else GradeEngine.evaluate(GradePlan(categories, catalogPlan.assessmentPlanKnown))
        val parts = buildList {
            add(course.id); add(catalogPlan.version); add(catalogPlan.assessmentPlanKnown.toString()); add(requireNotNull(scoreAccess.bindingFingerprint))
            categories.sortedBy { it.id }.forEach { category ->
                add("CATEGORY"); add(category.id); add(number(category.weight)); add(category.aggregation.name)
                category.assessments.sortedBy { it.id }.forEach { item ->
                    add("ASSESSMENT"); add(item.id); add(number(item.possiblePoints)); add(item.explicitWeight?.let(::number) ?: "NO_WEIGHT")
                    add(item.status.name); add(item.earnedPoints?.let(::number) ?: "NO_SCORE")
                }
            }
        }
        val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ResolvedAcademicPlan(categories, records, summary, fingerprint)
    }

    private fun number(value: Double) = if (value == 0.0) "0" else value.toString()
}
