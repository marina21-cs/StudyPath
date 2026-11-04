package com.studypath.app.domain

import kotlin.math.abs

enum class AssessmentStatus { SCORED, UNGRADED, EXCUSED, MISSING }
enum class Aggregation { POOLED_POINTS, EQUAL_ITEMS, EXPLICIT_WEIGHTS }

data class Assessment(
    val id: String,
    val possiblePoints: Double,
    val earnedPoints: Double?,
    val status: AssessmentStatus,
    val explicitWeight: Double? = null,
)

data class GradeCategory(
    val id: String,
    val weight: Double,
    val aggregation: Aggregation,
    val assessments: List<Assessment>,
)

/** False means listed assessments are a snapshot, not an approved complete assessment plan. */
data class GradePlan(val categories: List<GradeCategory>, val assessmentPlanKnown: Boolean = true)

data class GradeSummary(
    val gradedWorkPercent: Double?,
    val minimumFinalPercent: Double?,
    val maximumFinalPercent: Double?,
    val remainingWeight: Double?,
    val complete: Boolean,
)

enum class TargetStatus { UNKNOWN_PLAN, NEEDS_REMAINING, ALREADY_SECURED, UNREACHABLE, FINAL_MET, FINAL_MISSED }
data class TargetProjection(val status: TargetStatus, val requiredRemainingPercent: Double?)

/** Raw percentage arithmetic only: no institutional conversion, passing mark, or rounding policy. */
object GradeEngine {
    private const val WEIGHT_TOLERANCE = 1e-9

    fun evaluate(plan: GradePlan): GradeSummary {
        require(plan.categories.none { category -> category.assessments.any { it.status == AssessmentStatus.MISSING } }) {
            "Missing work requires an explicit reviewed treatment. It is neither an ungraded item nor an assumed zero."
        }
        require(plan.categories.isNotEmpty()) { "At least one category is required" }
        requireUniqueIds(plan.categories.map { it.id }, "category")
        validateWeights(plan.categories.map { it.weight }, "Category")
        val categoryWeightTotal = plan.categories.sumOf { it.weight }
        var contribution = 0.0
        var scoredWeight = 0.0
        var remainingWeight = 0.0
        var snapshotContribution = 0.0
        var snapshotWeight = 0.0

        for (category in plan.categories) {
            val weightedItems = weightedItems(category)
            val categoryWeight = category.weight / categoryWeightTotal
            var scoredCategoryContribution = 0.0
            var scoredCategoryWeight = 0.0
            for ((item, weight) in weightedItems) {
                val effectiveWeight = categoryWeight * weight
                if (item.status == AssessmentStatus.SCORED) {
                    val percent = item.earnedPoints!! / item.possiblePoints * 100.0
                    contribution += effectiveWeight * percent
                    scoredWeight += effectiveWeight
                    scoredCategoryContribution += weight * percent
                    scoredCategoryWeight += weight
                } else {
                    remainingWeight += effectiveWeight
                }
            }
            if (scoredCategoryWeight > 0.0) {
                snapshotContribution += categoryWeight * scoredCategoryContribution / scoredCategoryWeight
                snapshotWeight += categoryWeight
            }
        }

        if (!plan.assessmentPlanKnown) {
            return GradeSummary(
                gradedWorkPercent = if (snapshotWeight > 0.0) snapshotContribution / snapshotWeight else null,
                minimumFinalPercent = null,
                maximumFinalPercent = null,
                remainingWeight = null,
                complete = false,
            )
        }
        return GradeSummary(
            gradedWorkPercent = if (scoredWeight > 0.0) contribution / scoredWeight else null,
            minimumFinalPercent = contribution.coerceIn(0.0, 100.0),
            maximumFinalPercent = (contribution + 100.0 * remainingWeight).coerceIn(0.0, 100.0),
            remainingWeight = remainingWeight,
            complete = remainingWeight == 0.0,
        )
    }

    fun target(summary: GradeSummary, targetPercent: Double): TargetProjection {
        require(targetPercent.isFinite() && targetPercent in 0.0..100.0) { "Target must be a percentage in 0..100" }
        val earned = summary.minimumFinalPercent ?: return TargetProjection(TargetStatus.UNKNOWN_PLAN, null)
        val remaining = summary.remainingWeight ?: return TargetProjection(TargetStatus.UNKNOWN_PLAN, null)
        if (remaining == 0.0) {
            return TargetProjection(if (!exceeds(targetPercent, earned)) TargetStatus.FINAL_MET else TargetStatus.FINAL_MISSED, null)
        }
        val needed = (targetPercent - earned) / remaining
        return when {
            !exceeds(targetPercent, earned) -> TargetProjection(TargetStatus.ALREADY_SECURED, 0.0)
            exceeds(targetPercent, summary.maximumFinalPercent!!) -> TargetProjection(TargetStatus.UNREACHABLE, needed)
            else -> TargetProjection(TargetStatus.NEEDS_REMAINING, needed.coerceIn(0.0, 100.0))
        }
    }

    // A few machine representable steps absorb arithmetic noise, without display rounding or a passing policy.
    private fun exceeds(left: Double, right: Double): Boolean =
        left - right > 4.0 * maxOf(Math.ulp(left), Math.ulp(right))

    private fun weightedItems(category: GradeCategory): List<Pair<Assessment, Double>> {
        require(category.assessments.isNotEmpty()) { "Category ${category.id} has no assessments" }
        requireUniqueIds(category.assessments.map { it.id }, "assessment in ${category.id}")
        for (item in category.assessments) {
            require(item.possiblePoints.isFinite() && item.possiblePoints > 0.0) { "Possible points must be positive and finite" }
            if (item.status == AssessmentStatus.SCORED) {
                require(item.earnedPoints != null && item.earnedPoints.isFinite() &&
                    item.earnedPoints in 0.0..item.possiblePoints) { "Scored assessments require points within 0..possiblePoints" }
            } else {
                require(item.earnedPoints == null) { "Ungraded or excused assessments cannot contain a score" }
            }
        }
        if (category.aggregation == Aggregation.EXPLICIT_WEIGHTS) {
            validateWeights(category.assessments.map {
                requireNotNull(it.explicitWeight) { "Explicit item weight is required" }
            }, "Assessment")
        } else {
            require(category.assessments.all { it.explicitWeight == null }) { "Item weights require EXPLICIT_WEIGHTS mode" }
        }
        // Excused items are removed and active item weights are renormalized within the category.
        val active = category.assessments.filter { it.status != AssessmentStatus.EXCUSED }
        require(active.isNotEmpty()) { "An all-excused category needs an explicit replacement policy" }
        val rawWeights = active.map { item ->
            when (category.aggregation) {
                Aggregation.POOLED_POINTS -> item.possiblePoints
                Aggregation.EQUAL_ITEMS -> 1.0
                Aggregation.EXPLICIT_WEIGHTS -> item.explicitWeight!!
            }
        }
        val total = rawWeights.sum()
        require(total.isFinite() && total > 0.0) { "Active assessment weights must have a finite positive total" }
        return active.zip(rawWeights.map { it / total })
    }

    private fun validateWeights(weights: List<Double>, label: String) {
        require(weights.all { it.isFinite() && it >= 0.0 }) { "$label weights must be finite and nonnegative" }
        require(abs(weights.sum() - 1.0) <= WEIGHT_TOLERANCE) { "$label weights must sum to one" }
    }

    private fun requireUniqueIds(ids: List<String>, label: String) {
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Each $label needs a unique nonblank ID" }
    }
}
