package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.text.TextCleaner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds tables on a page.
 *
 * 1. Ruled tables: horizontal and vertical rules (lines, thin rectangles, stroked rectangle
 *    edges, cell fills) that intersect form a grid; text fragments are assigned to cells.
 * 2. Rule-delimited tables ("booktabs"): parallel horizontal rules enclosing rows whose text
 *    splits into aligned columns.
 * 3. Unruled tables: three or more consecutive rows of short, column-aligned text pieces.
 */
class TableDetector(private val lineBuilder: LineBuilder) {

    private data class Seg(val horizontal: Boolean, val pos: Float, val start: Float, val end: Float)

    /** Ruled and rule-delimited tables, built directly from the raw fragments. */
    fun detectRuled(page: RawPage): List<TableRegion> {
        val segs = collectSegments(page)
        if (segs.size < 3) return emptyList()
        val tables = mutableListOf<TableRegion>()
        val used = HashSet<Seg>()
        for (component in components(segs)) {
            val hs = component.filter { it.horizontal }
            val vs = component.filter { !it.horizontal }
            if (hs.size < 2 || vs.size < 2) continue
            val xs = cluster(vs.map { it.pos }, 3f)
            val ys = cluster(hs.map { it.pos }, 2f)
            if (ys.size < 2 || xs.size < 2) continue
            val bounds = Rect(xs.first(), ys.first(), xs.last(), ys.last())
            if (bounds.width < 40f || bounds.height < 16f) continue
            if (bounds.area > page.width * page.height * 0.92f) continue // page border
            val frags = fragmentsIn(page, bounds)
            var columns = xs
            var rowEdges = ys
            if (ys.size == 2) {
                // No rule between rows. A plain box (worked-example callout, shaded note) is not a
                // table; neither is a frame around running text. Only keep it when the text splits
                // into table-like rows.
                if (xs.size == 2) continue
                val rows = rowsOf(frags)
                if (rows.size < 2 || !plausibleRuleDelimited(rows, bounds)) continue
                rowEdges = listOf(bounds.top) + rowBoundaries(rows) + listOf(bounds.bottom)
            } else if (xs.size == 2) {
                // Only an outer box with rules between its rows: infer the columns from the text,
                // otherwise it's a frame. Prose has gaps between words, not columns.
                if (proseInside(frags, bounds) || framesProse(frags, bounds)) continue
                columns = inferColumns(frags, bounds) ?: continue
            }
            if ((columns.size - 1) * (rowEdges.size - 1) < 2) continue
            // A shaded first row (th background) marks a header.
            val firstRow = Rect(bounds.left, ys[0], bounds.right, ys[1])
            val shadedHeader = page.paths.any { p ->
                p.filled && !p.lightFill && p.shape == PathShape.RECTANGLE && p.bounds.height > 4f &&
                    firstRow.outset(1f).contains(p.bounds) && p.bounds.width > 8f
            } && ys.size > 2
            val table = fillGrid(frags, bounds, columns, rowEdges, ruled = true, header = shadedHeader) ?: continue
            if (looksLikeDiagram(page, table, (columns.size - 1) * (rowEdges.size - 1)) || looksLikeFormula(table) || looksLikeCircuit(table)) continue
            tables += table
            used += component
        }
        tables += detectRuleDelimited(page, segs.filter { it.horizontal && it !in used }, tables)
        return tables
    }

    private fun detectRuleDelimited(page: RawPage, hs: List<Seg>, existing: List<TableRegion>): List<TableRegion> {
        val long = hs.filter { it.end - it.start >= page.width * 0.25f }
            .filter { s -> existing.none { it.bounds.contains(s.start, s.pos) } }
            .sortedBy { it.pos }
        if (long.size < 2) return emptyList()
        val out = mutableListOf<TableRegion>()
        var i = 0
        while (i < long.size - 1) {
            // Group rules with matching extents.
            val group = mutableListOf(long[i])
            var j = i + 1
            while (j < long.size && abs(long[j].start - long[i].start) < 8f && abs(long[j].end - long[i].end) < 8f &&
                long[j].pos - group.last().pos < 260f
            ) {
                group += long[j]
                j++
            }
            if (group.size >= 2) {
                val bounds = Rect(group.first().start, group.first().pos, group.first().end, group.last().pos)
                val frags = fragmentsIn(page, bounds)
                val rows = rowsOf(frags)
                if (rows.size >= 2 && bounds.height > 20f && existing.none { it.bounds.intersects(bounds) } &&
                    plausibleRuleDelimited(rows, bounds)
                ) {
                    val columns = inferColumns(frags, bounds)
                    if (columns != null && columns.size >= 3) {
                        val ys = (listOf(bounds.top) + rowBoundaries(rows) + listOf(bounds.bottom)).distinct().sorted()
                        val table = fillGrid(frags, bounds, columns, ys, ruled = true, header = group.size >= 3)
                        if (table != null && !looksLikeDiagram(page, table, (columns.size - 1) * (ys.size - 1)) && !looksLikeFormula(table) &&
                            !looksLikeCircuit(table)
                        ) {
                            out += table
                            i = j
                            continue
                        }
                    }
                }
            }
            i++
        }
        return out
    }

