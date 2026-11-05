package com.studypath.app.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConnectedStudyTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `different screens observe saved materials immediately`() = runBlocking {
        val root = temp.newFolder()
        val writer = PersonalStudyStore(root, "student")
        val reader = PersonalStudyStore(root, "student")
        val subject = PersonalSubject(PersonalStudyStore.newId(), "Calculus")
        val observed = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { reader.observe().first { it.materials.isNotEmpty() } } }
        writer.update { it.copy(subjects = listOf(subject), materials = listOf(PersonalMaterial(PersonalStudyStore.newId(), subject.id, "Derivatives", "Notes"))) }
        assertEquals("Derivatives", observed.await().materials.single().title)
    }
    @Test fun `old workspace upgrades without losing its subjects`() {
        val store = PersonalStudyStore(temp.newFolder(), "student")
        val id = PersonalStudyStore.newId()
        java.io.File(store.directory, "workspace.json").writeText("""{"subjects":[{"id":"$id","title":"Physics"}],"tasks":[],"materials":[]}""")
        val old = store.read().subjects.single()
        assertEquals("General", old.categoryLabel())
        store.update { it.copy(subjects = listOf(old.copy(grade = "1.75", category = "Science"))) }
        assertEquals("1.75", store.read().subjects.single().grade)
    }
    @Test fun `manual prerequisites cannot contain cycles or deleted ids`() {
        val a = PersonalSubject(PersonalStudyStore.newId(), "Calculus")
        val b = PersonalSubject(PersonalStudyStore.newId(), "Advanced calculus", prerequisiteIds = listOf(a.id))
        PersonalStudyStore.validate(PersonalWorkspace(subjects = listOf(a,b)))
        assertThrows(IllegalArgumentException::class.java) { PersonalStudyStore.validate(PersonalWorkspace(subjects = listOf(a.copy(prerequisiteIds = listOf(b.id)), b))) }
        val store = PersonalStudyStore(temp.newFolder(), "student")
        store.update { PersonalWorkspace(subjects = listOf(a,b)) }; store.removeSubject(a.id)
        assertTrue(store.read().subjects.single().prerequisiteIds.orEmpty().isEmpty())
    }
    @Test fun `COR parsing requires reviewable fields and preserves quoted titles`() {
        val rows = CorImport.parse("Code,Subject,Units\nMATH101,\"Calculus, first course\",3\nPHY101,Physics,4")
        assertEquals(2, rows.size); assertEquals("Calculus, first course", rows.first().title)
        assertEquals(3.0, rows.first().units!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) { CorImport.parse("MATH,Calculus,-4") }
        assertThrows(IllegalArgumentException::class.java) { CorImport.parse("Unstructured PDF extraction is not a table") }
    }
}
