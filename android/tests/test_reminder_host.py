"""Host-only regressions for B07.3 diagnostics and independent restoration.

ADB is a mocked process boundary. Nested runner functions are compiled from the
actual source so these checks exercise the existing orchestration without
starting an emulator, building, or touching a guest account.
"""
import ast
import importlib.util
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


RUNNER_PATH = Path(__file__).resolve().parents[1] / "test-reminders.py"
SPEC = importlib.util.spec_from_file_location("reminder_host", RUNNER_PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def nested_function(name, bindings):
    source = ast.parse(RUNNER_PATH.read_text())
    main = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == "main")
    function = next(node for node in main.body if isinstance(node, ast.FunctionDef) and node.name == name)
    namespace = dict(vars(runner), **bindings)
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(RUNNER_PATH), "exec"), namespace)
    return namespace[name]


class CommandDiagnosticsTest(unittest.TestCase):
    def test_command_failure_records_only_allowlisted_operation_code_and_classification(self):
        private = "synthetic-private-uid person@example.test eyJprivate.token.signature"
        commands = [
            (["shell", "input", "keyevent", "KEYCODE_BACK"], "ui.back"),
            (["shell", "uiautomator", "dump", "--compressed", "/private/" + private], "ui.hierarchy.dump"),
            (["shell", "cat", "/sdcard/StudyPathReminderHost.xml"], "ui.hierarchy.read"),
            (["shell", "-T", "run-as", runner.PACKAGE, "tee", runner.PROOF], "app-private.write"),
            (["shell", "unknown-" + private], "device.command"),
        ]
        for values, operation in commands:
            with self.subTest(operation=operation), patch.object(runner.subprocess, "run", return_value=
                    subprocess.CompletedProcess([], 17, ("Permission denied " + private).encode())):
                with self.assertRaises(runner.CheckFailed) as failure:
                    runner.run(["adb", "-s", "emulator-" + private, *values])
                details = getattr(failure.exception, "details", None)
                self.assertEqual({"operation": operation, "exitCode": 17,
                                  "classification": "permission-denied"}, details)
                self.assertIn(operation, str(failure.exception))
                self.assertIn("17", str(failure.exception))
                self.assertNotIn(private, str(failure.exception) + str(details))

    def test_timeout_is_sanitized_without_changing_unchecked_timeout_handling(self):
        command = ["adb", "-s", "private-serial", "shell", "uiautomator", "dump"]
        error = subprocess.TimeoutExpired(command, 30, output=b"private-uid person@example.test")
        with patch.object(runner.subprocess, "run", side_effect=error):
            try:
                runner.run(command)
            except Exception as failure:
                self.assertIsInstance(failure, runner.CheckFailed)
                self.assertEqual({"operation": "ui.hierarchy.dump", "exitCode": 124,
                                  "classification": "timeout"}, getattr(failure, "details", None))
            else:
                self.fail("The timed out command was accepted")
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.run(command, check=False)


class SettingsExitDiagnosticsTest(unittest.TestCase):
    def test_each_settings_exit_command_retains_its_host_step_on_failure(self):
        tree = ET.fromstring('<hierarchy><node package="com.android.settings" text="Allow notifications" '
                             'checkable="true" checked="true" class="android.widget.Switch" /></hierarchy>')
        for failing in ("back", "dump", "read"):
            with self.subTest(command=failing):
                receipt = {}
                hierarchy_calls = 0

                def shell(*values, **_kwargs):
                    if values[:2] == ("input", "keyevent") and failing == "back":
                        raise runner.CheckFailed("simulated device boundary failure")
                    if values[:2] == ("uiautomator", "dump") and failing == "dump":
                        raise runner.CheckFailed("simulated device boundary failure")
                    if values[0] == "cat" and failing == "read":
                        raise runner.CheckFailed("simulated device boundary failure")
                    return subprocess.CompletedProcess([], 0, b"<hierarchy />")

                real_hierarchy = nested_function("hierarchy", {"receipt": receipt, "shell": shell})

                def hierarchy(*args, **kwargs):
                    nonlocal hierarchy_calls
                    hierarchy_calls += 1
                    return tree if hierarchy_calls == 1 else real_hierarchy(*args, **kwargs)

                toggle = nested_function("set_app_notifications_from_settings", {
                    "receipt": receipt, "shell": shell, "hierarchy": hierarchy,
                    "permission_state": lambda: {"granted": True}, "tap_node": lambda _node: None,
                })
                with self.assertRaises(runner.CheckFailed):
                    toggle(True)
                self.assertTrue(receipt["appSettingsToggleOnObserved"])
                self.assertEqual("appSettingsExit" + failing.title(), receipt.get("hostStep"))