    /** Unruled tables made of column-aligned short text pieces. Returns tables and the consumed lines. */
    fun detectAligned(lines: List<TextLine>, page: RawPage): Pair<List<TableRegion>, Set<TextLine>> {
        if (lines.size < 6) return emptyList<TableRegion>() to emptySet()
        // A table in the margin column has its rows on the same baselines as the text (or a
        // table) next to it: look for tables inside the text column and outside it separately.
        val prose = lines.filter { it.wordCount >= 6 && it.bounds.width >= page.width * 0.3f }
        if (prose.size >= 5) {
            val lefts = prose.map { it.bounds.left }.sorted()
            val rights = prose.map { it.bounds.right }.sorted()
            val colLeft = lefts[lefts.size / 10]
            val colRight = rights[rights.size * 9 / 10]
            val outside = lines.filter { it.bounds.left >= colRight + 2f || it.bounds.right <= colLeft - 2f }
            if (outside.isNotEmpty() && colRight - colLeft < page.width * 0.75f) {
                val left = outside.filter { it.bounds.right <= colLeft - 2f }
                val right = outside.filter { it.bounds.left >= colRight + 2f }
                val inside = lines.filter { it !in outside }
                val tables = mutableListOf<TableRegion>()
                val consumed = HashSet<TextLine>()
                for (zone in listOf(inside, left, right)) {
                    val (t, c) = detectAlignedIn(zone, page)
                    tables += t
                    consumed += c
                }
                return tables to consumed
            }
        }
        // Two columns of text (exercises, review questions): their option rows are not one table.
        val gutter = TextColumns.gutter(lines, page.width, page.height)
        if (gutter != null) {
            val tables = mutableListOf<TableRegion>()
            val consumed = HashSet<TextLine>()
            val left = lines.filter { it.bounds.right <= gutter + 2f }
            val right = lines.filter { it.bounds.left >= gutter - 2f }
            for (zone in listOf(left, right, lines.filter { it !in left && it !in right })) {
                val (t, c) = detectAlignedIn(zone, page)
                tables += t
                consumed += c
            }
            return tables to consumed
        }
        return detectAlignedIn(lines, page)
    }

