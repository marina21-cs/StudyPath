# Personal PDF reviewer data flow

Native Android UI → UID-owned personal PDF → bounded page rasterization → bundled on-device ML Kit text recognition → editable page text → deterministic extractive question drafts → explicit learner review → atomic personal workspace save → self-rated practice.

`PdfReviewerFragment` owns the flow and uses a Fragment ViewModel for in-flight OCR, editable draft and current practice queue. It never sends text or documents to a generation API. `ReviewerDrafts` is a pure domain function: definition sentences become identification prompts; other suitable sentences become a cloze by hiding an existing word. It does not invent explanations or answer options. Student confirmation is required before saved practice.

`PersonalWorkspace.reviewers` is an optional backwards-compatible list. Each reviewer belongs to an existing PDF material and retains up to five numbered source texts, 40 cards and per-card last understanding/attempt count. Workspace validation limits 20 reviewers and the existing total 3 MB JSON budget. Saved data uses the existing atomic UID-owned store. Removing the original material/subject removes its reviewer. No Firebase schema or cloud deployment changes.

Cards retain the original page number and a source excerpt. A manual IMAGE card renders that original PDF page at practice time, without creating another bitmap file or interpreting its equations. Source navigation uses the existing private offline PDF renderer. Review results are self-reported and separate from grades/GWA. Plan review adds a dated personal Planner task; it does not silently enable notifications.

OCR processes pages serially and releases each bitmap before the next. Recognition finishes using its input before cancellation recycles the bitmap. Selected pages and corrected text are kept only in ViewModel memory until a reviewer is explicitly saved; unsaved drafts do not survive process death. Saved cards/results survive reopening. Long PDFs are reviewed in bounded selections; replacing the saved selection requires confirmation and resets reviewer progress.

## Isolated demo host (0.1.11)

The fragment can also be hosted by `DemoActivity` through `DemoReviewerHost`. This interface exposes only the selected demo material/file, in-memory reviewer storage, demo task creation and source-page navigation. The account graph/store is never opened in that branch. Original sample PDFs are bundled assets copied into demo-only cache. The activity owns a separate reviewer ViewModel per PDF so source-page navigation and configuration recreation retain the current draft/practice; resetting demo clears those models and the session. Real-account ViewModels remain Fragment-scoped and saved data remains UID-owned. Neither branch changes academic scores.
