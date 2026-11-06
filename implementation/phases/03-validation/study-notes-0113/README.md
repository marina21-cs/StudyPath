# Study notes 0.1.13 verification

## Scope

Native shared note editor for personal and demo workspaces, photo/caption persistence, optional review question/answer, page-linked notes and consolidated Add material entry. See `docs/implementation/2026-11-06-study-notes.md`.

## Evidence

- `build-final.log`: Gradle offline, no daemon, two workers, no parallel; JVM tests and debug app/instrumentation build exit 0. `jvm-results.json`: 536 tests / 61 suites, zero failures, errors or skips.
- `notes-device.log`: first three StudyNotesDeviceTest cases passed on StudyPath_Lite_Test.
- `smoke-pre-polish.json`, `smoke-pre-polish-instrumentation.log`: `android/test-device.sh emulator-5554 --live-api --personal-workspace`, exit 0, explicit OK (1 test). Local Auth/RTDB were started and seeded. Signed-in note/photo save and reopen passed alongside registration, Wikipedia and personal workspace checks. This smoke preceded final UI-only field-label/reader polish.
- `focused-final.log`: final polished UI; nine of ten checks passed. The remaining TideDemo check expected the removed “Read & reflect” heading. Updated that assertion to require Edit note and the actual lesson body; no application change was needed.
- `test-label-build.log` and `focused-corrected.log`: final regression expectation rebuild exit 0; focused rerun explicitly reports OK (10 tests), 84.424 seconds.
- `screenshots/`: actual emulator captures of Add material, photo editor and saved photo note. Visually inspected; final labels no longer overlap entered text.
- `tested-source.json`: frozen local-test input hashes. Cloud candidate manifest has identical source hashes except ephemeral Kotlin session markers. A subsequent test-only assertion update does not change shipped application code.
- `.build/cloud-candidates/study-notes-0.1.13/candidate-manifest.json`: cloud-configured build exit 0; production cloud behavior remains unverified.

APK: version 0.1.13 (14), 60,051,076 bytes, SHA-256 `1167afbd8a9380d4dd52ac0298cb6603a308b2dd77b96b5e5fb789d173a14888`. Certificate SHA-256 `9d573838eb0968cd1e0f63a71f61d67f811a38cd4c52265407c1b28f33b79605` matches 0.1.12.

## Observations and limits

Create/edit, save/reopen, caption storage, recreation, discard, PDF-page source return and invalid-image cleanup were exercised. Attachment decoding uses the real normalization path; test setup inserts the normalized attachment into the editor model. The system picker was opened and cancelled, not automated through a successful provider selection. Images are device-local, with no cloud sync; demo changes reset with the session. External PDF import is available in personal workspaces; demo uses bundled PDFs. No preview account data was cleared or rebound. Physical-device, production cloud, full accessibility and lifecycle acceptance remain separate.

## Published release

GitHub v0.1.13 is public (`draft: false`). Uploaded StudyPath.apk is 60,051,076 bytes and GitHub's SHA-256 matches the local candidate above. `curl -I -L` on the versioned APK URL returned HTTP 200. See `github-release.json` and `release-build.json`. The initial download 404 occurred while the release was still a draft and its APK was uploading; no link change was necessary after publication.
