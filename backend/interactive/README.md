# B11 lesson completion backend component

`users/{uid}/lessonAttempts/{runId}` records immutable completed instructional runs. The engine persists mutable drafts locally; it sends the same deterministic completion fields and ID on every retry. New completions bind to a public immutable catalog curriculum/module/version and package hash. Existing exact retries remain accepted after catalog withdrawal. Cross-UID reads/writes, unknown fields, changed results and accepted-completion deletion are denied, including deletion through ancestors.

The legacy owner-level write grant applies only when neither the old nor new subtree contains lesson attempts. Individual legacy collections retain owner grants, so legitimate scores/preferences/download changes still work after a completion. Owners cannot replace or delete a private ancestor containing completed lesson attempts. This is necessary because RTDB validation rules are skipped on deletion.

Counts remain self-reported study data. Rules enforce field types, bounds, completeness and identity; the native strict payload parser and lesson engine validate authored lesson/question membership, answer semantics and deterministic `responsesJson`. The server does not evaluate a JSON string or certify mastery. Completion writes use a separate collection and do not update academic records.

Run only against the separate B11 services:

```sh
cd backend
./node_modules/.bin/firebase emulators:exec --config firebase.b11-test.json --project demo-studypath-b11-rules --only database 'node --test --test-reporter=tap --test-concurrency=1 interactive/lesson-attempts.rules.test.mjs'
./node_modules/.bin/firebase emulators:exec --config firebase.b11-test.json --project demo-studypath-b11-rules --only database 'node interactive/run-isolated-regression.mjs'
```

The dedicated configuration uses database port 19000. Both runners require this host; the legacy adapter also rejects any remaining preview port or namespace before execution. Tests clear only their isolated test namespaces. Do not execute the legacy suites directly against running preview services.

Generate the synthetic device fixture without changing any database:

```sh
node backend/interactive/synthetic-lesson-fixture.mjs > /tmp/studypath-b11-synthetic-fixture.json
```

Its output contains `catalog` projections and deterministic `expected` values. All reviewer/approval/permission metadata is fabricated test data and every title/source identifies synthetic material. A device harness may import it only into its fresh isolated test namespace. This does not accept S00 reviewed sources, production publication, complete B10 assets, B11 private review, U03/U04 UI or full phase acceptance.