class RestorationTest(unittest.TestCase):
    def restore(self, failing_check):
        calls = []
        receipt = {}
        permission = {"granted": False, "userFlags": []}
        radios = {"airplane_mode_on": 0, "wifi_on": 1, "mobile_data": 0}
        reverses = {"tcp:9099": "tcp:9099", "tcp:9000": "tcp:9000"}
        reverse_reads = 0

        def read(name, value):
            calls.append(name)
            if name == failing_check:
                raise runner.CheckFailed("simulated private read failure")
            return value

        def read_reverses():
            nonlocal reverse_reads
            reverse_reads += 1
            return read("routing-restore-read" if reverse_reads == 1 else "routing-verify", reverses)

        def command(*values, **_kwargs):
            calls.append(values)
            return subprocess.CompletedProcess([], 0, b"")

        restore = nested_function("restore_settings", {
            "receipt": receipt, "original_permission": permission, "original_appop": "default",
            "original_radios": radios, "original_reverses": reverses, "settings_touched": True,
            "restore_channel_if_blocked": lambda: calls.append("channel-restore"),
            "shell": command, "device": command, "reverses": read_reverses,
            "radios": lambda: read("radios-verify", radios),
            "permission_state": lambda: read("permission-verify", permission),
            "appop_mode": lambda: read("appop-verify", "default"),
        })
        with self.assertRaises(runner.CheckFailed):
            restore()
        return calls, receipt

    def test_failed_routing_read_does_not_skip_restore_actions_or_later_checks(self):
        calls, receipt = self.restore("routing-restore-read")
        for remote in ("tcp:9099", "tcp:9000"):
            self.assertIn(("reverse", remote, remote), calls)
        self.assertIn("radios-verify", calls)
        self.assertIn("permission-verify", calls)
        self.assertIn("appop-verify", calls)
        self.assertIn("routing-verify", calls)
        self.assertTrue(any(item["status"] == "FAILED" for item in receipt.get("restorationChecks", [])))

    def test_each_failed_verification_does_not_skip_the_remaining_checks(self):
        for failing in ("radios-verify", "permission-verify", "appop-verify", "routing-verify"):
            with self.subTest(failure=failing):
                calls, receipt = self.restore(failing)
                for check in ("radios-verify", "permission-verify", "appop-verify", "routing-verify"):
                    self.assertIn(check, calls)
                checks = receipt.get("restorationChecks", [])
                self.assertEqual(1, sum(item["status"] == "FAILED" for item in checks))


