package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Puts diagrams back together using their captions.
 *
 * A drawing made of thin lines and many labels (a circuit, a wiring plan) is easily split by the
 * figure detector into several small pictures, a "table" (its wires form a grid) and loose
 * labels. A printed caption ("চিত্র ৪.৫: …", "Figure 2.1") marks where a figure ends: everything
 * stacked directly above it that is not running text belongs to that figure, up to the drawing's
 * own title. The pieces become one figure, rendered from the page as printed.
 */
class FigureConsolidator {

    class Result(val figures: List<FigureRegion>, val tables: List<TableRegion>, val absorbed: Set<TextLine>)

    private sealed interface Piece {
        val bounds: Rect
    }

    private class FigurePiece(val figure: FigureRegion) : Piece {
        override val bounds get() = figure.bounds
    }

    private class TablePiece(val table: TableRegion) : Piece {
        override val bounds get() = table.bounds
    }

    private class LinePiece(val line: TextLine) : Piece {
        override val bounds get() = line.bounds
    }

    private class PathPiece(override val bounds: Rect) : Piece

    fun consolidate(
        page: RawPage,
        lines: List<TextLine>,
        figures: List<FigureRegion>,
        tables: List<TableRegion>,
        bodySize: Float,
        /** All lines of the page, captions included (to find where a figure's caption sits). */
        allLines: List<TextLine> = lines,
    ): Result {
        val anchors = mutableListOf<Pair<Rect, FigureRegion?>>()
        for (f in figures) {
            val caption = f.caption
            if (f.kind == FigureKind.EQUATION || caption == null || !isFigureCaption(caption)) continue
            // The figure reaches down to its caption: a piece left between them is part of it.
            val captionTop = allLines.filter { it.bounds.top >= f.bounds.bottom - 2f && caption.startsWith(it.text.take(12)) }
                .minOfOrNull { it.bounds.top }
            val start = if (captionTop != null && captionTop > f.bounds.bottom + 2f) f.bounds.union(Rect(f.bounds.left, f.bounds.top, f.bounds.right, captionTop - 1f)) else f.bounds
            anchors += start to f
        }
        val captionLines = lines.filter { isFigureCaption(it.text) && it.fontSize <= bodySize * 1.15f }
        for (c in captionLines) {
            // A caption not yet attached: the figure ends right above it.
            if (figures.none { it.caption != null && it.caption.startsWith(c.text.take(12)) }) {
                anchors += Rect(c.bounds.left, c.bounds.top, c.bounds.right, c.bounds.top) to null
            }
        }
        if (anchors.isEmpty()) return Result(figures, tables, emptySet())

        val textLeft = lines.filter { isProse(it, bodySize, page.width) }.minOfOrNull { it.bounds.left } ?: page.width * 0.1f
        val textRight = lines.filter { isProse(it, bodySize, page.width) }.maxOfOrNull { it.bounds.right } ?: page.width * 0.9f
        val columnWidth = max(textRight - textLeft, page.width * 0.5f)

        val outFigures = figures.toMutableList()
        val outTables = tables.toMutableList()
        val absorbed = HashSet<TextLine>()
        val gutter = TextColumns.gutter(lines, page.width, page.height)
        for ((start, anchorFigure) in anchors.sortedBy { it.first.top }) {
            if (anchorFigure != null && anchorFigure !in outFigures) continue
            // In a two-column layout a figure stays in the column of its caption.
            val side = when {
                gutter == null -> 0
                start.right <= gutter + 2f -> -1
                start.left >= gutter - 2f -> 1
                else -> 0
            }
            fun inColumn(r: Rect) = when (side) {
                -1 -> r.right <= gutter!! + 2f
                1 -> r.left >= gutter!! - 2f
                else -> true
            }
            val pieces = mutableListOf<Piece>()
            outFigures.filter { it !== anchorFigure }.forEach { pieces += FigurePiece(it) }
            outTables.forEach { pieces += TablePiece(it) }
            // (running headers and page numbers in the top and bottom margins are not part of a figure)
            lines.filter { it !in absorbed && it !in captionLines && it.bounds.top > page.height * 0.07f && it.bounds.bottom < page.height * 0.93f }
                .forEach { pieces += LinePiece(it) }
            for (p in page.paths) {
                val b = p.bounds
                if (b.width > columnWidth * 0.92f || b.bottom > start.top + 2f) continue // box borders, page rules
                if (p.shape == PathShape.RECTANGLE && p.lightFill && lines.any { isProse(it, bodySize, page.width) && b.contains(it.bounds, 1f) }) continue
                pieces += PathPiece(b)
            }
            for (img in page.images) if (img.bounds.bottom <= start.top + 2f && img.bounds.width < page.width * 0.95f) pieces += PathPiece(img.bounds)
            pieces.retainAll { inColumn(it.bounds) }

            var union: Rect? = if (anchorFigure != null) start else null
            var top = start.top
            val taken = mutableListOf<Piece>()
            var title: TextLine? = null
            val gapLimit = bodySize * 2.2f
            // Pieces overlapping the figure belong to it too.
            fun growOverlapping() {
                var grew = true
                while (grew) {
                    grew = false
                    val u = union ?: return
                    for (p in pieces) {
                        if (p in taken || !p.bounds.intersects(u, 1f)) continue
                        if (p is LinePiece && blocks(p.line, bodySize, page.width)) continue
                        // A table printed inside the drawing (a comparison strip in the artwork).
                        if (p is TablePiece && !diagramLike(p.table) && !(p.table.caption == null && p.bounds.coveredBy(u) >= 0.85f)) continue
                        if (p is FigurePiece && p.figure.caption != null && isFigureCaption(p.figure.caption)) continue
                        taken += p
                        union = u.union(p.bounds)
                        top = min(top, p.bounds.top)
                        grew = true
                        break
                    }
                }
            }
            growOverlapping()
            while (true) {
                val xRange = union ?: Rect(textLeft, top, textRight, top)
                // The nearest thing above that is not yet part of the figure.
                val next = pieces.filter { it !in taken && !(it is FigurePiece && it.figure.kind == FigureKind.EQUATION) &&
                    it.bounds.bottom <= top + 2f && it.bounds.bottom >= top - gapLimit &&
                    it.bounds.right > xRange.left - 4f && it.bounds.left < xRange.right + 4f
                }.maxByOrNull { it.bounds.bottom } ?: break
                if (next is LinePiece) {
                    val l = next.line
                    val u = union
                    if (u != null && isTitle(l, u, bodySize)) {
                        title = l
                        taken += next
                        union = u.union(l.bounds)
                        break
                    }
                    if (blocks(l, bodySize, page.width)) break
                }
                if (next is TablePiece && !diagramLike(next.table)) break
                if (next is FigurePiece && next.figure.caption != null && isFigureCaption(next.figure.caption)) break
                taken += next
                union = union?.union(next.bounds) ?: next.bounds
                top = min(top, next.bounds.top)
                growOverlapping()
            }
            val found = union
            if (title == null && found != null) {
                // The drawing's title, a little further up than the gap between its parts.
                pieces.filterIsInstance<LinePiece>().filter { it !in taken && it.line.bounds.bottom <= top + 2f && isTitle(it.line, found, bodySize) }
                    .maxByOrNull { it.line.bounds.bottom }
                    ?.takeIf { cand -> lines.none { o -> o !== cand.line && o.bounds.top >= cand.line.bounds.bottom - 1f && o.bounds.bottom <= top + 1f &&
                        o.bounds.horizontalOverlap(found) > 0f && taken.none { (it as? LinePiece)?.line === o } } }
                    ?.let {
                        title = it.line
                        taken += it
                        union = found.union(it.line.bounds)
                    }
            }
            val u = union ?: continue
            val visuals = taken.count { it is FigurePiece || it is TablePiece || it is PathPiece }
            val parts = taken.count { it !is PathPiece } + (if (anchorFigure != null) 1 else 0)
            if (anchorFigure != null && taken.isEmpty()) continue
            if (anchorFigure == null && (visuals == 0 || parts < 2 || taken.none { it is FigurePiece })) continue
            if (u.height < bodySize * 2f) continue
            val mergedFigures = taken.filterIsInstance<FigurePiece>().map { it.figure }
            val kinds = (mergedFigures + listOfNotNull(anchorFigure)).map { it.kind }.filter { it != FigureKind.EQUATION }.toSet()
            val kind = when {
                kinds.isEmpty() -> FigureKind.VECTOR
                kinds.size == 1 -> kinds.first()
                else -> FigureKind.MIXED
            }
            val caption = anchorFigure?.caption ?: captionText(lines, start)
            val labelLines = taken.filterIsInstance<LinePiece>().map { it.line }
            val textSize = labelLines.filter { it !== title }.map { it.fontSize }.sorted()
                .let { if (it.isEmpty()) anchorFigure?.textSize ?: 0f else it[it.size / 2] }
            // The space down to the caption counted only to collect pieces; the figure is what was taken.
            val bounds = if (anchorFigure != null) (Rect.bounding(taken.map { it.bounds }) ?: anchorFigure.bounds).union(anchorFigure.bounds) else u
            outFigures.removeAll(mergedFigures.toSet())
            anchorFigure?.let { outFigures.remove(it) }
            outTables.removeAll(taken.filterIsInstance<TablePiece>().map { it.table }.toSet())
            absorbed += labelLines
            if (anchorFigure == null) {
                lines.firstOrNull { isFigureCaption(it.text) && abs(it.bounds.top - start.top) < 1f }?.let { absorbed += captionBlock(it, lines) }
            }
            outFigures += FigureRegion(bounds.clamp(page.width, page.height), kind, caption, textSize = textSize)
        }
        return Result(outFigures, outTables, absorbed)
    }

