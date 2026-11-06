package com.studypath.app.domain

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class DraftGradeScaleTest {
    @Test fun passingMarkDoesNotTransmuteRawPercentage() {
        val scale=DraftGradeScale(passingPercent=75.0)
        assertEquals("0.00 / 100",scale.convert(0.0))
        assertEquals("80.00 / 100",scale.convert(80.0))
    }
    @Test fun explicitLinearRangeUsesBothEndpoints() {
        val scale=DraftGradeScale("LINEAR",75.0,100.0)
        assertEquals("75.00",scale.convert(0.0)); assertEquals("95.00",scale.convert(80.0)); assertEquals("100.00",scale.convert(100.0))
    }
    @Test fun letterBandsUseUnroundedInclusiveThresholds() {
        val scale=DraftGradeScale(mode="BANDS",bands=listOf(DraftGradeBand(80.0,"B"),DraftGradeBand(0.0,"F"),DraftGradeBand(90.0,"A")))
        assertEquals("F",scale.convert(79.999999));assertEquals("B",scale.convert(80.0));assertEquals("B",scale.convert(89.999999));assertEquals("A",scale.convert(90.0));assertEquals("A",scale.convert(100.0))
    }
    @Test fun customNumericBandsAreLabelsNotGwaConversion() {
        val scale=DraftGradeScale(mode="BANDS",bands=listOf(DraftGradeBand(0.0,"5.00"),DraftGradeBand(75.0,"3.00"),DraftGradeBand(90.0,"1.00")))
        assertEquals("3.00",scale.convert(85.0))
    }
    @Test fun invalidScalesFailWithoutInventingThresholds() {
        val invalid=listOf(DraftGradeScale(mode="UNKNOWN"),DraftGradeScale("LINEAR",100.0,75.0),DraftGradeScale("LINEAR",Double.NaN,100.0),DraftGradeScale(passingPercent=101.0),
            DraftGradeScale(mode="BANDS",bands=listOf(DraftGradeBand(75.0,"C"),DraftGradeBand(90.0,"A"))),
            DraftGradeScale(mode="BANDS",bands=listOf(DraftGradeBand(0.0,"F"),DraftGradeBand(0.0,"A"))))
        invalid.forEach { assertTrue(runCatching { it.validate() }.isFailure) }
    }
    @Test fun oldStoredDraftRemainsRawAndNewScaleRoundTrips() {
        val old=Gson().fromJson("""{"categories":[],"scores":[],"syllabus":"old","reviewPrepared":false}""",PersonalGradeDraft::class.java)
        old.validate();assertNull(old.scale)
        val current=old.copy(scale=DraftGradeScale(mode="BANDS",bands=listOf(DraftGradeBand(0.0,"F"),DraftGradeBand(75.0,"P"))))
        val restored=Gson().fromJson(Gson().toJson(current),PersonalGradeDraft::class.java);restored.validate();assertEquals(current,restored)
    }
    @Test fun activitiesProjectsHomeworkAndTestsUseTheirOwnWeights() {
        val draft=PersonalGradeDraft(listOf(DraftCategory("a","Activities",.3),DraftCategory("e","Exams / Tests",.3),DraftCategory("p","Projects",.2),DraftCategory("h","Homework",.2)),
            listOf(DraftScore("a","a","Activity 1",20.0,10.0,"SCORED"),DraftScore("e","e","Test 1",50.0,40.0,"SCORED"),DraftScore("p","p","Project",100.0,90.0,"SCORED"),DraftScore("h","h","Homework",10.0,0.0,"SCORED"),DraftScore("later","h","Not graded",10.0)))
        draft.validate();assertEquals(57.0,GradeEngine.evaluate(draft.plan()).gradedWorkPercent!!,.00001)
        assertNull(GradeEngine.evaluate(draft.plan()).maximumFinalPercent)
    }
    @Test fun incompleteCategoryWeightsCannotCalculate() {
        val draft=PersonalGradeDraft(listOf(DraftCategory("a","Activities",.3)),listOf(DraftScore("a","a","Activity",10.0,8.0,"SCORED")))
        draft.validate();assertTrue(runCatching { GradeEngine.evaluate(draft.plan()) }.isFailure)
    }
    @Test fun demoDraftChangesRemainSeparateFromRecordedGradesAndReset() {
        val demo=StudentDemoSession();val original=demo.workspace.subjects.first()
        demo.saveDraft(original.id,original.gradeDraft!!.copy(scale=DraftGradeScale("LINEAR",75.0,100.0)))
        assertEquals(original.grade,demo.workspace.subjects.first().grade); assertEquals(1.75,demo.gwa()!!,.000001)
        demo.reset();assertEquals(original.gradeDraft,demo.workspace.subjects.first().gradeDraft)
    }
}
