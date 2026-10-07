package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import kotlin.math.max

/**
 * Finds figures: raster images and clusters of vector graphics (diagrams, charts, drawings).
 * Text inside a figure (labels) is absorbed into it; the figure is later rendered from the
 * original page so diagrams keep their exact look. A caption line next to the figure
 * ("Figure 3…", "চিত্র ২…") is attached to it.
 */
class FigureDetector {

    class Result(val figures: List<FigureRegion>, val absorbed: Set<TextLine>)

    private class Item(var bounds: Rect, val strong: Boolean, val raster: Boolean, val complex: Boolean, val rect: Boolean)

    fun detect(page: RawPage, lines: List<TextLine>, tables: List<TableRegion>): Result {
        val pageArea = page.width * page.height
        val items = mutableListOf<Item>()
        val inTable = { r: Rect -> tables.any { it.bounds.outset(2f).contains(r) } }

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
                    items += Item(b, strong = p.segments >= 3 || b.width * b.height > 400f, raster = false, complex = true, rect = false)
                }
                PathShape.RECTANGLE -> if (!p.lightFill || p.stroked) {
                    items += Item(b, strong = b.width >= 8f && b.height >= 8f, raster = false, complex = false, rect = true)
                }
                PathShape.HORIZONTAL_LINE, PathShape.VERTICAL_LINE ->
                    items += Item(b, strong = false, raster = false, complex = false, rect = false)
            }
        }
        for (r in page.rotatedText) items += Item(r, strong = false, raster = false, complex = false, rect = false)
        if (items.none { it.strong }) return Result(emptyList(), emptySet())

        // Cluster strong items, then let weak items (lines, labels) join nearby clusters.
        val clusters = mutableListOf<MutableList<Item>>()
        for (it in items.filter { it.strong }) clusters += mutableListOf(it)
        mergeClusters(clusters, slack = 14f)
        val weak = items.filter { !it.strong }
        var grew = true
        while (grew) {
            grew = false
            for (w in weak) {
                if (clusters.any { c -> w in c }) continue
                val target = clusters.firstOrNull { c -> boundsOf(c).intersects(w.bounds, 6f) } ?: continue
                target += w
                grew = true
            }
            if (grew) mergeClusters(clusters, slack = 4f)
        }

        val figures = mutableListOf<FigureRegion>()
        val absorbed = HashSet<TextLine>()
        for (c in clusters) {
            var bounds = boundsOf(c)
            val hasRaster = c.any { it.raster }
            val complexCount = c.count { it.complex }
            val rectCount = c.count { it.rect }
            if (bounds.width < 36f || bounds.height < 24f) continue
            if (!hasRaster && bounds.area < pageArea * 0.012f) continue
            val inside = lines.filter { it.bounds.coveredBy(bounds.outset(2f)) >= 0.6f }
            val textArea = inside.sumOf { (it.bounds.area).toDouble() }.toFloat()
            if (!hasRaster) {
                // Frames, shaded boxes, highlight bars: mostly rectangles around running text.
                if (complexCount == 0 && rectCount <= 3 && (inside.size >= 3 || textArea > bounds.area * 0.25f)) continue
                if (complexCount == 0 && rectCount <= 1 && c.size <= 2) continue
                if (complexCount + rectCount < 2 && c.size < 3) continue
                // A big drawing that is mostly text paragraphs (e.g. a page border with content).
                if (inside.size > 12 && textArea > bounds.area * 0.35f) continue
            } else if (inside.size > 15 && textArea > bounds.area * 0.4f) {
                continue // decorative background image behind text
            }
            // Short labels that stick out of the drawing belong to it as well.
            val labels = lines.filter { l ->
                l !in inside && l.wordCount <= 6 && l.bounds.coveredBy(bounds.outset(4f)) >= 0.3f
            }
            labels.forEach { bounds = bounds.union(it.bounds) }
            val members = inside + labels
            absorbed += members
            val kind = when {
                page.fromOcr -> FigureKind.SCAN
                hasRaster && complexCount == 0 -> FigureKind.RASTER
                hasRaster -> FigureKind.MIXED
                else -> FigureKind.VECTOR
            }
            figures += FigureRegion(bounds.outset(3f).clamp(page.width, page.height), kind)
        }

        // Merge figures that overlap after label expansion.
        val merged = mutableListOf<FigureRegion>()
        for (f in figures.sortedBy { it.bounds.top }) {
            val idx = merged.indexOfFirst { it.bounds.intersects(f.bounds) }
            if (idx >= 0) {
                val m = merged[idx]
                val kind = if (m.kind == f.kind) m.kind else FigureKind.MIXED
                merged[idx] = FigureRegion(m.bounds.union(f.bounds), kind)
            } else {
                merged += f
            }
        }

        // Captions.
        val result = merged.map { f ->
            val caption = findCaption(f.bounds, lines.filter { it !in absorbed })
            if (caption != null) {
                absorbed += caption
                FigureRegion(f.bounds, f.kind, caption.joinToString(" ") { it.text })
            } else {
                f
            }
        }
        return Result(result, absorbed)
    }

    private fun boundsOf(c: List<Item>): Rect = Rect.bounding(c.map { it.bounds })!!

    private fun mergeClusters(clusters: MutableList<MutableList<Item>>, slack: Float) {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in clusters.indices) {
                val bi = boundsOf(clusters[i])
                for (j in i + 1 until clusters.size) {
                    if (bi.intersects(boundsOf(clusters[j]), slack)) {
                        clusters[i].addAll(clusters[j])
                        clusters.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
    }

    private fun findCaption(bounds: Rect, lines: List<TextLine>): List<TextLine>? {
        val near = lines.filter { l ->
            l.bounds.horizontalOverlap(bounds) > 0f &&
                (l.bounds.top >= bounds.bottom - 2f && l.bounds.top - bounds.bottom < l.fontSize * 2.5f ||
                    l.bounds.bottom <= bounds.top + 2f && bounds.top - l.bounds.bottom < l.fontSize * 2.5f)
        }.sortedBy { it.bounds.top }
        val first = near.firstOrNull { Patterns.caption.containsMatchIn(it.text) } ?: return null
        val out = mutableListOf(first)
        // Continuation lines of a multi-line caption below the figure.
        if (first.bounds.top >= bounds.bottom - 2f) {
            var last = first
            for (l in lines.sortedBy { it.bounds.top }) {
                if (l === first || l.bounds.top <= last.bounds.top) continue
                if (l.bounds.top - last.bounds.bottom > last.fontSize * 0.9f) break
                if (kotlin.math.abs(l.fontSize - first.fontSize) > 0.6f || l.bounds.horizontalOverlap(first.bounds) <= 0f) break
                out += l
                last = l
                if (out.size >= 4) break
            }
        }
        return out
    }
}
