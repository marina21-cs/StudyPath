# Disconnected personal study surfaces — 4 November 2026

## Report and cause

The user reported that Planner/Saved did not sync with personal subjects/PDFs and that Choose course pack did not work. In 0.1.3, Saved's lesson shelf only rendered approved downloads. Planner read personal tasks once and did not expose subjects/grades. Personal content was reachable through shortcuts, but was not represented in those root views. Choose course pack routed to preference editing without showing whether a published pack existed.

This investigation treats sync as same-phone cross-tab consistency; cross-device personal backup remains unimplemented and is explicitly documented. It does not establish a new defect in Firebase saved-reading synchronization.

## Fix

All personal surfaces observe the same UID-owned atomic store. Saved groups imported materials by subject; Planner renders subject/grade/task cards; Study opens the same subject IDs. A dedicated course-pack browser shows available approved options, selected state, retry and an independent-study fallback. Personal grades/prerequisites and COR review are separate from policy-approved academic records.

## Regressions found during verification

- An unrelated subject deletion changed untouched prerequisite metadata from null to an empty list. The existing preservation test caught it; deletion now changes only affected references.
- Applying the shared quiet-button style during Saved tab selection reset weighted widths, pushing Materials off-screen. Native testing caught it; styling now precedes width assignment.
- The temporary pack-selection test legitimately changed a synthetic profile beyond the old cleanup expectation. The final test verifies the exact expected preference/timestamp edit before cleanup; it does not broadly allow arbitrary profile changes.
- An Espresso page-jump interaction selected a root without focus. The captured dialog was rendered; explicit dialog-root targeting completed the final run.

## Evidence and remaining scope

Final application sources passed 486 JVM tests and the extended native connected-workspace flow. An independent saved-reading regression completed all eight observations, including independent note/place saving and pending offline notes. Its wrapper was interrupted after successful JUnit output; the receipt was recovered and exact synthetic cleanup verified. The normal preview's data was not cleared.

[Exact commands, attempts and limits](../../implementation/phases/02-ui/20261104-connected-study/README.md) · [Behavior contract](../implementation/personal-study-workspace.md) · [Data contract](../architecture/personal-study-data.md).

## Follow-up: redundant subject controls

The phone review found a colored subject card, Switch subject action, Add action and three outlined tab buttons stacked above content. The same subject was represented twice, and borders made every control compete with the content.

Replaced the stack with one neutral subject dropdown, an accessible 48 dp options menu for Add/Edit, and native underline tabs. Empty-state Add remains explicit. Subject selection keeps the active content destination; editing and deletion still use the existing validated editor. See [focused control verification](../../implementation/phases/02-ui/20261104-connected-study/focused-subject-controls.md) for the new build/device evidence.
