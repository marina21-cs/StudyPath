#!/usr/bin/env python3
"""Run one backend stage and retain its actual, narrowly scoped evidence."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PHASE_ROOT = ROOT / "implementation/phases/01-backend"
PLAN = json.loads((PHASE_ROOT / "phase-plan.json").read_text())
PHASES = {phase["id"]: phase for phase in PLAN["phases"]}


def now():
    return datetime.now(timezone.utc).isoformat()


def execute(command, cwd, log, env):
    print(f"  Running {' '.join(map(str, command))}", flush=True)
    with log.open("w") as output:
        process = subprocess.run(command, cwd=cwd, env=env, stdout=output,
                                 stderr=subprocess.STDOUT, timeout=900)
    if process.returncode:
        raise RuntimeError(f"Command exited {process.returncode}; see {log.relative_to(ROOT)}")


def junit_evidence(classes, output):
    reports = ROOT / "android/app/build/test-results/testDebugUnitTest"
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for name in classes:
        source = reports / f"TEST-{name}.xml"
        tree = ET.parse(source).getroot()
        if tree.attrib.get("name") != name:
            raise RuntimeError(f"Unexpected JUnit suite in {source}")
        for key in counts:
            counts[key] += int(tree.attrib.get(key, 0))
        shutil.copyfile(source, output / source.name)
    return counts


def verify(phase, env):
    output = PHASE_ROOT / phase["folder"] / "test-results"
    output.mkdir(parents=True, exist_ok=True)
    result = {
        "phase": phase["id"], "name": phase["name"], "startedAt": now(),
        "scope": "local automated backend gate; device/cloud acceptance is separate",
        "status": "running", "commands": [], "firebase": None, "jvm": None,
        "manualAcceptance": "pending" if phase.get("manualGate") else "tracked in B07/B08"
    }
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    exit_code = 0
    try:
        if phase["npmStage"]:
            npm = shutil.which("npm", path=env.get("PATH"))
            if not npm:
                raise RuntimeError("npm is missing; use Node 24.12+ and run npm ci in backend/")
            command = [npm, "test"] if phase["npmStage"] == "all" else [npm, "run", f"test:{phase['npmStage']}"]
            result["commands"].append({"argv": command, "cwd": "backend", "log": "firebase.log"})
            # Do not let a failed new attempt reuse an old stage summary.
            summary = ROOT / "backend/test-results/stages" / f"{phase['npmStage']}.json"
            if phase["npmStage"] != "all":
                summary.unlink(missing_ok=True)
            execute(command, ROOT / "backend", output / "firebase.log", env)
            if phase["npmStage"] == "all":
                log = (output / "firebase.log").read_text()
                passed = re.findall(r"^# pass (\d+)\s*$", log, re.M)
                failed = re.findall(r"^# fail (\d+)\s*$", log, re.M)
                skipped = re.findall(r"^# skipped (\d+)\s*$", log, re.M)
                if not passed or not failed or not skipped:
                    raise RuntimeError("Missing full Firebase test counts in log")
                firebase = {"passed": int(passed[-1]), "failed": int(failed[-1]), "skipped": int(skipped[-1])}
            else:
                firebase = json.loads(summary.read_text())
                if firebase.get("stage") != phase["npmStage"] or firebase.get("selectedCount") != phase["firebaseTests"]:
                    raise RuntimeError("Firebase stage selection does not match this phase")
                shutil.copyfile(summary, output / "firebase-summary.json")
            result["firebase"] = firebase
            if firebase["passed"] != phase["firebaseTests"] or firebase["failed"] or firebase["skipped"]:
                raise RuntimeError("Firebase result count or failure gate did not match")

        if phase["jvmClasses"]:
            gradle = env.get("STUDYPATH_GRADLE", str(ROOT / "android/gradlew"))
            command = [gradle, "--offline", "--no-daemon", "--max-workers=2", "--no-parallel", "--console=plain", "--rerun-tasks", ":app:testDebugUnitTest"]
            for name in phase["jvmClasses"]:
                command.extend(["--tests", name])
                (ROOT / "android/app/build/test-results/testDebugUnitTest" / f"TEST-{name}.xml").unlink(missing_ok=True)
            result["commands"].append({"argv": command, "cwd": "android", "log": "jvm.log"})
            execute(command, ROOT / "android", output / "jvm.log", env)
            result["jvm"] = junit_evidence(phase["jvmClasses"], output)
            if result["jvm"] != {"tests": phase["jvmTests"], "failures": 0, "errors": 0, "skipped": 0}:
                raise RuntimeError("JUnit result count, failures or skipped tests did not match")

        if phase.get("manualGate"):
            result["status"] = "automated_passed_manual_pending" if phase["npmStage"] else "manual_pending"
            result["note"] = "Complete this phase's README checklist with actual device/cloud evidence. This command cannot approve a manual gate."
            exit_code = 2
        else:
            result["status"] = "automated_passed"
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, ET.ParseError) as error:
        result["status"] = "failed"
        result["error"] = str(error)
        exit_code = 1
    except KeyboardInterrupt:
        result["status"] = "interrupted"
        result["error"] = "Interrupted before this phase gate finished."
        exit_code = 130
    result["finishedAt"] = now()
    result["exitCode"] = exit_code
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"{phase['id']}: {result['status']} — {output.relative_to(ROOT)}/result.json", flush=True)
    if result.get("error"):
        print(result["error"], file=sys.stderr)
    return exit_code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", nargs="?", choices=PHASES)
    parser.add_argument("--list", action="store_true", help="show gates without executing tests")
    parser.add_argument("--all-automated", action="store_true", help="run B01–B06 sequentially; stop at first failure")
    args = parser.parse_args()
    if args.list:
        for phase in PHASES.values():
            print(f"{phase['id']}  {phase['name']}  [Firebase {phase['firebaseTests']}, JVM {phase['jvmTests']}{', manual acceptance pending' if phase.get('manualGate') else ''}]")
        return 0
    if bool(args.phase) == bool(args.all_automated):
        parser.error("select one phase or --all-automated")
    env = os.environ.copy()
    selected = PLAN["automatedStages"] if args.all_automated else [args.phase]
    # One runner at a time: Firebase gates clear disposable emulator data and
    # Gradle filters share the same report directory.
    import fcntl
    lock_dir = ROOT / ".build"
    lock_dir.mkdir(exist_ok=True)
    with (lock_dir / "backend-phase-tests.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Another backend phase runner is active; wait for it to finish.", file=sys.stderr)
            return 1
        for phase_id in selected:
            code = verify(PHASES[phase_id], env)
            if code:
                return code
    return 0


if __name__ == "__main__":
    sys.exit(main())
