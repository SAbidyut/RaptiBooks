# RaptiBooks

**PDF → Analyze → Extract → Reformat → Mobile Book**

RaptiBooks is a native Android app (Kotlin + Jetpack Compose) that turns any PDF into a
book that is comfortable to read on a phone. It does not show the PDF pages: it rebuilds the
content — chapters, headings, paragraphs, lists, tables and figures — and reflows it to the
screen. Bangla and English are both first-class, and everything runs offline.

> যেকোনো পিডিএফ দিন — RaptiBooks অফলাইনে সেটি বিশ্লেষণ করে অধ্যায়, শিরোনাম, অনুচ্ছেদ,
> তালিকা, সারণি ও চিত্রসহ মোবাইলে পড়ার উপযোগী বই হিসেবে সাজিয়ে দেয়।

## Features

- **Library**: import with the system file picker, "Open with" or "Share"; covers, live
  conversion progress, reading progress, retry / cancel / convert again (optionally with OCR).
- **Conversion** (background, WorkManager foreground job with a progress notification,
  resumable after the app is killed):
  - text with positions and font styles (bold, italic incl. synthetic, superscript);
  - running headers/footers and page numbers removed (repetition across pages);
  - headings from font-size levels, bold lines, "Chapter/অধ্যায়" and "2.3"-style numbering;
  - paragraphs re-joined across lines, columns and page breaks, with de-hyphenation;
  - lists (bullets, including vector-drawn ones; 1. / a) / ক) / ১। enumerators);
  - tables: ruled grids, rule-delimited tables and column-aligned text → real tables, plus a
    snapshot of the printed table;
  - figures: raster images and **vector diagrams** (clustered paths) rendered from the page
    at high resolution and cropped tight to the printed content, with captions ("Figure 3",
    "চিত্র ২") — also captions printed in the margin beside the figure, small labelled
    diagrams (a circuit element with its i, +, v, −) and side-by-side panels (a), (b);
    running text is never swallowed into a figure;
  - **display equations**: stacked fractions, integral/sum limits, boxed and numbered formulas
    become one picture of the formula (an ink mask drawn in the theme's text colour, at the
    size of the body text, scrolling sideways when wider than the screen) with the equation
    number beside it; stacked formulas are never mistaken for tables;
  - **worked examples**: the steps of a calculation printed as a centred column ("I = V/R",
    "= 230/460", "= 0.5 A") become one picture of the whole derivation; the question,
    "প্রদত্ত/Given", "নির্ণেয়", "সমাধান" and "উত্তর/Answer" lines stay separate paragraphs; a
    one-line formula stays text, centred, and is never mistaken for a heading;
  - **callout boxes** (worked examples, definitions, notes, warnings printed in a tinted
    panel) are kept as boxes and shown as cards in the box's own accent colour, also when a
    box runs over a page break; a frame around running text is never read as a table;
  - **diagrams split into pieces** (thin-line circuits whose wires look like a table, loose
    labels) are put back together above their caption, with the drawing's own title;
  - **math symbols without Unicode mapping** (the "=", "×", "+" of Mathematical-Pi-style fonts,
    which otherwise vanish from the text) are identified from their printed shape; "Δ"
    printed with a "¢" code is repaired;
  - printed **contents pages** with or without dot leaders (also as tables, also two columns
    of "1.2  Systems of Units 4" with wrapped titles) become tappable contents entries, and
    the reader's Contents panel follows the book's own contents when it can be tied to the
    pages;
  - **textbook layouts** (e.g. *Fundamentals of Electric Circuits*): section numbers printed
    apart from their titles ("1.2 | Systems of Units") and chapter openers ("c h a p t e r",
    a huge "1" and the title) become proper headings; bold exercise numbers ("2.13") hang like
    list markers; equation numbers stay with their formula; circuit diagrams (wires, resistor
    bars, arrowheads) are never read as tables; figures of a two-column exercise page stay in
    their own column; tables in the margin column, centred bold header cells, exponents such
    as 10⁻¹² and two-line captions ("TABLE 1.2" + description) are kept together;
  - printer's marks outside the crop box (file-name slug, crop marks) are ignored, and shapes
    are measured inside their clipping area;
  - Latin words set without space glyphs in tightly justified lines keep their spaces;
  - hidden 1-pt text (bookmark markers) is ignored; "ভাগ করে…"/"অধ্যায় ১৬-এর…" inside a
    sentence is not a chapter title; answer options split by a page break are joined;
  - in a book with a real text layer, a page that is one big picture (cover, poster) is kept
    as that picture instead of being OCR'd;
  - margin columns (side notes, figures, tables, captions) are placed right after the
    paragraph printed beside them, also when the gutter is only one character wide; a
    paragraph continues across the page break past them;
  - multi-column reading order;
  - **Bangla**: visual→logical glyph order repair (ি ে ৈ, reph), ActualText clusters,
    Bijoy/SutonnyMJ (ANSI) → Unicode conversion;
  - **OCR** for scanned pages and broken text layers: Tesseract 5 with bundled `ben` + `eng`
    models, fully offline; text-as-outlines pages are detected too;
  - table of contents from the PDF outline, else from the printed contents page, else from
    detected headings;
  - tables keep superscripts and subscripts (10¹⁸, 10⁻³); wide tables scroll sideways with a
    hint, narrow ones fill the width.
