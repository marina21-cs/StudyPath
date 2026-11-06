# Semester-wide grading and presentation material

Planner's Set up grading now opens a shared full-screen flow for demo and real accounts. Choose the output system first (zero-based percentage, explicit linear range, or user-entered letter/numeric bands), select categories in a checklist, enter percentages, review the affected subjects, then apply to every current subject. No institutional thresholds are invented. From 0.1.12, this also saves a workspace default: new subjects without their own explicit grading draft inherit it. Reopening setup loads the saved formula and weights. Individual subject settings remain available from Grades & scores.

Weights must total 100%. From 0.1.12, applying replaces each subject’s active categories, weights and scale, without requiring old category names or aggregation methods to match. Each previous draft containing assessment scores is preserved unchanged in that subject’s read-only **Previous grading** history; those scores are excluded from the new estimate. The review screen states the affected subject list and number of scores retained before saving. Active scores begin empty. Reapplying an empty active setup does not add another history entry. Syllabus notes, recorded final grades, GWA inputs, materials and tasks remain unchanged. Review-prepared status resets. An all-subject template is not proof of a professor-approved grading policy.

The template and per-subject history use optional backward-compatible JSON fields in the existing UID-owned workspace. All subjects and the default save in one validated atomic file replacement. Invalid weights or a storage-size failure leave the previous workspace intact. Demo uses the same domain operation, with session-only persistence.

Wikipedia remains visible on Study in a distinct discovery card separated from subjects by 24dp. Optional topics, reading starters and course lessons are disclosed using Browse topics & course lessons. Search results hide unrelated study sections. No MediaWiki retrieval, attribution or saved-reading contract changes.

Demo includes five real, original two-page PDF handouts: calculus, data structures, databases, physics and communication, plus the existing nine notes. Each includes concepts, a worked example, a practice question and an answer. PDFs are bundled as assets, copied only to demo cache, and rendered using the same OfflinePdfView. The source generator is android/tools/build-demo-handouts.py, using the app's licensed Geist/Nunito fonts. These are presentation samples, not reviewed course modules or real student records.

The same PdfReviewerFragment supports demo through a narrow DemoReviewerHost interface: file lookup, reviewer read/write, task creation, and source navigation. Demo never obtains the application graph or PersonalStudyStore. Its session stores review cards, practice results and tasks in memory; all are discarded on exit/reset. Reviewer view models are scoped by PDF to the demo activity so returning from a source page retains practice state. Real-account behavior continues to use the UID-owned atomic store. No PDF content is uploaded for OCR.

Verification and release identity: implementation/phases/03-validation/semester-demo-0111/README.md. Device evidence is required before release claims.

Grading replacement follow-up: `implementation/phases/03-validation/grading-reset-0112/README.md`.
