# StudyPath 0.1.8 — Tide/demo verification

Verified on 2026-11-06. Native implementation scope is documented in `docs/implementation/2026-11-06-tide-demo-native.md`.

## Build and JVM checks

Command (exit 0):

```sh
./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-parallel --console=plain
```

508 tests across 57 suites passed, with zero failures, errors or skips. See `jvm-results.json`. Updated legacy device suites compiled; compilation is not a claim that each suite ran. `git diff --check` passed.

## Observed Android behavior

Used only the read-only `StudyPath_Lite_Test` AVD, API 36, 720 × 1280, serial emulator-5554. Started with `android/start-lite-emulator.sh --test --headless`; restarted this test AVD between fresh registration smoke attempts. Existing local Firebase services were seeded with `FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000 npm run seed` from backend. Normal preview data and account binding were not cleared or overwritten.

Command (exit 0; JUnit OK, 1 test, 112.135 seconds):

```sh
android/test-device.sh emulator-5554 --live-api --personal-workspace
```

Observed registration/sign-in, saved library, profile editing, personal subjects/tasks/PDFs, offline workspace recovery, course packs, grade/GWA/scenario/map access, linked-PDF failure/retry/offline behavior, Wikipedia, COR crop/OCR/review/recommendations, Profile → demo → exit with UID retained, Settings reminders, sign-out and sign-in. Auth/database used local Firebase emulators; public Wikipedia/PDF requests were live. See `smoke-verification.json` and `smoke-instrumentation.log`.

Command (exit 0; JUnit OK, 1 test, 19.728 seconds):

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.studypath.app.TideDemoDeviceTest \
  com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner
```

Observed demo task count 3 → 2, all five subjects, correct Physics note, GWA 1.75 → 1.63 excluding Physics, scenario 75 → 81, edited quiz estimate 88.6, connected prerequisite map/list, profile name Alex → Sam, dark theme plus Activity recreation, light theme, Privacy/Terms, reset restoring three tasks/Alex, and exit. Account binding and every pre-existing personal-study file hash remained unchanged. Test restores the preceding appearance preference. See `demo-final-instrumentation.log` and six native captures in `tide-demo-evidence/`.

## Artifact identity

- Version: 0.1.8 (9); package: com.studypath.app.
- Tested local APK SHA-256: `446c9d85cb31d9f94a7e05fd478292c0d1a7a0e3dd0e7c960604c52d96b7d6ef`.
- Cloud-configured debug distributable SHA-256: `30684659b0c1df4110a04fc7e11eef8c4c07894a70f724d4e1318692a9111ae8`.
- Distributable size: 58,487,151 bytes.
- Cloud build command (exit 0, 36 seconds): `android/build-cloud-candidate.sh --output /home/keni/Documents/ChatGPT/SE/.build/cloud-candidates/tide-demo-0.1.8`.
- All 308 Android source file hashes match the tested-source identity. Configuration differs between local emulator build and cloud candidate.
- `apksigner verify --print-certs` passed. Signer SHA-256 matches 0.1.7: `9d573838eb0968cd1e0f63a71f61d67f811a38cd4c52265407c1b28f33b79605`.
- Cloud status remains **BUILT_CLOUD_UNVERIFIED**: this records build/configuration, not production Firebase acceptance.

See `local-apk.json`, `cloud-artifact.json` and `tested-source.json`.

## Earlier attempts and corrections

An initial JVM assertion incorrectly validated intentionally non-persistent demo IDs using the real-store validator; the corrected test checks rejection by that validator and internal referential integrity. The first demo test had an ambiguous navigation text matcher; it now uses the navigation ID. Smoke attempts exposed an asynchronous profile-save wait and obsolete Planner task selectors; waits now observe the destination and the new Overview rows. One obsolete-selector attempt was stopped on the test AVD only. Boot-not-ready preflights were retried after boot completion. A missing test import was corrected. These failed attempts are not counted as passes. Some failed-smoke synthetic local accounts were retained because cleanup failed closed; deletion scope was not broadened.

## Remaining coverage and product boundaries

- Cloud authentication/database acceptance, B07 lifecycle/reminder cases and B08 cloud acceptance remain separate and open until individually observed.
- The separately compiled CloudAuth, FreshInstallLogin, NativeEmailVerification and NativeLearningRevision device suites were not individually executed in this run.
- Full TalkBack, large-font and exhaustive device-size coverage remain unverified.
- Privacy/Terms are explicitly test-build notices, not finalized public-service policies.
- Demo is fictional, in-memory and isolated; process death may restart it. Appearance is a real global app preference. Sample grades/maps do not constitute professor or institutional approval.
