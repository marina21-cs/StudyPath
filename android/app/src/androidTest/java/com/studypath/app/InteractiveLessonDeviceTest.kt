package com.studypath.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.appbar.MaterialToolbar
import com.studypath.app.data.*
import com.studypath.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.`is`
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host partitions transport and kills the process between these explicitly selected stages. */
@RunWith(AndroidJUnit4::class)
class InteractiveLessonDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val file get() = File(app.filesDir, "b11-lesson-device.json")
    private val key = TextPackageKey("fixture_v1", "fixture_module", 1)

    @Before fun isolatedOnly() {
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS)
        assertTrue("Never publish fabricated approvals to the normal database", BuildConfig.FIREBASE_DATABASE_URL.matches(
            Regex("https://demo-studypath-b11-ui-[a-f0-9]{8}\\.firebaseio\\.com")))
        assertTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.InteractiveLessonDeviceTest#") == true)
    }

    @Test fun downloadAndIncorrectFeedback() = runBlocking<Unit> {
        withTimeout(150_000) {
            assertNull("Restart the read-only test AVD instead of clearing a bound installation", repository.boundUid())
            val email = "lesson_${UUID.randomUUID()}@studypath.invalid"
            val session = repository.register(email, PASSWORD, "Synthetic lesson explorer")
            val receipt = JSONObject().put("schemaVersion", 1).put("uid", session.uid).put("email", email)
                .put("observations", JSONObject()).put("stage1Pid", Process.myPid())
            save(receipt)
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences("fixture_level", "fixture_v1", setOf("fixture_subject"), "fixture_class", true))
            repository.saveScore(score())
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitTag("package_download_fixture_module_v1")
                tap("package_download_fixture_module_v1")
                awaitTag("package_open_fixture_module_v1")
                assertNotNull(repository.openTextPackage(key))
                observed(receipt, "uiDownloadRequiresValidatedConfirmedBytes")
                tap("package_open_fixture_module_v1")
                awaitTag("lesson_path")
                receipt.put("headerGeometry", JSONArray().put(assertHeaderGeometry("Online")))
                val connectionDescription = main {
                    requireNotNull(activity()?.findViewById<TextView>(R.id.connection_status)).contentDescription.toString()
                }
                onView(withId(R.id.connection_status)).perform(click())
                onView(withText("Connection")).check(matches(isDisplayed()))
                onView(withText(connectionDescription)).check(matches(isDisplayed()))
                onView(withId(android.R.id.button1)).perform(click())
                awaitTag("lesson_path")
                observed(receipt, "connectionDialogExplainsCurrentStatusAndReturnsToLessonPath")
                capture("01-lesson-path")
                tap("lesson_start_lesson_1")
                awaitText("Goal")
                val beforeGlossary = requireNotNull(repository.lessonRun(module(), "lesson_1"))
                tap("lesson_tools")
                onView(withText("Glossary")).perform(click())
                onView(withText("Parts of a synthetic fixture.")).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(android.R.id.button1)).perform(click())
                assertEquals(beforeGlossary, repository.lessonRun(module(), "lesson_1"))
                observed(receipt, "glossaryLookupPreservesLessonState")
                primary(); awaitText("Worked example"); primary()
                awaitText("Guided check")
                receipt.put("readyForPartition", true); save(receipt)
                repository.connection().first { !it }
                tap("lesson_option_b"); primary()
                assertFeedbackVisible("Take another look.")
                val run = requireNotNull(repository.lessonRun(module(), "lesson_1"))
                assertEquals(LessonAnswerStatus.INCORRECT, run.answers.getValue("q_1").status)
                receipt.put("runId", run.runId)
                observed(receipt, "incorrectFeedbackIsDurableDuringActualPartition")
                observed(receipt, "incorrectFeedbackTitleIsVisibleAfterCheck")
                capture("02-offline-incorrect-feedback")
            }
        }
    }

    @Test fun recoverFeedbackAndNumericDraft() = runBlocking<Unit> {
        withTimeout(150_000) {
            val receipt = read()
            assertNotEquals(receipt.getInt("stage1Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { !it }
            val module = module()
            assertEquals(receipt.getString("runId"), repository.lessonRun(module, "lesson_1")!!.runId)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                openFromSaved()
                tap("lesson_start_lesson_1")
                assertFeedbackVisible("Take another look.")
                observed(receipt, "sameWrongFeedbackRestoresAfterOfflineProcessDeath")
                observed(receipt, "incorrectFeedbackTitleIsVisibleAfterOfflineRestore")
                tap("lesson_retry"); tap("lesson_option_a"); primary(); assertFeedbackVisible("That’s it."); primary()
                awaitText("Independent ordering")
                tap("lesson_order_down_first")
                tap("lesson_order_down_second")
                primary(); assertFeedbackVisible("That’s it."); primary()
                observed(receipt, "correctFeedbackTitlesAreVisibleAt200PercentText")
                awaitText("Independent number")
                tagged("lesson_response").perform(scrollTo(), replaceText("3.04")); closeSoftKeyboard()
                await("Numeric draft is durably retained") {
                    runBlocking { repository.lessonRun(module, "lesson_1")?.answers?.get("q_number")?.draft == listOf("3.04") }
                }
                assertLargeTextNumericLayout(receipt)
                tagged("lesson_response").perform(scrollTo(), replaceText("3.03"))
                pressBack()
                openFromSaved(); tap("lesson_start_lesson_1")
                awaitTag("lesson_response")
                tagged("lesson_response").check(matches(withText("3.03")))
                observed(receipt, "numericDraftSurvivesImmediateBackAndReentryOffline")
                tagged("lesson_response").perform(scrollTo(), replaceText("3.04"))
                await("Restored numeric draft must be durably retained before recreation") {
                    runBlocking { repository.lessonRun(module, "lesson_1")?.answers?.get("q_number")?.draft == listOf("3.04") }
                }
                scenario.recreate()
                awaitTag("lesson_response")
                tagged("lesson_response").check(matches(withText("3.04")))
                observed(receipt, "correctRetryAndAccessibleOrderingPreserveOneRun")
                observed(receipt, "numericDraftSurvivesActivityRecreationOffline")
                capture("03-offline-numeric-draft")
                receipt.put("stage2Pid", Process.myPid()); save(receipt)
            }
        }
    }

    @Test fun completeOfflineAndRetainPendingResult() = runBlocking<Unit> {
        withTimeout(150_000) {
            val receipt = read()
            assertNotEquals(receipt.getInt("stage2Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { !it }
            ActivityScenario.launch(MainActivity::class.java).use {
                openFromSaved(); tap("lesson_start_lesson_1")
                awaitTag("lesson_response"); tagged("lesson_response").check(matches(withText("3.04")))
                primary(); assertFeedbackVisible("That’s it."); primary()
                awaitText("Independent short answer")
                tap("lesson_hint")
                tagged("lesson_response").perform(scrollTo(), replaceText("B")); closeSoftKeyboard()
                primary(); assertFeedbackVisible("Take another look."); tap("lesson_reveal")
                assertFeedbackVisible("Assisted practice"); primary()
                awaitTag("lesson_summary")
                val module = module()
                val run = requireNotNull(repository.lessonRun(module, "lesson_1"))
                assertTrue(run.completed)
                assertEquals(receipt.getString("runId"), run.runId)
                val attempt = LessonEngine.attempt(module, run)
                assertEquals(6, attempt["resolvedSteps"])
                assertEquals(1, attempt["assistedChecks"])
                receipt.put("attempt", JSONObject(attempt))
                tap("lesson_submit")
                await("Offline result must retain unknown confirmation", 35_000) { main { texts().any { it.contains("Server confirmation is still unknown") } } }
                assertNull(repository.lessonRunConfirmedAt(module, run))
                val removal = runCatching { withTimeout(1000) { repository.removeTextPackage(key) } }.exceptionOrNull()
                assertTrue("Unconfirmed results must block removal before any network wait", removal is IllegalArgumentException)
                assertNotNull(repository.openTextPackage(key))
                observed(receipt, "pendingResultsPreventStrandingByPackageRemoval")
                observed(receipt, "numericDraftRestoresAfterOfflineProcessDeath")
                observed(receipt, "assistedCompletionStaysDistinctFromFirstTrySuccess")
                observed(receipt, "offlineCompletionRetainsExactPendingAttempt")
                tagged("lesson_sync").perform(scrollTo()).check(matches(isDisplayed()))
                    .check(matches(withText("Saved on this phone")))
                capture("04-offline-completion")
                tap("lesson_review_start_lesson_1")
                awaitTag("lesson_step"); primary(); primary()
                awaitTag("lesson_response")
                tagged("lesson_response").perform(scrollTo(), replaceText("A")); closeSoftKeyboard()
                primary(); assertFeedbackVisible("That’s it."); primary(); awaitTag("lesson_summary")
                val review = module.lessons.single { it.reviewOf == "lesson_1" }
                val reviewRun = requireNotNull(repository.lessonRun(module, review.id))
                val history = repository.lessonHistory(module)
                val schedule = requireNotNull(LessonReviewPolicy.schedule(module, "lesson_1", history))
                assertEquals(1, schedule.stage)
                assertEquals(schedule, LessonReviewPolicy.schedule(module, "lesson_1", history + reviewRun))
                receipt.put("reviewRunId", reviewRun.runId).put("reviewAttempt", JSONObject(LessonEngine.attempt(module, reviewRun)))
                observed(receipt, "authoredReviewIsPrivateVersionBoundAndIdempotent")
                receipt.put("stage3Pid", Process.myPid()); save(receipt)
            }
        }
    }

    @Test fun reconnectAndRetryExactResult() = runBlocking<Unit> {
        withTimeout(150_000) {
            val receipt = read()
            assertNotEquals(receipt.getInt("stage3Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { it }
            ActivityScenario.launch(MainActivity::class.java).use {
                openFromSaved(); tap("lesson_start_lesson_1"); awaitTag("lesson_summary")
                tap("lesson_sync_all")
                await("Explicit server acknowledgment appears") { main { texts().any { it == "Saved" } } }
                val module = module(); val run = requireNotNull(repository.lessonRun(module, "lesson_1"))
                val original = LessonEngine.attempt(module, run)
                repository.submitLessonRun(module, run)
                repository.syncPendingLessonRuns(module)
                assertEquals(original, LessonEngine.attempt(module, run))
                assertNotNull(repository.lessonRunConfirmedAt(module, run))
                assertEquals(0.0, repository.scores().first { it.isNotEmpty() }.single().earnedPoints!!, 0.0)
                observed(receipt, "reconnectAndExactRetryKeepOneImmutableResult")
                observed(receipt, "lessonPracticeLeavesAcademicScoreUnchanged")
                tagged("lesson_sync").perform(scrollTo()).check(matches(isDisplayed()))
                    .check(matches(withText("Saved")))
                receipt.getJSONArray("headerGeometry").put(assertHeaderGeometry("Online"))
                capture("05-confirmed-completion")
                receipt.put("stage4Pid", Process.myPid()); save(receipt)
            }
        }
    }

    @Test fun rejectMalformedDirectTransitionsAndRetainIdentity() = runBlocking<Unit> {
        withTimeout(150_000) {
            val receipt = read()
            assertNotEquals(receipt.getInt("stage4Pid"), Process.myPid())
            assertEquals(receipt.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { it }
            val module = module()
            val original = requireNotNull(repository.lessonRun(module, "lesson_1"))
            assertTrue(original.completed)
            assertEquals(receipt.getString("runId"), original.runId)
            val history = repository.lessonHistory(module)
            val attempt = LessonEngine.attempt(module, original)
            val confirmation = repository.lessonRunConfirmedAt(module, original)
            assertNotNull(confirmation)

            suspend fun rejects(label: String, action: suspend () -> Unit) {
                val failure = runCatching { withTimeout(4000) { action() } }.exceptionOrNull()
                assertTrue("$label must reject explicitly before a server write or timeout; actual=$failure",
                    failure is IllegalArgumentException || failure is IllegalStateException)
            }
            suspend fun rejectsSaveAndSubmit(label: String, content: InteractiveModule, candidate: LessonRun) {
                rejects("$label save") { repository.saveLessonRun(content, candidate) }
                rejects("$label submit") { repository.submitLessonRun(content, candidate) }
            }

            rejectsSaveAndSubmit("Wrong immutable version", module.copy(summary = module.summary.copy(version = 2)), original)
            rejectsSaveAndSubmit("Wrong immutable hash", module.copy(summary = module.summary.copy(packageSha256 = "0".repeat(64))), original)
            val fresh = LessonEngine.start(module, "lesson_1",
                LessonReviewPolicy.nextRunStartedAt(module, history, System.currentTimeMillis()))
            rejectsSaveAndSubmit("Unknown question", module, fresh.copy(answers = fresh.answers + ("unknown_question" to LessonAnswerState())))
            rejectsSaveAndSubmit("Impossible position", module, fresh.copy(position = 999))
            rejectsSaveAndSubmit("Impossible checked state", module, fresh.copy(answers = fresh.answers +
                ("q_1" to LessonAnswerState(draft = listOf("a"), status = LessonAnswerStatus.CORRECT))))
            observed(receipt, "directRepositoryRejectsWrongPackageQuestionAndState")

            rejectsSaveAndSubmit("Changed immutable completion", module,
                original.copy(completedAt = requireNotNull(original.completedAt) + 1, revision = original.revision + 1))
            val first = module.lessons.single { it.id == "lesson_1" }.steps.first()
            val advanced = LessonEngine.continueStep(module, fresh, first.id, System.currentTimeMillis())
            assertEquals(advanced, LessonEngine.continueStep(module, advanced, first.id, System.currentTimeMillis()))
            assertEquals(original, LessonEngine.continueStep(module, original, first.id, System.currentTimeMillis()))
            assertEquals(original, LessonEngine.check(module, original, first.id, System.currentTimeMillis()))
            repeat(2) {
                repository.saveLessonRun(module, original)
                repository.submitLessonRun(module, original)
            }
            assertEquals(attempt, LessonEngine.attempt(module, requireNotNull(repository.lessonRun(module, "lesson_1"))))
            assertEquals(confirmation, repository.lessonRunConfirmedAt(module, original))
            observed(receipt, "directRepositoryRejectsChangedCompletionAndAllowsExactDuplicates")

            assertEquals(original, repository.lessonRun(module, "lesson_1"))
            assertEquals(history, repository.lessonHistory(module))
            assertEquals(0, repository.pendingLessonRuns(module).size)
            assertEquals(0.0, repository.scores().first { it.isNotEmpty() }.single().earnedPoints!!, 0.0)
            observed(receipt, "directRepositoryRejectionsPreserveHistoryAndAcademicScore")
            receipt.put("stage5Pid", Process.myPid()); save(receipt)
        }
    }

    private fun module() = InteractiveLessonParser.parse(requireNotNull(repository.openTextPackage(key)).verified)
    private fun assertFeedbackVisible(title: String) {
        await("The actual $title feedback heading must be visible without a test scroll") {
            main {
                val panel = activity()?.window?.decorView?.findViewWithTag<View>("lesson_feedback")
                panel != null && all(panel).filterIsInstance<TextView>().any { text ->
                    val area = Rect()
                    text.text.toString() == title && text.getGlobalVisibleRect(area) &&
                        area.width() >= text.width * .9 && area.height() >= text.height * .9
                }
            }
        }
        onView(org.hamcrest.Matchers.allOf(withText(title), isDescendantOfA(withTagValue(`is`("lesson_feedback" as Any)))))
            .check(matches(isDisplayed()))
    }
    private fun assertLargeTextNumericLayout(receipt: JSONObject) {
        val measures = JSONArray()
        val scale = main { requireNotNull(activity()).resources.configuration.fontScale }
        assertTrue("Host must configure the isolated test AVD for 200% text", scale >= 1.95f)
        for (key in listOf("lesson_response", "lesson_primary")) {
            if (key == "lesson_response") tagged(key).perform(scrollTo())
            tagged(key).check { view, failure ->
                if (failure != null) throw failure
                val text = requireNotNull(view) as TextView
                val density = text.resources.displayMetrics.density
                val layout = requireNotNull(text.layout)
                assertTrue("$key must retain a 48dp target at 200% text", text.width + 1 >= 48 * density && text.height + 1 >= 48 * density)
                assertTrue("$key text must fit vertically", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
                for (line in 0 until layout.lineCount) assertEquals("$key text must not be ellipsized", 0, layout.getEllipsisCount(line))
                measures.put(JSONObject().put("tag", key).put("widthDp", text.width / density)
                    .put("heightDp", text.height / density).put("lineCount", layout.lineCount).put("ellipsized", false))
            }
        }
        tap("lesson_response")
        await("The software keyboard must be visible for the lesson inset check") {
            main { activity()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true }
        }
        val header = assertHeaderGeometry("Offline")
        receipt.getJSONArray("headerGeometry").put(header)
        main {
            val owner = requireNotNull(activity())
            val decor = owner.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            val primary = requireNotNull(decor.findViewWithTag<View>("lesson_primary"))
            val actionArea = Rect(); assertTrue("The primary lesson action must stay visible with the IME open", primary.getGlobalVisibleRect(actionArea))
            assertTrue("At least 90% of the action target must remain visible", actionArea.width() >= primary.width * .9 && actionArea.height() >= primary.height * .9)
            val windowArea = Rect(); decor.getWindowVisibleDisplayFrame(windowArea)
            val contentArea = Rect(); assertTrue(owner.findViewById<View>(R.id.main_content).getGlobalVisibleRect(contentArea))
            assertTrue("The primary action must remain above the IME", actionArea.bottom <= windowArea.bottom + 1)
            assertTrue("The primary action must remain within the inset content bounds", actionArea.left >= contentArea.left - 1 && actionArea.right <= contentArea.right + 1 &&
                actionArea.top >= contentArea.top - 1 && actionArea.bottom <= contentArea.bottom + 1)
            assertTrue("An observed keyboard must have a nonzero IME inset", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0)
            receipt.put("largeTextNumericLayout", JSONObject().put("fontScale", scale).put("targets", measures)
                .put("headerGeometry", header)
                .put("imeVisible", true).put("imeBottomPx", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                .put("actionBoundsPx", JSONArray(listOf(actionArea.left, actionArea.top, actionArea.right, actionArea.bottom)))
                .put("visibleWindowBottomPx", windowArea.bottom).put("primaryAtLeast90PercentVisible", true))
        }
        tagged("lesson_response").check(matches(withText("3.04")))
        capture("03b-numeric-200pct-keyboard")
        closeSoftKeyboard()
        await("The keyboard must close before the immediate Back check") {
            main { activity()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == false }
        }
        observed(receipt, "numeric200PercentTextRetainsTargetsDraftAndPrimaryAboveIme")
    }
    private fun assertHeaderGeometry(expectedStatus: String): JSONObject {
        await("Lesson title and $expectedStatus connection status must finish rendering") {
            main {
                val toolbar = activity()?.findViewById<MaterialToolbar>(R.id.toolbar)
                val status = activity()?.findViewById<TextView>(R.id.connection_status)
                toolbar != null && status != null && toolbar.title?.toString() == "Lesson" && status.text.toString() == expectedStatus &&
                    status.layout != null && status.width > 0 && all(toolbar).filterIsInstance<TextView>()
                        .any { it.text.toString() == "Lesson" && it.layout != null && it.width > 0 }
            }
        }
        return main {
            val owner = requireNotNull(activity())
            val toolbar = owner.findViewById<MaterialToolbar>(R.id.toolbar)
            val title = all(toolbar).filterIsInstance<TextView>().single { it.text.toString() == "Lesson" }
            val status = owner.findViewById<TextView>(R.id.connection_status)
            val density = owner.resources.displayMetrics.density
            fun textBounds(text: TextView, label: String): Rect {
                val visible = Rect()
                assertTrue("$label must be visible", text.getGlobalVisibleRect(visible))
                assertTrue("At least 90% of $label must be visible", visible.width() >= text.width * .9 && visible.height() >= text.height * .9)
                val layout = requireNotNull(text.layout)
                val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                assertTrue("$label text must fit vertically", layout.height <= availableHeight + 2)
                for (line in 0 until layout.lineCount) {
                    assertEquals("$label text must not be ellipsized", 0, layout.getEllipsisCount(line))
                    assertTrue("$label text must fit horizontally", layout.getLineWidth(line) <= availableWidth + 2)
                }
                val location = IntArray(2)
                text.getLocationOnScreen(location)
                return Rect(location[0], location[1], location[0] + text.width, location[1] + text.height)
            }
            val titleBounds = textBounds(title, "Lesson title")
            val statusBounds = textBounds(status, "$expectedStatus connection status")
            assertFalse("Lesson title must not overlap $expectedStatus status", Rect.intersects(titleBounds, statusBounds))
            assertTrue("Connection status must retain a 48dp touch target", status.width + 1 >= 48 * density && status.height + 1 >= 48 * density)
            val sameRow = titleBounds.top < statusBounds.bottom && statusBounds.top < titleBounds.bottom
            val gapPx = if (titleBounds.right <= statusBounds.left) statusBounds.left - titleBounds.right else titleBounds.left - statusBounds.right
            if (sameRow) assertTrue("Lesson title and status must have at least an 8dp gap", gapPx + 1 >= 8 * density)
            JSONObject().put("title", title.text.toString()).put("status", status.text.toString())
                .put("fontScale", owner.resources.configuration.fontScale)
                .put("imeVisible", ViewCompat.getRootWindowInsets(owner.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                .put("titleBoundsPx", JSONArray(listOf(titleBounds.left, titleBounds.top, titleBounds.right, titleBounds.bottom)))
                .put("statusBoundsPx", JSONArray(listOf(statusBounds.left, statusBounds.top, statusBounds.right, statusBounds.bottom)))
                .put("statusWidthDp", status.width / density).put("statusHeightDp", status.height / density)
                .put("titleAndStatusOverlap", false).put("titleAndStatusAtLeast90PercentVisible", true)
                .put("textEllipsizedOrClipped", false).put("sameRow", sameRow)
                .put("horizontalGapDp", if (sameRow) gapPx / density else JSONObject.NULL)
        }
    }
    private fun score() = ScoreRecord("b11_score", "fixture_course", "quiz_1", "quizzes", "SCORED", 20.0, 0.0, planVersion = "fixture_plan_v1")
    private fun openFromSaved() {
        await("Main navigation is ready") { main { activity()?.findViewById<View>(R.id.nav_saved)?.isShown == true } }
        onView(withId(R.id.nav_saved)).perform(click())
        awaitTag("package_open_fixture_module_v1")
        tap("package_open_fixture_module_v1"); awaitTag("lesson_path")
    }
    private fun primary() = tap("lesson_primary")
    private fun tap(tag: String) {
        await("UI control $tag must be ready") { main { activity()?.window?.decorView?.findViewWithTag<View>(tag)?.isEnabled == true } }
        val visible = main {
            val view = requireNotNull(activity()?.window?.decorView?.findViewWithTag<View>(tag))
            val area = Rect()
            view.getGlobalVisibleRect(area) && area.height() >= view.height * .9 && area.width() >= view.width * .9
        }
        if (!visible) tagged(tag).perform(scrollTo())
        tagged(tag).perform(click()); instrumentation.waitForIdleSync()
    }
    private fun tagged(tag: String) = onView(withTagValue(`is`(tag as Any)))
    private fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun texts() = activity()?.window?.decorView?.let(::all).orEmpty().filterIsInstance<TextView>().map { it.text.toString() }
    private fun awaitTag(tag: String) = await("UI must render $tag") { main { activity()?.window?.decorView?.findViewWithTag<View>(tag) != null } }
    private fun awaitText(text: String) = await("UI must render $text") { main { text in texts() } }
    private fun await(message: String, timeout: Long = 45_000, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { instrumentation.waitForIdleSync(); if (check()) return; SystemClock.sleep(70) }
        throw AssertionError(message)
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null; instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun capture(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(app.filesDir, "b11-lesson-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun read() = JSONObject(file.readText())
    private fun save(value: JSONObject) { file.writeText(value.toString()) }
    private fun observed(value: JSONObject, key: String) { value.getJSONObject("observations").put(key, true); save(value) }
    companion object { private const val PASSWORD = "LocalLesson123!" }
}
