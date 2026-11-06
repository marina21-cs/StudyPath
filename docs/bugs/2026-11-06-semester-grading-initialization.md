# Semester setup blocked by existing grading categories

The 0.1.11 all-subject flow required every scored legacy category to have a unique same-name category and unchanged aggregation in the new template. Advanced Calculus in the demo already had Quizzes, Midterm and Final exam scores. Selecting Activities and Exams therefore failed before saving, even though the user intended a complete replacement for all subjects.

A focused regression reproduced IllegalArgumentException before the fix. The replacement now initializes every subject’s active draft from the validated shared setup. Existing scored drafts are retained unchanged in visible Previous grading history rather than deleted or silently remapped. Final grades and GWA inputs remain intact. The shared default persists and initializes future subjects; explicit subject-specific drafts remain supported.

Validation receipts: `implementation/phases/03-validation/grading-reset-0112/README.md`. The regression covers demo subjects, category/aggregation changes, archive retention, repeat application, invalid atomic saves, persistent templates, future subjects and owner isolation. Native checks must also prove history is accessible and the new setup can save without legacy prerequisites.
