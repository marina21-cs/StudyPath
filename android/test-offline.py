#!/usr/bin/env python3
"""Bounded B07 queue/restart acceptance against local Firebase only.

Requires a freshly booted read-only StudyPath_Lite_Test. Does not clear data,
uninstall packages, purge writes, modify the seed, or touch cloud Firebase.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid

from b07_fixture import B07Fixture


ROOT = Path(__file__).resolve().parent.parent
B07_NAMESPACE = None
PACKAGE = "com.studypath.app"
OUT_ROOT = ROOT / "implementation/phases/01-backend/07-offline-reminders/01-offline-queue/test-results"
STAGES = ["prepareOnlineWorkspace", "queueWhileOffline", "verifyQueuedAfterColdRestart",
          "reconnectWithoutRetry", "retryExactPracticeAttempt"]


class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def run(args, timeout=45, check=True):
    result = subprocess.run([str(x) for x in args], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        # Never surface raw device preferences, account identifiers or REST URLs.
        raise CheckFailed("A device command failed; see the recorded stage, not private command output.")
    return result


def local_database(path="users"):
    return B07Fixture(B07_NAMESPACE).database(path)

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def main():
    global B07_NAMESPACE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", nargs="?", default="emulator-5554")
    parser.add_argument("--namespace", default="demo-studypath-b07-offline-" + uuid.uuid4().hex[:8])
    parser.add_argument("--skip-build", action="store_true", help="Use frozen app/test APKs built for this exact namespace.")
    args = parser.parse_args()
    B07_NAMESPACE = args.namespace
    mock = B07Fixture(B07_NAMESPACE)
    adb = os.environ.get("STUDYPATH_ADB") or shutil.which("adb") or str(
        Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))) / "platform-tools/adb")
    gradle = os.environ.get("STUDYPATH_GRADLE", str(ROOT / "android/gradlew"))
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    lock = OUT_ROOT / ".runner-lock"
    try:
        lock.mkdir()
    except FileExistsError:
        print("Another B07 run owns the evidence lock.", file=sys.stderr)
        return 1
    started = dt.datetime.now(dt.timezone.utc)
    out = OUT_ROOT / "latest"
    if out.exists():
        out.rename(OUT_ROOT / ("run-" + started.strftime("%Y%m%dT%H%M%SZ") + "-" + str(os.getpid())))
    out.mkdir()
    receipt = {"startedAtUtc": started.isoformat(), "status": "FAILED", "lastStage": "preflight",
               "avd": "StudyPath_Lite_Test", "serial": args.serial,
               "firebaseProject": "demo-studypath", "namespace": B07_NAMESPACE, "localOnly": True,
               "mockOnly": True, "realApprovalEstablished": False,
               "dataCleared": False, "packagesUninstalled": False, "stages": [],
               "remaining": ["Logout with pending writes and reminder cancellation",
                             "Notification permission/channel/delivery matrix", "Actual48-hour offline retention"]}
    radio_state = None
    reverse_state = None
    transport_touched = False
    failure = None

    def device(*values, **kwargs):
        return run([adb, "-s", args.serial, *values], **kwargs)

    def shell(*values, **kwargs):
        return device("shell", *values, **kwargs)

    def fixture():
        result = shell("run-as", PACKAGE, "cat", "files/b07-fixture.json")
        value = json.loads(result.stdout)
        require(value.get("namespace") == B07_NAMESPACE, "Wrong isolated B07 fixture namespace.")
        mock.claim_uid(value["uid"])
        return value

    def observe_radios(expected):
        observed = {}
        for _ in range(30):
            observed = {key: int(shell("settings", "get", "global", key).stdout.strip())
                        for key in expected}
            if observed == expected:
                return observed
            time.sleep(.1)
        raise CheckFailed("Guest radio settings did not reach their required state.")

    def account(uid):
        return local_database("users/" + uid) or {}

    def check_records(data, f, queued):
        scores = data.get("scores", {})
        require(set(scores) == {f["scoreId"]}, "Unexpected score IDs or duplicate score records.")
        score = scores[f["scoreId"]]
        expected = dict(f["expectedScore"])
        # The repository appends the reviewed authority to DatabaseRecords.score fields.
        expected.update(receipt["mockFixture"]["scoreBinding"])
        if not queued:
            expected["earnedPoints"] = 0
        require({k: v for k, v in score.items() if k != "updatedAt"} == expected,
                "Server score differs from the expected plan/payload.")
        progress = data.get("reviewProgress", {}).get(f["moduleId"], {}).get("v" + str(f["moduleVersion"]), {})
        expected_progress = dict(f["expectedProgress"])
        if not queued:
            expected_progress.update(completed=False, lastSection=0)
        require({k: v for k, v in progress.items() if k != "updatedAt"} == expected_progress,
                "Server progress differs from the expected version/payload.")
        for record in [score, progress]:
            stamp = record.get("updatedAt")
            require(type(stamp) in (int, float) and started.timestamp() * 1000 - 60000 <= stamp <= time.time() * 1000 + 60000,
                    "Server timestamp did not resolve within this test run.")
        attempts = data.get("practiceAttempts", {})
        if queued:
            attempt = f["attempt"]
            require(set(attempts) == {attempt["id"]}, "Practice submission was lost or duplicated.")
            require(attempts[attempt["id"]] == {k: v for k, v in attempt.items() if k != "id"},
                    "Immutable practice payload changed.")
        else:
            require(not attempts, "An offline practice submission reached the server unexpectedly.")

    def stop_process():
        shell("am", "force-stop", PACKAGE)
        for _ in range(40):
            if not shell("pidof", PACKAGE, check=False).stdout.strip():
                return
            time.sleep(.1)
        raise CheckFailed("App process did not stop before the cold restart.")

    def stage(name):
        receipt["lastStage"] = name
        stop_process()
        print("B07:", name, flush=True)
        result = device("shell", "am", "instrument", "-w", "-r", "-e", "class",
                        "com.studypath.app.OfflineRecoveryTest#" + name,
                        "-e", "b07Namespace", B07_NAMESPACE,
                        PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=120, check=False)
        (out / (name + ".log")).write_bytes(result.stdout)
        output = result.stdout.decode(errors="replace")
        require(result.returncode == 0 and re.search(r"^OK \(1 test\)", output, re.M) and
                not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]", output),
                "Instrumentation did not report explicit success: " + name)
        f = fixture()
        require(f["stages"].get(name) == "passed", "Test fixture did not confirm the selected stage.")
        pids = f["stagePids"]
        previous = [s["pid"] for s in receipt["stages"]]
        require(pids[name] not in previous, "Cold restart did not create a new application process.")
        receipt["stages"].append({"name": name, "junit": "OK (1 test)", "pid": pids[name],
                                  "priorProcessStopped": True, "log": name + ".log"})
        return f

    def restore_transport():
        if radio_state is not None or reverse_state is not None:
            device("wait-for-device", timeout=30)
        errors = []
        def restore(command):
            try:
                command()
            except Exception as error:
                errors.append(type(error).__name__)
        if radio_state is not None and transport_touched:
            restore(lambda: shell("cmd", "connectivity", "airplane-mode", "enable" if radio_state["airplane_mode_on"] else "disable"))
            restore(lambda: shell("svc", "wifi", "enable" if radio_state["wifi_on"] else "disable"))
            restore(lambda: shell("svc", "data", "enable" if radio_state["mobile_data"] else "disable"))
        if reverse_state is not None:
            for remote in ("tcp:9099", "tcp:9000"):
                restore(lambda remote=remote: device("reverse", "--remove", remote, check=False))
                if remote in reverse_state:
                    restore(lambda remote=remote: device("reverse", remote, reverse_state[remote]))
        require(not errors, "Some original transport settings could not be restored.")
        if radio_state is not None and transport_touched:
            receipt["transportAfter"] = observe_radios(radio_state)

    try:
        require(re.fullmatch(r"emulator-\d+", args.serial), "Use an Android emulator serial.")
        require(Path(adb).is_file() or shutil.which(adb), "ADB executable is unavailable.")
        require(device("emu", "avd", "name").stdout.decode().splitlines()[0].strip() == "StudyPath_Lite_Test",
                "Refusing a device other than the dedicated test AVD.")
        port = args.serial.split("-")[1]
        found = False
        # Linux /proc identifies the actual emulator executable and tokenized options;
        # a shell command containing this AVD name cannot satisfy this check.
        for proc in Path("/proc").glob("[0-9]*/cmdline"):
            try:
                tokens = proc.read_bytes().decode().strip("\0").split("\0")
                if tokens and Path(tokens[0]).name.startswith(("qemu-system-", "emulator")):
                    found |= ("-avd" in tokens and tokens[tokens.index("-avd") + 1] == "StudyPath_Lite_Test" and
                              "-port" in tokens and tokens[tokens.index("-port") + 1] == port and "-read-only" in tokens)
            except (OSError, UnicodeError, IndexError):
                pass
        require(found, "Cannot verify the selected test AVD is running read-only.")
        require(shell("getprop", "sys.boot_completed").stdout.strip() == b"1", "Wait for Android to finish booting.")
        receipt["mockFixture"] = mock.prepare(out)
        catalog = local_database("catalog")
        require(catalog["curriculumVersion"] == "bsit_demo_v1" and catalog["approved"] is False,
                "The isolated mock catalog is incomplete.")
        with urllib.request.urlopen("http://127.0.0.1:9099/", timeout=5):
            pass
        installed = shell("pm", "path", PACKAGE, check=False).stdout.strip()
        if installed:
            # Print neither the workspace XML nor its binding. This uses the same
            # fail-closed check as the regular smoke runner.
            result = shell("run-as", PACKAGE, "sh", "-c",
                           "'if [ -f shared_prefs/workspace.xml ]; then grep -q boundUid shared_prefs/workspace.xml; case $? in 1) exit 0;; *) exit 17;; esac; fi'", check=False)
            require(result.returncode == 0, "Test installation is already bound; restart the read-only AVD. No data was cleared.")
        receipt["lastStage"] = "build"
        flags = ["--offline", "--no-daemon", "--console=plain", "--max-workers=2", "--no-parallel",
                 "-PfirebaseUseEmulators=true", "-PfirebaseProjectId=demo-studypath",
                 "-PfirebaseEmulatorHost=10.0.2.2", "-PfirebaseApiKey=AIzaSyDemoStudyPathNotARealAPIKey000000",
                 "-PfirebaseAppId=1:1234567890:android:0123456789abcdef",
                 f"-PfirebaseDatabaseUrl=https://{B07_NAMESPACE}.firebaseio.com"]
        receipt["buildSkipped"] = args.skip_build
        if not args.skip_build:
            command = [gradle, *flags, ":app:assembleDebug", ":app:assembleDebugAndroidTest"]
            receipt["buildCommand"] = command
            with (out / "build.log").open("wb") as log:
                result = subprocess.run(command, cwd=ROOT / "android", stdout=log, stderr=subprocess.STDOUT, timeout=240)
            receipt["buildExitCode"] = result.returncode
            require(result.returncode == 0, "Build failed; inspect build.log.")
        apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
        receipt["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        test_apk = ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        receipt["testApkSha256"] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        receipt["fixtureSourceSha256"] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in [ROOT / "android/b07_fixture.py", ROOT / "android/test-fixtures/b07-policy-fixture.mjs",
                         ROOT / "backend/academics/policy-fixture.mjs", ROOT / "backend/database.rules.json"]}
        receipt["testSourceSha256"] = hashlib.sha256((ROOT / "android/app/src/androidTest/java/com/studypath/app/OfflineRecoveryTest.kt").read_bytes()).hexdigest()
        receipt["runnerSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        device("install", "-r", str(apk))
        device("install", "-r", str(test_apk))
        reverse_state = {}
        for row in device("reverse", "--list").stdout.decode().splitlines():
            parts = row.split()
            if len(parts) == 3:
                reverse_state[parts[1]] = parts[2]
        radio_state = {}
        for key in ("airplane_mode_on", "wifi_on", "mobile_data"):
            value = shell("settings", "get", "global", key).stdout.strip()
            require(value in (b"0", b"1"), "Cannot safely preserve a device radio setting.")
            radio_state[key] = int(value)
        require(not radio_state["airplane_mode_on"] and (radio_state["wifi_on"] or radio_state["mobile_data"]),
                "Start the isolated test with networking enabled.")
        receipt["transportBefore"] = radio_state
        device("reverse", "tcp:9099", "tcp:9099")
        device("reverse", "tcp:9000", "tcp:9000")
        others_before = local_database() or {}
        f = stage(STAGES[0])
        uid = f["uid"]
        require(uid not in others_before, "The synthetic account was not newly created for this run.")
        check_records(account(uid), f, False)
        transport_touched = True
        for remote in ("tcp:9099", "tcp:9000"):
            device("reverse", "--remove", remote)
        shell("cmd", "connectivity", "airplane-mode", "enable")
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
        receipt["offlineTransportObserved"] = observe_radios({"airplane_mode_on": 1, "wifi_on": 0, "mobile_data": 0})
        f = stage(STAGES[1])
        check_records(account(uid), f, False)
        f = stage(STAGES[2])
        check_records(account(uid), f, False)
        receipt["offlineServerBaselineUnchanged"] = True
        restore_transport()
        device("reverse", "tcp:9099", "tcp:9099")
        device("reverse", "tcp:9000", "tcp:9000")
        f = stage(STAGES[3])
        deadline = time.monotonic() + 45
        while True:
            try:
                check_records(account(uid), f, True)
                break
            except CheckFailed:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.5)
        receipt["automaticQueueFlushWithoutReplay"] = True
        original_attempt = account(uid)["practiceAttempts"]
        f = stage(STAGES[4])
        after = account(uid)
        check_records(after, f, True)
        require(after["practiceAttempts"] == original_attempt, "Retry changed or duplicated the immutable attempt.")
        others_after = local_database() or {}
        others_after.pop(uid, None)
        require(digest(others_before) == digest(others_after), "Another account's records changed during this isolated run.")
        require(all(f["stages"].get(s) == "passed" for s in STAGES), "Not all five stages completed.")
        receipt.update(status="PASSED", lastStage="complete", sameBoundAccount=True,
                       immutableRetryUnchanged=True, otherAccountsUnchanged=True,
                       serverVerified={"score": "12/20", "progress": "completed; section1; version1",
                                       "practiceRecords": 1, "practiceCorrect": 2, "practiceTotal": 2})
        print("B07 queue recovery passed:5 stages; original account; one immutable practice record.")
    except Exception as error:
        failure = error
        # Avoid URL-bearing exception messages from local REST errors.
        receipt["failure"] = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        print("B07 did not pass:", receipt["failure"], file=sys.stderr)
    finally:
        try:
            restore_transport()
            receipt["transportRestored"] = True
        except Exception as error:
            receipt["transportRestored"] = False
            receipt["restoreError"] = type(error).__name__
            receipt["status"] = "FAILED"
            failure = error
        try:
            if mock.owned:
                setup = shell("run-as", PACKAGE, "cat", "files/b07-setup-identity.json", check=False)
                if setup.returncode == 0:
                    identity = json.loads(setup.stdout)
                    require(identity.get("namespace") == B07_NAMESPACE, "Wrong setup cleanup namespace.")
                    mock.claim_uid(identity["uid"])
            receipt["isolationProof"] = mock.cleanup()
        except Exception as error:
            receipt["cleanupFailure"] = type(error).__name__
            receipt["status"] = "FAILED"
            failure = error
        receipt["finishedAtUtc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        receipt["exitCode"] = 1 if failure else 0
        (out / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
        lock.rmdir()
        print("Evidence:", out)
    return 1 if failure else 0


if __name__ == "__main__":
    sys.exit(main())
