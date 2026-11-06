package com.studypath.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studypath.app.data.*
import com.studypath.app.ui.*
import org.hamcrest.Matchers.`is`
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.*

@RunWith(AndroidJUnit4::class)
class StudyNotesDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val originalMode=Appearance.selected(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun restoreAppearance() { instrumentation.runOnMainSync { Appearance.set(instrumentation.targetContext,originalMode) } }
    private fun tag(t:String)=onView(withTagValue(`is`(t as Any)))
    private fun capture(name:String) { instrumentation.waitForIdleSync();Thread.sleep(700);val bmp=instrumentation.uiAutomation.takeScreenshot();val target=File(instrumentation.targetContext.getExternalFilesDir(null),"note-screenshots/$name.png");target.parentFile!!.mkdirs();target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) };bmp.recycle() }
    @Test fun editablePhotoNoteSurvivesRecreationAndKeepsSourcePage() {
        instrumentation.runOnMainSync { Appearance.set(instrumentation.targetContext,androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
        ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_saved)).perform(click())
            onView(withText("Laplace transforms handout\nAdvanced Calculus · PDF · Offline")).perform(scrollTo(),click())
            tag("pdf_add_note").perform(click())
            tag("note_title").perform(replaceText("Laplace review"))
            tag("note_text").perform(scrollTo(),replaceText("Split the expression into familiar terms.\nCheck the initial conditions before substituting."));closeSoftKeyboard()
            // Exercise the real normalizer with a rendered page from the bundled PDF.
            lateinit var state:StudyNoteModel;lateinit var pdf:File
            scenario.onActivity { a -> val f=a.supportFragmentManager.findFragmentById(R.id.main_content) as StudyNoteFragment;state=ViewModelProvider(a)["study-note-${f.requireArguments().getString("draft")}",StudyNoteModel::class.java];pdf=a.demoPdf("pdf-calculus") }
            val png=ByteArrayOutputStream()
            ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { r -> r.openPage(0).use { page -> val bitmap=Bitmap.createBitmap(600,850,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap.compress(Bitmap.CompressFormat.PNG,100,png);bitmap.recycle() } } }
            val photo=NotePhoto(PersonalStudyStore.newId())
            importNotePhoto(ByteArrayInputStream(png.toByteArray()),File(state.drafts,"${photo.id}.jpg"))
            scenario.onActivity { state.note=state.note!!.copy(photos=listOf(photo));state.dirty=true;state.changed() }
            tag("note_caption_0").perform(scrollTo(),replaceText("Reference: original handout, page 1"));closeSoftKeyboard()
            scenario.recreate()
            tag("note_title").perform(scrollTo()).check(matches(withText("Laplace review")))
            tag("note_caption_0").perform(scrollTo()).check(matches(withText("Reference: original handout, page 1")))
            tag("note_text").perform(scrollTo());capture("01-note-editor")
            tag("note_save").perform(scrollTo(),click())
            onView(withText("Page notes (1)")).perform(click())
            onView(withText("Page 1: Laplace review")).perform(click())
            tag("note_photo").perform(scrollTo()).check(matches(isDisplayed()));capture("02-photo-note")
            tag("note_source").perform(scrollTo(),click())
            tag("offline_pdf_status").check(matches(isDisplayed()))
            pressBack();tag("note_edit").perform(scrollTo(),click())
            tag("note_text").perform(scrollTo(),replaceText("Unsaved replacement"));closeSoftKeyboard();pressBack()
            onView(withText("Discard")).perform(click())
            onView(withText("Split the expression into familiar terms.\nCheck the initial conditions before substituting.")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.onActivity { a -> val saved=a.noteWorkspace().materials.last();assertEquals(1,saved.source!!.page);assertTrue(StudyNotes.image(a.noteDirectory(),photo.id).isFile) }
        }
    }
    @Test fun addMaterialCreatesNotesAndPickerCanBeCancelled() {
        ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
            onView(withText("Advanced Calculus")).perform(scrollTo(),click())
            tag("demo_add_material").perform(scrollTo());capture("03-subject-materials")
            tag("demo_add_material").perform(click());onView(withText("Write note or add photos")).perform(click())
            tag("note_title").perform(replaceText("Lecture notes"));closeSoftKeyboard()
            tag("note_save").perform(scrollTo(),click())
            tag("note_status").perform(scrollTo()).check(matches(withText("Write a note, add a photo, or add a question and answer.")))
            tag("note_text").perform(scrollTo(),replaceText("Bring a worked example to the next study session."));closeSoftKeyboard()
            tag("note_add_photo").perform(scrollTo(),click())
            val end=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<end && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true)Thread.sleep(100)
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            tag("note_text").perform(scrollTo()).check(matches(withText("Bring a worked example to the next study session.")))
            tag("note_save").perform(scrollTo(),click())
            onView(withText("Lecture notes")).perform(scrollTo(),click());tag("note_edit").perform(scrollTo(),click())
            tag("note_text").perform(scrollTo(),replaceText("Updated after class."));closeSoftKeyboard();tag("note_save").perform(scrollTo(),click())
            onView(withText("Updated after class.")).check(matches(isDisplayed()))
            scenario.onActivity { a -> assertEquals(15,a.noteWorkspace().materials.size) }
        }
    }
    @Test fun invalidImageDoesNotCreateAttachment() {
        val file=File(instrumentation.targetContext.cacheDir,"bad-note-photo.jpg")
        assertThrows(IllegalArgumentException::class.java) { importNotePhoto(ByteArrayInputStream("not an image".toByteArray()),file) }
        assertFalse(file.exists());assertFalse(File(file.parentFile,file.name+".import").exists())
    }
}