    private fun detectAlignedIn(lines: List<TextLine>, page: RawPage): Pair<List<TableRegion>, Set<TextLine>> {
        if (lines.size < 6) return emptyList<TableRegion>() to emptySet()
        data class Piece(val left: Float, val right: Float, val text: String, val bold: Boolean)
        // Note: not called "lines", which would resolve to the function parameter inside the class.
        data class Row(val members: List<TextLine>, val pieces: List<Piece>) {
            val top get() = members.minOf { it.bounds.top }
            val bottom get() = members.maxOf { it.bounds.bottom }
            val size get() = members.maxOf { it.fontSize }
        }

        val rows = mutableListOf<Row>()
        for (group in groupByBaseline(lines)) {
            val pieces = mutableListOf<Piece>()
            for (l in group.sortedBy { it.bounds.left }) {
                if (l.chunks.size > 1) {
                    val text = LineBuilder.scriptText(l)
                    for (c in l.chunks) {
                        val t = text.substring(c.textStart.coerceIn(0, text.length), c.textEnd.coerceIn(0, text.length)).trim()
                        if (t.isNotEmpty()) pieces += Piece(c.left, c.right, t, l.isBold)
                    }
                } else {
                    pieces += Piece(l.bounds.left, l.bounds.right, LineBuilder.scriptText(l), l.isBold)
                }
            }
            rows += Row(group, pieces)
        }

        val tables = mutableListOf<TableRegion>()
        val consumed = HashSet<TextLine>()
        // Running headers/footers ("Publisher   34   phone") sit in the top and bottom margins.
        fun inMargin(top: Float, bottom: Float) = bottom < page.height * 0.07f || top > page.height * 0.92f
        var i = 0
        while (i < rows.size) {
            if (rows[i].pieces.size < 2 || inMargin(rows[i].top, rows[i].bottom)) {
                i++
                continue
            }
            var j = i + 1
            while (j < rows.size && rows[j].pieces.size >= 2 && !inMargin(rows[j].top, rows[j].bottom) &&
                rows[j].top - rows[j - 1].bottom < rows[j - 1].size * 2.2f &&
                !ruleBetween(page, rows[j - 1].bottom, rows[j].top)
            ) j++
            val run = rows.subList(i, j)
            if (run.size >= 3 && !onDrawing(page, Rect.bounding(run.flatMap { r -> r.members.map { it.bounds } })!!)) {
                val lefts = cluster(run.flatMap { r -> r.pieces.map { it.left } }, 6f)
                val support = lefts.associateWith { x -> run.count { r -> r.pieces.any { abs(it.left - x) <= 6f } } }
                val columns = support.filter { it.value * 2 >= run.size }.keys.sorted()
                val avgWidth = run.flatMap { it.pieces }.map { it.right - it.left }.average().toFloat()
                val avgWords = run.flatMap { it.pieces }.map { p -> p.text.split(' ').size }.average()
                val shortCells = avgWidth < page.width * 0.3f && avgWords <= 4.5
                // Prose columns are justified: most pieces of a column end at the same x.
                val justifiedColumns = columns.count { x ->
                    val pieces = run.mapNotNull { r -> r.pieces.firstOrNull { abs(it.left - x) <= 6f } }
                    val maxRight = pieces.maxOfOrNull { it.right } ?: 0f
                    pieces.size >= 3 && pieces.count { maxRight - it.right < 3f } * 10 >= pieces.size * 6 &&
                        pieces.map { it.text.split(' ').size }.average() > 3
                }
                // Hanging list markers ("1.", "•") in a narrow first column are lists, not tables;
                // so are numbered exercises ("11.1 | The average power absorbed by…").
                val firstColumnMarkers = run.all { r ->
                    val first = r.pieces.first()
                    Patterns.listMarker.containsMatchIn(first.text + " ") && first.text.length <= 4
                } || run.count { r ->
                    r.pieces.size >= 2 && EXERCISE_NUMBER.matches(r.pieces[0].text) && r.pieces[1].text.split(' ').size >= 4
                } * 10 >= run.size * 3
                // Real tables have white channels between columns: no piece crosses into the
                // next column. Justified prose split at wide word gaps fails this.
                // A bold header row may centre its labels over the columns.
                val boldHeader = run.first().pieces.all { it.bold } && run.drop(1).any { r -> r.pieces.any { !it.bold } }
                val crossing = run.drop(if (boldHeader) 1 else 0).any { r ->
                    r.pieces.any { p ->
                        val col = columns.indexOfLast { it <= p.left + 6f }
                        col >= 0 && col + 1 < columns.size && p.right > columns[col + 1] - 2f
                    }
                }
                // A printed contents page without leaders: section numbers, titles and page numbers.
                fun titledPage(r: Row) = r.pieces.indices.any { k ->
                    (PAGE_ONLY.matches(r.pieces[k].text) && k > 0 && r.pieces[k - 1].text.any { it.isLetter() }) ||
                        TITLE_WITH_PAGE.matches(r.pieces[k].text)
                }
                val numbered = run.count { r -> r.pieces.any { SECTION_NUMBER.matches(it.text) || NUMBERED_TITLE.matches(it.text) } }
                if (numbered * 10 >= run.size * 4 && run.count { titledPage(it) } * 10 >= run.size * 5) {
                    i = j
                    continue
                }
                if (columns.size >= 2 && justifiedColumns < columns.size && !firstColumnMarkers && !crossing &&
                    (shortCells || (columns.size >= 3 && avgWords <= 6))
                ) {
                    // The bold header row above the rule under it.
                    val above = rows.getOrNull(i - 1)
                    val run = if (!boldHeader && above != null && above.pieces.size in 2..columns.size &&
                        above.pieces.all { it.bold } && run.first().top - above.bottom < above.size * 2.5f &&
                        above.pieces.all { p -> p.right - p.left < page.width * 0.3f } &&
                        above.pieces.map { p -> columns.indexOfLast { it <= (p.left + p.right) / 2f } }.let { it.distinct().size == it.size && it.all { c -> c >= 0 } }
                    ) listOf(above) + run else run
                    val boldHeader = boldHeader || run.size > j - i
                    val bounds = Rect.bounding(run.flatMap { r -> r.members.map { it.bounds } })!!
                    val grid = run.mapIndexed { k, r ->
                        val cells = MutableList(columns.size) { "" }
                        for (p in r.pieces) {
                            val col = if (k == 0 && boldHeader) {
                                columns.indexOfLast { it <= (p.left + p.right) / 2f }.coerceAtLeast(0)
                            } else {
                                columns.indexOfLast { it <= p.left + 6f }.coerceAtLeast(0)
                            }
                            cells[col] = (cells[col] + " " + TextCleaner.finish(p.text)).trim()
                        }
                        cells
                    }
                    val header = if (boldHeader) 1 else 0
                    tables += TableRegion(bounds, grid, header, ruled = false)
                    run.forEach { consumed += it.members }
                    i = j
                    continue
                }
            }
            i = j
        }
        return tables to consumed
    }

