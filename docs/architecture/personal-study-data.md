# Personal study data boundaries

The [personal-workspace revision](../implementation/personal-study-workspace.md) adds device-local study content beside the existing Firebase-backed account and approved curriculum. It introduces no cloud binary store or public publishing pipeline.

| Data | Owner and location | Writes and boundaries |
| --- | --- | --- |
| Subjects, manual academic details, tasks and authored study cards | App-private `filesDir/personal-study/<SHA-256(uid)>` metadata | A current gated session and matching installation binding are required. No Firebase, catalog, academic-record or family-summary write is made. The hashed directory name is not encryption. |
| Imported PDFs | Generated filenames in the same account-private store | The picker URI is copied immediately; viewing does not depend on retaining permission to the original URI. Source filenames never become storage paths. No binary upload occurs. |
| PDF reading position | Account/material-specific local preferences | Remembers a page independently of course packages or Firebase annotations. |
| Display name | Existing RTDB `/users/{uid}/profile` | `saveProfileName` journals an owned merge containing only `displayName`. It does not replace preferences, change Firebase Auth's display name, or write `updatedAt`. Authentication remains separate. |
| Saved reading | Existing bounded, attributed reading record | The 8,000-character excerpt, its saved revision and existing sync contract remain intact. |
| Full article text | Separate online fetch and native reader | Fetches the latest page by key, validates its own revision/license metadata and renders extracted text. It does not enlarge the saved excerpt or create a full-article offline package. |

## Local consistency and limits

[PersonalStudyStore](../../android/app/src/main/java/com/studypath/app/data/PersonalStudyStore.kt) validates a complete metadata change before writing a temporary file, syncing it and atomically replacing the metadata. Invalid or corrupt data is reported rather than silently reset. References must identify an existing subject; deleting a subject removes its dependent task/material metadata, removes its prerequisite references from remaining subjects, and attempts deletion of associated PDF files.

### Shared observations and migration

`observe()` maps a process-wide revision counter to a fresh read of the calling store's UID-owned file on the IO dispatcher, suppressing equal snapshots. A successful atomic update increments the counter. The counter carries no user data and is not a network acknowledgement. Initial collection rereads disk, so process restart does not depend on retaining the counter. Study, Saved, Planner and My subjects collect while their view lifecycle is started.

Subject records add optional category, course code, units, grade and prerequisite-ID fields. Missing fields in 0.1.3 JSON remain valid; unknown/missing category presentation falls back to General. Untouched legacy fields are not normalized during unrelated deletion. Codes are at most 24 characters, recorded grades at most 16, and units must be finite and in (0, 30]. Up to 20 unique prerequisites must identify other existing subjects; DFS validation rejects cycles before commit. Grades remain strings without school-scale conversion. No manual field can approve a curriculum or affect official GWA/eligibility.

### COR and published packs

`CorImport` parses bounded text/CSV, tab-separated or pipe-separated Code/Subject/Units tables, including quoted CSV cells. It rejects invalid units, duplicate codes and over-limit input. The review screen stores a draft; only confirmation merges selected rows into the personal store. Duplicate existing codes fail rather than overwrite. Suggested categories are presentation metadata, editable afterward. The sample is labeled demo.

COR PDF/image selection is staged temporarily (25 MiB bound). PDFs render at most 500 pages, with a long edge of 1800 pixels; images use sampled decoding. A normalized crop is editable by dragging corners or entering percentages. The bundled ML Kit Latin recognizer processes only that crop on-device. Page, rotation and crop survive activity recreation. Users correct one name per line and review before merging; codes/units are left empty. Duplicate existing names fail instead of creating duplicate subjects. Reviewed names alone drive Wikipedia suggestions and external PDF searches. No COR upload, remote OCR service, automatic PDF download or inferred school approval is introduced.

`CoursePacksFragment` reads the existing published learner-option index and filters to approved records. Selecting a pack saves chosen subjects through `saveLearnerPreferences`, preserving its journal, ownership checks and fresh option validation. It introduces no new Firebase schema or client catalog writes. Academic policy checks still apply independently. Empty pack results route to the local workspace.

Limits are 100 subjects, 500 tasks, 200 materials and 3,000,000 metadata bytes. Subject/task/material titles are limited to 80/160/120 characters. Authored lesson text is limited to 20,000 characters, questions to 1,000 and answers to 4,000; a question and answer must either both exist or both be blank.

PDF import uses app-private staging, a 25 MiB file limit and 250 MiB account aggregate limit. It checks the PDF signature and Android `PdfRenderer` opening 1–500 pages, then rechecks account ownership before committing metadata. Failure or cancellation removes incomplete staging/uncommitted targets. The reader rasterizes bounded pages on IO and recycles superseded/cancelled bitmaps. Page-number entry is range checked; 2× zoom uses horizontal scrolling and the existing render bounds. It does not run a WebView, OCR or question generator.

There is no StudyPath cloud synchronization or backup for this content. The [manifest](../../android/app/src/main/AndroidManifest.xml) requests Android backup disabled with `android:allowBackup="false"`; this is not a universal guarantee about OEM transfer tools. Clearing app data or uninstalling removes the local workspace. No cross-device restore/export contract is introduced.

## Online reading boundary

[MediaWikiClient](../../android/app/src/main/java/com/studypath/app/data/MediaWikiClient.kt) fetches `/page/{key}/with_html` independently of the saved revision. Its 2,000,000-byte response limit covers the entire JSON/HTML response, not a guaranteed amount of article body text. Existing request serialization, bounded 429/503 retries and provenance validation still apply.

[ReadingPages](../../android/app/src/main/java/com/studypath/app/data/ReadingPages.kt) preserves every character of the extracted text while splitting it into pages of at most 6,000 characters. This does not promise extraction of every Wikipedia element: images and tables remain at the linked source. Normal HTTP caching may apply, but full-article offline retention is not guaranteed. Fetch failure leaves the previously saved excerpt available.

These boundaries are source-supported contracts. Device acceptance and the remaining verification gaps are recorded in the [implementation note](../implementation/personal-study-workspace.md#verification-status).

## Photo notes (0.1.13)

PersonalMaterial now has optional `photos` (generated image ID and caption) and `source` (same-subject PDF ID and one-based page). Older text-only JSON remains readable. Photos are bounded normalized JPEG files under the same UID directory in `note-images/`; they are not encoded into workspace JSON or synchronized to Firebase. A metadata commit follows moving new image files, with rollback on commit failure. Deleting a PDF detaches its notes instead of deleting their text/photos. The demo uses the same note schema/editor with separate in-memory metadata and a session cache directory.
