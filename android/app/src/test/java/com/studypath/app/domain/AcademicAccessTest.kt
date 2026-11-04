package com.studypath.app.domain

import com.studypath.app.data.*
import org.junit.Assert.*
import org.junit.Test

class AcademicAccessTest {
    private val now = 1_800_000_000_000L
    private val preferences = LearnerPreferences("college", "fixture_curriculum", setOf("computing"), onboardingComplete = true)
    private val options = LearnerPreferenceOptions("fixture_curriculum", "college", setOf("computing"), true, "APPROVED")
    private fun policy() = AcademicPolicySnapshot("fixture_curriculum", "college", "HIGHER_EDUCATION", setOf("computing"),
        "APPROVED", "synthetic-curriculum-approval", "Synthetic test reviewer", now - 1000,
        "test-fixture-source", "test-fixture-context", 1, now - 1000, now + 1000,
        AcademicCapability.entries.toSet(), AcademicCapability.entries.associateWith { "synthetic-${it.name}-policy" },
        mapOf("it101" to AcademicCourseBinding("computing", "plan_v1")))
    private fun resolve(snapshot: AcademicPolicySnapshot? = policy(), pref: LearnerPreferences = preferences,
                        choices: LearnerPreferenceOptions? = options, capability: AcademicCapability = AcademicCapability.SCORES,
                        course: String = "it101", plan: String? = "plan_v1") =
        AcademicAccess.resolve(pref, choices, snapshot, course, capability, plan, now)

    @Test fun matchingReviewedPolicyEnablesOnlyTheResolvedCapabilityAndExactPlan() {
        val access = resolve()
        assertTrue(access.allowed)
        assertTrue(access.allows("it101", AcademicCapability.SCORES, "plan_v1", now))
        assertFalse(access.allows("it101", AcademicCapability.SCENARIOS, "plan_v1", now))
        assertFalse(access.allows("other", AcademicCapability.SCORES, "plan_v1", now))
        assertFalse(access.allows("it101", AcademicCapability.SCORES, "plan_v2", now))
        assertEquals("fixture_curriculum", access.curriculumVersion)
        assertEquals(1, access.policyRevision)
        assertEquals("synthetic-SCORES-policy", access.policyRef)
    }

    @Test fun absentPolicyAndAnApprovedOptionIndexCannotEnableAcademicResults() {
        assertFalse(resolve(null).allowed)
        assertTrue(resolve(null).reason.contains("policy", ignoreCase = true))
    }

    @Test fun demoPendingWithdrawnOrUnknownStatusCannotEnableAcademicResults() {
        listOf("DEMONSTRATION_ONLY", "REVIEW_PENDING", "WITHDRAWN", "forged").forEach {
            assertFalse(it, resolve(policy().copy(approvalStatus = it)).allowed)
        }
    }

    @Test fun draftWrongLevelCurriculumSubjectOrUnapprovedChoicesRemainUnavailable() {
        listOf(preferences.copy(onboardingComplete = false), preferences.copy(levelId = "grade7"),
            preferences.copy(curriculumVersion = "other_cohort"), preferences.copy(subjectIds = setOf("biology"))).forEach {
            assertFalse(resolve(pref = it).allowed)
        }
        assertFalse(resolve(choices = null).allowed)
        assertFalse(resolve(choices = options.copy(approved = false)).allowed)
    }

    @Test fun curriculumApprovalDoesNotInventASeparateCapabilityPolicy() {
        assertFalse(resolve(policy().copy(capabilityApprovalRefs = emptyMap())).allowed)
        assertFalse(resolve(policy().copy(capabilities = setOf(AcademicCapability.GWA))).allowed)
        assertTrue(resolve(policy().copy(capabilityApprovalRefs = mapOf(AcademicCapability.SCORES to "score-policy"))).allowed)
    }

    @Test fun basicEducationAssessmentPolicyNeverEnablesCollegeGwaOrPrerequisites() {
        val basic = policy().copy(educationSector = "BASIC_EDUCATION")
        assertTrue(resolve(basic).allowed)
        assertTrue(resolve(basic, capability = AcademicCapability.SCENARIOS).allowed)
        assertFalse(resolve(basic, capability = AcademicCapability.GWA).allowed)
        assertFalse(resolve(basic, capability = AcademicCapability.PREREQUISITES).allowed)
    }

    @Test fun aCollegeGwaApprovalReferenceCannotInventItsGradeScaleOrRetakeRules() {
        val access = resolve(capability = AcademicCapability.GWA)
        assertFalse("GWA needs an explicit reviewed numeric scale, attempt inclusion and rounding policy", access.allowed)
        assertTrue(access.reason.contains("scale", ignoreCase = true))
    }

