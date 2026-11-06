package com.studypath.app

import com.studypath.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class SemesterGradingTest {
    private fun template()=PersonalGradeDraft(categories=listOf(DraftCategory("a","Quizzes",.3),DraftCategory("b","Midterm",.3),DraftCategory("c","Final exam",.4)),scale=DraftGradeScale())
    @Test fun appliesAllFiveAndPreservesEveryScoreAndFinalGrade() {
        val old=StudentDemo.workspace();val next=SemesterGrading.apply(old,template())
        assertEquals(5,next.subjects.count { it.gradeDraft?.categories?.size==3 })
        assertEquals(old.subjects.map { it.grade },next.subjects.map { it.grade })
        assertEquals(old.subjects.first().gradeDraft!!.scores,next.subjects.first().gradeDraft!!.scores)
        assertEquals("quiz",next.subjects.first().gradeDraft!!.categories.first().id)
        assertEquals(.3,next.subjects.first().gradeDraft!!.categories.first().weight,0.0)
        assertEquals(old.materials,next.materials);assertEquals(old.tasks,next.tasks)
        assertEquals(3,next.subjects.first().gradeDraft!!.plan().categories.size)
    }
    @Test fun conflictingApplyLeavesWholeDemoUntouched() {
        val session=StudentDemoSession();val old=session.workspace
        assertThrows(IllegalArgumentException::class.java) { session.applyGrading(template().copy(categories=listOf(DraftCategory("only","Activities",1.0)))) }
        assertEquals(old,session.workspace)
    }
    @Test fun invalidWeightsScaleAndDuplicateNamesAreRejected() {
        val workspace=StudentDemo.workspace();val valid=template()
        listOf(valid.copy(scale=null),valid.copy(categories=valid.categories.map { it.copy(weight=.2) }),valid.copy(categories=listOf(DraftCategory("a","Quiz",.5),DraftCategory("b"," quiz ",.5)))).forEach {
            assertThrows(IllegalArgumentException::class.java) { SemesterGrading.apply(workspace,it) }
        }
    }
    @Test fun refusesToReinterpretExistingScoresOrDuplicateCategories() {
        val workspace=StudentDemo.workspace();val valid=template()
        assertThrows(IllegalArgumentException::class.java) { SemesterGrading.apply(workspace,valid.copy(categories=valid.categories.map { it.copy(aggregation="EQUAL_ITEMS") })) }
        val duplicate=workspace.copy(subjects=workspace.subjects.map { s -> if(s.id=="calculus")s.copy(gradeDraft=s.gradeDraft!!.copy(categories=s.gradeDraft!!.categories+DraftCategory("duplicate","Quizzes",0.0))) else s })
        assertThrows(IllegalArgumentException::class.java) { SemesterGrading.apply(duplicate,valid) }
    }
    @Test fun flagsPreviouslyPreparedReviewAndKeepsSyllabus() {
        val workspace=StudentDemo.workspace();val subject=workspace.subjects.first()
        val next=SemesterGrading.apply(workspace.copy(subjects=listOf(subject.copy(gradeDraft=subject.gradeDraft!!.copy(reviewPrepared=true)))),template())
        assertFalse(next.subjects.first().gradeDraft!!.reviewPrepared)
        assertEquals(subject.gradeDraft!!.syllabus,next.subjects.first().gradeDraft!!.syllabus)
    }
}
