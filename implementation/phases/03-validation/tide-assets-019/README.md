# StudyPath 0.1.9 — navigation and assets verification

Date: 2026-11-06. Scope: shared bottom navigation, bundled avatar picker, reading/celebration giraffe states and empty-state placement. Native Kotlin/XML remains the application stack.

## Reproduction and correction

A new `NavigationAssetsDeviceTest.navigationStaysCompactAboveSystemBars` check against 0.1.8 failed with `paddingBottom expected 0 but was 48`. Both the root and Material applied system insets in the demo. See `navbar-before.log` and `docs/bugs/2026-11-06-demo-navbar-insets.md`.

The shared navigation now suppresses duplicate inset application, sets 64dp minimum height, groups icons/labels and uses common Tide typography/colors. Material 1.12 intentionally keeps its navigation labels unscaled and exposes scaled long-press tooltips. An initial 200% text check caught an unnecessary bar-height increase while labels stayed fixed; that increase was removed. The original physical handset remains untested.

## Build and JVM tests

```sh
./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-parallel --console=plain
```

Exit 0, 23 seconds on final application build. 508 JVM tests / 57 suites, zero failures/errors/skips. The final added screenshot assertions compiled in a subsequent Android test build (exit 0, 4 seconds). `git diff --check` passed. Two intermediate test compilation errors (a helper outside its scope and string rather than integer night-mode arguments) were corrected before device runs.

## Focused Android checks

Used only `StudyPath_Lite_Test`, API 36, serial emulator-5554. Normal preview data was not cleared or overwritten. Installed the debug and test APKs with `adb install -r`.

```sh
adb -s emulator-5554 shell wm size 720x1600
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.studypath.app.NavigationAssetsDeviceTest,com.studypath.app.TideDemoDeviceTest \
  com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner
```

Final result: **OK (3 tests), 28.748 seconds**; runner process exit 0. Observed:

- Light and dark navigation: no duplicated bottom padding, all four icon/label groups visible above system bars, compact height and spacing.
- Avatar choices: glasses, hoodie, giraffe and initials reset; Cancel retains selection; selected art survives Activity recreation and theme switch.
- Private preference scope: a synthetic UID's choice is retained, a different UID defaults to initials, and demo edits/reset leave the real preferences map unchanged.
- Native reader mascot displayed on Study; completing the three sample tasks displays the celebration pose and copy.
- Existing full demo interactions, GWA, scenarios, score entry, prerequisite map, theme/reset/exit and personal-file/binding isolation continue to pass.

Evidence: `focused-device.log` and `final/` screenshots. Native avatar, book and celebration screens were visually inspected.

Additional navigation configuration:

```sh
adb -s emulator-5554 shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb -s emulator-5554 shell settings put system font_scale 2.0
adb -s emulator-5554 shell settings put global animator_duration_scale 0
adb -s emulator-5554 shell am instrument -w -r \
  -e class 'com.studypath.app.NavigationAssetsDeviceTest#navigationStaysCompactAboveSystemBars' \
  com.studypath.app.test/androidx.test.runner.AndroidJUnitRunner
```

**OK (1 test), 4.228 seconds**, process exit 0, both light and dark at 720 × 1600. See `large-text-device.log` and `large-text/saved-navigation-{light,dark}.png`. The screenshot was visually inspected: system controls and all destination labels remain separate. This is a navbar check, not exhaustive 200% text or TalkBack acceptance for the entire app.

Restored gesture navigation, font_scale 1.0 and the previously absent animator scale setting; reset screen size to the physical 720 × 1280 before restarting the read-only test AVD for registration smoke.

## Signed-in smoke and cloud distributable

Command: `android/test-device.sh emulator-5554 --live-api --personal-workspace` on a fresh read-only test AVD, with existing seeded local Firebase services. **JUnit OK (1 test), 163.612 seconds.** The smoke covered registration/sign-in, Wikipedia and saved/offline materials, COR scan/OCR/review/recommendations, grades/GWA/scenarios/map, profile name editing, avatar selection and retention after navigating away/back, demo entry/exit with unchanged account binding, reminders, sign-out and same-account sign-in.

The wrapper exited 1 after a transient ADB disconnect during screenshot transfer, after explicit JUnit success. The original FAILED receipt is retained in `smoke-original-verification.json`; it is not rewritten as a passing process. ADB recovered without restarting the AVD. Four missing named captures were pulled using `adb -s emulator-5554 pull`; all 21 expected PNG captures are now present. See `smoke-evidence-recovery.json` and `smoke-instrumentation.log`. The signed-in Profile screenshot was visually inspected and retained here. No test rerun, app-data clearing or broader private-file transfer was used to recover the evidence.

Cloud-configured distributable built with `android/build-cloud-candidate.sh --output /home/keni/Documents/ChatGPT/SE/.build/cloud-candidates/tide-assets-0.1.9` (exit 0). All 314 Android source hashes match the tested source. `apksigner verify --print-certs` passed and the signer matches 0.1.8. `aapt dump badging` confirms com.studypath.app version 0.1.9 (10). SHA-256: `ad51481edd4e0011fae17c676b241fd9d117c1d2c6c73ae9407c0e180cf9269d`, size 60,276,800 bytes. Full identity is recorded in `cloud-artifact.json`. No cloud production acceptance is implied by local-emulator tests or the candidate build.

## Product boundaries

Avatar choices are local per UID, not cloud-synced photos. Demo selection is in-memory. Bundled art is available offline. Privacy/Terms remain test-build notices. B07 lifecycle/reminder and B08 cloud acceptance remain separate; no Firebase rules or deployment changed in this patch.
