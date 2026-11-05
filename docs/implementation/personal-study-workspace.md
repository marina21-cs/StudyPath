# Personal study workspace and reading access

The 0.1.6 phone-testing revision connects personal study tools inside an established, signed-in StudyPath workspace. It does not require a published course catalog. It preserves the existing account binding, authentication, approved-package checks and academic-policy guards.

## User behavior

| Area | Behavior |
| --- | --- |
| My subjects | Study, Saved and Planner observe the same UID-owned personal workspace. Subjects have editable categories with vector icons. A single subject dropdown and underline tabs separate subject selection from Tasks, Materials and Grades. The options menu holds Add/Edit; the empty state keeps an explicit Add action. Users can create, rename and remove subjects, add dated tasks, and mark tasks done. Dates organize tasks; they do not schedule notifications. |
| Saved materials | Saved → Materials groups personal PDFs and study cards by subject, with approved course downloads in a separate subsection. Rows open the same material IDs used by My subjects. |
| Personal lessons | Users author text and an optional question/answer pair. Show answer and Try again reveal or hide the stored answer; there is no automatic grading, score or attempt submission. |
| PDFs | The system document picker selects a PDF, which is copied into the account's app-private storage. Each import must be a renderable PDF with 1–500 pages, at most 25 MiB; all PDFs for that account share a 250 MiB limit. Cancelling or rejecting an import leaves existing materials intact. |
| PDF reader | Native image rendering provides Previous/Next, validated page-number entry, 2× zoom/Fit page and remembered position. Zoomed pages can be scrolled horizontally. PDF text is not extracted or selectable. |
| Planner | A semester overview, subject cards with recorded grades, and up to five unfinished tasks appear above school tools. Personal organization works with an empty catalog. Manual grades and prerequisite links are personal records; official calculations and eligibility still require a reviewed policy. |
| Manual academic details | A subject can store a course code, units, a recorded grade and prerequisite subject links. Circular, self-referencing and missing-subject links are rejected. No official grade conversion or eligibility is inferred. |
| COR import | Paste or open a bounded text/CSV table with Code, Subject, Units columns; review/edit the table, select rows, then confirm. Existing course codes are not overwritten. Categories are suggested from code prefixes and remain editable. PDFs/images support a draggable course-name crop, an equivalent numeric crop editor, rotation/page controls and bundled on-device Latin-script OCR. Recognized names must be reviewed; codes and units are not inferred. Only reviewed names are used for Wikipedia suggestions and external PDF/module searches. The sample is explicitly labeled demo. |
| Course packs | Study and Planner open a dedicated browser of approved published option records. Users choose pack subjects and save through the existing journaled preference repository. An empty catalog offers personal subjects; errors offer retry. A selected pack alone does not approve an academic policy. |
| Profile | Account opens a separate name editor. It accepts a trimmed, nonblank name of at most 80 characters. Learning-preferences editing no longer edits the name. Save errors retain input and offer retry guidance. |
| Navigation | Study, Saved, Planner and Account remain the root destinations. Personal materials, profile editing and full articles are detail screens with Back and hidden bottom navigation. Changing root tabs clears the detail back stack. |

Personal subjects, manual academic details, tasks, authored lessons and PDFs are stored on this phone for the bound account. They are not approved course content, cloud-synced records or family-shared academic results. They persist in app storage across normal updates; uninstalling or clearing app data removes them. Unreadable workspace metadata produces an error with Retry and keeps the existing files.

The workspace allows up to 100 subjects, 500 tasks and 200 materials, with bounded text and metadata sizes. See the [data contract](../architecture/personal-study-data.md) for storage and ownership rules.

## Full article and saved excerpt

Reading offers **Read full article** and **Make a study card**. The card action opens My subjects with a suggested title; it does not automatically create a card, generate questions or copy article content.

The full reader fetches the latest Wikipedia page by key and displays extracted native text in pages. It provides selectable text, page selection, Previous/Next, and source, revision and license links. Images and tables remain available through Wikipedia's source page. The fetched article may have a newer revision than the saved excerpt.

Saved readings remain attributed excerpts of at most 8,000 characters. Full article text is not added to their offline snapshots or Firebase records. A failed article fetch offers Retry/source access, and Back returns to the retained excerpt. Full-article offline availability is not guaranteed.

## Verification status

The current candidate is **0.1.6 (7)**; see its [release verification](../releases/v0.1.6.md) and [phone checklist](../releases/v0.1.6-phone-checklist.md). Tide adds semantic subject colors, raised blue actions, subject tiles and purposeful giraffe placements.

