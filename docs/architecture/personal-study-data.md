# Personal study data boundaries

The [personal-workspace revision](../implementation/personal-study-workspace.md) adds device-local study content beside the existing Firebase-backed account and approved curriculum. It introduces no cloud binary store or public publishing pipeline.

| Data | Owner and location | Writes and boundaries |
| --- | --- | --- |
| Subjects, manual tasks and authored study cards | App-private `filesDir/personal-study/<SHA-256(uid)>` metadata | A current gated session and matching installation binding are required. No Firebase, catalog, academic-record or family-summary write is made. The hashed directory name is not encryption. |
| Imported PDFs | Generated filenames in the same account-private store | The picker URI is copied immediately; viewing does not depend on retaining permission to the original URI. Source filenames never become storage paths. No binary upload occurs. |
| PDF reading position | Account/material-specific local preferences | Remembers a page independently of course packages or Firebase annotations. |
| Display name | Existing RTDB `/users/{uid}/profile` | `saveProfileName` journals an owned merge containing only `displayName`. It does not replace preferences, change Firebase Auth's display name, or write `updatedAt`. Authentication remains separate. |
| Saved reading | Existing bounded, attributed reading record | The 8,000-character excerpt, its saved revision and existing sync contract remain intact. |
| Full article text | Separate online fetch and native reader | Fetches the latest page by key, validates its own revision/license metadata and renders extracted text. It does not enlarge the saved excerpt or create a full-article offline package. |

## Local consistency and limits

[PersonalStudyStore](../../android/app/src/main/java/com/studypath/app/data/PersonalStudyStore.kt) validates a complete metadata change before writing a temporary file, syncing it and atomically replacing the metadata. Invalid or corrupt data is reported rather than silently reset. References must identify an existing subject; deleting a subject removes its dependent task/material metadata and attempts deletion of associated PDF files.

Limits are 100 subjects, 500 tasks, 200 materials and 3,000,000 metadata bytes. Subject/task/material titles are limited to 80/160/120 characters. Authored lesson text is limited to 20,000 characters, questions to 1,000 and answers to 4,000; a question and answer must either both exist or both be blank.

PDF import uses app-private staging, a 25 MiB file limit and 250 MiB account aggregate limit. It checks the PDF signature and Android `PdfRenderer` opening 1–500 pages, then rechecks account ownership before committing metadata. Failure or cancellation removes incomplete staging/uncommitted targets. The reader rasterizes bounded pages; it does not run a WebView, OCR or question generator.

There is no StudyPath cloud synchronization or backup for this content. The [manifest](../../android/app/src/main/AndroidManifest.xml) requests Android backup disabled with `android:allowBackup="false"`; this is not a universal guarantee about OEM transfer tools. Clearing app data or uninstalling removes the local workspace. No cross-device restore/export contract is introduced.

## Online reading boundary

[MediaWikiClient](../../android/app/src/main/java/com/studypath/app/data/MediaWikiClient.kt) fetches `/page/{key}/with_html` independently of the saved revision. Its 2,000,000-byte response limit covers the entire JSON/HTML response, not a guaranteed amount of article body text. Existing request serialization, bounded 429/503 retries and provenance validation still apply.

[ReadingPages](../../android/app/src/main/java/com/studypath/app/data/ReadingPages.kt) preserves every character of the extracted text while splitting it into pages of at most 6,000 characters. This does not promise extraction of every Wikipedia element: images and tables remain at the linked source. Normal HTTP caching may apply, but full-article offline retention is not guaranteed. Fetch failure leaves the previously saved excerpt available.

These boundaries are source-supported contracts. Device acceptance and the remaining verification gaps are recorded in the [implementation note](../implementation/personal-study-workspace.md#verification-status).
