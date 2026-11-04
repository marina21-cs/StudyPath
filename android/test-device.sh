#!/usr/bin/env bash
set -euo pipefail

# Uses only the isolated, disposable test AVD. Never resets or erases app data.
studyPathAndroid="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
studyPathRoot="$(cd -- "$studyPathAndroid/.." && pwd)"
studyPathSerial=""
studyPathLive=false
usage() {
    cat <<'USAGE'
Usage: android/test-device.sh [device-serial] [--live-api]

Build and run StudyPathSmokeTest on the running StudyPath_Lite_Test AVD.
Start that isolated AVD with -read-only and start/seed the local demo-studypath
Firebase Auth (9099) and Realtime Database (9000) emulators first.
The test requires a fresh, unbound installation. This command never clears data,
uninstalls packages, or restarts an AVD. After a smoke run, close and relaunch the
disposable read-only AVD for the next run; retain a separate AVD for manual work.

--live-api additionally tests the live Wikipedia reading/save/reopen flow.
Overrides: STUDYPATH_ADB, STUDYPATH_GRADLE (executable paths).
Evidence: implementation/phases/03-validation/lite-emulator/test-results/latest/
USAGE
}
while [[ $# -gt 0 ]]; do
    case "$1" in
        --live-api) studyPathLive=true ;;
        --help|-h) usage; exit 0 ;;
        -*) usage >&2; exit 2 ;;
        *)
            if [[ -n "$studyPathSerial" ]]; then usage >&2; exit 2; fi
            studyPathSerial="$1"
            ;;
    esac
    shift
done

studyPathAdb="${STUDYPATH_ADB:-adb}"
if ! command -v "$studyPathAdb" >/dev/null 2>&1; then
    studyPathSdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    studyPathAdb="$studyPathSdk/platform-tools/adb"
fi
if ! command -v "$studyPathAdb" >/dev/null 2>&1; then
    echo "Set STUDYPATH_ADB to the Android SDK adb executable." >&2
    exit 1
fi
studyPathGradle="${STUDYPATH_GRADLE:-$studyPathAndroid/gradlew}"
if ! command -v "$studyPathGradle" >/dev/null 2>&1; then
    echo "Set STUDYPATH_GRADLE to a Gradle executable or restore android/gradlew." >&2
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "Python 3 is required for the local service check and evidence receipt." >&2
    exit 1
fi

studyPathResults="$studyPathRoot/implementation/phases/03-validation/lite-emulator/test-results"
mkdir -p "$studyPathResults"
studyPathLock="$studyPathResults/.runner-lock"
if ! mkdir "$studyPathLock" 2>/dev/null; then
    echo "Another device smoke run holds $studyPathLock. Do not run device builds concurrently." >&2
    exit 1
fi
studyPathStarted="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
studyPathStamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
studyPathOut="$studyPathResults/latest"
if [[ -e "$studyPathOut" ]]; then
    if ! mv "$studyPathOut" "$studyPathResults/run-$studyPathStamp"; then
        rmdir "$studyPathLock"
        exit 1
    fi
