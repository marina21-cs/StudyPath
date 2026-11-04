# StudyPath Android

StudyPath is a native Android app built with Kotlin and XML views. It uses Firebase email/password Authentication, Firebase Realtime Database, local package storage, and WorkManager reminders. The `ui`, `data`, and `domain` packages separate screens, persistence/network access, and academic calculations.

For the downloadable **0.1.2 cloud prerelease**, see [StudyPath Releases](https://github.com/marina21-cs/StudyPath/releases). That APK is signed with a debug certificate for testing. The hosted lesson catalog is currently empty; approved lessons and academic policies are required to enable their dependent features.

## Build from source

Install Android Studio, Android SDK 36, and a JDK 17 or later compatible with Gradle 8.13. The project uses Android Gradle Plugin 8.11.0 and Kotlin 2.1.20. It targets Android 16 / API 36 and supports Android 8.0 / API 26 or later.

Open the `android/` directory in Android Studio, allow Gradle synchronization, and set the SDK location through Android Studio or an ignored `local.properties`. From this directory:

```sh
./gradlew --max-workers=2 --no-parallel :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On Windows, use `gradlew.bat`. Keep builds to two workers and run device builds serially.

## Run with local Firebase

Start and seed the local services using the [backend guide](../backend/README.md). A default source build uses the synthetic `demo-studypath` project, Auth port 9099, and Database port 9000 through `10.0.2.2`. It cannot connect to a real cloud project with the default configuration.

Start an Android emulator, build the debug APK, then run:

```sh
./run-local.sh emulator-5554
```

Replace the serial with the output of `adb devices`. The script installs with `-r`, opens Welcome, and sets up both ADB reverse routes. These routes also support Database reconnects that advertise loopback. It retains app data. Add Android SDK `platform-tools` to `PATH`, or set `STUDYPATH_ADB` to the `adb` executable.

For a physical phone connected by USB, copy `firebase.properties.example` to the ignored `firebase.properties`, set `firebaseEmulatorHost=127.0.0.1`, and rebuild. Then use:

```sh
STUDYPATH_ALLOW_PHYSICAL=1 ./run-local.sh YOUR_DEVICE_SERIAL
```

Use that override only after building for loopback. An APK configured for `10.0.2.2` cannot reach your laptop's services from a physical phone. Local services remain bound to `127.0.0.1`.

## Firebase and API configuration

[`firebase.properties.example`](firebase.properties.example) lists the supported client properties. Copy it to `firebase.properties` for your own environment. Gradle also accepts these settings as `-P` properties, or an explicit configuration file through `-PfirebaseConfigFile=/absolute/path/to/config.properties`.

For your own Firebase project, register the Android application ID `com.studypath.app`, enable email/password Authentication, provision Realtime Database, and provide `firebaseProjectId`, `firebaseApiKey`, `firebaseAppId`, and `firebaseDatabaseUrl`. Set `firebaseUseEmulators=false` only after supplying real client configuration. Review and test the [database rules](../backend/database.rules.json) separately before deployment.

Set a maintained API contact before distributing your build, for example:

```properties
studyPathUserAgent=StudyPath/0.1.2 (https://github.com/marina21-cs/StudyPath)
```

Firebase environment files, `google-services.json`, Admin credentials, `keystore.properties`, and signing keys are excluded from Git. The app reads Firebase configuration directly from Gradle-generated fields; it does not require the Google Services plugin. Admin credentials must never be included in an APK.

Debug builds are signed automatically. For your own release builds, [`create-release-keystore.sh`](create-release-keystore.sh) creates an ignored keystore and signing properties. Back up the key and password outside this repository: future updates require the same signing identity. A release build is not proof of cloud readiness.

## Light emulator and device checks

The included launcher requires Python 3, hardware virtualization, Android SDK emulator/platform tools, and the installed **API 36 Google Play x86_64** system image. It does not download SDK packages. Set `ANDROID_HOME` if your SDK is outside its usual location.

From `android/`, start the persistent preview:

```sh
./start-lite-emulator.sh
```

`StudyPath_Lite` uses 720 × 1280 pixels, two CPU cores, and 2048 MB RAM. Leave the launcher terminal open and build/install from another terminal after boot. Run one AVD at a time. Preserve this preview's bound account, app data, and offline queue; do not clear, uninstall, or overwrite them to make a test pass.

For a fresh registration smoke, close the preview, start the separate read-only test AVD, and keep its terminal open:

```sh
./start-lite-emulator.sh --test
```

With local Firebase running and seeded, use a second terminal for one of these runs:

```sh
./test-device.sh emulator-5554
# Or include the live Wikipedia search/save/reopen flow:
./test-device.sh emulator-5554 --live-api
```

The runner accepts only `StudyPath_Lite_Test` with an unbound installation. Close and restart the read-only test AVD before another fresh registration run. `--headless` and `--port PORT` are launcher options. The runner creates local logs, screenshots, and a JSON receipt beneath `implementation/phases/03-validation/lite-emulator/test-results/`; this output directory need not exist in a fresh clone.

The smoke covers core account/navigation behavior. Check changed features separately, especially reminder delivery, permission denial, offline recovery, and accessibility. For a true offline check, remove both ADB reverse routes as well as disabling networking, then confirm the app reports Offline before restarting it. Restore the routes with `run-local.sh` afterward. Keep cloud validation separate from local tests; a successful build or screenshot does not establish device or production acceptance.

## Source map

- [`app/src/main/java/com/studypath/app/ui`](app/src/main/java/com/studypath/app/ui): native screens and navigation.
- [`app/src/main/java/com/studypath/app/data`](app/src/main/java/com/studypath/app/data): account binding, Firebase records, reading API, download validation, and local queues.
- [`app/src/main/java/com/studypath/app/domain`](app/src/main/java/com/studypath/app/domain): grade, prerequisite, reminder, and synchronization behavior.
- [`app/src/test`](app/src/test): JVM tests; [`app/src/androidTest`](app/src/androidTest): device tests.
- [`app/src/main/assets/licenses`](app/src/main/assets/licenses): bundled font licenses.