    // ------------------------------------------------------------------------------------

    /** A long horizontal rule between y positions [top] and [bottom] (e.g. above a page footer). */
    private fun ruleBetween(page: RawPage, top: Float, bottom: Float): Boolean = page.paths.any { p ->
        p.shape == PathShape.HORIZONTAL_LINE && p.bounds.width > page.width * 0.3f &&
            p.bounds.centerY > top && p.bounds.centerY < bottom
    }

    /** Real (non-thin) curved or diagonal shapes overlapping [r]: circles, arrowheads, rounded boxes. */
    private fun drawingShapes(page: RawPage, r: Rect): Int = page.paths.count { p ->
        val b = p.bounds
        b.intersects(r) && (
            (p.shape == PathShape.COMPLEX && min(b.width, b.height) > 4f && b.area < r.area * 4f) ||
                // circuit elements drawn as small solid bars (resistors) and arrowheads
                (p.filled && !p.lightFill && p.shape == PathShape.RECTANGLE && min(b.width, b.height) in 3f..14f &&
                    max(b.width, b.height) in 12f..50f && max(b.width, b.height) >= min(b.width, b.height) * 2f) ||
                (p.filled && !p.lightFill && p.shape == PathShape.COMPLEX && p.segments <= 4 && b.width in 2f..9f && b.height in 2f..9f)
            )
    }

    /** Text sitting on boxes and circles is diagram labelling, not an unruled table. */
    private fun onDrawing(page: RawPage, r: Rect): Boolean = drawingShapes(page, r.outset(4f)) >= 2

    /**
     * A circuit's wires form a grid too; its "cells" hold component values and polarity signs
     * ("2 Ω", "60 V", "+ vₒ −"), never words.
     */
    private fun looksLikeCircuit(table: TableRegion): Boolean {
        val cells = table.rows.flatten().map { it.trim() }.filter { it.isNotEmpty() }
        if (cells.isEmpty()) return false
        val words = cells.any { c -> c.split(' ').any { w -> w.count { it.isLetter() } >= 4 && w.none { it.isDigit() } } }
        val values = cells.count { c -> COMPONENT_VALUE.containsMatchIn(c) || c.all { it in "+−-–± " } || COMPONENT_NAME.matches(c) }
        return !words && values * 3 >= cells.size
    }

    /** Fraction bars of a displayed formula ("v₁ = R₁/(R₁ + R₂) v") also make a small grid. */
    private fun looksLikeFormula(table: TableRegion): Boolean {
        val cells = table.rows.flatten().filter { it.isNotBlank() }
        val text = cells.joinToString(" ")
        return table.rows.size <= 4 && table.columnCount <= 4 && text.length <= 80 &&
            cells.any { c -> c.any { it in "=≈≤≥" } } && cells.none { c -> c.split(' ').count { w -> w.length >= 4 && w.all { it.isLetter() } } >= 2 }
    }