fi
mkdir -p "$studyPathOut/screenshots"
studyPathStatus=FAILED
studyPathStage=preflight
studyPathAvd=""
studyPathApkSha=""
studyPathJUnit=false
studyPathDevice=()
finish() {
    local studyPathExit=$?
    trap - EXIT
    python3 - "$studyPathOut/verification.json" "$studyPathStarted" "$studyPathStatus" \
        "$studyPathExit" "$studyPathStage" "$studyPathSerial" "$studyPathAvd" \
        "$studyPathLive" "$studyPathJUnit" "$studyPathApkSha" <<'PY'
import datetime, json, pathlib, sys
path, started, status, code, stage, serial, avd, live, junit, sha = sys.argv[1:]
output = pathlib.Path(path)
result = {
    "startedAtUtc": started,
    "finishedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "status": status,
    "exitCode": int(code),
    "lastStage": stage,
    "deviceSerial": serial,
    "avd": avd,
    "firebase": {"projectId": "demo-studypath", "localOnly": True, "authPort": 9099, "databasePort": 9000},
    "liveApi": live == "true",
    "explicitJUnitSuccess": junit == "true",
    "apkSha256": sha or None,
    "screenshots": sorted(p.name for p in (output.parent / "screenshots").glob("*.png")),
    "dataCleared": False,
    "packagesUninstalled": False,
    "limits": [
        "Core smoke flow only; --live-api adds Wikipedia reading/save/reopen checks.",
        "This is not acceptance of notifications, accessibility, extended offline recovery, or cloud setup.",
        "Screenshots are diagnostic captures; verify visible content before assigning detailed captions."
    ]
}
output.write_text(json.dumps(result, indent=2) + "\n")
PY
    rmdir "$studyPathLock"
    echo "Evidence: $studyPathOut"
    exit "$studyPathExit"
}
trap finish EXIT
fail() { echo "$*" >&2; exit 1; }
adb_device() { "$studyPathAdb" "${studyPathDevice[@]}" "$@"; }
if [[ -z "$studyPathSerial" ]]; then
    studyPathSerial="$("$studyPathAdb" get-serialno)"
fi
[[ -n "$studyPathSerial" && "$studyPathSerial" != unknown ]] || fail "Choose one running StudyPath_Lite_Test device serial."
studyPathDevice=(-s "$studyPathSerial")
[[ "$(adb_device get-state)" == device ]] || fail "The selected device is not online."
studyPathAvd="$(adb_device emu avd name | tr -d '\r' | sed '/^OK$/d; /^$/d')"
[[ "$studyPathAvd" == StudyPath_Lite_Test ]] || fail "Refusing AVD '$studyPathAvd'. Use only the dedicated StudyPath_Lite_Test launched with -read-only."
[[ "$(adb_device shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || fail "The test AVD has not finished booting."

# Local administrative catalog read only; no credentials, accounts or tokens are logged.
python3 - <<'PY'
import json, socket, sys, urllib.error, urllib.request
try:
    for port in (9099, 9000):
        with socket.create_connection(("127.0.0.1", port), timeout=5):
            pass
    request = urllib.request.Request(
        "http://127.0.0.1:9000/catalog.json?ns=demo-studypath-default-rtdb",
        headers={"Authorization": "Bearer owner"})
    with urllib.request.urlopen(request, timeout=10) as response:
        catalog = json.load(response)
    if not isinstance(catalog, dict) or catalog.get("curriculumVersion") != "bsit_demo_v1" or not catalog.get("modules"):
        raise ValueError("demonstration catalog is missing")
except (OSError, ValueError, urllib.error.URLError) as error:
    print("Start and seed the local demo-studypath Auth/RTDB emulators before running this test.", file=sys.stderr)
    print(f"Local preflight: {type(error).__name__}", file=sys.stderr)
    sys.exit(1)
print("Local Firebase services and demonstration catalog are available.")
PY

check_fresh_install() {
    # Refuse a bound workspace; Android/Firebase framework preferences are harmless.
    # Check only for the binding key, without printing its UID, tokens or queued records.
    if adb_device shell pm path com.studypath.app | tr -d '\r' | grep -q '^package:'; then
        if ! adb_device shell run-as com.studypath.app pwd >/dev/null; then
            fail "Cannot inspect the debug installation safely. Use a fresh disposable test AVD."
        fi
        if ! adb_device shell run-as com.studypath.app sh -c \
            "'if [ -f shared_prefs/workspace.xml ]; then grep -q boundUid shared_prefs/workspace.xml; case \$? in 1) exit 0;; *) exit 17;; esac; fi'"; then
            fail "The test installation has a bound workspace or its binding could not be checked safely. Close and relaunch the disposable read-only test AVD; no data was cleared."
        fi
    fi
}
check_fresh_install

studyPathStage=build
echo "Building local debug and test APKs with at most two Gradle workers."
(
    cd "$studyPathAndroid"
    "$studyPathGradle" --console=plain --no-daemon --max-workers=2 --no-parallel \
        -PfirebaseUseEmulators=true -PfirebaseProjectId=demo-studypath \
        -PfirebaseEmulatorHost=10.0.2.2 \
        -PfirebaseApiKey=AIzaSyDemoStudyPathNotARealAPIKey000000 \
        -PfirebaseAppId=1:1234567890:android:0123456789abcdef \
        -PfirebaseDatabaseUrl=https://demo-studypath-default-rtdb.firebaseio.com \
        :app:assembleDebug :app:assembleDebugAndroidTest
) >"$studyPathOut/build.log" 2>&1 || fail "APK build failed. Read $studyPathOut/build.log."
studyPathApk="$studyPathAndroid/app/build/outputs/apk/debug/app-debug.apk"
studyPathTestApk="$studyPathAndroid/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$studyPathApk" && -f "$studyPathTestApk" ]] || fail "Gradle did not produce both expected APK files."
studyPathApkSha="$(python3 - "$studyPathApk" <<'PY'
import hashlib, pathlib, sys
print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"

