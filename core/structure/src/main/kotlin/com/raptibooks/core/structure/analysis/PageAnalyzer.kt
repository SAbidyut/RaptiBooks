package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.Chunk
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.VectorPath
import com.raptibooks.core.structure.text.BijoyConverter
import com.raptibooks.core.structure.text.TextQuality

/**
 * First pass, one page at a time: raw fragments/graphics -> lines, tables and figures.
 * Cross-page decisions (headers, headings, paragraphs) happen later in [PageStructurer].
 */
class PageAnalyzer(
    /** Body text size of the document, when known from a first scan of all pages. */
    private val bodySizeHint: Float? = null,
) {
    private val lineBuilder = LineBuilder()
    private val tableDetector = TableDetector(lineBuilder)
    private val figureDetector = FigureDetector()
    private val equationDetector = EquationDetector()

    fun analyze(input: RawPage): PageLayout {
        // Text set at 1-2 pt is invisible (bookmark targets, hidden running-header markers);
        // it must not be read as content.
        val page = if (input.fromOcr || input.fragments.none { it.fontSize < MIN_VISIBLE_SIZE }) input else RawPage(
            input.index, input.width, input.height, input.fragments.filter { it.fontSize >= MIN_VISIBLE_SIZE },
            input.images, input.paths, input.rotatedText, input.fromOcr, input.ocrPictures,
        )
        val spaces = page.fragments.count { it.isSpace }
        val solid = page.fragments.size - spaces
        lineBuilder.explicitSpacesHint = if (solid < 20) null else spaces * 30 >= solid
        val ruled = if (page.fromOcr) emptyList() else tableDetector.detectRuled(page)
        val remaining = page.fragments.filter { f ->
            val cx = (f.left + f.right) / 2f
            val cy = (f.top + f.bottom) / 2f
            ruled.none { it.bounds.outset(1f).contains(cx, cy) }
        }
        val (allLines, bulletPaths) = attachGraphicBullets(lineBuilder.build(remaining), page)
        val bodySize = bodySizeHint ?: typicalSize(allLines, page.width)
        // Display equations first: their stacked rows would otherwise look like table rows.
        val equations = if (page.fromOcr) null else equationDetector.detect(page, allLines, bodySize, ruled.map { it.bounds })
        val textLines = if (equations == null) allLines else allLines.filter { it !in equations.absorbed }
        val (aligned, consumed) = tableDetector.detectAligned(textLines, page)
        var lines = textLines.filter { it !in consumed }

        // Table captions ("Table 2: ...") directly above or below a table.
        val captionLines = HashSet<TextLine>()
        val tables = (ruled + aligned).map { t ->
            val cap = lines.firstOrNull { l ->
                l !in captionLines && Patterns.caption.containsMatchIn(l.text) &&
                    l.bounds.horizontalOverlap(t.bounds) > 0f &&
                    (t.bounds.top - l.bounds.bottom in -2f..l.fontSize * 2.5f ||
                        l.bounds.top - t.bounds.bottom in -2f..l.fontSize * 2.5f)
            }
            if (cap != null) {
                captionLines += cap
                TableRegion(t.bounds, t.rows, t.headerRows, t.ruled, cap.text)
            } else {
                t
            }
        }
        lines = lines.filter { it !in captionLines }

        val equationPaths = equations?.paths ?: emptySet()
        val figurePage = if (bulletPaths.isEmpty() && equationPaths.isEmpty()) page else RawPage(
            page.index, page.width, page.height, page.fragments, page.images,
            page.paths.filter { it !in bulletPaths && it !in equationPaths }, page.rotatedText, page.fromOcr, page.ocrPictures,
        )
        val equationRegions = equations?.equations ?: emptyList()
        val figures = figureDetector.detect(figurePage, lines, tables, equationRegions.map { it.bounds }, bodySize)
        val pieces = lines.filter { it !in figures.absorbed }
        // Diagrams split into pieces are put back together above their captions.
        val whole = FigureConsolidator().consolidate(figurePage, pieces, figures.figures + equationRegions, tables, bodySize, allLines)
        val body = pieces.filter { it !in whole.absorbed }
        val chars = allLines.sumOf { TextQuality.letterCount(it.text) } +
            ruled.sumOf { t -> t.rows.sumOf { r -> r.sumOf { TextQuality.letterCount(it) } } }
        return PageLayout(
            page.index, page.width, page.height, body, whole.figures, whole.tables, page.fromOcr, chars,
            if (page.fromOcr) emptyList() else BoxDetector().detect(page, allLines, whole.tables),
        )
    }

    companion object {
        /** Smaller text is not meant to be read (hidden markers). */
        const val MIN_VISIBLE_SIZE = 2.5f

        /**
         * Body text size of a page: the font size carrying the most characters in wide lines
         * (paragraph text), so that many small labels or big formula symbols don't count.
         */
        fun typicalSize(lines: List<TextLine>, pageWidth: Float): Float {
            if (lines.isEmpty()) return 10f
            val wide = lines.filter { it.bounds.width > pageWidth * 0.4f }
            val bySize = (wide.ifEmpty { lines }).groupBy { (it.fontSize * 2f).toInt() }
                .maxByOrNull { (_, ls) -> ls.sumOf { it.text.length } }!!
            return bySize.value.map { it.fontSize }.average().toFloat()
        }
    }
}

