package com.studypath.app.domain

import org.junit.Assert.*
import org.junit.Test

class GradeEngineTest {
    private fun item(id: String, earned: Double?, possible: Double = 10.0,
                     status: AssessmentStatus = if (earned == null) AssessmentStatus.UNGRADED else AssessmentStatus.SCORED,
                     weight: Double? = null) = Assessment(id, possible, earned, status, weight)

    private fun evaluate(vararg items: Assessment, mode: Aggregation = Aggregation.POOLED_POINTS,
                         known: Boolean = true) = GradeEngine.evaluate(
        GradePlan(listOf(GradeCategory("test", 1.0, mode, items.toList())), known))

    private fun close(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, 0.000001)
    }

    @Test fun pooledPointsDoesNotAveragePercentages() {
        close(100.0 * 59 / 110, evaluate(item("a", 9.0), item("b", 50.0, 100.0)).gradedWorkPercent)
    }

    @Test fun equalItemsUsesEqualPercentages() {
        close(70.0, evaluate(item("a", 9.0), item("b", 50.0, 100.0), mode = Aggregation.EQUAL_ITEMS).gradedWorkPercent)
    }

    @Test fun explicitItemWeightsApplyToPercentages() {
        close(60.0, evaluate(item("a", 9.0, weight = .25), item("b", 50.0, 100.0, weight = .75),
            mode = Aggregation.EXPLICIT_WEIGHTS).gradedWorkPercent)
    }

    @Test fun categoriesUseWeightsWithoutEarlyRounding() {
        val summary = GradeEngine.evaluate(GradePlan(listOf(
            GradeCategory("a", .3, Aggregation.POOLED_POINTS, listOf(item("a", 8.0))),
            GradeCategory("b", .7, Aggregation.POOLED_POINTS, listOf(item("b", 9.0))))))
        close(87.0, summary.minimumFinalPercent)
        assertTrue(summary.complete)
    }

    @Test fun ungradedIsNotZero() {
        val pending = evaluate(item("a", 8.0), item("b", null))
        val zero = evaluate(item("a", 8.0), item("b", 0.0))
        close(80.0, pending.gradedWorkPercent)
        close(40.0, zero.gradedWorkPercent)
        close(40.0, pending.minimumFinalPercent)
        close(90.0, pending.maximumFinalPercent)
        assertFalse(pending.complete)
        assertTrue(zero.complete)
    }

    @Test fun missingWorkCannotBeAssumedToBeUngradedOrZeroWithoutAReviewedTreatment() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            evaluate(item("recorded", 8.0), item("missing", null, status = AssessmentStatus.MISSING))
        }
        assertTrue(error.message!!.contains("reviewed treatment"))
    }

    @Test fun noScoredWorkHasNoSnapshot() {
        val summary = evaluate(item("a", null))
        assertNull(summary.gradedWorkPercent)
        close(0.0, summary.minimumFinalPercent)
        close(100.0, summary.maximumFinalPercent)
    }

    @Test fun partialCategoryDoesNotFreezeItsWholeWeight() {
        val summary = GradeEngine.evaluate(GradePlan(listOf(
            GradeCategory("a", .4, Aggregation.POOLED_POINTS,
                listOf(item("scored", 60.0, 60.0), item("pending", null, 40.0))),
            GradeCategory("b", .6, Aggregation.POOLED_POINTS, listOf(item("b", null, 100.0))))))
        close(24.0, summary.minimumFinalPercent)
        close(.76, summary.remainingWeight)
        close(67.1052631579, GradeEngine.target(summary, 75.0).requiredRemainingPercent)
    }

    @Test fun targetCanBeUnreachable() {
        val summary = evaluate(item("done", 48.0, 60.0), item("todo", null, 40.0))
        close(88.0, summary.maximumFinalPercent)
        val target = GradeEngine.target(summary, 90.0)
        assertEquals(TargetStatus.UNREACHABLE, target.status)
        close(105.0, target.requiredRemainingPercent)
    }

    @Test fun securedTargetRequiresNoMorePoints() {
        val target = GradeEngine.target(evaluate(item("a", 8.0), item("b", null)), 30.0)
        assertEquals(TargetStatus.ALREADY_SECURED, target.status)
        close(0.0, target.requiredRemainingPercent)
    }

    @Test fun completedTargetAvoidsDivisionByZero() {
        val summary = evaluate(item("a", 8.0))
        assertEquals(TargetStatus.FINAL_MET, GradeEngine.target(summary, 80.0).status)
        assertEquals(TargetStatus.FINAL_MISSED, GradeEngine.target(summary, 81.0).status)
        assertNull(GradeEngine.target(summary, 80.0).requiredRemainingPercent)
    }

    @Test fun unknownPlanGivesSnapshotWithoutExactProjection() {
        val summary = evaluate(item("a", 8.0), item("b", null), known = false)
        close(80.0, summary.gradedWorkPercent)
        assertNull(summary.minimumFinalPercent)
        assertNull(summary.maximumFinalPercent)
        assertNull(summary.remainingWeight)
        assertFalse(summary.complete)
        assertEquals(TargetStatus.UNKNOWN_PLAN, GradeEngine.target(summary, 75.0).status)
    }

    @Test fun excusedItemsAreRemovedAndRemainingWeightsRenormalized() {
        val summary = evaluate(item("a", 8.0, weight = .25),
            item("b", null, status = AssessmentStatus.EXCUSED, weight = .75), mode = Aggregation.EXPLICIT_WEIGHTS)
        close(80.0, summary.gradedWorkPercent)
        assertTrue(summary.complete)
    }

    @Test fun invalidAssessmentValuesAreRejected() {
        listOf(item("a", -1.0), item("a", 11.0), item("a", 0.0, 0.0),
            item("a", Double.NaN), item("a", 1.0, Double.POSITIVE_INFINITY),
            item("a", null, status = AssessmentStatus.SCORED),
            item("a", 1.0, status = AssessmentStatus.UNGRADED),
            item("a", 1.0, status = AssessmentStatus.EXCUSED)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { evaluate(bad) }
        }
    }

    @Test fun invalidAndAmbiguousWeightsAreRejected() {
        listOf(-.1, Double.NaN, Double.POSITIVE_INFINITY, .9).forEach { weight ->
            assertThrows(IllegalArgumentException::class.java) {
                GradeEngine.evaluate(GradePlan(listOf(GradeCategory("a", weight,
                    Aggregation.POOLED_POINTS, listOf(item("a", 5.0))))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(item("a", 8.0, weight = .4), mode = Aggregation.EXPLICIT_WEIGHTS)
        }
        assertThrows(IllegalArgumentException::class.java) { evaluate(item("a", 8.0), mode = Aggregation.EXPLICIT_WEIGHTS) }
        assertThrows(IllegalArgumentException::class.java) { evaluate(item("a", 8.0, weight = 1.0)) }
    }

    @Test fun emptyAndDuplicateIdentifiersAndAllExcusedAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GradeEngine.evaluate(GradePlan(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { evaluate(item("a", 1.0), item("a", 2.0)) }
        assertThrows(IllegalArgumentException::class.java) { evaluate(item("", 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { evaluate(item("a", null, status = AssessmentStatus.EXCUSED)) }
    }

    @Test fun targetMustBeFinitePercentage() {
        val summary = evaluate(item("a", 8.0))
        listOf(-1.0, 101.0, Double.NaN).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { GradeEngine.target(summary, value) }
        }
    }

    @Test fun floatingPointNoiseDoesNotTurnAnExactlyMetTargetIntoFailure() {
        val summary = GradeEngine.evaluate(GradePlan(listOf(
            GradeCategory("a", .1, Aggregation.POOLED_POINTS, listOf(item("a", 0.0))),
            GradeCategory("b", .2, Aggregation.POOLED_POINTS, listOf(item("b", 0.0))),
            GradeCategory("c", .7, Aggregation.POOLED_POINTS, listOf(item("c", 9.0))))))
        assertEquals(TargetStatus.FINAL_MET, GradeEngine.target(summary, 63.0).status)
        assertEquals(TargetStatus.FINAL_MISSED, GradeEngine.target(summary, 63.000001).status)
    }

    @Test fun unknownPlanSnapshotNormalizesOnlyObservedCategories() {
        val summary = GradeEngine.evaluate(GradePlan(listOf(
            GradeCategory("a", .4, Aggregation.EQUAL_ITEMS, listOf(item("a", 8.0), item("b", null))),
            GradeCategory("b", .6, Aggregation.EQUAL_ITEMS, listOf(item("c", null)))), false))
        close(80.0, summary.gradedWorkPercent)
        assertNull(summary.maximumFinalPercent)
    }

    @Test fun duplicateCategoryIdsAreRejected() {
        val category = GradeCategory("same", .5, Aggregation.EQUAL_ITEMS, listOf(item("a", 8.0)))
        assertThrows(IllegalArgumentException::class.java) { GradeEngine.evaluate(GradePlan(listOf(category, category))) }
    }

    @Test fun zeroWeightedPendingItemDoesNotPreventCompletion() {
        val summary = evaluate(item("a", 8.0, weight = 1.0), item("b", null, weight = 0.0),
            mode = Aggregation.EXPLICIT_WEIGHTS)
        close(80.0, summary.minimumFinalPercent)
        assertTrue(summary.complete)
    }

    @Test fun excusingAllPositiveWeightsRequiresAReplacementPolicy() {
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(item("a", 8.0, weight = 0.0), item("b", null, status = AssessmentStatus.EXCUSED, weight = 1.0),
                mode = Aggregation.EXPLICIT_WEIGHTS)
        }
    }
}
