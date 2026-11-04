#!/usr/bin/env python3
"""B13 native repository checks in a fresh, isolated local Firebase namespace.

Never resets shared services or changes the preview's binding, data or offline queue.
Build app/test APKs first and run on the managed read-only StudyPath_Lite_Test AVD.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.studypath.app'
CASES = ['nested', 'empty', 'duplicate', 'cycle', 'malformed', 'unavailable']
NAMESPACE = 'demo-studypath-b13-device-' + uuid.uuid4().hex[:12]


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def canonical_rtdb(value):
    # RTDB serializes dense numeric child maps as arrays on readback.
    if isinstance(value, list):
        return [canonical_rtdb(child) for child in value]
    if isinstance(value, dict):
        if value and set(value) == {str(index) for index in range(len(value))}:
            return [canonical_rtdb(value[str(index)]) for index in range(len(value))]
        return {key: canonical_rtdb(child) for key, child in value.items()}
    return value


def firebase(namespace, path='', method='GET', value=None):
    # All admin requests are fixed to local emulators; no cloud credentials are accepted.
    request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + namespace,
                                    method=method, headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
                                    data=json.dumps(value).encode() if method == 'PUT' else None)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def course():
    return {'title': 'Synthetic prerequisite fixture', 'units': 3, 'topicQuery': 'Programming', 'moduleId': 'synthetic',
            'prerequisitesKnown': True, 'ruleKind': 'NONE',
            'gradePlan': {'version':'prerequisite_fixture_v1','assessmentPlanKnown':True,'verified':True,
                          'label':'SYNTHETIC prerequisite device fixture only','categories':{'test':{'weight':1,'aggregation':'POOLED_POINTS','assessments':{'test':{'title':'Synthetic','possiblePoints':100}}}}}}


def catalog(case):
    courses = {key: course() for key in ['a', 'b', 'c', 'target']}
    leaf = lambda key: {'kind': 'COURSE', 'courseId': key}
    document = {'schemaVersion': 1, 'expression': {'kind': 'AND', 'children': {
        '0': leaf('a'), '1': {'kind': 'OR', 'children': {'0': leaf('b'), '1': leaf('c')}}}}}
    if case == 'nested':
        courses['target']['prerequisiteExpression'] = document
    elif case == 'empty':
        courses['target']['ruleKind'] = 'ALL_OF'
    elif case == 'duplicate':
        courses['target']['prerequisiteExpression'] = {'schemaVersion': 1, 'expression': {
            'kind': 'AND', 'children': {'0': leaf('a'), '1': leaf('a')}}}
    elif case == 'cycle':
        courses['target'].update(ruleKind='ALL_OF', prerequisiteCourseIds={'a': True})
        courses['a'].update(ruleKind='ALL_OF', prerequisiteCourseIds={'target': True})
    elif case == 'malformed':
        courses['target']['prerequisiteExpression'] = {'schemaVersion': 1,
            'expression': {**leaf('a'), 'unexpected': True}}
    elif case == 'unavailable':
        courses['target'].update(prerequisitesKnown=False, prerequisiteExpression={
            'schemaVersion': 1, 'expression': {'kind': 'NONE'}})
    curriculum = 'b13_fixture_v1'
    options = {'schemaVersion':1,'curriculumVersion':curriculum,'levelId':'test_college','subjectIds':{'computing':True},'approved':True,'approvalStatus':'APPROVED'}
    policy = {'schemaVersion':1,'curriculumVersion':curriculum,'levelId':'test_college','educationSector':'HIGHER_EDUCATION',
        'subjectIds':{'computing':True},'approvalStatus':'APPROVED','approvalRef':'SYNTHETIC_TEST_ONLY','reviewer':'Fabricated test reviewer',
        'reviewedAt':1792627200000,'sourceRef':'synthetic_source','contextRef':'synthetic_context','revision':1,'validFrom':0,'validUntil':4102444800000,
        'capabilities':{'SCORES':True,'SCENARIOS':False,'GWA':False,'PREREQUISITES':True},
        'capabilityApprovalRefs':{'SCORES':'test_score_ref','PREREQUISITES':'test_prerequisite_ref'},
        'coursePlans':{key:{'subjectId':'computing','planVersion':'prerequisite_fixture_v1'} for key in courses}}
    return {'catalog': {'courses': courses,'learnerPreferenceOptions':{curriculum:options},'academicPolicies':{curriculum:policy}}}


def main():
    serial = sys.argv[1] if len(sys.argv) == 2 else 'emulator-5554'
    require(len(sys.argv) <= 2 and re.fullmatch(r'emulator-\d+', serial), 'Select one test emulator serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    out = ROOT/'implementation/phases/04-ppt-spec-alignment/05-backend-academic-completion/test-results'/(
        'prerequisite-device-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'localOnly': True, 'namespace': NAMESPACE, 'serial': serial, 'avd': 'StudyPath_Lite_Test', 'stages': [],
               'appDataCleared': False, 'sharedDatabaseReset': False, 'previewBindingChanged': False,
               'acceptancePromoted': False}
    failure = 1
    routes = None
    shared_before = None
    binding_before = None
    namespace_owned = False

    def device(*args, check=True, timeout=45):
        result = subprocess.run([adb, '-s', serial, *map(str, args)], capture_output=True, timeout=timeout)
        require(not check or result.returncode == 0, 'ADB operation failed: ' + str(args[:2]))
        return result

    def routes_now():
        return {row[1]: row[2] for line in device('reverse', '--list').stdout.decode().splitlines()
                if len(row := line.split()) == 3}

    def binding_digest():
        result = device('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        return hashlib.sha256(result.stdout).hexdigest() if result.returncode == 0 else None

    try:
        require(device('emu', 'avd', 'name').stdout.decode().splitlines()[0] == 'StudyPath_Lite_Test', 'Only test AVD allowed.')
        require(device('shell', 'getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for Android boot.')
        managed = False
        for path in Path('/proc').glob('[0-9]*/cmdline'):
            try:
                args = path.read_bytes().decode().strip('\0').split('\0')
                managed |= bool(args and Path(args[0]).name.startswith(('qemu-system-', 'emulator')) and
                    '-avd' in args and args[args.index('-avd')+1] == 'StudyPath_Lite_Test' and '-read-only' in args and
                    '-port' in args and args[args.index('-port')+1] == serial.split('-')[1])
            except (OSError, UnicodeError, IndexError):
                pass
        require(managed, 'A managed read-only test AVD is required.')
        routes = routes_now()
        binding_before = binding_digest()
        shared_before = {ns: digest(firebase(ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        require(firebase(NAMESPACE) is None, 'Refusing an existing namespace.')
        namespace_owned = True
        firebase(NAMESPACE, '.settings/rules', 'PUT', json.loads((ROOT/'backend/database.rules.json').read_text()))
        for port in [9000, 9099]:
            device('reverse', f'tcp:{port}', f'tcp:{port}')
        for path in [ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',
                     ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            require(path.is_file(), 'Build the APKs first.')
            device('install', '-r', path)
            receipt['apkSha256' if path.name == 'app-debug.apk' else 'testApkSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        for case in CASES:
            print('B13 native prerequisite:', case, flush=True)
            expected = catalog(case)
            firebase(NAMESPACE, method='PUT', value=expected)
            require(canonical_rtdb(firebase(NAMESPACE, 'catalog')) == canonical_rtdb(expected['catalog']), 'Fixture readback differs.')
            device('shell', 'am', 'force-stop', PACKAGE)
            command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                       PACKAGE + '.PrerequisiteCatalogDeviceTest#repositoryPrerequisiteFixture',
                       '-e', 'b13Namespace', NAMESPACE, '-e', 'b13Case', case,
                       PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
            result = subprocess.run(command, capture_output=True, timeout=150)
            log = (result.stdout + result.stderr).decode(errors='replace')
            log = re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', log)
            (out/(case + '.log')).write_text(log)
            require(result.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                    not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log),
                    'Instrumentation failed in ' + case)
            observation = json.loads(device('shell', 'run-as', PACKAGE, 'cat', f'files/b13-prerequisite-{case}.json').stdout)
            require(observation['case'] == case and observation['namespace'] == NAMESPACE, 'Wrong native fixture identity.')
            uid = observation.pop('uid')
            require(all(row['actual'] == row['expected'] for row in observation['observations']), 'Native outcome differs.')
            if case == 'nested':
                attempts = firebase(NAMESPACE, 'users/' + uid + '/courseAttempts')
                require(set(attempts) == {'attempt_a', 'attempt_b', 'attempt_c'}, 'Expected exactly three server attempts.')
                require([attempts['attempt_' + key]['completionStatus'] for key in ['a', 'b', 'c']] ==
                        ['PASSED', 'FAILED', 'FAILED'], 'Independent final completion readback differs.')
                require(all(row['includeInGwa'] is False for row in attempts.values()), 'Fixture entered GWA unexpectedly.')
                observation['independentAttemptReadbackPassed'] = True
            require(canonical_rtdb(firebase(NAMESPACE, 'catalog')) == canonical_rtdb(expected['catalog']), 'Client mutated catalog.')
            receipt['stages'].append({'case': case, 'exitCode': result.returncode, 'junit': 'OK (1 test)',
                                      'command': command, 'log': case + '.log', 'fixtureSha256': digest(expected),
                                      'observation': observation})
        receipt['status'] = 'PASSED'
        receipt['exitCode'] = failure = 0
    except Exception as error:
        receipt['failure'] = str(error)
        print('B13 check failed:', error, flush=True)
    finally:
        cleanup_errors = []
        try:
            if namespace_owned:
                firebase(NAMESPACE, method='DELETE')
                receipt['isolatedNamespaceCleaned'] = firebase(NAMESPACE) is None
                require(receipt['isolatedNamespaceCleaned'], 'Isolated namespace cleanup failed.')
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if shared_before is not None:
                receipt['sharedBeforeSha256'] = shared_before
                receipt['sharedAfterSha256'] = {ns: digest(firebase(ns)) for ns in shared_before}
                require(receipt['sharedBeforeSha256'] == receipt['sharedAfterSha256'], 'Shared database changed.')
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if routes is not None:
                for port in ['tcp:9000', 'tcp:9099']:
                    if port in routes:
                        device('reverse', port, routes[port])
                    else:
                        device('reverse', '--remove', port, check=False)
                require(routes_now() == routes, 'Original ADB routes not restored.')
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if routes is not None:
                require(binding_digest() == binding_before, 'Original app binding changed.')
                receipt['appBindingPreserved'] = True
        except Exception as error:
            cleanup_errors.append(str(error))
        if cleanup_errors:
            receipt.update(status='FAILED', exitCode=1, cleanupFailures=cleanup_errors)
            failure = 1
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        receipt['sourceSha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [
            Path(__file__), ROOT/'android/app/src/androidTest/java/com/studypath/app/PrerequisiteCatalogDeviceTest.kt',
            ROOT/'backend/database.rules.json']}
        receipt['remainingGaps'] = ['Synthetic local prerequisite component only; reviewed curriculum/policy and full B13 planner journeys remain open.',
                                  'B12 dependency, scenario/GWA/score policy enforcement, cloud and other expanded acceptance gates remain open.']
        (out/'verification.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('Evidence:', out, flush=True)
    return failure


if __name__ == '__main__':
    raise SystemExit(main())
