# Wikipedia excerpt includes navigation content · 6 November 2026

Status: repaired in the 0.1.7 candidate; focused parser tests, captured DeepSeek probe and native live-reading smoke verified.

The user reported stray words and poor reading structure when opening DeepSeek in StudyPath. The app already uses MediaWiki REST `/page/{title}/with_html` and jsoup 1.18.3; this is not a lack of a parsing library.

## Evidence

Fetched the public DeepSeek API endpoint on 6 November 2026 (Asia/Manila): HTTP 200, 471,168 response bytes; revision 1382597292, timestamp 2026-10-29T11:56:36Z. A temporary Java probe using the installed jsoup 1.18.3 jar reproduced MediaWikiClient's removal selector, global `h2,h3,p,li` selection, leaf-list predicate and text joining. Command: `java --class-path <cached-jsoup-1.18.3.jar> /tmp/WikiAudit.java`, exit 0. No Android install/build was performed for this audit.

The probe selected 821 blocks, of which 679 had an infobox/navigation/reference-list ancestor across the full article. Infobox list items and AI sidebar topics appear at the beginning of the output, before the article prose. Citation marker `[1]` remains at character offset 3189. These are diagnostic counts for that captured revision, not stable production assertions. Raw inputs and probe outputs are local temporary evidence, not committed article copies.

## Cause and proposed fix

MediaWikiClient removes scripts/styles/media/edit links but does not remove article chrome such as infoboxes, sidebars, navigation and reference lists. It then selects every matching paragraph/list/heading in the document. Native TextView rendering cannot recover the intended reading structure after that flattening.

Create a dedicated article-block extractor, retain article order and list/heading roles, exclude non-article subtrees before walking once, and handle citations/formulas explicitly. Avoid a global regex that removes bracketed text or numbers indiscriminately. Tables and complex equations need supported rendering or an explicit source fallback, not silently corrupted prose.

Regression coverage should include DeepSeek, a math-heavy article, legitimate lists, nested list paragraphs, reference-only sections and malformed HTML. Confirm readable lead text and no chrome/duplicate content in both excerpt and full reader. Existing saved snapshots need a versioned, opt-in refreshed-copy path; preserve notes and explicitly reconcile changed reading positions.

Sources: [official REST API](https://www.mediawiki.org/wiki/API:REST_API/Reference#Get_page_with_HTML), [jsoup DOM selection](https://jsoup.org/), [MediaWiki DOM specification](https://www.mediawiki.org/wiki/Specs/HTML). The TextExtracts endpoint is not a drop-in cure: its [documented caveats](https://www.mediawiki.org/wiki/Extension:TextExtracts#Caveats) include retained citations, list/newline problems and missing formula images.

## Implemented repair

`WikipediaBody.text` removes article chrome before extracting native text, keeps separate prose/list blocks, removes citation markers without stripping legitimate bracketed prose, and keeps one TeX/image-alt formula representation. MediaWikiClient uses it for new excerpts and full articles. The original revision/license attribution remains. Tables are omitted from the text reader; the original source remains available.

Four focused parser tests cover chrome/citations/reference sections, nested list duplication, math alternatives and TeX braces. The actual Kotlin extractor was compiled and run against the previously captured DeepSeek document using `/tmp/WikipediaCleanProbe.java`, exit 0. It produced 10,523 characters beginning with the company’s lead paragraph rather than the AI sidebar. This is captured-revision evidence, not a guarantee for every Wikipedia page.

Saved excerpts retain their text, notes and position. “Open cleaned live copy” opens a separate freshly extracted full article; it does not migrate the old snapshot or remap note offsets. The extended Android smoke also verifies live Wikipedia retrieval, save/reopen and full-article pagination. See [release verification](../releases/v0.1.7.md).
