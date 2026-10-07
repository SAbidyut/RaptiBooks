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
  - figures: raster images and **vector diagrams** (clustered paths) rendered from the page,
    with captions ("Figure 3", "চিত্র ২");
  - multi-column reading order;
  - **Bangla**: visual→logical glyph order repair (ি ে ৈ, reph), ActualText clusters,
    Bijoy/SutonnyMJ (ANSI) → Unicode conversion;
  - **OCR** for scanned pages and broken text layers: Tesseract 5 with bundled `ben` + `eng`
    models, fully offline; text-as-outlines pages are detected too;
  - table of contents from the PDF outline, or from detected headings.
- **Reader**: Light / Sepia / Dark / Black themes; bundled Tiro Bangla (serif) and Hind
  Siliguri (sans) fonts that cover Bangla and Latin, plus system fonts; text size, line
  spacing, margins, justification; tap screen edges to turn pages; contents, bookmarks,
  in-book search with highlights; zoomable figures; horizontally scrollable tables; resume
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

1. **Pass 1 – per page (constant memory)**: extract glyphs/images/paths → decide whether the
   page needs OCR (scan, unmapped glyphs, broken encoding, outlined text) → OCR if needed →
   build lines, detect tables and figures → render figures/table snapshots → write the page
   analysis to a cache file and update document statistics.
2. **Profile**: body font size, heading size levels, repeated header/footer lines, whether the
   Bangla text layer is in visual order, language.
3. **Pass 2**: per page, remove running headers/footers, order columns, classify headings /
   lists / paragraphs, then the reflow engine streams blocks into SQLite; finally the TOC.

## Building

### With the Android SDK (Android Studio or command line)

Requires JDK 21 (Robolectric runs the Android 15 framework in the unit tests, which needs Java 21).

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
```

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
- Equations are not reconstructed (they stay as text or as part of a figure).
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

App code: see repository. Fonts: Tiro Bangla, Hind Siliguri (SIL Open Font License,
`core/ui/src/main/assets/licenses`). PdfBox-Android (Apache 2.0), Tesseract and tessdata
(Apache 2.0), JavaCPP presets (Apache 2.0 / GPL with classpath exception), AndroidX
(Apache 2.0).
