package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class LearnerPreferencesTest {
    private fun profile(vararg fields: Pair<String, Any?>) = mapOf("learnerPreferences" to mapOf(*fields))
    private fun full() = LearnerPreferences("fixture_level", "fixture_v1", setOf("math", "reading"), "fixture_section", true)
    private fun options() = LearnerPreferenceOptions("fixture_v1", "fixture_level", setOf("math", "reading"), true, "APPROVED")
    private fun rejected(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun legacyProfileBecomesIncompleteWithoutMutationOrDemoSelection() {
        val old = linkedMapOf<String, Any?>("displayName" to "Synthetic", "selectedCurriculumVersion" to "bsit_demo_v1", "createdAt" to 42L)
        val before = old.toMap()
        assertEquals(LearnerPreferences(), LearnerPreferenceRecords.fromProfile(old))
        assertEquals(before, old)
        assertNull(LearnerPreferenceRecords.fromProfile(old).curriculumVersion)
    }

    @Test fun versionedPreferencesRoundTripWithoutLosingSelections() {
        val expected = full()
        val record = LearnerPreferenceRecords.preferences(expected)
        assertEquals(1, record["schemaVersion"])
        assertEquals(mapOf("math" to true, "reading" to true), record["subjectIds"])
        assertEquals(expected, LearnerPreferenceRecords.fromProfile(mapOf("learnerPreferences" to record)))
    }

    @Test fun emptyDraftHasExplicitVersionAndFalseCompletionWithoutEmptyFirebaseMap() {
        assertEquals(mapOf("schemaVersion" to 1, "onboardingComplete" to false), LearnerPreferenceRecords.preferences(LearnerPreferences()))
    }

    @Test fun partialUnsupportedDraftRemainsIncomplete() {
        val draft = LearnerPreferences(levelId = "unselected_level", subjectIds = setOf("draft_subject"))
        assertEquals(draft, LearnerPreferenceRecords.fromProfile(mapOf("learnerPreferences" to LearnerPreferenceRecords.preferences(draft))))
        assertFalse(draft.supportedBy(options()))
    }

    @Test fun futureSchemaAndMalformedStoredPreferencesAreRejectedWithoutMigration() {
        for (version in listOf(0, 2, "1", 1.5, true)) {
            rejected { LearnerPreferenceRecords.fromProfile(profile("schemaVersion" to version, "onboardingComplete" to false)) }
        }
        rejected { LearnerPreferenceRecords.fromProfile(profile("schemaVersion" to 1, "onboardingComplete" to false, "isTeacher" to true)) }
        rejected { LearnerPreferenceRecords.fromProfile(mapOf("learnerPreferences" to "invalid")) }
        rejected { LearnerPreferenceRecords.fromProfile(profile("schemaVersion" to 1)) }
        rejected { LearnerPreferenceRecords.fromProfile(profile("schemaVersion" to 1, "onboardingComplete" to "false")) }
    }

    @Test fun subjectMapsRejectFalseUnknownTypesInvalidIdsAndExcessiveSelections() {
        for (subjects in listOf(mapOf("math" to false), mapOf("math" to 1), mapOf("bad/id" to true), (1..21).associate { "s$it" to true }, listOf("math"))) {
            rejected { LearnerPreferenceRecords.fromProfile(profile("schemaVersion" to 1L, "onboardingComplete" to false, "subjectIds" to subjects)) }
        }
    }

    @Test fun completionRequiresLevelCurriculumAndSubjects() {
        for (invalid in listOf(full().copy(levelId = null), full().copy(curriculumVersion = null), full().copy(subjectIds = emptySet()))) {
            rejected { LearnerPreferenceRecords.preferences(invalid) }
        }
        rejected { LearnerPreferenceRecords.preferences(full().copy(classSectionId = "../restricted")) }
    }

    @Test fun completedPreferencesNeedMatchingApprovedOptions() {
        val complete = full()
        assertTrue(complete.supportedBy(options()))
        assertFalse(complete.supportedBy(null))
        assertFalse(complete.supportedBy(options().copy(approved = false)))
        assertFalse(complete.supportedBy(options().copy(approvalStatus = "DEMONSTRATION_ONLY")))
        assertFalse(complete.supportedBy(options().copy(levelId = "other_level")))
        assertFalse(complete.supportedBy(options().copy(curriculumVersion = "other_v1")))
        assertFalse(complete.supportedBy(options().copy(subjectIds = setOf("math"))))
    }

    @Test fun aClassPreferenceDoesNotDetermineSupportedSubjectsOrMembership() {
        assertTrue(full().copy(classSectionId = "unassigned_class").supportedBy(options()))
        assertFalse(full().copy(subjectIds = setOf("unpublished_subject")).supportedBy(options()))
    }

    @Test fun optionIndexParsingIsVersionedAndStrict() {
        val record = mapOf<String, Any?>("schemaVersion" to 1L, "curriculumVersion" to "fixture_v1", "levelId" to "fixture_level",
            "subjectIds" to mapOf("math" to true, "reading" to true), "approved" to true, "approvalStatus" to "APPROVED")
        assertEquals(options(), LearnerPreferenceRecords.options(record))
        assertNull(LearnerPreferenceRecords.options(null))
        rejected { LearnerPreferenceRecords.options(record + ("schemaVersion" to 2)) }
        rejected { LearnerPreferenceRecords.options(record + ("subjectIds" to mapOf("math" to false))) }
        rejected { LearnerPreferenceRecords.options(record + ("unexpected" to true)) }
    }
}