- **Reader**: Light / Sepia / Dark / Black themes; bundled Tiro Bangla (serif) and Hind
  Siliguri (sans) fonts that cover Bangla and Latin, plus system fonts; text size, line
  spacing, margins, justification; contents, bookmarks,
  in-book search with highlights; zoomable figures; **marking pen** (tap the right edge: red / blue / yellow / green and an eraser; draw along
  words or loop around a passage — in text, tables and answer options — to underline it in that colour; on pictures a
  finger draws freehand lines that stay where they were drawn; the menu can be dragged; all saved with the book); **notes**: pick the note button in the pen menu, tap
  anywhere in the book and leave a voice recording or a written note there, shown as a pin in the free strip beside the text with an arrow to the place, so the text stays readable; tap the pin to open it again;
  status and navigation bars always stay visible, so swiping home never scrolls the page; horizontally scrollable tables; resume
  position; keep-screen-on; **original PDF page view** with pinch zoom and a jump back to the
  reflowed text at the same page.
- **Large PDFs / low RAM**: PdfBox spills to disk, pages are processed one at a time, page
  analyses are cached on disk, images are never decoded during analysis, the reader loads
  blocks in chunks and images are downsampled to the display size.
- English and Bangla UI.

## Architecture

```
app ──► feature:library ─┐
    └─► feature:reader ──┼─► core:ui ──► core:model
                         └─► core:data ─┬─► core:database ──► core:model
                                        ├─► core:pdf  ──┐
                                        ├─► core:ocr  ──┼─► core:structure ──► core:model
                                        └─► core:reflow ┘
```

| Module | Kind | Responsibility |
|---|---|---|
| `core:model` | Kotlin/JVM | Domain types: `Book`, `ContentBlock` (heading, paragraph, list item, image, table…), reader settings |
| `core:structure` | Kotlin/JVM | Document structure analysis: line building, tables, figures, reading order, headers/footers, headings, paragraphs, Bangla text repair, Bijoy conversion, OCR decision |
| `core:reflow` | Kotlin/JVM | Reflow engine: streams pages into a flat block list, joins paragraphs across pages, captions, TOC |
| `core:pdf` | Android | PDF parsing with PdfBox-Android (glyphs, image placements, vector paths, outline, metadata) and page/region rendering (PdfRenderer, PdfBox fallback) |
| `core:ocr` | Android | Tesseract 5 via JavaCPP, bundled tessdata (`ben`, `eng`) |
| `core:database` | Android | SQLite schema and DAOs (books, blocks, TOC, bookmarks, search) with reactive queries |
| `core:data` | Android | Repositories, the two-pass conversion pipeline, WorkManager worker, settings, DI container |
| `core:ui` | Compose | Theme, reader colour themes, bundled fonts, icons, memory-bounded image loader |
| `feature:library` | Compose | Library screen |
| `feature:reader` | Compose | Reader, contents/bookmarks, settings, search, original PDF view |
| `app` | Android app | Activity, navigation, Application |

The structure and reflow engines are plain Kotlin and unit-tested on the JVM; the Android
side only extracts raw page data and renders.

### Conversion pipeline

Conversion favours quality over speed: it reads the book twice and renders every picture
and formula at high resolution, so a long book takes a few minutes.

0. **Pass 0 – survey**: the body text size of the book is learned from up to 60 pages spread
   over the book, so every page is judged against the same reference.