studyPathStage=install
(
    adb_device reverse tcp:9099 tcp:9099 || exit 1
    adb_device reverse tcp:9000 tcp:9000 || exit 1
    adb_device install -r "$studyPathApk" || exit 1
    adb_device install -r "$studyPathTestApk" || exit 1
) >"$studyPathOut/install.log" 2>&1 || fail "APK install/routing failed. Read $studyPathOut/install.log."
check_fresh_install

studyPathStage=instrumentation
studyPathTestArgs=(-e class com.studypath.app.StudyPathSmokeTest)
if [[ "$studyPathLive" == true ]]; then studyPathTestArgs+=(-e verifyLiveReading true); fi
echo "Running StudyPathSmokeTest on $studyPathSerial (live API: $studyPathLive)."
studyPathInstrumentExit=0
adb_device shell am instrument -w -r "${studyPathTestArgs[@]}" \
    com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner \
    >"$studyPathOut/instrumentation.log" 2>&1 || studyPathInstrumentExit=$?
# am instrument can return zero even when JUnit fails. Require explicit OK and no failure markers.
if [[ "$studyPathInstrumentExit" -eq 0 ]] && \
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$studyPathOut/instrumentation.log" && \
    ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]' "$studyPathOut/instrumentation.log"; then
    studyPathJUnit=true
fi

# Pull only named synthetic smoke captures, never arbitrary screenshots or private storage.
studyPathStage=screenshots
studyPathScreens=(00-welcome 01-register-empty 02-study-home 03-study-shelf 04-planner 04b-planner-policy-preferences 05-account 06-login-bound-workspace)
if [[ "$studyPathLive" == true ]]; then studyPathScreens+=(07-live-reading 08-reading-attribution 09-saved-reading); fi
studyPathScreensComplete=true
for studyPathScreen in "${studyPathScreens[@]}"; do
    studyPathRemote="/sdcard/Pictures/StudyPathSmoke/$studyPathScreen.png"
    if adb_device shell test -f "$studyPathRemote"; then
        adb_device pull "$studyPathRemote" "$studyPathOut/screenshots/$studyPathScreen.png" \
            >>"$studyPathOut/screenshots.log" 2>&1 || studyPathScreensComplete=false
    else
        studyPathScreensComplete=false
    fi
done
[[ "$studyPathJUnit" == true ]] || fail "Device smoke test failed or omitted explicit JUnit success. Read $studyPathOut/instrumentation.log."
[[ "$studyPathScreensComplete" == true ]] || fail "JUnit passed, but expected screenshot evidence is incomplete. Read $studyPathOut/screenshots.log."
studyPathStatus=PASSED
studyPathStage=complete
echo "StudyPath device smoke passed with explicit JUnit success. App data and queued writes were retained."
