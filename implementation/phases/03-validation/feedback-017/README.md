# 0.1.7 feedback and Tide Play verification

User requested the selected slide-32 direction and slide flows in the native APK, including feedback slides 35–40, then GitHub publication. See the [screen coverage](../../../../docs/releases/v0.1.7.md) and [personal-draft decision](../../../../docs/decisions/2026-11-06-personal-academic-drafts.md).

## Scope

Visible academic toolkit; persistent draft score/template editing with raw GradeEngine arithmetic; external review-copy preparation without invented approval; explicit mapping to published course tools; GWA inclusion/common-scale confirmation; independent what-if calculations; connected personal prerequisite map with equivalent list; PDF URL/Share intake with bounded validated offline files; cleaned Wikipedia extraction; Tide Play tokens, raised buttons, subject tiles and giraffe placement.

## Commands and observed results

- `./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --console=plain`: final source build exit 0, 32 seconds; 504 JVM tests across 56 suites, zero failures/errors/skips. Counts are in `jvm-results.json`.
- `android/start-lite-emulator.sh --test --headless`: isolated `StudyPath_Lite_Test`, API 36, 720×1280, read-only. One AVD at a time. Normal preview data was not touched.
- `npm run emulators` and `FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000 npm run seed` in backend: local demo-studypath Auth/RTDB ready and synthetic catalog seeded, exit 0 for seed. No production deployment.
- First extended smoke: exit 0, explicit `OK (1 test)`, 92.205 seconds. It checked grades after recreation, GWA 1.75, a 75-point scenario, graph/list, URL validation and actual W3C PDF offline save/reopen, plus the prior live-reading, auth and COR flows.
- Added download-failure recovery: exit 0, explicit `OK (1 test)`, 148.768 seconds. Failure created no offline item; editing to a valid link completed. Receipt retained in `pre-chrome/`.
- Visual review found the new detail fragments missing from the main-toolbar detail predicate. Added them, plus back-button and hidden-root-navigation assertions. The network-failure fixture now uses a closed device-local HTTPS port rather than relying on public 404 timing. Final navigation run: exit 0, explicit `OK (1 test)`, 142.676 seconds. Back-button and hidden-bottom-navigation assertions passed for all academic destinations. Receipt: `device-verification.json`; local APK SHA-256 `c122817e8cc1aa5f0c27e8cdbef2f45e5a94ae780565ea1cba3a2a0bd4711ab4`.
- One smoke launch exited 1 before instrumentation because the fresh emulator had not finished booting. Retried after `sys.boot_completed=1`; no account/data reset was used.
- Earlier compile attempts caught a Kotlin setClipData chaining error and nullable Context call; both were corrected before successful builds. A duplicate Gradle invocation was cancelled; the subsequent clean verification succeeded.
- Actual Kotlin `WikipediaBody` extractor, compiled and run against the captured DeepSeek revision 1382597292: exit 0, 10,523 characters, article lead before any sidebar material. Four parser regressions passed.

`tested-source.json` records the frozen Android inputs for local/cloud comparison. Build-generated files, private configuration and signing files are excluded by the existing candidate script.

## Remaining acceptance boundaries

Real institutional policies and professor approvals are not fabricated. Draft calculations are labeled and existing reviewed-policy guards remain. Personal map links mean “requires all”; official eligibility remains unknown without reviewed rules. Cloud operation, broad TalkBack/text-scale checks, external Share providers, process-death/airplane-mode recovery, interrupted transfers and real COR layouts still need broader phone coverage. Saved reading notes/place remain attached to their original snapshot; a cleaned live article is a separate view. This receipt does not close B07/B08 or overall academic-policy acceptance.

## Cloud artifact

`android/build-cloud-candidate.sh --output /home/keni/Documents/ChatGPT/SE/.build/cloud-candidates/tide-feedback-0.1.7`: exit 0, build 36 seconds, emulator mode false. All 299 Android source hashes match `tested-source.json`. Signature and package checks exited 0. Cloud APK SHA-256 `48e8959d3c25ee3f75c30f2323671a04ed00744172d2200b7ec35bff394c874c`, 57,203,034 bytes, package com.studypath.app, 0.1.7 (8). Signer matches the prior published APK. See `cloud-artifact.json`. Cloud operation remains unverified; no cloud account or catalog was created.
