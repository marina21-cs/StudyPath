# Demo navigation spacing and system insets

The user's 720 × 1600 screenshot showed Saved's labels separated from icons at the bottom edge. The signed-in shell suppressed Material's bottom inset handling, but the programmatic demo did not. Both roots already reserved system-bar space. Material 1.12.0 therefore added bottom padding again in the demo; default typography/spacing also differed.

A focused device check against 0.1.8 reproduced the defect: navigation paddingBottom was 48 pixels instead of zero on the API 36 test emulator. The test failed before the fix. Material's source confirms its default bottom inset padding behavior: https://github.com/material-components/material-components-android/blob/1.12.0/lib/java/com/google/android/material/bottomnavigation/BottomNavigationView.java#L133-L154.

`CapsuleBottomNavigationView` now owns common icon/label typography, dimensions, colors and symmetric padding. Its roots own system insets. Demo uses explicit match-parent root sizing and wrap-content navigation; navigation remains 64dp, matching Material 1.12's fixed-size labels and scaled long-press tooltips. A first 200% text test caught extra icon/label separation from growing the bar while Material kept labels fixed; the redundant height growth was removed. Material still owns selection, accessibility and touch handling.

Verification and screenshots: `implementation/phases/03-validation/tide-assets-019/README.md`. The original physical handset has not been re-tested; emulator evidence is recorded separately.
