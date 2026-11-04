#!/usr/bin/env python3
"""Bounded B07.3 reminder acceptance on the fresh read-only StudyPath_Lite_Test.

Uses local demo Firebase only. Never clears data, uninstalls, changes clocks or
forces a JobScheduler execution. The background case uses HOME and ordinary
am kill; its delivery and notification tap are observed before instrumentation.
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
import xml.etree.ElementTree as ET
import uuid

from b07_fixture import B07Fixture


ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.studypath.app"
TEST_CLASS = PACKAGE + ".ReminderDeliveryTest"
OUT_ROOT = ROOT / "implementation/phases/01-backend/07-offline-reminders/03-reminders/test-results"
FIXTURE = "files/b07-reminders-fixture.json"
SETUP_IDENTITY = "files/b07-reminders-setup-identity.json"
B07_NAMESPACE = None
PROOF = "files/b07-reminders-host-proof.json"
PERMISSION = "android.permission.POST_NOTIFICATIONS"
STAGES = ["prepareAndDenyRuntimePermission", "verifyAppNotificationsBlocked",
          "verifyChannelNotificationsBlocked", "saveAndDeliverAllowedReminder", "editSameReminder",
          "disableAndDeleteReminder", "verifyOverdueReconciliation", "prepareBackgroundReminder",
          "verifyBackgroundDeliveryAfterReclaim"]
STAGE_OBSERVATIONS = {
    STAGES[0]: ("testRequestedRuntimePromptActuallyDenied", "runtimeDenialRecoveryVisible", "runtimeDenialDueRecordRetainedWithoutNotification"),
    STAGES[1]: ("appNotificationsActuallyBlocked", "appBlockRecoveryOpenedSystemSettings", "appBlockDueRecordRetainedWithoutNotification"),
    STAGES[2]: ("studyChannelActuallyBlocked", "channelRecoveryOpenedSystemSettings", "channelBlockDueRecordRetainedWithoutNotification", "channelRestoredByUi"),
    STAGES[3]: ("allowedReminderSavedThroughUi", "realScheduledReminderDelivered", "notificationGenericAndPrivate", "notificationTapOpenedAuthenticatedApp"),
    STAGES[4]: ("editPreservedLogicalReminderId", "editReplacedAndCancelledOldWork", "oldDueTimeProducedNoStaleNotification",
                "typedRetargetPreservedIdAndDueTime", "typedRetargetCancelledPriorWork", "typedTargetResolvedFromAcknowledgedSource"),
    STAGES[5]: ("disableCancelledScheduledWork", "reenableRetainedLogicalIdAndScheduledNewWork", "deleteRemovedRecordAndCancelledWork", "cancelledDueTimeProducedNoNotification"),
    STAGES[6]: ("overdueOriginalDelayedRequestPreserved", "overdueDeliveredExactlyOnce", "deliveredOverdueNotRescheduled"),
    STAGES[7]: ("backgroundReminderSavedThroughUi", "backgroundReminderEnqueuedBeforeHandoff"),
    STAGES[8]: ("ordinaryBackgroundProcessReclaimObserved", "backgroundWorkerRanInNewProcess", "backgroundRealNotificationDeliveredAndTapped", "backgroundAuthenticatedNavigationAndPrivacy", "backgroundNoDuplicateWorkGeneration"),
}
TITLE = "Time for a study session"
BODY = "Open StudyPath to continue your review."


class CheckFailed(Exception):
    pass


class CommandFailed(CheckFailed):
    def __init__(self, operation, exit_code, classification):
        self.details = {"operation": operation, "exitCode": exit_code,
                        "classification": classification}
        super().__init__(f"Device operation {operation} failed with exit code {exit_code} ({classification}); private output was suppressed.")


class RunInterrupted(Exception):
    def __init__(self, signum):
        self.exit_code = 128 + signum


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def command_operation(command):
    """Return fixed labels only; command arguments may contain private data."""
    values = tuple(command[3:] if len(command) > 2 and command[1] == "-s" else command)
    if len(values) == 3 and values[:2] == ("shell", "cat") and re.fullmatch(r"/proc/[1-9]\d*/oom_score_adj", values[2]):
        return "process.priority.read"
    prefixes = (
        (("shell", "input", "keyevent", "KEYCODE_BACK"), "ui.back"),
        (("shell", "uiautomator", "dump"), "ui.hierarchy.dump"),
        (("shell", "cat", "/sdcard/StudyPathReminderHost.xml"), "ui.hierarchy.read"),
        (("shell", "rm", "-f", "/sdcard/StudyPathReminderHost.xml"), "ui.hierarchy.remove"),
        (("shell", "input", "tap"), "ui.tap"),
        (("shell", "input", "keyevent"), "ui.keyevent"),
        (("shell", "am", "start"), "activity.start"),
        (("shell", "am", "instrument"), "instrumentation.run"),
        (("shell", "am", "force-stop"), "process.force-stop"),
        (("shell", "am", "kill"), "process.background-kill"),
        (("shell", "pidof"), "process.read"),
        (("shell", "dumpsys", "package"), "permission.read"),
        (("shell", "pm", "grant"), "permission.grant"),
        (("shell", "pm", "revoke"), "permission.revoke"),
        (("shell", "pm", "clear-permission-flags"), "permission.flags.clear"),
        (("shell", "pm", "set-permission-flags"), "permission.flags.set"),
        (("shell", "cmd", "appops", "get"), "notification-appop.read"),
        (("shell", "cmd", "appops", "set"), "notification-appop.set"),
        (("shell", "settings", "get", "global"), "radio.read"),
        (("shell", "dumpsys", "notification"), "notification.read"),
        (("shell", "dumpsys", "activity"), "activity.read"),
        (("shell", "-T", "run-as", PACKAGE, "tee", PROOF), "app-private.write"),
        (("shell", "run-as"), "app-private.read"),
        (("reverse", "--list"), "routing.read"),
        (("reverse", "--remove"), "routing.remove"),
        (("reverse",), "routing.set"),
        (("install",), "apk.install"),
    )
    return next((label for prefix, label in prefixes if values[:len(prefix)] == prefix), "device.command")


def command_failure_classification(output):
    text = output.decode(errors="replace").lower() if isinstance(output, bytes) else (output or "").lower()
    signatures = (
        (("permission denied", "securityexception"), "permission-denied"),
        (("device offline", "device not found", "no devices/emulators found"), "device-unavailable"),
        (("could not get idle state",), "ui-idle-state-unavailable"),
        (("null root node returned by uiautomator",), "ui-root-unavailable"),
    )
    return next((label for phrases, label in signatures if any(phrase in text for phrase in phrases)),
                "nonzero-exit" if text.strip() else "empty-output")


def run(command, timeout=45, check=True, input_data=None):
    command = [str(value) for value in command]
    operation = command_operation(command)
    try:
        result = subprocess.run(command, input=input_data,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired:
        if not check:
            raise
        raise CommandFailed(operation, 124, "timeout") from None
    if check and result.returncode:
        raise CommandFailed(operation, result.returncode, command_failure_classification(result.stdout))
    return result


def local_database(path="users"):
    return B07Fixture(B07_NAMESPACE).database(path)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def notification_records(text):
    """Parse active records only; never store dumps containing private identifiers.

    AOSP NotificationManagerService separates Notification List from mArchive.
    NotificationRecord exposes mCreationTimeMs, vis and title/text extras.
    Fail closed if this API36 image's diagnostic format differs.
    """
    if "  Notification List:" not in text:
        return []
    active = text.split("  Notification List:", 1)[1].split("  mArchive=", 1)[0]
    records = []
    for match in re.finditer(r"(?ms)^\s{4}NotificationRecord\(.*?(?=^\s{4}NotificationRecord\(|\Z)", active):
        block = match.group()
        if not re.search(r"\bpkg=" + re.escape(PACKAGE) + r"\s", block.splitlines()[0]):
            continue
        record_id = re.search(r"\bid=(-?\d+)\b", block.splitlines()[0])
        stamp = re.search(r"\bmCreationTimeMs=(\d+)", block)
        require(record_id is not None and stamp is not None, "Cannot inspect the active reminder notification safely.")
        records.append({
            "notificationId": int(record_id.group(1)), "postTime": int(stamp.group(1)),
            "genericContent": bool(re.search(r"android\.title=\w+ \(" + re.escape(TITLE) + r"\)", block)
                                   and re.search(r"android\.text=\w+ \(" + re.escape(BODY) + r"\)", block)),
            "privateVisibility": bool(re.search(r"(?m)^\s+vis=0\s*$", block)),
        })
    return records


def main():
    global B07_NAMESPACE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", nargs="?", default="emulator-5554")
    parser.add_argument("--namespace", default="demo-studypath-b07-reminders-" + uuid.uuid4().hex[:8])
    parser.add_argument("--skip-build", action="store_true", help="Use frozen app/test APKs built for this exact isolated namespace.")
    args = parser.parse_args()
    B07_NAMESPACE = args.namespace
    mock = B07Fixture(B07_NAMESPACE)
    run_token = uuid.uuid4().hex
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Android/Sdk"))))
    adb = os.environ.get("STUDYPATH_ADB") or shutil.which("adb") or str(sdk / "platform-tools/adb")
    gradle = os.environ.get("STUDYPATH_GRADLE", str(ROOT / "android/gradlew"))
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    lock = OUT_ROOT / ".runner-lock"
    try:
        lock.mkdir()
    except FileExistsError:
        print("Another B07.3 run owns the evidence lock.", file=sys.stderr)
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
        "avd": "StudyPath_Lite_Test", "serial": args.serial, "firebaseProject": "demo-studypath",
        "namespace": B07_NAMESPACE, "runToken": run_token, "mockOnly": True, "realApprovalEstablished": False,
        "localOnly": True, "dataCleared": False, "packagesUninstalled": False,
        "clocksChanged": False, "jobsForced": False, "stages": [],
        "limits": ["Bounded API36 emulator notification evidence; delivery is approximate.",
                   "Ordinary background am kill is distinct from force-stop and long-term retention.",
                   "Runtime permission prompt is explicitly requested by test code; no app-initiated prompt is claimed.",
                   "B07.4 actual 48-hour offline retention and B08 cloud acceptance remain separate."],
    }
    original_permission = None
    original_appop = None
    original_radios = None
    original_reverses = None
    settings_touched = False
    known_private = set()
    prior_fixture = None
    baseline_private = None
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
                "The reminder fixture format is missing or incompatible.")
        require(value.get("namespace") == B07_NAMESPACE and value.get("runToken") == run_token,
                "The reminder fixture belongs to another isolated namespace or invocation.")
        mock.claim_uid(value["uid"])
        for key in ("uid", "email"):
            if isinstance(value.get(key), str):
                known_private.add(value[key])
        return value

    def claim_setup_identity():
        setup = shell("run-as", PACKAGE, "cat", SETUP_IDENTITY, check=False)
        if setup.returncode != 0:
            return
        identity = json.loads(setup.stdout)
        require(identity.get("namespace") == B07_NAMESPACE and identity.get("runToken") == run_token,
                "The setup cleanup marker belongs to another invocation.")
        email = identity.get("email")
        require(isinstance(email, str) and re.fullmatch(r"reminders_[a-f0-9]{32}@studypath.invalid", email),
                "The setup cleanup marker must identify the owned synthetic reminder account.")
        owned = [row for row in mock.auth_users() if row.get("email") == email]
        require(len(owned) <= 1, "The owned synthetic email unexpectedly matched multiple accounts.")
        if owned:
            uid = owned[0]["localId"]
            require(identity.get("uid") in (None, uid), "The setup account identity changed.")
            mock.claim_uid(uid)
            known_private.update((uid, email))

    def redact(output):
        text = output.decode(errors="replace") if isinstance(output, bytes) else output
        for private in sorted(known_private, key=len, reverse=True):
            text = text.replace(private, "<redacted-synthetic-identity>")
        text = re.sub(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+", "<redacted-email>", text)
        return re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*\b", "<redacted-token>", text)

    def permission_state():
        text = shell("dumpsys", "package", PACKAGE).stdout.decode(errors="replace")
        match = re.search(r"android\.permission\.POST_NOTIFICATIONS:\s+granted=(true|false),\s*flags=\[([^\]]*)\]", text)
        require(match is not None, "Cannot inspect the notification runtime permission safely.")
        flags = []
        for flag in ("USER_SET", "USER_FIXED"):
            if re.search(r"\b" + flag + r"\b", match.group(2)):
                flags.append(flag.lower().replace("_", "-"))
        return {"granted": match.group(1) == "true", "userFlags": flags}

    def appop_mode():
        text = shell("cmd", "appops", "get", "--user", "0", PACKAGE, "POST_NOTIFICATION").stdout.decode()
        match = re.search(r"POST_NOTIFICATION:\s*(allow|ignore|deny|default|foreground)\b", text)
        if match:
            return match.group(1)
        require("No operations" in text, "Cannot preserve the notification app-op mode safely.")
        return "default"

    def radios():
        values = {}
        for key in ("airplane_mode_on", "wifi_on", "mobile_data"):
            value = shell("settings", "get", "global", key).stdout.strip()
            require(value in (b"0", b"1"), "Cannot preserve a guest radio setting safely.")
            values[key] = int(value)
        return values

    def reverses():
        values = {}
        for row in device("reverse", "--list").stdout.decode().splitlines():
            if not row.strip():
                continue
            parts = row.split()
            require(len(parts) == 3, "Cannot preserve existing ADB reverse mappings safely.")
            values[parts[1]] = parts[2]
        return values

    def notification_dump():
        return shell("dumpsys", "notification", "--noredact", "--package", PACKAGE).stdout.decode(errors="replace")

    def channel_importance():
        values = re.findall(r"NotificationChannel\{mId='study'[^\n]*?mImportance=(\d+)", notification_dump())
        return int(values[-1]) if values else None

    def hierarchy(host_step=None):
        remote = "/sdcard/StudyPathReminderHost.xml"
        try:
            if host_step is not None:
                receipt["hostStep"] = host_step + "Dump"
            shell("uiautomator", "dump", "--compressed", remote, timeout=30)
            if host_step is not None:
                receipt["hostStep"] = host_step + "Read"
            return ET.fromstring(shell("cat", remote).stdout)
        finally:
            shell("rm", "-f", remote, check=False)

    def tap_node(node):
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        require(bounds is not None, "The Android control has no usable bounds.")
        left, top, right, bottom = map(int, bounds.groups())
        require(right > left and bottom > top, "The Android control is not visibly tappable.")
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def set_app_notifications_from_settings(enabled):
        """Exercise Android's actual app-level toggle outside instrumentation.

        On API36 this can revoke POST_NOTIFICATIONS and kill the target process.
        It is a separate user flow from denying the first runtime prompt, rather
        than a claim that runtime permission and app blocking are independent.
        """
        shell("am", "start", "-a", "android.settings.APP_NOTIFICATION_SETTINGS", "--es",
              "android.provider.extra.APP_PACKAGE", PACKAGE)
        deadline = time.monotonic() + 20
        while True:
            require(time.monotonic() < deadline, "The app notification master switch was not available in Android settings.")
            tree = hierarchy()
            parents = {child: parent for parent in tree.iter() for child in parent}
            labels = [node for node in tree.iter("node")
                      if node.attrib.get("package") == "com.android.settings"
                      and node.attrib.get("text") in ("Allow notifications", "Show notifications", "All StudyPath notifications")]
            candidates = set()
            for label in labels:
                container = label
                for _ in range(4):
                    switches = [node for node in container.iter("node")
                                if node.attrib.get("package") == "com.android.settings"
                                and node.attrib.get("checkable") == "true"
                                and (node.attrib.get("resource-id", "").endswith("/switch_widget")
                                     or node.attrib.get("class", "").endswith("Switch"))]
                    if len(switches) == 1:
                        candidates.add(switches[0])
                        break
                    container = parents.get(container)
                    if container is None:
                        break
            if len(candidates) == 1:
                master = next(iter(candidates))
                checked = master.attrib.get("checked") == "true"
                if checked != enabled:
                    tap_node(master)
                break
            time.sleep(.2)
        deadline = time.monotonic() + 10
        while permission_state()["granted"] != enabled:
            require(time.monotonic() < deadline, "The actual Android app-level notification switch did not establish the required state.")
            time.sleep(.2)
        receipt["appSettingsToggle" + ("OnObserved" if enabled else "OffObserved")] = True
        # SubSettings can reuse its fragment for a later settings intent.
        # Finish setup before a stage opens a different notification page.
        for _ in range(4):
            receipt["hostStep"] = "appSettingsExitBack"
            shell("input", "keyevent", "KEYCODE_BACK")
            tree = hierarchy(host_step="appSettingsExit")
            if not any(node.attrib.get("package") == "com.android.settings" for node in tree.iter("node")):
                receipt.pop("hostStep", None)
                return
        raise CheckFailed("Android notification setup did not return from Settings.")

    def restore_channel_if_blocked():
        # A blocked channel cannot be restored by createNotificationChannel().
        # Use only the actual Android settings control on this disposable AVD.
        if channel_importance() != 0:
            return
        shell("pm", "grant", PACKAGE, PERMISSION)
        shell("am", "start", "-a", "android.settings.CHANNEL_NOTIFICATION_SETTINGS", "--es",
              "android.provider.extra.APP_PACKAGE", PACKAGE, "--es", "android.provider.extra.CHANNEL_ID", "study")
        for _ in range(8):
            nodes = [node for node in hierarchy().iter("node")
                     if node.attrib.get("package") == "com.android.settings"
                     and node.attrib.get("checkable") == "true"
                     and node.attrib.get("checked") == "false"
                     and (node.attrib.get("resource-id", "").endswith("/switch_widget")
                          or node.attrib.get("class", "").endswith("Switch"))]
            if len(nodes) == 1:
                tap_node(nodes[0])
                time.sleep(.3)
                break
            time.sleep(.25)
        require(channel_importance() != 0, "The blocked study channel could not be restored through Android settings.")

    def restore_settings():
        errors = []
        checks = receipt["restorationChecks"] = []

        def attempt(name, action):
            item = {"name": name, "status": "FAILED"}
            checks.append(item)
            try:
                result = action()
            except Exception as error:
                item["failure"] = error.details if isinstance(error, CommandFailed) else {
                    "classification": "assertion-failed" if isinstance(error, CheckFailed) else "host-error"}
                errors.append(item)
                return None
            item["status"] = "PASSED"
            return result

        def verify(name, key, read, expected, message):
            def check():
                actual = read()
                receipt[key] = actual
                require(actual == expected, message)
            attempt(name, check)

        if original_permission is not None and settings_touched:
            attempt("restoreStudyChannel", restore_channel_if_blocked)
            attempt("restoreRuntimePermission", lambda: shell("pm", "grant" if original_permission["granted"] else "revoke", PACKAGE, PERMISSION))
            attempt("clearRuntimePermissionFlags", lambda: shell("pm", "clear-permission-flags", PACKAGE, PERMISSION, "user-set", "user-fixed"))
            if original_permission["userFlags"]:
                attempt("restoreRuntimePermissionFlags", lambda: shell("pm", "set-permission-flags", PACKAGE, PERMISSION, *original_permission["userFlags"]))
        if original_appop is not None and settings_touched:
            attempt("restoreNotificationAppOp", lambda: shell("cmd", "appops", "set", "--user", "0", PACKAGE, "POST_NOTIFICATION", original_appop))
        if original_reverses is not None:
            current = attempt("readReverseMappingsForRestore", reverses)
            for remote in ("tcp:9099", "tcp:9000"):
                if current is None or remote in current:
                    attempt("removeTestReverse" + remote.split(":")[1], lambda remote=remote: device("reverse", "--remove", remote))
                if remote in original_reverses:
                    attempt("restoreReverse" + remote.split(":")[1], lambda remote=remote: device("reverse", remote, original_reverses[remote]))
        # No case changes networking, but verify the original guest state remains intact.
        if original_radios is not None:
            verify("verifyGuestRadios", "transportAfter", radios, original_radios,
                   "Guest radio settings changed during reminder tests.")
        if original_permission is not None:
            verify("verifyRuntimePermission", "notificationPermissionAfter", permission_state, original_permission,
                   "Original runtime permission state was not restored.")
        if original_appop is not None:
            verify("verifyNotificationAppOp", "notificationAppOpAfter", appop_mode, original_appop,
                   "Original notification app-op was not restored.")
        if original_reverses is not None:
            def verify_reverses():
                actual = reverses()
                require(all(actual.get(key) == original_reverses.get(key) for key in ("tcp:9099", "tcp:9000")),
                        "Original affected ADB reverse mappings were not restored.")
            attempt("verifyReverseMappings", verify_reverses)
        require(not errors, "Some original notification or routing settings could not be restored.")

    def fresh_install():
        if not shell("pm", "path", PACKAGE, check=False).stdout.strip():
            return
        check = shell("run-as", PACKAGE, "sh", "-c",
                      "'if [ -f shared_prefs/workspace.xml ]; then grep -q boundUid shared_prefs/workspace.xml; case $? in 1) exit 0;; *) exit 17;; esac; fi'", check=False)
        require(check.returncode == 0, "The workspace is bound or cannot be inspected; restart the read-only test AVD. No data was cleared.")
        require(shell("run-as", PACKAGE, "test", "-e", FIXTURE, check=False).returncode != 0,
                "A prior reminder fixture exists; restart the read-only AVD.")
        require(shell("run-as", PACKAGE, "test", "-e", SETUP_IDENTITY, check=False).returncode != 0,
                "A prior reminder setup marker exists; restart the read-only AVD.")

    def force_stop_for_stage():
        shell("am", "force-stop", PACKAGE)
        for _ in range(40):
            if not shell("pidof", PACKAGE, check=False).stdout.strip():
                return
            time.sleep(.1)
        raise CheckFailed("The previous stage process did not stop.")

    def selected_stage(name, stop_before=True):
        nonlocal prior_fixture
        receipt["lastStage"] = name
        if stop_before:
            force_stop_for_stage()
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        print("B07.3:", name, flush=True)
        command = [adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                   TEST_CLASS + "#" + name, "-e", "b07Namespace", B07_NAMESPACE,
                   "-e", "b07RunToken", run_token, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        timed_out = False
        try:
            result = run(command, timeout=240, check=False)
            output, code = result.stdout, result.returncode
        except subprocess.TimeoutExpired as error:
            output, code, timed_out = error.stdout or b"", 124, True
        value = None
        try:
            value = fixture()
        except Exception:
            pass
        text = redact(output)
        log_name = name + ".log"
        (out / log_name).write_text(text + ("\nHost instrumentation timeout.\n" if timed_out else ""))
        item = {"name": name, "exitCode": code, "timedOut": timed_out,
                "hostForceStopBeforeStage": stop_before, "log": log_name, "command": command}
        receipt["stages"].append(item)
        require(code == 0 and re.search(r"^OK \(1 test\)\s*$", text, re.M)
                and not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]", text),
                "Instrumentation did not report explicit success: " + name)
        require(value is not None and value.get("stages", {}).get(name) == "passed", "The fixture did not confirm the selected stage.")
        require(isinstance(value.get("uid"), str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value["uid"]), "The synthetic account identity is invalid.")
        if prior_fixture is not None:
            require(all(value.get(key) == prior_fixture.get(key) for key in ("uid", "email", "namespace", "runToken", "readingId", "retargetReadingId")),
                    "The original bound identity or acknowledged reading sources changed.")
            if "background" in prior_fixture:
                require(value.get("background") == prior_fixture["background"], "The prepared background reminder changed after handoff.")
            previous_deliveries = prior_fixture.get("deliveryObservations", [])
            require(value.get("deliveryObservations", [])[:len(previous_deliveries)] == previous_deliveries,
                    "A previously observed delivery was changed or discarded.")
        pid = value.get("stagePids", {}).get(name)
        require(type(pid) is int and pid > 0, "The selected stage did not identify its process.")
        item.update(junit="OK (1 test)", fixtureConfirmed=True, pid=pid)
        observations = value.get("observations", {})
        require(isinstance(observations, dict), "Reminder observations must be an object.")
        required = [flag for completed in STAGES[:STAGES.index(name) + 1] for flag in STAGE_OBSERVATIONS[completed]]
        require(all(observations.get(flag) is True for flag in required), "A required observed reminder assertion is missing or false.")
        receipt["observations"] = {key: val for key, val in observations.items()
                                   if isinstance(key, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", key)
                                   and (type(val) is bool or type(val) is int and 0 <= val <= 1_000_000)}
        receipt["deliveries"] = []
        for delivery in value.get("deliveryObservations", []):
            require(isinstance(delivery, dict), "A delivery observation is invalid.")
            safe = {key: delivery[key] for key in ("requestedAt", "dueAt", "observedAt", "notificationPostTime", "pid", "notificationId")
                    if type(delivery.get(key)) is int}
            require(all(key in safe for key in ("requestedAt", "dueAt", "observedAt", "notificationPostTime", "pid")),
                    "An actual delivery observation lacks timing/process evidence.")
            require(safe["requestedAt"] <= safe["dueAt"] <= safe["notificationPostTime"] <= safe["observedAt"],
                    "A delivery was reported before its scheduled time or observed out of order.")
            safe["delayMillis"] = safe["notificationPostTime"] - safe["dueAt"]
            case = delivery.get("case", "")
            require(isinstance(case, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,48}", case), "The delivery case label is invalid.")
            safe["case"] = case
            receipt["deliveries"].append(safe)
        prior_fixture = value
        return value

    def check_server(value, others_before):
        nonlocal baseline_private
        data = local_database("users/" + value["uid"])
        require(isinstance(data, dict), "The original account's server records are missing.")
        expected = value.get("expectedReminders")
        require(isinstance(expected, dict), "The fixture must specify its complete expected reminder collection.")
        reminders = data.get("reminders") or {}
        require(set(reminders) == set(expected), "Server reminder records are missing, duplicated or unexpectedly retained.")
        for key, record in reminders.items():
            require({field: val for field, val in record.items() if field != "updatedAt"} ==
                    {field: val for field, val in expected[key].items() if field != "updatedAt"},
                    "A reminder's acknowledged server payload differs from the expected state.")
            stamp = record.get("updatedAt")
            require(type(stamp) in (int, float) and started.timestamp() * 1000 - 60000 <= stamp <= time.time() * 1000 + 60000,
                    "A reminder server timestamp is unresolved or outside this test run.")
        private = {key: val for key, val in data.items() if key != "reminders"}
        if baseline_private is None:
            baseline_private = digest(private)
        require(digest(private) == baseline_private, "A reminder case changed unrelated private application records.")
        others = local_database() or {}
        others.pop(value["uid"], None)
        require(digest(others) == digest(others_before), "Another account's application records changed.")

    def foreground_main(any_app_activity=False, timeout=45):
        text = shell("dumpsys", "activity", "activities", timeout=timeout).stdout.decode(errors="replace")
        component = PACKAGE + "/" if any_app_activity else PACKAGE + "/.ui.MainActivity"
        return any(("mResumedActivity" in line or "topResumedActivity" in line)
                   and component in line for line in text.splitlines())

    def background_reclaim(value):
        receipt["lastStage"] = "ordinaryBackgroundReclaim"
        background = value.get("background")
        require(isinstance(background, dict) and all(key in background for key in
                ("reminderId", "workId", "dueAt", "notificationId", "requestedAt")), "The background reminder fixture is incomplete.")
        require(background["dueAt"] - int(time.time() * 1000) >= 30_000,
                "The background reminder does not leave enough real time for ordinary process reclaim.")
        shell("am", "start", "-n", PACKAGE + "/.ui.auth.WelcomeActivity")
        deadline = time.monotonic() + 20
        while not foreground_main():
            require(time.monotonic() < deadline, "The authenticated app did not open before background reclaim.")
            time.sleep(.2)
        previous = shell("pidof", PACKAGE).stdout.strip().split()
        require(len(previous) == 1, "Cannot identify one normal application process before reclaim.")
        prior_pid = int(previous[0])
        require(not notification_records(notification_dump()), "The background case must begin without active StudyPath notifications.")
        shell("input", "keyevent", "KEYCODE_HOME")
        # API36 am kill skips processes below SERVICE_ADJ (500), even when the
        # command returns zero. Observe the HOME transition before one kill.
        preparation = receipt["backgroundReclaimPreparation"] = {
            "priorPid": prior_pid, "eligibilityObserved": False, "observations": []}
        transition_started = time.monotonic()
        deadline = transition_started + 10
        while True:
            require(time.monotonic() < deadline,
                    "The app did not reach an observed background priority eligible for ordinary am kill.")
            app_resumed = foreground_main(any_app_activity=True, timeout=max(.1, deadline - time.monotonic()))
            priority = shell("cat", f"/proc/{prior_pid}/oom_score_adj", check=False,
                             timeout=max(.1, deadline - time.monotonic()))
            raw_priority = priority.stdout.strip()
            adj = int(raw_priority) if priority.returncode == 0 and re.fullmatch(rb"-?\d{1,4}", raw_priority) else None
            if adj is not None and not -1000 <= adj <= 1000:
                adj = None
            preparation["observations"].append({
                "elapsedMillis": int((time.monotonic() - transition_started) * 1000),
                "appActivityResumed": app_resumed, "oomScoreAdj": adj,
                "priorityReadSucceeded": priority.returncode == 0})
            require(time.monotonic() < deadline,
                    "The background priority observation exceeded its bounded eligibility window.")
            if not app_resumed and adj is not None and adj >= 500:
                preparation.update(eligibilityObserved=True,
                                   remainingBeforeDueMillis=background["dueAt"] - int(time.time() * 1000))
                require(preparation["remainingBeforeDueMillis"] >= 30_000,
                        "Observed background eligibility left less than 30 seconds before the reminder due time.")
                break
            time.sleep(.2)
        preparation["ordinaryKillAttempted"] = True
        shell("am", "kill", "--user", "0", PACKAGE)
        deadline = time.monotonic() + 10
        while shell("pidof", PACKAGE, check=False).stdout.strip():
            require(time.monotonic() < deadline, "Ordinary am kill did not reclaim the background process; force-stop is forbidden for this case.")
            time.sleep(.2)
        receipt["backgroundProcessReclaimed"] = {"priorPid": prior_pid, "ordinaryAmKill": True,
                                                  "forceStopped": False, "processAbsenceObserved": True,
                                                  "commands": [["input", "keyevent", "KEYCODE_HOME"],
                                                               ["am", "kill", "--user", "0", PACKAGE]]}
        worker_pid = None
        deadline = time.monotonic() + max(60, (background["dueAt"] - int(time.time() * 1000)) / 1000 + 180)
        first = None
        while first is None:
            require(time.monotonic() < deadline, "Android did not naturally deliver the background reminder within the bounded observation window.")
            pids = shell("pidof", PACKAGE, check=False).stdout.strip().split()
            if pids:
                require(len(pids) == 1 and int(pids[0]) != prior_pid, "The background process identity was not replaced.")
                worker_pid = int(pids[0])
            records = notification_records(notification_dump())
            if records:
                require(len(records) == 1 and records[0]["notificationId"] == background["notificationId"],
                        "The background case posted unexpected or multiple active notifications.")
                first = records[0]
                require(worker_pid is not None, "The background worker process was not independently observed.")
                require(first["genericContent"] and first["privateVisibility"] and first["postTime"] >= background["dueAt"],
                        "The background notification's timing/content/privacy is incorrect.")
                observed_at = int(time.time() * 1000)
            else:
                time.sleep(.4)
        time.sleep(1.5)
        require(notification_records(notification_dump()) == [first], "The background notification changed or duplicated before its tap.")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("cmd", "statusbar", "expand-notifications")
        deadline = time.monotonic() + 15
        tapped = False
        while not tapped:
            require(time.monotonic() < deadline, "The actual reminder notification was not found in the Android shade.")
            nodes = [node for node in hierarchy().iter("node") if node.attrib.get("text") == TITLE]
            if len(nodes) == 1:
                tap_node(nodes[0])
                tapped = True
                tapped_at = int(time.time() * 1000)
            else:
                time.sleep(.2)
        deadline = time.monotonic() + 15
        while not foreground_main():
            require(time.monotonic() < deadline, "Tapping the real reminder did not open the authenticated main activity.")
            time.sleep(.2)
        proof = {"schemaVersion": 1, "backgroundRecovered": True, "priorPid": prior_pid,
                 "workerPid": worker_pid, "dueAt": background["dueAt"], "notificationId": background["notificationId"],
                 "postTime": first["postTime"], "observedAt": observed_at, "tappedAt": tapped_at,
                 "mainActivityOpened": True, "ordinaryAmKill": True, "forceStopped": False,
                 "genericContent": True, "privateVisibility": True, "notificationCount": 1}
        # Only safe process/timing facts go into this separate private proof. No account IDs.
        device("shell", "-T", "run-as", PACKAGE, "tee", PROOF,
               input_data=json.dumps(proof, separators=(",", ":")).encode())
        require(json.loads(shell("run-as", PACKAGE, "cat", PROOF).stdout) == proof, "The background proof was not retained correctly.")
        receipt["backgroundProof"] = proof
        receipt["backgroundProofObservedBeforeFinalInstrumentation"] = True

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            old_handlers[signum] = signal.signal(signum, interrupted)
        require(re.fullmatch(r"emulator-\d+", args.serial), "Use an Android emulator serial.")
        require(Path(adb).is_file() or shutil.which(adb), "The ADB executable is unavailable.")
        avd = device("emu", "avd", "name").stdout.decode().splitlines()
        require(avd and avd[0].strip() == "StudyPath_Lite_Test", "Only the dedicated test AVD is allowed.")
        port = args.serial.split("-")[1]
        verified = False
        for proc in Path("/proc").glob("[0-9]*/cmdline"):
            try:
                tokens = proc.read_bytes().decode().strip("\0").split("\0")
                if tokens and Path(tokens[0]).name.startswith(("qemu-system-", "emulator")):
                    verified |= ("-avd" in tokens and tokens[tokens.index("-avd") + 1] == "StudyPath_Lite_Test"
                                 and "-port" in tokens and tokens[tokens.index("-port") + 1] == port and "-read-only" in tokens)
            except (OSError, UnicodeError, IndexError):
                pass
        require(verified, "Cannot verify the selected test AVD is running read-only.")
        require(shell("getprop", "sys.boot_completed").stdout.strip() == b"1", "Wait for Android to finish booting.")
        require(shell("getprop", "ro.build.version.sdk").stdout.strip() == b"36", "This settings/notification acceptance runner targets the API36 test image.")
        receipt["mockFixture"] = mock.prepare(out)
        catalog = local_database("catalog")
        require(isinstance(catalog, dict) and catalog.get("curriculumVersion") == "bsit_demo_v1"
                and catalog.get("approved") is False and catalog.get("modules", {}).get("programming_basics"),
                "The isolated synthetic reminder catalog is incomplete.")
        with urllib.request.urlopen("http://127.0.0.1:9099/", timeout=5):
            pass
        fresh_install()
        receipt["lastStage"] = "build"
        flags = ["--offline", "--no-daemon", "--console=plain", "--max-workers=2", "--no-parallel",
                 "-PfirebaseUseEmulators=true", "-PfirebaseProjectId=demo-studypath",
                 "-PfirebaseEmulatorHost=10.0.2.2", "-PfirebaseApiKey=AIzaSyDemoStudyPathNotARealAPIKey000000",
                 "-PfirebaseAppId=1:1234567890:android:0123456789abcdef",
                 "-PfirebaseDatabaseUrl=https://" + B07_NAMESPACE + ".firebaseio.com"]
        command = ["flock", str(ROOT / ".build/v1-fix-gradle.lock"), gradle, *flags, ":app:assembleDebug", ":app:assembleDebugAndroidTest"]
        receipt["buildCommand"] = command
        receipt["buildSkipped"] = args.skip_build
        if not args.skip_build:
            (ROOT / ".build").mkdir(exist_ok=True)
            with (out / "build.log").open("wb") as log:
                result = subprocess.run(command, cwd=ROOT / "android", stdout=log, stderr=subprocess.STDOUT, timeout=360)
            receipt["buildExitCode"] = result.returncode
            require(result.returncode == 0, "The build failed; inspect build.log.")
        apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
        test_apk = ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        receipt.update(apkSha256=file_digest(apk), testApkSha256=file_digest(test_apk),
                       testSourceSha256=file_digest(ROOT / "android/app/src/androidTest/java/com/studypath/app/ReminderDeliveryTest.kt"),
                       runnerSha256=file_digest(Path(__file__)))
        receipt["fixtureSourceSha256"] = {str(path.relative_to(ROOT)): file_digest(path) for path in (
            ROOT / "android/b07_fixture.py", ROOT / "android/test-fixtures/b07-policy-fixture.mjs",
            ROOT / "backend/academics/policy-fixture.mjs", ROOT / "backend/database.rules.json")}
        receipt["lastStage"] = "install"
        with (out / "install.log").open("w") as log:
            for artifact in (apk, test_apk):
                result = device("install", "-r", artifact, check=False)
                log.write(redact(result.stdout))
                require(result.returncode == 0, "APK installation failed; inspect install.log.")
        fresh_install()
        original_permission = permission_state()
        original_appop = appop_mode()
        original_radios = radios()
        original_reverses = reverses()
        receipt.update(notificationPermissionBefore=original_permission, notificationAppOpBefore=original_appop,
                       transportBefore=original_radios)
        receipt["studyChannelImportanceBefore"] = channel_importance()
        require(not original_permission["granted"] and not original_permission["userFlags"],
                "The actual runtime prompt requires a fresh, untouched notification permission; restart the test AVD.")
        require(not original_radios["airplane_mode_on"] and (original_radios["wifi_on"] or original_radios["mobile_data"]),
                "Start this online reminder suite with guest networking enabled.")
        for remote in ("tcp:9099", "tcp:9000"):
            device("reverse", remote, remote)
        others_before = local_database() or {}
        settings_touched = True
        value = selected_stage(STAGES[0])
        require(value["uid"] not in others_before, "The synthetic account was not newly created for this run.")
        check_server(value, others_before)
        shell("pm", "grant", PACKAGE, PERMISSION)
        require(permission_state()["granted"], "Notification permission was not enabled before the app-settings toggle.")
        set_app_notifications_from_settings(False)
        receipt["appLevelBlockObserved"] = True
        value = selected_stage(STAGES[1])
        check_server(value, others_before)
        set_app_notifications_from_settings(True)
        for name in STAGES[2:8]:
            value = selected_stage(name)
            check_server(value, others_before)
            if name == STAGES[2]:
                require(channel_importance() not in (None, 0), "The study channel was not actually restored after its blocked-state case.")
        background_reclaim(value)
        value = selected_stage(STAGES[8], stop_before=False)
        check_server(value, others_before)
        require(all(value.get("stages", {}).get(name) == "passed" for name in STAGES), "Not all nine reminder cases passed.")
        require([entry["case"] for entry in receipt["deliveries"]] ==
                ["allowed-ui", "controlled-overdue-component", "background-ordinary-am-kill"],
                "The three distinct real-delivery observations are incomplete or duplicated.")
        receipt.update(status="PASSED", lastStage="complete", otherAccountsUnchanged=True,
                       unrelatedPrivateRecordsUnchanged=True, serverRemindersVerified=True)
    except RunInterrupted as error:
        failure_code = error.exit_code
        receipt["failure"] = "Interrupted; original guest settings restoration was attempted."
        print("B07.3 interrupted.", file=sys.stderr)
    except Exception as error:
        failure_code = 1
        receipt["failure"] = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        if isinstance(error, CommandFailed):
            receipt["commandFailure"] = error.details
        print("B07.3 did not pass:", receipt["failure"], file=sys.stderr)
    finally:
        for signum in old_handlers:
            signal.signal(signum, signal.SIG_IGN)
        try:
            restore_settings()
            receipt["settingsRestored"] = True
        except Exception as error:
            receipt.update(settingsRestored=False, restoreError=type(error).__name__)
            failure_code = failure_code or 1
        try:
            if mock.owned:
                # Stop test-owned observers/work before deleting only the owned namespace/account.
                force_stop_for_stage()
                claim_setup_identity()
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
    if not failure_code:
        print("B07.3 reminders passed: nine cases; actual Android settings; ordinary background recovery.")
    return failure_code


if __name__ == "__main__":
    sys.exit(main())
