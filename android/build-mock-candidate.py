#!/usr/bin/env python3
"""Build a namespace-bound local mock APK; never starts services, installs or changes cloud config."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / '.build/v1-fix-gradle.lock'


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_identity():
    excluded = {'build', '.gradle', '.idea', '__pycache__', 'node_modules'}
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted((ROOT / 'android').rglob('*'))
            if p.is_file() and not p.is_symlink() and not excluded.intersection(p.relative_to(ROOT / 'android').parts)
            and p.name not in {'local.properties', 'firebase.properties', 'google-services.json'}
            and p.suffix not in {'.pyc', '.log', '.lock', '.jks', '.keystore'}}


def check_generated(values):
    path = ROOT / 'android/app/build/generated/source/buildConfig/debug/com/studypath/app/BuildConfig.java'
    source = path.read_text()
    require(re.search(r'USE_FIREBASE_EMULATORS\s*=\s*true\s*;', source), 'Built candidate must use Firebase emulators.')
    for field, key in [('FIREBASE_PROJECT_ID', 'firebaseProjectId'), ('FIREBASE_DATABASE_URL', 'firebaseDatabaseUrl'),
                       ('FIREBASE_EMULATOR_HOST', 'firebaseEmulatorHost'), ('MOCK_ASSET_MIRROR_URL', 'mockAssetMirrorUrl'),
                       ('FIREBASE_API_KEY', 'firebaseApiKey'), ('FIREBASE_APP_ID', 'firebaseAppId')]:
        value = re.search(r'\b' + field + r'\s*=\s*("(?:\\.|[^"\\])*")\s*;', source)
        require(value is not None and json.loads(value[1]) == values[key], 'Generated BuildConfig differs from the selected mock configuration: ' + field)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('namespace')
    parser.add_argument('--check', action='store_true', help='Validate selected configuration only; do not build.')
    parser.add_argument('--output', type=Path, help='New folder inside ignored .build/mock-candidates.')
    parser.add_argument('--lock-timeout', type=float, default=60)
    args = parser.parse_args()
    try:
        require(re.fullmatch(r'demo-studypath-mock-[a-f0-9]{8}', args.namespace), 'Use exact demo-studypath-mock-<8hex>; shared, publisher and cloud namespaces are refused.')
        require(0 <= args.lock_timeout <= 300, 'Lock timeout must be between 0 and 300 seconds.')
        values = {'firebaseUseEmulators': 'true', 'firebaseProjectId': 'demo-studypath',
            'firebaseEmulatorHost': '10.0.2.2', 'firebaseDatabaseUrl': f'https://{args.namespace}.firebaseio.com',
            'mockAssetMirrorUrl': 'http://10.0.2.2:8765/', 'firebaseApiKey': 'AIzaSyDemoStudyPathNotARealAPIKey000000',
            'firebaseAppId': '1:1234567890:android:0123456789abcdef'}
        if args.check:
            print(json.dumps({'status': 'CONFIGURATION_VALIDATED_BUILD_UNRUN', 'namespace': args.namespace,
                'emulators': True, 'databaseUrl': values['firebaseDatabaseUrl'], 'mockAssetMirrorUrl': values['mockAssetMirrorUrl'],
                'gradleLock': str(LOCK.relative_to(ROOT)), 'maxWorkers': 2, 'deviceInstalled': False, 'cloudTouched': False}))
            return 0
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        output = (args.output or ROOT / '.build/mock-candidates' / (args.namespace + '-' + stamp)).absolute()
        require(output.resolve().is_relative_to((ROOT / '.build/mock-candidates').resolve()) and not output.exists(), 'Choose a new folder inside .build/mock-candidates.')
        output.mkdir(parents=True)
        config = output / 'mock-client.properties'; config.write_text('\n'.join(f'{key}={value}' for key, value in values.items()) + '\n'); config.chmod(0o600)
        receipt = {'schemaVersion': 1, 'status': 'FAILED', 'namespace': args.namespace, 'syntheticOnly': True,
            'accepted': False, 'emulatorMode': True, 'deviceInstalled': False, 'servicesStarted': False, 'cloudTouched': False,
            'startedAtUtc': datetime.now(timezone.utc).isoformat(), 'configSha256': sha(config),
            'sourceSha256': source_identity(), 'gradleLock': str(LOCK.relative_to(ROOT)), 'maxWorkers': 2}
        try:
            env = os.environ.copy()
            for key in list(env):
                if key.startswith('ORG_GRADLE_PROJECT_firebase') or key == 'ORG_GRADLE_PROJECT_mockAssetMirrorUrl':
                    del env[key]
            for key in ['GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS']:
                require(not any(value in env.get(key, '').lower() for value in ['firebase', 'mockassetmirror']), 'Remove Firebase/mock property overrides from Java/Gradle option variables.')
            if 'JAVA_HOME' not in env and Path('/home/keni/.sdkman/candidates/java/current').is_dir():
                env['JAVA_HOME'] = '/home/keni/.sdkman/candidates/java/current'
            if env.get('JAVA_HOME'):
                env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
            command = [env.get('STUDYPATH_GRADLE', str(ROOT / 'android/gradlew')), '--offline', '--no-daemon',
                '--console=plain', '--max-workers=2', '--no-parallel', '-PfirebaseConfigFile=' + str(config),
                ':app:assembleDebug', ':app:assembleDebugAndroidTest']
            receipt['command'] = command; LOCK.parent.mkdir(exist_ok=True)
            with LOCK.open('a') as lock:
                deadline = time.monotonic() + args.lock_timeout
                while True:
                    try:
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB); break
                    except BlockingIOError:
                        require(time.monotonic() < deadline, 'Shared Gradle lock is busy; retry after the coordinated build finishes.'); time.sleep(.2)
                with (output / 'build.log').open('w') as log:
                    result = subprocess.run(command, cwd=ROOT / 'android', env=env, stdout=log, stderr=subprocess.STDOUT)
                receipt['buildExitCode'] = result.returncode
                require(result.returncode == 0, 'Mock candidate build failed; inspect build.log.')
                check_generated(values)
                require(sha(config) == receipt['configSha256'] and source_identity() == receipt['sourceSha256'], 'Configuration/source changed during build; freeze and rebuild.')
                receipt['artifacts'] = {}
                for name, path in [('StudyPath-mock-candidate-debug.apk', ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'),
                                   ('StudyPath-mock-candidate-test.apk', ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
                    shutil.copyfile(path, output / name)
                    receipt['artifacts'][name] = {'sha256': sha(output / name), 'bytes': (output / name).stat().st_size}
                receipt['status'] = 'BUILT_MOCK_DEVICE_UNVERIFIED'
        except (OSError, ValueError) as error:
            receipt['failure'] = str(error); print(str(error), file=sys.stderr)
        finally:
            receipt['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
            (output / 'candidate-manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('Mock candidate evidence: ' + str(output))
        return 0 if receipt['status'] == 'BUILT_MOCK_DEVICE_UNVERIFIED' else 1
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr); return 1


if __name__ == '__main__':
    raise SystemExit(main())
