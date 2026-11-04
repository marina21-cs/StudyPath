#!/usr/bin/env python3
"""Isolated native v1 policy/score/scenario/GWA gate. Never starts/stops shared services or resets preview data."""
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
import uuid

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
CASES = ['missing', 'mismatch', 'withdrawn', 'expired', 'valid']
UI_CASES = ['prerequisite_only', 'gwa_only', 'scenario_policy_rebase', 'scenario_plan_rebase']
CASES += UI_CASES
NAMESPACE = 'demo-studypath-v1-academic-' + uuid.uuid4().hex[:12]
CURRICULUM = 'v1_academic_fixture'


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def firebase(namespace, path='', method='GET', value=None):
    require(re.fullmatch(r'demo-studypath(?:-[a-z0-9-]+)?', namespace), 'Only local demo namespaces allowed.')
    require(not path.startswith('/') and '..' not in path and '?' not in path, 'Unsafe local path.')
    request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + namespace,
                                    method=method, headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
                                    data=json.dumps(value).encode() if method == 'PUT' else None)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def auth_users():
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
    return users


def auth_digest():
    return digest({'users': auth_users()})


def cleanup_owned_synthetic_auth(namespace, identity):
    """Delete exactly one UID proved by this test's local identity/seed marker; preserve every control account."""
    require(re.fullmatch(r'demo-studypath-v1-academic-[a-f0-9]{12}', namespace), 'Only academic test namespaces allowed.')
    require(identity.get('namespace') == namespace and
            re.fullmatch(r'[A-Za-z0-9_-]{1,128}', identity.get('uid', '')), 'Invalid owned identity marker.')
    uid = identity['uid']
    before = auth_users()
    owned = [row for row in before if row.get('localId') == uid]
    require(len(owned) <= 1, 'Ambiguous owned Auth identity.')
    if not owned:
        return {'deleted': False, 'alreadyAbsent': True}
    email = owned[0].get('email', '')
    require(re.fullmatch(r'v1_academic_[a-f0-9-]{36}@studypath[.]invalid', email), 'Owned UID is not a synthetic academic account.')
    require('email' not in identity or identity['email'] == email, 'Owned email marker differs.')
    request = urllib.request.Request(
        'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchDelete',
        method='POST', headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
        data=json.dumps({'localIds': [uid], 'force': True}).encode())
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    reasons = sorted({str(row.get('message', 'UNKNOWN')).split(':', 1)[0] for row in result.get('errors', [])})
    reasons = [reason if re.fullmatch(r'[A-Z0-9_]{1,80}', reason) else 'REDACTED_ERROR' for reason in reasons]
    require(not reasons, 'Owned synthetic Auth cleanup was rejected: ' + ','.join(reasons))
    after = auth_users()
    require(not any(row.get('localId') == uid for row in after), 'Owned synthetic UID remains after cleanup.')
    require(digest({'users': [row for row in before if row.get('localId') != uid]}) == digest({'users': after}),
            'An Auth control changed during owned cleanup.')
    return {'deleted': True, 'alreadyAbsent': False}


