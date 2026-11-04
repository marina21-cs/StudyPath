package com.studypath.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.MediaWikiClient
import com.studypath.app.data.TextPackageStore
import com.studypath.app.data.LessonRunStore
import com.studypath.app.data.ReadingStateStore
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.studypath.app.data.ReadingContextNetwork
import com.studypath.app.data.PackageAssetDownloader
import com.studypath.app.data.MockPackageAssets
import com.studypath.app.data.FamilySharingClient
import com.studypath.app.data.FamilySharingNetwork
import com.studypath.app.ui.ReminderScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StudyPathApplication : Application() {
    lateinit var graph: StudyPathGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val firebase = FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL)
            .build())
        val auth = FirebaseAuth.getInstance(firebase)
        val database = FirebaseDatabase.getInstance(firebase)
        if (BuildConfig.USE_FIREBASE_EMULATORS) {
            auth.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9099)
            database.useEmulator(BuildConfig.FIREBASE_EMULATOR_HOST, 9000)
        }
        database.setPersistenceEnabled(true)
        database.goOffline()
        val restBase = (if (BuildConfig.USE_FIREBASE_EMULATORS)
            "http://${BuildConfig.FIREBASE_EMULATOR_HOST}:9000/?ns=${java.net.URI(BuildConfig.FIREBASE_DATABASE_URL).host.substringBefore('.')}"
            else BuildConfig.FIREBASE_DATABASE_URL).toHttpUrl()
        graph = StudyPathGraph(
            FirebaseStudyRepository(auth, database, getSharedPreferences("workspace", MODE_PRIVATE),
                TextPackageStore(java.io.File(filesDir, "offline-text-packages")),
                LessonRunStore(java.io.File(filesDir, "lesson-runs")),
                ReadingStateStore(java.io.File(filesDir, "reading-state")),
                ReadingContextNetwork(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build(),
                    restBase),
                packageAssetDownloader = if (BuildConfig.MOCK_ASSET_MIRROR_URL.isEmpty()) PackageAssetDownloader(OkHttpClient(), BuildConfig.API_USER_AGENT)
                    else MockPackageAssets.downloader("https://mock-assets.example.org/".toHttpUrl(), BuildConfig.MOCK_ASSET_MIRROR_URL.toHttpUrl()),
                packageDownloadDirectory = java.io.File(filesDir, "package-download-staging"),
                operationDirectory = java.io.File(filesDir, "operation-recovery")) { uid, id, value ->
                ReminderScheduler.update(this, uid, id, value)
            },
            MediaWikiClient(OkHttpClient.Builder()
                .cache(Cache(java.io.File(cacheDir, "wikipedia-http"), 8L * 1024 * 1024))
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build(), BuildConfig.API_USER_AGENT),
        )
        graph = StudyPathGraph(graph.repository, graph.readings, FamilySharingClient(
            FamilySharingNetwork(OkHttpClient(), restBase, BuildConfig.USE_FIREBASE_EMULATORS),
            graph.repository::familyCredentials, allowSynthetic = BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATORS))
    }
}

class StudyPathGraph(
    val repository: FirebaseStudyRepository,
    val readings: MediaWikiClient,
    val family: FamilySharingClient? = null,
)
