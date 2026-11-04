# Local multiuser Firebase exercise

This harness exercises the current Auth + Realtime Database rules using real emulator-issued email/password sessions. It never targets a cloud project. It hard-requires `demo-studypath-scale`, namespace `demo-studypath-scale-default-rtdb`, Auth `127.0.0.1:9199`, and Database `127.0.0.1:9100`. It compares the loaded isolated database rules with `backend/database.rules.json` before creating accounts.

Firebase CLI requires its rule file to stay inside its configuration directory. Prepare an exact disposable copy, then start the separate services from `backend` (use Node 24.12+ and the repository's installed Firebase CLI):

```sh
cp database.rules.json scalability/database.rules.json
./node_modules/.bin/firebase emulators:start --config scalability/firebase.scale.json --project demo-studypath-scale --only auth,database
```

`scalability/database.rules.json` is ignored generated input; refresh the copy after source rule changes. The harness checks that the actually loaded rules equal the current source, so a stale copy fails before registration. An equivalent configuration and exact source copy in a dedicated evidence directory works as well.

The isolated hub uses 9140 and logging uses 9150. Keep the existing 9000/9099 services and preview data intact. Do not run the existing rules suites against these ports while this harness runs; those suites clear their test namespaces and can replace rules.

From the workspace root, in a second terminal:

```sh
/home/keni/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node backend/scalability/multiuser.mjs --out implementation/phases/02-ui/20261104-backend-scale-audit/multiuser
```

Optional bounded flags: `--levels 1,10,25,50,100`, `--concurrency 10` (maximum 20), and `--hold-ms 1500` (500–10000). The harness refuses remote hosts, other projects/namespaces, and the normal emulator ports. No Firebase key or Admin credentials are required. The project string is a dummy emulator API key.

Each wave uses independent newly registered workload accounts. One additional security sentinel, excluded from workload and persistent connection counts, provides a real foreign UID for the one-user wave. Larger waves target the next workload UID. Credentials and tokens stay in memory; the report contains only synthetic labels and timing/error metadata.

The contract phases cover registration, password login, refresh-token exchange, valid UID-owned profile write/read, exact stable-ID practice retries and denied mutation, atomic saved-reading/slot writes with immutable provenance, separate annotation write/read/retry, and relogin recovery. Cross-user profile and practice reads/writes must return a real permission denial; an invalid token, unexpected HTTP error, or timeout fails the check. Owner reads verify rejected mutations leave accepted records intact. A score without approved policy must be denied. No positive academic eligibility or official grade behavior is inferred.

REST operations use a bounded worker pool; 100 registered accounts alone do not establish 100 concurrent database connections. A separate phase creates one named Firebase JS app/Auth/Database context per workload user, authenticates each independently, keeps each `.info/connected` listener open, and verifies an owned server read. Earlier contexts remain connected while later contexts start. Once setup finishes, the harness samples the total connected count every 100 ms for the hold interval. It records peak actual connected, minimum connected during the hold, and count changes. It then unsubscribes, goes offline, signs out, and deletes each app.

`results.json` records per-wave p50/p95 durations and failure counts by phase, raw sanitized samples, connection counts, rule identity, runtime identity, and exact-owned cleanup outcomes. A phase containing multiple requests measures the entire scenario; these are phase latencies, not per-request latency distributions. Cleanup deletes only `users/<UID>` for accounts registered by this invocation, verifies that exact subtree is absent, and deletes those Auth accounts through their own ID tokens. No root database or Auth-wide clear endpoint is used. Earlier waves' accounts remain stored but disconnected until final cleanup, so the full run registers 186 workload accounts plus one sentinel while opening at most 100 workload SDK connections at once.

Run direct checks without emulator services:

```sh
/home/keni/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node --test --test-isolation=none --test-reporter=tap backend/scalability/multiuser.test.mjs
```

This is local correctness and short connection-hold evidence. It is not a sustained load benchmark, a quota/plan assessment, production/cloud capacity proof, Android client lifecycle coverage, or physical-device evidence. Real cloud capacity depends on the configured Firebase plan, quotas, payloads, connection/listener/query patterns, geography, and sustained workload.