    /**
     * Flow charts made of boxes joined by arrows form a "grid" of rules too, but most of its
     * cells are empty (the gaps between boxes) and it contains curved shapes.
     */
    private fun looksLikeDiagram(page: RawPage, table: TableRegion, cells: Int): Boolean {
        if (cells <= 0) return false
        val filled = table.rows.sumOf { row -> row.count { it.isNotBlank() } }
        val ratio = filled.toFloat() / cells
        val shapes = drawingShapes(page, table.bounds.outset(8f))
        return ratio < 0.2f || (ratio < 0.4f && shapes >= 1) || (ratio < 0.6f && shapes >= 3) || (ratio < 0.9f && shapes >= 4)
    }

    /**
     * Rules above and below a heading, a callout box or a horizontal divider also come in
     * matching pairs; only accept rows that look like table rows: uniform text size, mostly
     * split into columns, and no running prose.
     */
    private fun plausibleRuleDelimited(rows: List<List<TextFragment>>, bounds: Rect): Boolean {
        val sizes = rows.flatten().map { it.fontSize }.sorted()
        if (sizes.isEmpty()) return false
        val median = sizes[sizes.size / 2]
        if (sizes.last() > median * 1.2f) return false
        var split = 0
        for ((k, row) in rows.withIndex()) {
            val sorted = row.sortedBy { it.left }
            val pieces = 1 + sorted.zipWithNext().count { (a, b) -> b.left - a.right > median }
            if (pieces >= 2) {
                split++
                continue
            }
            // A sentence or question on its own line is prose, not a table row.
            val text = lineBuilder.assembleText(row).trim()
            val words = 1 + sorted.zipWithNext().count { (a, b) -> b.left - a.right > median * 0.15f }
            val wide = row.maxOf { it.right } - row.minOf { it.left } > bounds.width * 0.55f
            if (words >= (if (k == 0) 8 else 6) || (words >= 4 && Patterns.endsSentence(text)) || (k > 0 && wide)) return false
        }
        return split * 10 >= rows.size * 6
    }

    /**
     * Running text inside a box: at least two rows where words run on, with only word spaces
     * between them, across most of the box's width.
     */
    private fun proseInside(frags: List<TextFragment>, bounds: Rect): Boolean {
        val rows = rowsOf(frags)
        if (rows.size < 3) return false
        var prose = 0
        for (row in rows) {
            val sorted = row.sortedBy { it.left }
            val em = sorted.map { it.fontSize }.sorted()[sorted.size / 2]
            var start = sorted.first().left
            var right = sorted.first().right
            var longest = 0f
            for (f in sorted.drop(1)) {
                if (f.left - right > em * 1.2f) {
                    longest = max(longest, right - start)
                    start = f.left
                }
                right = max(right, f.right)
            }
            longest = max(longest, right - start)
            if (longest >= bounds.width * 0.6f) prose++
        }
        return prose >= 2
    }

    private fun collectSegments(page: RawPage): List<Seg> {
        val raw = mutableListOf<Seg>()
        val pageArea = page.width * page.height
        for (p in page.paths) {
            val b = p.bounds
            when (p.shape) {
                PathShape.HORIZONTAL_LINE -> if (b.width >= 12f) raw += Seg(true, b.centerY, b.left, b.right)
                PathShape.VERTICAL_LINE -> if (b.height >= 8f) raw += Seg(false, b.centerX, b.top, b.bottom)
                PathShape.RECTANGLE -> {
                    val edges = (p.stroked || (p.filled && !p.lightFill)) && b.area < pageArea * 0.5f
                    if (edges && b.width >= 12f && b.height >= 6f) {
                        raw += Seg(true, b.top, b.left, b.right)
                        raw += Seg(true, b.bottom, b.left, b.right)
                        raw += Seg(false, b.left, b.top, b.bottom)
                        raw += Seg(false, b.right, b.top, b.bottom)
                    }
                }
                PathShape.COMPLEX -> Unit
            }
        }
        return mergeCollinear(raw)
    }

    private fun mergeCollinear(segs: List<Seg>): List<Seg> {
        val out = mutableListOf<Seg>()
        for (horizontal in listOf(true, false)) {
            val group = segs.filter { it.horizontal == horizontal }.sortedWith(compareBy({ it.pos }, { it.start }))
            val merged = mutableListOf<Seg>()
            for (s in group) {
                val idx = merged.indexOfLast { abs(it.pos - s.pos) <= 1.5f && s.start <= it.end + 3f && s.end >= it.start - 3f }
                if (idx >= 0) {
                    val m = merged[idx]
                    merged[idx] = Seg(horizontal, (m.pos + s.pos) / 2f, min(m.start, s.start), max(m.end, s.end))
                } else {
                    merged += s
                }
            }
            out += merged
        }
        return out
    }

