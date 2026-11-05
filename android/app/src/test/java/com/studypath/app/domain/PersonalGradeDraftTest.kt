package com.studypath.app.domain
import org.junit.Test
import org.junit.Assert.*
class PersonalGradeDraftTest {
    @Test fun rawDraftPreservesZeroAndUngraded(){
        val d=PersonalGradeDraft(listOf(DraftCategory("a","Quiz",1.0)),listOf(DraftScore("one","a","Quiz 1",20.0,0.0,"SCORED"),DraftScore("two","a","Quiz 2",20.0)))
        d.validate();val summary=GradeEngine.evaluate(d.plan());assertEquals(0.0,summary.gradedWorkPercent!!,0.0);assertNull(summary.maximumFinalPercent)
    }
    @Test fun slideExampleUsesActualWeightedEngine(){
        val d=PersonalGradeDraft(listOf(DraftCategory("a","Quiz",.2),DraftCategory("b","Midterm",.3),DraftCategory("c","Final",.5)),listOf(DraftScore("a","a","Quiz",20.0,16.0,"SCORED"),DraftScore("b","b","Midterm",100.0,82.0,"SCORED"),DraftScore("c","c","Final",100.0,88.0,"SCORED")))
        assertEquals(84.6,GradeEngine.evaluate(d.plan()).gradedWorkPercent!!,.000001)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonFiniteScores(){PersonalGradeDraft(listOf(DraftCategory("a","Quiz",1.0)),listOf(DraftScore("a","a","Quiz",20.0,Double.NaN,"SCORED"))).validate()}
}
