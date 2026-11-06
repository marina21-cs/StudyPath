# StudyPath 0.1.11 verification

Date: 6 November 2026. Version 0.1.11 (12). Scope: shared all-current-subject grading setup, less crowded Wikipedia discovery, five bundled PDFs and isolated demo access to the production PDF reader/reviewer.

## Build and JVM

`./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-parallel --console=plain` exited 0. **531 tests / 60 suites, zero failures/errors/skips**, recorded in jvm-results.json. Tests cover applying to all subjects, preserving assessment identifiers and earned values, unchanged final grades/materials/tasks, atomic conflict rejection, 100% weights, explicit scale, duplicate category names, aggregation conflicts and invalidation of prepared review state. Demo fixture checks now include five PDFs and nine notes.

Initial Kotlin compilation rejected an inferred recursive render return type and a ViewParent layoutParams access; explicit Unit and View cast corrected both before these successful runs. An initial PDF render exposed host font substitution; PDFs were regenerated with embedded existing app fonts, and all ten rendered pages were inspected for legibility and clipping.

## Focused Android checks

Only the verified disposable read-only StudyPath_Lite_Test, API 36, emulator-5554 was used. No normal-preview app data was cleared, uninstalled or rebound.

After installing the built app/test APKs, command:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.studypath.app.SemesterDemoDeviceTest,com.studypath.app.GradingSetupDeviceTest,com.studypath.app.TideDemoDeviceTest,com.studypath.app.NavigationAssetsDeviceTest \
  com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner
```

**OK (7 tests), 81.277 seconds, shell exit 0.** See focused-device.log. Observed: grading-system-first setup; checkbox weights survive recreation; all five subjects receive the template; existing Quiz 1 remains editable; a previously unconfigured subject receives all categories. Each bundled PDF opens with two pages through Android PdfRenderer. The Data Structures file is opened through the UI, OCR extracts queue text, checked drafts are saved, answer reveal works, source navigation preserves the current card, and practice results survive recreation. Existing custom category/letter/linear calculations, header geometry in both themes, navigation, avatar, mascot and isolation/reset tests also pass.

## Signed-in smoke and release

**PASSED, wrapper exit 0, explicit JUnit OK (1 test), 186.268 seconds.** See smoke-verification.json and smoke-instrumentation.log. Command: `android/test-device.sh emulator-5554 --live-api --personal-workspace`, after closing and restarting only the read-only test AVD. The expanded test applied the shared setup to all real-workspace subjects and verified existing scores remained unchanged, in addition to baseline reading/import/OCR/planner/account flows.

## Scope limits

Personal grading is a user-entered draft; it does not confer professor approval or change GWA/final records. Applies to currently existing subjects, not an automatic default for future additions. OCR reviewer questions remain checked extractive drafts, not generated summaries or worked math. Demo edits/cards/tasks are in-memory session data. Physical handset, exhaustive large text and TalkBack checks are not claimed. Production Firebase/cloud acceptance remains separate from local emulator verification.

## Cloud-configured APK identity

`android/build-cloud-candidate.sh --output /home/keni/Documents/ChatGPT/SE/.build/cloud-candidates/semester-demo-0.1.11` exited 0, build successful in 32 seconds. Status `BUILT_CLOUD_UNVERIFIED`. All **333** source-file hashes exactly match tested-source.json. No production cloud operation is inferred from the build.

`aapt dump badging` confirms `com.studypath.app`, versionName 0.1.11, versionCode 12. `apksigner verify --print-certs` passed, certificate SHA-256 `9d573838eb0968cd1e0f63a71f61d67f811a38cd4c52265407c1b28f33b79605`, matching 0.1.10. APK: 60,439,963 bytes; SHA-256 `fd20647d88507873b181d25692f73a297ee4b4534c49c8678ee71201a80410e8`. See cloud-artifact.json.

A post-test optional attempt to launch non-exported MainActivity directly with adb was correctly denied by Android. No component export was changed; this is not an app-flow failure. Native screenshots from instrumentation were used for layout review instead.

## Published release

GitHub release v0.1.11 targets `b1128d995e9783cff8c4e19eae406fff96c459f2`. The GitHub API confirms a published release, uploaded StudyPath.apk, matching 60,439,963-byte size and SHA-256 digest. See github-release.json. Download: https://github.com/marina21-cs/StudyPath/releases/download/v0.1.11/StudyPath.apk