1. **Pass 1 – per page (constant memory)**: extract glyphs/images/paths → decide whether the
   page needs OCR (scan, unmapped glyphs, broken encoding, outlined text) → OCR if needed →
   identify unmapped math symbols from their rendering → build lines, detect display
   equations, tables and figures → render figures, formulas and table snapshots (cropped to
   the ink) → write the page analysis to a cache file and update document statistics.
2. **Profile**: body font size, heading size levels, repeated header/footer lines, whether the
   Bangla text layer is in visual order, language.
3. **Pass 2**: per page, remove running headers/footers, order columns, classify headings /
   lists / paragraphs, then the reflow engine streams blocks into SQLite; finally the TOC.

## Building

### With the Android SDK (Android Studio or command line)

Requires JDK 17 or newer (CI uses JDK 21).

```
./gradlew test             # engine unit tests + Robolectric tests
./gradlew assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

CI (`.github/workflows/android.yml`) runs both on every push and uploads the APK.

### Without the Android SDK (offline builder)

`tools/offline-build/rbuild.py` builds the same project where Google's SDK/Maven hosts are
unreachable. It reads the Gradle files (modules, namespaces, dependencies, versions) and uses
official binaries from Maven Central and NuGet (Robolectric's `android-all`, Google's
`aapt2`/D8/manifest merger/apksig from Microsoft's .NET Android pack, AndroidX AARs from the
Xamarin.AndroidX packages):

```
python3 tools/offline-build/rbuild.py apk       # build/offline/RaptiBooks.apk
python3 tools/offline-build/rbuild.py test      # JVM unit tests (structure, reflow)
python3 tools/offline-build/rbuild.py robotest  # Robolectric tests (DB, pipeline, UI)
python3 tools/pdf-harness/harness.py some.pdf   # print the reflowed book of a PDF (desktop)
HARNESS_IMAGES=/tmp/crops python3 tools/pdf-harness/harness.py some.pdf  # also save figure/formula crops
```

The test books in `app/src/test/resources/pdf` are printed from HTML; `tools/test-pdfs` holds
the sources of the math ones (`make_pdfs.py` regenerates them with Chromium).

### Signing

Both builds sign with `keystore/raptibooks-sideload.p12` (password `raptibooks`) so APKs from
either build install over each other. **For Play Store distribution, create your own private
upload key** and change `signingConfigs` in `app/build.gradle.kts`.

## Tests

- `core:structure` / `core:reflow`: Bangla repair, Bijoy conversion, line building, tables,
  figures, columns, serialization, and a multi-page book (headers, chapters, page-split
  paragraphs, lists, TOC).
- `app` (Robolectric): DAO round trips and search; the real conversion pipeline on English,
  Bangla and two-column PDFs; the library and reader screens in all themes.

## Known limitations

- Layout analysis is heuristic: unusual layouts (magazines, complex forms, math-heavy pages,
  tables with merged or borderless multi-line cells) may not reflow perfectly. The original
  page view and "Show as printed" for tables are there for those cases.
- Equations are shown as pictures of the printed formula (not as editable MathML); inline
  math inside a sentence stays text. Unmapped symbols that are not among the recognised
  shapes (= + − × ÷ ± · → ← < > ≤ ≥ ≡ Δ) are left out of the text.
- OCR quality depends on scan quality; OCR is slow on low-end phones (seconds per page).
  The fast `tessdata` models are bundled to keep the app small.
- Bijoy (SutonnyMJ) conversion covers the common character set; rare conjuncts may be missing
  ("Convert again with OCR" is the fallback).
- Password-protected PDFs (user password) are not supported yet; owner-password PDFs work.
- The bundled Tesseract build (JavaCPP 1.5.9) is the last one with 32-bit ARM support; its
  native libraries are not 16 KB page aligned, so on 16 KB-page devices OCR may be unavailable
  (the rest of the app works).
- No baseline profiles are shipped, so the very first launches are less smooth than later ones.

## Licenses

App code: see repository. Fonts: Tiro Bangla, Hind Siliguri (its Regular and SemiBold carry
the Bangla digit ১ of Noto Sans Bengali, as their own glyph is malformed) (SIL Open Font License,
`core/ui/src/main/assets/licenses`). PdfBox-Android (Apache 2.0), Tesseract and tessdata
(Apache 2.0), JavaCPP presets (Apache 2.0 / GPL with classpath exception), AndroidX
(Apache 2.0).
