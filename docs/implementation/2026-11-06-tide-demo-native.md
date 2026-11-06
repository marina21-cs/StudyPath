# Tide demo, Planner and profile — native implementation

Implements the approved five-slide proposal in Kotlin/XML Views. Version target: 0.1.8 (9).

## Behavior

- Welcome and Profile/Settings expose **Try the student demo**. A separate, non-exported DemoActivity holds fictional Alex Reyes data in an activity ViewModel: five subjects, 15 units, three tasks, nine authored notes, final grades, sample score template and illustrative prerequisite links.
- Demo never obtains the application repository, a UID or PersonalStudyStore. It neither changes auth/binding nor schedules reminders or downloads PDFs. Configuration changes retain demo edits; closing the demo discards them. Process death can start a fresh demo. Reset restores only in-memory sample records. Global appearance is a real app preference, explicitly separate from fictional student data.
- PlannerHomeFragment replaces the root Planner stack with Overview / Subjects / Grades. Overview presents tasks and one material continuation. Subjects groups add/import/manage. Grades links GWA, scenarios, map and per-subject score entry. Reviewed policy-bound tools remain a separate destination; capability guards are unchanged.
- ProfileFragment shows editable identity and actual personal subject/unit/material counts. Settings holds Light/Dark/System, reminders, storage, learning preferences, family sharing, account/sync controls and app information. Existing reminder, family and email-verification actions remain accessible.
- Theme uses semantic Tide light/night colors. Category icons/names remain, while color-maximalist card fills become neutral/Tide surfaces. Giraffe remains in welcome/Study; identity uses student initials. Existing native press feedback remains.
- Privacy and Terms are labeled test-build notices, not final public-service policies or an acceptance flow. No operator/contact or professor approval is invented.

## Academic boundaries

Fictional recorded grades yield 1.75 GWA. Excluding Physics yields 1.625 before display rounding. Calculus sample quiz/midterm/final weights yield 84.6/100. What-if uses separate explicit assumptions (41 points + 40% final), yielding 75 at an 85% final score. Scenario and draft changes never modify recorded final grades. Real GWA still requires explicit common-scale confirmation.

## Verification outcome

The native implementation passed 508 JVM tests, the full local-Firebase/live-material personal-workspace device smoke (112.135 seconds), and a focused demo/isolation/theme device test (19.728 seconds). Six native screenshots document the demo. The cloud-configured 0.1.8 (9) debug APK was built from all 308 matching tested Android source files and has the same signing certificate as 0.1.7. Commands, hashes, failures corrected during testing and remaining gaps are recorded in `implementation/phases/03-validation/tide-demo-018/README.md`. Production Firebase acceptance, exhaustive accessibility coverage and institutional-policy acceptance remain separate.
