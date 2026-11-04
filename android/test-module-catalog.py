#!/usr/bin/env python3
"""B09 public module metadata component gate. Build APKs first; use a fresh read-only test AVD.

Uses only local demo Firebase. Uses a unique synthetic catalog namespace; no shared data mutation or queue discard.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import uuid
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
FIXTURE = 'files/b09-catalog-fixture.json'
STAGES = ['prepareAndFilter', 'recoverCachedIndex', 'restoreAndCheckSession']
FLAGS = [['legacyDemoDoesNotSelectApprovedCatalog', 'immutableVersionsProvenanceAndApplicabilityFiltered',
          'activeLibraryRefiltersWhenPreferencesChange', 'metadataReadableDuringActualPartition'],
         ['cachedIndexOptionsAndSelectionSurviveProcessDeath'],
         ['catalogObserversCloseAndSignedOutReadsFail', 'sameUidRestoresCatalogWithoutRevivingOldObserver']]
OUT = ROOT / 'implementation/phases/04-ppt-spec-alignment/01-backend-learner-catalog/test-results/module-index-20261026'



class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    serial = sys.argv[1] if len(sys.argv) == 2 else 'emulator-5554'
    require(len(sys.argv) <= 2 and re.fullmatch(r'emulator-\d+', serial), 'Select one Android test emulator.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    OUT.mkdir(parents=True, exist_ok=True)
    lock = OUT / '.device-lock'; lock.mkdir()
    out = OUT / 'device-latest'
    if out.exists():
        out.rename(OUT / ('device-run-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
    out.mkdir()
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'localOnly': True, 'avd': 'StudyPath_Lite_Test', 'serial': serial, 'stages': [],
               'appDataCleared': False, 'packagesUninstalled': False, 'sharedCatalogMutated': False, 'syntheticCatalogSeeded': True}
    radios = None; mappings = None; known_private = set(); failure = 1
    namespace = 'demo-studypath-b09-index-' + uuid.uuid4().hex[:8]
    receipt['isolatedNamespace'] = namespace
    old_handlers = {}

    def interrupted(signum, _frame):
        raise InterruptedError('Device gate interrupted.')

    def device(*args, check=True, timeout=35):
        result = subprocess.run([adb, '-s', serial, *map(str, args)], capture_output=True, timeout=timeout)
        require(not check or result.returncode == 0, 'A device operation failed at the recorded stage.')
        return result

    def shell(*args, **kwargs):
        return device('shell', *args, **kwargs)

    def database(path='', selected_namespace=namespace, method='GET', value=None):
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + selected_namespace,
            headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'}, method=method,
            data=None if value is None else json.dumps(value).encode())
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def radio_values():
        return {key: int(shell('settings', 'get', 'global', key).stdout.strip())
                for key in ['airplane_mode_on', 'wifi_on', 'mobile_data']}

    def observe_radios(expected):
        deadline = time.monotonic() + 12
        while radio_values() != expected:
            require(time.monotonic() < deadline, 'Guest transport did not reach the required state.')
            time.sleep(.2)

    def route_values():
        return {parts[1]: parts[2] for line in device('reverse', '--list').stdout.decode().splitlines()
                if len(parts := line.split()) == 3}

    def forwards():
        for port in [9000, 9099]:
            device('reverse', f'tcp:{port}', f'tcp:{port}')

    def partition():
        for port in [9000, 9099]: device('reverse', '--remove', f'tcp:{port}', check=False)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        shell('svc', 'wifi', 'disable'); shell('svc', 'data', 'disable')
        observe_radios({'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0})

    def fixture():
        value = json.loads(shell('run-as', PACKAGE, 'cat', FIXTURE).stdout)
        require(value.get('schemaVersion') == 1, 'Missing compatible private test fixture.')
        known_private.update(value[key] for key in ['uid', 'email'])
        return value

    def redact(raw):
        text = raw.decode(errors='replace')
        for private in known_private:
            text = text.replace(private, '<synthetic-identity>')
        return re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', text)

    def stage(index):
        name = STAGES[index]; receipt['lastStage'] = name
        print('B09 catalog:', name, flush=True)
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Prior process did not stop.')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   PACKAGE + '.PublishedModuleCatalogDeviceTest#' + name, '-e', 'catalogNamespace', namespace,
                   PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            if index == 0:
                deadline = time.monotonic() + 110
                while process.poll() is None:
                    require(time.monotonic() < deadline, 'Preparation did not reach the offline boundary in time.')
                    result = shell('run-as', PACKAGE, 'cat', FIXTURE, check=False)
                    if result.returncode == 0:
                        try: value = json.loads(result.stdout)
                        except json.JSONDecodeError: value = {}
                        if value.get('readyForPartition') is True:
                            partition(); receipt['realPartitionObservedBeforeCachedRead'] = True; break
                    time.sleep(.2)
            output, _ = process.communicate(timeout=120)
        except BaseException:
            process.kill(); process.communicate(); raise
        try:
            value = fixture()
        except Exception:
            (out / (name + '.log')).write_text(redact(output))
            raise
        log = redact(output)
        (out / (name + '.log')).write_text(log)
        require(process.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log),
                'Instrumentation omitted explicit success at the recorded stage.')
        require(all(value['observations'].get(flag) is True for flag in FLAGS[index]), 'Required device observations are missing.')
        receipt['stages'].append({'name': name, 'exitCode': process.returncode, 'command': command, 'junit': 'OK (1 test)',
                                  'pid': value[f'stage{index+1}Pid'], 'log': name + '.log', 'fixtureConfirmed': True})
        receipt['observations'] = value['observations']
        return value

    try:
        for signum in [signal.SIGINT, signal.SIGTERM]: old_handlers[signum] = signal.signal(signum, interrupted)
        receipt['lastStage'] = 'preflight'
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only the test AVD is allowed.')
        port = serial.split('-')[1]; managed = False
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = path.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and '-avd' in args and
                                args[args.index('-avd')+1] == 'StudyPath_Lite_Test' and '-read-only' in args and
                                '-port' in args and args[args.index('-port')+1] == port)
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed and shell('getprop', 'sys.boot_completed').stdout.strip() == b'1', 'A booted managed read-only test AVD is required.')
        radios = radio_values(); mappings = route_values()
        require(radios['airplane_mode_on'] == 0 and (radios['wifi_on'] or radios['mobile_data']), 'Start the test online.')
        apk_paths = [ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',
                     ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
        for apk in apk_paths:
            require(apk.is_file(), 'Build the local debug and test APKs first.')
            device('install', '-r', apk)
        device('shell', 'run-as', PACKAGE, 'pwd')
        if shell('run-as', PACKAGE, 'test', '-f', 'shared_prefs/b09-catalog-workspace.xml', check=False).returncode == 0:
            require(b'boundUid' not in shell('run-as', PACKAGE, 'cat', 'shared_prefs/b09-catalog-workspace.xml').stdout,
                    'Close/restart the read-only test AVD; never clear a bound installation.')
        receipt['apkSha256'], receipt['testApkSha256'] = [hashlib.sha256(p.read_bytes()).hexdigest() for p in apk_paths]
        receipt['runnerSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        receipt['testSourceSha256'] = hashlib.sha256((ROOT/'android/app/src/androidTest/java/com/studypath/app/PublishedModuleCatalogDeviceTest.kt').read_bytes()).hexdigest()
        forwards()
        shared_namespace = 'demo-studypath-default-rtdb'
        shared_before = digest(database(selected_namespace=shared_namespace))
        require(database() is None, 'The unique test namespace is already populated.')
        rules = json.loads((ROOT/'backend/database.rules.json').read_text())
        database('.settings/rules', method='PUT', value=rules)
        synthetic = json.loads((ROOT/'android/test-fixtures/catalog/module-index.json').read_text())
        database('catalog', method='PUT', value=synthetic['catalog'])
        catalog_before = digest(database('catalog'))
        receipt['syntheticFixtureSha256'] = hashlib.sha256((ROOT/'android/test-fixtures/catalog/module-index.json').read_bytes()).hexdigest()
        value = stage(0); uid = value['uid']
        baseline = database('users/' + uid)
        expected_prefs = {'schemaVersion': 1, 'onboardingComplete': True, 'levelId': 'fixture_level',
                          'curriculumVersion': 'fixture_v1', 'subjectIds': {'fixture_subject': True}, 'classSectionId': '0'}
        require(baseline['profile']['learnerPreferences'] == expected_prefs, 'Selections were not acknowledged before partition.')
        require(baseline['profile']['selectedCurriculumVersion'] == 'bsit_demo_v1', 'Legacy profile context changed during discovery.')
        require(set(database('users')) == {uid}, 'Unexpected account records in the unique namespace.')
        receipt['acknowledgedSelectionAndLegacyFieldVerified'] = True
        require(receipt.get('realPartitionObservedBeforeCachedRead'), 'No actual guest transport partition was observed.')
        value = stage(1)
        require(digest(database('users/' + uid)) == digest(baseline), 'The server profile changed during the offline restart.')
        receipt['offlineServerRecordsUnchanged'] = True
        shell('cmd', 'connectivity', 'airplane-mode', 'disable' if not radios['airplane_mode_on'] else 'enable')
        shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable'); shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
        observe_radios(radios); forwards()
        value = stage(2)
        require(digest(database('users/' + uid)) == digest(baseline), 'Read/logout/relogin changed retained server records.')
        require(digest(database('catalog')) == catalog_before, 'Synthetic catalog was changed by the client.')
        require(digest(database(selected_namespace=shared_namespace)) == shared_before, 'Shared demo data changed.')
        require(len({item['pid'] for item in receipt['stages']}) == 3, 'Three distinct app processes were not observed.')
        receipt.update(status='PASSED', lastStage='complete', sharedDemoUnchanged=True,
                       syntheticCatalogUnchanged=True, retainedServerRecordsUnchanged=True,
                       metadataOnly=True, lessonPayloadOrOfflineDownloadVerified=False)
        failure = 0
    except Exception as error:
        receipt['failure'] = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        print('B09 catalog gate failed:', receipt['failure'], flush=True)
    finally:
        for signum in old_handlers: signal.signal(signum, signal.SIG_IGN)
        errors = []
        actions = []
        if radios is not None:
            actions = [lambda: shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable'),
                       lambda: shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable'),
                       lambda: shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable'), lambda: observe_radios(radios)]
        if mappings is not None:
            for port in [9000, 9099]:
                remote = f'tcp:{port}'
                actions.append((lambda key=remote: device('reverse', key, mappings[key])) if remote in mappings else
                               (lambda key=remote: device('reverse', '--remove', key, check=False)))
            actions.append(lambda: require(route_values() == mappings, 'Reverse mappings were not restored.'))
        for action in actions:
            try: action()
            except Exception as error: errors.append(type(error).__name__)
        receipt['transportRestored'] = not errors
        if errors: failure = 1; receipt['status'] = 'FAILED'; receipt['restorationErrors'] = errors
        receipt.update(exitCode=failure, finishedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat())
        (out/'verification.json').write_text(json.dumps(receipt, indent=2)+'\n'); lock.rmdir()
        for signum, handler in old_handlers.items(): signal.signal(signum, handler)
        print('Evidence:', out, flush=True)
    return failure


if __name__ == '__main__':
    sys.exit(main())
