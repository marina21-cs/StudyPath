#!/usr/bin/env python3
"""Two-identity native family sharing gate using isolated local rules and actual fresh ID-token REST.

Requires already-running Auth/RTDB, frozen APKs and a fresh read-only StudyPath_Lite_Test.
No shared reset, app-data clear, cloud write, normal account switch or service lifecycle change.
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
CLASS = PACKAGE + '.FamilySharingDeviceTest'
MARKER = 'files/family-sharing-device.json'
FLAGS = ['realAcknowledgedAuthoredLessonProvidesProtectedNonzeroSource',
         'independentRecipientExplicitlyAcceptsWithoutReceivingAccess', 'freshRecipientViewShowsOnlyCountAsOfAndExpiry',
         'offlineRecipientClearsAndCannotReusePreviouslyViewedSummary', 'ownerExplicitRevokeDeniesFreshIndependentRecipientRead',
         'staleGenerationCannotRevokeOrReadRegrantedSlot', 'actualExpiryClearsVisibleSummaryAndDeniesFreshRead',
         'hostReconnectWaitsForFreshAuthAndPolicyBeforeExplicitUiRetry']
PUBLISH = """
import {createMockCurriculum} from './backend/mock/curriculum.mjs';
import {createLocalTransport} from './backend/catalog/local-publication-transport.mjs';
import {preparePublication,publishIntoCatalog,executeCatalogOperation} from './backend/catalog/publication.mjs';
const namespace=process.argv[1]; if(!/^demo-studypath-family-device-[a-f0-9]{8}$/.test(namespace))throw Error('Isolated family namespace required');
const fixture=createMockCurriculum(), transport=createLocalTransport({host:'127.0.0.1:9000',namespace}), publications=[];
for(const row of fixture.candidates){const prepared=preparePublication(row.publicationInput); publications.push({moduleId:row.moduleId,...await executeCatalogOperation({transport,operation:current=>publishIntoCatalog(current,prepared),apply:true})});}
console.log(JSON.stringify({syntheticOnly:true,moduleAdminImports:0,publications,sharingLessonKeys:fixture.catalog.sharingLessonKeys,academic:fixture.academic,courses:fixture.catalog.courses}));
"""
FIXTURE = """
import {readFile} from 'node:fs/promises';
import {createFamilyFixture} from './backend/sharing/family-fixture.mjs';
import {createLocalFamilyRules} from './backend/sharing/firebase-family-rules.mjs';
const [namespace,ownerUid,recipientUid]=process.argv.slice(1); if(!/^demo-studypath-family-device-[a-f0-9]{8}$/.test(namespace))throw Error('Isolated family namespace required');
console.log(JSON.stringify({rules:JSON.parse(createLocalFamilyRules(await readFile('backend/database.rules.json','utf8'),namespace)),fixture:createFamilyFixture({namespace,ownerUid,recipientUid})}));
"""


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    require(len(sys.argv) in [2, 3], 'Usage: test-family-sharing.py demo-studypath-family-device-<8hex> [serial]')
    namespace, serial = sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else 'emulator-5554'
    require(re.fullmatch(r'demo-studypath-family-device-[a-f0-9]{8}', namespace), 'An exact owned family namespace is required.')
    require(re.fullmatch(r'emulator-\d+', serial), 'Select the Test AVD serial.')
    adb = os.environ.get('STUDYPATH_ADB', str(Path.home() / 'Android/Sdk/platform-tools/adb'))
    node = os.environ.get('STUDYPATH_NODE', '/home/keni/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
    out = ROOT / 'implementation/phases/04-ppt-spec-alignment/07-backend-family-sharing/test-results' / (
        'native-family-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    out.mkdir(parents=True)
    receipt = {'status': 'FAILED', 'exitCode': 1, 'namespace': namespace, 'serial': serial, 'accepted': False,
        'localOnly': True, 'syntheticOnly': True, 'acceptancePromoted': False, 'commands': [], 'stages': [],
        'appDataCleared': False, 'sharedDatabaseReset': False, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    radios = routes = binding = controls = auth_before = auth_before_users = None
    mirror = None
    owned = auth_cleaned = False
    actions = set()
    private = set()
    owned_identities = {}
    frozen = sorted(set([Path(__file__), ROOT / 'android/app/src/androidTest/java/com/studypath/app/FamilySharingDeviceTest.kt',
        ROOT / 'backend/database.rules.json', *list((ROOT / 'backend/mock').glob('*.mjs')),
        *list((ROOT / 'backend/sharing').glob('*.mjs')), *list((ROOT / 'android/app/src/main').rglob('*.kt')),
        *list((ROOT / 'android/app/src/main').rglob('*.xml'))]))
    receipt['sourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in frozen}

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
        require(ns == namespace or method == 'GET' and ns in ['demo-studypath', 'demo-studypath-default-rtdb'], 'Unsafe database mutation.')
        require(not path.startswith('/') and '..' not in path and '?' not in path, 'Unsafe database path.')
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + ns, method=method,
            headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
            data=json.dumps(value).encode() if method == 'PUT' else None)
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    def auth_users():
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
        return sorted(users, key=lambda row: row.get('localId', ''))

    def auth_digest():
        return digest(auth_users())

    def remember_owned_identities(value):
        before_uids = {row.get('localId') for row in auth_before_users or []}
        for label in ['owner', 'recipient']:
            uid, email = value.get(label + 'Uid'), value.get(label + 'Email')
            require(re.fullmatch(r'[A-Za-z0-9_-]{1,128}', uid or '') and uid not in before_uids,
                    'Owned identity is invalid or existed before this run.')
            require(re.fullmatch(r'family_' + label + r'_[a-f0-9-]{36}@studypath[.]invalid', email or ''),
                    'Owned identity does not have this harness synthetic email shape.')
            require(uid not in owned_identities or owned_identities[uid] == email, 'Owned identity changed during the run.')
            owned_identities[uid] = email

    def cleanup_owned_auth_fallback():
        require(owned and owned_identities, 'No retained exact owned identities are available for Auth fallback cleanup.')
        users = auth_users(); present = []
        for user in users:
            uid = user.get('localId')
            if uid in owned_identities:
                require(user.get('email') == owned_identities[uid], 'Owned Auth UID/email differs; refuse deletion.')
                present.append(uid)
        require(digest([row for row in users if row.get('localId') not in owned_identities]) == auth_before,
                'Non-owned Auth controls changed; do not broaden fallback cleanup.')
        if present:
            request = urllib.request.Request(
                'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchDelete',
                method='POST', headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
                data=json.dumps({'localIds': present, 'force': True}).encode())
            with urllib.request.urlopen(request, timeout=15) as response:
                result = json.load(response)
            require(not result.get('errors'), 'Exact owned Auth fallback deletion was rejected.')
        require(auth_digest() == auth_before, 'Auth controls were not restored after exact owned cleanup.')
        receipt['ownedAuthFallback'] = {'used': True, 'deletedPresentCount': len(present),
            'retainedOwnedIdentityCount': len(owned_identities), 'nonOwnedControlsPreserved': True}

    def wait_adb_ready():
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            try:
                state = device('get-state', check=False, timeout=5)
                if state.returncode == 0 and state.stdout.strip() == b'device':
                    return True
            except (OSError, subprocess.TimeoutExpired):
                pass
            time.sleep(.3)
        return False

    def routing():
        return {row[1]: row[2] for line in device('reverse', '--list').stdout.decode().splitlines() if len(row := line.split()) == 3}

    def radio_values():
        return {key: int(shell('settings', 'get', 'global', key).stdout.strip()) for key in ['airplane_mode_on', 'wifi_on', 'mobile_data']}

    def binding_digest():
        value = shell('run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml', check=False)
        return hashlib.sha256(value.stdout).hexdigest() if value.returncode == 0 else None

    def online():
        shell('cmd', 'connectivity', 'airplane-mode', 'disable'); shell('svc', 'wifi', 'enable'); shell('svc', 'data', 'enable')
        for port in [9000, 9099, 8765]:
            device('reverse', f'tcp:{port}', f'tcp:{port}')

    def partition():
        for port in [9000, 9099, 8765]:
            device('reverse', '--remove', f'tcp:{port}', check=False)
        shell('cmd', 'connectivity', 'airplane-mode', 'enable'); shell('svc', 'wifi', 'disable'); shell('svc', 'data', 'disable')
        deadline = time.monotonic() + 20
        while radio_values() != {'airplane_mode_on': 1, 'wifi_on': 0, 'mobile_data': 0}:
            require(time.monotonic() < deadline, 'Guest transport did not partition.'); time.sleep(.2)
        require(not any(f'tcp:{port}' in routing() for port in [9000, 9099, 8765]), 'Reverse transport remains active.')
        receipt['offlinePartition'] = radio_values()

    def generated(owner_uid='preview_owner', recipient_uid='preview_recipient'):
        command = [node, '--input-type=module', '-e', FIXTURE, namespace, owner_uid, recipient_uid]
        value = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=30)
        require(value.returncode == 0, 'Local family fixture/rule generation failed: ' + redact(value.stderr))
        return json.loads(value.stdout)

    def marker(check=True):
        value = shell('run-as', PACKAGE, 'cat', MARKER, check=False, timeout=8)
        if value.returncode:
            require(not check, 'Native family marker is missing.'); return None
        result = json.loads(value.stdout); require(result['namespace'] == namespace, 'Native marker namespace differs.')
        remember_owned_identities(result)
        private.update([result['ownerUid'], result['recipientUid']]); return result

    def acknowledge(action):
        path = out / ('ack-' + action + '.json'); path.write_text(json.dumps({'namespace': namespace, 'action': action}) + '\n')
        remote = '/data/local/tmp/family-ack-' + namespace[-8:] + '.json'
        device('push', path, remote); shell('run-as', PACKAGE, 'cp', remote, 'files/family-sharing-host-ack.tmp')
        shell('run-as', PACKAGE, 'mv', 'files/family-sharing-host-ack.tmp', 'files/family-sharing-host-ack.json'); shell('rm', remote)

    def stage(name, handshake=False):
        shell('am', 'force-stop', PACKAGE)
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#' + name,
            '-e', 'familyNamespace', namespace, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        error = None
        try:
            deadline = time.monotonic() + (390 if handshake else 60)
            while process.poll() is None:
                require(time.monotonic() < deadline, 'Native family stage timed out.')
                value = marker(check=False)
                action = value.get('hostAction') if value and handshake else None
                if action and action not in actions:
                    require(action in ['seed_trusted', 'partition', 'online'], 'Unknown native host action.')
                    if action == 'seed_trusted':
                        trusted = generated(value['ownerUid'], value['recipientUid'])['fixture']['trustedUpdates']
                        # Retain the actual validated authored lesson inventory; the independent fixture has a placeholder hash.
                        paths = []
                        for path, record in trusted.items():
                            if path.startswith('catalog/sharingLessonKeys/'):
                                continue
                            database(path, method='PUT', value=record); require(database(path) == record, 'Trusted family fixture readback differs.'); paths.append(path)
                        receipt['trustedSetup'] = {'paths': [re.sub(r'/(?:' + '|'.join(map(re.escape, private)) + r')/', '/<synthetic-uid>/', path) for path in paths],
                            'syntheticOnly': True, 'privateRecordsSeeded': False, 'placeholderLessonInventorySeeded': False}
                        receipt['trustedCatalogSha256'] = digest(database('catalog'))
                    elif action == 'partition':
                        receipt['serverBeforeOfflineSha256'] = digest(database()); partition()
                    else:
                        require(digest(database()) == receipt['serverBeforeOfflineSha256'], 'Offline viewing mutated server state.')
                        receipt['offlineServerUnchanged'] = True; online()
                    acknowledge(action); actions.add(action)
                time.sleep(.2)
        except BaseException as failure:
            error = failure
        finally:
            if process.poll() is None:
                shell('am', 'force-stop', PACKAGE, check=False); process.kill()
            output, _ = process.communicate(timeout=10); (out / (name + '.log')).write_text(redact(output))
            diagnostic = shell('run-as', PACKAGE, 'cat', 'files/family-sharing-ui-failure.json', check=False, timeout=8)
            if diagnostic.returncode == 0:
                (out / 'ui-failure.json').write_text(redact(diagnostic.stdout))
                receipt['nativeUiFailure'] = json.loads(redact(diagnostic.stdout))
                image = shell('run-as', PACKAGE, 'cat', 'files/family-ui-failure.png', check=False, timeout=8)
                if image.returncode == 0 and image.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                    (out / 'ui-failure.png').write_bytes(image.stdout)
            value = marker(check=False)
            if value is not None and 'ownerStateBeforeUi' in value:
                receipt['ownerStateBeforeUi'] = value['ownerStateBeforeUi']
            if value is not None:
                for key in ['familyResponseTimeSource', 'reconnectReadiness', 'ownerControlsAfterReconnect']:
                    if key in value:
                        receipt[key] = value[key]
        require(error is None, str(error))
        log = redact(output)
        require(process.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log), 'Native JUnit failed: ' + name)
        value = marker(); receipt['stages'].append({'name': name, 'command': command, 'exitCode': process.returncode, 'junit': 'OK (1 test)'})
        receipt['observations'] = value['observations']; return value

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
        require(managed, 'Managed read-only Test AVD required.')
        require(shell('getprop', 'sys.boot_completed').stdout.strip() == b'1', 'Wait for boot.')
        radios, routes, binding = radio_values(), routing(), binding_digest()
        require(marker(check=False) is None, 'Restart Test AVD instead of replacing prior family evidence.')
        controls = {ns: digest(database(ns=ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        auth_before_users = auth_users(); auth_before = digest(auth_before_users)
        require(database() is None, 'Choose an unused owned family namespace.'); owned = True
        local_rules = generated()['rules']; database('.settings/rules', method='PUT', value=local_rules)
        (out / 'local-only-rules.json').write_text(json.dumps(local_rules, indent=2) + '\n')
        receipt['localOnlyRulesSha256'] = digest(local_rules)
        publication = subprocess.run([node, '--input-type=module', '-e', PUBLISH, namespace], cwd=ROOT, capture_output=True, timeout=45)
        require(publication.returncode == 0, 'Mock publication failed: ' + redact(publication.stderr))
        fixture = json.loads(publication.stdout)
        require(fixture['moduleAdminImports'] == 0 and len(fixture['publications']) == 3 and all(row['publicationPerformed'] for row in fixture['publications']), 'Strict publication was not observed.')
        receipt['publication'] = {'exitCode': 0, 'moduleAdminImports': 0, 'publications': fixture['publications']}
        for course_id, course in fixture['courses'].items():
            database('catalog/courses/' + course_id, method='PUT', value=course)
        for path, value in [('catalog/learnerPreferenceOptions/mock_computing_v1', fixture['academic']['options']),
                            ('catalog/academicPolicies/mock_computing_v1', fixture['academic']['policy']),
                            ('catalog/sharingLessonKeys/mock_computing_v1', fixture['sharingLessonKeys']['mock_computing_v1'])]:
            database(path, method='PUT', value=value); require(database(path) == value, 'Academic/inventory seed readback differs.')
        mirror = subprocess.Popen([node, 'backend/mock/serve-assets.mjs', '--port', '8765'], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        ready, _, _ = select.select([mirror.stdout], [], [], 15); require(ready, 'Scoped PDF mirror did not start.')
        mirror_ready = json.loads(mirror.stdout.readline()); require(mirror_ready.get('status') == 'LISTENING', 'Scoped mirror failed.')
        receipt['mirror'] = mirror_ready
        for label, path in [('apk', ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'),
                            ('testApk', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
            require(path.is_file(), 'Build frozen APKs first.'); device('install', '-r', path)
            receipt[label + 'Sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        online(); value = stage('independentOwnerAndRecipientNativeJourneys', handshake=True)
        require(actions == {'seed_trusted', 'partition', 'online'} and all(value['observations'].get(flag) is True for flag in FLAGS), 'Required independent native journey observation is missing.')
        owner_data = database('users/' + value['ownerUid'])
        require(owner_data['lessonAttempts'] == {value['attempt']['runId']: value['attempt']}, 'Actual acknowledged source attempt differs.')
        index = owner_data['lessonCompletionIndex']['mock_computing_v1']
        require(index['count'] == 1 and len(index['entries']) == 1, 'Unique protected count inflated.')
        slots = database('sharedStudy/' + value['ownerUid'])
        require(list(slots) == ['g0'] and slots['g0']['grant']['generation'] == 3 and slots['g0']['grant']['status'] == 'REVOKED' and 'projection' not in slots['g0'], 'Final revoke tombstone/projection differs.')
        require(len(database('familyAcceptances/' + value['ownerUid'])) == 2, 'Retained independent acceptance identities differ.')
        require(digest(database('catalog')) == receipt['trustedCatalogSha256'], 'Clients modified trusted catalog data.')
        receipt['serverProof'] = {'acknowledgedAuthoredAttemptCount': 1, 'protectedUniqueCount': 1, 'acceptanceCount': 2,
            'slotCount': 1, 'finalGeneration': 3, 'finalStatus': 'REVOKED', 'projectionAbsent': True, 'catalogUnchanged': True}
        for name in ['01-owner-invitation', '02-recipient-acceptance', '03-owner-explicit-grant', '04-recipient-fresh-summary',
                     '05-offline-no-cached-summary', '06-owner-confirmed-revoke', '07-recipient-revoked', '08-expired-summary-cleared']:
            image = shell('run-as', PACKAGE, 'cat', f'files/family-{name}.png').stdout
            require(image.startswith(b'\x89PNG\r\n\x1a\n'), 'Native UI image is missing.'); (out / (name + '.png')).write_bytes(image)
        receipt['freshCredentialOperations'] = {'owner': value['ownerCredentialRefreshes'], 'recipient': value['recipientCredentialRefreshes']}
        stage('cleanupOwnedSyntheticAccounts'); auth_cleaned = True; receipt.update(status='PASSED', exitCode=0)
    except BaseException as error:
        receipt['failure'] = str(error); print('Family sharing gate failed:', error, flush=True)
    finally:
        errors = []
        try:
            if owned and not auth_cleaned:
                native_cleanup_error = None
                try:
                    if wait_adb_ready() and marker(check=False) is not None:
                        online(); stage('cleanupOwnedSyntheticAccounts'); auth_cleaned = True
                except Exception as error:
                    native_cleanup_error = type(error).__name__
                if not auth_cleaned and owned_identities:
                    cleanup_owned_auth_fallback(); auth_cleaned = True
                if native_cleanup_error:
                    receipt['nativeAuthCleanupUnavailable'] = native_cleanup_error
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
                mirror.terminate(); rest, _ = mirror.communicate(timeout=15); (out / 'mirror.log').write_text(redact(rest))
                closed = [json.loads(line) for line in rest.splitlines() if line.strip().startswith(b'{')]
                receipt['mirrorRequests'] = next((row['requests'] for row in closed if row.get('status') == 'CLOSED'), [])
            if radios is not None:
                shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable')
                shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable'); shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
            if routes is not None:
                for port in ['tcp:9000', 'tcp:9099', 'tcp:8765']:
                    device('reverse', port, routes[port]) if port in routes else device('reverse', '--remove', port, check=False)
                require(routing() == routes, 'Original reverse routes differ.')
                require(binding_digest() == binding, 'Normal app graph workspace binding changed.'); receipt['normalWorkspaceBindingPreserved'] = True
        except Exception as error:
            errors.append(str(error))
        receipt['finalSourceSha256'] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in frozen}
        if receipt['sourceSha256'] != receipt['finalSourceSha256']:
            errors.append('Source changed during native verification; rebuild and rerun.')
        if errors:
            receipt.update(status='FAILED', exitCode=1, cleanupFailures=errors)
        receipt['remainingGaps'] = ['Synthetic trusted policy/eligibility/recipient role do not establish real consent or guardian status.',
            'Local Firebase token REST proves next-read authorization, not immediate Admin Auth revoked-token validation.',
            'Selected native UI journeys do not establish full accessibility, physical-device, participant or cloud acceptance.']
        receipt['finishedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        (out / 'verification.json').write_text(json.dumps(receipt, indent=2) + '\n'); print('Evidence:', out, flush=True)
    return receipt['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
