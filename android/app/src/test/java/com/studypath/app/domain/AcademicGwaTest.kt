package com.studypath.app.domain

import com.studypath.app.data.*
import org.junit.Assert.*
import org.junit.Test

class AcademicGwaTest {
    private val reviewed = AcademicGwaPolicy("synthetic_scale", 1.0, 5.0, "EXPLICIT", "synthetic-retake-policy",
        "synthetic-exclusion-policy", "NONE", 3)
    private fun permission(courseId: String, policy: AcademicGwaPolicy = reviewed): AcademicAccess {
        val snapshot = AcademicPolicySnapshot("college_fixture", "college", "HIGHER_EDUCATION", setOf("subject"), "APPROVED",
            "synthetic-curriculum-approval", "Synthetic reviewer", 1_700_000_000_000, "fixture-source", "fixture-context", 1,
            1_700_000_000_000, 4_102_444_800_000, setOf(AcademicCapability.GWA), mapOf(AcademicCapability.GWA to "gwa-policy"),
            listOf("a", "b").associateWith { AcademicCourseBinding("subject", "plan") }, policy)
        return AcademicAccess.resolve(LearnerPreferences("college", "college_fixture", setOf("subject"), onboardingComplete = true),
            LearnerPreferenceOptions("college_fixture", "college", setOf("subject"), true, "APPROVED"), snapshot, courseId, AcademicCapability.GWA, "plan")
    }
    private fun attempt(id: String, course: String, grade: Double?, units: Double, included: Boolean = true) =
        CourseAttempt(id, course, "Synthetic term", "COMPLETED", units, grade = grade, scaleId = "synthetic_scale", includeInGwa = included,
            policyBinding = AcademicRecordBinding("college_fixture", 1, "gwa-policy", "plan"))
    private fun evaluate(attempts: List<CourseAttempt>, policy: AcademicGwaPolicy = reviewed) =
        AcademicGwa.evaluate(attempts, listOf("a", "b").associateWith { permission(it, policy) })

    @Test fun independentlyWorkedUnitWeightedGradesUseOnlyReviewedExplicitInclusion() {
        // (1.5 * 3 + 2.25 * 2) / 5 = 1.8; excluded prior failure remains in the input history.
        val history = listOf(attempt("prior", "a", 5.0, 3.0, false), attempt("current", "a", 1.5, 3.0), attempt("other", "b", 2.25, 2.0))
        val result = evaluate(history)
        assertEquals(1.8, result.average!!, 1e-9)
        assertEquals(2, result.includedAttemptCount)
        assertEquals(3, history.size)
        assertFalse(history.first().includeInGwa)
    }

    @Test fun missingMixedUnapprovedOrLegacyScaleNeverProducesAnActiveGwa() {
        val first = attempt("a", "a", 1.5, 3.0)
        val second = attempt("b", "b", 2.0, 3.0)
        listOf(second.copy(scaleId = "foreign"), second.copy(grade = null), second.copy(grade = 99.0),
            second.copy(policyBinding = null), second.copy(policyBinding = second.policyBinding!!.copy(policyRevision = 2)),
            second.copy(status = "INCOMPLETE")).forEach {
            assertNull(evaluate(listOf(first, it)).average)
        }
    }

    @Test fun emptyInclusionIsUnavailableAndRetakesAreNeverAutomaticallyReplaced() {
        assertNull(evaluate(emptyList()).average)
        val records = listOf(attempt("old", "a", 5.0, 3.0), attempt("new", "a", 2.0, 3.0))
        assertEquals(3.5, evaluate(records).average!!, 0.0)
        assertEquals(2.0, evaluate(records.map { it.copy(includeInGwa = it.id == "new") }).average!!, 0.0)
    }

    @Test fun onlyTheReviewedRoundingRuleIsApplied() {
        val records = listOf(attempt("a", "a", 1.25, 1.0))
        assertEquals(1.25, evaluate(records).average!!, 0.0)
        assertEquals(1.3, evaluate(records, reviewed.copy(roundingMode = "HALF_UP", roundingScale = 1)).average!!, 0.0)
        assertEquals(1.2, evaluate(records, reviewed.copy(roundingMode = "HALF_EVEN", roundingScale = 1)).average!!, 0.0)
    }

    @Test fun reviewedScaleAndRetakePolicyRoundTripWithoutInventingDefaults() {
        val access = permission("a")
        assertTrue(access.allowed)
        assertEquals(reviewed, access.gwaPolicy)
    }
}
