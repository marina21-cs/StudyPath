package com.studypath.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.TextPackageKey
import com.studypath.app.ui.MainActivity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.hamcrest.Matchers.`is`
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** UI wiring proof through the actual application's default graph and guarded BuildConfig mirror. */
@RunWith(AndroidJUnit4::class)
class MockDemoUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val repository get() = app.graph.repository
    private val namespace get() = requireNotNull(InstrumentationRegistry.getArguments().getString("mockNamespace"))
    private val marker get() = File(app.filesDir, "mock-demo-ui-device.json")
    private var scenario: ActivityScenario<MainActivity>? = null
    private val key = TextPackageKey("mock_computing_v1", "mock_programming", 1)

    @Before fun freshDefaultMockBuildOnly() {
        assertTrue(BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATORS)
        assertEquals("demo-studypath", BuildConfig.FIREBASE_PROJECT_ID)
        assertTrue(namespace.matches(Regex("demo-studypath-mock-[a-f0-9]{8}")))
        assertEquals("https://$namespace.firebaseio.com", BuildConfig.FIREBASE_DATABASE_URL)
        assertEquals(BuildConfig.FIREBASE_DATABASE_URL, FirebaseApp.getInstance().options.databaseUrl)
        val mirror = BuildConfig.MOCK_ASSET_MIRROR_URL.toHttpUrl()
        assertEquals("http", mirror.scheme); assertEquals(BuildConfig.FIREBASE_EMULATOR_HOST, mirror.host)
        assertEquals(8765, mirror.port); assertEquals("/", mirror.encodedPath)
        assertNull(mirror.query); assertNull(mirror.fragment); assertEquals("", mirror.username); assertEquals("", mirror.password)
        assertTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.MockDemoUiDeviceTest#") == true)
        assertFalse("Use a fresh Test AVD; do not overwrite previous UI evidence", marker.exists())
        assertNull("Never clear or replace a bound normal workspace", repository.boundUid())
        assertNull(FirebaseAuth.getInstance().currentUser)
    }

    @Test fun defaultGraphDownloadsAndOpensLessonAndPdfThroughUi() = runBlocking<Unit> {
        val originalGraph = app.graph
        val email = "mock_demo_ui_${UUID.randomUUID()}@studypath.invalid"
        val receipt = JSONObject().put("schemaVersion", 1).put("status", "RUNNING")
            .put("namespace", namespace).put("email", email).put("syntheticOnly", true)
            .put("defaultApplicationGraph", true).put("cloudVerified", false).put("acceptancePromoted", false)
            .put("observations", JSONObject()).put("cleanup", JSONObject())
        save(receipt)
        var ownedUid: String? = null
        var failure: Throwable? = null
        try {
            withTimeout(180_000) {
                val user = repository.register(email, "Mock-ui-local-only-42", "MOCK default graph learner")
                ownedUid = user.uid; receipt.put("uid", user.uid); save(receipt)
                repository.connection().first { it }
                repository.saveLearnerPreferences(LearnerPreferences("mock_college", "mock_computing_v1", setOf("computing"), null, true))
                val summary = repository.moduleLibrary().first { it.modules.size == 3 }.modules.single { it.moduleId == key.moduleId }
                assertTrue(summary.title.startsWith("MOCK")); assertTrue(summary.assetBytes > 0)
                assertNull("The test must exercise a new UI download", repository.openTextPackage(key))
                assertTrue(repository.retainedTextPackages().isEmpty())
                observed(receipt, "defaultRepositoryRegistersAndAcknowledgesMockPreferences")

                ActivityScenario.launch(MainActivity::class.java).use { current ->
                    scenario = current
                    await("Default MainActivity and Study navigation") { hasId(R.id.nav_study) }
                    onView(withId(R.id.nav_study)).perform(click())
                    await("Published mock module's actual download control") { hasTag("package_download_mock_programming_v1") }
                    tagged("package_download_mock_programming_v1").perform(scrollTo()).check(matches(isDisplayed()))
                    assertEquals("Not downloaded", text("package_status_mock_programming_v1"))
                    capture("01-library-before-download")
                    tagged("package_download_mock_programming_v1").perform(click())
                    await("UI download must reach acknowledged ready state", 120_000) {
                        text("package_status_mock_programming_v1").startsWith("Ready offline") && hasTag("package_open_mock_programming_v1")
                    }
                    tagged("package_status_mock_programming_v1").perform(scrollTo()).check(matches(isDisplayed()))
                    val retained = requireNotNull(repository.openTextPackage(key))
                    assertTrue(retained.ready); assertNotNull(retained.confirmedAt)
                    assertEquals(summary.packageSha256, retained.verified.summary.packageSha256)
                    val asset = retained.verified.assets.single { it.id == "companion_pdf" }
                    val file = requireNotNull(repository.openPackageAsset(key, asset.id))
                    assertEquals(asset.bytes, file.length()); assertEquals(asset.sha256, sha(file.readBytes()))
                    receipt.put("packageSha256", summary.packageSha256).put("pdfSha256", asset.sha256)
                        .put("confirmedAt", retained.confirmedAt).put("packageStatus", text("package_status_mock_programming_v1"))
                    capture("02-library-confirmed-ready")
                    observed(receipt, "libraryUiDownloadUsesDefaultMirrorAndAcknowledgedCompletePackage")

                    tagged("package_open_mock_programming_v1").perform(scrollTo(), click())
                    await("Actual downloaded lesson path") { hasTag("lesson_path") && hasTag("lesson_start_lesson_1") }
                    tagged("lesson_start_lesson_1").perform(scrollTo()).check(matches(isDisplayed()))
                    capture("03-actual-lesson-path")
                    tagged("lesson_start_lesson_1").perform(click())
                    await("Actual authored first step") { hasTag("lesson_step") && text("lesson_progress").startsWith("Step 1 of ") }
                    onView(withText("Goal and explanation")).perform(scrollTo()).check(matches(isDisplayed()))
                    assertTrue(allText().contains("MOCK ORIGINAL LESSON."))
                    receipt.put("lessonProgress", text("lesson_progress")); capture("04-authored-lesson-step")
                    observed(receipt, "uiOpensAndStartsActualRetainedLessonUsingDefaultGraph")

                    pressBack()
                    await("Back returns to actual navigation after preserving draft") { hasId(R.id.nav_saved) }
                    onView(withId(R.id.nav_saved)).perform(click())
                    await("Saved screen exposes the retained companion PDF") { hasTag("package_pdf_companion_pdf") }
                    tagged("package_pdf_companion_pdf").perform(scrollTo()).check(matches(isDisplayed()))
                    capture("05-saved-companion-control")
                    tagged("package_pdf_companion_pdf").perform(click())
                    await("Actual PDF view presents a rendered local page") {
                        text("offline_pdf_status") == "Page 1 of 1 · Available offline" && hasRenderedPdf()
                    }
                    tagged("offline_pdf_status").check(matches(isDisplayed()))
                    receipt.put("pdfStatus", text("offline_pdf_status")); capture("06-default-native-pdf-view")
                    observed(receipt, "savedUiOpensCompanionInActualNativePdfView")
                    assertSame("The application graph was never replaced", originalGraph, app.graph)
                    assertSame(originalGraph.repository, repository)
                    observed(receipt, "defaultApplicationGraphIdentityPreservedThroughEntireJourney")
                }
                scenario = null
            }
        } catch (error: Throwable) {
            failure = error
            receipt.put("failure", JSONObject().put("type", error.javaClass.name).put("message", error.message ?: ""))
            runCatching { receipt.put("failureVisibleText", allText()); capture("failure") }
        } finally {
            scenario = null
            val cleanup = runCatching {
                withContext(NonCancellable) {
                    withTimeout(20_000) {
                        val current = FirebaseAuth.getInstance().currentUser
                        if (current != null) {
                            assertEquals("Only the test's exact synthetic identity may be deleted", email, current.email)
                            if (ownedUid != null) assertEquals(ownedUid, current.uid)
                            else { ownedUid = current.uid; receipt.put("uid", current.uid) }
                            current.delete().await()
                            assertNull(FirebaseAuth.getInstance().currentUser)
                            receipt.getJSONObject("cleanup").put("ownedAuthDeleted", true)
                        } else {
                            assertNull("An owned account disappeared before deletion could be confirmed; host must inspect the exact UID", ownedUid)
                            receipt.getJSONObject("cleanup").put("noCurrentAuthIdentity", true)
                                .put("registrationUnconfirmed", true).put("hostCleanupRequired", true)
                        }
                    }
                }
            }
            repository.logout()
            receipt.getJSONObject("cleanup").put("repositorySignedOut", repository.currentSession() == null)
                .put("workspaceBindingPreserved", repository.boundUid() == ownedUid)
            cleanup.exceptionOrNull()?.let { error ->
                receipt.getJSONObject("cleanup").put("failure", error.javaClass.name).put("hostCleanupRequired", true)
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
            receipt.put("status", if (failure == null) "PASSED" else "FAILED"); save(receipt)
        }
        failure?.let { throw it }
    }

    private fun tagged(tag: String) = onView(withTagValue(`is`(tag as Any)))
    private fun withRoot(block: (View) -> Unit) {
        scenario?.onActivity { activity -> activity.findViewById<View>(android.R.id.content)?.let(block) }
    }
    private fun hasId(id: Int): Boolean { var found = false; withRoot { found = it.findViewById<View>(id) != null }; return found }
    private fun hasTag(tag: String): Boolean { var found = false; withRoot { found = it.findViewWithTag<View>(tag) != null }; return found }
    private fun text(tag: String): String { var value = ""; withRoot { value = it.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty() }; return value }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
    private fun allText(): String { var value = ""; withRoot { value = descendants(it).filterIsInstance<TextView>().joinToString("\n") { view -> view.text.toString() } }; return value }
    private fun hasRenderedPdf(): Boolean {
        var rendered = false
        withRoot { root -> rendered = descendants(root).filterIsInstance<ImageView>().any {
            it.contentDescription?.toString() == "companion_pdf · Version 1, PDF page 1 of 1" && it.drawable != null
        } }; return rendered
    }
    private fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!predicate()) { assertTrue("$label. Current UI: ${allText()}", SystemClock.uptimeMillis() < end); SystemClock.sleep(75) }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.filesDir, "mock-demo-ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun observed(receipt: JSONObject, name: String) { receipt.getJSONObject("observations").put(name, true); save(receipt) }
    private fun save(receipt: JSONObject) {
        val atomic = AtomicFile(marker); val output = atomic.startWrite()
        try { output.write(receipt.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
