package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test

class PreferenceChoicesTest {
    private val approved = LearnerPreferenceOptions("reviewed_v1", "college", setOf("programming", "databases"), true, "APPROVED")

    @Test fun anyStudentLevelCanBeRetainedAsAnIncompleteDraft() {
        val draft = LearnerPreferences(PreferenceChoices.customId("Graduate research"), subjectIds = setOf("astronomy"))
        val result = PreferenceChoices.forSave(draft, emptyList())
        assertEquals("graduate_research", result.levelId)
        assertEquals(setOf("astronomy"), result.subjectIds)
        assertFalse(result.onboardingComplete)
        assertNull(result.curriculumVersion)
    }

    @Test fun onlyMatchingApprovedCatalogsCanCompletePreferences() {
        val draft = LearnerPreferences("college", subjectIds = setOf("programming"))
        val variants = listOf(approved.copy(approved = false), approved.copy(approvalStatus = "REVIEW_PENDING"),
            approved.copy(levelId = "grade_10"), approved.copy(subjectIds = setOf("science")))
        assertTrue(PreferenceChoices.matching(draft, variants).isEmpty())
        assertFalse(PreferenceChoices.forSave(draft, variants).onboardingComplete)
        val complete = PreferenceChoices.forSave(draft, listOf(approved))
        assertEquals("reviewed_v1", complete.curriculumVersion)
        assertTrue(complete.supportedBy(approved))
    }

    @Test fun ambiguousApprovedCurriculaRequireAnExplicitSelection() {
        val choices = listOf(approved, approved.copy(curriculumVersion = "reviewed_v2"))
        val draft = LearnerPreferences("college", subjectIds = setOf("databases"))
        assertFalse(PreferenceChoices.forSave(draft, choices).onboardingComplete)
        assertNull(PreferenceChoices.forSave(draft, choices).curriculumVersion)
        val selected = PreferenceChoices.forSave(draft.copy(curriculumVersion = "reviewed_v2"), choices)
        assertTrue(selected.onboardingComplete)
        assertEquals("reviewed_v2", selected.curriculumVersion)
    }

    @Test fun emptySubjectsOrUnsupportedExistingCurriculumRemainDrafts() {
        assertFalse(PreferenceChoices.forSave(LearnerPreferences("college"), listOf(approved)).onboardingComplete)
        val draft = LearnerPreferences("college", "old_v1", setOf("astronomy"), "section_a", true)
        val result = PreferenceChoices.forSave(draft, listOf(approved))
        assertFalse(result.onboardingComplete)
        assertEquals("section_a", result.classSectionId)
        assertEquals(setOf("astronomy"), result.subjectIds)
    }

    @Test fun displayLabelsAreReadableAndCustomNamesProduceValidKeys() {
        assertEquals("Grade 10", PreferenceChoices.label("grade_10"))
        assertEquals("Data structures", PreferenceChoices.label("data_structures"))
        assertEquals("Section A", PreferenceChoices.label("section_a"))
        assertEquals("grade_10_honors", PreferenceChoices.customId(" Grade 10 / Honors "))
        DatabaseRecords.requireKey(PreferenceChoices.customId("Robotics & automation"))
    }

    @Test fun standardChoicesIncludeSchoolAndHigherEducationAlongsidePublishedLevels() {
        val levels = PreferenceChoices.levels(listOf(approved.copy(levelId = "professional_study")), "custom_level")
        assertTrue((1..12).all { grade -> levels.any { it.id == "grade_$grade" } })
        assertTrue(levels.any { it.id == "college" })
        assertTrue(levels.any { it.id == "graduate" })
        assertTrue(levels.any { it.id == "professional_study" })
        assertTrue(levels.any { it.id == "custom_level" })
    }
}
