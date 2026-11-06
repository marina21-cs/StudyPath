# Lightweight study notes

The subject Materials area has one **Add material** action. Personal workspaces offer a note with photos, a PDF file or a PDF link. The isolated demo offers the same note editor and its bundled PDF library. Demo PDF import from external files/links is not included in this increment.

The native editor supports a title, multiline text, up to six photos with captions, and an optional practice question/answer. Students can edit existing text lessons. Explicit Save persists the note, and Back asks before discarding unsaved changes. Draft text and captions survive activity recreation. A canceled photo picker preserves the draft.

PDFs expose **Add note for this page** and a list of attached page notes. A note opens its original PDF at the linked page. The PDF bytes remain unchanged. Removing a PDF retains its notes and clears their source link.

Photos remain private on the phone: the app copies the selected image through the Android document picker, bounds input to 12 MiB/80 megapixels, decodes with subsampling, applies EXIF orientation and writes a JPEG of at most 2 MiB. Captions are at most 500 characters. Note images have a 100 MiB workspace budget. No gallery-wide permission, remote image URL, photo upload, transcription, AI summary or collaboration service is added.

Personal image files live in the existing UID-owned workspace. Metadata saves remain atomic; a failed save rolls newly moved images back and preserves the previous note. Replaced/removed photos are deleted only after a successful commit. Demo photos live under a separate session cache and disappear on demo reset or session closure. A process crash can leave unreferenced staging/cache files; these are app-private and do not become materials.

This increment retains Tide colors and Android Kotlin/XML navigation. It does not change grading, GWA, task or reviewer calculations. Verification and release evidence: `implementation/phases/03-validation/study-notes-0113/`.

Presentation handoff: `deliverables/studypath-presentation-20261105/output/StudyPath-Presentation-20261106-v9-Study-Notes.pptx` adds actual Android Add material, editor and photo-note screenshots on slide 12 (23 slides total). Opening slide 2 and closing slide 23 carry the 0.1.13 GitHub APK QR. Final rendered QRs both decode to the versioned download URL; unchanged slides match v8 after excluding page counters. Earlier HTML/PDF exports remain earlier versions.
