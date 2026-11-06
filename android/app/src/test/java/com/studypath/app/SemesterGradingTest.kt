package com.studypath.app

import com.studypath.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class SemesterGradingTest {
    private fun template()=PersonalGradeDraft(categories=listOf(DraftCategory("activities","Activities",.6),DraftCategory("exams","Exams / Tests",.4)),scale=DraftGradeScale())
    @Test fun replacementInitializesEverySubjectWithoutLegacyCategoryRequirements() {
        val session=StudentDemoSession();val old=session.workspace
        session.applyGrading(template())
        val next=session.workspace
        assertEquals(5,next.subjects.size)
        assertTrue(next.subjects.all { it.gradeDraft!!.categories==template().categories && it.gradeDraft!!.scores.isEmpty() })
        assertEquals(old.subjects.map { it.grade },next.subjects.map { it.grade })
        assertEquals(old.subjects.first().gradeDraft,next.subjects.first().gradeHistory!!.single())
        assertEquals(old.materials,next.materials);assertEquals(old.tasks,next.tasks)
        assertEquals(template(),next.gradingTemplate)
    }
    @Test fun repeatReplacementKeepsHistoryWithoutDuplicatingEmptySetup() {
        val first=SemesterGrading.apply(StudentDemo.workspace(),template())
        val second=SemesterGrading.apply(first,template())
        assertEquals(first,second)
        val subject=second.subjects.first()
        val scored=subject.gradeDraft!!.copy(scores=listOf(DraftScore("lab","activities","Lab",10.0,8.0,"SCORED")))
        val third=SemesterGrading.apply(second.copy(subjects=second.subjects.map { if(it.id==subject.id)it.copy(gradeDraft=scored)else it }),template())
        assertEquals(subject.gradeHistory!!+scored,third.subjects.first().gradeHistory)
    }
    @Test fun invalidTemplatesLeaveWholeDemoUntouched() {
        val session=StudentDemoSession();val old=session.workspace;val valid=template()
        listOf(valid.copy(scale=null),valid.copy(categories=valid.categories.map { it.copy(weight=.2) }),
            valid.copy(categories=listOf(DraftCategory("a","Quiz",.5),DraftCategory("b"," quiz ",.5))),
            valid.copy(scores=listOf(DraftScore("lab","activities","Lab",10.0,8.0,"SCORED")))).forEach {
            assertThrows(IllegalArgumentException::class.java) { session.applyGrading(it) }
            assertEquals(old,session.workspace)
        }
    }
    @Test fun newAggregationDoesNotReinterpretOldScores() {
        val old=StudentDemo.workspace()
        val next=SemesterGrading.apply(old,template().copy(categories=template().categories.map { it.copy(aggregation="EQUAL_ITEMS") }))
        assertEquals(old.subjects.first().gradeDraft,next.subjects.first().gradeHistory!!.single())
        assertTrue(next.subjects.all { it.gradeDraft!!.scores.isEmpty() && it.gradeDraft!!.categories.all { c -> c.aggregation=="EQUAL_ITEMS" } })
    }
    @Test fun flagsPreviouslyPreparedReviewAndKeepsSyllabus() {
        val workspace=StudentDemo.workspace();val subject=workspace.subjects.first()
        val next=SemesterGrading.apply(workspace.copy(subjects=listOf(subject.copy(gradeDraft=subject.gradeDraft!!.copy(reviewPrepared=true,syllabus="Course syllabus")))),template())
        assertFalse(next.subjects.first().gradeDraft!!.reviewPrepared)
        assertEquals("Course syllabus",next.subjects.first().gradeDraft!!.syllabus)
        assertTrue(next.subjects.first().gradeHistory!!.single().reviewPrepared)
    }
}