    private fun captionText(lines: List<TextLine>, start: Rect): String? {
        val first = lines.firstOrNull { isFigureCaption(it.text) && abs(it.bounds.top - start.top) < 1f } ?: return null
        return captionBlock(first, lines).joinToString(" ") { it.text }
    }

    /** The caption line and the description lines right under it. */
    private fun captionBlock(first: TextLine, lines: List<TextLine>): List<TextLine> {
        val out = mutableListOf(first)
        var last = first
        for (l in lines.filter { it.bounds.horizontalOverlap(first.bounds) > 0f && it.bounds.top > first.bounds.top }.sortedBy { it.bounds.top }) {
            if (l.bounds.top - last.bounds.bottom > last.fontSize * 0.9f || out.size >= 4) break
            if (l.fontSize > first.fontSize + 0.6f || isFigureCaption(l.text) || Patterns.numberedHeading.containsMatchIn(l.text)) break
            out += l
            last = l
        }
        return out
    }

    private fun isFigureCaption(text: String): Boolean =
        Patterns.caption.containsMatchIn(text) && !Patterns.tableCaption.containsMatchIn(text)

    /** Running text, a section heading or another caption: the figure does not reach past it. */
    private fun blocks(l: TextLine, bodySize: Float, pageWidth: Float): Boolean =
        isProse(l, bodySize, pageWidth) || isFigureCaption(l.text) || Patterns.tableCaption.containsMatchIn(l.text) ||
            // the end of a sentence, a bold problem number ("2.7")
            (l.wordCount >= 4 && Patterns.endsSentence(l.text)) || (l.isBold && PROBLEM_NUMBER.matches(l.text)) ||
            Patterns.exampleLabel.matches(l.text) || l.fontSize > bodySize * 1.25f ||
            (Patterns.numberedHeading.containsMatchIn(l.text) && l.isBold && l.fontSize >= bodySize * 0.95f) ||
            Patterns.listMarker.containsMatchIn(l.text) && l.fontSize >= bodySize * 0.95f

