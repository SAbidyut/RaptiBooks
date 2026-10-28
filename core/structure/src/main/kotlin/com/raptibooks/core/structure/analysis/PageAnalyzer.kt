package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlinePicture
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.Chunk
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.layout.PictureRegion
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.VectorPath
import com.raptibooks.core.structure.text.BijoyConverter
import com.raptibooks.core.structure.text.TextQuality
import kotlin.math.max
import kotlin.math.min

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
        val (bulleted, bulletPaths) = attachGraphicBullets(lineBuilder.build(remaining, splitColumns = true), page)
        // Words whose text is garbage (Arabic read by a Latin-only OCR) are shown as printed, and
        // so are the lines of that script the OCR left as images.
        val imageLines = if (page.fromOcr) emptyList() else ForeignText.imageLines(page, bulleted)
        val (builtLines, pictures) = if (page.fromOcr) bulleted to emptyList() else numberPictures(
            ForeignText.joinCells(lineBuilder.pictureWords(bulleted, foreignPage = imageLines.isNotEmpty()) + imageLines.map { it.second }), page.index,
        )
        // Patches of the scan over such words are part of their pictures.
        val usedImages = imageLines.map { it.first }.toSet() + ForeignText.patches(page.images, builtLines)
        // A table of symbols and formulas printed on a shaded panel is shown as printed, and so is
        // a grid of words of the other script.
        val panels = if (page.fromOcr) emptyList() else formulaPanels(page, builtLines) + ForeignText.grids(builtLines)
        val allLines = if (panels.isEmpty()) builtLines else builtLines.filter { l -> panels.none { l in it.second } }
        val bodySize = bodySizeHint ?: typicalSize(allLines, page.width)
        // Display equations first: their stacked rows would otherwise look like table rows.
        val equations = if (page.fromOcr) null else equationDetector.detect(page, allLines, bodySize, ruled.map { it.bounds })
        val textLines = if (equations == null) allLines else allLines.filter { it !in equations.absorbed }
        val (aligned, consumed) = tableDetector.detectAligned(textLines, page)
        var lines = textLines.filter { it !in consumed }

        // Table captions ("Table 2: ...") directly above or below a table.
        val captionLines = HashSet<TextLine>()
        val captionOf = HashMap<TableRegion, List<TextLine>>()
        var tables = (ruled + aligned).map { t ->
            val cap = lines.firstOrNull { l ->
                l !in captionLines && Patterns.caption.containsMatchIn(l.text) &&
                    l.bounds.horizontalOverlap(t.bounds) > 0f &&
                    (t.bounds.top - l.bounds.bottom in -2f..l.fontSize * 2.5f ||
                        l.bounds.top - t.bounds.bottom in -2f..l.fontSize * 2.5f)
            }
            // "TABLE 1.1" on its own line with the description under it, right above the table.
            val label = if (cap != null) null else lines.filter { l ->
                l !in captionLines && Patterns.tableCaption.containsMatchIn(l.text) && l.wordCount <= 3 &&
                    l.bounds.horizontalOverlap(t.bounds) > 0f && t.bounds.top - l.bounds.bottom in -2f..l.fontSize * 6f
            }.maxByOrNull { it.bounds.bottom }
            val description = label?.let { lb ->
                lines.filter { l ->
                    l !== lb && l !in captionLines && l.bounds.top >= lb.bounds.bottom - 2f && l.bounds.bottom <= t.bounds.top + 2f &&
                        l.bounds.horizontalOverlap(lb.bounds.union(t.bounds)) > 0f
                }.sortedBy { it.bounds.top }
            }?.takeIf { it.size <= 3 }
            if (cap != null) {
                captionLines += cap
                TableRegion(t.bounds, t.rows, t.headerRows, t.ruled, cap.text).also { captionOf[it] = listOf(cap) }
            } else if (label != null && description != null) {
                captionLines += label
                captionLines += description
                TableRegion(t.bounds, t.rows, t.headerRows, t.ruled, (listOf(label) + description).joinToString(" ") { it.text })
            } else {
                t
            }
        }
        // A "table" captioned as a figure is a drawing whose lines form a grid (a circuit's
        // wires): its text and caption go back to the page for the figure detector.
        val drawings = tables.filter { t ->
            val c = t.caption
            c != null && Patterns.caption.containsMatchIn(c) && !Patterns.tableCaption.containsMatchIn(c)
        }
        if (drawings.isNotEmpty()) {
            tables = tables - drawings.toSet()
            for (t in drawings) {
                captionOf[t]?.let { captionLines -= it.toSet() }
                val area = t.bounds.outset(1f)
                lines = lines + if (t.ruled) {
                    lineBuilder.build(page.fragments.filter { f -> area.contains((f.left + f.right) / 2f, (f.top + f.bottom) / 2f) })
                } else {
                    consumed.filter { it.bounds.coveredBy(area) >= 0.5f }
                }
            }
        }
        lines = lines.filter { it !in captionLines }

        val equationPaths = equations?.paths ?: emptySet()
        val figurePage = if (bulletPaths.isEmpty() && equationPaths.isEmpty() && panels.isEmpty() && usedImages.isEmpty()) page else RawPage(
            page.index, page.width, page.height, page.fragments,
            page.images.filter { img -> img !in usedImages && panels.none { it.first.bounds.outset(1f).contains(img.bounds) } },
            page.paths.filter { p -> p !in bulletPaths && p !in equationPaths && panels.none { it.first.bounds.outset(1f).contains(p.bounds) } },
            page.rotatedText, page.fromOcr, page.ocrPictures,
        )
        var equationRegions = equations?.equations ?: emptyList()
        val detected = figureDetector.detect(figurePage, lines, tables, equationRegions.map { it.bounds }, bodySize)
        val figures = if (panels.isEmpty()) detected else FigureDetector.Result(detected.figures + panels.map { it.first }, detected.absorbed)
        // (a formula that is part of a picture, the display of a row of calculator keys)
        equationRegions = equationRegions.filter { e -> figures.figures.none { it.bounds.outset(1f).contains(e.bounds) } }
        val pieces = lines.filter { it !in figures.absorbed }
        // Diagrams split into pieces are put back together above their captions.
        val whole = FigureConsolidator().consolidate(figurePage, pieces, figures.figures + equationRegions, tables, bodySize, allLines)
        val body = pieces.filter { it !in whole.absorbed }
        val chars = allLines.sumOf { TextQuality.letterCount(it.text) } +
            ruled.sumOf { t -> t.rows.sumOf { r -> r.sumOf { TextQuality.letterCount(it) } } }
        // A table with words shown as printed in its cells is shown as printed, whole, and so is
        // one with a column of such words left as junk.
        val pictureTables = if (pictures.isEmpty()) emptyList() else whole.tables.filter { t ->
            t.rows.any { r -> r.any { InlinePicture.MARKER in it } } || ForeignText.unreadable(t)
        }
        val outFigures = whole.figures + pictureTables.map { FigureRegion(it.bounds, FigureKind.VECTOR, it.caption) }
        val outTables = whole.tables - pictureTables.toSet()
        return PageLayout(
            page.index, page.width, page.height, body, outFigures, outTables, page.fromOcr, chars,
            if (page.fromOcr) emptyList() else BoxDetector().detect(page, allLines, whole.tables),
            // (only those still in the text: not inside a figure or a table)
            if (pictures.isEmpty()) pictures else body.flatMap { l -> l.styles.filter { it.picture }.map { InlineStyle.pictureId(it.flags) } }.toSet()
                .let { used -> pictures.filter { it.id in used } },
        )
    }

    /**
     * Gives the words shown as printed their numbers in the book (page × 1024 + k), carried by
     * the lines' picture styles, and keeps each picture clear of the lines above and below (the
     * lines keep where their pictures are, no longer by number).
     */
    private fun numberPictures(lines: List<TextLine>, pageIndex: Int): Pair<List<TextLine>, List<PictureRegion>> {
        if (lines.none { it.pictures.isNotEmpty() }) return lines to emptyList()
        val regions = ArrayList<PictureRegion>()
        val out = lines.map { l ->
            if (l.pictures.isEmpty()) return@map l
            val ids = IntArray(l.pictures.size)
            val places = ArrayList<Rect>(l.pictures.size)
            for ((k, r) in l.pictures.withIndex()) {
                var top = r.top
                var bottom = r.bottom
                for (o in lines) {
                    if (o === l || o.bounds.horizontalOverlap(r) <= 0f) continue
                    if (o.baseline < l.baseline - l.fontSize * 0.5f) top = max(top, min(o.baseline + o.fontSize * 0.25f, l.baseline - l.fontSize * 0.7f))
                    if (o.baseline > l.baseline + l.fontSize * 0.5f) bottom = min(bottom, max(o.baseline - o.fontSize * 0.75f, l.baseline + l.fontSize * 0.2f))
                }
                val id = pageIndex * 1024 + regions.size.coerceAtMost(1023)
                ids[k] = id
                regions += PictureRegion(id, Rect(r.left - 0.5f, top, r.right + 0.5f, bottom), l.fontSize)
                places += regions.last().bounds
            }
            val styles = l.styles.map { st -> if (st.picture) InlineStyle(st.start, st.end, InlineStyle.pictureFlags(ids[InlineStyle.pictureId(st.flags)])) else st }
            TextLine(l.text, styles, l.bounds, l.baseline, l.fontSize, l.boldRatio, l.italicRatio, l.fontName, l.chunks, places)
        }
        return out to regions
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
/**
 * Shaded panels holding a table of symbols and formulas ("TABLE 1.3  Symbol | Meaning" with
 * fractions in its rows): text extraction cannot keep such a table together, so it becomes a
 * picture captioned with the table's label. Returns each panel with the lines it takes in.
 */
internal fun formulaPanels(page: RawPage, lines: List<TextLine>): List<Pair<FigureRegion, Set<TextLine>>> {
    val out = mutableListOf<Pair<FigureRegion, Set<TextLine>>>()
    for (p in page.paths) {
        val b = p.bounds
        if (p.shape != PathShape.RECTANGLE || !p.filled || p.lightFill || b.width < page.width * 0.25f || b.height < 40f) continue
        if (b.area > page.width * page.height * 0.6f) continue
        val inside = lines.filter { it.bounds.coveredBy(b.outset(1f)) >= 0.6f }
        if (inside.size < 4) continue
        val size = inside.map { it.fontSize }.sorted()[inside.size / 2]
        // labelled as a table right above the panel (or in its first row)
        val label = lines.filter { l ->
            Patterns.tableCaption.containsMatchIn(l.text) && l.wordCount <= 3 && l.bounds.horizontalOverlap(b) > 0f &&
                l.bounds.bottom in b.top - size * 3f..b.top + size * 2f
        }.minByOrNull { it.bounds.top } ?: continue
        val description = lines.filter { l ->
            l !== label && l !in inside && l.bounds.top >= label.bounds.bottom - 1f && l.bounds.bottom <= b.top + 1f && l.bounds.horizontalOverlap(b) > 0f
        }.sortedBy { it.bounds.top }.takeIf { it.size <= 2 } ?: continue
        // rows of short entries, with fractions among them
        if (inside.count { it.wordCount >= 10 } > inside.size / 4) continue
        val bars = page.paths.count { q ->
            q.shape == PathShape.HORIZONTAL_LINE && q.bounds.width in 3f..b.width * 0.3f && b.contains(q.bounds)
        }
        if (bars == 0) continue
        val caption = (listOf(label) + description).joinToString(" ") { it.text.trim() }
        out += FigureRegion(b, FigureKind.VECTOR, caption, textSize = size) to (inside + label + description).toSet()
    }
    return out
}

internal fun attachGraphicBullets(lines: List<TextLine>, page: RawPage): Pair<List<TextLine>, Set<VectorPath>> {
    // (not a shape inside a small frame: the shading of a calculator key)
    val keys = page.paths.filter { it.bounds.width in 10f..70f && it.bounds.height in 8f..26f }
    val candidates = page.paths.filter { p ->
        val b = p.bounds
        p.filled && !p.lightFill && b.width in 1.5f..9f && b.height in 1.5f..9f &&
            b.width / b.height in 0.5f..2f && (p.shape == PathShape.COMPLEX || p.shape == PathShape.RECTANGLE) &&
            keys.none { k -> k !== p && k.bounds.outset(0.5f).contains(b) }
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
