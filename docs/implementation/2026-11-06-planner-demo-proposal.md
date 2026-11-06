# Planner/demo/profile refinement proposal — 6 November 2026

Status: localhost design proposal; Android implementation and release are not changed in this turn.

Preview: http://localhost:8788/planner-demo.html?palette=tide#1
Profile/settings comparison: http://localhost:8788/planner-demo.html?palette=tide#5
Source: `deliverables/studypath-redesign-20261104/planner-demo*.{html,css,js,json}`. Contract: adjacent `planner-demo-design/`.

## Scope

Five slides propose demo entry, a compact Planner, five populated subjects, grouped academic tools, and profile/settings with light/dark themes. The user's latest Tide-only request supersedes multicolor maximalism for this proposal. Keep the original deck intact for comparison.

Fictional Alex Reyes has five three-unit subjects (Calculus, Data Structures, Databases, Physics, Communication), three tasks and nine authored sample notes. Recorded sample grades produce 26.25 / 15 = 1.75 GWA. Demo changes are memory-only. No sign-in, download, notification scheduling or native account writes occur.

Duolingo inspiration is limited to identity/avatar editing and accessible settings entry, supported by https://blog.duolingo.com/avatar-creator/ . Grouped settings and study stats are our adaptation. Retain StudyPath branding and giraffe; mascot on welcome/Study only. Privacy and Terms are draft structures, not published legal policies.

## Native follow-up

Implement isolated demo data ownership, persisted light/dark/system preferences, profile editing and reorganized Planner in Kotlin/XML. Existing real-account state and offline queues must survive demo entry/reset/exit. Native fixture isolation, calculations, recreation, TalkBack and large-text verification are required before a new APK claim.

## Verification

JavaScript syntax checks and browser checks are recorded in the local contract folder's verification.md. The browser shows five slides and functional demo interactions. Android tests were not run because this change only authors a planning prototype and documentation.
