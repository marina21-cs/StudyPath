package com.studypath.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.studypath.app.data.*
import com.studypath.app.ui.InteractiveLessonFragment
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.ReadingFragment
import com.studypath.app.ui.planner.PlannerFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cache
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real screens with a test-only graph swap. Default Firebase app/binding/queue are never used. */
@RunWith(AndroidJUnit4::class)
class ReadingContextDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val fixtureFile get() = File(app.filesDir, "b12-context-fixture.json")
    private lateinit var namespace: String
    private lateinit var firebase: FirebaseApp
    private lateinit var database: FirebaseDatabase
    private lateinit var auth: FirebaseAuth
    private lateinit var repository: FirebaseStudyRepository
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var original: StudyPathGraph
    private lateinit var publicCache: Cache
    private val publicNetwork = CopyOnWriteArrayList<JSONObject>()
    private val gson = Gson()

    @Before fun isolatedGraphOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("class")?.startsWith("com.studypath.app.ReadingContextDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        namespace = requireNotNull(args.getString("b12Namespace"))
        require(namespace.matches(Regex("demo-studypath-b12-device-[a-f0-9]{12}")))
        val suffix = namespace.takeLast(12)
        original = app.graph
        firebase = FirebaseApp.initializeApp(app, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setDatabaseUrl("https://$namespace.firebaseio.com")
            .build(), "b12_$suffix")
        auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
        database = FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000)
            setPersistenceEnabled(true)
            goOffline()
        }
        preferences = app.getSharedPreferences("b12_context_$suffix", Context.MODE_PRIVATE)
        val directory = File(app.filesDir, "b12-context-state/$suffix")
        repository = FirebaseStudyRepository(auth, database, preferences,
            TextPackageStore(File(directory, "packages")), LessonRunStore(File(directory, "lessons")),
            ReadingStateStore(File(directory, "readings")),
            ReadingContextNetwork(OkHttpClient(), "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000/?ns=$namespace".toHttpUrl()),
            reminderChanged = { _, _, _ -> })
        publicCache = Cache(File(app.cacheDir, "b12-context-http/$suffix"), 8L * 1024 * 1024)
        val publicClient = OkHttpClient.Builder().cache(publicCache).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) {
                    if (response.request.url.host == "en.wikipedia.org") publicNetwork += JSONObject()
                        .put("path", response.request.url.encodedPath).put("code", response.code)
                        .put("receivedAt", response.receivedResponseAtMillis)
                }
            }).build()
        setGraph(StudyPathGraph(repository, MediaWikiClient(publicClient, BuildConfig.API_USER_AGENT)))
    }

    @After fun restoreDefaultGraph() {
        if (::database.isInitialized) database.goOffline()
        if (::original.isInitialized) setGraph(original)
        if (::publicCache.isInitialized) publicCache.close()
        // Keep the named app's synthetic Auth/cache state for the next force-stopped process stage.
        if (::firebase.isInitialized) firebase.delete()
    }

    @Test fun liveSaveAndNote() = runBlocking<Unit> {
        withTimeout(180_000) {
            assertFalse("Use a fresh test fixture; never clear a bound workspace.", fixtureFile.exists())
            assertNull(repository.boundUid())
            assertFalse("This run must start with its own empty public HTTP cache", publicCache.urls().hasNext())
            val email = "b12_${UUID.randomUUID()}@studypath.invalid"
            val session = repository.register(email, PASSWORD, "Synthetic reading learner")
            val f = JSONObject().put("namespace", namespace).put("uid", session.uid).put("email", email)
                .put("observations", JSONObject()).put("pids", JSONObject()).put("screenshots", JSONArray())
                .put("readingLayout", JSONObject())
            save(f)
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences("fixture_level", "fixture_v1", setOf("fixture_subject"), "fixture_class", true))
            val summary = repository.publishedModuleIndex("fixture_v1").first { it.isNotEmpty() }.single()
            val downloaded = repository.downloadTextPackage(summary)
            assertTrue(downloaded.ready)
            assertEquals("Computer programming", repository.resolveReadingContext(ReadingContext("module_test")).query)
            assertEquals("Computer programming", repository.resolveReadingContext(ReadingContext("weak_test")).query)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("reading_search_query")
                scenario.onActivity { it.openDetails(InteractiveLessonFragment.forPackage(downloaded.key)) }
                awaitTag("lesson_related_readings")
                onView(withTagValue(equalTo<Any>("lesson_related_readings"))).perform(scrollTo(), click())
                awaitText("Choose a module concept")
                onView(withText("Computer programming")).perform(click())
                awaitQuery()
                onView(withTagValue(equalTo<Any>("reading_search_submit"))).perform(scrollTo(), click())
                awaitText("Readings")
                onView(allOf(withText("Computer programming"), not(isAssignableFrom(EditText::class.java)))).perform(scrollTo(), click())
                awaitText("Save offline")
                awaitTag("reading_excerpt")
                assertInitialReadingViewport(f)
                capture(f, "00-initial-reading-compact")
                onView(withText("Save offline")).perform(scrollTo(), click())
                try {
                    awaitText("Saved · server confirmed")
                } catch (missingConfirmation: AssertionError) {
                    // A failed UI save still needs the actual repository/codec stack in the diagnostic receipt.
                    try {
                        repository.saveReading(app.graph.readings.fetchReading("Computer_programming"), context = ReadingContext("module_test"))
                    } catch (cause: Exception) {
                        throw AssertionError("The UI save failed; a direct source retry exposes the underlying failure", cause)
                    }
                    throw missingConfirmation
                }
                val saved = repository.savedReadings().first { it.size == 1 }.single()
                assertTrue(repository.isReadingSaveConfirmed(saved.id))
                assertEquals("module_test", repository.retainedReading(saved.id)!!.contextId)
                f.put("savedId", saved.id).put("revisionId", saved.reading.revisionId)
                    .put("originalReading", gson.toJson(saved.reading))
                save(f)
                assertDraftDisclosureAndRecreation(f, scenario)
                editNote("Synthetic online note")
                scrollExcerptToPosition(500)
                onView(withContentDescription("Save place")).perform(click())
                awaitText("Note and position · server confirmed")
                val retained = repository.retainedReading(saved.id)!!
                assertEquals(ReadingSync.CONFIRMED, retained.annotationSync)
                assertTrue(retained.annotation!!.position > 0)
                f.put("onlineAnnotation", JSONObject(retained.annotation.record(saved)))
                editNote("Unsaved draft during an unrelated source save")
                setNotesExpanded(false)
                assertCollapsedPersonalStatus("Note and position · server confirmed")
                f.getJSONObject("readingLayout").put("confirmedPersonalStatusAvailableWhenCollapsed", true)
                repository.saveReading(saved.reading, context = ReadingContext("module_test"))
                instrumentation.waitForIdleSync()
                setNotesExpanded(true)
                assertNote("Unsaved draft during an unrelated source save")
                val afterSourceRetry = requireNotNull(repository.retainedReading(saved.id))
                assertEquals("A source retry must not change the original excerpt", saved, afterSourceRetry.saved)
                assertEquals("A source retry must not save or replace the personal draft", retained.annotation, afterSourceRetry.annotation)
                assertEquals("module_test", afterSourceRetry.contextId)
                f.getJSONObject("readingLayout").put("collapsedDraftSurvivesUnrelatedStateEmission", true)
                editNote("Synthetic online note")
                onView(withTagValue(equalTo<Any>("reading_note"))).perform(scrollTo())
                assertToolbar(f)
                capture(f, "01-live-source-online-note")
                onView(withText("Source & license")).perform(scrollTo(), click())
                awaitContains(saved.reading.attribution)
                awaitContains(saved.reading.licenseTitle)
                capture(f, "02-live-source-attribution")
                onView(withText("Close")).perform(click())
                scenario.onActivity { it.openDetails(PlannerFragment()) }
                awaitText("More options")
                onView(withContentDescription("More options, expand")).perform(scrollTo(), click())
                onView(withContentDescription("Related readings, expand")).perform(scrollTo(), click())
                awaitTag("planner_related_readings")
                onView(withTagValue(equalTo<Any>("planner_related_readings"))).perform(scrollTo(), click())
                awaitText("Choose a course category")
                onView(withText("quizzes · Computer programming")).perform(click())
                awaitQuery()
                f.put("moduleAndCategoryReviewedQueriesOpened", true)
                capture(f, "03-reviewed-category-query")
            }
            repository.savedReadings().first()
            repository.readingAnnotation(savedId(f)).first { it.first != null }
            assertTrue("A real submitted search must reach Wikipedia", publicNetwork.any { it.getString("path").endsWith("/search/page") && it.getInt("code") == 200 })
            assertTrue("A real detail response must reach Wikipedia", publicNetwork.any { it.getString("path").endsWith("/with_html") && it.getInt("code") == 200 })
            f.put("liveNetwork", JSONArray(publicNetwork))
            finish(f, "liveSaveAndNote")
        }
    }

    @Test fun offlineEditAndQueue() = runBlocking<Unit> {
        withTimeout(90_000) {
            val f = resume()
            repository.connection().first { !it }
            val saved = assertSource(f)
            assertFalse(repository.isReadingSaveConfirmed(saved.id))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("reading_search_query")
                scenario.onActivity { it.openDetails(ReadingFragment.forSaved(saved.id)) }
                awaitTag("reading_management_toggle")
                assertNotesExpanded(false)
                setNotesExpanded(true)
                assertNote("Synthetic online note")
                editNote("Synthetic offline personal note")
                setNotesExpanded(false)
                setNotesExpanded(true)
                assertNote("Synthetic offline personal note")
                f.getJSONObject("readingLayout").put("largeTextDraftSurvivesCollapseReopen", true)
                assertLargeTextManagement(f)
                scrollExcerptToPosition(700)
                onView(withContentDescription("Save place")).perform(click())
                await("The note must be durably pending before process death") {
                    repository.retainedReading(saved.id)?.let { it.annotation?.note == "Synthetic offline personal note" && it.annotationSync == ReadingSync.PENDING } == true
                }
                val annotation = repository.retainedReading(saved.id)!!.annotation!!
                assertTrue(annotation.position > 0)
                f.put("pendingAnnotation", JSONObject(annotation.record(saved)))
                onView(withTagValue(equalTo<Any>("reading_note"))).perform(scrollTo())
                capture(f, "04-offline-note-pending")
                setNotesExpanded(false)
                assertCollapsedPersonalStatus("Saved on device · sync acknowledgement unknown. Retry when connected.")
                f.getJSONObject("readingLayout").put("personalSyncStatusAvailableWhenCollapsed", true)
                capture(f, "04c-pending-status-collapsed")
                assertLargeTextSourceDialog(f, saved.reading)
            }
            val legacy = legacyReading()
            database.getReference("users/${f.getString("uid")}/savedReadings/${legacy.id}").setValue(DatabaseRecords.reading(legacy))
            val local = database.getReference("users/${f.getString("uid")}/savedReadings/${legacy.id}").get().await()
            assertTrue("The old queued write must exist in the SDK cache before restart", local.exists())
            assertTrue(preferences.edit().putBoolean("readingJournalV1Ready", false).commit())
            f.put("legacyId", legacy.id).put("legacyReading", gson.toJson(legacy))
            finish(f, "offlineEditAndQueue")
        }
    }

    @Test fun offlineReopen() = runBlocking<Unit> {
        withTimeout(90_000) {
            val f = resume()
            repository.connection().first { !it }
            val saved = assertSource(f)
            val retained = repository.retainedReading(saved.id)!!
            assertEquals(f.getJSONObject("pendingAnnotation").getString("editId"), retained.annotation!!.editId)
            assertEquals(ReadingSync.PENDING, retained.annotationSync)
            val legacy = gson.fromJson(f.getString("legacyReading"), SavedReading::class.java)
            val legacyRetained = repository.retainedReading(legacy.id)
            assertNotNull("Upgrade recovery must export the old queued source before reconnecting", legacyRetained)
            assertEquals(legacy, legacyRetained!!.saved)
            assertEquals(ReadingSync.PENDING, legacyRetained.snapshotSync)
            assertTrue(preferences.getBoolean("readingJournalV1Ready", false))
            f.put("legacyQueueExportedBeforeReconnect", true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("reading_search_query")
                scenario.onActivity { it.openDetails(ReadingFragment.forSaved(saved.id)) }
                awaitTag("reading_management_toggle")
                assertNotesExpanded(false)
                setNotesExpanded(true)
                assertNote("Synthetic offline personal note")
                onView(withTagValue(equalTo<Any>("reading_resume"))).perform(scrollTo(), click())
                await("Resume must restore the saved excerpt character position") {
                    main { visibleExcerptPosition() == retained.annotation.position }
                }
                f.getJSONObject("readingLayout").put("pendingNoteAndExactPositionRestoreAt200Percent", true)
                capture(f, "05-offline-restarted-resume")
                onView(withText("Source & license")).perform(scrollTo(), click())
                awaitContains(saved.reading.attribution)
                capture(f, "06-offline-restarted-attribution")
                onView(withText("Close")).perform(click())
            }
            finish(f, "offlineReopen")
        }
    }

    @Test fun reconnectAndConfirm() = runBlocking<Unit> {
        withTimeout(90_000) {
            val f = resume()
            repository.connection().first { it }
            val saved = assertSource(f)
            repository.syncReadingAnnotation(saved.id)
            assertEquals(ReadingSync.CONFIRMED, repository.retainedReading(saved.id)!!.annotationSync)
            val legacy = gson.fromJson(f.getString("legacyReading"), SavedReading::class.java)
            withTimeout(20_000) {
                while (database.getReference("users/${f.getString("uid")}/savedReadings/${legacy.id}").get().await().exists()) delay(100)
            }
            assertEquals("Server rejection must not erase the exported old queued source", legacy, repository.openReading(legacy.id))
            f.put("legacyQueueRetainedAfterServerRollback", true)
            finish(f, "reconnectAndConfirm")
        }
    }

    @Test fun queueDeletionOffline() = runBlocking<Unit> {
        withTimeout(90_000) {
            val f = resume()
            repository.connection().first { !it }
            val saved = assertSource(f)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("reading_search_query")
                scenario.onActivity { it.openDetails(ReadingFragment.forSaved(saved.id)) }
                awaitText("Remove saved reading")
                onView(withText("Remove saved reading")).perform(scrollTo(), click())
                onView(withText("Remove")).perform(click())
                await("Offline deletion must retain the excerpt until acknowledgement") {
                    repository.retainedReading(saved.id)?.let { it.deletionEditId != null && it.deletionSync == ReadingSync.PENDING } == true
                }
                assertEquals(saved, repository.openReading(saved.id))
                f.put("deletionEditId", repository.retainedReading(saved.id)!!.deletionEditId)
                capture(f, "07-offline-removal-pending")
            }
            finish(f, "queueDeletionOffline")
        }
    }

    @Test fun offlineDeletionReopen() = runBlocking<Unit> {
        withTimeout(90_000) {
            val f = resume()
            repository.connection().first { !it }
            val saved = assertSource(f)
            val retained = repository.retainedReading(saved.id)!!
            assertEquals(f.getString("deletionEditId"), retained.deletionEditId)
            assertEquals(ReadingSync.PENDING, retained.deletionSync)
            assertTrue(repository.savedReadings().first().any { it.id == saved.id })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("reading_search_query")
                scenario.onActivity { it.openDetails(ReadingFragment.forSaved(saved.id)) }
                awaitText("Removal pending · retained text is available until server confirmation.")
                setNotesExpanded(true)
                assertNote("Synthetic offline personal note")
                capture(f, "08-offline-removal-restarted")
            }
            finish(f, "offlineDeletionReopen")
        }
    }

    @Test fun reconnectDeletion() = runBlocking<Unit> {
        withTimeout(90_000) {
            val f = resume()
            repository.connection().first { it }
            repository.deleteReading(savedId(f))
            assertNull(repository.retainedReading(savedId(f)))
            assertFalse(repository.savedReadings().first().any { it.id == savedId(f) })
            finish(f, "reconnectDeletion")
        }
    }

    @Test fun cleanupIdentity() = runBlocking<Unit> {
        withTimeout(30_000) { database.goOnline(); auth.currentUser?.delete()?.await() }
        repository.logout()
    }

    private suspend fun resume(): JSONObject {
        val f = JSONObject(fixtureFile.readText())
        assertEquals(namespace, f.getString("namespace"))
        assertEquals(f.getString("uid"), repository.resumeSession()?.uid)
        val oldPids = f.getJSONObject("pids")
        for (stage in oldPids.keys()) assertNotEquals(oldPids.getInt(stage), android.os.Process.myPid())
        return f
    }

    private suspend fun assertSource(f: JSONObject): SavedReading = repository.openReading(savedId(f)).also {
        assertEquals(gson.fromJson(f.getString("originalReading"), ReadingContent::class.java), it.reading)
    }
    private fun savedId(f: JSONObject) = f.getString("savedId")
    private fun save(f: JSONObject) { fixtureFile.writeText(f.toString()) }
    private fun finish(f: JSONObject, stage: String) {
        f.getJSONObject("observations").put(stage, true)
        f.getJSONObject("pids").put(stage, android.os.Process.myPid())
        save(f)
    }
    private fun setGraph(graph: StudyPathGraph) {
        val field = StudyPathApplication::class.java.getDeclaredField("graph").apply { isAccessible = true }
        instrumentation.runOnMainSync { field.set(app, graph) }
    }
    private fun awaitQuery() {
        await("Reviewed query must populate only the public topic field") {
            main { currentRoot()?.findViewWithTag<EditText>("reading_search_query")?.let { it.isEnabled && it.text.toString() == "Computer programming" } == true }
        }
    }
    private fun assertInitialReadingViewport(f: JSONObject) = main {
        val root = requireNotNull(currentRoot())
        val excerpt = requireNotNull(root.findViewWithTag<TextView>("reading_excerpt"))
        val panel = requireNotNull(root.findViewWithTag<View>("reading_management"))
        val toggle = requireNotNull(root.findViewWithTag<View>("reading_management_toggle"))
        val source = requireNotNull(root.findViewWithTag<View>("reading_source"))
        val scroll = generateSequence(excerpt.parent) { it.parent }.filterIsInstance<ScrollView>().first()
        val scale = excerpt.resources.configuration.fontScale
        assertEquals("The initial reading viewport must be observed at normal text size", 1f, scale, .01f)
        assertEquals("Notes must initially be collapsed", View.GONE, panel.visibility)
        assertEquals("The article must be visible before any test scroll", 0, scroll.scrollY)
        assertEquals("The article must stay in the outer reading column", scroll.getChildAt(0), excerpt.parent)
        assertTrue("Source and notes controls must precede the article", source.top < toggle.top && toggle.top < excerpt.top)
        assertTrue("The supplemental source caption must remain visible in the reading screen",
            descendants(root).filterIsInstance<TextView>().any { it.text.toString() == "Supplemental reading from Wikipedia" && it.isShown })
        val viewport = Rect()
        assertTrue(root.findViewById<View>(R.id.main_content).getGlobalVisibleRect(viewport))
        val location = IntArray(2); excerpt.getLocationOnScreen(location)
        val textTop = location[1] + excerpt.compoundPaddingTop
        val layout = requireNotNull(excerpt.layout)
        val firstCompleteLines = (0 until layout.lineCount).takeWhile { line ->
            textTop + layout.getLineTop(line) >= viewport.top && textTop + layout.getLineBottom(line) <= viewport.bottom
        }.size
        assertTrue("At least two complete article lines must appear in the initial viewport", firstCompleteLines >= 2)
        val density = excerpt.resources.displayMetrics.density
        f.getJSONObject("readingLayout").put("initialViewport", JSONObject().put("fontScale", scale)
            .put("notesInitiallyCollapsed", true).put("scrollYPx", scroll.scrollY)
            .put("articleTopFromViewportDp", (textTop - viewport.top) / density)
            .put("viewportHeightDp", viewport.height() / density).put("completeArticleLinesVisible", firstCompleteLines)
            .put("supplementalCaptionPreserved", true).put("sourceAndDisclosureBeforeArticle", true))
    }
    private suspend fun assertDraftDisclosureAndRecreation(f: JSONObject, scenario: ActivityScenario<MainActivity>) {
        val draft = "Unsaved disclosure and recreation draft"
        setNotesExpanded(true)
        editNote(draft)
        setNotesExpanded(false)
        assertNote(draft)
        scenario.recreate()
        awaitTag("reading_management_toggle")
        assertNotesExpanded(false)
        assertNote(draft)
        assertRecreatedReadingIdentity(f)
        setNotesExpanded(true)
        assertNote(draft)
        scenario.recreate()
        awaitTag("reading_note")
        assertNotesExpanded(true)
        assertNote(draft)
        assertRecreatedReadingIdentity(f)
        f.getJSONObject("readingLayout").put("draftSurvivesCollapseReopen", true)
            .put("collapsedDraftAndDisclosureSurviveRecreation", true)
            .put("expandedDraftAndDisclosureSurviveRecreation", true)
            .put("recreatedDraftKeepsSavedReadingIdentity", true)
    }
    private suspend fun assertRecreatedReadingIdentity(f: JSONObject) {
        val saved = assertSource(f)
        val retained = requireNotNull(repository.retainedReading(savedId(f)))
        assertEquals("Recreation must retain the exact saved reading ID", savedId(f), retained.saved.id)
        assertEquals("Recreation must retain the saved revision", f.getLong("revisionId"), retained.saved.reading.revisionId)
        assertEquals("Recreation must retain the original saved source", saved, retained.saved)
        assertEquals("Recreation must retain the reviewed context", "module_test", retained.contextId)
        assertEquals("The recreated draft must remain beside the original excerpt", saved.reading.excerpt,
            main { requireNotNull(currentRoot()?.findViewWithTag<TextView>("reading_excerpt")).text.toString() })
    }
    private fun setNotesExpanded(expanded: Boolean) {
        awaitTag("reading_management_toggle")
        val visible = main { requireNotNull(currentRoot()?.findViewWithTag<View>("reading_management")).visibility == View.VISIBLE }
        if (visible != expanded) onView(withTagValue(equalTo<Any>("reading_management_toggle"))).perform(scrollTo(), click())
        await("Notes disclosure must become ${if (expanded) "expanded" else "collapsed"}") {
            main { currentRoot()?.findViewWithTag<View>("reading_management")?.visibility == if (expanded) View.VISIBLE else View.GONE }
        }
        assertNotesExpanded(expanded)
        if (expanded) awaitTag("reading_note")
    }
    private fun assertNotesExpanded(expanded: Boolean) = main {
        val panel = requireNotNull(currentRoot()?.findViewWithTag<View>("reading_management"))
        val toggle = requireNotNull(currentRoot()?.findViewWithTag<TextView>("reading_management_toggle"))
        assertEquals(if (expanded) View.VISIBLE else View.GONE, panel.visibility)
        assertEquals("Notes & position", toggle.text.toString())
        assertEquals("Disclosure state must be announced to accessibility services", if (expanded) "Expanded" else "Collapsed",
            ViewCompat.getStateDescription(toggle)?.toString())
    }
    private fun assertCollapsedPersonalStatus(expected: String) {
        assertNotesExpanded(false)
        await("The actual personal sync status must remain available with notes collapsed") {
            main {
                currentRoot()?.findViewWithTag<TextView>("reading_note_status")?.let {
                    it.isShown && it.text.toString() == expected
                } == true
            }
        }
        onView(withTagValue(equalTo<Any>("reading_note_status"))).perform(scrollTo())
            .check(matches(isDisplayed())).check(matches(withText(expected)))
            .check { view, failure ->
                if (failure != null) throw failure
                val status = requireNotNull(view) as TextView
                val visible = Rect()
                assertTrue("Collapsed personal sync feedback must be readable after scrolling", status.getGlobalVisibleRect(visible))
                assertTrue("At least 90% of the collapsed sync feedback must be visible", visible.width() >= status.width * .9 && visible.height() >= status.height * .9)
                val layout = requireNotNull(status.layout)
                for (line in 0 until layout.lineCount) assertEquals("Personal sync feedback must not be ellipsized", 0, layout.getEllipsisCount(line))
            }
    }
    private fun assertLargeTextManagement(f: JSONObject) {
        val scale = main { requireNotNull(currentRoot()).resources.configuration.fontScale }
        assertTrue("The host must configure 200% text for offline reading controls", scale >= 1.95f)
        val controls = JSONArray()
        for (tag in listOf("reading_management_toggle", "reading_note", "reading_save_note", "reading_retry", "reading_resume", "reading_source")) {
            val control = onView(withTagValue(equalTo<Any>(tag))).perform(scrollTo())
            controls.put(measureTextTarget(control, tag))
        }
        f.getJSONObject("readingLayout").put("largeTextControls", JSONObject().put("fontScale", scale).put("targets", controls))
        onView(withTagValue(equalTo<Any>("reading_note"))).perform(scrollTo())
        capture(f, "04a-notes-200pct")
    }
    private fun assertLargeTextSourceDialog(f: JSONObject, reading: ReadingContent) {
        val scale = main { requireNotNull(currentRoot()).resources.configuration.fontScale }
        assertTrue("Source and license access must be observed at 200% text", scale >= 1.95f)
        val source = onView(withTagValue(equalTo<Any>("reading_source"))).perform(scrollTo())
        val targets = JSONArray().put(measureTextTarget(source, "reading_source"))
        source.perform(click())
        awaitContains(reading.attribution)
        awaitContains(reading.licenseTitle)
        for (title in listOf("Source", "License")) {
            targets.put(measureTextTarget(onView(withText(title)).perform(scrollTo()), title))
        }
        targets.put(measureTextTarget(onView(withText("Close")), "Close"))
        capture(f, "04b-source-license-200pct")
        onView(withText("Close")).perform(click())
        assertNotesExpanded(false)
        assertNote("Synthetic offline personal note")
        f.getJSONObject("readingLayout").put("largeTextSourceDialog", JSONObject().put("fontScale", scale)
            .put("targets", targets).put("attributionAndLicensePreserved", true).put("browserLaunched", false)
            .put("pendingNotePreservedOnClose", true))
    }
    private fun measureTextTarget(control: ViewInteraction, label: String): JSONObject {
        var measure: JSONObject? = null
        control.check { view, failure ->
            if (failure != null) throw failure
            val text = requireNotNull(view) as TextView
            val visible = Rect()
            assertTrue("$label must be visible after scrolling", text.getGlobalVisibleRect(visible))
            assertTrue("At least 90% of $label must be visible", visible.width() >= text.width * .9 && visible.height() >= text.height * .9)
            val density = text.resources.displayMetrics.density
            assertTrue("$label must have a 48dp touch target", text.width + 1 >= 48 * density && text.height + 1 >= 48 * density)
            val layout = requireNotNull(text.layout)
            val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
            val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
            assertTrue("$label text must fit vertically", layout.height <= availableHeight + 2)
            for (line in 0 until layout.lineCount) {
                assertEquals("$label must not be ellipsized", 0, layout.getEllipsisCount(line))
                assertTrue("$label text must fit horizontally", layout.getLineWidth(line) <= availableWidth + 2)
            }
            measure = JSONObject().put("control", label).put("widthDp", text.width / density).put("heightDp", text.height / density)
                .put("lineCount", layout.lineCount).put("atLeast90PercentVisible", true).put("ellipsizedOrClipped", false)
        }
        return requireNotNull(measure)
    }
    private fun editNote(text: String) {
        onView(withTagValue(equalTo<Any>("reading_note"))).perform(scrollTo(), replaceText(text), closeSoftKeyboard())
    }
    private fun assertNote(text: String) {
        assertEquals(text, main { requireNotNull(currentRoot()?.findViewWithTag<EditText>("reading_note")).text.toString() })
    }
    private fun scrollExcerptToPosition(offset: Int) {
        main {
            val excerpt = requireNotNull(currentRoot()?.findViewWithTag<TextView>("reading_excerpt"))
            val scroll = generateSequence(excerpt.parent) { it.parent }.filterIsInstance<ScrollView>().first()
            val layout = requireNotNull(excerpt.layout)
            scroll.scrollTo(0, excerpt.top + layout.getLineTop(layout.getLineForOffset(offset.coerceAtMost(excerpt.text.length / 2))))
        }
        instrumentation.waitForIdleSync()
    }
    private fun assertToolbar(f: JSONObject) = main {
        val toolbar = requireNotNull(currentRoot()?.findViewById<ViewGroup>(R.id.toolbar))
        val action = descendants(toolbar).single { it.contentDescription?.toString() == "Save place" }
        val title = descendants(toolbar).filterIsInstance<TextView>().single { it.text.toString() == "Reading" }
        val connection = requireNotNull(currentRoot()?.findViewById<TextView>(R.id.connection_status))
        fun bounds(view: View): Rect = Rect().also { assertTrue(view.getGlobalVisibleRect(it)) }
        val a = bounds(action); val t = bounds(title); val c = bounds(connection)
        assertFalse("Reading title must not overlap Save place", Rect.intersects(t, a))
        assertFalse("Connection status must not overlap Save place", Rect.intersects(c, a))
        assertFalse("Reading title must not overlap connection status", Rect.intersects(t, c))
        val density = toolbar.resources.displayMetrics.density
        assertTrue("Save place must have a 48dp touch target", action.width + 1 >= 48 * density && action.height + 1 >= 48 * density)
        val titleLayout = requireNotNull(title.layout)
        for (line in 0 until titleLayout.lineCount) assertEquals("Reading title must not be ellipsized", 0, titleLayout.getEllipsisCount(line))
        f.put("toolbarGeometry", JSONObject().put("action", "Save place").put("widthDp", action.width / density)
            .put("heightDp", action.height / density).put("titleAndStatusOverlap", false)
            .put("fontScale", toolbar.resources.configuration.fontScale).put("titleEllipsized", false)
            .put("actionBounds", a.toShortString()).put("titleBounds", t.toShortString()).put("connectionBounds", c.toShortString()))
    }
    private fun visibleExcerptPosition(): Int {
        val excerpt = requireNotNull(currentRoot()?.findViewWithTag<TextView>("reading_excerpt"))
        val scroll = generateSequence(excerpt.parent) { it.parent }.filterIsInstance<ScrollView>().first()
        val layout = requireNotNull(excerpt.layout)
        return layout.getLineStart(layout.getLineForVertical((scroll.scrollY - excerpt.top - excerpt.compoundPaddingTop).coerceAtLeast(0)))
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun currentRoot(): View? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
    private fun awaitTag(tag: String) = await("UI must render $tag") {
        main { currentRoot()?.findViewWithTag<View>(tag)?.let { it.isShown && it.width > 0 && it.height > 0 } == true }
    }
    private fun awaitText(text: String) = await("UI must render $text") {
        main { currentRoot()?.let(::descendants)?.filterIsInstance<TextView>()?.any { it.text.toString() == text } == true } ||
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.any { it.text?.toString() == text } == true
    }
    private fun awaitContains(text: String) = await("UI must preserve source attribution") {
        main { currentRoot()?.let(::descendants)?.filterIsInstance<TextView>()?.any { it.text.contains(text) } == true } ||
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.any { it.text?.contains(text) == true } == true
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun capture(f: JSONObject, name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(app.filesDir, "b12-context-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        f.getJSONArray("screenshots").put(name)
        save(f)
    }
    private fun legacyReading() = SavedReading("wiki_999001_1", ReadingContent(999001, "Algorithm", "SYNTHETIC legacy queue fixture",
        "Synthetic local-only legacy queued excerpt. This is not an API source receipt.", 1, "2026-10-28T00:00:00Z",
        "https://en.wikipedia.org/wiki/Algorithm", "https://en.wikipedia.org/w/index.php?oldid=1", "CC BY-SA 4.0",
        "https://creativecommons.org/licenses/by-sa/4.0/", "SYNTHETIC FIXTURE ONLY", "Test fixture", 100), 100)

    companion object { private const val PASSWORD = "B12-local-fixture-42" }
}
