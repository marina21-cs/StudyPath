package com.studypath.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.studypath.app.data.*
import com.studypath.app.ui.InteractiveLessonFragment
import com.studypath.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matchers.equalTo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Separate local account/storage. Seeded package/confirmation states are UI fixtures, not backend acknowledgement evidence. */
@RunWith(AndroidJUnit4::class)
class NativeLearningRevisionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private lateinit var original: StudyPathGraph
    private lateinit var firebase: FirebaseApp
    private lateinit var database: FirebaseDatabase
    private lateinit var repository: FirebaseStudyRepository
    private lateinit var packages: TextPackageStore
    private lateinit var lessons: LessonRunStore
    private val observations = JSONObject()

    @Before fun isolatedLocalGraph() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.NativeLearningRevisionDeviceTest") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        original = app.graph
        val suffix = UUID.randomUUID().toString().replace("-", "")
        firebase = FirebaseApp.initializeApp(app, FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY).setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL).build(), "native_learning_$suffix")
        val auth = FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) }
        database = FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000); setPersistenceEnabled(false); goOffline()
        }
        val directory = File(app.filesDir, "native-learning-revision/$suffix")
        packages = TextPackageStore(File(directory, "packages")); lessons = LessonRunStore(File(directory, "lessons"))
        repository = FirebaseStudyRepository(auth, database, app.getSharedPreferences("native_learning_$suffix", Context.MODE_PRIVATE),
            packages, lessons, ReadingStateStore(File(directory, "readings")), reminderChanged = { _, _, _ -> })
        setGraph(StudyPathGraph(repository, original.readings))
    }

    @After fun restoreDefaultGraph() {
        if (::database.isInitialized) database.goOffline()
        if (::original.isInitialized) setGraph(original)
        if (::firebase.isInitialized) firebase.delete()
    }

    @Test fun plannerModesAndExactLessonResumeLeadToAuthoredReview() = runBlocking<Unit> {
        withTimeout(120_000) {
            val session = repository.register("native_learning_${UUID.randomUUID()}@studypath.invalid", "LocalUiRevision123!", "Synthetic learning UI learner")
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences(levelId = "ui_fixture", subjectIds = setOf("ui_subject"), onboardingComplete = false))
            val verified = packageFixture()
            val module = InteractiveLessonParser.parse(verified)
            val originalLesson = module.lessons.first { it.reviewOf == null }
            packages.stage(session.uid, verified)
            packages.confirm(session.uid, verified, System.currentTimeMillis())
            var retained = LessonEngine.start(module, originalLesson.id, System.currentTimeMillis())
            retained = LessonEngine.continueStep(module, retained, originalLesson.steps.first().id, System.currentTimeMillis())
            lessons.save(session.uid, module, retained)
            observations.put("fixtureScope", "Synthetic package READY and confirmed result states are local UI fixtures. No real download/server acknowledgement assertion.")

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_saved }
                awaitTag("saved_lessons_tab"); tap("saved_lessons_tab")
                val packageStatus = "package_status_${module.summary.moduleId}_v${module.summary.version}"
                awaitTag(packageStatus)
                assertEquals("Ready offline", text(packageStatus))
                capture("04a-saved-download")
                observations.put("readyDownloadUsesOnePlainStatus", true)
                scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_planner }
                onView(withText("Grades")).perform(click())
                tap("planner_reviewed_tools")
                awaitTag("planner_mode_scores")
                awaitTag("planner_policy_unavailable")
                assertNull(main { root()?.findViewWithTag<View>("planner_average") })
                tap("planner_mode_what-if")
                assertTrue(main { root()?.findViewWithTag<View>("planner_mode_what-if")?.isSelected == true })
                scenario.recreate()
                awaitTag("planner_mode_what-if")
                assertTrue(main { root()?.findViewWithTag<View>("planner_mode_what-if")?.isSelected == true })
                assertNull("An unsupported policy must never invent a scenario average", main { root()?.findViewWithTag<View>("planner_scenario_average") })
                assertTrue(main { root()?.findViewWithTag<View>("planner_policy_unavailable")?.isShown == true })
                observations.put("plannerModeSurvivesRecreationWithoutInventingPolicyNullAverage", true)
                capture("04-planner-policy-unavailable")
                tap("planner_policy_details")
                assertEquals("Expanded", main { androidx.core.view.ViewCompat.getStateDescription(requireNotNull(root()?.findViewWithTag<View>("planner_policy_details")))?.toString() })
                tap("planner_policy_details")
                observations.put("policyReasonsRemainAvailableOnDemand", true)
                tap("planner_mode_roadmap")
                awaitTag("planner_prerequisite_chain")
                assertNull("The course map must not bypass absent assessment policy", main { root()?.findViewWithTag<View>("planner_average") })
                assertTrue(main { texts(requireNotNull(root())).any { it.contains("UNKNOWN ?") } })
                scenario.recreate(); awaitTag("planner_mode_roadmap")
                assertTrue(main { root()?.findViewWithTag<View>("planner_mode_roadmap")?.isSelected == true })
                observations.put("roadmapSurvivesRecreationAndPreservesUnknownPolicy", true)

                scenario.onActivity { it.openDetails(InteractiveLessonFragment.forPackage(verified.key, originalLesson.id)) }
                awaitTag("lesson_step")
                assertTrue(text("lesson_progress").startsWith("Step 2 of "))
                assertEquals(retained.runId, repository.lessonRun(module, originalLesson.id)?.runId)
                scenario.recreate(); awaitTag("lesson_step")
                assertTrue(text("lesson_progress").startsWith("Step 2 of "))
                assertEquals(retained.runId, repository.lessonRun(module, originalLesson.id)?.runId)
                observations.put("exactDurableLessonRunAndPositionResumeAfterRecreation", true)
                capture("05-lesson-exact-resume")

                onView(withTagValue(equalTo<Any>("lesson_primary"))).perform(click())
                awaitTag("lesson_option_b")
                tap("lesson_option_b")
                onView(withTagValue(equalTo<Any>("lesson_primary"))).perform(click())
                awaitTag("lesson_retry")
                assertTrue(main { texts(requireNotNull(root())).contains("Take another look.") })
                onView(withTagValue(equalTo<Any>("lesson_retry"))).perform(click())
                awaitTag("lesson_option_b")
                tap("lesson_option_b")
                onView(withTagValue(equalTo<Any>("lesson_primary"))).perform(click())
                awaitTag("lesson_reveal")
                tap("lesson_reveal")
                awaitTag("lesson_primary")
                assertTrue(main { texts(requireNotNull(root())).contains("Assisted practice") })
                val assisted = requireNotNull(repository.lessonRun(module, originalLesson.id))
                assertEquals(LessonAnswerStatus.ASSISTED, assisted.answers.getValue("q_1").status)
                assertEquals(2, assisted.answers.getValue("q_1").wrongCount)
                observations.put("incorrectFeedbackTryAgainAndExplicitRevealKeepAssistedPracticeHistory", true)
                capture("05b-calm-assisted-feedback")
                onView(withTagValue(equalTo<Any>("lesson_primary"))).perform(click())

                val completed = finish(module, requireNotNull(repository.lessonRun(module, originalLesson.id)))
                lessons.save(session.uid, module, completed)
                lessons.confirm(session.uid, module, completed, System.currentTimeMillis())
                scenario.onActivity { it.openDetails(InteractiveLessonFragment.forPackage(verified.key, originalLesson.id)) }
                awaitTag("lesson_summary"); awaitTag("lesson_next")
                assertNull("A confirmed result must not invite resubmission", main { root()?.findViewWithTag<View>("lesson_submit") })
                assertEquals("Back to lesson path", text("lesson_next"))
                assertEquals(completed, repository.lessonRun(module, originalLesson.id))
                observations.put("confirmedSummaryOffersNextDestinationWithoutRetryingSameResult", true)
                assertFalse("Detailed results start collapsed", main { root()?.findViewWithTag<View>("lesson_result_details_body")?.isShown == true })
                capture("06-lesson-confirmed-next")
                tap("lesson_result_details")
                assertTrue("Detailed results remain reachable", main { root()?.findViewWithTag<View>("lesson_result_details_body")?.isShown == true })
                assertTrue(main { texts(requireNotNull(root()?.findViewWithTag<View>("lesson_result_details_body"))).contains("Practice only · doesn’t affect your grades.") })
                assertTrue("Results use learner language", main { texts(requireNotNull(root())).none {
                    it.contains("server", ignoreCase = true) || it.contains("sync", ignoreCase = true)
                } })
                capture("06b-practice-details")
                tap("lesson_result_details")
                assertFalse(main { root()?.findViewWithTag<View>("lesson_result_details_body")?.isShown == true })
                assertEquals("Opening details must not alter the result", completed, repository.lessonRun(module, originalLesson.id))
                observations.put("compactSummaryExpandsDetailsWithoutChangingAttempt", true)
                tap("lesson_summary_options")
                assertTrue("Related readings remain available in More options", main { root()?.findViewWithTag<View>("lesson_related_readings")?.isShown == true })
                tap("lesson_summary_options")
                assertFalse(main { root()?.findViewWithTag<View>("lesson_related_readings")?.isShown == true })
                observations.put("secondarySummaryActionsRemainReachable", true)

                scenario.onActivity { activity ->
                    activity.goToStudy()
                    activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_account
                }
                onView(withText("Study reminders & next review")).perform(scrollTo(),click())
                awaitTag("profile_open_review")
                assertTrue(main { root()?.findViewWithTag<View>("reminders")?.isShown == true })
                assertFalse(main { root()?.findViewWithTag<View>("profile_preferences_action")?.isShown == true })
                observations.put("accountUsesFactualAuthoredReviewAndCollapsedOptionalControls", true)
                capture("07-account-next-review")
                pressBack(); tap("profile_settings"); tap("settings_account")
                awaitTag("profile_sync_action")
                assertEquals("Saved changes", text("profile_sync_action"))
                assertTrue(main { texts(requireNotNull(root()?.findViewWithTag<View>("profile_sync_section"))).none {
                    it.contains("server", ignoreCase = true) || it.contains("sync", ignoreCase = true)
                } })
                onView(withTagValue(equalTo<Any>("profile_sync_action"))).perform(scrollTo())
                capture("07b-account-save-settings")
                pressBack(); pressBack()
                onView(withText("Study reminders & next review")).perform(scrollTo(),click())
                tap("profile_open_review"); awaitTag("lesson_step")
                val review = module.lessons.single { it.reviewOf == originalLesson.id }
                val reviewRun = requireNotNull(repository.lessonRun(module, review.id))
                assertEquals(0, reviewRun.position)
                assertEquals(completed, repository.lessonRun(module, originalLesson.id))
                scenario.recreate(); awaitTag("lesson_step")
                assertEquals(reviewRun.runId, repository.lessonRun(module, review.id)?.runId)
                observations.put("openReviewStartsAuthoredVersionAndRecreationPreservesExactRun", true)
            }
            val receipt = File(app.getExternalFilesDir(null), "native-slide-revision/learning-observations.json")
            receipt.parentFile?.mkdirs(); receipt.writeText(observations.toString(2))
        }
    }

    @Test fun revisionThreeProfileStepsRoleFilterAndAccountEntry() = runBlocking<Unit> {
        withTimeout(120_000) {
            val owner = repository.register("ux_profile_${UUID.randomUUID()}@studypath.invalid", "LocalUiRevision123!", "Original name")
            repository.connection().first { it }
            repository.saveLearnerPreferences(LearnerPreferences(levelId = "college"))
            com.studypath.app.ui.OptionalOnboarding.skip(app, owner.uid)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.openDetails(com.studypath.app.ui.LearnerPreferencesFragment()) }
                awaitTag("preference_display_name")
                withTimeout(20_000) {
                    while (!main { root()?.findViewWithTag<View>("preference_next")?.isEnabled == true }) kotlinx.coroutines.delay(50)
                }
                onView(withTagValue(equalTo<Any>("preference_display_name"))).perform(scrollTo(), androidx.test.espresso.action.ViewActions.replaceText("  Keni  "))
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                capture("08-profile-step-one")
                onView(withTagValue(equalTo<Any>("preference_next"))).perform(scrollTo())
                capture("08b-profile-level-options")
                tap("preference_next")
                tap("preference_subject_programming")
                assertTrue(text("preference_unlock_summary").contains("Wikipedia"))
                capture("09-profile-step-two")
                onView(withTagValue(equalTo<Any>("preference_save"))).perform(scrollTo())
                capture("09b-subjects-and-capabilities")
                scenario.recreate(); awaitTag("preference_save")
                assertTrue(main { root()?.findViewWithTag<View>("preference_subject_step")?.isShown == true })
                tap("preference_save"); awaitTag("onboarding_ready")
                assertTrue(text("ready_heading").contains("Keni"))
                assertEquals("Keni", repository.profileName().first { it == "Keni" })
                capture("10-personal-ready")
                tap("preference_continue"); awaitTag("study_browse_body")
                assertTrue(main { root()?.findViewWithTag<View>("study_browse_body")?.isShown == true })
                capture("11-study-discovery")
                scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_account }
                onView(withText("Study reminders & next review")).perform(scrollTo(),click())
                awaitTag("profile_due_queue"); awaitTag("profile_edit_direct")
                assertTrue(main { root()?.findViewWithTag<View>("profile_due_queue")?.isShown == true })
                capture("12-account-up-next")
                scenario.onActivity { it.openDetails(com.studypath.app.ui.FamilySharingFragment()) }
                awaitTag("family_role_all")
                assertTrue(main { root()?.findViewWithTag<View>("family_student_controls")?.isShown == true })
                assertFalse("Only the selected sharing task is shown initially", main { root()?.findViewWithTag<View>("family_recipient_controls")?.isShown == true })
                capture("13-family-share-controls")
                tap("family_role_all")
                assertTrue(main { root()?.findViewWithTag<View>("family_recipient_controls")?.isShown == true })
                capture("13-family-all-controls")
                tap("family_role_family")
                assertFalse(main { root()?.findViewWithTag<View>("family_student_controls")?.isShown == true })
                assertTrue(main { root()?.findViewWithTag<View>("family_recipient_controls")?.isShown == true })
                assertEquals("Collapsed", main { androidx.core.view.ViewCompat.getStateDescription(requireNotNull(root()?.findViewWithTag<View>("family_recipient_id")))?.toString() })
                scenario.recreate(); awaitTag("family_role_family")
                assertTrue(main { root()?.findViewWithTag<View>("family_role_family")?.isSelected == true })
                assertEquals(View.GONE, main { activity()!!.findViewById<View>(R.id.navigation).visibility })
                capture("14-family-recipient-mode")
            }
        }
    }

    private fun packageFixture(): VerifiedTextPackage {
        val json = JsonParser.parseString(instrumentation.context.assets.open("native-learning/lesson.json").bufferedReader().use { it.readText() }).asJsonObject
        @Suppress("UNCHECKED_CAST")
        val fields = Gson().fromJson(json["summary"], Map::class.java) as Map<String, Any?>
        val summary = PublishedModuleCatalog.parse("fixture_v1", "fixture_module", "v1", fields)
        return TextPackageVerifier.verify(summary, json["manifestJson"].asString, json["textJson"].asString)
    }
    private fun finish(module: InteractiveModule, initial: LessonRun): LessonRun {
        var run = initial
        while (!run.completed) {
            val step = requireNotNull(LessonEngine.currentStep(module, run))
            if (step.kind == LessonStepKind.CHECK) {
                val question = module.questions.getValue(requireNotNull(step.questionId))
                val response = when (question.format) {
                    LessonQuestionFormat.SELECTED_RESPONSE, LessonQuestionFormat.ORDERING -> question.answer
                    LessonQuestionFormat.BOUNDED_RESPONSE -> listOf(if (question.responseType == "numeric") question.target.toString() else question.acceptedAnswers.first())
                }
                run = LessonEngine.select(module, run, step.id, response, System.currentTimeMillis())
                run = LessonEngine.check(module, run, step.id, System.currentTimeMillis())
            }
            run = LessonEngine.continueStep(module, run, step.id, System.currentTimeMillis())
        }
        return run
    }
    private fun setGraph(value: StudyPathGraph) {
        val field = StudyPathApplication::class.java.getDeclaredField("graph").apply { isAccessible = true }
        instrumentation.runOnMainSync { field.set(app, value) }
    }
    private fun activity(): MainActivity? = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
    private fun root(): View? = activity()?.window?.decorView
    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) (0 until view.childCount).forEach { addAll(texts(view.getChildAt(it))) }
    }
    private fun text(tag: String): String = main { requireNotNull(root()?.findViewWithTag<TextView>(tag)).text.toString() }
    private fun awaitTag(tag: String) {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) { if (main { root()?.findViewWithTag<View>(tag) != null }) return; SystemClock.sleep(100) }
        assertNotNull("Missing native UI tag $tag", main { root()?.findViewWithTag<View>(tag) })
    }
    private fun tap(tag: String) { onView(withTagValue(equalTo<Any>(tag))).perform(scrollTo(), click()) }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null; instrumentation.runOnMainSync { result = runCatching(block) }; return requireNotNull(result).getOrThrow()
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val target = File(app.getExternalFilesDir(null), "native-slide-revision/$name.png")
        target.parentFile?.mkdirs(); target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
