#!/usr/bin/env bash
set -euo pipefail

# Start and seed the local Firebase emulators first; this never clears app data.
studyPathRoot="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
studyPathAdb="${STUDYPATH_ADB:-adb}"
if ! command -v "$studyPathAdb" >/dev/null 2>&1; then
    studyPathSdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    studyPathAdb="$studyPathSdk/platform-tools/adb"
fi
if [[ ! -x "$studyPathAdb" ]] && ! command -v "$studyPathAdb" >/dev/null 2>&1; then
    echo "Add Android SDK platform-tools to PATH or set STUDYPATH_ADB to its adb executable." >&2
    exit 1
fi
studyPathDevice=()
if [[ $# -gt 1 ]]; then
    echo "Usage: ./run-local.sh [device-serial]" >&2
    exit 1
fi
if [[ $# -eq 1 ]]; then studyPathDevice=(-s "$1"); fi
studyPathApk="${STUDYPATH_APK:-$studyPathRoot/app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$studyPathApk" ]]; then
    echo "Build the app first: ./gradlew :app:assembleDebug" >&2
    exit 1
fi
# An emulator-mode APK points at 10.0.2.2, which only resolves from an Android emulator. A phone
# needs the cloud candidate (build-cloud-candidate.sh) or a 127.0.0.1 build with adb reverse routes.
studyPathSerial="$("$studyPathAdb" "${studyPathDevice[@]}" get-serialno 2>/dev/null | tr -d '\r' || true)"
studyPathBuildConfig="$studyPathRoot/app/build/generated/source/buildConfig/debug/com/studypath/app/BuildConfig.java"
if [[ "$studyPathSerial" != emulator-* && "${STUDYPATH_ALLOW_PHYSICAL:-0}" != 1 ]]; then
    if [[ ! -f "$studyPathBuildConfig" ]] || { grep -q 'USE_FIREBASE_EMULATORS = true' "$studyPathBuildConfig" &&
        grep -q 'FIREBASE_EMULATOR_HOST = "10.0.2.2"' "$studyPathBuildConfig"; }; then
        echo "Refusing to install on '${studyPathSerial:-unknown device}': this APK targets the local Firebase emulators at 10.0.2.2, so registration and sign-in cannot succeed on a physical device. Install a cloud candidate instead, or set STUDYPATH_ALLOW_PHYSICAL=1 after building with -PfirebaseEmulatorHost=127.0.0.1." >&2
        exit 1
    fi
fi
# RTDB emulator may advertise loopback on reconnect. These routes also cover that address.
"$studyPathAdb" "${studyPathDevice[@]}" reverse tcp:9099 tcp:9099
"$studyPathAdb" "${studyPathDevice[@]}" reverse tcp:9000 tcp:9000
"$studyPathAdb" "${studyPathDevice[@]}" install -r "$studyPathApk"
"$studyPathAdb" "${studyPathDevice[@]}" shell am start -n com.studypath.app/.ui.auth.WelcomeActivity
