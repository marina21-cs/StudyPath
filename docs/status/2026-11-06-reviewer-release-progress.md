# StudyPath 0.1.10 release handoff — 6 November 2026

## Published and previously verified

GitHub v0.1.9 was the previous published APK. Native Android Kotlin/XML Views + ViewBinding, Firebase email/password Authentication and Realtime Database, local UID-owned personal materials, Wikipedia retrieval, COR crop/OCR/review, PDF import/link/offline viewing, grades/GWA/scenarios/prerequisite map, a fictional five-subject demo, Tide light/dark/system theme and original bundled avatars/mascot states. It is a cloud-configured debug test build; production Firebase acceptance remains separate.

## Published and locally verified in 0.1.10

- Shared native shell and principal Study/Planner/Profile/material components between demo and account. Header inset handling and semantic dark selected-tab colors.
- Planner-visible flexible grading setup: custom categories/weights, pooled/equal/custom assessment aggregation, activity/project/homework/exam scores, raw percentage, explicitly configured linear numeric range or custom grade bands. Drafts are personal, unapproved, and do not automatically write final grades/GWA.
- Personal scanned-PDF reviewer: select 1–5 pages, bundled on-device OCR, corrected text, editable identification/cloze drafts, optional manual original-page image cards, explicit confirmation, source-page links, answer reveal, self-rated results/retry, saved progress and dated Planner task. Question drafts are deterministic extracts, not AI summaries. No generation-service upload. Full image PDF remains available; the reviewer is a practice layer linked to its source, not a replacement or a downloaded simplified PDF.

Final local verification: 526 JVM tests / 59 suites passed. Five focused Android tests passed in 59.393 seconds. The expanded signed-in smoke passed in 142.837 seconds with wrapper exit 0, including raster-only PDF OCR, editable reviewer, retry/source navigation, persistence, Planner task creation, theme/header checks and the existing feature baseline. Production Firebase acceptance is separate. A prior smoke found a shared subject-row tag regression; the tag is restored and the failed receipt retained.

Published release: https://github.com/marina21-cs/StudyPath/releases/tag/v0.1.10

APK: https://github.com/marina21-cs/StudyPath/releases/download/v0.1.10/StudyPath.apk

Source commit: `82fc63b92fd205743c267e51d180399449394ff5`. GitHub asset digest matches `f09604bc9769c0389b999f60a7afffc9d43dc9396e2bd757976a3308333b8919`. Version 0.1.10 (11), signed with the same certificate as 0.1.9. Cloud-configured debug test APK; production Firebase acceptance is still pending.

## Deferred

AI summaries, multiple-choice distractors, worked math generation, automatic understanding of scanned equations, multi-chapter reviewer packs larger than five source pages, and notification scheduling from the new personal review task are not included. Unsaved OCR/card drafts survive Activity recreation, not process death. Saved reviewer edits/reset require confirmation.

Sources: docs/implementation/2026-11-06-pdf-interactive-reviewer.md, docs/implementation/2026-11-06-shared-workspace-grading.md, docs/architecture/pdf-reviewer.md. Final evidence belongs in implementation/phases/03-validation/tide-parity-0110/README.md.
