package com.studypath.app

import com.studypath.app.domain.StudentDemoSession
import com.studypath.app.data.PersonalStudyStore
import org.junit.Assert.*
import org.junit.Test

class StudentDemoTest {
    @Test fun fixtureHasConnectedRecordsAndTransparentArithmetic() {
        val s = StudentDemoSession()
        // Demo IDs intentionally cannot be written through the personal account store.
        assertThrows(IllegalArgumentException::class.java) { PersonalStudyStore.validate(s.workspace) }
        val ids=s.workspace.subjects.map { it.id }.toSet()
        assertEquals(5,ids.size)
        assertTrue(s.workspace.materials.all { it.subjectId in ids && it.text.isNotBlank() })
        assertTrue(s.workspace.tasks.all { it.subjectId in ids })
        assertTrue(s.workspace.subjects.all { it.prerequisiteIds.orEmpty().all { id -> id in ids && id != it.id } })
        assertEquals(5,s.workspace.subjects.size); assertEquals(9,s.workspace.materials.size)
        assertEquals(3,s.workspace.tasks.size); assertEquals(15.0,s.includedUnits(),0.0)
        assertEquals(1.75,s.gwa()!!,0.0001); assertEquals(84.6,s.estimate(),0.0001)
        assertEquals(75.0,s.projection(),0.0001)
        s.included.remove("physics"); assertEquals(1.625,s.gwa()!!,0.0001)
        s.included.clear(); assertNull(s.gwa())
    }
    @Test fun sessionsAreIsolatedAndResetRestoresOriginalRecords() {
        val a=StudentDemoSession(); val b=StudentDemoSession()
        a.rename("Sam Demo"); a.toggleTask("t1");a.setQuiz(0.0);a.setExam(0);a.included.clear()
        assertEquals("Alex Reyes",b.name);assertFalse(b.workspace.tasks.first().done)
        assertEquals(1.75,b.gwa()!!,0.0);assertEquals(84.6,b.estimate(),0.001)
        a.reset();assertEquals(b.workspace,a.workspace);assertEquals(b.name,a.name)
        assertEquals(b.included,a.included);assertEquals(84.6,a.estimate(),0.001)
    }
    @Test fun estimateAndScenarioNeverMutateRecordedFinalGrades() {
        val s=StudentDemoSession();val records=s.workspace
        s.setQuiz(20.0);s.setExam(100)
        assertEquals(88.6,s.estimate(),0.001);assertEquals(81.0,s.projection(),0.001)
        assertEquals(records,s.workspace);assertEquals(1.75,s.gwa()!!,0.0)
    }
    @Test fun invalidInputIsRejectedBeforeMutation() {
        val s=StudentDemoSession()
        listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,21.0).forEach { assertThrows(IllegalArgumentException::class.java) { s.setQuiz(it) } }
        assertThrows(IllegalArgumentException::class.java) { s.rename(" ") }
        assertThrows(IllegalArgumentException::class.java) { s.toggleTask("unknown") }
        assertThrows(IllegalArgumentException::class.java) { s.setExam(101) }
        assertEquals(84.6,s.estimate(),0.001)
    }
}
