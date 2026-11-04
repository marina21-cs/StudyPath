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
