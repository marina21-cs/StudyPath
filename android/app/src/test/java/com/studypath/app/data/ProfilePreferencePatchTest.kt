package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class ProfilePreferencePatchTest {
    private val draft = LearnerPreferences(levelId = "college", subjectIds = setOf("programming"))

    @Test fun trimsNameAndEditsOnlyOwnedProfileFields() {
        val patch = LearnerPreferenceRecords.profilePatch(draft, "  Keni  ", 42L)
        assertEquals(setOf("displayName", "learnerPreferences", "updatedAt"), patch.keys)
        assertEquals("Keni", patch["displayName"])
        assertEquals(42L, patch["updatedAt"])
        assertEquals(LearnerPreferenceRecords.preferences(draft), patch["learnerPreferences"])
    }

    @Test fun omittedOrBlankNameDoesNotEraseTheExistingProfile() {
        for (name in listOf(null, "", "   ")) assertFalse(LearnerPreferenceRecords.profilePatch(draft, name, 42L).containsKey("displayName"))
    }

    @Test fun optionalUiDefaultAndEightyCharacterBoundaryAreValid() {
        assertEquals("Student", LearnerPreferenceRecords.profilePatch(draft, "Student", 42L)["displayName"])
        assertEquals("a".repeat(80), LearnerPreferenceRecords.profilePatch(draft, "a".repeat(80), 42L)["displayName"])
    }

    @Test(expected = IllegalArgumentException::class) fun tooLongNameIsRejectedBeforeWriting() {
        LearnerPreferenceRecords.profilePatch(draft, "a".repeat(81), 42L)
    }

    @Test fun coursePackSelectionKeepsPublishedMembershipAndDraftFallback() {
        val option = LearnerPreferenceOptions("mock_v1", "mock_college", setOf("computing"), true, "APPROVED")
        val selected = LearnerPreferences(option.levelId, option.curriculumVersion, option.subjectIds)
        assertTrue(PreferenceChoices.forSave(selected, listOf(option)).onboardingComplete)
        assertFalse(PreferenceChoices.forSave(selected.copy(subjectIds = setOf("custom")), listOf(option)).onboardingComplete)
        assertFalse(PreferenceChoices.forSave(selected, listOf(option.copy(approved = false))).onboardingComplete)
    }
}
