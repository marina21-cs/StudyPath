# StudyPath 0.1.12 grading replacement verification

Date: 6 November 2026. Version 0.1.12 (13). Scope: replace active grading on all subjects, retain prior scores in visible history, persist the default and initialize future subjects.

## Regression and JVM

Before the fix, focused `SemesterGradingTest.replacementInitializesEverySubjectWithoutLegacyCategoryRequirements` failed with IllegalArgumentException (one test, one failure). The demo’s existing scored categories prevented applying a new Activities template. See regression-before.log.

`./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-parallel --console=plain` exited 0, BUILD SUCCESSFUL in 47s. **532 tests / 60 suites**, zero failures/errors/skips. See build.log and jvm-results.json. Tests cover all-subject replacement without legacy names, unchanged archived drafts, repeat applications, aggregation replacement without reinterpreting scores, invalid atomic saves, final-grade preservation, persistence, new-subject inheritance, explicit subject overrides and owner isolation.

## Android

After installing the built app and test APKs, the focused instrumentation command exited 0 with **OK (7 tests), 69.638 seconds**:

```sh
adb -s emulator-5554 shell am instrument -w -r -e class com.studypath.app.SemesterDemoDeviceTest,com.studypath.app.GradingSetupDeviceTest,com.studypath.app.TideDemoDeviceTest,com.studypath.app.NavigationAssetsDeviceTest com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner
```

The demo selects Activities 60% and Exams / Tests 40%, survives recreation, saves across all five subjects, shows Quiz 1 (16/20) in Previous grading, exposes the new categories on a second subject, and restores 60% when setup is reopened. Existing PDF/OCR, scale/formula, header, navigation, theme, avatar and isolation checks passed. See focused-device.log.

Only the disposable read-only StudyPath_Lite_Test is used; normal preview data is not cleared or rebound. The signed-in smoke passed: `android/test-device.sh emulator-5554 --live-api --personal-workspace` exited 0, explicit **OK (1 test), 206.991 seconds**. A fresh read-only test AVD and local Firebase seed were used. The real workspace replaces its old Quizzes setup with Activities 100%, exposes Quiz 1 through Previous grading, verifies all subjects’ new categories and exact archived scores, then completes GWA/scenario/map and PDF checks. See smoke-verification.json and smoke-instrumentation.log.

## Scope

Previous scored drafts remain available read-only under Grades & scores → Previous grading. New active drafts start without assessments; old scores are excluded from current estimates. Existing recorded final grades and GWA inputs are unchanged. Real workspace changes are local, UID-owned atomic saves. Demo changes remain session-only. Production Firebase and physical-device acceptance are separate from these checks.

## Cloud-configured APK

`android/build-cloud-candidate.sh --output /home/keni/Documents/ChatGPT/SE/.build/cloud-candidates/grading-reset-0.1.12` exited 0, BUILD SUCCESSFUL in 45s. All **333** Android source hashes matched tested-source.json. `apksigner verify --print-certs` passed with certificate `9d573838eb0968cd1e0f63a71f61d67f811a38cd4c52265407c1b28f33b79605`, matching 0.1.11. APK size: 60018308 bytes; SHA-256 `ecd862d6a19a2eeeb80ab1396608a9df94b16707e5891439c93076a174402b97`. Build status remains BUILT_CLOUD_UNVERIFIED: no production Firebase acceptance is claimed. See cloud-artifact.json.

The native review screenshot was inspected: selected scale, 100% total, affected subjects, history explanation and final replacement action are visible without clipping. The smoke wrapper did not auto-pull this extra image; it was retrieved from its test-generated external-files path.
