# Personal study workspace and reading access

This phone-testing revision adds personal study tools inside an established, signed-in StudyPath workspace. It does not require a published course catalog. It preserves the existing account binding, authentication, approved-package checks and academic-policy guards.

## User behavior

| Area | Behavior |
| --- | --- |
| My subjects | Study, Saved and Planner open the same personal workspace. Users can create, rename and remove subjects, add tasks with optional dates, and toggle tasks between unfinished and done. Dates organize tasks; they do not schedule notifications. |
| Personal lessons | Users author text and an optional question/answer pair. Show answer and Try again reveal or hide the stored answer; there is no automatic grading, score or attempt submission. |
| PDFs | The system document picker selects a PDF, which is copied into the account's app-private storage. Each import must be a renderable PDF with 1–500 pages, at most 25 MiB; all PDFs for that account share a 250 MiB limit. Cancelling or rejecting an import leaves existing materials intact. |
| PDF reader | Native image rendering provides Previous/Next and remembers the page for the account/material. PDF text is not extracted or selectable; this is not an OCR or accessible-text reader. |
| Planner | Up to five unfinished personal tasks appear above course tools. Personal organization remains available with an empty catalog. Grade estimates and prerequisites still require the applicable reviewed course pack and policy. |
| Profile | Account opens a separate name editor. It accepts a trimmed, nonblank name of at most 80 characters. Learning-preferences editing no longer edits the name. Save errors retain input and offer retry guidance. |
| Navigation | Study, Saved, Planner and Account remain the root destinations. Personal materials, profile editing and full articles are detail screens with Back and hidden bottom navigation. Changing root tabs clears the detail back stack. |

Personal subjects, tasks, authored lessons and PDFs are stored on this phone for the bound account. They are not approved course content, cloud-synced records or family-shared academic results. They persist in app storage across normal updates; uninstalling or clearing app data removes them. Unreadable workspace metadata produces an error with Retry and keeps the existing files.

The workspace allows up to 100 subjects, 500 tasks and 200 materials, with bounded text and metadata sizes. See the [data contract](../architecture/personal-study-data.md) for storage and ownership rules.

## Full article and saved excerpt

Reading offers **Read full article** and **Make a study card**. The card action opens My subjects with a suggested title; it does not automatically create a card, generate questions or copy article content.

The full reader fetches the latest Wikipedia page by key and displays extracted native text in pages. It provides selectable text, page selection, Previous/Next, and source, revision and license links. Images and tables remain available through Wikipedia's source page. The fetched article may have a newer revision than the saved excerpt.

Saved readings remain attributed excerpts of at most 8,000 characters. Full article text is not added to their offline snapshots or Firebase records. A failed article fetch offers Retry/source access, and Back returns to the retained excerpt. Full-article offline availability is not guaranteed.

## Verification status

The final 4 November 2026 test candidate is **0.1.3 (4)**. It is a phone-testing APK, not a declaration that the wider project acceptance gates are closed.

| Check | Observed result |
| --- | --- |
| Frozen local build `personal-local-06` | Exit 0; 482 JVM tests, 0 failures/errors/skips; app and instrumentation APKs assembled. |
| Matching native run `personal-native-06` | Exit 0, explicit `OK (1 test)`; extended flow completed in 79.009 seconds. |
| Synthetic cleanup | Exact owned RTDB tree deletion/readback and Auth deletion/lookup denial confirmed; original installation binding preserved. |
| Frozen cloud build `personal-cloud-06` | Exit 0; emulator mode false, existing `studypath-c8086` configuration. Source manifest matches the tested local build. |
| APK checks | Version/signature verified; signer matches 0.1.2. No backend deployment occurred. |

The extended native flow observed distinct profile editing and real name-save/restore round trips; manual subjects, task completion and authored question/answer reveal; equal-height tabs; real DocumentsUI opening/cancellation; production PDF import from a supplied test URI and two-page native viewing; personal work while Firebase networking was disabled; an empty course catalog with manual planner access; and real Wikipedia article page 2. The baseline registration, saved library, policy guard, account controls, logout and same-account return also passed.

The system picker cancellation was real. The PDF selection result was supplied by the test to the production import method, so selecting a file from a third-party provider is still a phone check. Disabling Firebase is not a physical airplane-mode test. PDF limits/encrypted or malformed failures, interrupted imports, broader font/TalkBack checks, changed-subject preference save/retry, and production-cloud operation on the user's phone remain separate checks. Personal editor dialog drafts are not guaranteed across rotation; save before leaving the editor. Full article text is not an offline package, and personal task dates do not schedule notifications.

An earlier native attempt lost its local Firebase process before feature checks; it failed closed and was not counted as a pass. Later clean runs, including the final one, confirmed cleanup. Historical build/test attempts remain in the phase evidence folder.

[APK and release verification](../releases/v0.1.3.md) · [Phone checklist](../releases/v0.1.3-phone-checklist.md) · [Data architecture](../architecture/personal-study-data.md)

## Source map

- [Personal workspace UI](../../android/app/src/main/java/com/studypath/app/ui/PersonalWorkspaceFragment.kt) and [local store](../../android/app/src/main/java/com/studypath/app/data/PersonalStudyStore.kt).
- [Profile editor](../../android/app/src/main/java/com/studypath/app/ui/EditProfileFragment.kt) and [owned profile repository](../../android/app/src/main/java/com/studypath/app/data/FirebaseStudyRepository.kt).
- [Full article UI](../../android/app/src/main/java/com/studypath/app/ui/FullArticleFragment.kt), [Wikipedia client](../../android/app/src/main/java/com/studypath/app/data/MediaWikiClient.kt) and [text pagination](../../android/app/src/main/java/com/studypath/app/data/ReadingPages.kt).
