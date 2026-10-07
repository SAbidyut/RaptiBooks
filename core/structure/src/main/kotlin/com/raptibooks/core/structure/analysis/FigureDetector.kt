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
 * Finds figures: raster images and clusters of vector graphics (diagrams, charts, drawings).
 * Text inside a figure (labels) is absorbed into it; the figure is later rendered from the
 * original page so diagrams keep their exact look. A caption line next to the figure
 * ("Figure 3…", "চিত্র ২…") is attached to it.
 */
class FigureDetector {

    class Result(val figures: List<FigureRegion>, val absorbed: Set<TextLine>)

    private class Item(var bounds: Rect, val strong: Boolean, val raster: Boolean, val complex: Boolean, val rect: Boolean)

    fun detect(
        page: RawPage,
        lines: List<TextLine>,
        tables: List<TableRegion>,
        equations: List<Rect> = emptyList(),
        bodySizeHint: Float? = null,
    ): Result {
        val pageArea = page.width * page.height
        val items = mutableListOf<Item>()
        val inTable = { r: Rect -> tables.any { it.bounds.outset(2f).contains(r) } || equations.any { it.outset(2f).contains(r) } }

        if (page.fromOcr) {
            for (r in page.ocrPictures) items += Item(r, strong = true, raster = true, complex = false, rect = false)
        }
        for (img in page.images) {
            val b = img.bounds.clamp(page.width, page.height)
            if (b.width < 14f || b.height < 14f) continue
            if (b.area > pageArea * 0.85f) continue // full-page background or scan; handled by OCR logic
            if (inTable(b)) continue
            items += Item(b, strong = true, raster = true, complex = false, rect = false)
        }
        for (p in page.paths) {
            val b = p.bounds.clamp(page.width, page.height)
            if (b.isEmpty && p.shape == PathShape.COMPLEX) continue
            if (b.area > pageArea * 0.6f) continue // page frames and backgrounds
            if (inTable(b)) continue
            when (p.shape) {
                PathShape.COMPLEX -> if (max(b.width, b.height) >= 4f) {
                    // Thin pieces (mitred box borders, rules drawn as polygons) are lines, not drawings.
                    val thin = min(b.width, b.height) <= 4f && max(b.width, b.height) >= min(b.width, b.height) * 6f
                    items += if (thin) {
                        Item(b, strong = false, raster = false, complex = false, rect = false)
                    } else {
                        Item(b, strong = p.segments >= 3 || b.width * b.height > 400f, raster = false, complex = true, rect = false)
                    }
                }
                PathShape.RECTANGLE -> if (!p.lightFill || p.stroked) {
                    items += Item(b, strong = b.width >= 8f && b.height >= 8f, raster = false, complex = false, rect = true)
                }
                PathShape.HORIZONTAL_LINE, PathShape.VERTICAL_LINE ->
                    items += Item(b, strong = false, raster = false, complex = false, rect = false)
            }
        }
        for (r in page.rotatedText) items += Item(r, strong = false, raster = false, complex = false, rect = false)
        val bodySize = bodySizeHint ?: PageAnalyzer.typicalSize(lines, page.width)
        // Banners: a thin strip of colour behind a heading ("Example 1.4") is decoration.
        items.removeAll { it.raster && isBanner(it.bounds, lines, bodySize) }
        if (items.none { it.strong }) return Result(emptyList(), emptySet())

        // Running text separates figures: nothing is merged across a paragraph line.
        val prose = lines.filter { isProse(it, bodySize, page.width) }

        // Cluster strong items, then let weak items (lines, labels) join nearby clusters.
        val clusters = mutableListOf<MutableList<Item>>()
        for (it in items.filter { it.strong }) clusters += mutableListOf(it)
        mergeClusters(clusters, slack = 14f, prose)
        val weak = items.filter { !it.strong }
        var grew = true
        while (grew) {
            grew = false
            for (w in weak) {
                if (clusters.any { c -> w in c }) continue
                val target = clusters.firstOrNull { c -> boundsOf(c).intersects(w.bounds, 6f) && !separated(boundsOf(c), w.bounds, prose) }
                    ?: continue
                target += w
                grew = true
            }
            if (grew) mergeClusters(clusters, slack = 4f, prose)
        }

        class Candidate(var bounds: Rect, val items: MutableList<Item>, val members: MutableSet<TextLine>)
        val candidates = mutableListOf<Candidate>()
        val absorbed = HashSet<TextLine>()
        for (c in clusters) {
            var bounds = boundsOf(c)
            val hasRaster = c.any { it.raster }
            val complexCount = c.count { it.complex }
            // Tiny shapes (an emoji/icon in a callout title, arrowheads) don't make a drawing.
            val drawnShapes = c.count { it.complex && it.bounds.area > 500f }
            val rectCount = c.count { it.rect }
            if (bounds.width < 6f || bounds.height < 6f) continue
            val inside = lines.filter { it.bounds.coveredBy(bounds.outset(2f)) >= 0.6f }
            val textArea = inside.sumOf { (it.bounds.area).toDouble() }.toFloat()
            if (!hasRaster) {
                // Frames, shaded boxes, highlight bars: mostly rectangles around running text.
                if (complexCount == 0 && rectCount <= 3 && (inside.size >= 3 || textArea > bounds.area * 0.25f)) continue
                // A callout box: frame, maybe an icon, and lines of running text.
                if (drawnShapes == 0 && rectCount <= 3 && inside.size >= 2 && textArea > bounds.area * 0.12f &&
                    inside.any { it.wordCount >= 6 }
                ) continue
                if (complexCount == 0 && rectCount <= 1 && c.size <= 2) continue // a lone box or rule
                // A big drawing that is mostly text paragraphs (e.g. a page border with content).
                if (inside.size > 12 && textArea > bounds.area * 0.35f) continue
                if (inside.count { it in prose } >= 2 && rectCount <= 3 && drawnShapes <= 1) continue
            } else if (inside.size > 15 && textArea > bounds.area * 0.4f) {
                continue // decorative background image behind text
            }
            // Labels around the drawing (titles above nodes, notes beside them) belong to it too,
            // otherwise they leak into the text and the picture is cropped through them.
            val members = inside.toMutableSet()
            var grown = true
            while (grown) {
                grown = false
                for (l in lines) {
                    if (l in members || l in absorbed) continue
                    if (isLabel(l, bounds, bodySize, page.width)) {
                        members += l
                        bounds = bounds.union(l.bounds)
                        grown = true
                    }
                }
            }
            absorbed += members
            candidates += Candidate(bounds, c.toMutableList(), members)
        }
        // Panels of one figure ("(a)", "(b)") printed side by side or stacked closely.
        var joined = true
        while (joined) {
            joined = false
            loop@ for (i in candidates.indices) for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                val rowOverlap = a.bounds.verticalOverlap(b.bounds) / min(a.bounds.height, b.bounds.height)
                val gap = max(a.bounds.left - b.bounds.right, b.bounds.left - a.bounds.right)
                val sideBySide = (rowOverlap >= 0.5f && gap <= bodySize * 8f) ||
                    (rowOverlap >= 0.75f && gap <= max(bodySize * 10f, page.width * 0.25f))
                val stacked = a.bounds.horizontalOverlap(b.bounds) >= min(a.bounds.width, b.bounds.width) * 0.3f &&
                    max(a.bounds.top - b.bounds.bottom, b.bounds.top - a.bounds.bottom) <= bodySize * 1.5f
                val small = min(a.bounds.area, b.bounds.area) < pageArea * 0.05f
                if ((sideBySide || (stacked && small)) && !separated(a.bounds, b.bounds, prose)) {
                    a.bounds = a.bounds.union(b.bounds)
                    a.items += b.items
                    a.members += b.members
                    candidates.removeAt(j)
                    joined = true
                    break@loop
                }
            }
        }

