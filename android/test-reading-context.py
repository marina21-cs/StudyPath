#!/usr/bin/env python3
"""B12 live-source/native-note lifecycle gate in an owned, isolated local namespace.

Build APKs first. Uses only the managed read-only StudyPath_Lite_Test AVD. Never
clears app data, replaces the normal binding, or resets shared Firebase data.
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
FIXTURE = 'files/b12-context-fixture.json'
STAGES = [('liveSaveAndNote', True), ('offlineEditAndQueue', False), ('offlineReopen', False),
          ('reconnectAndConfirm', True), ('queueDeletionOffline', False), ('offlineDeletionReopen', False),
          ('reconnectDeletion', True)]


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def firebase(namespace, path='', method='GET', value=None):
    request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + namespace,
                                    method=method, headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
                                    data=json.dumps(value).encode() if method == 'PUT' else None)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def catalog_fixture():
    bundled = Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node'
    node = os.environ.get('STUDYPATH_NODE', str(bundled) if bundled.is_file() else 'node')
    result = subprocess.run([node, str(ROOT/'backend/interactive/synthetic-lesson-fixture.mjs')],
                            capture_output=True, timeout=30, check=True)
    fixture = json.loads(result.stdout)
    require(fixture['testOnly'] is True, 'Only the fabricated isolated lesson fixture is allowed.')
    catalog = fixture['catalog']
    summary = catalog['moduleIndex']['fixture_v1']['fixture_module']['v1']
    review = {'status': 'APPROVED', 'reviewer': 'FABRICATED LOCAL TEST ONLY', 'reviewedOn': '2026-10-28',
              'approvalReference': 'FABRICATED TEST CLAIM; NEVER PUBLISH'}
    common = {'schemaVersion': 1, 'curriculumVersion': 'fixture_v1', 'subjectId': 'fixture_subject',
              'query': 'Computer programming', 'review': review}
    catalog['readingContexts'] = {
        'module_test': {**common, 'kind': 'MODULE_CONCEPT', 'moduleId': 'fixture_module', 'moduleVersion': 1,
                        'packageSha256': summary['packageSha256'], 'conceptId': 'parts'},
        'weak_test': {**common, 'kind': 'WEAK_CATEGORY', 'courseId': 'fixture_course',
                      'planVersion': 'reviewed_fixture_v1', 'categoryId': 'quizzes'}}
    catalog['courses'] = {'fixture_course': {'title': 'Synthetic local course', 'units': 3,
        'topicQuery': 'PRIVATE FIXTURE TOPIC MUST NOT BECOME A QUERY', 'moduleId': 'fixture_module',
        'prerequisitesKnown': True, 'ruleKind': 'NONE', 'gradePlan': {'version': 'reviewed_fixture_v1',
            'assessmentPlanKnown': True, 'verified': True, 'label': 'FABRICATED TEST ONLY',
            'categories': {'quizzes': {'weight': 1, 'aggregation': 'POOLED_POINTS',
                                      'assessments': {'q1': {'title': 'Synthetic check', 'possiblePoints': 10}}}}}}}
    return {'catalog': catalog}


def main():
    serial = sys.argv[1] if len(sys.argv) == 2 else 'emulator-5554'
    require(len(sys.argv) <= 2 and re.fullmatch(r'emulator-\d+', serial), 'Select one test emulator serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home()/'Android/Sdk/platform-tools/adb'))
    namespace = 'demo-studypath-b12-device-' + uuid.uuid4().hex[:12]
    out = ROOT/'implementation/phases/04-ppt-spec-alignment/04-backend-api-reading-context/test-results'/(
        'reading-context-device-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'namespace': namespace, 'serial': serial, 'avd': 'StudyPath_Lite_Test', 'localOnly': True,
               'liveWikipediaRequired': True, 'testApprovalMetadataIsFabricated': True, 'stages': [],
               'appDataCleared': False, 'packagesUninstalled': False, 'sharedDatabaseReset': False,
               'normalBindingChanged': False, 'acceptancePromoted': False}
    known_private = set()
    old_handlers = {}
    lock = ROOT/'implementation/phases/03-validation/lite-emulator/test-results/.runner-lock'
    lock_owned = False
    owned = managed = installed = False
    routes = radios = shared_before = binding_before = font_scale = None
    failure = 1

    def device(*args, check=True, timeout=45):
        result = subprocess.run([adb, '-s', serial, *map(str, args)], capture_output=True, timeout=timeout)
        if check and args[:4] == ('shell', 'run-as', PACKAGE, 'cat'):
            for attempt in range(2):
                if result.returncode == 0:
                    break
                receipt.setdefault('readOnlyAdbRetries', []).append({
                    'operation': list(map(str, args[:4])), 'retry': attempt + 1,
                    'exitCode': result.returncode, 'stderr': redact(result.stderr)[:2000]})
                time.sleep(.5 * (attempt + 1))
                result = subprocess.run([adb, '-s', serial, *map(str, args)], capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            receipt.setdefault('failedDeviceOperations', []).append({
                'operation': list(map(str, args[:3])), 'exitCode': result.returncode,
                'stderr': redact(result.stderr)[:2000]})
        require(not check or result.returncode == 0, 'ADB operation failed: ' + str(args[:2]))
        return result

    def shell(*args, **kwargs):
        return device('shell', *args, **kwargs)

    def binding_digest():
        result = shell('run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        return hashlib.sha256(result.stdout).hexdigest() if result.returncode == 0 else None

    def routes_now():
        return {row[1]: row[2] for line in device('reverse', '--list').stdout.decode().splitlines()
                if len(row := line.split()) == 3}

    def radios_now():
        return {key: int(shell('settings', 'get', 'global', key).stdout.strip())
                for key in ['airplane_mode_on', 'wifi_on', 'mobile_data']}

    def transport(online):
        if online:
            shell('cmd', 'connectivity', 'airplane-mode', 'disable')
            shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable')
            shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
            for port in [9000, 9099]:
                device('reverse', f'tcp:{port}', f'tcp:{port}')
            expected = radios
        else:
            for port in [9000, 9099]:
                device('reverse', '--remove', f'tcp:{port}', check=False)
            shell('cmd', 'connectivity', 'airplane-mode', 'enable')
            shell('svc', 'wifi', 'disable')
            shell('svc', 'data', 'disable')
            expected = {'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0}
        deadline = time.monotonic() + 12
        while radios_now() != expected:
            require(time.monotonic() < deadline, 'Guest transport did not reach the required state.')
            time.sleep(.2)

    def fixture():
        value = json.loads(shell('run-as', PACKAGE, 'cat', FIXTURE).stdout)
        require(value['namespace'] == namespace, 'Wrong native fixture namespace.')
        known_private.update(value.get(key, '') for key in ['uid', 'email'])
        return value

    def redact(raw):
        value = raw.decode(errors='replace')
        for private in known_private:
            if private:
                value = value.replace(private, '<synthetic-identity>')
        value = re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', value)
        return re.sub(r'eyJ[A-Za-z0-9_.-]+', '<redacted-token>', value)

    def instrumentation(stage):
        shell('am', 'force-stop', PACKAGE)
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   PACKAGE + '.ReadingContextDeviceTest#' + stage, '-e', 'b12Namespace', namespace,
                   PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        try:
            result = subprocess.run(command, capture_output=True, timeout=240)
            raw = result.stdout + result.stderr
        except subprocess.TimeoutExpired as error:
            raw = (error.stdout or b'') + (error.stderr or b'')
            (out/(stage + '.log')).write_text(redact(raw))
            raise RuntimeError('Native stage timed out: ' + stage) from None
        try:
            current = fixture()
            for name in current.get('screenshots', []):
                require(re.fullmatch(r'[A-Za-z0-9_-]{1,80}', name), 'Unexpected screenshot name.')
                captured = shell('run-as', PACKAGE, 'cat', 'files/b12-context-screenshots/' + name + '.png', check=False)
                if captured.returncode == 0 and captured.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                    (out/(name + '.png')).write_bytes(captured.stdout)
        except Exception:
            pass
        log = redact(raw)
        (out/(stage + '.log')).write_text(log)
        require(result.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log),
                'Native instrumentation failed: ' + stage)
        return command, result.returncode

    def interrupted(_number, _frame):
        raise InterruptedError('Device gate interrupted.')

    try:
        for signum in [signal.SIGINT, signal.SIGTERM]:
            old_handlers[signum] = signal.signal(signum, interrupted)
        lock.parent.mkdir(parents=True, exist_ok=True)
        lock.mkdir()
        lock_owned = True
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only test AVD allowed.')
        require(shell('getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for Android boot.')
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = path.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and
                    '-avd' in args and args[args.index('-avd')+1] == 'StudyPath_Lite_Test' and '-read-only' in args and
                    '-port' in args and args[args.index('-port')+1] == serial.split('-')[1])
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed, 'A managed read-only test AVD is required.')
        routes, radios, binding_before = routes_now(), radios_now(), binding_digest()
        font_scale = shell('settings', 'get', 'system', 'font_scale').stdout.decode().strip()
        require(radios['airplane_mode_on'] == 0 and (radios['wifi_on'] or radios['mobile_data']), 'Start the test online.')
        require(shell('run-as', PACKAGE, 'test', '-f', FIXTURE, check=False).returncode != 0,
                'Close/restart the read-only test AVD; a previous B12 fixture exists.')
        shared_before = {ns: digest(firebase(ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        require(firebase(namespace) is None, 'Refusing an existing namespace.')
        owned = True
        firebase(namespace, '.settings/rules', 'PUT', json.loads((ROOT/'backend/database.rules.json').read_text()))
        seeded = catalog_fixture()
        firebase(namespace, method='PUT', value=seeded)
        catalog_digest = digest(firebase(namespace, 'catalog'))
        receipt['catalogSha256'] = catalog_digest
        for apk in [ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',
                    ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            require(apk.is_file(), 'Build both APKs first.')
            device('install', '-r', apk)
            receipt['apkSha256' if apk.name == 'app-debug.apk' else 'testApkSha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        installed = True
        original_source = confirmed_note = pending_note = None
        for stage, online in STAGES:
            print('B12 native reading:', stage, flush=True)
            shell('settings', 'put', 'system', 'font_scale', '1.0' if stage == 'liveSaveAndNote' else '2.0')
            transport(online)
            command, code = instrumentation(stage)
            value = fixture()
            require(value['observations'].get(stage) is True, 'Missing native observation: ' + stage)
            uid, saved_id = value['uid'], value['savedId']
            source = firebase(namespace, 'users/' + uid + '/savedReadings/' + saved_id)
            annotation = firebase(namespace, 'users/' + uid + '/readingAnnotations/' + saved_id)
            if stage == 'liveSaveAndNote':
                require(source and annotation, 'Initial source/note were not independently confirmed.')
                original_source, confirmed_note = source, annotation
                require(source['revisionId'] == value['revisionId'], 'Wrong server revision.')
                started_ms = dt.datetime.fromisoformat(receipt['startedAtUtc']).timestamp() * 1000
                require(started_ms - 2000 <= source['retrievedAt'] <= time.time() * 1000 + 2000,
                        'The detail receipt was not retrieved or revalidated during this run.')
                require(any(row['path'].endswith('/search/page') and row['code'] == 200 for row in value['liveNetwork']) and
                        any(row['path'].endswith('/with_html') and row['code'] == 200 for row in value['liveNetwork']),
                        'Missing real Wikipedia search/detail network receipts.')
                receipt['liveNetwork'] = value['liveNetwork']
                geometry = value['toolbarGeometry']
                require(geometry['titleAndStatusOverlap'] is False and geometry['widthDp'] >= 47.5 and geometry['heightDp'] >= 47.5,
                        'Save-place toolbar geometry did not meet the native assertions.')
                receipt['toolbarGeometry'] = geometry
                layout = value.get('readingLayout', {})
                viewport = layout.get('initialViewport', {})
                require(viewport.get('fontScale') == 1 and viewport.get('notesInitiallyCollapsed') is True and
                        viewport.get('completeArticleLinesVisible', 0) >= 2,
                        'Missing normal-size content-first reading viewport proof.')
                require(all(layout.get(flag) is True for flag in [
                    'draftSurvivesCollapseReopen', 'collapsedDraftAndDisclosureSurviveRecreation',
                    'expandedDraftAndDisclosureSurviveRecreation', 'collapsedDraftSurvivesUnrelatedStateEmission',
                    'recreatedDraftKeepsSavedReadingIdentity', 'confirmedPersonalStatusAvailableWhenCollapsed']),
                    'Missing note draft/disclosure recovery proof.')
                require(annotation['position'] > 0 and annotation['note'] == 'Synthetic online note', 'Wrong initial server note/position.')
                context = firebase(namespace, 'users/' + uid + '/readingContexts/' + saved_id)
                require(context == {'schemaVersion': 1, 'contextId': 'module_test'}, 'Reviewed context was not stored separately.')
            elif stage in ['offlineEditAndQueue', 'offlineReopen']:
                require(source == original_source and annotation == confirmed_note, 'Offline edit unexpectedly changed server data.')
                pending_note = value['pendingAnnotation']
                require(firebase(namespace, 'users/' + uid + '/savedReadings/' + value['legacyId']) is None,
                        'Queued legacy fixture unexpectedly reached the server while offline.')
                if stage == 'offlineReopen':
                    require(value.get('legacyQueueExportedBeforeReconnect') is True,
                            'Old SDK queue was not exported before reconnection.')
                    layout = value.get('readingLayout', {})
                    require(layout.get('pendingNoteAndExactPositionRestoreAt200Percent') is True and
                            layout.get('largeTextDraftSurvivesCollapseReopen') is True and
                            layout.get('personalSyncStatusAvailableWhenCollapsed') is True,
                            'Missing large-text note and exact-position recovery proof.')
                    for key in ['largeTextControls', 'largeTextSourceDialog']:
                        measured = layout.get(key, {})
                        require(measured.get('fontScale', 0) >= 1.95 and measured.get('targets'),
                                'Missing 200% reading/source control measurements.')
                        require(all(row.get('widthDp', 0) >= 47.5 and row.get('heightDp', 0) >= 47.5 and
                                    row.get('atLeast90PercentVisible') is True and row.get('ellipsizedOrClipped') is False
                                    for row in measured['targets']), 'A reading/source target failed geometry checks.')
            elif stage == 'reconnectAndConfirm':
                require(source == original_source and annotation == pending_note, 'Exact pending edit was not independently confirmed.')
                confirmed_note = annotation
                require(value.get('legacyQueueRetainedAfterServerRollback') is True and
                        firebase(namespace, 'users/' + uid + '/savedReadings/' + value['legacyId']) is None,
                        'Old queued source did not survive server rejection in the native journal.')
            elif stage in ['queueDeletionOffline', 'offlineDeletionReopen']:
                require(source == original_source and annotation == confirmed_note, 'Offline deletion removed server records prematurely.')
            else:
                require(source is None and annotation is None, 'Linked reading data still exists after confirmed removal.')
                require(firebase(namespace, 'users/' + uid + '/readingContexts/' + saved_id) is None, 'Context survived linked removal.')
                slots = firebase(namespace, 'users/' + uid + '/savedReadingSlots') or {}
                require(saved_id not in slots.values(), 'A reading slot survived linked removal.')
            require(digest(firebase(namespace, 'catalog')) == catalog_digest, 'Native client changed test catalog.')
            receipt['readingLayout'] = value.get('readingLayout', {})
            receipt['stages'].append({'name': stage, 'online': online,
                                      'fontScale': 1.0 if stage == 'liveSaveAndNote' else 2.0, 'exitCode': code, 'command': command,
                                      'pid': value['pids'][stage], 'junit': 'OK (1 test)', 'log': stage + '.log',
                                      'observed': True, 'independentServerReadbackPassed': True})
            for name in value.get('screenshots', []):
                result = shell('run-as', PACKAGE, 'cat', 'files/b12-context-screenshots/' + name + '.png', check=False)
                if result.returncode == 0 and result.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                    (out/(name + '.png')).write_bytes(result.stdout)
        require(len({row['pid'] for row in receipt['stages']}) == len(STAGES), 'Every lifecycle stage needs a fresh process.')
        receipt['legacyQueueExportedBeforeReconnect'] = value['legacyQueueExportedBeforeReconnect']
        receipt['legacyQueueRetainedAfterServerRollback'] = value['legacyQueueRetainedAfterServerRollback']
        receipt.update(status='PASSED', exitCode=0)
        failure = 0
    except Exception as error:
        receipt['failure'] = str(error)
        print('B12 check failed:', error, flush=True)
    finally:
        cleanup = []
        if managed and radios is not None:
            try:
                transport(True)
                if installed:
                    instrumentation('cleanupIdentity')
                    receipt['syntheticAuthCleanupPassed'] = True
            except Exception as error:
                cleanup.append('Identity/transport cleanup: ' + str(error))
        if owned:
            try:
                firebase(namespace, method='DELETE')
                require(firebase(namespace) is None, 'Owned namespace was not cleaned.')
                receipt['isolatedNamespaceCleaned'] = True
            except Exception as error:
                cleanup.append('Namespace cleanup: ' + str(error))
        if managed and routes is not None:
            try:
                if font_scale is not None:
                    if font_scale == 'null':
                        shell('settings', 'delete', 'system', 'font_scale')
                    else:
                        shell('settings', 'put', 'system', 'font_scale', font_scale)
                    require(shell('settings', 'get', 'system', 'font_scale').stdout.decode().strip() == font_scale,
                            'Original font scale was not restored.')
                    receipt['fontScaleRestored'] = True
                for port in ['tcp:9000', 'tcp:9099']:
                    if port in routes:
                        device('reverse', port, routes[port])
                    else:
                        device('reverse', '--remove', port, check=False)
                require(routes_now() == routes and radios_now() == radios, 'Original transport was not restored.')
                require(binding_digest() == binding_before, 'Normal app binding changed.')
                receipt['normalBindingPreserved'] = receipt['transportRestored'] = True
            except Exception as error:
                cleanup.append('Device restoration: ' + str(error))
        if shared_before is not None:
            try:
                receipt['sharedBeforeSha256'] = shared_before
                receipt['sharedAfterSha256'] = {ns: digest(firebase(ns)) for ns in shared_before}
                require(shared_before == receipt['sharedAfterSha256'], 'Shared database changed.')
            except Exception as error:
                cleanup.append('Shared data guard: ' + str(error))
        if cleanup:
            receipt.update(status='FAILED', exitCode=1, cleanupFailures=cleanup)
            failure = 1
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        receipt['sourceSha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [
            Path(__file__), ROOT/'android/app/src/androidTest/java/com/studypath/app/ReadingContextDeviceTest.kt',
            ROOT/'backend/database.rules.json'] if p.is_file()}
        receipt['remainingGaps'] = ['Synthetic local fixture review metadata does not approve a real curriculum or module.',
                                  'Reminder cleanup integration and cloud acceptance remain separate gates.']
        (out/'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
        if lock_owned:
            lock.rmdir()
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)
        print('Evidence:', out, flush=True)
    return failure


if __name__ == '__main__':
    raise SystemExit(main())