    private fun components(segs: List<Seg>): List<List<Seg>> {
        val parent = IntArray(segs.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val n = parent[c]; parent[c] = r; c = n
            }
            return r
        }
        val tol = 3f
        for (a in segs.indices) for (b in a + 1 until segs.size) {
            val s = segs[a]
            val t = segs[b]
            if (s.horizontal == t.horizontal) continue
            val h = if (s.horizontal) s else t
            val v = if (s.horizontal) t else s
            if (v.pos >= h.start - tol && v.pos <= h.end + tol && h.pos >= v.start - tol && h.pos <= v.end + tol) {
                parent[find(a)] = find(b)
            }
        }
        return segs.indices.groupBy { find(it) }.values.map { idx -> idx.map { segs[it] } }
    }

    private fun cluster(values: List<Float>, tol: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val out = mutableListOf<MutableList<Float>>()
        for (v in sorted) {
            val last = out.lastOrNull()
            if (last != null && v - last.last() <= tol) last += v else out += mutableListOf(v)
        }
        return out.map { it.average().toFloat() }
    }

    private fun fragmentsIn(page: RawPage, bounds: Rect): List<TextFragment> =
        page.fragments.filter { bounds.outset(1f).contains((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }

    private fun rowsOf(frags: List<TextFragment>): List<List<TextFragment>> {
        val solid = frags.filter { !it.isSpace }.sortedBy { it.baseline }
        val rows = mutableListOf<MutableList<TextFragment>>()
        for (f in solid) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.first().baseline - f.baseline) <= f.fontSize * 0.3f) last += f else rows += mutableListOf(f)
        }
        return rows
    }

    private fun rowBoundaries(rows: List<List<TextFragment>>): List<Float> =
        rows.zipWithNext { a, b -> (a.maxOf { it.bottom } + b.minOf { it.top }) / 2f }

    /**
     * Whether a frame without inner rules surrounds running text: a line of prose (many words,
     * no wide gaps, spanning most of the frame) or a sentence. Worked examples, definitions and
     * notes are printed this way; reading them as a table scrambles their text into columns.
     */
    private fun framesProse(frags: List<TextFragment>, bounds: Rect): Boolean {
        val rows = rowsOf(frags)
        if (rows.isEmpty()) return false
        val em = rows.flatten().map { it.fontSize }.sorted().let { it[it.size / 2] }
        var prose = 0
        for (row in rows) {
            val sorted = row.sortedBy { it.left }
            val gaps = sorted.zipWithNext().count { (a, b) -> b.left - a.right > em * 1.2f }
            val width = sorted.last().right - sorted.first().left
            val text = lineBuilder.assembleText(row)
            // Word gaps (fragments carry no spaces here).
            val words = 1 + sorted.zipWithNext().count { (a, b) -> b.left - a.right > em * 0.15f }
            if (gaps == 0 && words >= 6 && width > bounds.width * 0.6f) prose++
            else if (gaps == 0 && words >= 4 && Patterns.endsSentence(text) && width > bounds.width * 0.4f) prose++
        }
        return prose >= 2 || (prose >= 1 && rows.size <= 3)
    }

    /** Column boundaries (including the outer edges) from vertical gaps in the text. */
    private fun inferColumns(frags: List<TextFragment>, bounds: Rect): List<Float>? {
        val solid = frags.filter { !it.isSpace }
        if (solid.size < 4) return null
        val em = solid.map { it.fontSize }.sorted()[solid.size / 2]
        // Rows that span the full width without internal gaps are titles or merged cells; two or
        // more of them are paragraphs, and then the short lines between them are only line ends
        // of running text, not table rows.
        val allRows = rowsOf(solid)
        fun fullWidth(row: List<TextFragment>): Boolean {
            val sorted = row.sortedBy { it.left }
            return sorted.zipWithNext().none { (a, b) -> b.left - a.right > em } && (row.maxOf { it.right } - row.minOf { it.left }) >= bounds.width * 0.8f
        }
        if (allRows.count { fullWidth(it) } >= 2) return null
        val rows = allRows.filter { !fullWidth(it) }
        if (rows.size < 2) return null
        val step = 1f
        val bins = ((bounds.width) / step).toInt() + 1
        val covered = BooleanArray(bins)
        for (row in rows) for (f in row) {
            val a = ((f.left - bounds.left) / step).toInt().coerceIn(0, bins - 1)
            val b = ((f.right - bounds.left) / step).toInt().coerceIn(0, bins - 1)
            for (k in a..b) covered[k] = true
        }
        val first = covered.indexOfFirst { it }
        val last = covered.indexOfLast { it }
        if (first < 0) return null
        val boundaries = mutableListOf(bounds.left)
        var k = first
        while (k <= last) {
            if (!covered[k]) {
                val start = k
                while (k <= last && !covered[k]) k++
                if ((k - start) * step >= em * 0.9f) boundaries += bounds.left + (start + k) / 2f * step
            } else {
                k++
            }
        }
        boundaries += bounds.right
        return if (boundaries.size >= 3) boundaries else null
    }

    private fun fillGrid(
        frags: List<TextFragment>,
        bounds: Rect,
        xs: List<Float>,
        ys: List<Float>,
        ruled: Boolean,
        header: Boolean = false,
    ): TableRegion? {
        val nRows = ys.size - 1
        val nCols = xs.size - 1
        if (nRows < 1 || nCols < 1) return null
        val buckets = Array(nRows) { Array(nCols) { mutableListOf<TextFragment>() } }
        for (f in frags) {
            val cx = (f.left + f.right) / 2f
            val cy = (f.top + f.bottom) / 2f
            val c = xs.indexOfLast { it <= cx }.coerceIn(0, nCols - 1)
            val r = ys.indexOfLast { it <= cy }.coerceIn(0, nRows - 1)
            buckets[r][c] += f
        }
        var grid = buckets.map { row -> row.map { TextCleaner.finish(lineBuilder.assembleText(it, unicodeScripts = true)).trim() } }
        val boldFirstRow = buckets.firstOrNull()?.flatMap { it }?.filter { !it.isSpace }?.let { cells ->
            cells.isNotEmpty() && cells.count { it.bold } * 10 >= cells.size * 6
        } ?: false
        // Drop empty rows/columns (artefacts of decorative rules).
        grid = grid.filter { row -> row.any { it.isNotBlank() } }
        if (grid.isEmpty()) return null
        val keepCols = (0 until nCols).filter { c -> grid.any { it[c].isNotBlank() } }
        grid = grid.map { row -> keepCols.map { row[it] } }
        val filled = grid.sumOf { row -> row.count { it.isNotBlank() } }
        if (filled < 2 || keepCols.isEmpty()) return null
        if (keepCols.size == 1 && grid.size < 3) return null
        val headerRows = if ((header || boldFirstRow) && grid.size > 1) 1 else 0
        return TableRegion(bounds, grid, headerRows, ruled)
    }

    private fun groupByBaseline(lines: List<TextLine>): List<List<TextLine>> {
        val sorted = lines.sortedBy { it.baseline }
        val out = mutableListOf<MutableList<TextLine>>()
        for (l in sorted) {
            val last = out.lastOrNull()
            if (last != null && abs(last.first().baseline - l.baseline) <= l.fontSize * 0.3f) last += l else out += mutableListOf(l)
        }
        return out
    }

    private companion object {
        val SECTION_NUMBER = Regex("^\\s*(?:(?:chapter|part)\\s+[0-9]{1,2}|[0-9]{1,2}(?:\\.[0-9]{1,2}){1,3})\\s*$", RegexOption.IGNORE_CASE)
        val NUMBERED_TITLE = Regex("^\\s*[0-9]{1,2}(?:\\.[0-9]{1,2}){1,3}\\s+.*\\p{L}.*$")
        val COMPONENT_VALUE = Regex("[0-9]\\s*(?:[kmMμnpG]?\\s*(?:Ω|V|A|F|H|W|S|Hz)|VAR|VA)\\b|Ω|^[+−-]|[+−-]$")
        /** "R₁", "Vs", "iₒ", "C2": a component or quantity name next to a drawing. */
        val COMPONENT_NAME = Regex("^[+−-]?\\s*[A-Za-zΩα-ω][₀-₉ₐ-ₜa-z0-9]{0,3}\\s*[+−-]?$")
        val EXERCISE_NUMBER = Regex("^\\s*[0-9]{1,2}\\.[0-9]{1,3}\\s*$")
        val PAGE_ONLY = Regex("^\\s*(?:[0-9]{1,4}|[ivxlc]{1,7})\\s*$")
        val TITLE_WITH_PAGE = Regex("^.*\\p{L}.*\\s[0-9]{1,4}$")
    }
}
