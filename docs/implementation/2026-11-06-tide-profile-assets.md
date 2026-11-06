# Tide profile art and mascot states

Version target: 0.1.9 (10). Complements the navbar bug fix with offline, bundled art.

## Profile pictures

Profile → Change picture offers initials, two original anime-inspired student portraits (Blue hoodie / Blue glasses), and the StudyPath giraffe. Selection is immediate; Cancel makes no change. Human portraits use Tide clothing/backgrounds and natural skin tones. They are not licensed character likenesses or user photos.

Real profile choice is a private device preference keyed by the authenticated UID, separate from name editing and cloud profile data. The picker explicitly says it is saved on this device. No uploads, image fetching, camera permission or Firebase schema change is introduced. Unrecognized selections fall back to initials.

Demo picture choice belongs to its activity ViewModel, survives configuration changes, resets to initials on Reset demo and is discarded on exit/process death. It does not write the real avatar preference.

## Mascot

The existing layered native giraffe gains a reading pose with a Tide book and a celebration pose with Tide stars. Study greetings use the reader; Saved's empty reading/material lists use the same artwork. Empty Planner subjects use the welcome pose; no tasks uses a resting pose; completing all tasks shows celebration. These are small supporting illustrations, with no decorative asset on grade calculations or the reading document itself. Motion settles, observes the system animator setting and cancels on detachment using the existing mascot lifecycle.

## Asset provenance

The two PNGs in `drawable-nodpi/avatar_tide_*.png` were generated for this project with the built-in image generation tool on 2026-11-06. Prompts requested original, centered anime-inspired college-student portraits with navy/periwinkle/ice-blue palette, no text or existing characters. Generated files are copied into the Android resources; runtime does not rely on the generation directory. The book/stars are editable native vectors using semantic Tide roles. Existing giraffe artwork remains unchanged.

Actual build, device, isolation and visual evidence is recorded under `implementation/phases/03-validation/tide-assets-019/`.
