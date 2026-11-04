package com.studypath.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic public metadata only, in a host-created unique local namespace. No main catalog writes. */
@RunWith(AndroidJUnit4::class)
class PublishedModuleCatalogDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()
    private val namespace get() = InstrumentationRegistry.getArguments().getString("catalogNamespace")!!.also {
        require(it.matches(Regex("demo-studypath-b09-index-[a-f0-9]{8}")))
    }
    // A named app gives the synthetic database its own persistence cache and Auth instance.
    private val firebase by lazy {
        FirebaseApp.initializeApp(app, FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setDatabaseUrl("https://$namespace.firebaseio.com").build(), "B09CatalogDevice")
    }
    private val auth by lazy { FirebaseAuth.getInstance(firebase).apply { useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099) } }
    private val database by lazy {
        FirebaseDatabase.getInstance(firebase).apply {
            useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000)
            setPersistenceEnabled(true)
        }
    }
    private val repository by lazy {
        FirebaseStudyRepository(auth, database, app.getSharedPreferences("b09-catalog-workspace", 0)) { _, _, _ -> }
    }
    private val fixtureFile get() = File(app.filesDir, "b09-catalog-fixture.json")
    private val selection = LearnerPreferences("fixture_level", "fixture_v1", setOf("fixture_subject"), "0", true)
    private val expected = listOf("all_classes:v1", "fixture_module:v2", "fixture_module:v1")
    private fun keys(value: ModuleLibrary) = value.modules.map { "${it.moduleId}:v${it.version}" }
    private fun read() = JSONObject(fixtureFile.readText()).also { assertEquals(namespace, it.getString("namespace")) }
    private fun save(value: JSONObject) { fixtureFile.writeText(value.toString()) }
    private fun observed(value: JSONObject, key: String) { value.getJSONObject("observations").put(key, true); save(value) }

    @Before fun isolatedLocalStageOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith("com.studypath.app.PublishedModuleCatalogDeviceTest#") == true)
        assertTrue(BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        namespace
    }

    @Test fun prepareAndFilter() = runBlocking<Unit> {
        withTimeout(90_000) {
            assertNull(repository.boundUid())
            val email = "index_${UUID.randomUUID().toString().replace("-", "")}@studypath.invalid"
            val user = repository.register(email, PASSWORD, "Synthetic catalog learner")
            val fixture = JSONObject().put("schemaVersion", 1).put("namespace", namespace).put("uid", user.uid).put("email", email).put("observations", JSONObject())
            save(fixture)
            assertEquals(ModuleLibraryStatus.INCOMPLETE_SELECTION, repository.moduleLibrary().first().status)
            observed(fixture, "legacyDemoDoesNotSelectApprovedCatalog")
            repository.saveLearnerPreferences(selection)
            val index = repository.publishedModuleIndex("fixture_v1").first { it.size == 7 }
            assertEquals(setOf("0"), index.first { it.moduleId == "fixture_module" }.classIds)
            assertEquals(expected, keys(repository.moduleLibrary().first { keys(it) == expected }))
            observed(fixture, "immutableVersionsProvenanceAndApplicabilityFiltered")
            val seen = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
            val observer = launch { repository.moduleLibrary().collect { seen += keys(it) } }
            while (expected !in seen) delay(20)
            repository.saveLearnerPreferences(selection.copy(classSectionId = null))
            while (listOf("all_classes:v1") !in seen) delay(20)
            repository.saveLearnerPreferences(LearnerPreferences())
            while (emptyList<String>() !in seen) delay(20)
            assertEquals(ModuleLibraryStatus.INCOMPLETE_SELECTION, repository.moduleLibrary().first().status)
            repository.saveLearnerPreferences(selection)
            repository.moduleLibrary().first { keys(it) == expected }
            observer.cancelAndJoin()
            observed(fixture, "activeLibraryRefiltersWhenPreferencesChange")
            repository.connection().first { it }
            fixture.put("readyForPartition", true); save(fixture)
            repository.connection().first { !it }
            assertEquals(expected, keys(repository.moduleLibrary().first()))
            observed(fixture, "metadataReadableDuringActualPartition")
            fixture.put("stage1Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun recoverCachedIndex() = runBlocking<Unit> {
        withTimeout(60_000) {
            val fixture = read()
            assertEquals(fixture.getString("uid"), repository.resumeSession()?.uid)
            assertNotEquals(fixture.getInt("stage1Pid"), android.os.Process.myPid())
            repository.connection().first { !it }
            assertEquals(selection, repository.learnerPreferences().first())
            assertEquals(expected, keys(repository.moduleLibrary().first()))
            assertEquals(7, repository.publishedModuleIndex("fixture_v1").first().size)
            observed(fixture, "cachedIndexOptionsAndSelectionSurviveProcessDeath")
            fixture.put("stage2Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    @Test fun restoreAndCheckSession() = runBlocking<Unit> {
        withTimeout(90_000) {
            val fixture = read()
            assertEquals(fixture.getString("uid"), repository.resumeSession()?.uid)
            repository.connection().first { it }
            assertEquals(expected, keys(repository.moduleLibrary().first { keys(it) == expected }))
            val count = AtomicInteger(); val closed = AtomicBoolean()
            val observer = launch {
                try { repository.moduleLibrary().collect { count.incrementAndGet() } }
                catch (_: IllegalStateException) { closed.set(true) }
            }
            while (count.get() == 0) delay(20)
            repository.logout()
            while (!closed.get()) delay(20)
            observer.join(); val oldCount = count.get()
            assertTrue(runCatching { repository.moduleLibrary().first() }.isFailure)
            assertTrue(runCatching { repository.publishedModuleIndex("fixture_v1").first() }.isFailure)
            observed(fixture, "catalogObserversCloseAndSignedOutReadsFail")
            assertEquals(fixture.getString("uid"), repository.login(fixture.getString("email"), PASSWORD).uid)
            assertEquals(expected, keys(repository.moduleLibrary().first { keys(it) == expected }))
            delay(200); assertEquals(oldCount, count.get())
            observed(fixture, "sameUidRestoresCatalogWithoutRevivingOldObserver")
            fixture.put("stage3Pid", android.os.Process.myPid()); save(fixture)
        }
    }

    companion object { private const val PASSWORD = "SyntheticIndexOnly123!" }
}
