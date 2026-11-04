#!/usr/bin/env python3
"""Actual mock publication + repository HTTP PDF transfer + offline process recovery gate.

Uses already-running local Firebase services, frozen app/test APKs and a fresh read-only Test AVD.
Never resets shared data, starts/stops Firebase, clears app data or changes the normal workspace binding.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import select
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
CLASS = PACKAGE + '.MockCurriculumDeviceTest'
MARKER = 'files/mock-curriculum-device.json'
STAGES = ['downloadThreePublishedModulesAndCompleteFirst', 'restoreAllPdfsAndCompleteRemainingLessonsOffline',
          'reconnectAndConfirmExactlyThreeOriginalResults']
FLAGS = [['threeCompleteRepositoryDownloadsRequireRuleAcknowledgements',
          'originalInstructionAndThreeInteractionFormatsCompleteWithCorrectiveFeedback'],
         ['offlineProcessRestartRestoresThreeNativePdfReadsAndCompletedLesson',
          'twoOfflineOriginalCompletionsRetainPendingExactResults'],
         ['reconnectConfirmsExactlyThreeVersionBoundUnchangedResults']]


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    require(len(sys.argv) in [2, 3], 'Usage: test-mock-curriculum.py demo-studypath-mock-<8hex> [serial]')
    namespace, serial = sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else 'emulator-5554'
    require(re.fullmatch(r'demo-studypath-mock-[a-f0-9]{8}', namespace), 'An exact isolated mock namespace is required.')
    require(re.fullmatch(r'emulator-\d+', serial), 'Select the Test AVD serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    node = os.environ.get('STUDYPATH_NODE', '/home/keni/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
    mirror_port = 8765
    out = ROOT / 'implementation/phases/04-ppt-spec-alignment/00-source-policy-gate/test-results/mock-curriculum-20261031' / (
        'native-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    receipt = {'status': 'FAILED', 'exitCode': 1, 'namespace': namespace, 'serial': serial,
               'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'syntheticOnly': True, 'localOnly': True,
               'pdfTransport': 'actual HTTP to explicit loopback test mirror; declared HTTPS origin remains immutable',
               'productionPublicHttpsVerified': False, 'acceptancePromoted': False, 'appDataCleared': False,
               'sharedDatabaseReset': False, 'commands': [], 'stages': []}
    radios = routes = binding = controls = auth_before = None
    mirror = None
    owned = auth_cleaned = False
    private = set()
    sources = sorted(set([Path(__file__), ROOT / 'backend/database.rules.json',
        ROOT / 'android/app/src/androidTest/java/com/studypath/app/MockCurriculumDeviceTest.kt',
        *list((ROOT / 'backend/mock').glob('*.mjs')), *list((ROOT / 'android/app/src/main').rglob('*.kt')),
        *list((ROOT / 'android/app/src/main').rglob('*.xml'))]))
    receipt['sourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}

    def redact(value):
        text = value.decode(errors='replace') if isinstance(value, bytes) else value
        for identity in private:
            text = text.replace(identity, '<synthetic-uid>')
        return re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', text)

    def device(*args, check=True, timeout=45):
        command = [adb, '-s', serial, *map(str, args)]
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        receipt['commands'].append({'command': command, 'exitCode': result.returncode})
        require(not check or result.returncode == 0, 'Recorded ADB command failed: ' + redact(result.stderr)[:300])
        return result

    def shell(*args, **kwargs):
        return device('shell', *args, **kwargs)

    def database(path='', *, ns=namespace, method='GET', value=None):
        require(ns == namespace or method == 'GET' and ns in ['demo-studypath', 'demo-studypath-default-rtdb'], 'Unsafe namespace mutation.')
        require(not path.startswith('/') and '..' not in path and '?' not in path, 'Unsafe local database path.')
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + ns, method=method,
            headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
            data=json.dumps(value).encode() if method == 'PUT' else None)
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    def auth_digest():
        from urllib.parse import urlencode
        users, token, seen = [], None, set()
        while True:
            query = {'maxResults': 1000}
            if token:
                query['nextPageToken'] = token
            request = urllib.request.Request('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchGet?' + urlencode(query),
                                             headers={'Authorization': 'Bearer owner'})
            with urllib.request.urlopen(request, timeout=15) as response:
                page = json.load(response)
            users.extend(page.get('users', [])); token = page.get('nextPageToken')
            if not token:
                break
            require(token not in seen, 'Repeated Auth export token.'); seen.add(token)
        return digest(sorted(users, key=lambda row: row.get('localId', '')))

    def routing():
        return {row[1]: row[2] for line in device('reverse', '--list').stdout.decode().splitlines() if len(row := line.split()) == 3}

    def binding_digest():
        value = shell('run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        return hashlib.sha256(value.stdout).hexdigest() if value.returncode == 0 else None

    def radio_values():
        return {key: int(shell('settings', 'get', 'global', key).stdout.strip()) for key in ['airplane_mode_on', 'wifi_on', 'mobile_data']}

    def online():
        shell('cmd', 'connectivity', 'airplane-mode', 'disable'); shell('svc', 'wifi', 'enable'); shell('svc', 'data', 'enable')
        for port in [9000, 9099, mirror_port]:
            device('reverse', f'tcp:{port}', f'tcp:{port}')

    def partition():
        for port in [9000, 9099, mirror_port]:
            device('reverse', '--remove', f'tcp:{port}', check=False)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable'); shell('svc', 'wifi', 'disable'); shell('svc', 'data', 'disable')
        deadline = time.monotonic() + 20
        while radio_values() != {'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0}:
            require(time.monotonic() < deadline, 'Guest radios did not partition.'); time.sleep(.2)
        require(not any(f'tcp:{port}' in routing() for port in [9000, 9099, mirror_port]), 'Reverse transport remains active.')
        receipt['offlinePartition'] = radio_values()

    def marker(check=True):
        value = shell('run-as', PACKAGE, 'cat', MARKER, check=False)
        if value.returncode:
            require(not check, 'Native evidence marker is missing.'); return None
        result = json.loads(value.stdout); require(result['namespace'] == namespace, 'Native marker belongs to another namespace.')
        private.add(result['uid']); return result

    def stage(name, index=None):
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Previous app process is alive.')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#' + name,
                   '-e', 'mockNamespace', namespace, '-e', 'mirrorPort', str(mirror_port), PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        result = subprocess.run(command, capture_output=True, timeout=210)
        log = redact(result.stdout + result.stderr); (out / (name + '.log')).write_text(log)
        require(result.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log), 'Native JUnit failed: ' + name)
        value = marker()
        if index is not None:
            require(all(value['observations'].get(flag) is True for flag in FLAGS[index]), 'Native behavior observation is missing.')
            receipt['stages'].append({'name': name, 'command': command, 'exitCode': result.returncode, 'junit': 'OK (1 test)', 'pid': value[f'stage{index + 1}Pid']})
        receipt['observations'] = value['observations']; return value

    def tool(mode):
        command = [node, 'backend/mock/seed-emulator.mjs', mode, namespace]
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=45)
        (out / (mode[2:] + '.log')).write_text(redact(result.stdout + result.stderr))
        require(result.returncode == 0, 'Mock local tool failed: ' + mode)
        return {'command': command, 'exitCode': result.returncode, 'receipt': json.loads(result.stdout)}

    try:
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
        require(managed, 'Managed read-only Test AVD is required.')
        require(shell('getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for Android boot.')
        radios, routes, binding = radio_values(), routing(), binding_digest()
        require(marker(check=False) is None, 'Restart Test AVD instead of overwriting prior mock evidence.')
        controls = {ns: digest(database(ns=ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        auth_before = auth_digest()
        require(database() is None, 'Choose an unused isolated mock namespace.'); owned = True
        database('.settings/rules', method='PUT', value=json.loads((ROOT / 'backend/database.rules.json').read_text()))
        receipt['academicSetup'] = tool('--seed'); receipt['publication'] = tool('--publish')
        catalog = database('catalog'); catalog_sha = digest(catalog)
        require(receipt['publication']['receipt']['moduleAdminImports'] == 0, 'Modules must pass the real publisher.')
        fixture = {'namespace': namespace, 'syntheticOnly': True, 'packages': {}}
        for module_id, versions in catalog['moduleIndex']['mock_computing_v1'].items():
            manifest = json.loads(catalog['modulePackages']['mock_computing_v1'][module_id]['v1']['manifestJson'])
            fixture['packages'][module_id] = {'packageSha256': versions['v1']['packageSha256'], 'pdfSha256': manifest['assets'][0]['sha256']}
        (out / 'fixture.json').write_text(json.dumps(fixture, indent=2) + '\n')
        command = [node, 'backend/mock/serve-assets.mjs', '--port', str(mirror_port)]
        mirror = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        ready, _, _ = select.select([mirror.stdout], [], [], 15); require(ready, 'Local PDF mirror did not start.')
        first_line = mirror.stdout.readline(); mirror_state = json.loads(first_line)
        require(mirror_state.get('status') == 'LISTENING' and mirror_state.get('port') == mirror_port, 'Local mirror failed.')
        receipt['mirror'] = {'command': command, 'ready': mirror_state}
        for label, path in [('apk', ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'),
                            ('testApk', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
            require(path.is_file(), 'Build frozen app/test APKs first.'); device('install', '-r', path)
            receipt[label + 'Sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        remote = '/data/local/tmp/mock-curriculum-' + namespace[-8:] + '.json'
        device('push', out / 'fixture.json', remote); shell('run-as', PACKAGE, 'mkdir', '-p', 'files')
        shell('run-as', PACKAGE, 'cp', remote, 'files/mock-curriculum-fixture.json'); shell('rm', remote)
        online(); value = stage(STAGES[0], 0)
        before = database('users/' + value['uid']); require(len(before.get('lessonAttempts', {})) == 1, 'First original result must be acknowledged.')
        require(len(before['offlinePackages']['mock_computing_v1']) == 3, 'All complete packages need actual rule receipts.')
        partition(); value = stage(STAGES[1], 1)
        require(database('users/' + value['uid']) == before, 'Offline completion changed the server during partition.')
        require(value['pdfReadCount'] == 3, 'All three retained PDFs must render after process restart.')
        for module_id in fixture['packages']:
            png = shell('run-as', PACKAGE, 'cat', f'files/mock-{module_id}-pdf.png').stdout
            require(png.startswith(b'\x89PNG\r\n\x1a\n'), 'Native PDF raster is missing.')
            (out / (module_id + '-offline-native.png')).write_bytes(png)
        online(); value = stage(STAGES[2], 2)
        after = database('users/' + value['uid'])
        require(after['lessonAttempts'] == value['attempts'] and len(after['lessonAttempts']) == 3, 'Independent readback differs from three exact retained results.')
        require(digest(database('catalog')) == catalog_sha, 'The learner changed trusted catalog data.')
        require('scores' not in after and 'courseAttempts' not in after, 'Practice results must not become academic grades.')
        receipt['serverProof'] = {'attemptCount': 3, 'attemptsSha256': digest(after['lessonAttempts']),
            'completeReceiptCount': 3, 'catalogUnchanged': True, 'academicGradesUnchanged': True, 'offlineServerUnchanged': True}
        stage('cleanupOwnedSyntheticAuth'); auth_cleaned = True
        receipt.update(status='PASSED', exitCode=0)
    except BaseException as error:
        receipt['failure'] = str(error); print('Mock curriculum gate failed:', error, flush=True)
    finally:
        errors = []
        try:
            if owned and not auth_cleaned and marker(check=False) is not None:
                online(); stage('cleanupOwnedSyntheticAuth'); auth_cleaned = True
            receipt['ownedAuthCleaned'] = auth_cleaned
            if owned:
                database(method='DELETE'); require(database() is None, 'Owned namespace cleanup failed.'); receipt['isolatedNamespaceCleaned'] = True
            if controls is not None:
                receipt['sharedBeforeSha256'] = controls; receipt['sharedAfterSha256'] = {ns: digest(database(ns=ns)) for ns in controls}
                require(controls == receipt['sharedAfterSha256'], 'Shared database controls changed.')
            if auth_before is not None:
                receipt['authBeforeSha256'], receipt['authAfterSha256'] = auth_before, auth_digest()
                require(auth_before == receipt['authAfterSha256'], 'Shared Auth controls changed.')
        except Exception as error:
            errors.append(str(error))
        try:
            if mirror is not None:
                mirror.terminate(); remaining, _ = mirror.communicate(timeout=15)
                (out / 'mirror.log').write_text(redact(remaining))
                closed = [json.loads(line) for line in remaining.splitlines() if line.strip().startswith(b'{')]
                requests = next((row['requests'] for row in closed if row.get('status') == 'CLOSED'), [])
                receipt['mirrorRequests'] = requests
                if receipt['status'] == 'PASSED':
                    require(len([row for row in requests if row['method'] == 'GET' and row['status'] == 200]) == 3, 'Expected three actual HTTP PDF transfers.')
            if radios is not None:
                shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable')
                shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable'); shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
            if routes is not None:
                for port in ['tcp:9000', 'tcp:9099', 'tcp:8765']:
                    device('reverse', port, routes[port]) if port in routes else device('reverse', '--remove', port, check=False)
                require(routing() == routes, 'Original reverse routes were not restored.')
                require(binding_digest() == binding, 'Normal app workspace binding changed.'); receipt['appBindingPreserved'] = True
        except Exception as error:
            errors.append(str(error))
        receipt['finalSourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}
        if receipt['sourceSha256'] != receipt['finalSourceSha256']:
            errors.append('Source changed during native verification; rebuild and rerun.')
        if errors:
            receipt.update(status='FAILED', exitCode=1, cleanupFailures=errors)
        receipt['remainingGaps'] = ['Synthetic local curriculum and policies do not establish institutional approval.',
            'Local HTTP mirror is real transfer evidence but does not verify public HTTPS hosting.',
            'Repository/renderer exercise does not establish full learner UI, TalkBack, physical-device or human research acceptance.']
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (out / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n'); print('Evidence:', out, flush=True)
    return receipt['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
