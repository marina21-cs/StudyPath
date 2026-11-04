"""Mock policy/namespace support for the host-coordinated B07 runners; no cloud endpoints."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


class B07Fixture:
    def __init__(self, namespace):
        if not re.fullmatch(r'demo-studypath-b07-(?:offline|session|reminders)-[a-f0-9]{8}', namespace):
            raise ValueError('Select an isolated B07 test namespace.')
        self.namespace = namespace
        self.owned = False
        self.shared_before = None
        self.auth_before = None
        self.auth_before_ids = set()
        self.owned_uids = set()

    def database(self, path='', method='GET', value=None, namespace=None):
        namespace = namespace or self.namespace
        if namespace != self.namespace and not (method == 'GET' and namespace in ['demo-studypath', 'demo-studypath-default-rtdb']):
            raise ValueError('Shared namespaces are read-only controls.')
        if path.startswith('/') or '..' in path or '?' in path:
            raise ValueError('Unsafe local database path.')
        request = urllib.request.Request('http://127.0.0.1:9000/' + path + '.json?ns=' + namespace,
            method=method, headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
            data=json.dumps(value).encode() if method == 'PUT' else None)
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    @staticmethod
    def auth_users():
        users, token, seen = [], None, set()
        while True:
            query = {'maxResults': 1000}
            if token is not None:
                query['nextPageToken'] = token
            request = urllib.request.Request('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:batchGet?' +
                urllib.parse.urlencode(query), headers={'Authorization': 'Bearer owner'})
            with urllib.request.urlopen(request, timeout=15) as response:
                page = json.load(response)
            users.extend(page.get('users', [])); token = page.get('nextPageToken')
            if not token:
                break
            if token in seen:
                raise RuntimeError('Repeated local Auth pagination token.')
            seen.add(token)
        return sorted(users, key=lambda row: row.get('localId', ''))

    def prepare(self, out):
        if self.database() is not None:
            raise RuntimeError('Refusing to overwrite an existing test namespace.')
        self.shared_before = {ns: digest(self.database(namespace=ns)) for ns in ['demo-studypath', 'demo-studypath-default-rtdb']}
        users = self.auth_users()
        self.auth_before = digest(users); self.auth_before_ids = {row['localId'] for row in users}
        node = os.environ.get('STUDYPATH_NODE', '/home/keni/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
        command = [node, 'android/test-fixtures/b07-policy-fixture.mjs']
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError('B07 synthetic fixture generation failed.')
        fixture = json.loads(result.stdout)
        if fixture.get('mockOnly') is not True or fixture.get('realApprovalEstablished') is not False:
            raise RuntimeError('Explicit mock-only fixture required.')
        (out / 'mock-policy-fixture.json').write_text(json.dumps(fixture, indent=2) + '\n')
        self.owned = True
        self.database('.settings/rules', 'PUT', json.loads((ROOT / 'backend/database.rules.json').read_text()))
        self.database('catalog', 'PUT', fixture['catalog'])
        if self.database('catalog') != fixture['catalog']:
            raise RuntimeError('Synthetic policy catalog readback differs.')
        return {'namespace': self.namespace, 'mockOnly': True, 'realApprovalEstablished': False,
                'fixtureCommand': command, 'fixtureExitCode': result.returncode,
                'catalogSha256': digest(fixture['catalog']), 'scoreBinding': fixture['scoreBinding']}

    def claim_uid(self, uid):
        if not isinstance(uid, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', uid) or uid in self.auth_before_ids:
            raise RuntimeError('Refusing to claim a preexisting or invalid account.')
        self.owned_uids.add(uid)

    def cleanup(self):
        # Delete only identities absent at baseline and explicitly observed in this owned test.
        # Account JSON, salts, password hashes and tokens are held in memory and never persisted.
        for uid in self.owned_uids:
            request = urllib.request.Request('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/projects/demo-studypath/accounts:delete',
                method='POST', headers={'Authorization': 'Bearer owner', 'Content-Type': 'application/json'},
                data=json.dumps({'localId': uid}).encode())
            with urllib.request.urlopen(request, timeout=15) as response:
                json.load(response)
        if self.owned:
            self.database(method='DELETE')
            if self.database() is not None:
                raise RuntimeError('Owned B07 namespace cleanup failed.')
        result = {'namespaceCleaned': self.owned, 'ownedAuthAccountsCleaned': len(self.owned_uids)}
        if self.shared_before is not None:
            after = {ns: digest(self.database(namespace=ns)) for ns in self.shared_before}
            result.update(sharedBeforeSha256=self.shared_before, sharedAfterSha256=after)
            if after != self.shared_before:
                raise RuntimeError('Shared database controls changed.')
        if self.auth_before is not None:
            after = digest(self.auth_users())
            result.update(authBeforeSha256=self.auth_before, authAfterSha256=after)
            if after != self.auth_before:
                raise RuntimeError('Shared Auth controls changed; inspect owned cleanup.')
        return result
