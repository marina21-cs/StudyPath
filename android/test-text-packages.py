#!/usr/bin/env python3
"""B10 text-package repository gate; build APKs first and boot a fresh read-only test AVD.

Uses unique, local-only Firebase/catalog and file workspaces. Actual guest partition and process
death are host coordinated. Fabricated approvals, text integrity and repository reads do not prove
teacher approval, interactive lesson semantics, PDF support, or reader screen acceptance.
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
import uuid

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
FIXTURE = 'files/b10-text-package-fixture.json'
EXPECTED = 'files/b10-text-package-expected.json'
STAGES = ['downloadAndQueueOffline', 'recoverLocalPackages', 'removeAndCheckSession']
FLAGS = [
    ['twoVersionsDownloadedAndServerConfirmed', 'corruptTextRejectedWithoutReadyPackage',
     'acknowledgedReceiptWithPendingLocalFinalizationRemainsUnavailable',
     'completeVersionedTextReadableDuringActualPartition', 'unrelatedScoreAndProgressQueuedOffline'],
    ['exactTextAndDurableReceiptsRecoverAfterOfflineProcessDeath',
     'pendingFinalizationBytesRecoverOfflineWithoutReadyClaim', 'pendingScoreAndProgressSurviveOfflineProcessDeath'],
    ['removingOneVersionPreservesOtherVersionAndUnrelatedQueuedRecords',
     'retryFinalizesOriginalAcknowledgedReceiptWithoutDuplicatingVersion',
     'independentLocalAndServerClocksAccepted',
     'signedOutLocalPackageOpenAndListAreDenied', 'sameUidRestoresRetainedPackageAndQueuedRecords'],
]
OUT = ROOT / 'implementation/phases/04-ppt-spec-alignment/02-backend-offline-downloads/test-results/text-packages'


class CheckFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckFailed(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def synthetic_catalog():
    """Build exact bytes through the existing validator/projections; corrupt only transport v3."""
    script = r'''
import {fixture, hash} from './backend/catalog/tests/fixture.mjs';
import {summarizeCandidate} from './backend/catalog/summarize-candidate.mjs';
import {packageCandidate} from './backend/catalog/package-candidate.mjs';
const versions = {}, packages = {}, expected = {};
for (const version of [1, 2, 3, 4]) {
  const candidate = fixture();
  candidate.manifest.version = version;
  const text = Buffer.from(JSON.stringify({moduleId: 'fixture_module', version,
    teaching: `Original synthetic v${version} test text. Two equal parts plus one equal part make three. 界 🧠`}));
  candidate.files.set('module.json', text);
  Object.assign(candidate.manifest.text, {bytes: text.length, sha256: hash(text)});
  candidate.manifestBytes = Buffer.from(JSON.stringify(candidate.manifest, null, 2) + '\n');
  const summary = summarizeCandidate(candidate), transport = packageCandidate(candidate);
  versions['v' + version] = summary;
  expected['v' + version] = {...transport, summary};
  packages['v' + version] = version === 3
    ? {...transport, textJson: transport.textJson.replace('make three', 'make four ')} : transport;
}
process.stdout.write(JSON.stringify({catalog: {
  learnerPreferenceOptions: {fixture_v1: {schemaVersion: 1, curriculumVersion: 'fixture_v1',
    levelId: 'fixture_level', subjectIds: {fixture_subject: true}, approved: true, approvalStatus: 'APPROVED'}},
  moduleIndex: {fixture_v1: {fixture_module: versions}},
  modulePackages: {fixture_v1: {fixture_module: packages}}
}, expected}));
'''
    result = subprocess.run([os.environ.get('STUDYPATH_NODE', 'node'), '--input-type=module', '-e', script],
                            cwd=ROOT, capture_output=True, timeout=20)
    require(result.returncode == 0, 'Synthetic text package generation failed validation.')
    return json.loads(result.stdout)


def main():
    serial = sys.argv[1] if len(sys.argv) == 2 else 'emulator-5554'
    require(len(sys.argv) <= 2 and re.fullmatch(r'emulator-\d+', serial), 'Select one Android test emulator.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    OUT.mkdir(parents=True, exist_ok=True)
    lock = OUT / '.device-lock'
    lock.mkdir()
    out = OUT / 'device-latest'
    if out.exists():
        out.rename(OUT / ('device-run-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')))
    out.mkdir()
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'localOnly': True, 'avd': 'StudyPath_Lite_Test', 'serial': serial, 'stages': [],
               'appDataCleared': False, 'packagesUninstalled': False, 'sharedCatalogMutated': False,
               'scope': 'B10_TEXT_PACKAGE_REPOSITORY_COMPONENT'}
    namespace = 'demo-studypath-b10-text-' + uuid.uuid4().hex[:8]
    receipt['isolatedNamespace'] = namespace
    radios = None
    mappings = None
    known_private = set()
    old_handlers = {}
    failure = 1

    def interrupted(_signum, _frame):
        raise InterruptedError('Device gate interrupted.')

    def device(*args, check=True, timeout=35, input=None):
        result = subprocess.run([adb, '-s', serial, *map(str, args)], capture_output=True,
                                timeout=timeout, input=input)
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

    def restore_radios():
        shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable')
        shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable')
        shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
        observe_radios(radios)

    def partition():
        for port in [9000, 9099]:
            device('reverse', '--remove', f'tcp:{port}', check=False)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        shell('svc', 'wifi', 'disable')
        shell('svc', 'data', 'disable')
        observe_radios({'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0})
        require(all(f'tcp:{port}' not in route_values() for port in [9000, 9099]),
                'Firebase reverse transport was not actually removed.')

    def fixture():
        value = json.loads(shell('run-as', PACKAGE, 'cat', FIXTURE).stdout)
        require(value.get('schemaVersion') == 1 and value.get('namespace') == namespace,
                'Missing compatible private test fixture.')
        known_private.update(value[key] for key in ['uid', 'email'])
        return value

    def redact(raw):
        text = raw.decode(errors='replace')
        for private in known_private:
            text = text.replace(private, '<synthetic-identity>')
        return re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', text)

    def stage(index):
        name = STAGES[index]
        receipt['lastStage'] = name
        print('B10 text packages:', name, flush=True)
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Prior process did not stop.')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   PACKAGE + '.TextPackageDownloadDeviceTest#' + name, '-e', 'packageNamespace', namespace,
                   PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        output = b''
        try:
            if index == 0:
                deadline = time.monotonic() + 115
                while process.poll() is None:
                    require(time.monotonic() < deadline, 'Download preparation did not reach the partition boundary.')
                    result = shell('run-as', PACKAGE, 'cat', FIXTURE, check=False)
                    if result.returncode == 0:
                        try:
                            value = json.loads(result.stdout)
                        except json.JSONDecodeError:
                            value = {}
                        if value.get('readyForPartition') is True:
                            partition()
                            receipt['realPartitionObservedBeforeLocalRead'] = True
                            break
                    time.sleep(.2)
            output, _ = process.communicate(timeout=130)
        except BaseException:
            process.kill()
            output, _ = process.communicate()
            (out / (name + '.log')).write_text(redact(output))
            raise
        try:
            value = fixture()
        finally:
            (out / (name + '.log')).write_text(redact(output))
        log = redact(output)
        require(process.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log),
                'Instrumentation omitted explicit success at the recorded stage.')
        require(all(value['observations'].get(flag) is True for flag in FLAGS[index]),
                'Required device observations are missing.')
        receipt['stages'].append({'name': name, 'exitCode': process.returncode, 'command': command,
                                 'junit': 'OK (1 test)', 'pid': value[f'stage{index + 1}Pid'],
                                 'log': name + '.log', 'fixtureConfirmed': True})
        receipt['observations'] = value['observations']
        return value

    def assert_downloads(user, value, expected, versions):
        packages = user.get('offlinePackages', {}).get('fixture_v1', {}).get('fixture_module', {})
        require(set(packages) == {'v' + str(version) for version in versions},
                'Server package receipts differ from the retained immutable versions.')
        for version in versions:
            name = 'v' + str(version)
            row = packages[name]
            summary = expected[name]['summary']
            require(set(row) == {'schemaVersion', 'moduleId', 'version', 'curriculumVersion',
                                'packageSha256', 'textBytes', 'downloadedAt', 'confirmedAt'},
                    'Unexpected package receipt fields.')
            require(row['schemaVersion'] == 1 and row['moduleId'] == 'fixture_module' and
                    row['version'] == version and row['curriculumVersion'] == 'fixture_v1' and
                    row['packageSha256'] == summary['packageSha256'] and
                    row['textBytes'] == summary['textBytes'], 'Package receipt does not match validated source bytes.')
            require(row['downloadedAt'] == value['downloads'][name]['downloadedAt'] and
                    all(isinstance(row[field], (int, float)) and not isinstance(row[field], bool) and
                        0 <= row[field] <= 4_102_444_800_000 and row[field] % 1 == 0
                        for field in ['downloadedAt', 'confirmedAt']),
                    'Package receipt is missing a durable server timestamp.')
            if version == 4:
                require(row['downloadedAt'] > row['confirmedAt'],
                        'The fixture did not exercise independent device/service clocks.')

    try:
        for signum in [signal.SIGINT, signal.SIGTERM]:
            old_handlers[signum] = signal.signal(signum, interrupted)
        receipt['lastStage'] = 'preflight'
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test',
                'Only the test AVD is allowed.')
        port = serial.split('-')[1]
        managed = False
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = path.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and
                                '-avd' in args and args[args.index('-avd') + 1] == 'StudyPath_Lite_Test' and
                                '-read-only' in args and '-port' in args and args[args.index('-port') + 1] == port)
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed and shell('getprop', 'sys.boot_completed').stdout.strip() == b'1',
                'A booted managed read-only test AVD is required.')
        radios = radio_values()
        mappings = route_values()
        require(radios['airplane_mode_on'] == 0 and (radios['wifi_on'] or radios['mobile_data']),
                'Start the test online.')
        apks = [ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk',
                ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
        for apk in apks:
            require(apk.is_file(), 'Build the local debug and test APKs first.')
            device('install', '-r', apk)
        shell('run-as', PACKAGE, 'pwd')
        require(shell('run-as', PACKAGE, 'test', '-f', FIXTURE, check=False).returncode != 0,
                'Close/restart the read-only test AVD; never overwrite an existing test fixture.')
        prefs = 'shared_prefs/b10-text-package-workspace.xml'
        if shell('run-as', PACKAGE, 'test', '-f', prefs, check=False).returncode == 0:
            require(b'boundUid' not in shell('run-as', PACKAGE, 'cat', prefs).stdout,
                    'Close/restart the read-only test AVD; never clear a bound installation.')
        receipt['apkSha256'], receipt['testApkSha256'] = [hashlib.sha256(path.read_bytes()).hexdigest() for path in apks]
        receipt['runnerSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        receipt['testSourceSha256'] = hashlib.sha256((ROOT / 'android/app/src/androidTest/java/com/studypath/app/TextPackageDownloadDeviceTest.kt').read_bytes()).hexdigest()
        forwards()
        shared_namespace = 'demo-studypath-default-rtdb'
        shared_before = digest(database(selected_namespace=shared_namespace))
        require(database() is None, 'The unique test namespace is already populated.')
        database('.settings/rules', method='PUT', value=json.loads((ROOT / 'backend/database.rules.json').read_text()))
        synthetic = synthetic_catalog()
        database('catalog', method='PUT', value=synthetic['catalog'])
        catalog_before = digest(database('catalog'))
        receipt['syntheticCatalogSha256'] = catalog_before
        expected = {'schemaVersion': 1, 'namespace': namespace, 'packages': synthetic['expected']}
        shell('run-as', PACKAGE, 'mkdir', '-p', 'files')
        shell('run-as', PACKAGE, 'tee', EXPECTED, input=json.dumps(expected).encode())
        (out / 'synthetic-catalog.json').write_text(json.dumps(synthetic, indent=2, ensure_ascii=False) + '\n')

        value = stage(0)
        uid = value['uid']
        baseline = database('users/' + uid)
        assert_downloads(baseline, value, synthetic['expected'], [1, 2, 4])
        require(baseline['scores']['b10_score']['earnedPoints'] == 0 and
                baseline['reviewProgress']['fixture_module']['v2']['completed'] is False,
                'Unacknowledged score/progress reached the server during the partition.')
        require(set(database('users')) == {uid}, 'Unexpected user records in the unique namespace.')
        receipt['twoReadyAndOnePendingFinalizationServerReceiptsAndNoCorruptReceiptVerified'] = True
        require(receipt.get('realPartitionObservedBeforeLocalRead'), 'No actual guest transport partition was observed.')
        stage(1)
        require(digest(database('users/' + uid)) == digest(baseline),
                'The server changed while package/queue recovery was offline.')
        receipt['offlineServerRecordsUnchanged'] = True
        restore_radios()
        forwards()
        value = stage(2)
        deadline = time.monotonic() + 20
        while True:
            retained = database('users/' + uid)
            if (retained['scores']['b10_score']['earnedPoints'] == 12 and
                    retained['reviewProgress']['fixture_module']['v2']['completed'] is True):
                break
            require(time.monotonic() < deadline, 'Unrelated queued score/progress did not reconcile under the original UID.')
            time.sleep(.2)
        require(set(retained['scores']) == {'b10_score'} and
                set(retained['reviewProgress']) == {'fixture_module'} and
                set(retained['reviewProgress']['fixture_module']) == {'v2'},
                'Package removal duplicated or discarded unrelated versioned records.')
        expected_score = dict(baseline['scores']['b10_score'], earnedPoints=12)
        expected_progress = dict(baseline['reviewProgress']['fixture_module']['v2'], completed=True, lastSection=1)
        for actual, expected_row in [(retained['scores']['b10_score'], expected_score),
                                     (retained['reviewProgress']['fixture_module']['v2'], expected_progress)]:
            require({key: value for key, value in actual.items() if key != 'updatedAt'} ==
                    {key: value for key, value in expected_row.items() if key != 'updatedAt'} and
                    isinstance(actual.get('updatedAt'), (int, float)) and actual['updatedAt'] > 0,
                    'Reconciled score/progress fields differ from the original queued records.')
        assert_downloads(retained, value, synthetic['expected'], [2, 4])
        retained_packages = retained['offlinePackages']['fixture_v1']['fixture_module']
        baseline_packages = baseline['offlinePackages']['fixture_v1']['fixture_module']
        require(retained_packages['v2'] == baseline_packages['v2'],
                'Removal/session restore changed the retained ready v2 receipt.')
        require({key: value for key, value in retained_packages['v4'].items() if key != 'confirmedAt'} ==
                {key: value for key, value in baseline_packages['v4'].items() if key != 'confirmedAt'} and
                retained_packages['v4']['confirmedAt'] >= baseline_packages['v4']['confirmedAt'] and
                retained_packages['v4']['confirmedAt'] == value['downloads']['v4']['confirmedAt'],
                'Retry changed immutable v4 fields or did not retain its refreshed server confirmation.')
        require(set(retained) == set(baseline), 'Package removal changed unrelated top-level user collections.')
        for name in set(baseline) - {'scores', 'reviewProgress', 'offlinePackages'}:
            require(retained[name] == baseline[name], 'Package removal changed unrelated retained user data.')
        require(digest(database('catalog')) == catalog_before, 'Client changed the synthetic published catalog.')
        require(digest(database(selected_namespace=shared_namespace)) == shared_before, 'Shared demo data changed.')
        require(len({item['pid'] for item in receipt['stages']}) == 3, 'Three distinct app processes were not observed.')
        receipt.update(status='PASSED', lastStage='complete', sharedDemoUnchanged=True,
                       syntheticCatalogUnchanged=True, queuedScoresAndProgressRetained=True,
                       pendingLocalFinalizationAndExplicitRetryVerified=True,
                       deterministicKillBetweenServerAckAndLocalFinalizationVerified=False,
                       textBytesAndDownloadReceiptsVerified=True, teacherApprovedContentVerified=False,
                       interactiveSemanticsVerified=False, pdfAssetsVerified=False, readerUiVerified=False)
        failure = 0
    except Exception as error:
        receipt['failure'] = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        print('B10 text package gate failed:', receipt['failure'], flush=True)
    finally:
        for signum in old_handlers:
            signal.signal(signum, signal.SIG_IGN)
        errors = []
        actions = [restore_radios] if radios is not None else []
        if mappings is not None:
            for port in [9000, 9099]:
                remote = f'tcp:{port}'
                actions.append((lambda key=remote: device('reverse', key, mappings[key])) if remote in mappings else
                               (lambda key=remote: device('reverse', '--remove', key, check=False)))
            actions.append(lambda: require(route_values() == mappings, 'Reverse mappings were not restored.'))
        for action in actions:
            try:
                action()
            except Exception as error:
                errors.append(type(error).__name__)
        receipt['transportRestored'] = not errors
        if errors:
            failure = 1
            receipt.update(status='FAILED', restorationErrors=errors)
        receipt.update(exitCode=failure, finishedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat())
        (out / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
        lock.rmdir()
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)
        print('Evidence:', out, flush=True)
    return failure


if __name__ == '__main__':
    sys.exit(main())