The preceding **0.1.5 (6)** candidate: The focused subject-header follow-up passed 486 JVM tests, the extended native smoke (116.47 seconds, exit 0), source identity and signature checks. Actual Tasks and Grades screenshots were inspected. See [focused control evidence](../../implementation/phases/02-ui/20261104-connected-study/focused-subject-controls.md) and the [APK/screenshots](../../deliverables/StudyPath-Focused-Subjects-20261104/README.md). This verifies the changed flows, not all project acceptance gates or cross-device cloud sync.

The preceding 0.1.4 evidence remains recorded below for the underlying connected-workspace changes:

| Check | Observed result |
| --- | --- |
| Frozen local build `connected-local-06` | Exit 0; 486 JVM tests, zero failures/errors/skips. Includes observable local updates, legacy JSON compatibility, prerequisite integrity and COR parsing. |
| Final local build `connected-local-07` | Exit 0; JVM task up-to-date. Only instrumentation dialog targeting changed from local-06; application and JVM-test sources stayed the same. |
| `connected-native-07` | Exit 0 and explicit `OK (1 test)` for the extended connected-workspace scenario. Exact synthetic Auth/RTDB cleanup confirmed. |
| `reading-native-07` | Instrumentation completed `OK (1 test)` in 19.159 seconds. Its wrapper was interrupted afterward (143); the device observation receipt was recovered and all eight checks verified. Exact synthetic cleanup confirmed separately. |
| `connected-cloud-06` | Exit 0; existing `studypath-c8086` configuration, emulator mode false. Production sources match the tested local build; no production signup or deployment performed. |
| APK | Version/signature/hash verified; signer matches 0.1.3. Install as an update. |

The extended native flow covers cross-tab subject/material visibility, task completion, manual grade display in Planner, COR sample review/confirmation, prerequisite persistence, empty course-pack fallback, selecting a temporary approved local option, profile/preferences restoration, PDF import from a supplied URI, page navigation/zoom/jump, Firebase-offline personal work, live article pagination and the existing sign-in/logout baseline. Temporary approved options are test fixtures, not production publications.

The separate reading regression covers saved-reading resume, independent note/place saves, attribution access, pending offline notes and return to the originating Saved shelf. The real system PDF picker was opened/cancelled; the import result URI was test-supplied. Third-party provider selection, real text-file COR selection, physical airplane mode/restart, malformed/encrypted PDF failures, broad font/TalkBack coverage and real institutional content remain phone/project checks. Personal editor dialog drafts are not guaranteed across rotation. Personal records have no cloud backup; task dates do not schedule notifications; The earlier 0.1.5 candidate did not implement COR PDF/image OCR; 0.1.6 adds the reviewed crop/OCR flow.

[APK, screenshots and phone checklist](../../deliverables/StudyPath-Connected-Study-20261104/README.md) · [Commands, build identity and repair history](../../implementation/phases/02-ui/20261104-connected-study/README.md) · [Defect investigation](../bugs/2026-11-04-connected-study.md) · [Historical 0.1.3 release](../releases/v0.1.3.md)

## Source map

- [Personal workspace UI](../../android/app/src/main/java/com/studypath/app/ui/PersonalWorkspaceFragment.kt) and [local store](../../android/app/src/main/java/com/studypath/app/data/PersonalStudyStore.kt).
- [Profile editor](../../android/app/src/main/java/com/studypath/app/ui/EditProfileFragment.kt) and [owned profile repository](../../android/app/src/main/java/com/studypath/app/data/FirebaseStudyRepository.kt).
- [Full article UI](../../android/app/src/main/java/com/studypath/app/ui/FullArticleFragment.kt), [Wikipedia client](../../android/app/src/main/java/com/studypath/app/data/MediaWikiClient.kt) and [text pagination](../../android/app/src/main/java/com/studypath/app/data/ReadingPages.kt).

- [Shared subject cards](../../android/app/src/main/java/com/studypath/app/ui/SubjectCards.kt), [Saved materials](../../android/app/src/main/java/com/studypath/app/ui/SavedFragment.kt) and [Planner](../../android/app/src/main/java/com/studypath/app/ui/planner/PlannerFragment.kt).
- [COR parser](../../android/app/src/main/java/com/studypath/app/data/CorImport.kt), [COR review](../../android/app/src/main/java/com/studypath/app/ui/CorImportFragment.kt) and [course-pack browser](../../android/app/src/main/java/com/studypath/app/ui/CoursePacksFragment.kt).
- [Native PDF controls](../../android/app/src/main/java/com/studypath/app/ui/OfflinePdfView.kt).