class BackgroundEligibilityTest(unittest.TestCase):
    class KillBoundary(Exception):
        pass

    def reclaim(self, priorities, resumed, due_after_ms=90_000, command_seconds=0):
        receipt, calls = {}, []
        elapsed, reads = 0.0, 0

        def sleep(seconds):
            nonlocal elapsed
            elapsed += seconds

        clock = SimpleNamespace(monotonic=lambda: elapsed, time=lambda: 1000 + elapsed, sleep=sleep)

        def shell(*values, **_kwargs):
            nonlocal reads
            calls.append(values)
            if values[:2] == ("am", "kill"):
                raise self.KillBoundary()
            if values[0] == "pidof":
                return subprocess.CompletedProcess([], 0, b"8123")
            if values[0] == "cat":
                self.assertEqual(("cat", "/proc/8123/oom_score_adj"), values)
                code, output = priorities[min(reads, len(priorities) - 1)]
                reads += 1
                sleep(command_seconds)
                return subprocess.CompletedProcess([], code, output)
            return subprocess.CompletedProcess([], 0, b"")

        def foreground_main(any_app_activity=False, **_kwargs):
            return resumed[min(reads, len(resumed) - 1)] if any_app_activity else True

        reclaim = nested_function("background_reclaim", {
            "receipt": receipt, "shell": shell, "foreground_main": foreground_main,
            "notification_records": lambda _dump: [], "notification_dump": lambda: "",
            "time": clock,
        })
        value = {"background": {"reminderId": "private-synthetic-id", "workId": "private-work-id",
                 "dueAt": 1_000_000 + due_after_ms, "notificationId": 24, "requestedAt": 1_000_000}}
        try:
            reclaim(value)
        except Exception as failure:
            return calls, receipt, failure
        self.fail("The test must stop at the kill boundary or a failed precondition")

    def test_waits_for_observed_0_200_700_transition_before_one_ordinary_kill(self):
        calls, receipt, failure = self.reclaim([(0, b"0"), (0, b"200"), (0, b"700")], [True, False, False])
        self.assertIsInstance(failure, self.KillBoundary)
        self.assertEqual(3, sum(command[0] == "cat" for command in calls))
        self.assertEqual(1, sum(command[:2] == ("am", "kill") for command in calls))
        preparation = receipt.get("backgroundReclaimPreparation", {})
        self.assertTrue(preparation.get("eligibilityObserved"))
        self.assertEqual([0, 200, 700], [item["oomScoreAdj"] for item in preparation.get("observations", [])])
        self.assertFalse(preparation["observations"][-1]["appActivityResumed"])
        self.assertGreaterEqual(preparation["remainingBeforeDueMillis"], 30_000)
        self.assertNotIn("private-", str(preparation))

    def test_resumed_app_activity_blocks_kill_even_with_eligible_priority(self):
        calls, receipt, failure = self.reclaim([(0, b"700")], [True, True, False])
        self.assertIsInstance(failure, self.KillBoundary)
        self.assertEqual(3, sum(command[0] == "cat" for command in calls))
        self.assertEqual([True, True, False], [item["appActivityResumed"] for item in
                         receipt.get("backgroundReclaimPreparation", {}).get("observations", [])])

    def test_missing_invalid_or_ineligible_priority_times_out_before_kill(self):
        for label, priority in (("missing", (1, b"private-read-error")),
                                ("invalid", (0, b"private-invalid-value")),
                                ("ineligible", (0, b"200"))):
            with self.subTest(priority=label):
                calls, receipt, failure = self.reclaim([priority], [False])
                self.assertIsInstance(failure, runner.CheckFailed)
                self.assertFalse(any(command[:2] == ("am", "kill") for command in calls))
                preparation = receipt.get("backgroundReclaimPreparation", {})
                self.assertFalse(preparation.get("eligibilityObserved"))
                self.assertTrue(preparation.get("observations"))
                self.assertLessEqual(preparation["observations"][-1]["elapsedMillis"], 11_000)
                self.assertNotIn("private-", str(preparation))

    def test_due_time_budget_is_rechecked_after_background_observation(self):
        calls, receipt, failure = self.reclaim([(0, b"700")], [False], due_after_ms=30_500, command_seconds=1)
        self.assertIsInstance(failure, runner.CheckFailed)
        self.assertFalse(any(command[:2] == ("am", "kill") for command in calls))
        preparation = receipt.get("backgroundReclaimPreparation", {})
        self.assertTrue(preparation.get("eligibilityObserved"))
        self.assertLess(preparation["remainingBeforeDueMillis"], 30_000)

    def test_observation_finishing_outside_bounded_window_fails_before_kill(self):
        calls, receipt, failure = self.reclaim([(0, b"700")], [False], command_seconds=11)
        self.assertIsInstance(failure, runner.CheckFailed)
        self.assertFalse(any(command[:2] == ("am", "kill") for command in calls))
        self.assertFalse(receipt.get("backgroundReclaimPreparation", {}).get("eligibilityObserved"))


