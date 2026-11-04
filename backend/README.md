# StudyPath Firebase backend tooling

This directory contains StudyPath's Realtime Database schema, security rules, local demonstration seed, content tools, and tests. The [Android client](../android/README.md) uses Firebase email/password Authentication and Realtime Database directly. No custom server, Cloud Functions, or Firestore is required for local development.

## Run local services

Install **Node.js 24.12 or later** and **Java 21**, and make `node`, `npm`, and `java` available on `PATH`. From `backend/`:

```sh
npm ci
npm run emulators
```

Keep that terminal open. The checked-in [Firebase configuration](firebase.json) starts Auth at `127.0.0.1:9099` and Realtime Database at `127.0.0.1:9000`, using the local-only project `demo-studypath`. The first run may download the Firebase Database emulator.

In a second terminal, seed the synthetic catalog:

```sh
cd backend
FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000 npm run seed
```

Run `cd backend` from the repository root, or omit it if already in that directory. The seed refuses any other host and replaces only `catalog/` in the local demonstration namespace. It leaves `users/` intact, but replaces any existing local catalog customization. The seed's `Bearer owner` bypass is emulator administration only and must never be used in the Android client or against cloud services.

The local [catalog seed](seed/catalog.json) is explicitly `DEMONSTRATION_ONLY` and unapproved. It demonstrates record shapes and does not represent an approved curriculum, assessment plan, or lesson publication. The hosted lesson catalog used by the public APK is currently empty.

Use [`android/run-local.sh`](../android/run-local.sh) after building the Android app. It forwards ports 9099 and 9000 over ADB, installs the APK, and opens Welcome. The [Android guide](../android/README.md) also covers physical phones and the separate light test AVD.

## Test

Install dependencies with `npm ci`, then choose the appropriate command from this directory:

| Command | Purpose |
| --- | --- |
| `npm run test:tooling` | Pure content, sharing, mock, and rule-generation tooling tests; no Firebase services required. |
| `npm run test:integration` | Serial integration selections with disposable Firebase service lifecycles, including interactive lessons and family sharing. |
| `npm test` | Legacy core Auth/Database rules suite with its own service lifecycle. |
| `npm run test:catalog` | Pure catalog tooling selection. |

**Stop local preview/demo Firebase services before integration or rules tests.** Those tests clear disposable data and may use the same ports as the preview. Run them serially, using only local demo namespaces. Never point them at cloud services or services holding persistent preview data. The interactive and family rules selections use their separate [test configuration](firebase.b11-test.json).

[`package.json`](package.json) lists focused selections such as `test:auth`, `test:records`, `test:readings`, `test:study`, and `test:academics`. `test:rules` runs against already configured services and does not manage their lifecycle; prefer the managed commands above for normal use. A passing rules suite establishes only its tested local behavior.

## Data contract

[`schema-contract.json`](schema-contract.json) describes paths, required fields, versions, and bounds. [`database.rules.json`](database.rules.json) is the enforced ruleset. The default is deny; catalog reads require authentication and all catalog client writes are denied.

| Path | Purpose |
| --- | --- |
| `catalog/` | Maintainer-published options, module metadata/packages, reading starters, and reviewed policies. |
| `users/{uid}/profile` | Account profile and learner preferences. |
| `users/{uid}/savedReadings`, reading slots/annotations/contexts | Attributed text snapshots and owner reading state, including the twenty-slot shelf. |
| `users/{uid}/reviewProgress`, `practiceAttempts`, `lessonAttempts`, `lessonCompletionIndex` | Version-bound study progress and protected lesson completion records. |
| `users/{uid}/offlinePackages` | Download identity and acknowledgment receipts. |
| `users/{uid}/scores`, `courseAttempts`, `scenarios` | Policy-bound academic records and separate hypothetical scenarios. |
| `users/{uid}/reminders` | Reminder settings; Android schedules notifications. |
| `familyAcceptances`, `sharedStudy`, trusted sharing eligibility/roles | Narrow recipient consent and shared summary projections, governed by reviewed policy and expiry. |

Private records are scoped to the authenticated Firebase UID. Typed records reject unsupported fields and out-of-range values. A recorded score of zero differs from an ungraded result; academic writes require matching reviewed policy and plan identities. Calculations and self-reported outcomes do not certify institutional eligibility.

Saved snapshots retain source revision, license, and attribution. Immutable attempt/snapshot records support exact retries; protected lesson completion uses its own atomic rules. Shared summaries have their own restricted recipient access and do not grant access to private user records. Read the full contract and rules before introducing a new writer.

## Content and cloud boundaries

[`catalog/`](catalog) contains candidate validation and packaging tools; [`materials/`](materials) handles reading metadata; [`interactive/`](interactive) contains lesson-attempt rules tests; [`sharing/`](sharing) contains sharing contracts, rule generation, and tests; [`mock/`](mock) provides local mock support. Inspect each command's scope before use. Validating a candidate does not publish or approve it.

The default configuration is local only. Cloud setup, reviewed content publication, rule deployment, and Android signing are separate operations. Local tests do not establish cloud acceptance, approved academic policy, consent provenance, reminder delivery, or full device/offline behavior. Private Firebase configuration, credentials, user data, and signing keys are excluded from the repository. No paid Firebase service is needed for the local workflow.
