package com.studypath.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studypath.app.ui.*
import org.hamcrest.Matchers.`is`
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemesterDemoDeviceTest {
    private fun tag(value:String)=onView(withTagValue(`is`(value as Any)))
    private fun waitFor(s:ActivityScenario<DemoActivity>,tag:String) {
        val end=System.currentTimeMillis()+30000
        while(System.currentTimeMillis()<end) {
            var found=false;s.onActivity { found=it.findViewById<View>(android.R.id.content).findViewWithTag<View>(tag)?.isShown==true }
            if(found)return
            Thread.sleep(150)
        }
        fail("Missing $tag")
    }
    @Test fun semesterSetupAppliesAllSubjectsAndPreservesScores() {
        ActivityScenario.launch(DemoActivity::class.java).use { activity ->
            onView(withId(R.id.nav_planner)).perform(click());tag("planner_grading_setup").perform(scrollTo(),click())
            onView(withText("Choose your grading system")).check(matches(isDisplayed()))
            tag("semester_scale").perform(scrollTo(),click());onView(withText("Zero-based percentage (0–100)")).perform(click());onView(withText("Save")).perform(click())
            tag("semester_next").perform(scrollTo(),click())
            for((name,value) in listOf("Quizzes" to "30","Midterm" to "30","Final exam" to "40")) {
                tag("semester_category_$name").perform(scrollTo(),click());tag("semester_weight_$name").perform(scrollTo(),replaceText(value));closeSoftKeyboard()
            }
            activity.recreate()
            tag("semester_weight_Final exam").perform(scrollTo()).check(matches(withText("40")))
            tag("semester_review").perform(scrollTo(),click());tag("semester_apply").perform(scrollTo(),click())
            onView(withText("Advanced Calculus · 1.75")).perform(scrollTo(),click())
            onView(withText("Edit Quiz 1")).perform(scrollTo()).check(matches(isDisplayed()))
            pressBack();onView(withText("Data Structures · 2.0")).perform(scrollTo(),click())
            onView(withText("Add Quizzes score")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Add Midterm score")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Add Final exam score")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun fiveRealPdfsOpenAndUseSharedOcrReviewer() {
        ActivityScenario.launch(DemoActivity::class.java).use { activity ->
            activity.onActivity { a ->
                for(id in listOf("calculus","structures","databases","physics","communication")) {
                    val file=a.demoPdf("pdf-$id");assertTrue(file.length()>1000)
                    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { assertEquals(2,it.pageCount) } }
                }
            }
            onView(withId(R.id.nav_saved)).perform(click())
            onView(withText("Queues and stacks handout\nData Structures · PDF · Offline")).perform(scrollTo(),click())
            tag("demo_pdf_reviewer").perform(click())
            waitFor(activity,"reviewer_scan")
            tag("reviewer_last").perform(scrollTo(),replaceText("1"));closeSoftKeyboard()
            tag("reviewer_scan").perform(scrollTo(),click());waitFor(activity,"reviewer_extracted")
            tag("reviewer_extracted").perform(scrollTo()).check(matches(withText(org.hamcrest.Matchers.containsString("queue"))))
            tag("reviewer_text_checked").perform(scrollTo(),click());tag("reviewer_generate").perform(scrollTo(),click())
            tag("reviewer_cards_checked").perform(scrollTo(),click());tag("reviewer_save").perform(scrollTo(),click())
            tag("reviewer_practice").perform(scrollTo(),click());tag("reviewer_reveal").perform(scrollTo(),click())
            tag("reviewer_answer").perform(scrollTo()).check(matches(isDisplayed()))
            tag("reviewer_source").perform(scrollTo(),click());waitFor(activity,"offline_pdf_status");pressBack()
            tag("reviewer_answer").perform(scrollTo()).check(matches(isDisplayed()))
            tag("reviewer_understood").perform(scrollTo(),click())
            activity.recreate();onView(withText("Your PDF, ready to practice.")).check(matches(isDisplayed()))
            activity.onActivity { a -> assertEquals(1,a.demoReviewer("pdf-structures")!!.results.size) }
        }
    }
}
