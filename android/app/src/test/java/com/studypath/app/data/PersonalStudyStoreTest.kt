package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalStudyStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun subject() = PersonalSubject(PersonalStudyStore.newId(), "Physics")
    @Test fun `subjects tasks and authored lessons survive reopening and remain owner isolated`() {
        val root = temp.newFolder(); val store = PersonalStudyStore(root, "owner-a"); val subject = subject()
        val task = PersonalTask(PersonalStudyStore.newId(), subject.id, "Read chapter 2", "2026-11-08", true)
        val lesson = PersonalMaterial(PersonalStudyStore.newId(), subject.id, "Motion", "Study notes", "What is speed?", "Distance per time")
        val expected = store.update { PersonalWorkspace(listOf(subject), listOf(task), listOf(lesson)) }
        assertEquals(expected, PersonalStudyStore(root, "owner-a").read())
        assertEquals(PersonalWorkspace(), PersonalStudyStore(root, "owner-b").read())
    }
    @Test fun `personal grading configuration and scores survive reopening without changing final grade`() {
        val root=temp.newFolder();val store=PersonalStudyStore(root,"grade-owner")
        val draft=com.studypath.app.domain.PersonalGradeDraft(
            categories=listOf(com.studypath.app.domain.DraftCategory("activities","Activities",1.0)),
            scores=listOf(com.studypath.app.domain.DraftScore("lab","activities","Lab",20.0,16.0,"SCORED")),
            scale=com.studypath.app.domain.DraftGradeScale("LINEAR",75.0,100.0))
        val subject=subject().copy(grade="1.75",gradeDraft=draft)
        store.update { it.copy(subjects=listOf(subject)) }
        val reopened=PersonalStudyStore(root,"grade-owner").read().subjects.single()
        assertEquals(draft,reopened.gradeDraft);assertEquals("1.75",reopened.grade)
        assertTrue(PersonalStudyStore(root,"other-owner").read().subjects.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(subjects=listOf(subject.copy(gradeDraft=draft.copy(scale=com.studypath.app.domain.DraftGradeScale("LINEAR",100.0,75.0))))) } }
        assertEquals(subject,store.read().subjects.single())
    }
    @Test fun `reviewer progress persists with its PDF and is removed with that material`() {
        val root=temp.newFolder();val store=PersonalStudyStore(root,"owner");val subject=subject()
        val pdf=PersonalMaterial(PersonalStudyStore.newId(),subject.id,"Module",pdf=true)
        store.update { it.copy(subjects=listOf(subject),materials=listOf(pdf)) }
        val page=com.studypath.app.domain.ReviewerPage(1,"A queue is a collection of items processed in arrival order.")
        val cards=com.studypath.app.domain.ReviewerDrafts.create(listOf(page))
        val pack=com.studypath.app.domain.PdfReviewer(pdf.id,listOf(page),cards).record(cards.first().id,false)
        store.saveReviewer(pack)
        assertEquals(pack,PersonalStudyStore(root,"owner").reviewer(pdf.id))
        assertNull(PersonalStudyStore(root,"other").reviewer(pdf.id))
        assertThrows(IllegalArgumentException::class.java) { store.saveReviewer(pack.copy(materialId=PersonalStudyStore.newId())) }
        store.removeMaterial(pdf.id);assertNull(store.reviewer(pdf.id))
    }
    @Test fun `legacy workspace without reviewers opens and subject removal cleans reviewer`() {
        val root=temp.newFolder();val store=PersonalStudyStore(root,"owner");val subject=subject()
        val pdf=PersonalMaterial(PersonalStudyStore.newId(),subject.id,"Module",pdf=true)
        store.update { it.copy(subjects=listOf(subject),materials=listOf(pdf)) }
        assertTrue(store.read().reviewers.orEmpty().isEmpty())
        val page=com.studypath.app.domain.ReviewerPage(1,"A queue is a collection of items processed in arrival order.")
        store.saveReviewer(com.studypath.app.domain.PdfReviewer(pdf.id,listOf(page),com.studypath.app.domain.ReviewerDrafts.create(listOf(page))))
        store.removeSubject(subject.id);assertTrue(store.read().reviewers.orEmpty().isEmpty())
    }
    @Test fun `invalid task and orphan material leave prior workspace intact`() {
        val store = PersonalStudyStore(temp.newFolder(), "owner"); val subject = subject()
        val before = store.update { it.copy(subjects = listOf(subject)) }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(tasks = listOf(PersonalTask(PersonalStudyStore.newId(), subject.id, "Read", "2026-02-30"))) } }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(materials = listOf(PersonalMaterial(PersonalStudyStore.newId(), "missing", "Lesson", "Body"))) } }
        assertEquals(before, store.read())
    }
    @Test fun `deleting subject removes only its tasks materials and pdf files`() {
        val store = PersonalStudyStore(temp.newFolder(), "owner"); val first = subject(); val second = subject().copy(title = "Math")
        val pdf = PersonalMaterial(PersonalStudyStore.newId(), first.id, "Module", pdf = true)
        store.pdf(pdf.id).writeText("fixture")
        store.update { PersonalWorkspace(listOf(first, second), listOf(PersonalTask(PersonalStudyStore.newId(), first.id, "Read")), listOf(pdf)) }
        store.removeSubject(first.id)
        assertEquals(listOf(second), store.read().subjects); assertTrue(store.read().tasks.isEmpty()); assertFalse(store.pdf(pdf.id).exists())
    }
    @Test fun `corrupt workspace is preserved instead of silently reset`() {
        val store = PersonalStudyStore(temp.newFolder(), "owner"); val file = java.io.File(store.directory, "workspace.json")
        file.writeText("not json")
        assertThrows(Exception::class.java) { store.update { PersonalWorkspace() } }
        assertEquals("not json", file.readText())
    }
    @Test fun `incomplete self check cannot be saved`() {
        val store = PersonalStudyStore(temp.newFolder(), "owner"); val subject = subject()
        store.update { it.copy(subjects = listOf(subject)) }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(materials = listOf(PersonalMaterial(PersonalStudyStore.newId(), subject.id, "Card", question = "Why?"))) } }
        assertTrue(store.read().materials.isEmpty())
    }
    @Test fun `generated pdf path rejects traversal`() {
        val store = PersonalStudyStore(temp.newFolder(), "owner")
        assertThrows(IllegalArgumentException::class.java) { store.pdf("../../private") }
    }
}
