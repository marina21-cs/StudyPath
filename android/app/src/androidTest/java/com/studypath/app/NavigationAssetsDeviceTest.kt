package com.studypath.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.studypath.app.ui.CapsuleBottomNavigationView
import com.studypath.app.ui.DemoActivity
import com.studypath.app.ui.ProfileAvatars
import com.studypath.app.ui.Appearance
import androidx.test.espresso.assertion.ViewAssertions.matches
import org.hamcrest.Matchers.`is`
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationAssetsDeviceTest {
    private fun descendants(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Test fun navigationStaysCompactAboveSystemBars() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val oldMode=Appearance.selected(context)
        try { for((mode,night) in listOf("light" to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,"dark" to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Appearance.set(context,night) }
        ActivityScenario.launch(DemoActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_saved)).perform(click())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val nav = descendants(activity.window.decorView).filterIsInstance<CapsuleBottomNavigationView>().single()
                val density = activity.resources.displayMetrics.density
                val rect = Rect(); assertTrue(nav.getGlobalVisibleRect(rect))
                val bars = ViewCompat.getRootWindowInsets(nav)!!.getInsets(WindowInsetsCompat.Type.systemBars())
                assertEquals("The root owns bottom insets; navigation must not apply them again", 0, nav.paddingBottom)
                assertTrue("Navigation must fit above system controls", rect.bottom <= activity.window.decorView.height-bars.bottom)
                assertTrue("Navigation must have a compact height", nav.height <= (100*density*activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt())
                for (id in listOf(R.id.nav_study,R.id.nav_saved,R.id.nav_planner,R.id.nav_account)) {
                    val item = nav.findViewById<View>(id)
                    val icon = item.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
                    val iconRect = Rect(); assertTrue(icon.getGlobalVisibleRect(iconRect))
                    val labels = descendants(item).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
                    assertTrue("Every destination needs a label", labels.isNotEmpty())
                    labels.forEach { label ->
                        val labelRect=Rect(); assertTrue(label.getGlobalVisibleRect(labelRect))
                        assertTrue("Icon and label must stay together", labelRect.top-iconRect.bottom <= 14*density)
                        assertTrue("Label must fit inside navigation", labelRect.bottom <= rect.bottom)
                    }
                }
            }
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val app=InstrumentationRegistry.getInstrumentation().targetContext
            val out=File(app.getExternalFilesDir(null),"navigation-assets-evidence").apply{mkdirs()}
            File(out,"saved-navigation-$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        } } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { Appearance.set(context,oldMode) } }
    }
    @Test fun avatarSelectionSurvivesRecreationAndDemoResetIsIsolated() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=app.getSharedPreferences("profile-avatars",android.content.Context.MODE_PRIVATE)
        val uid="avatar-test-"+java.util.UUID.randomUUID().toString()
        val oldMode=Appearance.selected(app)
        try {
            ProfileAvatars.save(app,uid,"hoodie")
            assertEquals("hoodie",ProfileAvatars.selected(app,uid))
            assertEquals("initials",ProfileAvatars.selected(app,uid+"-other"))
            val before=prefs.all.toMap()
            ActivityScenario.launch(DemoActivity::class.java).use { activity ->
                onView(withId(R.id.nav_account)).perform(click())
                onView(withText("Change picture")).perform(scrollTo(),click())
                onView(withTagValue(`is`("avatar_choice_glasses" as Any))).perform(scrollTo(),click())
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Blue glasses")))
                activity.recreate()
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Blue glasses")))
                onView(withContentDescription("Open settings")).perform(click())
                onView(withTagValue(`is`("theme_dark" as Any))).perform(scrollTo(),click())
                pressBack()
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Blue glasses")))
                val out=File(app.getExternalFilesDir(null),"navigation-assets-evidence").apply{mkdirs()}
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    File(out,"anime-profile-dark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                onView(withText("Change picture")).perform(scrollTo(),click())
                onView(withText("Cancel")).perform(click())
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Blue glasses")))
                onView(withText("Change picture")).perform(scrollTo(),click())
                onView(withTagValue(`is`("avatar_choice_hoodie" as Any))).perform(scrollTo(),click())
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Blue hoodie")))
                onView(withText("Change picture")).perform(scrollTo(),click())
                onView(withTagValue(`is`("avatar_choice_giraffe" as Any))).perform(scrollTo(),click())
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, StudyPath giraffe")))
                onView(withText("Reset demo")).perform(scrollTo(),click())
                onView(withText("Reset sample data")).perform(click())
                onView(withId(R.id.nav_account)).perform(click())
                onView(withTagValue(`is`("profile_avatar" as Any))).check(matches(withContentDescription("Change profile picture, Your initials")))
                onView(withId(R.id.nav_study)).perform(click())
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    File(out,"mascot-reading.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                onView(withText("Open Planner")).perform(scrollTo(),click())
                for(id in listOf("t1","t2","t3")) onView(withTagValue(`is`("demo_task_$id" as Any))).perform(scrollTo(),click())
                onView(withText("All done. Nice work!")).perform(scrollTo()).check(matches(isDisplayed()))
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    File(out,"mascot-celebration.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            }
            assertEquals("Demo must not write real-account avatar preferences",before,prefs.all)
        } finally {
            prefs.edit().remove(uid).commit()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { Appearance.set(app,oldMode) }
        }
    }

}
