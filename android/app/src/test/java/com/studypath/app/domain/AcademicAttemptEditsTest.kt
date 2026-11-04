package com.studypath.app.domain

import com.studypath.app.data.*
import org.junit.Assert.*
import org.junit.Test

class AcademicAttemptEditsTest {
    private fun permissions(enabled: Set<AcademicCapability>): List<AcademicAccess> {
        val policy = AcademicPolicySnapshot("fixture", "college", "HIGHER_EDUCATION", setOf("subject"), "APPROVED",
            "synthetic-approval", "Synthetic reviewer", 1_700_000_000_000, "fixture-source", "fixture-context", 1,
            1_700_000_000_000, 4_102_444_800_000, enabled, enabled.associateWith { "${it.name}-policy" },
            mapOf("course" to AcademicCourseBinding("subject", "plan")),
            AcademicGwaPolicy("scale", 1.0, 5.0, "EXPLICIT", "retakes", "exclusions", "HALF_UP", 2))
        return listOf(AcademicCapability.SCORES, AcademicCapability.PREREQUISITES, AcademicCapability.GWA).map {
            AcademicAccess.resolve(LearnerPreferences("college", "fixture", setOf("subject"), onboardingComplete = true),
                LearnerPreferenceOptions("fixture", "college", setOf("subject"), true, "APPROVED"), policy, "course", it)
        }
    }

    @Test fun gwaOnlyPolicyCanExcludeOrChangeAnAttemptWithoutEnablingScores() {
        val available = permissions(setOf(AcademicCapability.GWA))
        val oldBinding = AcademicRecordBinding("fixture", 1, "GWA-policy", "plan")
        val chosen = AcademicAttemptEdits.permission("course", false, null, available, oldBinding)
        assertEquals(AcademicCapability.GWA, chosen.capability)
        assertTrue(chosen.matches(oldBinding))
        assertFalse(available.first().allowed)
    }

    @Test fun prerequisiteOnlyPolicyCanSaveANoncompletedAttemptWithNoOutcome() {
        val chosen = AcademicAttemptEdits.permission("course", false, null,
            permissions(setOf(AcademicCapability.PREREQUISITES)))
        assertEquals(AcademicCapability.PREREQUISITES, chosen.capability)
    }

    @Test fun inclusionAndOutcomeEachRequireTheirOwnCapability() {
        assertThrows(IllegalArgumentException::class.java) {
            AcademicAttemptEdits.permission("course", true, null, permissions(setOf(AcademicCapability.SCORES)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AcademicAttemptEdits.permission("course", true, "PASSED", permissions(setOf(AcademicCapability.GWA)))
        }
        val chosen = AcademicAttemptEdits.permission("course", true, "PASSED",
            permissions(setOf(AcademicCapability.GWA, AcademicCapability.PREREQUISITES)))
        assertEquals(AcademicCapability.GWA, chosen.capability)
    }

    @Test fun legalEditKeepsItsOriginalCapabilityBindingWhenMoreCapabilitiesAreAvailable() {
        val binding = AcademicRecordBinding("fixture", 1, "GWA-policy", "plan")
        val chosen = AcademicAttemptEdits.permission("course", false, "UNKNOWN",
            permissions(setOf(AcademicCapability.SCORES, AcademicCapability.GWA, AcademicCapability.PREREQUISITES)), binding)
        assertTrue(chosen.matches(binding))
        assertEquals(AcademicCapability.GWA, chosen.capability)
    }

    @Test fun unavailableOrForeignCoursePermissionCannotEnableEditing() {
        assertThrows(IllegalArgumentException::class.java) {
            AcademicAttemptEdits.permission("course", false, null, permissions(emptySet()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AcademicAttemptEdits.permission("foreign", false, null, permissions(setOf(AcademicCapability.GWA)))
        }
    }
}