        val figures = mutableListOf<FigureRegion>()
        for (cand in candidates) {
            val bounds = cand.bounds
            val c = cand.items
            val hasRaster = c.any { it.raster }
            val complexCount = c.count { it.complex }
            val rectCount = c.count { it.rect }
            if (bounds.width < 30f || bounds.height < 20f) {
                absorbed -= cand.members
                continue
            }
            // Small drawings count when they are labelled (a circuit element with its i, +, v, −).
            val labels = cand.members.count { it.text.length <= 12 }
            val minArea = if (labels >= 2 && (complexCount + rectCount) >= 1) pageArea * 0.003f else pageArea * 0.012f
            if (!hasRaster && bounds.area < minArea) {
                absorbed -= cand.members
                continue
            }
            if (!hasRaster && complexCount + rectCount < 2 && c.size < 3 && labels < 2) {
                absorbed -= cand.members
                continue
            }
            val kind = when {
                page.fromOcr -> FigureKind.SCAN
                hasRaster && complexCount == 0 -> FigureKind.RASTER
                hasRaster -> FigureKind.MIXED
                else -> FigureKind.VECTOR
            }
            val textSize = cand.members.map { it.fontSize }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            figures += FigureRegion(bounds.clamp(page.width, page.height), kind, textSize = textSize)
        }

