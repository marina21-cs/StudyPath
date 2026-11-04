package com.studypath.app.ui.planner

import com.studypath.app.data.*
import com.studypath.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class PlannerAdapterTest {
    private val plan = CatalogGradePlan("v1", true, true, "Synthetic reviewed fixture", listOf(
        CatalogCategory("quizzes", 1.0, "POOLED_POINTS", listOf(
            CatalogAssessment("q1", "Quiz 1", 10.0), CatalogAssessment("q2", "Quiz 2", 10.0)))))
    private fun course(plan: CatalogGradePlan? = this.plan) = CatalogCourse("it101", "Programming", 3.0,
        "Programming", "basics", true, "NONE", emptyList(), plan)
    private fun score(id: String = "s1", assessment: String = "q1", earned: Double? = 8.0,
                      version: String = "v1", category: String = "quizzes") = ScoreRecord(
        id, "it101", assessment, category, if (earned == null) "UNGRADED" else "SCORED", 10.0, earned,
        updatedAt = 1L, planVersion = version, policyBinding = binding(version))

    private fun binding(version: String = "v1", capability: AcademicCapability = AcademicCapability.SCORES) =
        AcademicRecordBinding("test_curriculum", 1, "synthetic-${capability.name}-policy", version)

    /** Explicit synthetic approval fixture: no production default can enable these arithmetic probes. */
    private fun access(course: CatalogCourse, catalog: List<CatalogCourse>, capability: AcademicCapability): AcademicAccess {
        val now = System.currentTimeMillis()
        val policy = AcademicPolicySnapshot("test_curriculum", "college", "HIGHER_EDUCATION", setOf("computing"),
            "APPROVED", "synthetic-curriculum-approval", "Synthetic test reviewer", 1_700_000_000_000,
            "synthetic-test-source", "synthetic-test-context", 1, 1_700_000_000_000, 4_102_444_800_000,
            AcademicCapability.entries.toSet(), AcademicCapability.entries.associateWith { "synthetic-${it.name}-policy" },
            catalog.associate { it.id to AcademicCourseBinding("computing", it.gradePlan?.version ?: "no_plan") })
        return AcademicAccess.resolve(LearnerPreferences("college", "test_curriculum", setOf("computing"), onboardingComplete = true),
            LearnerPreferenceOptions("test_curriculum", "college", setOf("computing"), true, "APPROVED"),
            policy, course.id, capability, course.gradePlan?.version, now)
    }
    private fun evaluate(course: CatalogCourse, scores: List<ScoreRecord>) =
        PlannerAdapter.evaluate(course, scores, access(course, listOf(course), AcademicCapability.SCORES))
    private fun prerequisites(course: CatalogCourse, catalog: List<CatalogCourse>, attempts: List<CourseAttempt>) =
        PlannerAdapter.prerequisites(course, catalog, attempts.map { it.copy(policyBinding = binding(
            catalog.singleOrNull { item -> item.id == it.courseId }?.gradePlan?.version ?: "v1", AcademicCapability.PREREQUISITES)) },
            access(course, catalog, AcademicCapability.PREREQUISITES))

    @Test fun visualTreeCannotExposeUngatedOrMalformedDependencies() {
        val dependency = course().copy(id = "A")
        val target = course().copy(id = "B", ruleKind = "ALL_OF", prerequisiteCourseIds = listOf("A"))
        val catalog = listOf(dependency, target)
        assertTrue(PlannerAdapter.prerequisiteTree(target, catalog, null) is Prerequisite.Unknown)
        assertEquals(Prerequisite.AllOf(listOf(Prerequisite.Course("A"))),
            PlannerAdapter.prerequisiteTree(target, catalog, access(target, catalog, AcademicCapability.PREREQUISITES)))
        val missing = target.copy(prerequisiteCourseIds = listOf("MISSING"))
        assertTrue(PlannerAdapter.prerequisiteTree(missing, listOf(dependency, missing),
            access(missing, listOf(dependency, missing), AcademicCapability.PREREQUISITES)) is Prerequisite.Unknown)
    }

    @Test fun demonstrationPlanCannotExposeAnActiveGradeWithOnlyAWarning() {
        val state = PlannerAdapter.evaluate(course(plan.copy(verified = false)), listOf(score()))
        assertNull("Unapproved demonstration plans must not calculate an active grade", state.summary)
        assertTrue("An unavailable plan must not expose editable planned assessments", state.assessments.isEmpty())
        assertTrue(state.warnings.any { it.contains("unavailable", ignoreCase = true) })
    }

    @Test fun aVerifiedBooleanAloneCannotApproveTheLearnerCurriculumOrPrerequisites() {
        val forged = course(plan.copy(verified = true))
        assertNull("A course flag cannot replace reviewed learner-context policy", PlannerAdapter.evaluate(forged, listOf(score())).summary)
        val result = PlannerAdapter.prerequisites(forged, listOf(forged), emptyList())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.any { it.contains("policy", ignoreCase = true) })
    }

    @Test fun legacyAndOtherCurriculumScoresAreRetainedButNeverReboundToTheNewPolicy() {
        val legacy = score().copy(policyBinding = null)
        val foreign = score("foreign", "q2").copy(policyBinding = binding().copy(curriculumVersion = "other_curriculum"))
        val state = evaluate(course(), listOf(legacy, foreign))
        assertNull(state.summary!!.gradedWorkPercent)
        assertTrue(state.assessments.all { it.record == null })
        assertTrue(state.warnings.any { it.contains("retained") })
    }

    @Test fun legacyPassingAttemptCannotSatisfyAReviewedNewCurriculum() {
        val dependency = course().copy(id = "A")
        val target = course().copy(id = "B", ruleKind = "ALL_OF", prerequisiteCourseIds = listOf("A"))
        val catalog = listOf(dependency, target)
        val legacy = CourseAttempt("legacy", "A", "old_demo", "COMPLETED", 3.0, "PASSED")
        val result = PlannerAdapter.prerequisites(target, catalog, listOf(legacy), access(target, catalog, AcademicCapability.PREREQUISITES))
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.any { it.contains("UNKNOWN") })
    }

    @Test fun unverifiedCatalogPlanStaysUnavailableDespiteAnExplicitMatchingPolicyFixture() {
        val unverified = course(plan.copy(verified = false))
        val result = evaluate(unverified, listOf(score()))
        assertNull(result.summary)
        assertTrue(result.assessments.isEmpty())
    }

    @Test fun missingWorkRetainsEditableRowsWhileWithholdingTheUnsupportedGrade() {
        val missing = score().copy(status = "MISSING", earnedPoints = null)
        val result = evaluate(course(), listOf(missing))
        assertNull(result.summary)
        assertEquals(AssessmentStatus.MISSING, result.assessments.first().assessment.status)
        assertEquals(missing, result.assessments.first().record)
        assertTrue(result.warnings.any { it.contains("Missing") })
    }

    @Test fun missingPlannedAssessmentRemainsUngradedAndContributesToRemainingWeight() {
        val state = evaluate(course(), listOf(score()))
        assertEquals(AssessmentStatus.UNGRADED, state.assessments.last().assessment.status)
        assertEquals(.5, state.summary!!.remainingWeight!!, 1e-9)
        assertEquals(40.0, state.summary.minimumFinalPercent!!, 1e-9)
    }

    @Test fun ignoresOtherVersionsAndDoesNotMigrateTheirScores() {
        val state = evaluate(course(), listOf(score(version = "old")))
        assertNull(state.summary!!.gradedWorkPercent)
        assertTrue(state.warnings.any { it.contains("version") })
        assertEquals(evaluate(course(), emptyList()).fingerprint, state.fingerprint)
    }

    @Test fun unknownAssessmentPlanNeverGetsFixedPlanProjection() {
        val state = evaluate(course(plan.copy(assessmentPlanKnown = false)), listOf(score()))
        assertNull(state.summary!!.maximumFinalPercent)
        assertEquals(TargetStatus.UNKNOWN_PLAN, GradeEngine.target(state.summary, 80.0).status)
    }

    @Test fun missingPlanIsUnavailableAndCannotBeFabricatedFromRecordedRows() {
        val state = evaluate(course(null), listOf(score()))
        assertNull(state.summary)
        assertTrue(state.assessments.isEmpty())
    }

    @Test fun zeroAndUngradedHaveDifferentFingerprintsAndResults() {
        val zero = evaluate(course(), listOf(score(earned = 0.0)))
        val ungraded = evaluate(course(), listOf(score(earned = null)))
        assertNotEquals(zero.fingerprint, ungraded.fingerprint)
        assertEquals(0.0, zero.summary!!.gradedWorkPercent!!, 0.0)
        assertNull(ungraded.summary!!.gradedWorkPercent)
    }

    @Test fun fingerprintsIgnoreCollectionOrderRecordIdsAndServerTimestamps() {
        val scores = listOf(score(), score("s2", "q2", 9.0))
        val first = evaluate(course(), scores)
        val second = evaluate(course(), scores.reversed().map { it.copy(id = "new_${it.id}", updatedAt = 9000) })
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test fun fingerprintChangesWhenActualScoreOrPlanChanges() {
        val original = evaluate(course(), listOf(score())).fingerprint
        assertNotEquals(original, evaluate(course(), listOf(score(earned = 7.0))).fingerprint)
        assertNotEquals(original, evaluate(course(plan.copy(version = "v2")), emptyList()).fingerprint)
        assertNotEquals(original, evaluate(course(plan.copy(assessmentPlanKnown = false)), listOf(score())).fingerprint)
    }

    @Test fun unknownCurrentAssessmentAndDuplicateRowsAreRejectedInsteadOfDoubleCounted() {
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(course(), listOf(score(assessment = "not_planned")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(course(), listOf(score(), score(id = "duplicate")))
        }
    }

    @Test fun recordedMaximumCannotSilentlyChangeTheFixedPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(course(), listOf(score().copy(possiblePoints = 20.0)))
        }
    }

    @Test fun stableScoreIdsIncludeCoursePlanCategoryAndAssessment() {
        val id = PlannerAdapter.scoreId("course", "v1", "quiz", "q1")
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
        assertEquals(id, PlannerAdapter.scoreId("course", "v1", "quiz", "q1"))
        assertNotEquals(id, PlannerAdapter.scoreId("course", "v2", "quiz", "q1"))
        assertNotEquals(PlannerAdapter.scoreId("a_b", "c", "d", "e"), PlannerAdapter.scoreId("a", "b_c", "d", "e"))
    }

    @Test fun explicitNoPrerequisitesIsDifferentFromUnknownRule() {
        assertEquals(PrerequisiteState.MET, prerequisites(course(), listOf(course()), emptyList()).state)
        assertEquals(PrerequisiteState.UNKNOWN, prerequisites(course().copy(prerequisitesKnown = false),
            listOf(course()), emptyList()).state)
    }

    @Test fun mapsAndOrRulesAndExplicitCompletionWithoutUsingGradeAsPass() {
        val prerequisite = course().copy(id = "next", ruleKind = "ALL_OF", prerequisiteCourseIds = listOf("it101"))
        val passed = CourseAttempt("a", "it101", "demo", "COMPLETED", 3.0, "PASSED")
        val records = listOf(course(), prerequisite)
        assertEquals(PrerequisiteState.MET, prerequisites(prerequisite, records, listOf(passed)).state)
        assertEquals(PrerequisiteState.UNKNOWN, prerequisites(prerequisite, records,
            listOf(passed.copy(completionStatus = null, grade = 100.0))).state)
        assertEquals(PrerequisiteState.NOT_MET, prerequisites(prerequisite, records,
            listOf(passed.copy(status = "IN_PROGRESS", completionStatus = null))).state)
    }

    @Test fun conflictingAttemptsStayUnknownWithoutAssumedRepeatPolicy() {
        val attempts = listOf(CourseAttempt("one", "it101", "t1", "COMPLETED", 3.0, "PASSED"),
            CourseAttempt("two", "it101", "t2", "COMPLETED", 3.0, "FAILED"))
        assertEquals(CompletionStatus.UNKNOWN, PlannerAdapter.completionStates(attempts)["it101"])
    }

    @Test fun rejectsUnknownRuleKindAndMissingReferencedCourse() {
        val bad = course().copy(ruleKind = "CO_REQUISITE", prerequisiteCourseIds = listOf("it101"))
        assertEquals(PrerequisiteState.UNKNOWN, prerequisites(bad, listOf(course()), emptyList()).state)
        val missing = course().copy(ruleKind = "ALL_OF", prerequisiteCourseIds = listOf("missing"))
        assertEquals(PrerequisiteState.UNKNOWN, prerequisites(missing, listOf(course()), emptyList()).state)
    }

    @Test fun emptyLegacyAllOfRequiresReviewInsteadOfBecomingNoPrerequisites() {
        val malformed = course().copy(ruleKind = "ALL_OF")
        val result = prerequisites(malformed, listOf(malformed), emptyList())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("empty", ignoreCase = true))
    }

    @Test fun cyclicLegacyCatalogCannotBeSatisfiedByPassingRecords() {
        val first = course().copy(id = "A", ruleKind = "ALL_OF", prerequisiteCourseIds = listOf("B"))
        val second = course().copy(id = "B", ruleKind = "ALL_OF", prerequisiteCourseIds = listOf("A"))
        val passed = CourseAttempt("passed_b", "B", "demo", "COMPLETED", 3.0, "PASSED")
        val result = prerequisites(first, listOf(first, second), listOf(passed))
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("cycle", ignoreCase = true))
    }

    @Test fun duplicateLegacyReferencesCannotYieldAMetResult() {
        val dependency = course().copy(id = "A")
        val malformed = course().copy(id = "B", ruleKind = "ANY_OF", prerequisiteCourseIds = listOf("A", "A"))
        val passed = CourseAttempt("passed_a", "A", "demo", "COMPLETED", 3.0, "PASSED")
        val result = prerequisites(malformed, listOf(dependency, malformed), listOf(passed))
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("duplicate", ignoreCase = true))
    }

    private fun nestedCatalog(): List<CatalogCourse> {
        val expression = mapOf("schemaVersion" to 1, "expression" to mapOf("kind" to "AND", "children" to mapOf(
            "0" to mapOf("kind" to "COURSE", "courseId" to "A"),
            "1" to mapOf("kind" to "OR", "children" to mapOf(
                "0" to mapOf("kind" to "COURSE", "courseId" to "B"),
                "1" to mapOf("kind" to "COURSE", "courseId" to "C"))))))
        return listOf("A", "B", "C").map { course().copy(id = it) } + course().copy(id = "D",
            prerequisiteExpression = PrerequisiteCodec.parse(expression))
    }

    private fun completion(id: String, state: String) =
        CourseAttempt("outcome_$id", id, "demo", "COMPLETED", 3.0, state)

    @Test fun nestedCatalogUsesPassedAlternativeFromActualCompletionRecords() {
        val catalog = nestedCatalog()
        val result = prerequisites(catalog.last(), catalog, listOf(
            completion("A", "PASSED"), completion("B", "FAILED"), completion("C", "PASSED")))
        assertEquals(PrerequisiteState.MET, result.state)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun nestedCatalogKeepsAnUnenteredAlternativeUnknownWithItsReason() {
        val catalog = nestedCatalog()
        val result = prerequisites(catalog.last(), catalog, listOf(
            completion("A", "PASSED"), completion("B", "FAILED")))
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertEquals(listOf("B is FAILED; recorded passing completion is required", "C completion is UNKNOWN"), result.reasons)
    }

    @Test fun failedMandatoryCourseDominatesNestedUnknownAlternatives() {
        val catalog = nestedCatalog()
        val result = prerequisites(catalog.last(), catalog, listOf(completion("A", "FAILED")))
        assertEquals(PrerequisiteState.NOT_MET, result.state)
        assertEquals(listOf("A is FAILED; recorded passing completion is required",
            "B completion is UNKNOWN", "C completion is UNKNOWN"), result.reasons)
    }

    @Test fun nestedMalformedFieldCannotFallBackToLegacyNone() {
        val malformed = course().copy(prerequisiteExpression = PrerequisiteCodec.parse(mapOf(
            "schemaVersion" to 1, "expression" to mapOf("kind" to "NONE", "forged" to true))))
        val result = prerequisites(malformed, listOf(malformed), emptyList())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("fields"))
    }

    @Test fun malformedAnyOfAndContradictoryNoneDoNotBecomeKnownRules() {
        listOf(course().copy(ruleKind = "ANY_OF"), course().copy(prerequisiteCourseIds = listOf("A"))).forEach { malformed ->
            val result = prerequisites(malformed, listOf(malformed), emptyList())
            assertEquals(PrerequisiteState.UNKNOWN, result.state)
        }
    }

    @Test fun duplicateCatalogCourseIdsFailClosed() {
        val result = prerequisites(course(), listOf(course(), course()), emptyList())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("Duplicate"))
    }

    @Test fun nestedCycleCannotBeSatisfiedByActualPassingRecords() {
        val a = course().copy(id = "A", prerequisiteExpression = PrerequisiteDocument(Prerequisite.Course("B")))
        val b = course().copy(id = "B", prerequisiteExpression = PrerequisiteDocument(Prerequisite.Course("A")))
        val result = prerequisites(a, listOf(a, b), listOf(completion("B", "PASSED")))
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertEquals(listOf("Prerequisite data needs review: Prerequisite cycle: A -> B -> A"), result.reasons)
    }

    @Test fun unknownLegacyFlagCannotBeBypassedByNestedNone() {
        val unavailable = course().copy(prerequisitesKnown = false,
            prerequisiteExpression = PrerequisiteDocument(Prerequisite.AllOf(emptyList())))
        val result = prerequisites(unavailable, listOf(unavailable), emptyList())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("No approved prerequisite rule"))
    }

    @Test fun unknownLegacyFlagCannotBeBypassedByNestedPassedReference() {
        val dependency = course().copy(id = "A")
        val unavailable = course().copy(id = "B", prerequisitesKnown = false,
            prerequisiteExpression = PrerequisiteDocument(Prerequisite.Course("A")))
        val result = prerequisites(unavailable, listOf(dependency, unavailable), listOf(completion("A", "PASSED")))
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("No approved prerequisite rule"))
    }

    @Test fun unavailableFlagStillPreservesMalformedNestedDocumentReason() {
        val unavailable = course().copy(prerequisitesKnown = false, prerequisiteExpression = PrerequisiteCodec.parse(mapOf(
            "schemaVersion" to 1, "expression" to mapOf("kind" to "NONE", "forged" to true))))
        val result = prerequisites(unavailable, listOf(unavailable), emptyList())
        assertEquals(PrerequisiteState.UNKNOWN, result.state)
        assertTrue(result.reasons.single().contains("fields"))
    }
}
