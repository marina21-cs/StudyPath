package com.studypath.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.LessonRunStore
import com.studypath.app.data.ReadingContent
import com.studypath.app.data.ReadingStateStore
import com.studypath.app.data.ReadingSync
import com.studypath.app.data.TextPackageStore
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.ReadingFragment
import com.studypath.app.ui.SavedFragment
import com.studypath.app.ui.StudyFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.containsString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Native UX semantics against local Auth/RTDB with separate UID, preferences and retained bytes. */
@RunWith(AndroidJUnit4::class)
class NativeReadingRevisionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private lateinit var original: StudyPathGraph
    private lateinit var firebase: FirebaseApp
    private lateinit var database: FirebaseDatabase
    private lateinit var repository: FirebaseStudyRepository
    private val observations = JSONObject()

    @Before fun isolatedLocalGraph() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.NativeReadingRevisionDeviceTest") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        original = app.graph
        firebase = FirebaseApp.initializeApp(app, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL)
            .build(), "native_reading_$suffix")
        val auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
        database = FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000)
            setPersistenceEnabled(false)
            goOffline()
        }
        val directory = File(app.filesDir, "native-reading-revision/$suffix")
        repository = FirebaseStudyRepository(auth, database, app.getSharedPreferences("native_reading_$suffix", Context.MODE_PRIVATE),
            TextPackageStore(File(directory, "packages")), LessonRunStore(File(directory, "lessons")),
            ReadingStateStore(File(directory, "readings")), reminderChanged = { _, _, _ -> })
        setGraph(StudyPathGraph(repository, original.readings))
    }

    @After fun restoreDefaultGraph() {
        if (::database.isInitialized) database.goOffline()
        if (::original.isInitialized) setGraph(original)
        if (::firebase.isInitialized) firebase.delete()
    }

    @Test fun continueSavedReadingAndKeepDraftSeparateFromPlace() = runBlocking<Unit> {
        withTimeout(120_000) {
            val session = repository.register("native_reading_${UUID.randomUUID()}@studypath.invalid", "LocalUiRevision123!", "Synthetic UI learner")
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences(levelId = "ui_fixture", subjectIds = setOf("ui_subject")))
            observations.put("uid", session.uid).put("fixture", "Synthetic excerpt; no live Wikipedia call")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("reading_search_query")
                assertEquals("What do you want to understand?", text("study_heading"))
                assertNull(main { currentRoot()?.findViewWithTag<View>("study_continue_reading") })
                onView(withTagValue(equalTo<Any>("reading_search_submit"))).perform(scrollTo(), click())
                await { visible("reading_search_clear") }
                onView(withTagValue(equalTo<Any>("reading_search_clear"))).perform(scrollTo(), click())
                await { visible("study_hero") && !visible("reading_search_clear") }
                observations.put("emptyStudyHasDiscoveryAndSearchReset", true)

                val reading = fixtureReading()
                val saved = repository.saveReading(reading)
                repository.saveReadingAnnotation(saved.id, "Original saved note", 240)
                await { main {
                    val root = currentRoot()
                    root?.findViewWithTag<View>("study_continue_reading") != null &&
                        root.findViewWithTag<TextView>("study_heading")?.text.toString() == "Let’s pick it up." &&
                        texts(root).contains(reading.title)
                } }
                capture("01-study-real-resume")
                observations.put("continueNamesActualSavedReadingAndPosition", true)

                scenario.onActivity { activity -> activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_saved }
                awaitTag("saved_readings_tab")
                onView(withTagValue(equalTo<Any>("saved_lessons_tab"))).perform(scrollTo(), click())
                await { visible("modules") && !visible("readings") }
                assertTrue(main { texts(requireNotNull(currentRoot())).contains("0 ready offline") })
                onView(withTagValue(equalTo<Any>("saved_readings_tab"))).perform(scrollTo(), click())
                awaitTag("saved_reading_${saved.id}")
                onView(withTagValue(equalTo<Any>("saved_reading_${saved.id}"))).perform(scrollTo(), click())
                awaitTag("reading_excerpt")
                assertEquals(reading.excerpt, text("reading_excerpt"))
                assertEquals("Ready offline", text("reading_copy_status"))
                assertFalse("Secondary reading actions are collapsed", visible("reading_reminder"))
                assertFalse("Removal stays under Reading options", visible("reading_remove"))
                onView(withTagValue(equalTo<Any>("reading_options"))).perform(scrollTo(), click())
                assertTrue(visible("reading_reminder"))
                assertTrue(visible("reading_remove"))
                onView(withTagValue(equalTo<Any>("reading_options"))).perform(scrollTo(), click())
                observations.put("readingOptionsRevealReminderAndRemoval", true)
                onView(withTagValue(equalTo<Any>("reading_management_toggle"))).perform(scrollTo(), click())
                onView(withTagValue(equalTo<Any>("reading_note"))).perform(scrollTo(), replaceText("An unfinished note draft"), closeSoftKeyboard())
                val previousEdit = repository.retainedReading(saved.id)?.annotation?.editId
                onView(withContentDescription("Save place")).perform(click())
                await { repository.retainedReading(saved.id)?.let { it.annotation?.editId != previousEdit && it.annotationSync == ReadingSync.CONFIRMED } == true }
                assertEquals("Save my place must preserve the previously saved note", "Original saved note", repository.retainedReading(saved.id)?.annotation?.note)
                assertEquals("An unfinished note draft", text("reading_note"))
                observations.put("savePlaceDoesNotSubmitNoteDraft", true)
                capture("02-reading-independent-place")

                onView(withTagValue(equalTo<Any>("reading_save_note"))).perform(scrollTo(), click())
                await { repository.retainedReading(saved.id)?.let { it.annotation?.note == "An unfinished note draft" && it.annotationSync == ReadingSync.CONFIRMED } == true }
                observations.put("explicitSaveNoteCommitsDraft", true)
                onView(withTagValue(equalTo<Any>("reading_source"))).perform(scrollTo(), click())
                onView(withText(containsString(reading.licenseTitle))).check(matches(isDisplayed()))
                onView(withText("Close")).perform(click())
                observations.put("sourceAndLicenseRemainAccessible", true)

                database.goOffline()
                onView(withTagValue(equalTo<Any>("reading_note"))).perform(scrollTo(), replaceText("Offline note retained"), closeSoftKeyboard())
                onView(withTagValue(equalTo<Any>("reading_save_note"))).perform(scrollTo(), click())
                await { repository.retainedReading(saved.id)?.let { it.annotation?.note == "Offline note retained" && it.annotationSync == ReadingSync.PENDING } == true }
                assertEquals("Excerpt sync must not imply personal-edit confirmation", ReadingSync.CONFIRMED, repository.retainedReading(saved.id)?.snapshotSync)
                assertEquals(reading.excerpt, text("reading_excerpt"))
                capture("03-offline-note-pending-copy-readable")
                observations.put("offlineNoteRetainedWhileExcerptRemainsReadable", true)
                pressBack()
                await { main { currentActivity()?.supportFragmentManager?.findFragmentById(R.id.main_content) is SavedFragment } }
                assertTrue(visible("readings"))
                observations.put("backReturnsToOriginatingSavedShelf", true)
            }
            val receipt = File(app.getExternalFilesDir(null), "native-slide-revision/reading-observations.json")
            receipt.parentFile?.mkdirs(); receipt.writeText(observations.toString(2))
        }
    }

    private fun fixtureReading() = ReadingContent(
        pageId = 998811, pageKey = "Queue_(abstract_data_type)", title = "Queues · synthetic UX fixture",
        excerpt = (1..12).joinToString("\n\n") { "A queue handles items in the order they arrive. Paragraph $it is synthetic text for checking a saved reading position and readable spacing in the native StudyPath revision." },
        revisionId = 998812, revisionTimestamp = "2026-10-31T00:00:00Z",
        sourceUrl = "https://en.wikipedia.org/wiki/Queue_(abstract_data_type)", revisionUrl = "https://en.wikipedia.org/w/index.php?oldid=998812",
        licenseTitle = "CC BY-SA 4.0", licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        attribution = "Wikipedia contributors · synthetic transport fixture", modificationNotice = "Synthetic text for local UX verification.", retrievedAt = System.currentTimeMillis())

    private fun setGraph(value: StudyPathGraph) {
        val field = StudyPathApplication::class.java.getDeclaredField("graph").apply { isAccessible = true }
        instrumentation.runOnMainSync { field.set(app, value) }
    }
    private fun currentActivity(): MainActivity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
    private fun currentRoot(): View? = currentActivity()?.window?.decorView
    private fun texts(root: View): List<String> = buildList {
        if (root is TextView) add(root.text.toString())
        if (root is ViewGroup) (0 until root.childCount).forEach { addAll(texts(root.getChildAt(it))) }
    }
    private fun text(tag: String): String = main { requireNotNull(currentRoot()?.findViewWithTag<TextView>(tag)).text.toString() }
    private fun visible(tag: String): Boolean = main { currentRoot()?.findViewWithTag<View>(tag)?.isShown == true }
    private fun awaitTag(tag: String) = await { main { currentRoot()?.findViewWithTag<View>(tag) != null } }
    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(100) }
        assertTrue("Native UI condition did not become true before timeout", check())
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun capture(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        val target = File(app.getExternalFilesDir(null), "native-slide-revision/$name.png")
        target.parentFile?.mkdirs()
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
