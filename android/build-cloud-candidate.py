#!/usr/bin/env python3
"""Build a project-bound cloud candidate without login, deployment or device installation."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PROJECT = 'studypath-c8086'
DATABASE_URL = 'https://studypath-c8086-default-rtdb.asia-southeast1.firebasedatabase.app'
DEFAULT_CONFIG = ROOT / '.build/firebase-cloud/studypath-c8086.properties'
LOCK = ROOT / '.build/v1-fix-gradle.lock'


class CandidateError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CandidateError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_config(path):
    require(path.is_absolute(), 'Choose an absolute cloud configuration path.')
    require(not path.is_symlink() and path.is_file(), 'Cloud configuration must be an existing regular file.')
    require(path.resolve().is_relative_to((ROOT / '.build/firebase-cloud').resolve()), 'Keep the configuration inside ignored .build/firebase-cloud.')
    require(stat.S_IMODE(path.stat().st_mode) == 0o600, 'Cloud configuration must have mode 0600.')
    require(path.stat().st_uid == os.getuid(), 'Cloud configuration must belong to the current user.')
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(('#', '!')):
            continue
        require('=' in line and not line.rstrip().endswith('\\'), 'Use simple key=value cloud properties without continuation.')
        key, value = [part.strip() for part in line.split('=', 1)]
        require(key and key not in values, 'Duplicate or empty cloud property name.')
        values[key] = value
    required = {'firebaseUseEmulators', 'firebaseProjectId', 'firebaseDatabaseUrl', 'firebaseApiKey', 'firebaseAppId'}
    require(required <= values.keys(), 'The cloud client configuration is incomplete.')
    require(values.keys() <= required | {'studyPathUserAgent'}, 'Unexpected cloud configuration property.')
    require(values['firebaseUseEmulators'] == 'false', 'Cloud candidate must disable Firebase emulators.')
    require(values['firebaseProjectId'] == PROJECT and values['firebaseDatabaseUrl'] == DATABASE_URL, 'Cloud configuration does not match the intended StudyPath project/database.')
    require(bool(re.fullmatch(r'AIza[A-Za-z0-9_-]{20,80}', values['firebaseApiKey'])) and 'NotAReal' not in values['firebaseApiKey'], 'A real Android client key is required.')
    require(bool(re.fullmatch(r'1:[0-9]+:android:[a-f0-9]+', values['firebaseAppId'])), 'A valid Android client app ID is required.')
    return values


def source_identity():
    excluded = {'build', '.gradle', '.idea', '__pycache__', '.firebase', 'node_modules'}
    private = {'local.properties', 'firebase.properties', 'google-services.json'}
    files = [path for path in (ROOT / 'android').rglob('*') if path.is_file() and not path.is_symlink() and
             not excluded.intersection(path.relative_to(ROOT / 'android').parts) and path.name not in private and
             path.suffix not in {'.pyc', '.log', '.lock', '.jks', '.keystore'}]
    return {str(path.relative_to(ROOT)): sha(path) for path in sorted(files)}


def check_generated(values):
    generated = ROOT / 'android/app/build/generated/source/buildConfig/debug/com/studypath/app/BuildConfig.java'
    require(generated.is_file(), 'Generated main BuildConfig was not found.')
    source = generated.read_text()
    flag = re.search(r'USE_FIREBASE_EMULATORS\s*=\s*(true|false)\s*;', source)
    require(flag is not None and flag[1] == 'false', 'Built emulator flag does not match the selected cloud configuration.')
    for field, key in [('FIREBASE_PROJECT_ID', 'firebaseProjectId'), ('FIREBASE_DATABASE_URL', 'firebaseDatabaseUrl'),
                       ('FIREBASE_API_KEY', 'firebaseApiKey'), ('FIREBASE_APP_ID', 'firebaseAppId')]:
        match = re.search(r'\b' + field + r'\s*=\s*("(?:\\.|[^"\\])*")\s*;', source)
        require(match is not None and json.loads(match[1]) == values[key], 'A built Firebase field differs from the selected file; inspect property overrides privately.')
    mirror = re.search(r'\bMOCK_ASSET_MIRROR_URL\s*=\s*("(?:\\.|[^"\\])*")\s*;', source)
    require(mirror is None or json.loads(mirror[1]) == '', 'A cloud candidate cannot include an enabled local mock asset mirror.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    parser.add_argument('--output', type=Path, help='New candidate folder inside .build/cloud-candidates.')
    parser.add_argument('--check', action='store_true', help='Validate local configuration only; do not build or copy artifacts.')
    parser.add_argument('--lock-timeout', type=float, default=60)
    args = parser.parse_args()
    try:
        values = read_config(args.config)
        require(0 <= args.lock_timeout <= 300, 'Choose a lock timeout from 0 through 300 seconds.')
        if args.check:
            print(json.dumps({'status': 'CONFIGURATION_VALIDATED_BUILD_UNRUN', 'project': PROJECT, 'databaseUrl': DATABASE_URL,
                'configMode': '0600', 'cloudVerified': False, 'loginAttempted': False, 'deploymentAttempted': False}))
            return 0
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        output = (args.output or ROOT / '.build/cloud-candidates' / stamp).absolute()
        require(output.resolve().is_relative_to((ROOT / '.build/cloud-candidates').resolve()) and not output.exists(), 'Select a new folder inside .build/cloud-candidates.')
        output.mkdir(parents=True)
        manifest = {'schemaVersion': 1, 'status': 'FAILED', 'startedAtUtc': datetime.now(timezone.utc).isoformat(),
            'project': PROJECT, 'databaseUrl': DATABASE_URL, 'emulatorMode': False, 'cloudVerified': False,
            'loginAttempted': False, 'deploymentAttempted': False, 'deviceInstalled': False, 'configSha256': sha(args.config),
            'sourceSha256': source_identity(), 'gradleLock': str(LOCK.relative_to(ROOT)), 'maxWorkers': 2}
        try:
            env = os.environ.copy()
            for key in list(env):
                if key.startswith('ORG_GRADLE_PROJECT_firebase') or key == 'ORG_GRADLE_PROJECT_mockAssetMirrorUrl':
                    del env[key]
            for name in ['GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS']:
                require('firebase' not in env.get(name, '').lower() and 'mockassetmirror' not in env.get(name, '').lower(), 'Remove Firebase property overrides from Java/Gradle option variables.')
            if 'JAVA_HOME' not in env and Path('/home/keni/.sdkman/candidates/java/current').is_dir():
                env['JAVA_HOME'] = '/home/keni/.sdkman/candidates/java/current'
            if env.get('JAVA_HOME'):
                env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
            gradle = env.get('STUDYPATH_GRADLE', str(ROOT / 'android/gradlew'))
            command = [gradle, '--offline', '--no-daemon', '--console=plain', '--max-workers=2', '--no-parallel',
                '-PfirebaseConfigFile=' + str(args.config), ':app:assembleDebug', ':app:assembleDebugAndroidTest']
            manifest['command'] = command
            LOCK.parent.mkdir(exist_ok=True)
            with LOCK.open('a') as lock:
                deadline = time.monotonic() + args.lock_timeout
                while True:
                    try:
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB); break
                    except BlockingIOError:
                        require(time.monotonic() < deadline, 'The shared Gradle lock is busy; retry after the current build finishes.')
                        time.sleep(.2)
                # Filter in memory before writing any build output. Client values never enter the log.
                with (output / 'build.log').open('w') as log:
                    process = subprocess.Popen(command, cwd=ROOT / 'android', env=env, stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT, text=True, errors='replace')
                    for line in process.stdout:
                        for secret in [values['firebaseApiKey'], values['firebaseAppId']]:
                            line = line.replace(secret, '<redacted-client-config>')
                        log.write(line)
                    manifest['buildExitCode'] = process.wait()
                require(manifest['buildExitCode'] == 0, 'Cloud candidate build failed; inspect the sanitized build log.')
                check_generated(values)
                require(sha(args.config) == manifest['configSha256'] and source_identity() == manifest['sourceSha256'], 'Configuration/source changed during the build; freeze inputs and rebuild.')
                artifacts = [('StudyPath-cloud-candidate-debug.apk', ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'),
                             ('StudyPath-cloud-candidate-test.apk', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]
                manifest['artifacts'] = {}
                for name, path in artifacts:
                    require(path.is_file(), 'An expected app/test APK is missing.')
                    shutil.copyfile(path, output / name)
                    manifest['artifacts'][name] = {'sha256': sha(output / name), 'bytes': (output / name).stat().st_size}
                manifest['status'] = 'BUILT_CLOUD_UNVERIFIED'
            print('Cloud candidate built; cloud operation remains unverified. Evidence: ' + str(output))
        except (CandidateError, OSError, ValueError) as error:
            manifest['failure'] = str(error) if isinstance(error, CandidateError) else type(error).__name__
            print(manifest['failure'], file=sys.stderr)
        finally:
            manifest['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
            (output / 'candidate-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        return 0 if manifest['status'] == 'BUILT_CLOUD_UNVERIFIED' else 1
    except (CandidateError, OSError, ValueError) as error:
        print(str(error) if isinstance(error, CandidateError) else type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
