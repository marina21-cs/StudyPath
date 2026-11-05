# Tide Play · native Android direction

The design follows the supplied AI design workflow: assess the screenshots, extract reusable reference patterns, choose tokens, review the localhost slides, translate the accepted direction into native controls, then inspect actual Android screens. Clucky informs the raised buttons, expressive subject colors, rounded geometry and brief character feedback. StudyPath keeps its own giraffe, content, navigation and academic boundaries.

## Before → after

| Before | After | Why |
| --- | --- | --- |
| Maroon actions and mostly uniform cards | Tide blue primary actions with sun, peach, mint and lilac subject surfaces | Separate actions from subject identity and make the library easier to scan. |
| Repeated subject-management shortcuts | Shared personal store across Study, Saved, Planner and subject details | Opening a subject reaches the same tasks and materials everywhere. |
| Large resume metadata blocks | Compact saved-place line and a yellow resume surface | Make the next useful action easier to find. |
| COR limited to text/CSV | Choose PDF/image → crop course names → on-device recognition → edit → review → suggestions | Guide the user through uncertain recognition before changing their workspace. |
| Oversized PDF navigation | Compact page buttons, page jump and zoom | Give the document more usable space. |

## Palette

| Role | Color |
| --- | --- |
| Primary action | `#315CB5` |
| Raised-button edge | `#1A3C7D` |
| Ink | `#203452` |
| Background | `#F2F5FA` |
| Soft blue | `#E0E8FA` |
| Sun | `#FFD66B` |
| Peach | `#FFC6A3` |
| Mint | `#BCE8D4` |
| Lilac | `#D8CCFF` |

Subject names and icons remain the primary identifiers; color is supplementary. Small screens and enlarged text use a single-column subject library. Primary buttons use a short 120 ms press and 150 ms release; motion respects Android's animation setting.

## Mascot placement

| Place | Role |
| --- | --- |
| Welcome | Prominent wake-up animation introducing the app. |
| Onboarding ready | A welcoming wave before entering Study. |
| Study heading | Small static greeting beside the heading. |
| Empty subjects/materials | A compact helper beside guidance for the first useful action. |
| Correct practice feedback | A 72 dp character inside the feedback card with a one-shot 320 ms wave. |
| Reading, PDF, grades and privacy | Content-focused layouts without additional mascot decoration. |

The mascot is decorative for accessibility. It does not replace success/error text or block Continue. Repeated rendering of the same answer does not restart its celebration.

## COR and recommendation boundaries

The original COR is processed on the phone. Users can drag crop corners or enter numeric percentages, switch PDF pages, and rotate the image. Recognition returns editable names; users remove headers, correct spelling and join wrapped names. Codes/units are not guessed. Confirmation adds selected subjects without overwriting existing ones.

Only reviewed names are used for online discovery. Wikipedia results appear in the app; PDF/module search opens the browser. Results are supplemental, not institutional approval, and files are never downloaded automatically. Real COR layouts and device file providers remain part of phone testing.
