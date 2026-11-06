package com.studypath.app.data

import com.google.gson.Gson
import com.studypath.app.domain.StudentDemoSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StudyNotesTest {
    @get:Rule val temp=TemporaryFolder()
    private fun id()=PersonalStudyStore.newId()
    @Test fun legacyNotesAndPhotoOnlyNotesRemainReadable() {
        val legacy=Gson().fromJson("""{"id":"${id()}","subjectId":"${id()}","title":"Old","text":"Keep","question":"","answer":"","pdf":false}""",PersonalMaterial::class.java)
        StudyNotes.validate(legacy);assertTrue(legacy.photos.orEmpty().isEmpty())
        StudyNotes.validate(legacy.copy(text="",photos=listOf(NotePhoto(id(),"Diagram"))))
        assertThrows(IllegalArgumentException::class.java) { StudyNotes.validate(legacy.copy(text="")) }
        assertThrows(IllegalArgumentException::class.java) { StudyNotes.validate(legacy.copy(photos=List(7){NotePhoto(id())})) }
        assertThrows(IllegalArgumentException::class.java) { StudyNotes.validate(legacy.copy(photos=listOf(NotePhoto("../../escape")))) }
        assertThrows(IllegalArgumentException::class.java) { StudyNotes.validate(legacy.copy(question="Missing answer")) }
    }
    @Test fun photosSurviveReopenAndFailedSavePreservesOldFiles() {
        val root=temp.newFolder();val store=PersonalStudyStore(root,"a");val subject=PersonalSubject(id(),"Math")
        store.update { it.copy(subjects=listOf(subject)) }
        val drafts=temp.newFolder();val photo=NotePhoto(id(),"Board work");File(drafts,"${photo.id}.jpg").writeText("image-fixture")
        val note=PersonalMaterial(id(),subject.id,"Photo note",photos=listOf(photo))
        store.saveNote(note,drafts)
        assertEquals(note,PersonalStudyStore(root,"a").read().materials.single());assertTrue(StudyNotes.image(store.directory,photo.id).exists())
        assertFalse(StudyNotes.image(PersonalStudyStore(root,"b").directory,photo.id).exists())
        val added=NotePhoto(id());File(drafts,"${added.id}.jpg").writeText("new-image")
        assertThrows(IllegalArgumentException::class.java) { store.saveNote(note.copy(subjectId=id(),photos=listOf(added)),drafts) }
        assertEquals(note,store.read().materials.single());assertTrue(StudyNotes.image(store.directory,photo.id).exists());assertTrue(File(drafts,"${added.id}.jpg").exists());assertFalse(StudyNotes.image(store.directory,added.id).exists())
        store.saveNote(note.copy(text="Text only",photos=emptyList()),drafts);assertFalse(StudyNotes.image(store.directory,photo.id).exists())
    }
    @Test fun linkedNotesRemainAfterPdfRemovalAndImagesCleanUpWithSubject() {
        val store=PersonalStudyStore(temp.newFolder(),"a");val subject=PersonalSubject(id(),"Math");val pdf=PersonalMaterial(id(),subject.id,"Module",pdf=true)
        store.update { it.copy(subjects=listOf(subject),materials=listOf(pdf)) }
        val drafts=temp.newFolder();val photo=NotePhoto(id());File(drafts,"${photo.id}.jpg").writeText("image")
        val note=PersonalMaterial(id(),subject.id,"Page note",photos=listOf(photo),source=NoteSource(pdf.id,2))
        store.saveNote(note,drafts);assertEquals(2,store.read().materials.last().source!!.page)
        assertThrows(IllegalArgumentException::class.java) { store.saveNote(note.copy(source=NoteSource(id(),1)),drafts) }
        store.removeMaterial(pdf.id);assertNull(store.read().materials.single().source);assertTrue(StudyNotes.image(store.directory,photo.id).exists())
        store.removeSubject(subject.id);assertFalse(StudyNotes.image(store.directory,photo.id).exists())
    }
    @Test fun demoNotesAreEditableAndResetWithoutChangingAnotherSession() {
        val a=StudentDemoSession();val b=StudentDemoSession();val note=PersonalMaterial(id(),"calculus","My note","First")
        a.saveNote(note);a.saveNote(note.copy(text="Edited"));assertEquals("Edited",a.workspace.materials.last().text)
        assertFalse(b.workspace.materials.any { it.id==note.id });a.reset();assertFalse(a.workspace.materials.any { it.id==note.id })
    }
}
