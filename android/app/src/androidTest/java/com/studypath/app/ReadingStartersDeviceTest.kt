package com.studypath.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.studypath.app.data.ReadingStarter
import com.studypath.app.data.SavedReading
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.ReadingFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Host selects three fresh-process stages, partitions real transport and inspects local server data. */
@RunWith(AndroidJUnit4::class)
class ReadingStartersDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val fixtureFile get() = File(app.filesDir, "b12-reading-starters-fixture.json")
    private val expected by lazy {
        JSONObject(instrumentation.context.assets.open("reading-starters.json").bufferedReader().use { it.readText() })
    }

    @Before fun selectedLocalStageOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.ReadingStartersDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
    }

    @Test fun browseAndSave() = runBlocking<Unit> {
        withTimeout(180_000) {
            assertNull("Use a fresh read-only test AVD; never clear a bound preview.", repository.boundUid())
            val email = "reading_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            val session = repository.register(email, PASSWORD, "Synthetic reading learner")
            val fixture = JSONObject().put("schemaVersion", 1).put("uid", session.uid).put("email", email)
                .put("observations", JSONObject())
            save(fixture)
            repository.connection().first { it }
            val starters = repository.readingStarters().first { it.size == 3 }
            assertMetadata(starters)
            observed(fixture, "gatheredStarterMetadataMatchesPublishedRecords")
            assertTrue(repository.savedReadings().first().isEmpty())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Maybe later")
                onView(withText("Maybe later")).perform(scrollTo(), click())
                awaitText(HEADING)
                awaitText(HELP)
                await("All three supplemental live actions must render") { main { starterActions().size == 3 } }
                assertTargets(fixture, "normalTargets", largeText = false)
                onView(withText(HEADING)).perform(scrollTo())
                capture("01-starter-library")
                observed(fixture, "threeStarterCardsAnd48DpLiveTargetsRendered")
                val target = starters.single { it.pageKey == "Computer_programming" }
                onView(withContentDescription("Gathered source: ${target.title}")).perform(scrollTo(), click())
                onView(withText(containsString("Gathered revision ${target.revisionId}"))).check(matches(isDisplayed()))
                onView(withText(containsString(target.licenseTitle))).check(matches(isDisplayed()))
                onView(withText(containsString("The live article may have changed."))).check(matches(isDisplayed()))
                capture("02-gathered-source")
                onView(withText("Close")).perform(click())
                observed(fixture, "gatheredSourceDisclosurePreservesAttributionAndRevision")
                onView(withContentDescription("Open live reading: ${target.title}")).perform(scrollTo(), click())
                awaitText("Supplemental reading from Wikipedia")
                awaitText("Save offline")
                capture("03-live-reading")
                onView(withText("Save offline")).perform(scrollTo(), click())
                awaitText("Ready offline")
                val saved = repository.savedReadings().first { it.size == 1 }.single()
                assertEquals(target.pageId, saved.reading.pageId)
                assertEquals(target.pageKey, saved.reading.pageKey)
                assertEquals(target.title, saved.title)
                assertEquals(target.sourceUrl, saved.reading.sourceUrl)
                assertEquals(target.licenseTitle, saved.reading.licenseTitle)
                assertEquals(target.licenseUrl, saved.reading.licenseUrl)
                assertEquals("https://en.wikipedia.org/w/index.php?oldid=${saved.reading.revisionId}", saved.reading.revisionUrl)
                assertTrue(saved.excerpt.isNotBlank())
                assertTrue(saved.reading.attribution.contains("Wikipedia contributors"))
                assertTrue(repository.isReadingSaveConfirmed(saved.id))
                fixture.put("savedId", saved.id).put("liveRevisionId", saved.reading.revisionId)
                    .put("liveRevisionTimestamp", saved.reading.revisionTimestamp)
                    .put("liveRetrievedAt", saved.reading.retrievedAt)
                    .put("liveExcerpt", saved.excerpt)
                save(fixture)
                onView(withText("Source & license")).perform(scrollTo(), click())
                onView(withText(containsString(saved.reading.attribution))).check(matches(isDisplayed()))
                onView(withText(containsString(saved.reading.licenseTitle))).check(matches(isDisplayed()))
                capture("04-saved-attribution")
                onView(withText("Close")).perform(click())
                observed(fixture, "liveApiReadingSavedOnlyAfterServerConfirmation")
                scenario.recreate()
                awaitText("Save offline")
                assertEquals(saved.id, repository.savedReadings().first().single().id)
            }
            // Complete collection reads warm the SDK's durable caches before host process death.
            assertMetadata(repository.readingStarters().first())
            assertEquals(fixture.getString("savedId"), repository.savedReadings().first().single().id)
            fixture.put("stage1Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun recoverOfflineAtLargeText() = runBlocking<Unit> {
        withTimeout(120_000) {
            val fixture = read()
            assertEquals(fixture.getString("uid"), repository.resumeSession()?.uid)
            assertNotEquals(fixture.getInt("stage1Pid"), android.os.Process.myPid())
            repository.connection().first { !it }
            assertTrue("The host must set the actual system font scale to 200% before launch.", app.resources.configuration.fontScale >= 1.99f)
            assertMetadata(repository.readingStarters().first())
            val saved = repository.savedReadings().first().single()
            assertSaved(fixture, saved)
            assertFalse("Cached presence is not this-process server acknowledgement.", repository.isReadingSaveConfirmed(saved.id))
            observed(fixture, "starterMetadataAndSavedExcerptRecoverAfterOfflineProcessDeath")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Maybe later")
                onView(withText("Maybe later")).perform(scrollTo(), click())
                awaitText(HEADING)
                awaitText(HELP)
                await("All three cached live actions must render at 200% font") { main { starterActions().size == 3 } }
                assertTargets(fixture, "largeTextTargets", largeText = true)
                main {
                    val section = requireNotNull(currentRoot()?.findViewWithTag<View>("reading_starters"))
                    assertTrue(texts(section).none { it.contains("Ready offline", ignoreCase = true) })
                }
                onView(withContentDescription("Open live reading: Computer programming")).perform(scrollTo())
                capture("05-offline-starters-large-text")
                observed(fixture, "largeTextStarterTargetsWrapWithoutClippingAndAvoidOfflineClaim")
                onView(withContentDescription("Gathered source: Computer programming")).perform(scrollTo(), click())
                onView(withText(containsString("The live article may have changed."))).check(matches(isDisplayed()))
                assertSourceActions(fixture, "largeTextGatheredSourceActions")
                capture("05b-gathered-source-actions-large-text")
                onView(withText("Close")).perform(click())
                scenario.onActivity { it.openDetails(ReadingFragment.forSaved(saved.id)) }
                awaitText("Saved excerpt · Supplemental reading from Wikipedia")
                awaitText("Cached excerpt · save again to confirm sync.")
                awaitText(saved.excerpt)
                capture("06-offline-saved-large-text")
                main {
                    val excerpt = requireNotNull(currentRoot()?.let(::descendants)?.filterIsInstance<TextView>()
                        ?.singleOrNull { it.text.toString() == saved.excerpt })
                    val scroll = generateSequence(excerpt.parent) { it.parent }.filterIsInstance<ScrollView>().first()
                    assertTrue("The excerpt remains a direct child of the reading column", excerpt.parent === scroll.getChildAt(0))
                    scroll.scrollTo(0, excerpt.top)
                }
                instrumentation.waitForIdleSync()
                onView(withText(saved.excerpt)).check(matches(isDisplayed()))
                main {
                    val excerpt = requireNotNull(currentRoot()?.let(::descendants)?.filterIsInstance<TextView>()
                        ?.singleOrNull { it.text.toString() == saved.excerpt })
                    val visible = Rect()
                    assertTrue("The cached excerpt must have a visible area", excerpt.getGlobalVisibleRect(visible))
                    assertTrue("Several lines of cached excerpt must be visible", visible.height() >= 100)
                    val location = IntArray(2); excerpt.getLocationOnScreen(location)
                    val layout = requireNotNull(excerpt.layout)
                    val firstLine = layout.getLineForVertical(maxOf(0, visible.top - location[1] - excerpt.compoundPaddingTop))
                    val lastLine = layout.getLineForVertical(visible.bottom - location[1] - excerpt.compoundPaddingTop - 1)
                    assertEquals("The excerpt screenshot must show its opening lines", 0, firstLine)
                    assertTrue("The excerpt screenshot must show at least three lines", lastLine - firstLine >= 2)
                    fixture.put("largeTextExcerptVisibleArea", JSONObject().put("heightPx", visible.height())
                        .put("firstVisibleLine", firstLine).put("lastVisibleLine", lastLine))
                    save(fixture)
                }
                capture("06b-offline-excerpt-large-text")
                onView(withText("Source & license")).perform(scrollTo(), click())
                onView(withText(containsString(saved.reading.attribution))).check(matches(isDisplayed()))
                onView(withText(containsString(saved.reading.licenseTitle))).check(matches(isDisplayed()))
                capture("07-offline-attribution-large-text")
                assertSourceActions(fixture, "largeTextSavedSourceActions")
                capture("07b-offline-source-actions-large-text")
                onView(withText("Close")).perform(click())
                observed(fixture, "largeTextGatheredAndSavedSourceActionsFullyReachable")
                assertEquals(1, repository.savedReadings().first().size)
                assertFalse(repository.isReadingSaveConfirmed(saved.id))
                observed(fixture, "savedReadingAndAttributionOpenOfflineWithoutNewSave")
            }
            fixture.put("stage2Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun restoreAndLogout() = runBlocking<Unit> {
        withTimeout(90_000) {
            val fixture = read()
            val uid = fixture.getString("uid")
            assertEquals(uid, repository.resumeSession()?.uid)
            assertNotEquals(fixture.getInt("stage2Pid"), android.os.Process.myPid())
            repository.connection().first { it }
            assertMetadata(repository.readingStarters().first { it.size == 3 })
            assertSaved(fixture, repository.savedReadings().first().single())
            val events = AtomicInteger(); val closed = AtomicBoolean()
            val observer = launch {
                try { repository.readingStarters().collect { events.incrementAndGet() } }
                catch (_: IllegalStateException) { closed.set(true) }
            }
            while (events.get() == 0) delay(20)
            repository.logout()
            withTimeout(5_000) { observer.join() }
            assertTrue(closed.get())
            var rejected = false
            try { repository.readingStarters().first() } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            val endedCount = events.get()
            observed(fixture, "activeStarterObserverClosesAndSignedOutReadFails")
            assertEquals(uid, repository.boundUid())
            assertEquals(uid, repository.login(fixture.getString("email"), PASSWORD).uid)
            assertMetadata(repository.readingStarters().first { it.size == 3 })
            assertSaved(fixture, repository.savedReadings().first().single())
            delay(200)
            assertEquals(endedCount, events.get())
            observed(fixture, "sameUidRestoresStartersWithoutRevivingOldObserver")
            fixture.put("stage3Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    private fun assertMetadata(rows: List<ReadingStarter>) {
        assertEquals(3, rows.size)
        assertEquals(expected.keys().asSequence().toSet(), rows.map { it.id }.toSet())
        rows.forEach { row ->
            val value = expected.getJSONObject(row.id)
            assertEquals(value.getLong("pageId"), row.pageId)
            assertEquals(value.getString("pageKey"), row.pageKey)
            assertEquals(value.getString("title"), row.title)
            assertEquals(value.getString("description"), row.description)
            assertEquals(value.getString("topicQuery"), row.topicQuery)
            assertEquals(value.getLong("revisionId"), row.revisionId)
            assertEquals(value.getString("revisionTimestamp"), row.revisionTimestamp)
            assertEquals(value.getString("sourceUrl"), row.sourceUrl)
            assertEquals(value.getString("revisionUrl"), row.revisionUrl)
            assertEquals(value.getString("licenseTitle"), row.licenseTitle)
            assertEquals(value.getString("licenseUrl"), row.licenseUrl)
            assertEquals(value.getString("attribution"), row.attribution)
            assertEquals(value.getLong("retrievedAt"), row.gatheredAt)
        }
    }

    private fun assertSaved(fixture: JSONObject, saved: SavedReading) {
        assertEquals(fixture.getString("savedId"), saved.id)
        assertEquals(fixture.getLong("liveRevisionId"), saved.reading.revisionId)
        assertEquals(fixture.getString("liveRevisionTimestamp"), saved.reading.revisionTimestamp)
        assertEquals(fixture.getLong("liveRetrievedAt"), saved.reading.retrievedAt)
        assertEquals(fixture.getString("liveExcerpt"), saved.excerpt)
    }

    private fun assertTargets(fixture: JSONObject, key: String, largeText: Boolean) = main {
        val root = requireNotNull(currentRoot())
        val density = root.resources.displayMetrics.density
        val screenWidth = root.resources.displayMetrics.widthPixels
        val actions = starterActions()
        assertEquals(3, actions.size)
        for (id in expected.keys()) {
            val card = requireNotNull(root.findViewWithTag<View>("reading_starter_$id"))
            val record = expected.getJSONObject(id)
            assertTrue(texts(card).containsAll(listOf(record.getString("title"), record.getString("description"))))
            assertTrue(descendants(card).any { it.contentDescription == "Gathered source: ${record.getString("title")}" })
        }
        val measurements = JSONArray()
        actions.forEach { view ->
            val text = view as TextView
            assertTrue("Live target height must be at least 48dp", view.height + 1 >= 48 * density)
            assertTrue("Live target width must be at least 48dp", view.width + 1 >= 48 * density)
            val layout = requireNotNull(text.layout)
            assertTrue(layout.lineCount > 0)
            for (line in 0 until layout.lineCount) assertEquals("Action text must not be ellipsized", 0, layout.getEllipsisCount(line))
            assertTrue("Wrapped action text must fit vertically", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
            val location = IntArray(2); view.getLocationOnScreen(location)
            assertTrue("Actions must stay inside horizontal screen bounds", location[0] >= 0 && location[0] + view.width <= screenWidth)
            measurements.put(JSONObject().put("action", view.contentDescription).put("widthDp", view.width / density)
                .put("heightDp", view.height / density).put("lineCount", layout.lineCount))
        }
        if (largeText) {
            val section = requireNotNull(root.findViewWithTag<View>("reading_starters"))
            assertTrue("Large-text discovery content must wrap", descendants(section).filterIsInstance<TextView>().any { (it.layout?.lineCount ?: 0) > 1 })
        }
        fixture.put(key, measurements); save(fixture)
    }

    private fun assertSourceActions(fixture: JSONObject, key: String) {
        val measurements = JSONArray()
        for (label in listOf("Source", "License", "Close")) {
            val target = onView(withText(label))
            if (label != "Close") target.perform(scrollTo())
            target.check(matches(isCompletelyDisplayed())).check { view, error ->
                if (error != null) throw error
                val text = requireNotNull(view) as TextView
                val density = text.resources.displayMetrics.density
                assertTrue("The $label target must be at least 48dp high", text.height + 1 >= 48 * density)
                assertTrue("The $label target must be at least 48dp wide", text.width + 1 >= 48 * density)
                val layout = requireNotNull(text.layout)
                assertTrue("The $label text must fit inside its target", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
                for (line in 0 until layout.lineCount) assertEquals("The $label text must not be ellipsized", 0, layout.getEllipsisCount(line))
                measurements.put(JSONObject().put("action", label).put("widthDp", text.width / density)
                    .put("heightDp", text.height / density).put("lineCount", layout.lineCount).put("completelyDisplayed", true))
            }
        }
        fixture.put(key, measurements); save(fixture)
    }

    private fun starterActions() = currentRoot()?.let(::descendants).orEmpty()
        .filter { it.contentDescription?.toString()?.startsWith("Open live reading: ") == true }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun currentRoot(): View? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull()?.window?.decorView
    private fun awaitText(text: String) = await("UI must render '$text'") { main { currentRoot()?.let { text in texts(it) } == true } }
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
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val frames = CountDownLatch(1)
        main {
            val root = requireNotNull(currentRoot())
            root.postOnAnimation { root.postOnAnimation { frames.countDown() } }
        }
        assertTrue("Two UI frames must finish before capturing $name", frames.await(5, TimeUnit.SECONDS))
        // UI geometry can be current before SurfaceFlinger composites the scrolled content.
        SystemClock.sleep(100)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(app.filesDir, "reading-starters-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun read() = JSONObject(fixtureFile.readText())
    private fun save(value: JSONObject) { fixtureFile.writeText(value.toString()) }
    private fun observed(value: JSONObject, key: String) { value.getJSONObject("observations").put(key, true); save(value) }

    companion object {
        private const val PASSWORD = "LocalReading123!"
        private const val HEADING = "Supplemental Wikipedia readings"
        private const val HELP = "Live readings need internet. Saved excerpts are available in Saved."
    }
}
