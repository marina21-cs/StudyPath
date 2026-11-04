package com.studypath.app.domain

import org.junit.Assert.*
import org.junit.Test

class GwaEngineTest {
    private fun attempt(id: String, grade: Double?, units: Double, included: Boolean = true,
                        scale: String = "explicit-demo") = GradeAttempt(id, "course-$id", grade, units, included, scale)

    @Test fun weightsByUnitsInsteadOfNumberOfCourses() {
        val result = GwaEngine.evaluate(listOf(attempt("a", 1.5, 3.0), attempt("b", 2.25, 2.0)))
        assertEquals(1.8, result.average!!, 1e-9)
        assertEquals(5.0, result.includedUnits, 0.0)
    }

    @Test fun repeatsAreIncludedOnlyWhenExplicitlySelected() {
        val old = attempt("old", 5.0, 3.0).copy(courseId = "a")
        val latest = attempt("new", 2.0, 3.0).copy(courseId = "a")
        val other = attempt("b", 1.5, 3.0)
        assertEquals(2.8333333333, GwaEngine.evaluate(listOf(old, latest, other)).average!!, 1e-9)
        assertEquals(1.75, GwaEngine.evaluate(listOf(old.copy(included = false), latest, other)).average!!, 1e-9)
    }

    @Test fun noIncludedAttemptsReturnsUnavailableInsteadOfZero() {
        assertNull(GwaEngine.evaluate(emptyList()).average)
        assertNull(GwaEngine.evaluate(listOf(attempt("a", null, 0.0, included = false))).average)
    }

    @Test fun includedNonnumericGradeMakesResultUnavailable() {
        val result = GwaEngine.evaluate(listOf(attempt("a", 1.0, 3.0), attempt("inc", null, 3.0)))
        assertNull(result.average)
        assertNotNull(result.unavailableReason)
    }

    @Test fun numericZeroIsNotMissingAndNoPassingScaleIsAssumed() {
        val result = GwaEngine.evaluate(listOf(attempt("a", 0.0, 1.0), attempt("b", 100.0, 1.0)))
        assertEquals(50.0, result.average!!, 0.0)
    }

    @Test fun invalidIncludedUnitsAndNonfiniteGradesAreRejected() {
        listOf(0.0, -1.0, Double.NaN).forEach { units ->
            assertThrows(IllegalArgumentException::class.java) { GwaEngine.evaluate(listOf(attempt("a", 1.0, units))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GwaEngine.evaluate(listOf(attempt("a", Double.POSITIVE_INFINITY, 3.0)))
        }
    }

    @Test fun mixedScalesAndDuplicateAttemptIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GwaEngine.evaluate(listOf(attempt("a", 1.0, 3.0), attempt("b", 80.0, 3.0, scale = "percentage")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GwaEngine.evaluate(listOf(attempt("a", 1.0, 3.0), attempt("a", 2.0, 3.0)))
        }
    }
}