    private fun isProse(l: TextLine, bodySize: Float, pageWidth: Float): Boolean =
        l.wordCount >= 5 && l.fontSize >= bodySize * 0.88f && l.bounds.width >= pageWidth * 0.25f &&
            !isFigureCaption(l.text)

    /** The drawing's own title: a short bold line centred over it, right above it. */
    private fun isTitle(l: TextLine, figure: Rect, bodySize: Float): Boolean =
        l.isBold && l.fontSize in bodySize * 0.9f..bodySize * 1.2f && l.wordCount <= 14 && !Patterns.endsSentence(l.text) &&
            figure.top - l.bounds.bottom <= bodySize * 2.8f &&
            abs(l.bounds.centerX - figure.centerX) <= max(figure.width * 0.12f, bodySize * 2f) &&
            !Patterns.numberedHeading.containsMatchIn(l.text)

    private companion object {
        val PROBLEM_NUMBER = Regex("^\\s*[0-9]{1,2}\\.[0-9]{1,3}\\s*$")
    }

    /** A "table" made of a drawing's wires: few, short cells. */
    private fun diagramLike(t: TableRegion): Boolean {
        val cells = t.rows.flatten().filter { it.isNotBlank() }
        if (t.caption != null) return false
        return cells.size <= 12 && cells.all { it.length <= 40 } && t.rows.size <= 4
    }
}
