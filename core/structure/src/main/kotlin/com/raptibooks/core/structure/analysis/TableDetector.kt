package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
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
            if (xs.size == 2) {
                // Only an outer box: infer the columns from the text, otherwise it's a frame.
                columns = inferColumns(frags, bounds) ?: continue
            }
            if ((columns.size - 1) * (ys.size - 1) < 2) continue
            // A shaded first row (th background) marks a header.
            val firstRow = Rect(bounds.left, ys[0], bounds.right, ys[1])
            val shadedHeader = page.paths.any { p ->
                p.filled && !p.lightFill && p.shape == PathShape.RECTANGLE && p.bounds.height > 4f &&
                    firstRow.outset(1f).contains(p.bounds) && p.bounds.width > 8f
            } && ys.size > 2
            val table = fillGrid(frags, bounds, columns, ys, ruled = true, header = shadedHeader) ?: continue
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
                if (rows.size >= 2 && bounds.height > 20f) {
                    val columns = inferColumns(frags, bounds)
                    if (columns != null && columns.size >= 3) {
                        val ys = (listOf(bounds.top) + rowBoundaries(rows) + listOf(bounds.bottom)).distinct().sorted()
                        val table = fillGrid(frags, bounds, columns, ys, ruled = true, header = group.size >= 3)
                        if (table != null) {
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
        data class Piece(val left: Float, val right: Float, val text: String, val bold: Boolean)
        data class Row(val lines: List<TextLine>, val pieces: List<Piece>) {
            val top get() = lines.minOf { it.bounds.top }
            val bottom get() = lines.maxOf { it.bounds.bottom }
            val size get() = lines.maxOf { it.fontSize }
        }

        val rows = mutableListOf<Row>()
        for (group in groupByBaseline(lines)) {
            val pieces = mutableListOf<Piece>()
            for (l in group.sortedBy { it.bounds.left }) {
                if (l.chunks.size > 1) {
                    for (c in l.chunks) {
                        val t = l.text.substring(c.textStart.coerceIn(0, l.text.length), c.textEnd.coerceIn(0, l.text.length)).trim()
                        if (t.isNotEmpty()) pieces += Piece(c.left, c.right, t, l.isBold)
                    }
                } else {
                    pieces += Piece(l.bounds.left, l.bounds.right, l.text, l.isBold)
                }
            }
            rows += Row(group, pieces)
        }

        val tables = mutableListOf<TableRegion>()
        val consumed = HashSet<TextLine>()
        var i = 0
        while (i < rows.size) {
            if (rows[i].pieces.size < 2) {
                i++
                continue
            }
            var j = i + 1
            while (j < rows.size && rows[j].pieces.size >= 2 &&
                rows[j].top - rows[j - 1].bottom < rows[j - 1].size * 2.2f
            ) j++
            val run = rows.subList(i, j)
            if (run.size >= 3) {
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
                // Hanging list markers ("1.", "•") in a narrow first column are lists, not tables.
                val firstColumnMarkers = run.all { r ->
                    val first = r.pieces.first()
                    Patterns.listMarker.containsMatchIn(first.text + " ") && first.text.length <= 4
                }
                // Real tables have white channels between columns: no piece crosses into the
                // next column. Justified prose split at wide word gaps fails this.
                val crossing = run.any { r ->
                    r.pieces.any { p ->
                        val col = columns.indexOfLast { it <= p.left + 6f }
                        col >= 0 && col + 1 < columns.size && p.right > columns[col + 1] - 2f
                    }
                }
                if (columns.size >= 2 && justifiedColumns < columns.size && !firstColumnMarkers && !crossing &&
                    (shortCells || (columns.size >= 3 && avgWords <= 6))
                ) {
                    val bounds = Rect.bounding(run.flatMap { r -> r.lines.map { it.bounds } })!!
                    val grid = run.map { r ->
                        val cells = MutableList(columns.size) { "" }
                        for (p in r.pieces) {
                            val col = columns.indexOfLast { it <= p.left + 6f }.coerceAtLeast(0)
                            cells[col] = (cells[col] + " " + p.text).trim()
                        }
                        cells
                    }
                    val header = if (run.first().pieces.all { it.bold } && run.drop(1).any { r -> r.pieces.any { !it.bold } }) 1 else 0
                    tables += TableRegion(bounds, grid, header, ruled = false)
                    run.forEach { consumed += it.lines }
                    i = j
                    continue
                }
            }
            i = j
        }
        return tables to consumed
    }

    // ------------------------------------------------------------------------------------

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

    /** Column boundaries (including the outer edges) from vertical gaps in the text. */
    private fun inferColumns(frags: List<TextFragment>, bounds: Rect): List<Float>? {
        val solid = frags.filter { !it.isSpace }
        if (solid.size < 4) return null
        val em = solid.map { it.fontSize }.sorted()[solid.size / 2]
        // Ignore rows that span the full width without internal gaps (titles, merged cells).
        val rows = rowsOf(solid).filter { row ->
            val sorted = row.sortedBy { it.left }
            sorted.zipWithNext().any { (a, b) -> b.left - a.right > em } || (row.maxOf { it.right } - row.minOf { it.left }) < bounds.width * 0.8f
        }
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
        var grid = buckets.map { row -> row.map { lineBuilder.assembleText(it) } }
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
}
