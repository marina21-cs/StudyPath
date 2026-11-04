package com.studypath.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.studypath.app.data.*
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.OfflinePdfView
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Native parser/private-file component tests. No real approval, network transfer or managed ack is claimed. */
@RunWith(AndroidJUnit4::class)
class CompletePackageAssetsDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    @Before fun explicitOwnedTestRunOnly() {
        assumeTrue("Select this isolated package component class explicitly on the Test AVD",
            InstrumentationRegistry.getArguments().getString("class")?.startsWith("com.studypath.app.CompletePackageAssetsDeviceTest") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS)
    }

    @Test fun nativePdfParserRejectsBrokenBytesAndRetainedAssetsRevalidateAfterStoreRecreation() {
        val directory = File(app.cacheDir, "b10-native-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val source = generatedPdf(directory)
            NativePdfAssetValidator.validate(source)
            val broken = File(directory, "broken.pdf").apply { writeText("%PDF-1.4\nnot a usable PDF\n%%EOF\n") }
            assertTrue(runCatching { NativePdfAssetValidator.validate(broken) }.isFailure)
            val root = File(directory, "retained")
            val verified = fixture(source)
            val store = TextPackageStore(root)
            assertTrue(runCatching { store.stage("synthetic_uid", verified) }.isFailure)
            val pending = store.stage("synthetic_uid", verified, 1000, mapOf("test_pdf" to source))
            assertFalse(pending.ready); assertNull(store.assetFile("synthetic_uid", verified.key, "test_pdf"))
            val recreated = TextPackageStore(root)
            assertFalse(requireNotNull(recreated.read("synthetic_uid", "fixture_v1", "fixture_module", 1)).ready)
            // Explicit synthetic acknowledgement boundary: this test does not call or certify the server path.
            recreated.confirm("synthetic_uid", verified, 1100)
            val retained = requireNotNull(recreated.assetFile("synthetic_uid", verified.key, "test_pdf"))
            assertArrayEquals(source.readBytes(), retained.readBytes())
            NativePdfAssetValidator.validate(retained)
            assertNull(recreated.assetFile("other_uid", verified.key, "test_pdf"))
            retained.writeText("%PDF-1.4\ncorrupt\n%%EOF\n")
            assertNull(TextPackageStore(root).read("synthetic_uid", "fixture_v1", "fixture_module", 1))
            assertNull(recreated.assetFile("synthetic_uid", verified.key, "test_pdf"))
        } finally { check(directory.deleteRecursively()) }
    }

    @Test fun nativePdfViewerPaginatesRestoresPresentedPageAndClampsStalePagePositions() {
        assertNotNull("Run after the owned Test AVD baseline has signed in; do not bind or reset an account here.",
            runBlocking { withTimeout(20_000) { app.graph.repository.resumeSession() } })
        val name = "b10-pdf-ui-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, name).apply { check(mkdir()) }
        val preferences = app.getSharedPreferences(name, 0)
        try {
            val pdf = generatedPdf(directory)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun await(label: String, predicate: (MainActivity) -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var matched = false
                    while (SystemClock.uptimeMillis() < deadline && !matched) {
                        scenario.onActivity { matched = predicate(it) }
                        if (!matched) SystemClock.sleep(50)
                    }
                    assertTrue(label, matched)
                }
                fun show(initialPage: Int = preferences.getInt("page", 0)) {
                    await("Main content must finish opening", { it.findViewById<android.view.View>(R.id.main_content) != null })
                    scenario.onActivity { activity ->
                        val view = OfflinePdfView(activity, activity)
                        activity.setContentView(view)
                        view.open(pdf, "Synthetic PDF", initialPage) { page -> preferences.edit().putInt("page", page).commit() }
                    }
                }
                fun page(number: Int) = await("Native PDF page $number must render") {
                    it.findViewById<android.view.View>(android.R.id.content)?.findViewWithTag<TextView>("offline_pdf_status")?.text == "Page $number of 2 · Available offline"
                }
                show(); page(1)
                onView(withTagValue(`is`("offline_pdf_previous" as Any))).check(matches(not(isEnabled())))
                onView(withTagValue(`is`("offline_pdf_next" as Any))).perform(click()); page(2)
                assertEquals(1, preferences.getInt("page", -1))
                onView(withTagValue(`is`("offline_pdf_next" as Any))).check(matches(not(isEnabled())))
                scenario.recreate(); show(); page(2)
                onView(withTagValue(`is`("offline_pdf_previous" as Any))).perform(click()); page(1)
                scenario.recreate(); show(999); page(2)
                assertEquals(1, preferences.getInt("page", -1))
            }
        } finally { app.deleteSharedPreferences(name); check(directory.deleteRecursively()) }
    }

    private fun generatedPdf(directory: File): File = File(directory, "fixture.pdf").also { output ->
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, 320, index + 1).create())
                page.canvas.drawText("Synthetic test page ${index + 1}", 20f, 100f, Paint().apply { textSize = 12f })
                pdf.finishPage(page)
            }
            output.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }

    private fun fixture(pdf: File): VerifiedTextPackage {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun resource(name: String) = assets.open(name).bufferedReader().use { it.readText() }
        val text = resource("text-package-content.json")
        val manifest = JsonParser.parseString(resource("text-package-manifest.json")).asJsonObject
        manifest.add("assetPolicy", JsonObject().apply {
            addProperty("reference", "SYNTHETIC DEVICE TEST POLICY"); addProperty("approvalReference", "TEST ONLY")
            addProperty("reviewer", "TEST ONLY"); addProperty("reviewedOn", "2026-10-30"); addProperty("access", "PUBLIC_PREPUBLISHED")
            add("origins", JsonArray().apply { add("https://assets.example.org") })
            addProperty("maxCount", 1); addProperty("maxFileBytes", 1_048_576); addProperty("maxTotalBytes", 1_048_576)
            add("mimeTypes", JsonArray().apply { add("application/pdf") })
        })
        manifest.add("assets", JsonArray().apply { add(JsonObject().apply {
            addProperty("id", "test_pdf"); addProperty("file", "source.pdf"); addProperty("kind", "PDF")
            addProperty("bytes", pdf.length()); addProperty("sha256", sha(pdf.readBytes())); addProperty("url", "https://assets.example.org/source.pdf")
        }) })
        val rawManifest = manifest.toString()
        val hashInput = JsonObject().apply {
            addProperty("manifestSha256", sha(rawManifest.toByteArray())); addProperty("textSha256", sha(text.toByteArray()))
            add("assets", JsonArray().apply { add(JsonObject().apply {
                addProperty("id", "test_pdf"); addProperty("sha256", sha(pdf.readBytes())); addProperty("bytes", pdf.length())
            }) })
        }.toString()
        @Suppress("UNCHECKED_CAST")
        val fields = Gson().fromJson(resource("text-package-summary.json"), Map::class.java) as Map<String, Any?>
        val summary = PublishedModuleCatalog.parse("fixture_v1", "fixture_module", "v1", fields).copy(
            textBytes = rawManifest.toByteArray().size.toLong() + text.toByteArray().size,
            assetBytes = pdf.length(), packageSha256 = sha(hashInput.toByteArray()))
        return TextPackageVerifier.verify(summary, rawManifest, text)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
