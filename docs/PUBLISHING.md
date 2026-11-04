# Git and APK releases

The public repository is [marina21-cs/StudyPath](https://github.com/marina21-cs/StudyPath). `main` holds the public source. APK binaries are attached to GitHub Releases, with testing builds marked as prereleases.

## Everyday source changes

Create a focused branch, inspect the diff, run checks appropriate to the change, and commit only the intended files:

```sh
git switch -c codex/describe-the-change
git status --short
git diff
# Run the relevant checks before committing.
git add path/to/changed-file
git commit -m "Describe the change"
git push -u origin HEAD
```

Use a pull request for review before merging into `main`. Keep signing keys, Firebase credentials, real account data, build caches, and raw device reports out of commits. `.gitignore` preserves these local files while excluding them from publication.

## Publish a verified APK

1. Finish the source change and record build/test/device evidence with the exact APK identity. Keep existing preview account data intact during tests.
2. Update `versionName` and `versionCode` when preparing a new Android version. Keep the same signing key for compatible updates.
3. Verify the selected APK with Android SDK `apksigner` and `aapt`, and generate its SHA-256 file. Review it for the intended local/cloud configuration.
4. Update the README's version, direct download link, checksum, and release status. Add a sanitized record under `docs/releases/`.
5. Commit and push the matching source before creating the release.

For a future version, replace the example tag and paths below with the checked version and existing files:

```sh
gh release create v0.1.4 /path/to/StudyPath.apk /path/to/SHA256.txt \
  --repo marina21-cs/StudyPath \
  --target main \
  --title "StudyPath 0.1.4" \
  --prerelease \
  --notes-file /path/to/release-notes.md
```

Keep the uploaded APK name **StudyPath.apk**. Its direct link follows this format:

```text
https://github.com/marina21-cs/StudyPath/releases/download/VERSION_TAG/StudyPath.apk
```

Prereleases do not use GitHub's `releases/latest` download alias. Share the explicit version link, and use the repository's Releases page to browse newer builds. After uploading, confirm the release visibility, asset size, and checksum against the local APK.
