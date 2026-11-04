#!/usr/bin/env python3
"""B07.2 session/logout acceptance using local Firebase and a disposable AVD.

Requires a fresh, read-only StudyPath_Lite_Test. Never clears app data,
uninstalls packages, discards queued writes or contacts cloud Firebase.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

from b07_fixture import B07Fixture
import uuid


ROOT = Path(__file__).resolve().parent.parent
B07_NAMESPACE = None
PACKAGE = "com.studypath.app"
TEST_CLASS = PACKAGE + ".SessionIsolationTest"
OUT_ROOT = ROOT / "implementation/phases/01-backend/07-offline-reminders/02-session-lifecycle/test-results"
STAGES = ["prepareSignedInWorkspace", "queueAndLogoutOffline", "verifySignedOutAfterReconnect",
          "rejectOtherAndRestoreOriginal", "workerLogoutOverlap"]
STAGE_OBSERVATIONS = {
    STAGES[0]: ("notificationPermissionGranted", "completePrivateQueriesLoaded", "futureReminderEnqueued",
                "realWorkerNotificationPosted"),
    STAGES[1]: ("offlinePreLogoutNotificationPosted", "privateObserversActiveWithQueuedEcho",
                "offlineWritesPendingAtLogout", "loginShownAfterLogout", "privateListenersClosed",
                "noPrivateCallbacksAfterLogout", "lateProfileCallbacksBlocked", "draftRetainedAtLogout",
                "futureReminderCancelled", "postedNotificationsCleared", "backDidNotRevealPrivateUi"),
    STAGES[2]: ("privateMainRedirectedToLogin", "loggedOutAfterReconnect", "privateAccessDenied",
                "signedOutDraftRetained"),
    STAGES[3]: ("otherAccountRejected", "retainedPrivateReadingVisible", "originalAccountRestored",
                "immutablePracticeRetryAcknowledged", "draftClearedAfterAck"),
    STAGES[4]: ("overlapRunningObserved", "overlapConfirmationClickedOnRunning", "overlapTaggedWorkTerminal",
                "overlapNotificationsCleared", "loggedOutAfterOverlap"),
}
REVERSES = ("tcp:9099", "tcp:9000")
PERMISSION = "android.permission.POST_NOTIFICATIONS"
FIXTURE = "files/b07-session-fixture.json"


class CheckFailed(Exception):
    pass


class RunInterrupted(Exception):
    def __init__(self, signum):
        self.exit_code = 128 + signum


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def run(args, timeout=45, check=True):
    result = subprocess.run([str(value) for value in args], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise CheckFailed("A device command failed at the recorded stage; private output was suppressed.")
    return result


def local_database(path="users"):
    return B07Fixture(B07_NAMESPACE).database(path)

def create_other_account():
    """Retain only synthetic identity fields; never retain or log Auth tokens."""
    email = "b07_other_" + uuid.uuid4().hex + "@studypath.invalid"
    request = urllib.request.Request(
        "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=dummy",
        data=json.dumps({"email": email, "password": "LocalOffline123!", "returnSecureToken": True}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=10) as response:
        value = json.load(response)
    uid = value.get("localId")
    require(isinstance(uid, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", uid),
            "The local Auth emulator did not create the other synthetic identity.")
    return email, uid


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    global B07_NAMESPACE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", nargs="?", default="emulator-5554")
    parser.add_argument("--namespace", default="demo-studypath-b07-session-" + uuid.uuid4().hex[:8])
    parser.add_argument("--skip-build", action="store_true", help="Use frozen app/test APKs built for this exact namespace.")
    args = parser.parse_args()
    B07_NAMESPACE = args.namespace
    mock = B07Fixture(B07_NAMESPACE)
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Android/Sdk"))))
    adb = os.environ.get("STUDYPATH_ADB") or shutil.which("adb") or str(sdk / "platform-tools/adb")
    gradle = os.environ.get("STUDYPATH_GRADLE", str(ROOT / "android/gradlew"))
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    lock = OUT_ROOT / ".runner-lock"
    try:
        lock.mkdir()
    except FileExistsError:
        print("Another B07.2 run owns the evidence lock.", file=sys.stderr)
        return 1
    started = dt.datetime.now(dt.timezone.utc)
    out = OUT_ROOT / "latest"
    try:
        if out.exists():
            out.rename(OUT_ROOT / ("run-" + started.strftime("%Y%m%dT%H%M%SZ") + "-" + str(os.getpid())))
        out.mkdir()
    except BaseException:
        lock.rmdir()
        raise
    receipt = {
        "startedAtUtc": started.isoformat(), "status": "FAILED", "lastStage": "preflight",
        "avd": "StudyPath_Lite_Test", "serial": args.serial,
        "firebaseProject": "demo-studypath", "namespace": B07_NAMESPACE, "localOnly": True,
               "mockOnly": True, "realApprovalEstablished": False,
        "dataCleared": False, "packagesUninstalled": False, "stages": [],
        "remaining": ["B07.3 notification permission/channel/delivery matrix",
                      "B07.3 overdue reminder reconciliation", "B07.4 actual 48-hour offline retention",
                      "B08 cloud acceptance"],
    }
    radio_state = None
    reverse_state = None
    transport_touched = False
    known_private = set()
    prior_fixture = None
    failure_code = 0
    old_handlers = {}

    def interrupted(signum, _frame):
        raise RunInterrupted(signum)

    def device(*values, **kwargs):
        return run([adb, "-s", args.serial, *values], **kwargs)

    def shell(*values, **kwargs):
        return device("shell", *values, **kwargs)

    def fixture():
        value = json.loads(shell("run-as", PACKAGE, "cat", FIXTURE).stdout)
        require(isinstance(value, dict) and value.get("schemaVersion") == 1,
                "The session test fixture format is missing or incompatible.")
        require(value.get("namespace") == B07_NAMESPACE, "Wrong isolated B07 session namespace.")
        mock.claim_uid(value["uid"])
        for key in ("uid", "email", "otherEmail"):
            if isinstance(value.get(key), str):
                known_private.add(value[key])
        return value

    def redact(output):
        text = output.decode(errors="replace") if isinstance(output, bytes) else output
        for private in sorted(known_private, key=len, reverse=True):
            text = text.replace(private, "<redacted-synthetic-identity>")
        text = re.sub(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+", "<redacted-email>", text)
        text = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*\b", "<redacted-token>", text)
        return text

    def observe_radios(expected):
        for _ in range(40):
            observed = {key: int(shell("settings", "get", "global", key).stdout.strip()) for key in expected}
            if observed == expected:
                return observed
            time.sleep(.1)
        raise CheckFailed("Guest radio settings did not reach their required state.")

    def reverse_mappings():
        mappings = {}
        for row in device("reverse", "--list").stdout.decode().splitlines():
            parts = row.split()
            if not parts:
                continue
            require(len(parts) == 3, "Cannot safely interpret existing ADB reverse mappings.")
            mappings[parts[1]] = parts[2]
        return mappings

    def restore_transport():
        if radio_state is None and reverse_state is None:
            return
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
            current = reverse_mappings()
            for remote in REVERSES:
                if remote in current:
                    restore(lambda remote=remote: device("reverse", "--remove", remote))
                if remote in reverse_state:
                    restore(lambda remote=remote: device("reverse", remote, reverse_state[remote]))
        require(not errors, "Some original transport settings could not be restored.")
        if radio_state is not None and transport_touched:
            receipt["transportAfter"] = observe_radios(radio_state)
        if reverse_state is not None:
            observed = reverse_mappings()
            require({key: observed.get(key) for key in REVERSES} ==
                    {key: reverse_state.get(key) for key in REVERSES},
                    "Original affected ADB reverse mappings were not restored.")

    def fresh_install():
        if not shell("pm", "path", PACKAGE, check=False).stdout.strip():
            return
        result = shell("run-as", PACKAGE, "sh", "-c",
                       "'if [ -f shared_prefs/workspace.xml ]; then grep -q boundUid shared_prefs/workspace.xml; case $? in 1) exit 0;; *) exit 17;; esac; fi'", check=False)
        require(result.returncode == 0,
                "The test workspace is bound or could not be inspected safely; restart the read-only AVD. No data was cleared.")
        require(shell("run-as", PACKAGE, "test", "-e", FIXTURE, check=False).returncode != 0,
                "A prior session fixture exists; restart the read-only AVD rather than overwriting it.")

    def permission_granted():
        package_dump = shell("dumpsys", "package", PACKAGE).stdout.decode(errors="replace")
        match = re.search(r"android\.permission\.POST_NOTIFICATIONS:\s+granted=(true|false)", package_dump)
        require(match is not None, "Cannot verify the test installation's notification permission.")
        return match.group(1) == "true"

    def stop_process():
        shell("am", "force-stop", PACKAGE)
        for _ in range(40):
            if not shell("pidof", PACKAGE, check=False).stdout.strip():
                return
            time.sleep(.1)
        raise CheckFailed("App process did not terminate before the selected stage.")

    def selected_stage(name, other_email):
        nonlocal prior_fixture
        receipt["lastStage"] = name
        stop_process()
        print("B07.2:", name, flush=True)
        command = [adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                   TEST_CLASS + "#" + name, "-e", "b07Namespace", B07_NAMESPACE]
        if name == STAGES[0]:
            command += ["-e", "otherEmail", other_email]
        command += [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        timed_out = False
        try:
            result = run(command, timeout=180, check=False)
            output, code = result.stdout, result.returncode
        except subprocess.TimeoutExpired as error:
            output, code, timed_out = error.stdout or b"", 124, True
        # Read fixture identities only into memory so failed test logs can also be redacted.
        value = None
        try:
            value = fixture()
        except Exception:
            pass
        log_name = name + ".log"
        text = redact(output)
        (out / log_name).write_text(text + ("\nHost instrumentation timeout.\n" if timed_out else ""))
        stage_receipt = {
            "name": name, "exitCode": code, "timedOut": timed_out,
            "priorProcessStopped": True, "log": log_name,
            "command": ["<synthetic-email>" if item == other_email else str(item) for item in command],
        }
        receipt["stages"].append(stage_receipt)
        require(code == 0 and re.search(r"^OK \(1 test\)\s*$", text, re.M) and
                not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]", text),
                "Instrumentation did not report explicit success: " + name)
        require(value is not None, "The selected method did not leave a valid private fixture.")
        require(value.get("stages", {}).get(name) == "passed", "The fixture did not confirm the selected stage.")
        require(value.get("otherEmail") == other_email, "The other synthetic identity changed unexpectedly.")
        require(isinstance(value.get("uid"), str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value["uid"]),
                "The original synthetic identity is invalid.")
        require(value.get("email") != other_email, "The synthetic accounts must be distinct.")
        if prior_fixture is not None:
            stable_keys = ("uid", "email", "otherEmail", "moduleId", "moduleVersion", "scoreId", "readingId",
                           "reminderId", "dueAt", "futureWorkId", "postedWorkId", "expectedScore", "expectedProgress",
                           "expectedReading", "expectedReminder")
            require(all(value.get(key) == prior_fixture.get(key) for key in stable_keys),
                    "The selected stage changed an immutable fixture field.")
            if "attempt" in prior_fixture:
                require(value.get("attempt") == prior_fixture["attempt"], "The retained practice submission changed.")
        pid = value.get("stagePids", {}).get(name)
        previous_pids = [entry.get("pid") for entry in receipt["stages"][:-1]]
        require(type(pid) is int and pid > 0 and pid not in previous_pids,
                "The selected stage did not establish a distinct application process.")
        stage_receipt.update(junit="OK (1 test)", pid=pid, fixtureConfirmed=True)
        # Only safe booleans/counts are allowed into the public receipt.
        observations = value.get("observations", {})
        require(isinstance(observations, dict), "The session observations must be an object.")
        required_observations = [key for completed in STAGES[:STAGES.index(name) + 1]
                                 for key in STAGE_OBSERVATIONS[completed]]
        require(all(observations.get(key) is True for key in required_observations),
                "A selected stage omitted or contradicted a required session observation.")
        if name == STAGES[4]:
            require(type(observations.get("overlapRequestsEnqueued")) is int
                    and 1 <= observations["overlapRequestsEnqueued"] <= 24
                    and observations.get("postTerminalObservationMillis") == 1500,
                    "Real worker overlap or the post-terminal observation interval was not established.")
        receipt["observations"] = {key: item for key, item in observations.items()
                                   if isinstance(key, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", key)
                                   and (type(item) is bool or type(item) is int and 0 <= item <= 1_000_000)}
        prior_fixture = value
        return value

    def check_records(data, value, queued):
        require(isinstance(data, dict), "The original account's server records are missing.")
        score = data.get("scores", {})
        require(set(score) == {value["scoreId"]}, "Score records are missing or duplicated.")
        expected = dict(value["expectedScore"])
        # The repository appends the reviewed authority to DatabaseRecords.score fields.
        expected.update(receipt["mockFixture"]["scoreBinding"])
        if not queued:
            expected["earnedPoints"] = 0
        score = score[value["scoreId"]]
        require({key: item for key, item in score.items() if key != "updatedAt"} == expected,
                "The server score differs from the expected plan/payload.")
        progress_rows = data.get("reviewProgress", {})
        require(set(progress_rows) == {value["moduleId"]}, "Review progress has unexpected module records.")
        versions = progress_rows[value["moduleId"]]
        version_key = "v" + str(value["moduleVersion"])
        require(set(versions) == {version_key}, "Review progress has unexpected version records.")
        progress = versions[version_key]
        expected = dict(value["expectedProgress"])
        if not queued:
            expected.update(completed=False, lastSection=0)
        require({key: item for key, item in progress.items() if key != "updatedAt"} == expected,
                "The server review progress differs from the expected payload.")
        readings = data.get("savedReadings", {})
        require(set(readings) == {value["readingId"]} and readings[value["readingId"]] == value["expectedReading"],
                "The acknowledged saved reading was changed or lost.")
        reminders = data.get("reminders", {})
        require(set(reminders) == {value["reminderId"]}, "The acknowledged reminder was changed or lost.")
        reminder = reminders[value["reminderId"]]
        require({key: item for key, item in reminder.items() if key != "updatedAt"} == value["expectedReminder"],
                "The acknowledged reminder payload changed.")
        for record in (score, progress, reminder):
            stamp = record.get("updatedAt")
            require(type(stamp) in (int, float) and started.timestamp() * 1000 - 60000 <= stamp <= time.time() * 1000 + 60000,
                    "A server timestamp is unresolved or outside this test run.")
        attempts = data.get("practiceAttempts", {})
        if queued:
            attempt = value["attempt"]
            require(set(attempts) == {attempt["id"]} and attempts[attempt["id"]] ==
                    {key: item for key, item in attempt.items() if key != "id"},
                    "The immutable practice submission was lost, changed or duplicated.")
        else:
            require(not attempts, "A queued practice submission reached the server while signed out/offline.")

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            old_handlers[signum] = signal.signal(signum, interrupted)
        require(re.fullmatch(r"emulator-\d+", args.serial), "Use an Android emulator serial.")
        require(Path(adb).is_file() or shutil.which(adb), "The ADB executable is unavailable.")
        avd_lines = device("emu", "avd", "name").stdout.decode().splitlines()
        require(avd_lines and avd_lines[0].strip() == "StudyPath_Lite_Test", "Only the dedicated test AVD is allowed.")
        port = args.serial.split("-")[1]
        managed_process = False
        for proc in Path("/proc").glob("[0-9]*/cmdline"):
            try:
                tokens = proc.read_bytes().decode().strip("\0").split("\0")
                if tokens and Path(tokens[0]).name.startswith(("qemu-system-", "emulator")):
                    managed_process |= ("-avd" in tokens and tokens[tokens.index("-avd") + 1] == "StudyPath_Lite_Test"
                                        and "-port" in tokens and tokens[tokens.index("-port") + 1] == port
                                        and "-read-only" in tokens)
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed_process, "Cannot verify the selected test AVD is running read-only.")
        require(shell("getprop", "sys.boot_completed").stdout.strip() == b"1", "Wait for Android to finish booting.")
        require(int(shell("getprop", "ro.build.version.sdk").stdout.strip()) >= 33,
                "This notification/session acceptance suite requires API 33 or newer.")
        receipt["mockFixture"] = mock.prepare(out)
        catalog = local_database("catalog")
        require(isinstance(catalog, dict) and catalog.get("curriculumVersion") == "bsit_demo_v1"
                and catalog.get("approved") is False and catalog.get("modules", {}).get("programming_basics"),
                "The isolated mock catalog is incomplete.")
        with urllib.request.urlopen("http://127.0.0.1:9099/", timeout=5):
            pass
        fresh_install()
        receipt["lastStage"] = "build"
        flags = ["--offline", "--no-daemon", "--console=plain", "--max-workers=2", "--no-parallel",
                 "-PfirebaseUseEmulators=true", "-PfirebaseProjectId=demo-studypath",
                 "-PfirebaseEmulatorHost=10.0.2.2", "-PfirebaseApiKey=AIzaSyDemoStudyPathNotARealAPIKey000000",
                 "-PfirebaseAppId=1:1234567890:android:0123456789abcdef",
                 f"-PfirebaseDatabaseUrl=https://{B07_NAMESPACE}.firebaseio.com"]
        build_command = [gradle, *flags, ":app:assembleDebug", ":app:assembleDebugAndroidTest"]
        receipt["buildCommand"] = build_command
        receipt["buildSkipped"] = args.skip_build
        if not args.skip_build:
            with (out / "build.log").open("wb") as log:
                result = subprocess.run(build_command, cwd=ROOT / "android", stdout=log,
                                        stderr=subprocess.STDOUT, timeout=300)
            receipt["buildExitCode"] = result.returncode
            require(result.returncode == 0, "The build failed; inspect build.log.")
        apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
        test_apk = ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        receipt.update(apkSha256=file_digest(apk), testApkSha256=file_digest(test_apk),
                       testSourceSha256=file_digest(ROOT / "android/app/src/androidTest/java/com/studypath/app/SessionIsolationTest.kt"),
                       runnerSha256=file_digest(Path(__file__)))
        receipt["fixtureSourceSha256"] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in [ROOT / "android/b07_fixture.py", ROOT / "android/test-fixtures/b07-policy-fixture.mjs",
                         ROOT / "backend/academics/policy-fixture.mjs", ROOT / "backend/database.rules.json"]}
        receipt["lastStage"] = "install"
        with (out / "install.log").open("w") as log:
            for artifact in (apk, test_apk):
                result = device("install", "-r", artifact, check=False)
                log.write(redact(result.stdout))
                require(result.returncode == 0, "APK installation failed; inspect install.log.")
        fresh_install()
        receipt["notificationPermissionBefore"] = permission_granted()
        shell("pm", "grant", PACKAGE, PERMISSION)
        receipt["notificationPermissionGranted"] = permission_granted()
        require(receipt["notificationPermissionGranted"], "The isolated test requires notification permission.")
        reverse_state = reverse_mappings()
        radio_state = {}
        for key in ("airplane_mode_on", "wifi_on", "mobile_data"):
            value = shell("settings", "get", "global", key).stdout.strip()
            require(value in (b"0", b"1"), "Cannot safely preserve a guest radio setting.")
            radio_state[key] = int(value)
        require(not radio_state["airplane_mode_on"] and (radio_state["wifi_on"] or radio_state["mobile_data"]),
                "Start the isolated test with guest networking enabled.")
        receipt["transportBefore"] = dict(radio_state)
        for remote in REVERSES:
            device("reverse", remote, remote)
        others_before = local_database() or {}
        require(isinstance(others_before, dict), "The local users collection is invalid.")
        receipt["lastStage"] = "createOtherLocalIdentity"
        other_email, other_uid = create_other_account()
        mock.claim_uid(other_uid)
        known_private.update((other_email, other_uid))
        require(not local_database("users/" + other_uid), "The other synthetic account must have no application records.")
        value = selected_stage(STAGES[0], other_email)
        uid = value["uid"]
        require(uid != other_uid and uid not in others_before,
                "The original synthetic account was not newly created for this run.")
        baseline = local_database("users/" + uid)
        check_records(baseline, value, queued=False)
        baseline_digest = digest(baseline)
        transport_touched = True
        for remote in REVERSES:
            device("reverse", "--remove", remote)
        shell("cmd", "connectivity", "airplane-mode", "enable")
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
        receipt["offlineTransportObserved"] = observe_radios({"airplane_mode_on": 1, "wifi_on": 0, "mobile_data": 0})
        value = selected_stage(STAGES[1], other_email)
        check_records(local_database("users/" + uid), value, queued=False)
        require(digest(local_database("users/" + uid)) == baseline_digest,
                "The server baseline changed during offline logout.")
        receipt["offlineLogoutServerBaselineUnchanged"] = True
        restore_transport()
        for remote in REVERSES:
            device("reverse", remote, remote)
        value = selected_stage(STAGES[2], other_email)
        # A reconnect indicator is insufficient: verify the server remains unchanged
        # for a bounded real interval after signed-out process restart/reconnect.
        deadline = time.monotonic() + 3.0
        samples = 0
        while True:
            data = local_database("users/" + uid)
            check_records(data, value, queued=False)
            require(digest(data) == baseline_digest, "Pending private writes flushed while signed out.")
            require(not local_database("users/" + other_uid), "The other synthetic account acquired application records.")
            samples += 1
            if time.monotonic() >= deadline:
                break
            time.sleep(.25)
        receipt.update(signedOutServerBaselineUnchanged=True, signedOutObservationSeconds=3.0,
                       signedOutServerSamples=samples)
        value = selected_stage(STAGES[3], other_email)
        deadline = time.monotonic() + 45
        while True:
            try:
                restored = local_database("users/" + uid)
                check_records(restored, value, queued=True)
                break
            except CheckFailed:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.5)
        receipt["originalAccountQueueRestored"] = True
        restored_digest = digest(restored)
        value = selected_stage(STAGES[4], other_email)
        final = local_database("users/" + uid)
        check_records(final, value, queued=True)
        require(digest(final) == restored_digest, "Worker/logout overlap changed application records.")
        require(not local_database("users/" + other_uid), "Wrong-account rejection wrote application data for that account.")
        others_after = local_database() or {}
        others_after.pop(uid, None)
        require(digest(others_after) == digest(others_before), "Another account's application records changed.")
        require(all(value["stages"].get(name) == "passed" for name in STAGES), "Not all five session stages passed.")
        receipt.update(status="PASSED", lastStage="complete", sameBoundAccount=True,
                       otherAccountHasNoApplicationRecords=True, otherAccountsUnchanged=True,
                       overlapServerRecordsUnchanged=True,
                       serverVerified={"score": "12/20", "progress": "completed; section1; version1",
                                       "practiceRecords": 1, "savedReadings": 1, "reminders": 1})
        print("B07.2 session isolation passed: five stages; original-account recovery; isolated reminders.")
    except RunInterrupted as error:
        failure_code = error.exit_code
        receipt["failure"] = "Interrupted; original transport restoration was attempted."
        print("B07.2 interrupted.", file=sys.stderr)
    except Exception as error:
        failure_code = 1
        receipt["failure"] = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        print("B07.2 did not pass:", receipt["failure"], file=sys.stderr)
    finally:
        # A second interrupt must not prevent the bounded restoration attempt.
        for signum in old_handlers:
            signal.signal(signum, signal.SIG_IGN)
        try:
            restore_transport()
            receipt["transportRestored"] = True
        except Exception as error:
            receipt.update(transportRestored=False, restoreError=type(error).__name__)
            failure_code = failure_code or 1
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
            failure_code = failure_code or 1
        if failure_code:
            receipt["status"] = "FAILED"
        receipt.update(finishedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(), exitCode=failure_code)
        try:
            (out / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
        finally:
            lock.rmdir()
            for signum, handler in old_handlers.items():
                signal.signal(signum, handler)
        print("Evidence:", out)
    return failure_code


if __name__ == "__main__":
    sys.exit(main())
