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

    /** The page is a scan: the sizes of its text are an OCR's guesses. */
    private var scan = false

    private class Item(var bounds: Rect, val strong: Boolean, val raster: Boolean, val complex: Boolean, val rect: Boolean)

    /**
     * A box is often painted as several shapes on top of each other: a rounded outline (curves),
     * one rectangle per side or fill. They are one box, not a drawing: identical rectangles count
     * once, and a rounded outline that coincides with a rectangle is dropped.
     */
    private fun collapseBoxes(items: MutableList<Item>) {
        fun same(a: Rect, b: Rect, tol: Float) =
            abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol && abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol
        val rects = items.filter { it.rect }
        val drop = HashSet<Item>()
        for ((i, r) in rects.withIndex()) {
            if (r in drop) continue
            for (j in i + 1 until rects.size) if (same(r.bounds, rects[j].bounds, 1.5f)) drop += rects[j]
        }
        for (c in items) {
            if (!c.complex || c.bounds.width < 40f || c.bounds.height < 20f) continue
            if (rects.any { r -> same(c.bounds, r.bounds, 3f) }) drop += c
        }
        items.removeAll(drop)
        // A border painted as strips along the edges of a (rounded) panel is one box too.
        val added = mutableListOf<Item>()
        for (c in items.toList()) {
            if (!c.complex || c in drop || c.bounds.width < 40f || c.bounds.height < 20f) continue
            val b = c.bounds
            val strips = items.filter { s ->
                s !== c && (s.rect || !s.strong) && min(s.bounds.width, s.bounds.height) <= 10f && b.outset(3f).contains(s.bounds)
            }
            val edges = listOf(
                strips.filter { abs(it.bounds.top - b.top) <= 3f && it.bounds.width >= b.width * 0.8f },
                strips.filter { abs(it.bounds.bottom - b.bottom) <= 3f && it.bounds.width >= b.width * 0.8f },
                strips.filter { abs(it.bounds.left - b.left) <= 3f && it.bounds.height >= b.height * 0.8f },
                strips.filter { abs(it.bounds.right - b.right) <= 3f && it.bounds.height >= b.height * 0.8f },
            )
            if (edges.count { it.isNotEmpty() } < 3) continue
            val frame = edges.flatten().toSet()
            drop += c
            drop += frame
            added += Item(Rect.bounding(frame.map { it.bounds } + b)!!, strong = true, raster = false, complex = false, rect = true)
        }
        items.removeAll(drop)
        items += added
    }

    fun detect(
        page: RawPage,
        lines: List<TextLine>,
        tables: List<TableRegion>,
        equations: List<Rect> = emptyList(),
        bodySizeHint: Float? = null,
    ): Result {
        val pageArea = page.width * page.height
        scan = page.ink != null
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
        // Chips drawn around words of a sentence (inline `code`, a keyboard key, a highlighted
        // term) are decoration of the text, not drawings: they sit inside a line of running text.
        val chipBody = bodySizeHint ?: PageAnalyzer.typicalSize(lines, page.width)
        val chipLines = lines.filter { isProse(it, chipBody, page.width) }
        fun inlineChip(b: Rect): Boolean = b.height <= chipBody * 1.9f && b.width <= page.width * 0.6f && chipLines.any { l ->
            l.bounds.verticalOverlap(b) >= min(b.height, l.bounds.height) * 0.7f && b.centerX in l.bounds.left..l.bounds.right
        }
        for (p in page.paths) {
            val b = p.bounds.clamp(page.width, page.height)
            if (b.isEmpty && p.shape == PathShape.COMPLEX) continue
            if (b.area > pageArea * 0.6f) continue // page frames and backgrounds
            if (inTable(b)) continue
            if (p.shape != PathShape.HORIZONTAL_LINE && p.shape != PathShape.VERTICAL_LINE && inlineChip(b)) continue
            when (p.shape) {
                PathShape.COMPLEX -> if (max(b.width, b.height) >= 4f) {
                    // Thin pieces (mitred box borders, rules drawn as polygons) are lines, not drawings.
                    // (a 4.5 pt accent border of a rounded callout is one too, drawn piece by piece)
                    val thin = min(b.width, b.height) <= 6.5f && max(b.width, b.height) >= min(b.width, b.height) * 6f
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
        collapseBoxes(items)
        val bodySize = bodySizeHint ?: PageAnalyzer.typicalSize(lines, page.width)
        // Banners: a thin strip of colour behind a heading ("Example 1.4") is decoration.
        items.removeAll { it.raster && isBanner(it.bounds, lines, bodySize) }
        if (items.none { it.strong }) return Result(emptyList(), emptySet())

        // Running text separates figures: nothing is merged across a paragraph line.
        val prose = lines.filter { isProse(it, bodySize, page.width) }
        // Left edges of the text columns: where three or more lines of running text start.
        val textEdges = lines.filter { it.wordCount >= 5 && it.fontSize >= bodySize * 0.9f }.map { it.bounds.left }.let { lefts ->
            lefts.filter { x -> lefts.count { abs(it - x) <= 1.5f } >= 3 }
        }

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
        joinKeyRows(clusters, lines, prose, bodySize)

        // On a scan, captions ("FIGURE 1-1" and the description under it, beside the drawing)
        // are no labels.
        val captionBlocks = if (page.ink == null) emptyList() else lines.filter { Patterns.caption.containsMatchIn(it.text) }.map { captionLines(it, lines) }
        val captions = captionBlocks.flatten().toSet()
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
            // A small emblem in the top or bottom margin (the chapter's logo beside the running
            // head) is page furniture, like the running head.
            if (max(bounds.width, bounds.height) <= 60f && (bounds.bottom <= page.height * 0.08f || bounds.top >= page.height * 0.92f)) continue
            val inside = lines.filter { it.bounds.coveredBy(bounds.outset(2f)) >= 0.6f }
            val textArea = inside.sumOf { (it.bounds.area).toDouble() }.toFloat()
            val keyRow = keyCaps(c, lines) >= 3
            if (!hasRaster && !keyRow) {
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
            } else if (inside.size > 15 && textArea > bounds.area * 0.4f && !scan) {
                continue // decorative background image behind text (on a scan: what an OCR read in a drawing)
            }
            // Labels around the drawing (titles above nodes, notes beside them) belong to it too,
            // otherwise they leak into the text and the picture is cropped through them.
            val members = inside.toMutableSet()
            if (keyRow) {
                // The rest of the row of keys: the text on the keys and the result after them
                // ("ENTER = 3 + 9/4 = 5.25"), a formula in it included.
                var more = true
                while (more) {
                    more = false
                    val row = { r: Rect -> r.verticalOverlap(bounds) >= min(r.height, bounds.height) * 0.5f &&
                        max(bounds.left - r.right, r.left - bounds.right) <= bodySize * 2f }
                    for (l in lines) if (l !in members && l !in absorbed && row(l.bounds)) { members += l; bounds = bounds.union(l.bounds); more = true }
                    for (e in equations) if (!bounds.contains(e) && row(e)) { bounds = bounds.union(e); more = true }
                }
            }
            var grown = true
            while (grown) {
                grown = false
                for (l in lines) {
                    if (l in members || l in absorbed || l in captions) continue
                    // (on a scan, not the labels of another drawing)
                    if (scan && clusters.any { o -> o !== c && boundsOf(o).contains(l.bounds.centerX, l.bounds.centerY) }) continue
                    if (isLabel(l, bounds, bodySize, page.width, page.height, textEdges)) {
                        members += l
                        bounds = bounds.union(l.bounds)
                        grown = true
                    }
                }
            }
            // (a caption printed beside the drawing, over the end of its axis: the drawing stops there)
            captionBlocks.map { b -> Rect.bounding(b.map { it.bounds })!! }.filter { it.intersects(bounds) && it.left > bounds.left + bounds.width * 0.5f }
                .minOfOrNull { it.left }?.let { edge ->
                    bounds = Rect(bounds.left, bounds.top, edge - 2f, bounds.bottom)
                    members.removeAll { it.bounds.left >= edge - 1f }
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
                // Two drawings with their own captions are two figures.
                val capA = captionUnder(a.bounds, lines, bodySize)
                val capB = captionUnder(b.bounds, lines, bodySize)
                val ownCaptions = capA != null && capB != null && capA !== capB
                if ((sideBySide || (stacked && small)) && !ownCaptions && !separated(a.bounds, b.bounds, prose) &&
                    !(sideBySide && textColumnsBetween(a.bounds, b.bounds, lines.filter { it !in a.members && it !in b.members }, page.height))
                ) {
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
            // A decorative band of pictures down the edge of a chapter opener is not a figure.
            if (hasRaster && bounds.height >= page.height * 0.9f && bounds.width < page.width * 0.45f &&
                (bounds.left <= 2f || bounds.right >= page.width - 2f)
            ) {
                // letters printed on it (a logo) are part of the decoration
                absorbed -= cand.members.filter { it.text.trim().length > 2 }.toSet()
                continue
            }
            // A row of calculator keys is a small picture, but a picture.
            val keys = keyCaps(c, cand.members.toList()) >= 3
            if ((bounds.width < 30f || bounds.height < 20f) && !keys) {
                absorbed -= cand.members
                continue
            }
            // Small drawings count when they are labelled (a circuit element with its i, +, v, −).
            val labels = cand.members.count { it.text.length <= 12 }
            // A small drawing with its own "Figure 1.12" caption under it is a figure all the same
            // (not a shaded box around a sentence printed above a figure's caption).
            val captioned = captionUnder(bounds, lines.filter { it !in cand.members }, bodySize, bodySize * 3f) != null &&
                bounds.height >= 20f && cand.members.none { it.wordCount >= 6 }
            val minArea = if (labels >= 2 && (complexCount + rectCount) >= 1) pageArea * 0.003f else pageArea * 0.012f
            if (!hasRaster && bounds.area < minArea && !captioned && !keys) {
                absorbed -= cand.members
                continue
            }
            if (!hasRaster && complexCount + rectCount < 2 && c.size < 3 && labels < 2 && !captioned) {
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

        val gutter = TextColumns.gutter(lines, page.width, page.height)
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
                val beside = if (above == null) findCaptionBeside(f.bounds, free, page.width, gutter) else null
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

    /**
     * Calculator keys ("2ND", "√", "9", "ENTER"): small framed labels side by side, most of the
     * cluster's shapes. Returns how many keys (0 if the cluster is something else).
     */
    /**
     * The calculator keys among [c]: small frames of one height with their label inside, and keys
     * with a drawn symbol (arrow keys). A key painted as a stack of shapes (shading, outline)
     * counts once, by its outermost shape.
     */
    private fun keysOf(c: List<Item>, text: List<TextLine>): Pair<List<Item>, List<Item>> {
        val sized = c.filter { k -> (k.rect || k.complex) && k.bounds.height in 8f..26f && k.bounds.width in 8f..70f }
        val outer = sized.filter { k -> sized.none { o -> o !== k && o.bounds.outset(0.5f).contains(k.bounds) && o.bounds.area > k.bounds.area * 1.02f } }
            .distinctBy { listOf((it.bounds.left / 2f).toInt(), (it.bounds.top / 2f).toInt(), (it.bounds.right / 2f).toInt(), (it.bounds.bottom / 2f).toInt()) }
        // (its label may be part of a line running over several keys: "2ND SIN 0")
        val labelled = outer.filter { k ->
            text.any { l -> l.bounds.horizontalOverlap(k.bounds) >= min(k.bounds.width, l.bounds.width) * 0.5f && l.bounds.centerY in k.bounds.top..k.bounds.bottom }
        }
        val drawn = outer.filter { k -> k !in labelled && c.any { o -> o !== k && o !in outer && k.bounds.outset(0.5f).contains(o.bounds) } }
        return labelled to drawn
    }

    /** Shapes of [c] that are neither keys nor parts of keys (nor small round buttons). */
    private fun nonKeys(c: List<Item>, keys: List<Item>): Int {
        val keysBounds = Rect.bounding(keys.map { it.bounds }) ?: return c.count { it.strong }
        return c.count { o ->
            o.strong && keys.none { k -> k.bounds.outset(1f).contains(o.bounds) } && !(o.bounds.width <= 10f && o.bounds.height <= 10f) &&
                // (the band printed behind the row of keys)
                !o.bounds.outset(1f).contains(keysBounds)
        }
    }

    private fun keyCaps(c: List<Item>, text: List<TextLine>): Int {
        val (labelled, drawn) = keysOf(c, text)
        if (labelled.size < 2 || labelled.size + drawn.size < 3) return 0
        val keys = labelled + drawn
        if (keys.size < nonKeys(c, keys) * 2) return 0
        // all keys of one height (an arrow key may be a little smaller)
        val h = labelled.map { it.bounds.height }.sorted()[labelled.size / 2]
        if (labelled.any { abs(it.bounds.height - h) > h * 0.25f }) return 0
        if (drawn.any { it.bounds.height < h * 0.6f || it.bounds.height > h * 1.25f }) return 0
        return keys.distinctBy { (it.bounds.centerX / 4f).toInt() to (it.bounds.centerY / 4f).toInt() }.size
    }

    /**
     * Keys printed along a line of text ("MODE ↓ Display Digits → 3:FIX 2 ENTER ENTER") are one
     * row of keys, however far apart: clusters of nothing but keys on one row are joined.
     */
    private fun joinKeyRows(clusters: MutableList<MutableList<Item>>, lines: List<TextLine>, prose: List<TextLine>, bodySize: Float) {
        fun keysOnly(c: List<Item>): Boolean {
            val (labelled, drawn) = keysOf(c, lines)
            val keys = labelled + drawn
            return keys.isNotEmpty() && nonKeys(c, keys) == 0
        }
        var joined = true
        while (joined) {
            joined = false
            outer@ for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val a = boundsOf(clusters[i])
                val b = boundsOf(clusters[j])
                if (a.verticalOverlap(b) < min(a.height, b.height) * 0.5f) continue
                val gap = Rect(min(a.right, b.right), min(a.top, b.top), max(a.left, b.left), max(a.bottom, b.bottom))
                if (gap.width > bodySize * 15f || prose.any { it.bounds.intersects(gap) }) continue
                if (!keysOnly(clusters[i]) || !keysOnly(clusters[j])) continue
                val both = clusters[i] + clusters[j]
                if (keyCaps(both, lines) < 3) continue
                clusters[i].addAll(clusters[j])
                clusters.removeAt(j)
                joined = true
                break@outer
            }
        }
    }

    /** Whether [l] is a label of the drawing at [fig]: inside it, or small text right next to it. */
    private fun isLabel(l: TextLine, fig: Rect, bodySize: Float, pageWidth: Float, pageHeight: Float, textEdges: List<Float>): Boolean {
        if (l.bounds.coveredBy(fig.outset(2f)) >= 0.6f) return true
        if (Patterns.caption.containsMatchIn(l.text)) return false
        // A line starting where the text column's lines start ("Solution:" beside a figure in
        // the margin column) is part of the text.
        if (l.fontSize >= bodySize * 0.9f && textEdges.any { abs(it - l.bounds.left) <= 1.5f }) return false
        // Running headers and page numbers in the top and bottom margins are not labels.
        if (l.bounds.bottom < pageHeight * 0.08f || l.bounds.top > pageHeight * 0.92f) return false
        // Nor is a sentence, an exercise ("2.73 An ammeter…") or a caption's "For Prob. 2.72."
        // printed about the size of the text (a diagram's phrases are set much smaller).
        if (l.fontSize >= bodySize * 0.8f && sentence(l)) return false
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
        val WORD = Regex("\\p{L}{3,}")
        val FOR_REFERENCE = Regex("^\\s*for\\s+(?:prob|problem|practice|example|exercise|ex)\\b", RegexOption.IGNORE_CASE)
    }

    /** Running text rather than a label: several words, a numbered exercise, "For Prob. 2.7." */
    private fun sentence(l: TextLine): Boolean {
        val pieces = if (l.chunks.size >= 2) l.chunks.size else 1
        // (a value such as "10 V" or "5 kΩ" is a label, not a numbered exercise)
        return (l.wordCount >= 6 && l.wordCount.toFloat() / pieces >= 4f) ||
            (Patterns.numberedHeading.containsMatchIn(l.text) && WORD.containsMatchIn(l.text)) ||
            FOR_REFERENCE.containsMatchIn(l.text) || (l.wordCount >= 4 && Patterns.endsSentence(l.text))
    }

    /** The "Figure 2.47" caption line printed under [r] (within a few lines), if any. */
    private fun captionUnder(r: Rect, lines: List<TextLine>, bodySize: Float, slack: Float = 0f): TextLine? =
        lines.filter { l ->
            Patterns.caption.containsMatchIn(l.text) && l.bounds.horizontalOverlap(r.outset(slack)) > 0f &&
                l.bounds.top >= r.bottom - 2f && l.bounds.top - r.bottom <= bodySize * 4f
        }.minByOrNull { it.bounds.top }

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

    /**
     * Whether a gutter between two text columns runs between [a] and [b] (figures of two
     * problems printed side by side in a two-column layout are separate figures).
     */
    private fun textColumnsBetween(a: Rect, b: Rect, lines: List<TextLine>, pageHeight: Float): Boolean {
        val (l, r) = if (a.centerX < b.centerX) a to b else b to a
        if (l.right >= r.left) return false
        // (running headers in the top and bottom margins span the whole text width)
        val text = lines.filter { it.wordCount >= 4 && it.bounds.top > pageHeight * 0.07f && it.bounds.bottom < pageHeight * 0.93f }
        var x = l.right
        while (x <= r.left) {
            val gx = x
            if (text.none { it.bounds.left < gx - 2f && it.bounds.right > gx + 2f } &&
                text.count { it.bounds.right <= gx } >= 2 && text.count { it.bounds.left >= gx } >= 2
            ) return true
            x += 2f
        }
        return false
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
            l.bounds.top >= bounds.bottom - 4f && l.bounds.horizontalOverlap(bounds) > 0f &&
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
    private fun findCaptionBeside(bounds: Rect, lines: List<TextLine>, pageWidth: Float, gutter: Float?): List<TextLine>? {
        val first = lines.filter { l ->
            // not across the gutter of a two-column page
            (gutter == null || (l.bounds.centerX < gutter) == (bounds.centerX < gutter)) &&
            Patterns.caption.containsMatchIn(l.text) && l.bounds.horizontalOverlap(bounds) <= 0f &&
                max(bounds.left - l.bounds.right, l.bounds.left - bounds.right) <= pageWidth * 0.3f &&
                l.bounds.top >= bounds.top - l.fontSize * 2f && l.bounds.top <= bounds.bottom + l.fontSize * 1.5f
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
        // Only lines under the caption: text of the next column may sit on the same rows.
        for (l in lines.filter { it.bounds.horizontalOverlap(first.bounds) > 0f }.sortedBy { it.bounds.top }) {
            if (l === first || l.bounds.top <= last.bounds.top) continue
            if (l.bounds.top - last.bounds.bottom > last.fontSize * 0.9f) break
            // The label ("Figure 1.5") may be set larger than the description under it.
            val sameSize = kotlin.math.abs(l.fontSize - last.fontSize) <= if (scan) 1.5f else 0.6f
            // the next problem or list item ("2.10 Determine…") starts after the caption
            if (Patterns.numberedHeading.containsMatchIn(l.text)) break
            val smallerDescription = out.size == 1 && l.fontSize < first.fontSize && first.fontSize - l.fontSize <= first.fontSize * (if (scan) 0.3f else 0.16f)
            if (!(sameSize || smallerDescription)) break
            out += l
            last = l
            if (out.size >= 4) break
        }
        return out
    }
}
