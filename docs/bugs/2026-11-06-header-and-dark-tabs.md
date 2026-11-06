# Clipped header and unreadable dark Saved tabs

Reported in handset screenshots `Screenshot_20261106-092043.png` and `Screenshot_20261106-092302.png`.

## Findings

- Saved tab selection hardcoded white backgrounds/foregrounds, producing light text on white or pale blue in dark mode.
- Demo rebuilt its root on navigation and installed the system-inset listener after setting content. A replacement root could miss inset application. Real and demo shells also used different padding, headers and navigation construction, making fixes inconsistent.

## Correction

Use a shared `installStudyShell`: register the system-bar/cutout/IME listener before attaching content, request insets on attachment, and apply them at the root. Place toolbar/navigation outside scrolling content. Remove the real shell's extra outer frame/clip. Shared helpers align the principal Study, Planner, Profile and material components.

Use paired semantic foreground/background tokens for both Saved tab states. Related selected controls use `sp_on_action` instead of fixed white.

## Regression evidence

`GradingSetupDeviceTest` asserts the entire demo toolbar remains below the status/cutout inset after root navigation, opening a lesson, recreation, back navigation and Settings in both light and dark themes. The signed-in smoke checks toolbar geometry in captured MainActivity screens and verifies at least 4.5:1 text contrast for each Saved tab in both selected states and themes. The existing navigation/asset and demo-isolation checks are retained. See the dated 0.1.10 validation receipt for actual run outcomes. The original physical phone is not available to automated testing.
