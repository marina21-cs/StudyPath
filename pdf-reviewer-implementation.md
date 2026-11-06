# PDF reviewer implementation

Build an offline, source-linked reviewer from selected pages of a personal PDF, alongside the current 0.1.10 UI/grading fixes.

- [x] Add bounded reviewer data, deterministic extractive question drafts and UID-owned persistence; test source references, old records and zero cross-account leakage.
- [x] Reuse bundled OCR for up to five selected PDF pages; allow correction and original-page inspection before creating draft cards.
- [x] Add editable identification/cloze cards and manual original-page cards; require review before saving.
- [x] Add practice, answer reveal, self-rated understanding, retry missed, persisted progress and review scheduling; keep practice separate from academic scores.
- [x] Verify domain tests, actual scanned-PDF device flow, header/contrast/grading regressions and fresh local Firebase smoke; document and publish the verified APK.
