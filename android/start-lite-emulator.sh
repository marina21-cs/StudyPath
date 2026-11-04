#!/usr/bin/env bash
set -euo pipefail

# Separate profiles keep everyday app data out of the disposable smoke workflow.
studyPathRoot="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
studyPathSdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
studyPathEmulator="${STUDYPATH_EMULATOR:-$studyPathSdk/emulator/emulator}"
studyPathAdb="${STUDYPATH_ADB:-$studyPathSdk/platform-tools/adb}"
studyPathAvd="StudyPath_Lite"
studyPathPort=5554
studyPathHeadless=false
studyPathDisposable=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --test) studyPathAvd="StudyPath_Lite_Test"; studyPathDisposable=true; shift ;;
        --headless) studyPathHeadless=true; shift ;;
        --port)
            [[ $# -gt 1 ]] || { echo "--port needs an even port from 5554 to 5584." >&2; exit 1; }
            studyPathPort="$2"; shift 2 ;;
        *) echo "Usage: android/start-lite-emulator.sh [--test] [--headless] [--port PORT]" >&2; exit 1 ;;
    esac
done
if [[ ! "$studyPathPort" =~ ^[0-9]+$ ]] || (( studyPathPort < 5554 || studyPathPort > 5584 || studyPathPort % 2 )); then
    echo "Choose an even emulator port between 5554 and 5584." >&2
    exit 1
fi
[[ -x "$studyPathEmulator" && -x "$studyPathAdb" ]] || { echo "Set ANDROID_HOME to an installed Android SDK." >&2; exit 1; }
studyPathImage="$studyPathSdk/system-images/android-36/google_apis_playstore/x86_64"
[[ -f "$studyPathImage/system.img" ]] || { echo "Install API36 Google Play x86_64 first; this launcher never downloads SDK packages." >&2; exit 1; }
"$studyPathEmulator" -accel-check
if "$studyPathAdb" devices | awk 'NR>1 && $1 ~ /^emulator-/ { found=1 } END { exit !found }'; then
    echo "An emulator is already running. Close it first to keep only one virtual phone active." >&2
    exit 1
fi

# New named profiles are registered in the normal AVD directory for Android
# Studio discovery. Existing profiles are never overwritten or wiped.
python3 - "$studyPathAvd" "$studyPathSdk" <<'PY'
import json
import os
from pathlib import Path
import sys

name, sdk = sys.argv[1:]
avd_home = Path(os.environ.get("ANDROID_AVD_HOME", str(Path.home() / ".android/avd"))).expanduser()
folder = avd_home / f"{name}.avd"
entry = avd_home / f"{name}.ini"
marker = folder / "studypath-profile.json"
if folder.exists() or entry.exists():
    if not marker.exists() or not entry.exists() or not (folder / "config.ini").exists():
        raise SystemExit(f"{name} already exists without a complete StudyPath profile; refusing to change it.")
    print(f"Using existing {name}; keeping its data and configuration.")
else:
    folder.mkdir(parents=True)
    config = {
        "AvdId": name, "PlayStore.enabled": "true", "abi.type": "x86_64",
        "avd.ini.displayname": "StudyPath Lite" + (" Test (disposable)" if name.endswith("_Test") else ""),
        "avd.ini.encoding": "UTF-8", "disk.dataPartition.size": "6G",
        "fastboot.forceColdBoot": "no", "fastboot.forceFastBoot": "yes",
        "hw.accelerometer": "yes", "hw.audioInput": "no", "hw.battery": "yes",
        "hw.camera.back": "none", "hw.camera.front": "none", "hw.cpu.arch": "x86_64",
        "hw.cpu.ncore": "2", "hw.dPad": "no", "hw.gps": "no", "hw.gpu.enabled": "yes",
        "hw.gpu.mode": "auto", "hw.initialOrientation": "portrait", "hw.keyboard": "yes",
        "hw.lcd.density": "320", "hw.lcd.height": "1280", "hw.lcd.width": "720",
        "hw.mainKeys": "no", "hw.ramSize": "2048", "hw.sdCard": "no",
        "image.sysdir.1": str(Path(sdk) / "system-images/android-36/google_apis_playstore/x86_64") + "/",
        "runtime.network.latency": "none", "runtime.network.speed": "full",
        "showDeviceFrame": "no", "skin.dynamic": "yes", "skin.name": "720x1280",
        "tag.display": "Google Play", "tag.id": "google_apis_playstore", "target": "android-36",
        "vm.heapSize": "256"
    }
    (folder / "config.ini").write_text("".join(f"{key}={value}\n" for key, value in config.items()))
    entry.write_text(f"avd.ini.encoding=UTF-8\npath={folder}\ntarget=android-36\n")
    marker.write_text(json.dumps({"managedBy": "StudyPath", "profile": name, "resolution": "720x1280", "ramMB": 2048, "cores": 2, "disposable": name.endswith("_Test")}, indent=2) + "\n")
    print(f"Created {name}: 720x1280, 2 cores, 2048MB, API36 x86_64.")
PY

studyPathArgs=(-avd "$studyPathAvd" -port "$studyPathPort" -accel on -gpu auto -memory 2048 -cores 2 -no-audio -no-boot-anim)
if [[ "$studyPathDisposable" == true ]]; then studyPathArgs+=(-read-only -no-snapshot); fi
if [[ "$studyPathHeadless" == true ]]; then studyPathArgs+=(-no-window); fi

# This host's Qt emulator build can conflict with bundled FreeType/Fontconfig.
# Keep the compatibility workaround scoped to this process and its children.
if [[ -f /usr/lib/libfreetype.so.6 ]]; then
    export LD_PRELOAD="/usr/lib/libfreetype.so.6${LD_PRELOAD:+:$LD_PRELOAD}"
fi
if [[ -f /usr/lib/libfontconfig.so.1 ]]; then
    export LD_PRELOAD="/usr/lib/libfontconfig.so.1${LD_PRELOAD:+:$LD_PRELOAD}"
fi
mkdir -p "$studyPathRoot/.build/lite-emulator"
echo "Starting $studyPathAvd as emulator-$studyPathPort. Keep this terminal open."
"$studyPathEmulator" "${studyPathArgs[@]}" 2>&1 | tee "$studyPathRoot/.build/lite-emulator/$studyPathAvd.log"