    @Test fun missingOrMismatchedCoursePlanNeverFallsBackToAnotherPlan() {
        assertFalse(resolve(course = "not_in_curriculum").allowed)
        assertFalse(resolve(plan = "old_plan").allowed)
        assertFalse(resolve(policy().copy(coursePlans = mapOf("it101" to AcademicCourseBinding("different_subject", "plan_v1")))).allowed)
    }

    @Test fun futureExpiredOrFutureReviewedPolicyCannotBecomeActive() {
        assertFalse(resolve(policy().copy(validFrom = now + 1)).allowed)
        assertFalse(resolve(policy().copy(validUntil = now)).allowed)
        assertFalse(resolve(policy().copy(reviewedAt = now + 1)).allowed)
        val access = resolve()
        assertFalse("An already resolved capability expires without requiring a new preference", access.allows("it101", AcademicCapability.SCORES, "plan_v1", now + 1000))
    }

    @Test fun policyRevisionOrIndependentPolicyReferenceChangesTheScenarioBaseIdentity() {
        val original = resolve().bindingFingerprint
        assertNotEquals(original, resolve(policy().copy(revision = 2)).bindingFingerprint)
        assertNotEquals(original, resolve(policy().copy(capabilityApprovalRefs = policy().capabilityApprovalRefs +
            (AcademicCapability.SCENARIOS to "replacement-scenario-policy"))).bindingFingerprint)
    }

    @Test fun retainedRecordBindingCannotCrossCurriculumRevisionPlanOrCapability() {
        val access = resolve()
        val binding = AcademicRecordBinding("fixture_curriculum", 1, "synthetic-SCORES-policy", "plan_v1")
        assertTrue(access.matches(binding, nowMs = now))
        assertFalse(access.matches(null, nowMs = now))
        listOf(binding.copy(curriculumVersion = "legacy_demo"), binding.copy(policyRevision = 2),
            binding.copy(policyRef = "synthetic-GWA-policy"), binding.copy(planVersion = "old_plan")).forEach {
            assertFalse(access.matches(it, nowMs = now))
        }
        assertFalse(access.matches(binding, nowMs = now + 1000))
        assertFalse(access.matches(binding.copy(policyRef = "forged-policy"), setOf("forged-policy"), nowMs = now))
    }

    @Test fun completionFromAnotherCurrentCapabilityStillNeedsItsOwnCoursePlan() {
        val snapshot = policy().copy(coursePlans = policy().coursePlans +
            ("it102" to AcademicCourseBinding("computing", "plan_v2")), gwaPolicy = AcademicGwaPolicy(
                "synthetic_scale", 1.0, 5.0, "EXPLICIT", "synthetic-retake", "synthetic-exclusion", "NONE", 3))
        val access = resolve(snapshot, capability = AcademicCapability.PREREQUISITES)
        val binding = AcademicRecordBinding("fixture_curriculum", 1, "synthetic-GWA-policy", "plan_v2")
        assertTrue(access.matchesAttempt(binding, "it102", now))
        assertFalse(access.matchesAttempt(binding.copy(planVersion = "plan_v1"), "it102", now))
        assertFalse(access.matchesAttempt(binding.copy(policyRef = "unapproved-policy"), "it102", now))
        assertFalse(access.matchesAttempt(binding.copy(policyRef = "synthetic-SCENARIOS-policy"), "it102", now))
        assertFalse(access.matchesAttempt(binding, "foreign_course", now))
    }

    @Test fun parserRoundTripsCanonicalReviewedCatalogData() {
        val snapshot = policy()
        assertEquals(snapshot, AcademicPolicyRecords.parse(snapshot.curriculumVersion, AcademicPolicyRecords.fields(snapshot)))
    }

    @Test fun parserRejectsUnexpectedFieldsWrongPathAndForgedCapabilityKeys() {
        val fields = AcademicPolicyRecords.fields(policy())
        assertThrows(IllegalArgumentException::class.java) { AcademicPolicyRecords.parse("fixture_curriculum", fields + ("clientApproved" to true)) }
        assertThrows(IllegalArgumentException::class.java) { AcademicPolicyRecords.parse("other", fields) }
        assertThrows(IllegalArgumentException::class.java) { AcademicPolicyRecords.parse("fixture_curriculum", fields +
            ("capabilities" to mapOf("SCORES" to true, "HONORS" to true))) }
    }

    @Test fun malformedProvenanceAndUnboundedPolicyValuesFailClosedEvenForConstructedObjects() {
        listOf(policy().copy(approvalRef = ""), policy().copy(reviewer = ""), policy().copy(sourceRef = ""),
            policy().copy(contextRef = ""), policy().copy(revision = 0), policy().copy(validUntil = now - 1001),
            policy().copy(educationSector = "UNKNOWN")).forEach { assertFalse(resolve(it).allowed) }
    }
}
