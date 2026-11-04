package com.studypath.app.ui.planner

import com.studypath.app.data.*
import com.studypath.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class PlannerScenarioTest {
    private val course = CatalogCourse("course", "Synthetic scenario fixture", 3.0, "Computing", "module", true,
        "NONE", emptyList(), CatalogGradePlan("plan", true, true, "Synthetic reviewed plan", listOf(
            CatalogCategory("quiz", 1.0, "POOLED_POINTS", listOf(CatalogAssessment("one", "One", 10.0),
                CatalogAssessment("two", "Two", 30.0))))))
    private fun access(capability: AcademicCapability, revision: Int = 1, plan: String = "plan", curriculum: String = "fixture",
                       scenarioRef: String = "scenarios-policy"): AcademicAccess {
        val policy = AcademicPolicySnapshot(curriculum, "college", "HIGHER_EDUCATION", setOf("subject"), "APPROVED",
            "synthetic-approval", "Synthetic reviewer", 1_700_000_000_000, "fixture-source", "fixture-context", revision,
            1_700_000_000_000, 4_102_444_800_000, setOf(AcademicCapability.SCORES, AcademicCapability.SCENARIOS),
            mapOf(AcademicCapability.SCORES to "scores-policy", AcademicCapability.SCENARIOS to scenarioRef),
            mapOf("course" to AcademicCourseBinding("subject", plan)))
        return AcademicAccess.resolve(LearnerPreferences("college", curriculum, setOf("subject"), onboardingComplete = true),
            LearnerPreferenceOptions(curriculum, "college", setOf("subject"), true, "APPROVED"), policy, "course", capability, plan)
    }
    private val actual = listOf(ScoreRecord("actual", "course", "one", "quiz", "SCORED", 10.0, 8.0,
        planVersion = "plan", policyBinding = AcademicRecordBinding("fixture", 1, "scores-policy", "plan")))

    @Test fun scenarioOverridesChangeOnlyHypotheticalRowsAndLeaveActualRecordsUnchanged() {
        val original = actual.toList()
        val scores = AcademicScenarios.overlay(course, actual, mapOf(AcademicScenarios.overrideKey("quiz", "two") to 50.0),
            access(AcademicCapability.SCORES), access(AcademicCapability.SCENARIOS))
        val result = PlannerAdapter.evaluate(course, scores, access(AcademicCapability.SCORES))
        // Independently worked pooled points: (8 + 50% of 30) / (10+30) = 57.5%.
        assertEquals(57.5, result.summary!!.minimumFinalPercent!!, 1e-9)
        assertTrue(result.summary.complete)
        assertEquals(original, actual)
        assertEquals(1, actual.size)
        assertEquals(8.0, actual.single().earnedPoints!!, 0.0)
    }

    @Test fun repositoryAndPresentationShareOneActualBaseFingerprintAndAnEditMakesItStale() {
        val permission = access(AcademicCapability.SCORES)
        val current = AcademicPlanFingerprint.compute(course, actual, permission)
        assertEquals(PlannerAdapter.evaluate(course, actual, permission).fingerprint, current)
        assertNotEquals(current, AcademicPlanFingerprint.compute(course, actual.map { it.copy(earnedPoints = 7.0) }, permission))
        val scenario = GradeScenario("saved", "course", "Saved", 75.0, current,
            policyBinding = AcademicRecordBinding("fixture", 1, "scenarios-policy", "plan"))
        assertFalse(AcademicScenarios.isCurrent(scenario,
            AcademicPlanFingerprint.compute(course, actual.map { it.copy(earnedPoints = 7.0) }, permission), access(AcademicCapability.SCENARIOS)))
    }

    @Test fun malformedOrUnknownOverrideCannotBecomeAnActiveScenario() {
        listOf(mapOf("foreign" to 50.0), mapOf(AcademicScenarios.overrideKey("quiz", "one") to Double.NaN),
            mapOf(AcademicScenarios.overrideKey("quiz", "one") to 100.1)).forEach { overrides ->
            assertThrows(IllegalArgumentException::class.java) { AcademicScenarios.overlay(course, actual, overrides,
                access(AcademicCapability.SCORES), access(AcademicCapability.SCENARIOS)) }
        }
    }

    @Test fun categoryIdentityDisambiguatesRepeatedAssessmentIds() {
        assertNotEquals(AcademicScenarios.overrideKey("quiz", "one"), AcademicScenarios.overrideKey("exam", "one"))
        assertTrue(AcademicScenarios.overrideKey("quiz", "one").matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    @Test fun staleReplayRequiresAnExplicitRebaseAndNeverChangesTheActualBase() {
        val scenario = GradeScenario("scenario", "course", "What if", 75.0, "old-base", overrides = mapOf(
            AcademicScenarios.overrideKey("quiz", "two") to 50.0), policyBinding = AcademicRecordBinding("fixture", 1, "scenarios-policy", "plan"))
        val scenarioAccess = access(AcademicCapability.SCENARIOS)
        assertFalse(AcademicScenarios.isCurrent(scenario, "current-base", scenarioAccess))
        val rebased = AcademicScenarios.rebase(scenario, "current-base", scenarioAccess)
        assertTrue(AcademicScenarios.isCurrent(rebased, "current-base", scenarioAccess))
        assertEquals(scenario.id, rebased.id)
        assertEquals(scenario.overrides, rebased.overrides)
        assertEquals("old-base", scenario.baseVersion)
        assertNull(rebased.requiredPercent)
    }

    @Test fun policyOrPlanRebaseCreatesANewScenarioWhileRetainingTheOriginal() {
        val original = GradeScenario("retained", "course", "Old policy", 75.0, "old-base", requiredPercent = 80.0,
            overrides = mapOf(AcademicScenarios.overrideKey("quiz", "two") to 50.0),
            policyBinding = AcademicRecordBinding("fixture", 1, "scenarios-policy", "plan"))
        val before = original.copy(overrides = original.overrides.toMap())
        for (changed in listOf(
            access(AcademicCapability.SCENARIOS, revision = 2),
            access(AcademicCapability.SCENARIOS, plan = "plan2"),
            access(AcademicCapability.SCENARIOS, curriculum = "fixture2"),
            access(AcademicCapability.SCENARIOS, scenarioRef = "replacement-policy"),
        )) {
            assertTrue(changed.allowed)
            val rebased = AcademicScenarios.rebase(original, "current-base", changed)
            assertNotEquals("A changed immutable binding must not overwrite the retained record", original.id, rebased.id)
            assertTrue(AcademicScenarios.isCurrent(rebased, "current-base", changed))
            assertEquals(original.overrides, rebased.overrides)
            assertNull(rebased.requiredPercent)
            assertEquals(before, original)
        }
    }
}
