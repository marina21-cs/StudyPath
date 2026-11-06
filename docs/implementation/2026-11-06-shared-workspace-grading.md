# Shared workspace UI and personal grading setup

Target: StudyPath 0.1.10 (11). User request: use the demo layout in the real app, fix clipped headers and unreadable dark tabs, and expose configurable grading during Planner setup.

## UI behavior

`WorkspaceUi` installs one native Kotlin/XML shell for MainActivity and DemoActivity. System-bar, cutout and keyboard insets are installed before attachment and requested on attachment. The root owns these insets. The header and navigation sit outside the scrolling page. Study greeting/mascot, continue card, semester summary, Planner tabs/tasks/subject rows/GWA actions, Profile identity and compact material rows share rendering helpers. The real workspace retains its live search, download, policy and account functions. The demo banner and fictional data remain explicit; demo edits never obtain a real repository or personal store.

Saved selected tabs use `sp_action` / `sp_on_action`; unselected tabs use `sp_card` / `sp_ink` in both themes. Reviewed Planner, interactive lesson selections and family controls also use semantic action ink.

## Planner grading setup

Overview and Grades expose **Set up grading → choose subject**. Both personal and demo workspaces use `GradeDraftEditor`:

1. Add categories: Activities, Quizzes, Exams / Tests, Projects, Homework, Participation, Practical / Lab, or a custom name. Enter each category's percentage. Drafts can be saved incrementally; calculations require a 100% total.
2. Combine assessments by pooled points, equal assessment percentages, or explicit assessment weights. Existing scores must be removed before changing aggregation so their meaning is not silently changed.
3. Choose a grade display: zero-based percentage, an explicitly entered increasing linear numeric range, or custom letter/numeric threshold bands. Bands must cover 0%, have distinct thresholds and use inclusive lower bounds. Conversion uses the unrounded raw percentage. An optional passing threshold is explicitly a raw percentage.
4. Add, edit or remove earned/possible scores for any category. Blank means ungraded, zero means scored zero, excused work is excluded. Category removal requires confirmation and removes its assessment scores.

A passing mark of 75 on 0–100 is distinct from linear conversion into 75–100. The latter displays its equation and example before saving. No institutional thresholds or passing policy are preselected. Changing category rules or scale invalidates the prepared review copy. The review export includes configured scale and raw passing threshold.

Calculations are current graded-work snapshots, not guaranteed final grades. Every category needs assessments before calculation. Unsupported missing-work treatment and all-excused categories remain unavailable. No converted draft value automatically changes a recorded final grade, GWA or prerequisite eligibility. GWA still requires explicit inclusion and common numeric-scale confirmation. Professor approval still requires a reviewed published policy; a personal setup cannot grant it.

## Persistence and compatibility

The optional `PersonalGradeDraft.scale` is stored atomically in the existing UID-owned local personal workspace. Older records without this property remain valid raw-percentage drafts. Demo drafts remain in its Activity ViewModel, survive recreation, and reset with the demo. No Firebase schema or deployment changed. Personal drafts are local, not cloud-synced.

## Validation

Actual commands, observations, source/APK identity and limits are recorded in `implementation/phases/03-validation/tide-parity-0110/README.md`. This implementation document does not itself assert device or cloud acceptance.

## Approved PDF/reviewer addition

The user subsequently approved the interactive reviewer. Its delivered scope, source-checking flow and limits are described in `docs/implementation/2026-11-06-pdf-interactive-reviewer.md`, with data flow in `docs/architecture/pdf-reviewer.md`. This uses on-device OCR and deterministic extractive drafts; generative summaries, distractors and worked solutions remain outside this release.