/**
 * Browsers and some layout tools draw list bullets as small filled shapes instead of a "•"
 * glyph. A small, roughly square filled path just left of a line, vertically inside it, is
 * turned into a "• " prefix so the list structure survives.
 */
internal fun attachGraphicBullets(lines: List<TextLine>, page: RawPage): Pair<List<TextLine>, Set<VectorPath>> {
    val candidates = page.paths.filter { p ->
        val b = p.bounds
        p.filled && !p.lightFill && b.width in 1.5f..9f && b.height in 1.5f..9f &&
            b.width / b.height in 0.5f..2f && (p.shape == PathShape.COMPLEX || p.shape == PathShape.RECTANGLE)
    }
    if (candidates.isEmpty()) return lines to emptySet()
    val used = HashSet<VectorPath>()
    val out = lines.map { line ->
        val em = line.fontSize
        val bullet = candidates.firstOrNull { p ->
            p !in used && line.bounds.left - p.bounds.right in 0.5f..em * 2.2f &&
                p.bounds.centerY in line.bounds.top..line.bounds.bottom && p.bounds.height <= em * 0.8f
        }
        if (bullet == null) {
            line
        } else {
            used += bullet
            TextLine(
                "• " + line.text,
                line.styles.map { InlineStyle(it.start + 2, it.end + 2, it.flags) },
                line.bounds.union(bullet.bounds), line.baseline, line.fontSize, line.boldRatio, line.italicRatio,
                line.fontName, line.chunks.map { Chunk(it.left, it.right, it.textStart + 2, it.textEnd + 2) },
            )
        }
    }
    return out to used
}

/** Decides whether a page's text layer is unusable and the page should be OCR'd. */
object OcrDecision {
    enum class Reason { NONE, SCANNED, BROKEN_ENCODING, UNMAPPED_GLYPHS, OUTLINED_TEXT }

    /**
     * @param glyphsDrawn number of glyphs the PDF drew.
     * @param glyphsMapped how many of them had a Unicode mapping (unmapped ones produce no text).
     */
    fun evaluate(page: RawPage, glyphsDrawn: Int, glyphsMapped: Int): Reason {
        val solid = page.fragments.filter { !it.isSpace }
        val unicodeFrags = solid.filter { !BijoyConverter.isBijoyFont(it.fontName) }
        val text = buildString { unicodeFrags.forEach { append(it.text) } }
        val letters = TextQuality.letterCount(text) + solid.size - unicodeFrags.size
        val pageArea = page.width * page.height
        val imageArea = coverage(page.images.map { it.bounds }, page.width, page.height)
        val complexPaths = page.paths.count { it.shape == PathShape.COMPLEX }
        val pathArea = coverage(page.paths.filter { it.shape == PathShape.COMPLEX }.map { it.bounds }, page.width, page.height)
        return when {
            letters < 25 && imageArea > pageArea * 0.35f -> Reason.SCANNED
            letters < 25 && complexPaths > 150 && pathArea > pageArea * 0.25f -> Reason.OUTLINED_TEXT
            glyphsDrawn > 60 && glyphsMapped < glyphsDrawn * 0.4f -> Reason.UNMAPPED_GLYPHS
            letters >= 25 && TextQuality.garbageRatio(text) > 0.3f -> Reason.BROKEN_ENCODING
            else -> Reason.NONE
        }
    }

    /** Approximate union area of rectangles, clipped to the page (coarse grid). */
    fun coverage(rects: List<Rect>, width: Float, height: Float): Float {
        if (rects.isEmpty() || width <= 0f || height <= 0f) return 0f
        val cols = 60
        val rows = 80
        val cw = width / cols
        val ch = height / rows
        val grid = BooleanArray(cols * rows)
        for (r in rects) {
            val c0 = (r.left / cw).toInt().coerceIn(0, cols - 1)
            val c1 = ((r.right - 0.01f) / cw).toInt().coerceIn(0, cols - 1)
            val r0 = (r.top / ch).toInt().coerceIn(0, rows - 1)
            val r1 = ((r.bottom - 0.01f) / ch).toInt().coerceIn(0, rows - 1)
            if (r.right <= 0f || r.bottom <= 0f || r.left >= width || r.top >= height) continue
            for (y in r0..r1) for (x in c0..c1) grid[y * cols + x] = true
        }
        return grid.count { it } * cw * ch
    }
}