class BackgroundProofTransportTest(unittest.TestCase):
    def reclaim(self, mode="retained"):
        receipt, transfers = {}, []
        elapsed, pid_reads, notification_reads = 0.0, 0, 0
        stored = None

        def sleep(seconds):
            nonlocal elapsed
            elapsed += seconds

        clock = SimpleNamespace(monotonic=lambda: elapsed, time=lambda: 1000 + elapsed, sleep=sleep)
        writer = ("shell", "-T", "run-as", runner.PACKAGE, "tee", runner.PROOF)
        reader = ("shell", "run-as", runner.PACKAGE, "cat", runner.PROOF)

        def adb_process(command, input=None, **_kwargs):
            nonlocal stored
            values = tuple(command[3:])
            transfers.append(values)
            if values == writer:
                if mode == "failed-write":
                    return subprocess.CompletedProcess(command, 1, b"private-write-error person@example.test")
                stored = input
                return subprocess.CompletedProcess(command, 0, input)  # tee echoes; keep it private.
            if values == reader:
                output = b'{"schemaVersion":0}' if mode == "mismatched-readback" else stored
                return subprocess.CompletedProcess(command, 0 if output is not None else 1,
                                                   output or b"private-missing-proof")
            # Reproduce exec-in's observed zero-exit/no-file behavior.
            if values[0] == "exec-in":
                return subprocess.CompletedProcess(command, 0, b"")
            self.fail("Unexpected private transport operation")

        def device(*values, **kwargs):
            return runner.run(["adb", "-s", "emulator-5554", *values], **kwargs)

        def shell(*values, **kwargs):
            nonlocal elapsed, pid_reads
            if values == reader[1:]:
                return device("shell", *values, **kwargs)
            if values[0] == "pidof":
                pid_reads += 1
                if pid_reads == 3:
                    elapsed = 91
                output = {1: b"8123", 2: b""}.get(pid_reads, b"9931")
                return subprocess.CompletedProcess([], 0, output)
            if values[0] == "cat":
                return subprocess.CompletedProcess([], 0, b"700")
            return subprocess.CompletedProcess([], 0, b"")

        record = {"notificationId": 24, "postTime": 1_090_001,
                  "genericContent": True, "privateVisibility": True}

        def notification_records(_dump):
            nonlocal notification_reads
            notification_reads += 1
            return [] if notification_reads == 1 else [record]

        reclaim = nested_function("background_reclaim", {
            "receipt": receipt, "shell": shell, "device": device, "time": clock,
            "foreground_main": lambda any_app_activity=False, **_kwargs: not any_app_activity,
            "notification_records": notification_records, "notification_dump": lambda: "",
            "hierarchy": lambda: ET.fromstring('<hierarchy><node text="' + runner.TITLE + '" /></hierarchy>'),
            "tap_node": lambda _node: None,
        })
        value = {"background": {"reminderId": "private-synthetic-id", "workId": "private-work-id",
                 "dueAt": 1_090_000, "notificationId": 24, "requestedAt": 1_000_000}}
        with patch.object(runner.subprocess, "run", side_effect=adb_process):
            try:
                reclaim(value)
            except Exception as failure:
                return receipt, transfers, stored, failure
        return receipt, transfers, stored, None

    def test_structured_shell_transfers_json_and_verifies_retention_before_accepting(self):
        receipt, transfers, stored, failure = self.reclaim()
        self.assertIsNone(failure)
        self.assertEqual([("shell", "-T", "run-as", runner.PACKAGE, "tee", runner.PROOF),
                          ("shell", "run-as", runner.PACKAGE, "cat", runner.PROOF)], transfers)
        self.assertEqual(receipt["backgroundProof"], json.loads(stored))
        self.assertTrue(receipt["backgroundProofObservedBeforeFinalInstrumentation"])
        self.assertNotIn("private-", str(receipt))

    def test_failed_write_stops_before_readback_and_acceptance(self):
        receipt, transfers, _stored, failure = self.reclaim("failed-write")
        self.assertIsInstance(failure, runner.CheckFailed)
        self.assertEqual("app-private.write", getattr(failure, "details", {}).get("operation"))
        self.assertEqual(1, len(transfers))
        self.assertNotIn("backgroundProof", receipt)
        self.assertNotIn("backgroundProofObservedBeforeFinalInstrumentation", receipt)
        self.assertNotIn("private-write-error", str(failure) + str(receipt))

    def test_mismatched_readback_cannot_accept_the_background_proof(self):
        receipt, _transfers, _stored, failure = self.reclaim("mismatched-readback")
        self.assertIsInstance(failure, runner.CheckFailed)
        self.assertEqual("The background proof was not retained correctly.", str(failure))
        self.assertNotIn("backgroundProof", receipt)
        self.assertNotIn("backgroundProofObservedBeforeFinalInstrumentation", receipt)


if __name__ == "__main__":
    unittest.main()
