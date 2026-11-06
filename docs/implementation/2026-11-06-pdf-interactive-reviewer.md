# Interactive reviewer from a personal PDF

User approved on 6 November 2026; included in target APK 0.1.10 (11).

Entry: import a personal PDF or save a PDF link offline, open it, then choose **Turn this PDF into a reviewer**. An existing pack exposes **Open interactive reviewer**.

1. Choose 1–5 pages from the PDF. OCR runs on the phone using the existing bundled recognizer. It supports printed English text for this workflow; poor scans, columns, formulas and diagrams need correction.
2. Check every selected page's extracted text. Open the original source page, edit recognition/order mistakes, and explicitly confirm the text. Empty or unsuitable pages offer manual card creation.
3. Check generated identification and fill-in-the-blank drafts. These are extractive questions, not AI summaries or new explanations. Edit question/answer/page, remove weak cards or add a manual card. Select **Show original page image** for equation/diagram cards and provide a confirmed answer. Confirm cards before saving.
4. Practice offline: type an optional answer, reveal the answer/source excerpt, mark **I understood this** or **Review again**, and retry missed cards. Each card can open its original PDF page. Results are self-rated, never written to academic grades.
5. Saved cards and last-result progress survive recreation/reopening. **Plan a review** creates a dated Planner task; its dialog states that it does not enable a notification.

A saved reviewer is replaced only on explicit confirmation. Replacement resets practice results. The original PDF remains the source. Deleting that material removes its reviewer; other subjects/accounts are unaffected. No content upload or generation-service cost is introduced.

Limits: one saved reviewer per PDF; up to five source pages, 40 cards and 20 PDF reviewers under the existing workspace size limit. This first version does not generate summaries, multiple-choice distractors or worked math solutions. Unsaved OCR/card drafts survive screen recreation through the ViewModel but not process death. Original-page image cards show the full source page, with the source viewer available for zoom.

Evidence and exact run outcomes are in `implementation/phases/03-validation/tide-parity-0110/README.md`. Architecture: `docs/architecture/pdf-reviewer.md`.
