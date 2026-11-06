# StudyPath 0.1.10 verification

Date: 6 November 2026. Scope: shared native workspace UI, header insets, dark selected-control colors, personal grading configuration and scanned-PDF interactive reviewer. Target: version 0.1.10 (11).

## Builds and JVM checks

Final local compilation: `./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-parallel --console=plain` succeeded. The smoke runner subsequently rebuilt the final bitmap-cleanup change with the explicit local Firebase configuration (exit 0, 56 seconds). Final JVM command `./android/gradlew -p android :app:testDebugUnitTest --max-workers=2 --no-parallel --console=plain` exited 0 in 24 seconds. **526 tests / 59 suites, zero failures/errors/skips**, recorded in `jvm-results.json`.

New domain/store checks cover raw percentage vs explicit numeric transmutation, unrounded letter thresholds, incomplete weights, activity/exam/project/homework weights, legacy JSON, atomic persistence, account isolation, deterministic source-linked reviewer drafts, bounded/invalid sources, latest self-rated result/retry, manual image cards, persisted reviewer ownership and cleanup when a PDF/subject is removed. No Firebase rules changed.

## Focused Android checks

Only the disposable read-only `StudyPath_Lite_Test`, API 36, emulator-5554 was used. Normal preview data was not cleared, rebound, uninstalled or overwritten.

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.studypath.app.GradingSetupDeviceTest,com.studypath.app.TideDemoDeviceTest,com.studypath.app.NavigationAssetsDeviceTest \
  com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner
```

Final installed-source result: **OK (5 tests), 59.393 seconds**, shell exit 0. `focused-release-device.log` is the final receipt. Earlier pre-reviewer focused runs also passed (60.99 and 59.798 seconds); retained separately.

Observed custom Activities setup, weight total, editable earned/possible scores, letter B at 80%, explicit 75–100 linear conversion to 95, Activity recreation, and demo file/binding isolation. Header geometry remained below system-bar/cutout insets across root/detail navigation, source lesson, back, Settings and recreation in light/dark. Navigation, avatars, mascot poses, GWA/scenario controls and demo reset checks passed.

## Signed-in end-to-end smoke

Fresh AVD launched with `android/start-lite-emulator.sh --test --headless`; existing seeded local Firebase Auth/RTDB used. Command:

```sh
android/test-device.sh emulator-5554 --live-api --personal-workspace
```

Final **wrapper exit 0, PASSED, explicit JUnit OK (1 test), 142.837 seconds**. Local test APK SHA-256: `dba33fb5d962a1befd48cb643247b0f7cc115d7ec845286b9e613dc63f254f90`. See `smoke-verification.json` and `smoke-instrumentation.log`. All 21 baseline captures were collected; four additional named reviewer/contrast images were pulled separately.

The test registered a synthetic account and covered:

- Both Saved tabs in both selected states and both themes: at least 4.5:1 foreground/background contrast. Toolbar title text explicitly visible; captured MainActivity headers checked for unclipped geometry and readable title contrast.
- Personal material creation/import/viewing; a raster-only two-page PDF fixture was imported through the production URI-copy/validation method. DocumentsUI opening/cancellation was observed; automated file selection itself remains outside this test.
- Bundled OCR recognized “queue” from image pixels. The learner corrected text, confirmed it, edited an identification draft, added a manual original-page image card and explicitly saved the reviewer.
- Answers hidden before reveal; self-rated miss/understood, retry queue narrowed to the missed card, original page reopened, progress retained after Activity recreation, and a dated review task added. Subject grades were unchanged. The synthetic task was then removed to preserve baseline fixture expectations.
- Personal categories/scores persisted and calculated; GWA/scenarios/prerequisite graph/list, COR crop/OCR/review/recommendations, linked PDF validation/download, live Wikipedia and offline saved material flows, profile editing, account binding, logout/same-account sign-in.

Representative light/dark Saved and reviewer completion/home captures were inspected. Screenshots are supplementary evidence, not the basis of the test pass.

## Intermediate failures and corrections

A preflight invocation before the cold boot finished stopped without registering an account. The first pre-reviewer signed-in smoke failed when the shared Study subject row omitted its previous `subject_<id>` identifier; restored it in the shared row helper. Its failed JUnit log and wrapper receipt are retained as `first-smoke-failure.log` and `first-smoke-verification.json`. Subsequent full smoke passed. Intermediate test compilation issues (wildcard import ambiguity, a parenthesis, overloaded sumOf) and a local field-name shadow were corrected before final runs.

A quick screenshot interpretation initially suggested a missing title, but pixel inspection and the explicit title checks confirmed it was rendered. No speculative title fix was applied. The actual shell fix addresses inset listener timing and common layout ownership.

## Limits

Physical handset, exhaustive TalkBack/large-font acceptance, production Firebase acceptance and B07/B08 broader lifecycle/cloud cases remain outside these receipts. Reviewer outputs are editable extractive drafts, not generated summaries, multiple-choice distractors or math solutions. OCR is checked by the learner; manual image cards preserve original pages. Practice outcomes are self-rated. One saved reviewer per PDF, up to five pages and 40 cards; unsaved drafts do not survive process death. Review planning creates a dated task, not a notification. Personal grading formulas are unapproved assumptions and never automatically change final grades or GWA.

## Cloud-configured download artifact

`android/build-cloud-candidate.sh --output /home/keni/Documents/ChatGPT/SE/.build/cloud-candidates/tide-reviewer-0.1.10` exited 0 (Gradle 39 seconds). All 322 Android source hashes match the tested inventory. `apksigner verify --print-certs` exited 0; signer SHA-256 matches 0.1.9. `aapt dump badging` confirms com.studypath.app version 0.1.10 (11). APK SHA-256: `f09604bc9769c0389b999f60a7afffc9d43dc9396e2bd757976a3308333b8919`; 60278871 bytes. See `cloud-artifact.json`. Status remains BUILT_CLOUD_UNVERIFIED: this packages the production client configuration, without claiming production Firebase acceptance.

Published GitHub release: https://github.com/marina21-cs/StudyPath/releases/tag/v0.1.10 at source commit `82fc63b92fd205743c267e51d180399449394ff5`. The API reports the APK asset uploaded at 60,278,871 bytes with its matching SHA-256 digest. See `github-release.json`.
