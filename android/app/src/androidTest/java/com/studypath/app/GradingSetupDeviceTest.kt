package com.studypath.app

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
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

@RunWith(AndroidJUnit4::class)
class GradingSetupDeviceTest {
    private fun tag(value:String)=onView(withTagValue(`is`(value as Any)))
    @Test fun sharedSetupSupportsCustomCategoriesScoresAndScales() {
        ActivityScenario.launch(DemoActivity::class.java).use { activity ->
            onView(withId(R.id.nav_planner)).perform(click())
            onView(withText("Grades")).perform(click())
            onView(withText("Data Structures · 2.0")).perform(scrollTo(),click())
            tag("grading_setup").perform(scrollTo(),click())
            tag("academic_add_category").perform(scrollTo(),click())
            onView(withText("Choose a category name")).perform(scrollTo(),click())
            onView(withText("Activities")).perform(click())
            tag("category_weight").perform(replaceText("100"));closeSoftKeyboard()
            onView(withText("Save")).perform(click())
            tag("grading_weight_total").perform(scrollTo()).check(matches(withText("Total: 100.0% · Ready")))
            tag("grading_scale").perform(scrollTo(),click())
            onView(withText("Letter or custom grade bands")).perform(click())
            tag("scale_bands").perform(replaceText("90 = A\n80 = B\n0 = C"));closeSoftKeyboard()
            onView(withText("Save")).perform(click())
            onView(withText("Add / edit scores")).perform(scrollTo(),click())
            onView(withText("Add Activities score")).perform(scrollTo(),click())
            tag("score_title").perform(replaceText("Lab activity"))
            tag("score_possible").perform(replaceText("50"))
            tag("score_earned").perform(replaceText("40"));closeSoftKeyboard()
            onView(withText("Save")).perform(click())
            onView(withText("B")).perform(scrollTo()).check(matches(isDisplayed()))
            activity.recreate()
            onView(withText("B")).perform(scrollTo()).check(matches(isDisplayed()))
            tag("grading_setup").perform(scrollTo(),click())
            tag("grading_scale").perform(scrollTo(),click())
            onView(withText("Linear numeric range")).perform(click())
            tag("scale_minimum").perform(replaceText("75"));tag("scale_maximum").perform(replaceText("100"));closeSoftKeyboard()
            onView(withText("Save")).perform(click())
            onView(withText("Add / edit scores")).perform(scrollTo(),click())
            onView(withText("95.00")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
    @Test fun headerStaysBelowStatusBarAcrossDemoNavigationAndRecreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val old=Appearance.selected(context)
        try { for(mode in listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { Appearance.set(context,mode) }
            ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
                fun checkHeader() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); scenario.onActivity { activity ->
                    val toolbar=activity.findViewById<View>(R.id.toolbar);val bounds=Rect();assertTrue(toolbar.getGlobalVisibleRect(bounds))
                    val top=ViewCompat.getRootWindowInsets(toolbar)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
                    assertTrue("Header must start below status bar",bounds.top>=top);assertEquals("Header must be fully visible",toolbar.height,bounds.height())
                } }
                checkHeader();onView(withId(R.id.nav_saved)).perform(click());checkHeader()
                onView(withText("Inverse transform steps\nAdvanced Calculus")).perform(scrollTo(),click());checkHeader()
                scenario.recreate();checkHeader();pressBack();checkHeader()
                onView(withId(R.id.nav_account)).perform(click());checkHeader()
                onView(withContentDescription("Open settings")).perform(click());checkHeader()
            }
        } } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { Appearance.set(context,old) } }
    }
}
