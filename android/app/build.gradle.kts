import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val firebaseProperties = Properties().apply {
    val localFile = rootProject.file(providers.gradleProperty("firebaseConfigFile").orNull ?: "firebase.properties")
    if (providers.gradleProperty("firebaseConfigFile").isPresent) require(localFile.isFile) { "The selected Firebase configuration file is missing." }
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
fun setting(name: String, default: String): String =
    providers.gradleProperty(name).orNull ?: firebaseProperties.getProperty(name, default)
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val useEmulators = setting("firebaseUseEmulators", "true").toBooleanStrict()
val firebaseProject = setting("firebaseProjectId", "demo-studypath")
val firebaseApiKey = setting("firebaseApiKey", "AIzaSyDemoStudyPathNotARealAPIKey000000")
val firebaseAppId = setting("firebaseAppId", "1:1234567890:android:0123456789abcdef")
val firebaseDatabaseUrl = setting("firebaseDatabaseUrl", "https://demo-studypath-default-rtdb.firebaseio.com")
val mockAssetMirrorUrl = setting("mockAssetMirrorUrl", "")
require(useEmulators || mockAssetMirrorUrl.isEmpty()) { "Mock asset mirrors are local-emulator development only." }
if (!useEmulators) {
    require(!firebaseProject.startsWith("demo-") && !firebaseApiKey.contains("NotAReal")) {
        "Configure a real Firebase Android app in firebase.properties before disabling emulators."
    }
}
// Release signing reads the ignored keystore.properties written by create-release-keystore.sh.
// Its SHA-1 is what the Firebase API-key Android restriction and App Check must be registered with.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val releaseSigningConfigured = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.studypath.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.studypath.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.1.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "USE_FIREBASE_EMULATORS", useEmulators.toString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(firebaseProject))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(firebaseApiKey))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(firebaseAppId))
        buildConfigField("String", "FIREBASE_DATABASE_URL", quoted(firebaseDatabaseUrl))
        buildConfigField("String", "MOCK_ASSET_MIRROR_URL", quoted(mockAssetMirrorUrl))
        buildConfigField("String", "FIREBASE_EMULATOR_HOST", quoted(setting("firebaseEmulatorHost", "10.0.2.2")))
        buildConfigField("String", "API_USER_AGENT", quoted(setting("studyPathUserAgent", "StudyPathCoursePrototype/0.1 (https://github.com/Keni-Dev)")))
    }

    signingConfigs {
        if (releaseSigningConfigured) create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            buildConfigField("String", "MOCK_ASSET_MIRROR_URL", quoted(""))
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets {
        getByName("test").resources.srcDir(rootProject.file("test-fixtures/catalog"))
        getByName("androidTest").assets.srcDir(rootProject.file("test-fixtures/catalog"))
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
