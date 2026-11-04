#!/usr/bin/env python3
"""Native v1 recovery/PDF gate; use frozen isolated APKs and a fresh read-only Test AVD.

Build first with -PfirebaseDatabaseUrl=https://demo-studypath-v1-fixes-<8hex>.firebaseio.com.
This runner never starts/stops Firebase, clears app data, resets shared namespaces or uninstalls apps.
PDF transfer is explicitly synthetic injection; RTDB transport/rules acknowledgements are real local calls.
"""
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
FIXTURE = 'files/v1-recovery-device.json'
STAGES = ['downloadPdfAndQueueCancelledRecovery', 'restoreOfflineAndRenderPdfAfterProcessDeath',
          'reconnectResolveRetainedResultsAndRemoval', 'verifyRemovalAndRejectedHistoryAfterAnotherRestart']
FLAGS = [
    ['pdfRepositoryDownloadAcknowledgedBeforeReady', 'corruptAndMissingPdfNeverBecomeReady',
     'unknownReminderParentRejectsRemovalAndPreservesReadyBytesAndJournal',
     'cancelledWaiterExactRetryConfirmsOriginalAndPreservesNewerActive', 'cancelledRemovalPersistsUnavailableIntent'],
    ['offlineProcessDeathPreservesExactPayloadsRemovalIntentAndPdf'],
    ['restartRetryUsesOriginalIdentityPayloadAndDomainConfirmation', 'acknowledgedCancelledRemovalReconcilesLocalFiles',
     'reviewRequiredRemovalPreservesRejectedRunAndExactPayload', 'retainedCopyWithdrawalConfirmsPrivateResultBeforeRemoval',
     'otherPackagesAndQueuedProgressSurviveRecovery'],
    ['secondRestartKeepsDeletionsAndRejectedRecoveryHistory'],
]


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def synthetic_pdf():
    """Two usable vector-text pages, generated locally with a conventional xref table."""
    streams = [f'BT /F1 14 Tf 20 260 Td (Synthetic recovery page {page}) Tj ET\n'.encode() for page in [1, 2]]
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 240 320] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>',
        b'<< /Length ' + str(len(streams[0])).encode() + b' >>\nstream\n' + streams[0] + b'endstream',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 240 320] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>',
        b'<< /Length ' + str(len(streams[1])).encode() + b' >>\nstream\n' + streams[1] + b'endstream',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    output = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets = []
    for index, value in enumerate(objects, 1):
        offsets.append(len(output)); output.extend(f'{index} 0 obj\n'.encode() + value + b'\nendobj\n')
    xref = len(output)
    output.extend(f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode())
    for offset in offsets:
        output.extend(f'{offset:010d} 00000 n \n'.encode())
    output.extend(f'trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return bytes(output)


def main():
    require(len(sys.argv) in [2, 3], 'Usage: test-v1-recovery.py <isolated-namespace> [serial]')
    namespace, serial = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'emulator-5554'
    require(re.fullmatch(r'demo-studypath-v1-fixes-[a-f0-9]{8}', namespace), 'Use an isolated v1 fixes namespace.')
    require(re.fullmatch(r'emulator-\d+', serial), 'Select the Test AVD serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    out = ROOT / 'implementation/phases/04-ppt-spec-alignment/12-final-verification/test-results' / (
        'v1-recovery-device-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'namespace': namespace, 'serial': serial, 'localOnly': True, 'syntheticOnly': True, 'syntheticPdfTransport': True,
        'dataCleared': False, 'packagesUninstalled': False, 'sharedDatabaseReset': False, 'commands': [], 'stages': [],
        'acceptancePromoted': False}
    private = set()
    radios = routes = binding = shared_before = auth_before = None
    owned = False
    auth_cleaned = False
    handled_actions = set()
    frozen_sources = [Path(__file__), ROOT / 'android/app/src/androidTest/java/com/studypath/app/V1RecoveryDeviceTest.kt',
        ROOT / 'android/test-fixtures/v1-recovery-fixture.mjs', ROOT / 'backend/database.rules.json',
        ROOT / 'android/app/src/main/java/com/studypath/app/data/FirebaseStudyRepository.kt',
        ROOT / 'android/app/src/main/java/com/studypath/app/data/TextPackageStore.kt',
        ROOT / 'android/app/src/main/java/com/studypath/app/data/LessonRunStore.kt',
        ROOT / 'android/app/src/main/java/com/studypath/app/data/OperationJournal.kt']
    receipt['sourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in frozen_sources}

    def device(*args, check=True, timeout=45):
        command = [adb, '-s', serial, *map(str, args)]
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        command_receipt = {'command': command, 'exitCode': result.returncode, 'stage': receipt.get('lastStage')}
        diagnostic = redact(result.stderr).strip()[:500] if result.returncode else ''
        if diagnostic:
            command_receipt['stderr'] = diagnostic
        receipt['commands'].append(command_receipt)
        require(not check or result.returncode == 0,
                f'A recorded Android command failed (exit {result.returncode}): ' + (diagnostic or 'no stderr output'))
        return result

    def shell(*args, **kwargs):
        return device('shell', *args, **kwargs)

    def database(path='', *, ns=namespace, method='GET', value=None):
        require(ns == namespace or method == 'GET' and ns in ['demo-studypath', 'demo-studypath-default-rtdb'], 'Unsafe namespace mutation.')
        require(not path.startswith('/') and '..' not in path and '?' not in path, 'Unsafe database path.')
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + ns,
            method=method, headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
            data=json.dumps(value).encode() if method == 'PUT' else None)
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    def auth_digest():
        # Emulator batchGet is read-only. The /emulator/.../accounts path supports DELETE only.
        # Hash all pages in memory; never persist/print account records, hashes, salts or tokens.
        from urllib.parse import urlencode
        users, page_token, seen = [], None, set()
        while True:
            query = {'maxResults': 1000}
            if page_token is not None:
                query['nextPageToken'] = page_token
            request = urllib.request.Request(
                'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchGet?' + urlencode(query),
                headers={'Authorization': 'Bearer owner'})
            with urllib.request.urlopen(request, timeout=15) as response:
                records = json.load(response)
            require(isinstance(records.get('users', []), list), 'Invalid local Auth export response.')
            users.extend(records.get('users', []))
            page_token = records.get('nextPageToken')
            if not page_token:
                break
            require(page_token not in seen, 'Repeated local Auth pagination token.')
            seen.add(page_token)
        users.sort(key=lambda row: row.get('localId', ''))
        return digest({'users': users})

    def radio_values():
        return {key: int(shell('settings', 'get', 'global', key).stdout.strip())
                for key in ['airplane_mode_on', 'wifi_on', 'mobile_data']}

    def routing():
        return {row[1]: row[2] for line in device('reverse', '--list').stdout.decode().splitlines() if len(row := line.split()) == 3}

    def binding_digest():
        result = shell('run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        return hashlib.sha256(result.stdout).hexdigest() if result.returncode == 0 else None

    def online():
        shell('cmd', 'connectivity', 'airplane-mode', 'disable')
        shell('svc', 'wifi', 'enable'); shell('svc', 'data', 'enable')
        for port in [9000, 9099]:
            device('reverse', f'tcp:{port}', f'tcp:{port}')

    def partition():
        for port in [9000, 9099]:
            device('reverse', '--remove', f'tcp:{port}', check=False)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable')
        shell('svc', 'wifi', 'disable'); shell('svc', 'data', 'disable')
        deadline = time.monotonic() + 20
        while True:
            state = radio_values()
            if state == {'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0}:
                break
            require(time.monotonic() < deadline, 'Guest radios did not partition.')
            time.sleep(.2)
        require(not any(f'tcp:{port}' in routing() for port in [9000, 9099]), 'Reverse transport remains active.')
        receipt.setdefault('partitions', []).append({'radios': state, 'timeUtc': dt.datetime.now(dt.timezone.utc).isoformat()})

    def marker(check=True):
        result = shell('run-as', PACKAGE, 'cat', FIXTURE, check=False, timeout=8)
        if result.returncode:
            require(not check, 'Native observation marker is missing.')
            return None
        value = json.loads(result.stdout)
        require(value['namespace'] == namespace, 'Native observation namespace differs.')
        private.add(value['uid'])
        return value

    def redact(output):
        value = output.decode(errors='replace')
        for identity in private:
            value = value.replace(identity, '<synthetic-uid>')
        return re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', value)

    def stage(name, index=None):
        receipt['lastStage'] = name
        print('V1 recovery:', name, flush=True)
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Previous app process is alive.')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            PACKAGE + '.V1RecoveryDeviceTest#' + name, '-e', 'v1RecoveryNamespace', namespace,
            PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        stage_error = None
        try:
            deadline = time.monotonic() + (270 if index == 0 else 180)
            while process.poll() is None:
                require(time.monotonic() < deadline, 'Native stage timed out.')
                value = marker(check=False)
                if value and index == 0:
                    action = value.get('hostAction')
                    if action and action not in handled_actions:
                        require(action in ['partition_unknown_reminders', 'reconnect_prime_reminders',
                                           'partition_one', 'reconnect_one', 'partition_two'], 'Unknown native transport request.')
                        if action == 'partition_unknown_reminders':
                            receipt['unknownReminderRemovalServerBeforeSha256'] = digest(database('users/' + value['uid']))
                        elif action == 'reconnect_prime_reminders':
                            require(value['observations'].get('unknownReminderParentRejectsRemovalAndPreservesReadyBytesAndJournal') is True,
                                    'Unknown reminder parent did not reach its explicit nonmutation observation.')
                            require(digest(database('users/' + value['uid'])) == receipt['unknownReminderRemovalServerBeforeSha256'],
                                    'Unknown reminder-parent removal changed server state.')
                            receipt['unknownReminderRemovalServerUnchanged'] = True
                        online() if action in ['reconnect_one', 'reconnect_prime_reminders'] else partition()
                        acknowledgement = {'namespace': namespace, 'action': action,
                                           'completedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
                        local_ack = out / ('host-ack-' + action + '.json')
                        local_ack.write_text(json.dumps(acknowledgement) + '\n')
                        remote_ack = '/data/local/tmp/v1-recovery-ack-' + namespace[-8:] + '.json'
                        device('push', local_ack, remote_ack)
                        shell('run-as', PACKAGE, 'cp', remote_ack, 'files/v1-recovery-host-ack.tmp')
                        shell('run-as', PACKAGE, 'mv', 'files/v1-recovery-host-ack.tmp', 'files/v1-recovery-host-ack.json')
                        shell('rm', remote_ack)
                        handled_actions.add(action)
                time.sleep(.2)
        except BaseException as error:
            stage_error = error
        finally:
            if process.poll() is None:
                shell('am', 'force-stop', PACKAGE, check=False); process.kill()
            output, _ = process.communicate(timeout=10)
            value = marker(check=False)
            (out / (name + '.log')).write_text(redact(output))
        require(stage_error is None, str(stage_error))
        log = redact(output)
        require(process.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
            not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log), 'Native JUnit failure: ' + name)
        require(value is not None, 'Missing native marker.')
        if index is not None:
            require(all(value['observations'].get(flag) is True for flag in FLAGS[index]), 'Missing native behavior observation.')
            receipt['stages'].append({'name': name, 'command': command, 'exitCode': process.returncode,
                'junit': 'OK (1 test)', 'pid': value[f'stage{index + 1}Pid']})
        receipt['observations'] = value['observations']
        return value

    try:
        receipt['lastStage'] = 'preflight'
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only Test AVD is permitted.')
        managed = False
        for proc in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = proc.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and '-avd' in args and
                    args[args.index('-avd') + 1] == 'StudyPath_Lite_Test' and '-read-only' in args and '-port' in args and
                    args[args.index('-port') + 1] == serial.split('-')[1])
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed, 'Managed read-only Test AVD on this serial is required.')
        require(shell('getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for Android boot.')
        radios, routes, binding = radio_values(), routing(), binding_digest()
        require(marker(check=False) is None, 'Restart the test AVD instead of overwriting prior recovery evidence.')
        shared_before = {ns: digest(database(ns=ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        auth_before = auth_digest()
        require(database() is None, 'Choose an unused isolated namespace.')
        owned = True
        database('.settings/rules', method='PUT', value=json.loads((ROOT / 'backend/database.rules.json').read_text()))
        (out / 'synthetic.pdf').write_bytes(synthetic_pdf())
        node = os.environ.get('STUDYPATH_NODE', '/home/keni/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
        command = [node, 'android/test-fixtures/v1-recovery-fixture.mjs', str(out / 'synthetic.pdf'), namespace]
        generated = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=30)
        require(generated.returncode == 0, 'Fixture generation failed: ' + generated.stderr.decode(errors='replace'))
        fixture = json.loads(generated.stdout)
        require(fixture['testOnly'] is True and fixture['syntheticAssetTransport'] is True, 'Fixture is not explicitly synthetic.')
        require(base64.b64decode(fixture['pdfBase64']) == (out / 'synthetic.pdf').read_bytes(), 'PDF fixture bytes differ.')
        (out / 'fixture.json').write_text(json.dumps(fixture, indent=2) + '\n')
        database('catalog', method='PUT', value=fixture['catalog'])
        require(database('catalog') == fixture['catalog'], 'Catalog seed readback differs.')
        for label, path in [('apk', ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'),
                            ('testApk', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
            require(path.is_file(), 'Build frozen app/test APKs first.')
            device('install', '-r', path); receipt[label + 'Sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        remote_fixture = '/data/local/tmp/v1-recovery-' + namespace[-8:] + '.json'
        device('push', out / 'fixture.json', remote_fixture)
        shell('run-as', PACKAGE, 'mkdir', '-p', 'files')
        shell('run-as', PACKAGE, 'cp', remote_fixture, 'files/v1-recovery-fixture.json')
        shell('rm', remote_fixture)
        online(); value = stage(STAGES[0], 0)
        require(handled_actions == {'partition_unknown_reminders', 'reconnect_prime_reminders',
                                    'partition_one', 'reconnect_one', 'partition_two'}, 'Transport boundaries were not all observed.')
        user = database('users/' + value['uid'])
        require(set(user.get('lessonAttempts', {})) == {'cancelled_waiter'}, 'Offline submissions reached the server.')
        require(user['lessonAttempts']['cancelled_waiter'] == value['attempts']['cancelled_waiter'], 'Cancelled waiter changed original payload.')
        require('v2' in user['offlinePackages']['fixture_v1']['fixture_module'], 'Offline deletion reached server before reconnect.')
        require(user['reviewProgress']['fixture_module']['v1']['completed'] is False, 'Offline progress reached server.')
        for version, retention in [(3, 'REVIEW_REQUIRED_FOR_RETAINED_COPIES'), (7, 'RETAIN_EXISTING_PRIVATE_COPIES')]:
            package = fixture['packages'][f'v{version}']; summary = package['summary']
            occurred = max(int(time.time() * 1000), user['offlinePackages']['fixture_v1']['fixture_module'][f'v{version}']['confirmedAt']) + 1
            history = {'schemaVersion': 1, 'curriculumVersion': 'fixture_v1', 'moduleId': 'fixture_module', 'version': version,
                'packageSha256': summary['packageSha256'], 'publishedAt': occurred - 86400000,
                'withdrawal': {'actorId': 'synthetic_local_actor', 'occurredAt': occurred,
                    'reason': 'SYNTHETIC v1 recovery regression', 'reference': 'SYNTHETIC TEST ONLY', 'retention': retention}}
            suffix = f'fixture_v1/fixture_module/v{version}'
            database('catalog/moduleIndex/' + suffix, method='DELETE')
            database('catalog/modulePackages/' + suffix, method='DELETE')
            database('catalog/modulePublicationHistory/' + suffix, method='PUT', value=history)
        catalog_withdrawn = digest(database('catalog'))
        stage(STAGES[1], 1)
        require(database('users/' + value['uid']) == user, 'Offline restart changed server state.')
        online(); value = stage(STAGES[2], 2); value = stage(STAGES[3], 3)
        final_user = database('users/' + value['uid'])
        expected_runs = {'cancelled_waiter', 'restart_original', 'retained_copy_result'}
        require(set(final_user.get('lessonAttempts', {})) == expected_runs, 'Wrong result count or forbidden review-required write.')
        for run in expected_runs:
            require(final_user['lessonAttempts'][run] == value['attempts'][run], 'Accepted server result differs from retained exact payload.')
        require(set(final_user['offlinePackages']['fixture_v1']['fixture_module']) == {'v1', 'v4'}, 'Selected receipt deletion changed another version.')
        require(final_user['reviewProgress']['fixture_module']['v1']['completed'] is True, 'Queued unrelated progress was lost.')
        require(digest(database('catalog')) == catalog_withdrawn, 'Client modified its read-only catalog.')
        receipt['serverProof'] = {'acceptedAttempts': final_user['lessonAttempts'], 'attemptCount': 3,
            'rejectedReviewResultAbsent': 'review_required_result' not in final_user['lessonAttempts'],
            'retainedReceiptVersions': sorted(final_user['offlinePackages']['fixture_v1']['fixture_module']),
            'queuedProgressConfirmed': True, 'readback': 'Independent host REST after native retries, removals and process recreation'}
        pdf = shell('run-as', PACKAGE, 'cat', 'files/v1-recovery-pdf.png').stdout
        require(pdf.startswith(b'\x89PNG\r\n\x1a\n'), 'Native PDF raster evidence missing.')
        (out / 'offline-native-pdf.png').write_bytes(pdf)
        stage('cleanupOwnedSyntheticAuth'); auth_cleaned = True
        receipt.update(status='PASSED', exitCode=0, pdfPages=value['pdfPages'], pdfSha256=value['pdfSha256'])
    except BaseException as error:
        receipt['failure'] = str(error)
        print('V1 recovery gate failed:', error, flush=True)
    finally:
        errors = []
        try:
            if owned and not auth_cleaned and marker(check=False) is not None:
                online(); stage('cleanupOwnedSyntheticAuth'); auth_cleaned = True
            receipt['ownedAuthCleaned'] = auth_cleaned
        except Exception as error:
            errors.append('Owned auth cleanup: ' + str(error))
        try:
            if owned:
                database(method='DELETE'); require(database() is None, 'Owned namespace cleanup failed.')
                receipt['isolatedNamespaceCleaned'] = True
            if shared_before is not None:
                receipt['sharedBeforeSha256'] = shared_before
                receipt['sharedAfterSha256'] = {ns: digest(database(ns=ns)) for ns in shared_before}
                require(shared_before == receipt['sharedAfterSha256'], 'Shared database controls changed.')
            if auth_before is not None:
                receipt['authBeforeSha256'], receipt['authAfterSha256'] = auth_before, auth_digest()
                require(auth_before == receipt['authAfterSha256'], 'Shared Auth controls changed.')
        except Exception as error:
            errors.append(str(error))
        try:
            if radios is not None:
                shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable')
                shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable')
                shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
            if routes is not None:
                for port in ['tcp:9000', 'tcp:9099']:
                    device('reverse', port, routes[port]) if port in routes else device('reverse', '--remove', port, check=False)
                require(routing() == routes, 'Original reverse routes were not restored.')
                require(binding_digest() == binding, 'Original app workspace binding changed.')
                receipt['appBindingPreserved'] = True
        except Exception as error:
            errors.append(str(error))
        receipt['finalSourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in frozen_sources}
        if receipt['finalSourceSha256'] != receipt['sourceSha256']:
            errors.append('Source changed while native gate was running; rebuild and rerun.')
        if errors:
            receipt.update(status='FAILED', exitCode=1, cleanupFailures=errors)
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        receipt['remainingGaps'] = ['Synthetic injected PDF bytes; live allowlisted HTTPS transport not exercised.',
            'Repository/native renderer regression, not broad UI, TalkBack or physical-device performance acceptance.',
            'No production content/policy approval, selected-cloud, human or 48-hour duration acceptance.']
        (out / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('Evidence:', out, flush=True)
    return receipt['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
