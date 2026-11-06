package com.studypath.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class TideDemoDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private fun tagged(tag:String)=onView(withTagValue(`is`(tag as Any)))
    private fun snapshot()=File(app.filesDir,"personal-study").walkTopDown().filter{it.isFile}.associate{it.relativeTo(app.filesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}
    private fun shot(name:String) {
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val out=File(app.getExternalFilesDir(null),"tide-demo-evidence").apply{mkdirs()}
        File(out,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun isolatedDemoProfilePlannerAndThemeSurviveRecreation() {
        val bound=app.graph.repository.boundUid();val files=snapshot();val oldMode=Appearance.selected(app)
        try {
            ActivityScenario.launch(DemoActivity::class.java).use { activity ->
                onView(withText("Open Planner")).perform(scrollTo(),click())
                onView(withText("3 tasks left")).check(matches(isDisplayed()))
                tagged("demo_task_t1").perform(scrollTo(),click())
                onView(withText("2 tasks left")).check(matches(isDisplayed()))
                shot("01-overview")
                onView(withText("Subjects")).perform(click())
                tagged("demo_subject_communication").perform(scrollTo()).check(matches(isDisplayed()))
                shot("02-five-subjects")
                tagged("demo_subject_physics").perform(scrollTo(),click())
                onView(withText("Motion and forces")).perform(scrollTo(),click())
                onView(withText("Read & reflect")).check(matches(isDisplayed()))
                pressBack();pressBack()
                onView(withText("Grades")).perform(click())
                onView(withText("See calculation")).perform(scrollTo(),click())
                tagged("demo_gwa").check(matches(withText("1.75")))
                tagged("demo_include_physics").perform(scrollTo(),click())
                tagged("demo_gwa").check(matches(withText("1.63")))
                pressBack()
                onView(withText("What-if")).perform(scrollTo(),click())
                tagged("demo_projection").check(matches(withText("75.0 / 100")))
                activity.onActivity { it.window.decorView.findViewWithTag<android.widget.SeekBar>("demo_exam").progress = 100 }
                tagged("demo_projection").check(matches(withText("81.0 / 100")))
                pressBack()
                onView(withText("Advanced Calculus · 1.75")).perform(scrollTo(),click())
                tagged("demo_quiz").perform(replaceText("20"));closeSoftKeyboard()
                onView(withText("Recalculate estimate")).perform(scrollTo(),click())
                onView(withText("88.6 / 100")).check(matches(isDisplayed()))
                pressBack()
                onView(withText("Course map")).perform(scrollTo(),click())
                onView(withText("Prerequisites as a list")).perform(scrollTo()).check(matches(isDisplayed()))
                shot("03-map");pressBack()
                onView(withId(R.id.nav_account)).perform(click())
                onView(withText("Edit profile")).perform(scrollTo(),click())
                tagged("demo_name").perform(replaceText("Sam Demo"));closeSoftKeyboard()
                onView(withText("Save demo profile")).perform(scrollTo(),click())
                onView(withText("Sam Demo")).check(matches(isDisplayed()))
                shot("04-profile")
                onView(withContentDescription("Open settings")).perform(click())
                tagged("theme_dark").perform(scrollTo(),click())
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                activity.onActivity { assertEquals(Configuration.UI_MODE_NIGHT_YES,it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) }
                shot("05-dark-settings")
                activity.recreate()
                tagged("theme_dark").check(matches(isChecked()))
                onView(withText("Privacy")).perform(scrollTo(),click())
                onView(withText("Close")).perform(click())
                onView(withText("Terms of use")).perform(scrollTo(),click())
                onView(withText("Close")).perform(click())
                tagged("theme_light").perform(scrollTo(),click())
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                shot("06-light-settings")
                onView(withText("Reset demo")).perform(scrollTo(),click())
                onView(withText("Reset sample data")).perform(click())
                onView(withText("3 tasks left")).check(matches(isDisplayed()))
                onView(withId(R.id.nav_account)).perform(click())
                onView(withText("Alex Reyes")).check(matches(isDisplayed()))
                onView(withText("Exit demo")).perform(scrollTo(),click())
                onView(withText("Exit demo")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
            }
            assertEquals(bound,app.graph.repository.boundUid());assertEquals(files,snapshot())
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { Appearance.set(app,oldMode) } }
    }
}