def cleanup_crashed_namespace(namespace, serial):
    require(re.fullmatch(r'emulator-\d+', serial), 'Select the test emulator serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    def device(*args):
        result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=30)
        require(result.returncode == 0, 'Unable to read the owned test identity.')
        return result.stdout
    require(device('emu', 'avd', 'name').decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only test AVD allowed.')
    require(re.fullmatch(r'demo-studypath-v1-academic-[a-f0-9]{12}', namespace), 'Invalid academic namespace.')
    identity = json.loads(device('shell', 'run-as', PACKAGE, 'cat', f'files/v1-academic-{namespace[-12:]}-seed.json'))
    result = cleanup_owned_synthetic_auth(namespace, identity)
    out = ROOT / 'implementation/phases/04-ppt-spec-alignment/05-backend-academic-completion/test-results' / (
        'owned-auth-cleanup-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '.json')
    out.write_text(json.dumps({'namespace': namespace, 'serial': serial, 'localOnly': True, 'accepted': False,
        'ownedIdentitySha256': digest(identity), 'outcome': result, 'controlAccountsPreserved': True}, indent=2) + '\n')
    print('Owned synthetic Auth cleanup complete. Evidence:', out, flush=True)
    return 0

def catalog(case, now):
    override_key = lambda category, assessment: 'override_' + hashlib.sha256(
        ''.join(f'{len(value)}:{value}' for value in [category, assessment]).encode()).hexdigest()[:48]
    course = lambda units: {'title': 'SYNTHETIC TEST ONLY academic course', 'units': units,
        'topicQuery': 'Computing', 'moduleId': 'synthetic_module', 'prerequisitesKnown': True, 'ruleKind': 'NONE',
        'gradePlan': {'version': 'fixture_plan', 'assessmentPlanKnown': True, 'verified': True,
            'label': 'SYNTHETIC TEST ONLY reviewed plan', 'overrideKeys': {override_key('quiz', key): True for key in ['one', 'two']},
            'categories': {'quiz': {'weight': 1.0,
                'aggregation': 'POOLED_POINTS', 'assessments': {'one': {'title': 'One', 'possiblePoints': 10.0},
                    'two': {'title': 'Two', 'possiblePoints': 30.0}}}}}}
    policy = {'schemaVersion': 1, 'curriculumVersion': CURRICULUM, 'levelId': 'fixture_college',
        'educationSector': 'HIGHER_EDUCATION', 'subjectIds': {'fixture_subject': True}, 'approvalStatus': 'APPROVED',
        'approvalRef': 'SYNTHETIC_TEST_ONLY_APPROVAL', 'reviewer': 'SYNTHETIC TEST ONLY', 'reviewedAt': now - 3 * 86400000,
        'sourceRef': 'SYNTHETIC_TEST_ONLY_SOURCE', 'contextRef': 'SYNTHETIC_TEST_ONLY_CONTEXT', 'revision': 1,
        'validFrom': now - 2 * 86400000, 'validUntil': now + 86400000,
        'capabilities': {key: True for key in ['SCORES', 'SCENARIOS', 'GWA', 'PREREQUISITES']},
        'capabilityApprovalRefs': {key: 'SYNTHETIC_TEST_ONLY_' + key for key in ['SCORES', 'SCENARIOS', 'GWA', 'PREREQUISITES']},
        'coursePlans': {key: {'subjectId': 'fixture_subject', 'planVersion': 'fixture_plan'}
                        for key in ['course', 'zero_course', 'legacy_course']},
        'gwaPolicy': {'scaleId': 'fixture_numeric', 'minimumGrade': 1.0, 'maximumGrade': 5.0,
            'attemptInclusion': 'EXPLICIT', 'retakePolicyRef': 'SYNTHETIC_TEST_ONLY_RETAKES',
            'exclusionPolicyRef': 'SYNTHETIC_TEST_ONLY_EXCLUSIONS', 'roundingMode': 'HALF_UP', 'roundingScale': 2}}
    if case == 'mismatch':
        policy['levelId'] = 'different_level'
    elif case == 'withdrawn':
        policy['approvalStatus'] = 'WITHDRAWN'
    elif case == 'expired':
        policy['validUntil'] = now - 86400000
    elif case in ['prerequisite_only', 'gwa_only']:
        selected = 'PREREQUISITES' if case == 'prerequisite_only' else 'GWA'
        policy['capabilities'] = {key: key == selected for key in policy['capabilities']}
        policy['capabilityApprovalRefs'] = {selected: 'SYNTHETIC_TEST_ONLY_' + selected}
    elif case == 'scenario_policy_rebase':
        policy['revision'] = 2
    result = {'courses': {key: course(units) for key, units in [('course', 3.0), ('zero_course', 2.0), ('legacy_course', 3.0)]},
        'learnerPreferenceOptions': {CURRICULUM: {'schemaVersion': 1, 'curriculumVersion': CURRICULUM,
            'levelId': 'fixture_college', 'subjectIds': {'fixture_subject': True}, 'approved': True, 'approvalStatus': 'APPROVED'}}}
    if case != 'missing':
        result['academicPolicies'] = {CURRICULUM: policy}
    if case == 'scenario_plan_rebase':
        for row in result['courses'].values():
            row['gradePlan']['version'] = 'fixture_plan2'
        for row in policy['coursePlans'].values():
            row['planVersion'] = 'fixture_plan2'
    return result


def ui_seed(case, now):
    if case == 'gwa_only':
        return {'courseAttempts': {'gwa_only': {'courseId': 'course', 'term': 'GWA only term', 'status': 'COMPLETED',
            'units': 3.0, 'grade': 2.0, 'scaleId': 'fixture_numeric', 'includeInGwa': True,
            'curriculumVersion': CURRICULUM, 'policyRevision': 1, 'policyRef': 'SYNTHETIC_TEST_ONLY_GWA',
            'planVersion': 'fixture_plan', 'updatedAt': now}}}
    if case.startswith('scenario_'):
        key = 'override_' + hashlib.sha256('4:quiz3:two'.encode()).hexdigest()[:48]
        return {'scenarios': {'retained_scenario': {'courseId': 'course', 'title': 'Retained policy scenario',
            'targetPercent': 75.0, 'baseVersion': 'retained-old-base', 'overrides': {key: 50.0},
            'curriculumVersion': CURRICULUM, 'policyRevision': 1, 'policyRef': 'SYNTHETIC_TEST_ONLY_SCENARIOS',
            'planVersion': 'fixture_plan', 'updatedAt': now}}}
    return {}


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--cleanup-owned-auth':
        return cleanup_crashed_namespace(sys.argv[2], sys.argv[3])
    serial = sys.argv[1] if len(sys.argv) == 2 else 'emulator-5554'
    require(len(sys.argv) <= 2 and re.fullmatch(r'emulator-\d+', serial), 'Select one test emulator serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    out = ROOT / 'implementation/phases/04-ppt-spec-alignment/05-backend-academic-completion/test-results' / (
        'v1-academic-device-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'localOnly': True, 'syntheticOnly': True, 'namespace': NAMESPACE, 'serial': serial, 'stages': [],
        'appDataCleared': False, 'sharedDatabaseReset': False, 'previewBindingChanged': False, 'acceptancePromoted': False}
    routes = None
    shared_before = None
    binding_before = None
    auth_before = None
    namespace_owned = False
    owned_identities = {}

    def device(*args, check=True, timeout=45):
        result = subprocess.run([adb, '-s', serial, *map(str, args)], capture_output=True, timeout=timeout)
        require(not check or result.returncode == 0, 'ADB command failed: ' + str(args[:2]))
        return result

    def routes_now():
        return {row[1]: row[2] for line in device('reverse', '--list').stdout.decode().splitlines()
                if len(row := line.split()) == 3}

    def binding_digest():
        result = device('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        return hashlib.sha256(result.stdout).hexdigest() if result.returncode == 0 else None

    def case_identity(case_prefix, case, case_token, suffix):
        result = device('shell', 'run-as', PACKAGE, 'cat', f'files/{case_prefix}-{suffix}.json', check=False, timeout=5)
        if result.returncode != 0:
            return None
        try:
            identity = json.loads(result.stdout)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None  # A marker being written is not yet a completed handshake.
        if identity.get('case') != case or identity.get('caseToken') != case_token or identity.get('namespace') != NAMESPACE:
            return None
        require(re.fullmatch(r'[A-Za-z0-9_-]{1,128}', identity.get('uid', '')), 'Invalid case identity.')
        return identity

    try:
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only test AVD allowed.')
        require(device('shell', 'getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for Android boot.')
        managed = False
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = path.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and
                    '-avd' in args and args[args.index('-avd') + 1] == 'StudyPath_Lite_Test' and '-read-only' in args and
                    '-port' in args and args[args.index('-port') + 1] == serial.split('-')[1])
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed, 'Managed read-only StudyPath_Lite_Test is required.')
        routes, binding_before = routes_now(), binding_digest()
        shared_before = {ns: digest(firebase(ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        auth_before = auth_digest()
        require(firebase(NAMESPACE) is None, 'Refusing an existing namespace.')
        namespace_owned = True
        firebase(NAMESPACE, '.settings/rules', 'PUT', json.loads((ROOT / 'backend/database.rules.json').read_text()))
        for port in [9000, 9099]:
            device('reverse', f'tcp:{port}', f'tcp:{port}')
        for path in [ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk',
                     ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            require(path.is_file(), 'Build frozen app/test APKs first.')
            device('install', '-r', path)
            receipt['apkSha256' if path.name == 'app-debug.apk' else 'testApkSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        prefix = 'v1-academic-' + NAMESPACE[-12:]
        for case in CASES:
            case_token = uuid.uuid4().hex
            case_prefix = prefix + '-' + case
            print('V1 native academic:', case, flush=True)
            fixture = catalog(case, int(time.time() * 1000))
            firebase(NAMESPACE, method='PUT', value={'catalog': fixture})
            require(firebase(NAMESPACE, 'catalog') == fixture, 'Synthetic catalog readback differs.')
            device('shell', 'am', 'force-stop', PACKAGE)
            command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                PACKAGE + '.AcademicPolicyDeviceTest#nativePolicyAndPlannerContract', '-e', 'v1AcademicNamespace', NAMESPACE,
                '-e', 'v1AcademicCase', case, '-e', 'v1AcademicCaseToken', case_token,
                PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            stage_error = None
            legacy = None
            seeded_ui = None
            deadline = time.monotonic() + 160
            try:
                while process.poll() is None:
                    require(time.monotonic() < deadline, 'Native academic stage timed out.')
                    identity = case_identity(case_prefix, case, case_token, 'identity')
                    if identity is not None:
                        owned_identities[identity['uid']] = identity
                    if (case == 'valid' and legacy is None) or (case in UI_CASES and seeded_ui is None):
                        identity = case_identity(case_prefix, case, case_token, 'seed')
                        if identity is not None:
                            owned_identities[identity['uid']] = identity
                            if case == 'valid':
                                legacy = {'courseId': 'legacy_course', 'assessmentId': 'one', 'categoryId': 'quiz', 'status': 'SCORED',
                                    'possiblePoints': 10, 'earnedPoints': 8, 'planVersion': 'fixture_plan', 'updatedAt': int(time.time() * 1000)}
                                firebase(NAMESPACE, 'users/' + identity['uid'] + '/scores/legacy', 'PUT', legacy)
                            else:
                                seeded_ui = ui_seed(case, int(time.time() * 1000))
                                for collection, rows in seeded_ui.items():
                                    firebase(NAMESPACE, 'users/' + identity['uid'] + '/' + collection, 'PUT', rows)
                            device('shell', 'run-as', PACKAGE, 'touch', f'files/{case_prefix}-ready')
                    time.sleep(.15)
            except Exception as error:
                stage_error = error
            finally:
                if process.poll() is None:
                    process.kill()
                stdout, stderr = process.communicate(timeout=5)
                identity = case_identity(case_prefix, case, case_token, 'identity')
                if identity is not None:
                    owned_identities[identity['uid']] = identity
            log = (stdout + stderr).decode(errors='replace')
            log = re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', log)
            (out / (case + '.log')).write_text(log)
            require(stage_error is None, str(stage_error))
            require(process.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log), 'Native failure in ' + case)
            observation = json.loads(device('shell', 'run-as', PACKAGE, 'cat', f'files/{case_prefix}-observation.json').stdout)
            require(observation['case'] == case and observation['namespace'] == NAMESPACE and observation['syntheticOnly'], 'Wrong observation identity.')
            uid = observation.pop('uid')
            user = firebase(NAMESPACE, 'users/' + uid)
            if case == 'valid':
                require(legacy is not None and user['scores']['legacy'] == legacy, 'Legacy source row was rebound or changed.')
                require(user['scores']['score_zero']['earnedPoints'] == 0 and user['scores']['actual']['earnedPoints'] == 5, 'Score readback differs.')
                require(all(key not in user['scores'] for key in ['unknown', 'points', 'forged', 'rule_forged']), 'A denied score reached server.')
                for key in ['actual', 'score_zero']:
                    require(user['scores'][key]['curriculumVersion'] == CURRICULUM and
                        user['scores'][key]['policyRef'] == 'SYNTHETIC_TEST_ONLY_SCORES', 'Score binding differs.')
                overlay_key = next(row['overrideKey'] for row in observation['observations'] if row['check'] == 'actual-scenario-actual-unchanged')
                require(set(user['scenarios']) == {'scenario'}, 'A denied scenario reached the server.')
                require(user['scenarios']['scenario']['overrides'] == {overlay_key: 50}, 'Scenario override readback differs.')
                require(user['scenarios']['scenario']['title'] == 'Synthetic what if', 'A stale replay replaced the retained scenario.')
                require(user['scenarios']['scenario']['policyRef'] == 'SYNTHETIC_TEST_ONLY_SCENARIOS', 'Scenario binding differs.')
                require(set(user['courseAttempts']) == {'gwa_one', 'gwa_two'}, 'Unexpected included attempt reached server.')
                require(all(row['includeInGwa'] is True and row['scaleId'] == 'fixture_numeric' and
                    row['policyRef'] == 'SYNTHETIC_TEST_ONLY_GWA' for row in user['courseAttempts'].values()), 'GWA binding differs.')
            elif case in UI_CASES:
                require(seeded_ui is not None, 'UI seed handshake was not observed.')
                require(not user.get('scores'), 'Planner UI changed actual scores.')
                if case == 'prerequisite_only':
                    attempts = user.get('courseAttempts', {})
                    require(len(attempts) == 1, 'Expected one prerequisite-only attempt from the UI.')
                    attempt = next(iter(attempts.values()))
                    require(attempt['term'] == 'Prerequisite-only in progress' and attempt['status'] == 'IN_PROGRESS' and
                        attempt['includeInGwa'] is False and 'completionStatus' not in attempt and 'grade' not in attempt and
                        attempt['policyRef'] == 'SYNTHETIC_TEST_ONLY_PREREQUISITES', 'Prerequisite-only UI attempt differs.')
                elif case == 'gwa_only':
                    require(set(user.get('courseAttempts', {})) == {'gwa_only'}, 'GWA UI created a replacement attempt.')
                    changed = user['courseAttempts']['gwa_only']
                    original = seeded_ui['courseAttempts']['gwa_only']
                    require(changed['includeInGwa'] is False and
                        {key: value for key, value in changed.items() if key not in ['includeInGwa', 'updatedAt']} ==
                        {key: value for key, value in original.items() if key not in ['includeInGwa', 'updatedAt']},
                        'Unchecking GWA changed the original attempt fields/binding.')
                else:
                    scenarios = user.get('scenarios', {})
                    require(len(scenarios) == 2 and scenarios['retained_scenario'] == seeded_ui['scenarios']['retained_scenario'],
                        'Rebase replaced the original scenario or failed to create a new ID.')
                    new_id = next(key for key in scenarios if key != 'retained_scenario')
                    changed = scenarios[new_id]
                    require(changed['policyRevision'] == (2 if case == 'scenario_policy_rebase' else 1) and
                        changed['planVersion'] == ('fixture_plan2' if case == 'scenario_plan_rebase' else 'fixture_plan') and
                        changed['overrides'] == seeded_ui['scenarios']['retained_scenario']['overrides'],
                        'Rebased scenario is not bound to the current policy/plan or lost its draft overrides.')
                    observed_id = next(row['newScenarioId'] for row in observation['observations'] if row['check'].startswith('ui-scenario_'))
                    require(new_id == observed_id, 'Native and independent server scenario identities differ.')
            else:
                require(not user.get('scores'), 'Unavailable context created an academic score.')
            require(firebase(NAMESPACE, 'catalog') == fixture, 'Client mutated published fixture.')
            receipt['stages'].append({'case': case, 'exitCode': process.returncode, 'junit': 'OK (1 test)', 'command': command,
                'fixtureSha256': digest(fixture), 'independentUserReadbackSha256': digest(user), 'log': case + '.log', 'observation': observation})
        receipt.update(status='PASSED', exitCode=0)
    except Exception as error:
        receipt['failure'] = str(error)
        print('V1 academic gate failed:', error, flush=True)
    finally:
        cleanup_errors = []
        receipt['ownedAuthCleanup'] = []
        if receipt['status'] != 'PASSED' and owned_identities:
            try:
                device('shell', 'am', 'force-stop', PACKAGE)
            except Exception as error:
                cleanup_errors.append('Stop failed test process: ' + str(error))
        for identity in owned_identities.values():
            try:
                receipt['ownedAuthCleanup'].append(cleanup_owned_synthetic_auth(NAMESPACE, identity))
            except Exception as error:
                cleanup_errors.append('Owned synthetic Auth cleanup: ' + str(error))
        try:
            if namespace_owned:
                firebase(NAMESPACE, method='DELETE')
                require(firebase(NAMESPACE) is None, 'Isolated namespace cleanup failed.')
                receipt['isolatedNamespaceCleaned'] = True
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if shared_before is not None:
                receipt['sharedBeforeSha256'] = shared_before
                receipt['sharedAfterSha256'] = {ns: digest(firebase(ns)) for ns in shared_before}
                require(receipt['sharedBeforeSha256'] == receipt['sharedAfterSha256'], 'Shared database controls changed.')
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if auth_before is not None:
                receipt['authBeforeSha256'], receipt['authAfterSha256'] = auth_before, auth_digest()
                require(receipt['authBeforeSha256'] == receipt['authAfterSha256'], 'Shared Auth controls changed; inspect owned synthetic cleanup.')
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if routes is not None:
                for port in ['tcp:9000', 'tcp:9099']:
                    if port in routes:
                        device('reverse', port, routes[port])
                    else:
                        device('reverse', '--remove', port, check=False)
                require(routes_now() == routes, 'Original reverse routes not restored.')
                require(binding_digest() == binding_before, 'Original workspace binding changed.')
                receipt['appBindingPreserved'] = True
        except Exception as error:
            cleanup_errors.append(str(error))
        if cleanup_errors:
            receipt.update(status='FAILED', exitCode=1, cleanupFailures=cleanup_errors)
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        paths = [Path(__file__), ROOT / 'android/app/src/androidTest/java/com/studypath/app/AcademicPolicyDeviceTest.kt',
            ROOT / 'backend/database.rules.json', ROOT / 'android/app/src/main/java/com/studypath/app/data/FirebaseStudyRepository.kt']
        receipt['sourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        receipt['remainingGaps'] = ['Synthetic local repository/domain and selected Planner form journeys only; broad accessibility/performance not accepted.',
            'No actual policy/content approval, selected-cloud acceptance, 48-hour test or participant evidence.',
            'Only two explicit worked fixture calculations here; >=30 independently externally worked/reviewed cases remain a separate gate.']
        (out / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('Evidence:', out, flush=True)
    return receipt['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