        // Merge figures that overlap after label expansion.
        val merged = mutableListOf<FigureRegion>()
        for (f in figures.sortedBy { it.bounds.top }) {
            val idx = merged.indexOfFirst { it.bounds.intersects(f.bounds) }
            if (idx >= 0) {
                val m = merged[idx]
                val kind = if (m.kind == f.kind) m.kind else FigureKind.MIXED
                val size = listOf(m.textSize, f.textSize).filter { it > 0f }.minOrNull() ?: 0f
                merged[idx] = FigureRegion(m.bounds.union(f.bounds), kind, textSize = size)
            } else {
                merged += f
            }
        }

        // Captions: below the figure first (with any notes between them), else directly above.
        val captionLines = HashSet<TextLine>()
        val withCaptions = merged.sortedBy { it.bounds.top }.map { f ->
            val free = lines.filter { it !in absorbed }
            val below = findCaptionBelow(f.bounds, free, bodySize)
            if (below != null) {
                val (notes, caption) = below
                absorbed += notes
                absorbed += caption
                captionLines += caption
                var b = f.bounds
                for (n in notes) {
                    b = b.union(n.bounds)
                    // The box a note is printed in belongs to the figure too.
                    page.paths.filter { p -> p.bounds.width <= f.bounds.width * 1.3f && p.bounds.contains(n.bounds, 2f) }
                        .forEach { p -> b = b.union(p.bounds) }
                }
                FigureRegion(b, f.kind, caption.joinToString(" ") { it.text }, textSize = f.textSize)
            } else {
                val above = findCaptionAbove(f.bounds, free)
                val beside = if (above == null) findCaptionBeside(f.bounds, free, page.width) else null
                if (above != null) {
                    absorbed += above
                    captionLines += above
                    FigureRegion(f.bounds, f.kind, above.text, textSize = f.textSize)
                } else if (beside != null) {
                    absorbed += beside
                    captionLines += beside
                    FigureRegion(f.bounds, f.kind, beside.joinToString(" ") { it.text }, textSize = f.textSize)
                } else {
                    f
                }
            }
        }
        // Text that ended up inside the final picture (a title printed on the figure's panel)
        // is part of it; otherwise it would appear twice.
        for (f in withCaptions) {
            lines.filter { it !in absorbed && it.bounds.coveredBy(f.bounds) >= 0.8f }.forEach { absorbed += it }
        }
        // Breathing room around the crop, without cutting into the text next to it.
        val others = lines.filter { it !in absorbed || it in captionLines }
        val result = withCaptions.map { f ->
            FigureRegion(pad(f.bounds, others, PAD).clamp(page.width, page.height), f.kind, f.caption, textSize = f.textSize)
        }
        return Result(result, absorbed)
    }

    private fun pad(b: Rect, others: List<TextLine>, d: Float): Rect {
        var top = b.top - d
        var bottom = b.bottom + d
        var left = b.left - d
        var right = b.right + d
        for (l in others) {
            val r = l.bounds
            if (r.horizontalOverlap(b) > 0f) {
                if (r.bottom <= b.top + 1f) top = max(top, min(b.top, r.bottom + 1f))
                if (r.top >= b.bottom - 1f) bottom = min(bottom, max(b.bottom, r.top - 1f))
            }
            if (r.verticalOverlap(b) > 0f) {
                if (r.right <= b.left + 1f) left = max(left, min(b.left, r.right + 1f))
                if (r.left >= b.right - 1f) right = min(right, max(b.right, r.left - 1f))
            }
        }
        return Rect(left, top, right, bottom)
    }

    /** Whether [l] is a label of the drawing at [fig]: inside it, or small text right next to it. */
    private fun isLabel(l: TextLine, fig: Rect, bodySize: Float, pageWidth: Float): Boolean {
        if (l.bounds.coveredBy(fig.outset(2f)) >= 0.6f) return true
        if (Patterns.caption.containsMatchIn(l.text)) return false
        val em = l.fontSize
        val hGap = max(0f, max(fig.left - l.bounds.right, l.bounds.left - fig.right))
        val vGap = max(0f, max(fig.top - l.bounds.bottom, l.bounds.top - fig.bottom))
        // Symbols and short names next to the drawing ("i", "+", "v", "(a)", "R1", "10 Ω").
        if (l.text.length <= 6 && l.wordCount <= 2) {
            val beside = l.bounds.verticalOverlap(fig) >= l.bounds.height * 0.5f && hGap <= max(em * 1.6f, 10f)
            val under = l.bounds.horizontalOverlap(fig) >= l.bounds.width * 0.5f && vGap <= em * 1.3f
            if (beside || under) return true
        }
        // A row of separate short labels (one under each photo) is printed as one line with gaps.
        val pieces = if (l.chunks.size >= 2) l.chunks.map { it.right - it.left } else listOf(l.bounds.width)
        val words = l.wordCount.toFloat() / pieces.size
        if (words > 10f || pieces.any { it > pageWidth * 0.45f }) return false
        val small = em <= bodySize * 0.92f
        val boldShort = l.isBold && em <= bodySize * 1.02f && l.wordCount <= 6
        if (!small && !boldShort) return false
        if (l.bounds.verticalOverlap(fig) >= l.bounds.height * 0.5f && hGap <= max(em * 3f, 12f)) return true
        val within = l.bounds.horizontalOverlap(fig) >= l.bounds.width * 0.5f
        val reach = if (small) max(em * 1.2f, 6f) else em * 0.6f
        return within && vGap <= reach
    }

    private companion object {
        const val PAD = 6f
    }

    private fun boundsOf(c: List<Item>): Rect = Rect.bounding(c.map { it.bounds })!!

    /** A line of running text: a figure never extends across one. */
    private fun isProse(l: TextLine, bodySize: Float, pageWidth: Float): Boolean =
        l.wordCount >= 5 && l.fontSize >= bodySize * 0.88f && l.bounds.width >= pageWidth * 0.25f &&
            !Patterns.caption.containsMatchIn(l.text)

    /** Whether a prose line lies between [a] and [b] (inside their union but outside both). */
    private fun separated(a: Rect, b: Rect, prose: List<TextLine>): Boolean {
        val u = a.union(b)
        return prose.any { l ->
            val r = l.bounds
            r.intersects(u, -1f) && !r.intersects(a, -1f) && !r.intersects(b, -1f) && r.coveredBy(u) >= 0.5f
        }
    }

    /** A strip of colour behind a line of text (heading bars, example banners). */
    private fun isBanner(r: Rect, lines: List<TextLine>, bodySize: Float): Boolean =
        r.height <= bodySize * 3.2f && r.width >= r.height * 3.5f &&
            lines.any { l -> l.bounds.coveredBy(r.outset(2f)) >= 0.7f }

    private fun mergeClusters(clusters: MutableList<MutableList<Item>>, slack: Float, prose: List<TextLine>) {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in clusters.indices) {
                val bi = boundsOf(clusters[i])
                for (j in i + 1 until clusters.size) {
                    val bj = boundsOf(clusters[j])
                    if (bi.intersects(bj, slack) && !separated(bi, bj, prose)) {
                        clusters[i].addAll(clusters[j])
                        clusters.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
    }

    /**
     * Caption below the figure, possibly after a few notes that belong to the drawing (a formula
     * box, a remark in small print) centred under it. Returns (notes, caption lines).
     */
    private fun findCaptionBelow(bounds: Rect, lines: List<TextLine>, bodySize: Float): Pair<List<TextLine>, List<TextLine>>? {
        val below = lines.filter { l ->
            l.bounds.top >= bounds.bottom - 2f && l.bounds.horizontalOverlap(bounds) > 0f &&
                l.bounds.top - bounds.bottom < bodySize * 12f
        }.sortedBy { it.bounds.top }
        val notes = mutableListOf<TextLine>()
        var lastBottom = bounds.bottom
        for (l in below) {
            if (l.bounds.top - lastBottom > max(l.fontSize * 3.5f, bodySize * 2.5f)) return null
            if (Patterns.caption.containsMatchIn(l.text)) {
                return notes to captionLines(l, lines)
            }
            val centred = kotlin.math.abs(l.bounds.centerX - bounds.centerX) <= bounds.width * 0.15f
            val inside = l.bounds.horizontalOverlap(bounds) >= l.bounds.width * 0.8f
            if (!centred || !inside || l.fontSize > bodySize * 1.1f || notes.size >= 5 ||
                Patterns.listMarker.containsMatchIn(l.text)
            ) return null
            notes += l
            lastBottom = l.bounds.bottom
        }
        return null
    }

    /** A caption printed in the margin column next to the figure (common in textbooks). */
    private fun findCaptionBeside(bounds: Rect, lines: List<TextLine>, pageWidth: Float): List<TextLine>? {
        val first = lines.filter { l ->
            Patterns.caption.containsMatchIn(l.text) && l.bounds.horizontalOverlap(bounds) <= 0f &&
                max(bounds.left - l.bounds.right, l.bounds.left - bounds.right) <= pageWidth * 0.3f &&
                l.bounds.top >= bounds.top - l.fontSize * 2f && l.bounds.top <= bounds.bottom
        }.minByOrNull { abs(it.bounds.top - bounds.top) } ?: return null
        return captionLines(first, lines)
    }

    private fun findCaptionAbove(bounds: Rect, lines: List<TextLine>): TextLine? =
        lines.filter { l ->
            l.bounds.horizontalOverlap(bounds) > 0f && l.bounds.bottom <= bounds.top + 2f &&
                bounds.top - l.bounds.bottom < l.fontSize * 2.5f && Patterns.caption.containsMatchIn(l.text)
        }.maxByOrNull { it.bounds.bottom }

    /** The caption line and its continuation lines (a multi-line caption). */
    private fun captionLines(first: TextLine, lines: List<TextLine>): List<TextLine> {
        val out = mutableListOf(first)
        var last = first
        for (l in lines.sortedBy { it.bounds.top }) {
            if (l === first || l.bounds.top <= last.bounds.top) continue
            if (l.bounds.top - last.bounds.bottom > last.fontSize * 0.9f) break
            if (kotlin.math.abs(l.fontSize - first.fontSize) > 0.6f || l.bounds.horizontalOverlap(first.bounds) <= 0f) break
            out += l
            last = l
            if (out.size >= 4) break
        }
        return out
    }
}
