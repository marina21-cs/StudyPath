#!/usr/bin/env python3
"""Real preference UI/recreation/queued-write/process-recovery gate; build APKs first.

Only the managed read-only StudyPath_Lite_Test is allowed. Never clears data, uninstalls,
resets Firebase, writes the shared catalog, or discards an offline queue.
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
FIXTURE = 'files/u03-onboarding-fixture.json'
STAGES = ['uiPreferencesAndRecreation', 'queueOfflineAtLargeText', 'recoverAndRelogin']
FLAGS = [
    ['firstLoginAutomaticallyShowsPreferences', 'unsavedLevelSubjectsAndClassSurviveRecreation',
     'uiDraftSaveWaitsForServerAcknowledgement', 'wikipediaSearchRemainsReachableWithoutLessonCatalog',
     'studyAndSavedDoNotExposeLegacyDemoLessons',
     'normalTextPreferencesKeepConciseUnsupportedExplanationReadable',
     'normalTextPreferenceSaveReachableAboveActualIme',
     'normalTextImeDoneClosesKeyboardAndRevealsPreferenceSave',
     'normalTextProfileSeparatesReachableStudyAndAccountActions',
     'profileEmptyReminderGuidanceReachable', 'profileFutureReminderServerConfirmed'],
    ['sameUidOfflineEditorRecoversPriorPreferencesAt200PercentText',
     'offlineUiWriteQueuesWithoutServerConfirmedOrDownloadClaim',
     'largeTextPreferencesKeepConciseUnsupportedExplanationReadable',
     'largeTextPreferenceSaveReachableAboveActualIme',
     'largeTextImeDoneClosesKeyboardAndRevealsPreferenceSave',
     'largeTextProfileSeparatesReachableStudyAndAccountActions',
     'profileBlockedNotificationsOfferCorrectSettingsIntent',
     'largeTextAccountDialogActionsRemainReachable',
     'profileLargeTextSignOutCancelKeepsSession',
     'profileOfflineReminderPendingSurvivesReminderRerender'],
    ['queuedUiPreferencesRecoverAfterProcessDeathWithoutNewWrite',
     'uiLogoutAndOriginalUidReloginPreserveQueuedPreferencesAndGating',
     'profileQueuedReminderRecoveredOffAfterProcessDeath', 'normalTextAccountDialogActionsRemainReachable'],
]
SCREENS = ['01-first-login-preferences', '02-recreated-draft', '03-server-confirmed-preferences',
           '04-study-independent-search', '05-saved-preference-gating', '05b-large-text-editor-actions',
           '06-offline-save-pending-large-text', '07-reconnected-queued-preferences',
           '08-same-uid-relogin-preferences']
SCREENS += ['02b-normal-preferences-save-keyboard', '04b-profile-normal-controls',
            '02c-preference-save-result',
            '04c-profile-empty-reminders', '05a-profile-large-controls',
            '05c-large-preferences-save-keyboard', '05d-profile-offline-reminder-pending',
            '05e-profile-signout-large']
OUT = ROOT / 'implementation/phases/04-ppt-spec-alignment/08-ui-onboarding-module-library/test-results/onboarding-ui'



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
        out.rename(OUT / ('device-run-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')))
    out.mkdir()
    receipt = {'status': 'FAILED', 'exitCode': 1, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
               'localOnly': True, 'avd': 'StudyPath_Lite_Test', 'serial': serial, 'stages': [],
               'appDataCleared': False, 'packagesUninstalled': False, 'catalogMutatedByHarness': False,
               'sharedDatabaseReset': False, 'requiresLiveWikipedia': False, 'commands': []}
    radios = None; mappings = None; font_scale = None; hardware_ime = None; known_private = set(); failure = 1
    old_handlers = {}

    def interrupted(_signum, _frame):
        raise InterruptedError('Device gate interrupted.')

    def device(*args, check=True, timeout=35):
        command = [adb, '-s', serial, *map(str, args)]
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        receipt['commands'].append({'stage': receipt.get('lastStage'), 'command': command, 'exitCode': result.returncode})
        require(not check or result.returncode == 0, 'A device operation failed at the recorded stage.')
        return result

    def shell(*args, **kwargs):
        return device('shell', *args, **kwargs)

    def database(path):
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=demo-studypath-default-rtdb',
                                         headers={'Authorization': 'Bearer owner'})
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

    def restore_font():
        if font_scale == 'null':
            shell('settings', 'delete', 'system', 'font_scale')
        else:
            shell('settings', 'put', 'system', 'font_scale', font_scale)
        require(shell('settings', 'get', 'system', 'font_scale').stdout.decode().strip() == font_scale,
                'The original system font-scale setting was not restored.')

    def fixture():
        value = json.loads(shell('run-as', PACKAGE, 'cat', FIXTURE).stdout)
        require(value.get('schemaVersion') == 1, 'Missing compatible private test fixture.')
        require(all(isinstance(value.get(key), str) and value[key] for key in ['uid', 'email']),
                'The private test fixture is missing its synthetic binding.')
        known_private.update(value[key] for key in ['uid', 'email'])
        return value

    def redact(raw):
        text = raw.decode(errors='replace')
        for private in known_private:
            text = text.replace(private, '<synthetic-identity>')
        return re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+', '<synthetic-email>', text)

    def capture_available():
        for name in SCREENS:
            result = shell('run-as', PACKAGE, 'cat', 'files/onboarding-screenshots/' + name + '.png', check=False)
            if result.returncode == 0 and result.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                (out / (name + '.png')).write_bytes(result.stdout)

    def stage(index):
        name = STAGES[index]; receipt['lastStage'] = name
        print('U03 preferences:', name, flush=True)
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Prior process did not stop.')
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   PACKAGE + '.PreferenceOnboardingDeviceTest#' + name, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            output, _ = process.communicate(timeout=240 if index == 0 else 180)
        except BaseException:
            process.kill(); output, _ = process.communicate()
            try: fixture()
            except Exception: pass
            (out / (name + '.log')).write_text(redact(output))
            raise
        try:
            value = fixture()
        except Exception:
            (out / (name + '.log')).write_text(redact(output))
            raise
        log = redact(output)
        (out / (name + '.log')).write_text(log)
        require(process.returncode == 0 and re.search(r'^OK \(1 test\)', log, re.M) and
                not re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=', log),
                'Instrumentation omitted explicit success at the recorded stage.')
        require(all(value['observations'].get(flag) is True for flag in FLAGS[index]), 'Required device observations are missing.')
        receipt['stages'].append({'name': name, 'exitCode': process.returncode, 'command': command, 'junit': 'OK (1 test)',
                                  'pid': value[f'stage{index+1}Pid'], 'log': name + '.log', 'fixtureConfirmed': True})
        receipt['observations'] = value['observations']
        for key in ['normalTextTargets', 'largeTextTargets', 'preferenceGeometry',
                    'preferenceSaveImeGeometry', 'profileGeometry', 'notificationSettingsIntent',
                    'saveAttemptDiagnostics', 'accountDialogGeometry']:
            if key in value: receipt[key] = value[key]
        capture_available()
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
        require(managed and shell('getprop', 'sys.boot_completed').stdout.strip() == b'1',
                'A booted managed read-only test AVD is required.')
        radios = radio_values(); mappings = route_values()
        font_scale = shell('settings', 'get', 'system', 'font_scale').stdout.decode().strip()
        hardware_ime = shell('settings', 'get', 'secure', 'show_ime_with_hard_keyboard').stdout.decode().strip()
        require(font_scale == 'null' or re.fullmatch(r'\d+(?:\.\d+)?', font_scale), 'Unexpected initial font-scale setting.')
        require(radios['airplane_mode_on'] == 0 and (radios['wifi_on'] or radios['mobile_data']), 'Start the test online.')
        apk_paths = [ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',
                     ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
        for apk in apk_paths:
            require(apk.is_file(), 'Build the local debug and test APKs first.')
            device('install', '-r', apk)
        permission_deadline = time.monotonic() + 15
        while True:
            permissions = shell('dumpsys', 'package', PACKAGE).stdout.decode()
            permission = re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=(true|false)', permissions)
            if permission:
                require(permission[1] == 'false',
                        'Use a fresh test AVD with notifications off for the recovery UI check.')
                break
            require(time.monotonic() < permission_deadline, 'Installed notification permission did not become readable.')
            receipt['notificationPermissionReadWaits'] = receipt.get('notificationPermissionReadWaits', 0) + 1
            time.sleep(.2)
        receipt['notificationPermissionInitiallyDenied'] = True
        device('shell', 'run-as', PACKAGE, 'pwd')
        if shell('run-as', PACKAGE, 'test', '-f', 'shared_prefs/workspace.xml', check=False).returncode == 0:
            require(b'boundUid' not in shell('run-as', PACKAGE, 'cat', 'shared_prefs/workspace.xml').stdout,
                    'Close/restart the read-only test AVD; never clear a bound installation.')
        require(shell('run-as', PACKAGE, 'test', '-f', FIXTURE, check=False).returncode != 0,
                'A prior fixture is present; close/restart the read-only test AVD.')
        receipt['apkSha256'], receipt['testApkSha256'] = [hashlib.sha256(p.read_bytes()).hexdigest() for p in apk_paths]
        receipt['runnerSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        receipt['testSourceSha256'] = hashlib.sha256((ROOT/'android/app/src/androidTest/java/com/studypath/app/PreferenceOnboardingDeviceTest.kt').read_bytes()).hexdigest()
        # Both services must be local; retain all catalog/account values only in memory.
        import socket
        for service_port in [9099, 9000]:
            with socket.create_connection(('127.0.0.1', service_port), timeout=5):
                pass
        forwards()
        shell('settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
        catalog_before = digest(database('catalog')); others_before = database('users') or {}
        receipt['catalogBeforeSha256'] = catalog_before
        # Establish a normal-font baseline; independently restore the original setting in finally.
        shell('settings', 'put', 'system', 'font_scale', '1.0')
        value = stage(0); uid = value['uid']
        require(uid not in others_before, 'The test identity is not fresh.')
        baseline = database('users/' + uid)
        require(baseline.get('profile', {}).get('learnerPreferences') == value['firstPreferences'],
                'Server preferences differ from the acknowledged Grade 7/Mathematics UI draft.')
        require(value['firstPreferences'] == {'schemaVersion': 1, 'onboardingComplete': False,
                'levelId': 'grade_7', 'subjectIds': {'mathematics': True}, 'classSectionId': 'pilot_a'},
                'Unexpected first UI preference fixture.')
        require(value['queuedPreferences'] == {'schemaVersion': 1, 'onboardingComplete': False,
                'levelId': 'college', 'subjectIds': {'programming': True}, 'classSectionId': 'independent_b'},
                'Unexpected queued UI preference fixture.')
        receipt['initialPreferenceWriteIndependentlyVerified'] = True
        receipt['expectedInitialPreferences'] = value['firstPreferences']
        receipt['expectedQueuedPreferences'] = value['queuedPreferences']
        reminder_id = value.get('profileReminderId')
        require(reminder_id == 'ui_profile_pending_reminder', 'Unexpected owned reminder identity.')
        reminder_before = value.get('profileReminderBefore')
        reminder_after = value.get('profileReminderAfter')
        def reminder_payload(record):
            require(isinstance(record, dict), 'Missing owned reminder record.')
            result = dict(record); result.pop('updatedAt', None)
            return result
        require(reminder_payload(reminder_before) == {
            'moduleId': 'programming_basics', 'title': 'Synthetic profile reminder',
            'dueAt': reminder_before.get('dueAt'), 'enabled': True, 'zoneId': 'Asia/Manila'},
            'Unexpected initial reminder fixture.')
        require(isinstance(reminder_before['dueAt'], int) and
                reminder_before['dueAt'] > int(time.time() * 1000), 'Fixture reminder must be in the future.')
        expected_after = reminder_payload(reminder_before); expected_after['enabled'] = False
        require(reminder_payload(reminder_after) == expected_after, 'Unexpected queued reminder transition.')
        require(reminder_payload(baseline.get('reminders', {}).get(reminder_id)) == reminder_payload(reminder_before),
                'Initial reminder server readback differs from the acknowledged fixture.')
        receipt['initialReminderServerReadbackVerified'] = True
        receipt['expectedReminderTransition'] = {'id': reminder_id, 'before': reminder_payload(reminder_before),
                                                 'after': expected_after, 'serverTimestampMayAdvance': True}
        baseline_unrelated = json.loads(json.dumps(baseline))
        baseline_unrelated['profile'].pop('learnerPreferences', None)
        baseline_unrelated['profile'].pop('updatedAt', None)
        baseline_unrelated['reminders'].pop(reminder_id)
        shell('am', 'force-stop', PACKAGE)
        require(not shell('pidof', PACKAGE, check=False).stdout.strip(), 'Online process did not stop before partition.')
        partition(); receipt['realTransportPartitionObserved'] = True
        shell('settings', 'put', 'system', 'font_scale', '2.0')
        require(float(shell('settings', 'get', 'system', 'font_scale').stdout.strip()) == 2.0, 'System font scale did not reach 200%.')
        stage(1)
        require(digest(database('users/' + uid)) == digest(baseline), 'Server records changed while the UI preferences write was offline and queued.')
        receipt['offlineServerRecordsUnchanged'] = True
        shell('am', 'force-stop', PACKAGE)
        shell('cmd', 'connectivity', 'airplane-mode', 'disable' if not radios['airplane_mode_on'] else 'enable')
        shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable')
        shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')
        observe_radios(radios); forwards(); restore_font()
        stage(2)
        deadline = time.monotonic() + 30
        while True:
            final_owner = database('users/' + uid)
            if (final_owner.get('profile', {}).get('learnerPreferences') == value['queuedPreferences'] and
                    reminder_payload(final_owner.get('reminders', {}).get(reminder_id)) == expected_after):
                break
            require(time.monotonic() < deadline, 'The original offline UI write did not reconcile to the expected server preferences.')
            time.sleep(.2)
        final_unrelated = json.loads(json.dumps(final_owner))
        final_unrelated['profile'].pop('learnerPreferences', None)
        final_unrelated['profile'].pop('updatedAt', None)
        final_reminder = final_unrelated['reminders'].pop(reminder_id)
        require(isinstance(final_reminder.get('updatedAt'), (int, float)) and
                final_reminder['updatedAt'] >= baseline['reminders'][reminder_id]['updatedAt'],
                'Reconciled reminder lacks a valid nondecreasing server timestamp.')
        require(digest(final_unrelated) == digest(baseline_unrelated),
                'A record outside preferences/profile updatedAt and the exact reminder changed for the synthetic owner.')
        receipt['queuedPreferenceServerReadbackVerified'] = True
        receipt['queuedReminderServerReadbackVerified'] = True
        receipt['unrelatedOwnerRecordsUnchanged'] = True
        others_after = database('users') or {}; others_after.pop(uid, None)
        require(digest(others_after) == digest(others_before), 'Other account records changed.')
        require(digest(database('catalog')) == catalog_before, 'The shared catalog changed during feature verification.')
        require(len({item['pid'] for item in receipt['stages']}) == 3, 'Three distinct application processes were not observed.')
        require(all((out/(name+'.png')).is_file() for name in SCREENS), 'Expected UI screenshots were not collected.')
        receipt.update(status='PASSED', lastStage='complete', catalogUnchanged=True, otherAccountsUnchanged=True,
                       queuedPreferencesReconciledAfterProcessDeath=True, screenshots=[name+'.png' for name in SCREENS])
        failure = 0
    except Exception as error:
        receipt['failure'] = str(error) if isinstance(error, CheckFailed) else type(error).__name__
        print('U03 preference UI gate failed:', receipt['failure'], flush=True)
    finally:
        for signum in old_handlers: signal.signal(signum, signal.SIG_IGN)
        errors = []
        actions = []
        if font_scale is not None: actions.append(('fontScale', restore_font))
        if hardware_ime is not None:
            actions.append(('hardwareIme', lambda: shell('settings',
                'delete' if hardware_ime == 'null' else 'put', 'secure', 'show_ime_with_hard_keyboard',
                *([] if hardware_ime == 'null' else [hardware_ime]))))
            actions.append(('hardwareImeObservation', lambda: require(
                shell('settings', 'get', 'secure', 'show_ime_with_hard_keyboard').stdout.decode().strip() == hardware_ime,
                'The original hardware-keyboard IME setting was not restored.')))
        if radios is not None:
            actions.extend([
                ('airplane', lambda: shell('cmd', 'connectivity', 'airplane-mode', 'enable' if radios['airplane_mode_on'] else 'disable')),
                ('wifi', lambda: shell('svc', 'wifi', 'enable' if radios['wifi_on'] else 'disable')),
                ('data', lambda: shell('svc', 'data', 'enable' if radios['mobile_data'] else 'disable')),
                ('radioObservation', lambda: observe_radios(radios)),
            ])
        if mappings is not None:
            for port in [9000, 9099]:
                remote = f'tcp:{port}'
                action = ((lambda key=remote: device('reverse', key, mappings[key])) if remote in mappings else
                          (lambda key=remote: device('reverse', '--remove', key, check=False)))
                actions.append(('reverse' + str(port), action))
            actions.append(('routeObservation', lambda: require(route_values() == mappings, 'Reverse mappings were not restored.')))
        for label, action in actions:
            try: action()
            except Exception as error: errors.append({'setting': label, 'errorType': type(error).__name__})
        # Only named preferences/library captures are exported; no private fixture or auth form.
        try: capture_available()
        except Exception: pass
        receipt['transportAndFontScaleRestored'] = not errors
        if errors:
            failure = 1; receipt['status'] = 'FAILED'; receipt['restorationErrors'] = errors
        receipt.update(exitCode=failure, finishedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat())
        (out/'verification.json').write_text(json.dumps(receipt, indent=2)+'\n'); lock.rmdir()
        for signum, handler in old_handlers.items(): signal.signal(signum, handler)
        print('Evidence:', out, flush=True)
    return failure


if __name__ == '__main__':
    sys.exit(main())
