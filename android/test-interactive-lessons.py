#!/usr/bin/env python3
"""B11 host-coordinated native lesson gate on a fresh read-only test AVD.

Build with -PfirebaseDatabaseUrl=https://<namespace>.firebaseio.com first; namespace must be
demo-studypath-b11-ui- plus eight lowercase hex digits. No normal preview data is cleared.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
FIXTURE = 'files/b11-lesson-device.json'
STAGES = ['downloadAndIncorrectFeedback', 'recoverFeedbackAndNumericDraft',
          'completeOfflineAndRetainPendingResult', 'reconnectAndRetryExactResult',
          'rejectMalformedDirectTransitionsAndRetainIdentity']
FLAGS = [
    ['uiDownloadRequiresValidatedConfirmedBytes', 'glossaryLookupPreservesLessonState',
     'incorrectFeedbackIsDurableDuringActualPartition', 'incorrectFeedbackTitleIsVisibleAfterCheck'],
    ['sameWrongFeedbackRestoresAfterOfflineProcessDeath', 'correctRetryAndAccessibleOrderingPreserveOneRun',
     'numericDraftSurvivesActivityRecreationOffline', 'numericDraftSurvivesImmediateBackAndReentryOffline',
     'numeric200PercentTextRetainsTargetsDraftAndPrimaryAboveIme',
     'incorrectFeedbackTitleIsVisibleAfterOfflineRestore', 'correctFeedbackTitlesAreVisibleAt200PercentText'],
    ['numericDraftRestoresAfterOfflineProcessDeath', 'assistedCompletionStaysDistinctFromFirstTrySuccess',
     'offlineCompletionRetainsExactPendingAttempt', 'pendingResultsPreventStrandingByPackageRemoval',
     'authoredReviewIsPrivateVersionBoundAndIdempotent'],
    ['reconnectAndExactRetryKeepOneImmutableResult', 'lessonPracticeLeavesAcademicScoreUnchanged'],
    ['directRepositoryRejectsWrongPackageQuestionAndState',
     'directRepositoryRejectsChangedCompletionAndAllowsExactDuplicates',
     'directRepositoryRejectionsPreserveHistoryAndAcademicScore'],
]
OUT = ROOT / 'implementation/phases/04-ppt-spec-alignment/03-backend-interactive-study/test-results/lesson-device'


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    require(len(sys.argv) in [2, 3], 'Usage: test-interactive-lessons.py <isolated-namespace> [serial]')
    namespace, serial = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'emulator-5554'
    require(re.fullmatch(r'demo-studypath-b11-ui-[a-f0-9]{8}', namespace), 'Use a unique B11 UI test namespace.')
    require(re.fullmatch(r'emulator-\d+', serial), 'Select the isolated Android emulator.')
    adb = str(Path.home() / 'Android/Sdk/platform-tools/adb')
    OUT.mkdir(parents=True, exist_ok=True)
    lock = OUT / '.device-lock'; lock.mkdir()
    out = OUT / 'latest'
    if out.exists():
        out.rename(OUT / ('run-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')))
    out.mkdir()
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'namespace': namespace, 'deviceSerial': serial, 'scope': 'B11_NATIVE_INTERACTIVE_TEXT_COMPONENT',
               'localOnly': True, 'dataCleared': False, 'packagesUninstalled': False, 'commands': [], 'stages': []}
    private = set(); radios = None; routes = None; font = None; hardware_ime = None; shared_before = None

    def device(*args, check=True, input=None, timeout=40):
        command = [adb, '-s', serial, *map(str, args)]
        result = subprocess.run(command, input=input, capture_output=True, timeout=timeout)
        receipt['commands'].append({'command': command, 'exitCode': result.returncode, 'stage': receipt.get('lastStage')})
        require(not check or result.returncode == 0, 'A recorded Android command failed.')
        return result

    def shell(*args, **kwargs):
        return device('shell', *args, **kwargs)

    def database(path='', ns=namespace, value=None, method='GET'):
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + ns,
            headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
            method=method, data=None if value is None else json.dumps(value).encode())
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def radio_values():
        return {key: int(shell('settings', 'get', 'global', key).stdout.strip())
                for key in ['airplane_mode_on', 'wifi_on', 'mobile_data']}

    def routing():
        return {parts[1]: parts[2] for line in device('reverse', '--list').stdout.decode().splitlines()
                if len(parts := line.split()) == 3}

    def online():
        shell('cmd', 'connectivity', 'airplane-mode', 'disable')
        shell('svc', 'wifi', 'enable'); shell('svc', 'data', 'enable')
        for port in [9000, 9099]: device('reverse', f'tcp:{port}', f'tcp:{port}')

    def partition():
        for port in [9000, 9099]: device('reverse', '--remove', f'tcp:{port}', check=False)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        shell('svc', 'wifi', 'disable'); shell('svc', 'data', 'disable')
        deadline = time.monotonic() + 20
        while True:
            state = radio_values()
            receipt['partitionRadioState'] = state
            if state == {'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0}: break
            require(time.monotonic() < deadline, 'Guest transport did not partition: ' + str(state))
            time.sleep(.2)
        require(not any(f'tcp:{port}' in routing() for port in [9000, 9099]), 'Reverse transport still exists.')
        receipt['actualGuestPartition'] = True

    def fixture():
        value = json.loads(shell('run-as', PACKAGE, 'cat', FIXTURE).stdout)
        private.update(value[key] for key in ['uid', 'email'])
        return value

    def redact(value):
        text = value.decode(errors='replace')
        for item in private: text = text.replace(item, '<synthetic-identity>')
        return re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', text)

    def capture():
        for name in ['01-lesson-path', '02-offline-incorrect-feedback', '03-offline-numeric-draft', '03b-numeric-200pct-keyboard',
                     '04-offline-completion', '05-confirmed-completion']:
            result = shell('run-as', PACKAGE, 'cat', f'files/b11-lesson-screenshots/{name}.png', check=False)
            if result.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                (out / f'{name}.png').write_bytes(result.stdout)

    def stage(index):
        name = STAGES[index]; receipt['lastStage'] = name
        print('B11 lessons:', name, flush=True)
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Previous app process remains alive.')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   PACKAGE + '.InteractiveLessonDeviceTest#' + name, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            if index == 0:
                deadline = time.monotonic() + 120
                while process.poll() is None:
                    require(time.monotonic() < deadline, 'Lesson UI did not reach the partition boundary.')
                    result = shell('run-as', PACKAGE, 'cat', FIXTURE, check=False)
                    if result.returncode == 0:
                        try: value = json.loads(result.stdout)
                        except json.JSONDecodeError: value = {}
                        if value.get('readyForPartition'):
                            partition(); break
                    time.sleep(.3)
            output, _ = process.communicate(timeout=175)
        except BaseException:
            shell('am', 'force-stop', PACKAGE, check=False)
            process.kill(); output, _ = process.communicate(); raise
        finally:
            try: value = fixture()
            except Exception: value = {}
            (out / (name + '.log')).write_text(redact(output if 'output' in locals() else b''))
            capture()
        text = redact(output)
        require(process.returncode == 0 and re.search(r'^OK \(1 test\)', text, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', text),
                'Instrumentation failed or omitted explicit JUnit success.')
        require(all(value['observations'].get(flag) is True for flag in FLAGS[index]), 'Device observation is missing.')
        receipt['stages'].append({'name': name, 'command': command, 'exitCode': process.returncode,
                                  'junit': 'OK (1 test)', 'pid': value[f'stage{index + 1}Pid']})
        receipt['observations'] = value['observations']
        if 'largeTextNumericLayout' in value:
            receipt['largeTextNumericLayout'] = value['largeTextNumericLayout']
        return value

    try:
        receipt['lastStage'] = 'preflight'
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only the test AVD is allowed.')
        managed = False
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = path.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and
                                '-avd' in args and args[args.index('-avd') + 1] == 'StudyPath_Lite_Test' and '-read-only' in args)
            except (OSError, UnicodeError, IndexError): pass
        require(managed, 'Launch the managed read-only test AVD.')
        require(shell('getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for Android boot.')
        radios = radio_values(); routes = routing()
        font = shell('settings', 'get', 'system', 'font_scale').stdout.decode().strip()
        hardware_ime = shell('settings', 'get', 'secure', 'show_ime_with_hard_keyboard').stdout.decode().strip()
        require(shell('run-as', PACKAGE, 'test', '-f', FIXTURE, check=False).returncode != 0, 'Restart the test AVD; never overwrite a test workspace.')
        prefs = shell('run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        require(prefs.returncode != 0 or b'boundUid' not in prefs.stdout, 'Restart the test AVD; never clear a bound installation.')
        shared_before = digest(database(ns='demo-studypath-default-rtdb'))
        receipt['sharedDatabaseSha256Before'] = shared_before
        require(database() is None, 'Choose an unused isolated namespace.')
        database('.settings/rules', method='PUT', value=json.loads((ROOT / 'backend/database.rules.json').read_text()))
        generated = subprocess.run([os.environ.get('STUDYPATH_NODE', 'node'), 'backend/interactive/synthetic-lesson-fixture.mjs'],
                                   cwd=ROOT, capture_output=True, timeout=20, check=True)
        synthetic = json.loads(generated.stdout)
        require(synthetic['testOnly'] is True, 'The fixture must be explicitly synthetic.')
        database('catalog', method='PUT', value=synthetic['catalog'])
        catalog_before = digest(database('catalog'))
        receipt['syntheticCatalogSha256'] = catalog_before
        (out / 'synthetic-fixture.json').write_text(json.dumps(synthetic, indent=2) + '\n')
        apks = [ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk',
                ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
        for apk in apks: device('install', '-r', apk)
        receipt['apkSha256'], receipt['testApkSha256'] = [hashlib.sha256(apk.read_bytes()).hexdigest() for apk in apks]
        receipt['runnerSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        online()
        stage(0)
        shell('settings', 'put', 'system', 'font_scale', '2.0')
        shell('settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        receipt['largeTextScale'] = 2.0
        stage(1); value = stage(2)
        require(not database('users/' + value['uid']).get('lessonAttempts'), 'An offline result unexpectedly reached the server.')
        online(); stage(3); value = stage(4)
        user = database('users/' + value['uid'])
        require(set(user.get('lessonAttempts', {})) == {value['runId'], value['reviewRunId']}, 'Retry changed the number of immutable lesson/review results.')
        require(user['lessonAttempts'][value['runId']] == value['attempt'], 'Independent server result differs from retained completion.')
        require(user['lessonAttempts'][value['reviewRunId']] == value['reviewAttempt'], 'Independent server review differs from retained completion.')
        require(user['scores']['b11_score']['earnedPoints'] == 0, 'Practice changed an academic score.')
        receipt['serverProof'] = {'resultCount': len(user['lessonAttempts']),
                                  'attempts': user['lessonAttempts'], 'academicScoreBefore': 0,
                                  'academicScoreAfter': user['scores']['b11_score']['earnedPoints'],
                                  'readback': 'Independent host REST read after explicit native sync and exact retry',
                                  'identityOmitted': True}
        require(digest(database('catalog')) == catalog_before, 'The lesson client changed its read-only catalog.')
        require(digest(database(ns='demo-studypath-default-rtdb')) == shared_before, 'The normal database changed during isolated tests.')
        receipt.update(status='PASSED', exitCode=0, sharedDatabaseUnchanged=True,
                       independentServerResultMatches=True, fullPhaseAccepted=False)
    except BaseException as error:
        receipt['error'] = str(error)
        print('B11 lessons failed:', error, flush=True)
    finally:
        try:
            if shared_before is not None:
                receipt['sharedDatabaseSha256After'] = digest(database(ns='demo-studypath-default-rtdb'))
                receipt['sharedDatabaseUnchanged'] = receipt['sharedDatabaseSha256After'] == shared_before
                require(receipt['sharedDatabaseUnchanged'], 'The normal database changed during isolated tests.')
        except Exception as error:
            receipt.update(status='FAILED', exitCode=1, sharedDatabaseCheckError=str(error))
        try:
            if radios is not None:
                shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable')
                shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable')
                shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
            if routes is not None:
                for port in [9000, 9099]:
                    device('reverse', '--remove', f'tcp:{port}', check=False)
                    if f'tcp:{port}' in routes: device('reverse', f'tcp:{port}', routes[f'tcp:{port}'])
            if font is not None:
                shell('settings', 'delete', 'system', 'font_scale') if font == 'null' else shell('settings', 'put', 'system', 'font_scale', font)
            if hardware_ime is not None:
                shell('settings', 'delete', 'secure', 'show_ime_with_hard_keyboard') if hardware_ime == 'null' else shell('settings', 'put', 'secure', 'show_ime_with_hard_keyboard', hardware_ime)
        except Exception as error:
            receipt.update(status='FAILED', exitCode=1, cleanupError=str(error))
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        receipt['limits'] = ['Synthetic text lessons only; no approved curriculum or real content approval.',
                             'No PDF/assets, reviewed real lesson content, cloud, physical-device performance, TalkBack or actual48-hour acceptance.']
        (out / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
        lock.rmdir()
        print('Evidence:', out, flush=True)
    return receipt['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
