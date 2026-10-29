package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.VectorPath
import com.raptibooks.core.structure.text.TextCleaner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds display equations: formulas set on their own lines, typically with stacked fractions,
 * integral or sum limits, sub/superscripts, symbols from math fonts and an equation number.
 *
 * Text extraction cannot reflow such two-dimensional layouts (fractions come out as separate
 * rows, which also look like tables), so each equation becomes one region that is rendered
 * from the page: the reader shows it like the book prints it, at the size of the body text.
 * The equation number ("(1.6)") is kept separately as the region's caption.
 *
 * Simple one-line formulas whose text is complete ("R = 10 + 20 = 30 Ω") stay text.
 */
class EquationDetector {

    class Result(val equations: List<FigureRegion>, val absorbed: Set<TextLine>, val paths: Set<VectorPath>)

    private class Feat(val line: TextLine) {
        val text = line.text
        val words: Int
        val rel: Int
        val big: Int
        val unknown: Int
        val nonWordRatio: Float
        val number: Boolean = EQ_NUMBER.matches(text.trim())

        init {
            val st = MathText.stats(text)
            words = st.words
            rel = st.relations
            big = st.big
            unknown = st.unknown
            nonWordRatio = st.nonWordRatio
        }

        /** "প্রদত্ত : V = 230 V": the data line of a worked example, kept as text. */
        val labelled: Boolean = MathText.hasLabel(text) || Patterns.listMarker.containsMatchIn(text)

        /** Short math piece: a numerator, a limit, a symbol, an equation number. */
        val fragment: Boolean get() = number || (words == 0 && text.length <= 40) || (words <= 1 && text.length <= 12)

        val seed: Boolean get() = !number && !labelled && words <= 2 && nonWordRatio >= 0.4f && (rel > 0 || unknown > 0 || big > 0)

        val prose: Boolean get() = words >= 4
    }

    private class Region(var bounds: Rect) {
        val lines = LinkedHashSet<TextLine>()
        /** Words on the rows of a derivation ("so that", "and"), part of the picture. */
        val labels = LinkedHashSet<TextLine>()
        /** Numerators and denominators made of words ("potential difference / resistance"). */
        val wordy = LinkedHashSet<TextLine>()
        val bars = mutableListOf<VectorPath>()
        val frames = mutableListOf<VectorPath>()
        var number: TextLine? = null
    }

    fun detect(page: RawPage, lines: List<TextLine>, bodySize: Float, obstacles: List<Rect> = emptyList()): Result {
        if (lines.isEmpty()) return Result(emptyList(), emptySet(), emptySet())
        val feats = lines.associateWith { Feat(it) }
        val free = { r: Rect -> obstacles.none { it.intersects(r) } }
        // "That is, VW = 0∠0°, VB = 120∠0°": a sentence with inline math, starting where the
        // lines of the paragraph start, is text (display formulas are set apart).
        val proseLefts = lines.filter { feats.getValue(it).prose }.map { it.bounds.left }
        fun sentence(l: TextLine) = (SENTENCE_START.containsMatchIn(l.text) || OPENER.containsMatchIn(l.text.lowercase())) &&
            proseLefts.count { abs(it - l.bounds.left) <= l.fontSize * 0.5f } >= 2

        val regions = mutableListOf<Region>()
        // Fraction bars: a short rule with a math piece right above and right below it.
        val rules = page.paths.filter { p ->
            p.shape == PathShape.HORIZONTAL_LINE && p.bounds.height <= 2.2f && p.bounds.width in 3f..page.width * 0.45f && free(p.bounds)
        }
        for (bar in rules) {
            val b = bar.bounds
            // Pieces of a longer rule (table borders drawn cell by cell) are not fraction bars.
            if (rules.any { o -> o !== bar && abs(o.bounds.centerY - b.centerY) <= 1f &&
                    o.bounds.left <= b.right + 3f && o.bounds.right >= b.left - 3f }
            ) continue
            val y = b.centerY
            // Two fractions side by side ("= 250×1000/250+1000 = 250000/1250") share text lines:
            // judge each bar against the piece of the line above/below it.
            fun piece(l: TextLine): Rect {
                if (l.chunks.size < 2) return l.bounds
                val c = l.chunks.maxByOrNull { min(it.right, b.right) - max(it.left, b.left) }!!
                return Rect(c.left, l.bounds.top, c.right, l.bounds.bottom)
            }
            fun over(l: TextLine) = piece(l).let { it.horizontalOverlap(b) >= min(it.width, b.width) * 0.5f }
            // The text of the part of the line over the bar.
            fun pieceText(l: TextLine): String {
                if (l.chunks.size < 2) return l.text
                val c = l.chunks.maxByOrNull { min(it.right, b.right) - max(it.left, b.left) }!!
                return l.text.substring(c.textStart.coerceIn(0, l.text.length), c.textEnd.coerceIn(0, l.text.length))
            }
            fun stats(l: TextLine) = if (l.chunks.size < 2) null else MathText.stats(pieceText(l))
            fun fragment(l: TextLine): Boolean {
                val st = stats(l) ?: return feats.getValue(l).fragment
                val t = pieceText(l).trim()
                return EQ_NUMBER.matches(t) || (st.words == 0 && t.length <= 40) || (st.words <= 1 && t.length <= 12)
            }
            fun words(l: TextLine) = stats(l)?.words ?: feats.getValue(l).words
            // "Effect = cause / opposition": a fraction of words, with "=" right beside the bar
            val relation = lines.any { l ->
                abs(l.bounds.centerY - y) <= bodySize * 0.8f &&
                    ((l.text.trimEnd().endsWith("=") && l.bounds.right <= b.left + 1f && b.left - l.bounds.right <= bodySize * 2f) ||
                        (l.text.trimStart().startsWith("=") && l.bounds.left >= b.right - 1f && l.bounds.left - b.right <= bodySize * 2f))
            }
            fun wordy(l: TextLine) = relation && words(l) <= 3 && pieceText(l).trim().length <= 30
            val num = lines.firstOrNull { l ->
                val em = l.fontSize
                over(l) && l.baseline in y - em * 1.0f..y - em * 0.05f && (fragment(l) || wordy(l))
            } ?: continue
            val den = lines.firstOrNull { l ->
                val em = l.fontSize
                over(l) && l.bounds.top in y - em * 0.25f..y + em * 0.6f && l.baseline > y && (fragment(l) || wordy(l))
            } ?: continue
            if (words(num) + words(den) > 1 && !(wordy(num) && wordy(den))) continue
            // A fraction bar is centred under its numerator and barely wider than the wider
            // part; a table rule runs under a whole row of header cells.
            val em = max(num.fontSize, den.fontSize)
            val numPiece = piece(num)
            val denPiece = piece(den)
            val widest = max(numPiece.width, denPiece.width)
            if (b.width > widest * 1.6f + em || abs(numPiece.centerX - b.centerX) > b.width * 0.3f + 2f ||
                abs(denPiece.centerX - b.centerX) > b.width * 0.3f + 2f
            ) continue
            val r = Region(b.union(num.bounds).union(den.bounds))
            r.lines += num
            r.lines += den
            if (!fragment(num)) r.wordy += num
            if (!fragment(den)) r.wordy += den
            r.bars += bar
            regions += r
        }
        for (l in lines) {
            val f = feats.getValue(l)
            if (f.seed && free(l.bounds) && regions.none { l in it.lines } && !sentence(l)) {
                regions += Region(l.bounds).also { it.lines += l }
            }
        }
        if (regions.isEmpty()) return Result(emptyList(), emptySet(), emptySet())

        // Grow: pieces on the same rows, limits and scripts just above/below, other fractions.
        var changed = true
        while (changed) {
            changed = false
            mergeOverlapping(regions, bodySize)
            for (r in regions) {
                val em = emOf(r, bodySize)
                for (l in lines) {
                    if (l in r.lines || regions.any { o -> o !== r && l in o.lines } || l === r.number) continue
                    val f = feats.getValue(l)
                    if (f.prose || !free(l.bounds)) continue
                    val sameRow = l.bounds.verticalOverlap(r.bounds) >= min(l.bounds.height, r.bounds.height) * 0.5f
                    val hGap = max(0f, max(r.bounds.left - l.bounds.right, l.bounds.left - r.bounds.right))
                    if (sameRow && f.number && l.bounds.left >= r.bounds.right - 1f && hGap <= page.width * 0.45f) {
                        if (r.number == null) {
                            r.number = l
                            changed = true
                        }
                        continue
                    }
                    // (an option's label, "b.", belongs to the formula after it; the columns of a
                    // system of equations without fractions, "I1(Z1 + ZL1)   − I2Zm12   + I3Zm13
                    // = E1", are set wider apart)
                    val rows = equationRows(r.lines)
                    val column = rows >= 1 && f.words == 0 && hGap <= em * 6f && r.bars.isEmpty() && l.text.any { it in "+−=" } &&
                        r.lines.any { abs(it.baseline - l.baseline) <= em * 0.08f } &&
                        !OPTION_START.containsMatchIn(l.text) && !Patterns.listMarker.containsMatchIn(l.text) && !labelled(r) &&
                        regions.none { o -> o.bars.isNotEmpty() && o.bounds.verticalOverlap(l.bounds) > 0f } &&
                        (rows >= 2 || regions.any { o ->
                            o !== r && equationRows(o.lines) >= 1 && o.bounds.horizontalOverlap(r.bounds) > 0f &&
                                max(o.bounds.top - r.bounds.bottom, r.bounds.top - o.bounds.bottom) <= em * 0.8f
                        })
                    if (sameRow && (f.fragment || f.seed) && (hGap <= em * 2.5f || column) &&
                        !(l.bounds.left >= r.bounds.right - 1f && OPTION_LABEL.matches(l.text.trim()))
                    ) {
                        r.lines += l
                        r.bounds = r.bounds.union(l.bounds)
                        changed = true
                        continue
                    }
                    val vGap = max(0f, max(r.bounds.top - l.bounds.bottom, l.bounds.top - r.bounds.bottom))
                    val within = l.bounds.left >= r.bounds.left - em * 1.5f && l.bounds.right <= r.bounds.right + em * 1.5f
                    // (a sentence stays text, but its pieces on the formula's own row join it:
                    // "Since v =" before a fraction and an integral)
                    if (vGap <= em * 0.35f && within && (f.words == 0 || f.seed) && !f.number && !sentence(l)) {
                        r.lines += l
                        r.bounds = r.bounds.union(l.bounds)
                        changed = true
                    }
                }
            }
        }

        mergeDerivations(regions, lines, feats, bodySize)
        // Pieces of math printed inside a derivation's picture are part of it (the columns of a
        // system of equations whose rows were taken in one by one).
        for (r in regions) for (l in lines) {
            if (regions.any { l in it.lines || l === it.number }) continue
            val f = feats.getValue(l)
            if (f.words == 0 && !f.number && free(l.bounds) && l.bounds.coveredBy(r.bounds) >= 0.9f) r.lines += l
        }
        if (absorbRowLabels(regions, lines, bodySize, proseLefts)) mergeOverlapping(regions, bodySize)
        numberRows(regions, lines, feats, page, free)

        val out = mutableListOf<FigureRegion>()
        val absorbed = HashSet<TextLine>()
        val usedPaths = HashSet<VectorPath>()
        for (r in regions) {
            val members = r.lines.filter { it !in r.labels && it !in r.wordy }.map { feats.getValue(it) }
            val em = emOf(r, bodySize)
            val rows = rowCount(r.lines)
            val stacked = rows >= 2 || r.bars.isNotEmpty()
            val special = members.any { it.unknown > 0 || it.big > 0 }
            // A numbered formula is display math even when it fits on one line.
            val numbered = r.number != null && members.any { it.rel > 0 }
            if (!stacked && !special && !numbered) continue
            if (members.sumOf { it.words } >= 5) continue
            // Columns of plain "A = 1.0" lines side by side are a table of values.
            if (r.bars.isEmpty() && !special && rows >= 3 && regions.count { o ->
                    o !== r && o.bars.isEmpty() && rowCount(o.lines) >= 3 &&
                        o.bounds.verticalOverlap(r.bounds) >= min(o.bounds.height, r.bounds.height) * 0.8f
                } >= 2
            ) continue
            if (!stacked && !numbered && inParagraph(r, lines, feats, em)) continue
            // A line of prose sharing the rows of the formula: it is inline math, keep the text.
            // (measured from the formulas, without the words printed on their rows)
            val formula = Rect.bounding(r.lines.filter { it !in r.labels }.map { it.bounds } + r.bars.map { it.bounds }) ?: r.bounds
            // (a line of the other column of the page, across the gutter, is not)
            val prose = lines.any { l ->
                l !in r.lines && l !== r.number && feats.getValue(l).prose &&
                    l.bounds.verticalOverlap(formula) > l.bounds.height * 0.5f &&
                    abs(l.bounds.centerX - formula.centerX) < page.width * 0.35f &&
                    max(l.bounds.left - formula.right, formula.left - l.bounds.right) <= em * 3f
            }
            if (prose) continue
            // Labels inside a drawing ("R1 = 10 Ω" next to a resistor) belong to the figure.
            val near = r.bounds.outset(em)
            if (page.images.any { it.bounds.intersects(near) }) continue
            // (units struck out where they cancel, "3 ft̸ / 1 yard̸", are marks on the formula)
            fun strike(p: VectorPath) = p.shape == PathShape.COMPLEX && p.segments <= 2 && p.bounds.height <= em &&
                r.lines.any { it.bounds.intersects(p.bounds) }
            val shapes = page.paths.filter { p ->
                p !in r.bars && p.bounds.intersects(near) && !p.bounds.contains(r.bounds.outset(-1f)) && p.bounds.width < page.width * 0.8f && !strike(p)
            }
            if (shapes.count { it.shape == PathShape.COMPLEX && min(it.bounds.width, it.bounds.height) > em * 0.8f } >= 1 ||
                shapes.count { it.shape != PathShape.HORIZONTAL_LINE } >= 6
            ) continue
            // Fraction bars inside, and a frame drawn around the formula (boxed equations).
            var bounds = r.bounds
            for (p in rules) if (p !in r.bars && bounds.outset(1f).contains(p.bounds)) r.bars += p
            val reach = Rect(bounds.left - em * 1.8f, bounds.top - em * 0.9f, bounds.right + em * 1.8f, bounds.bottom + em * 0.9f)
            val frame = page.paths.filter { p ->
                p.bounds.width < page.width * 0.8f && reach.contains(p.bounds) && !bounds.contains(p.bounds) &&
                    (p.shape == PathShape.HORIZONTAL_LINE || p.shape == PathShape.VERTICAL_LINE || p.shape == PathShape.RECTANGLE)
            }
            val frameBounds = Rect.bounding(frame.map { it.bounds })
            if (frameBounds != null && (frame.size >= 2 || frame[0].shape == PathShape.RECTANGLE) && frameBounds.contains(bounds, 1f) &&
                lines.none { l -> l !in r.lines && l !== r.number && frameBounds.intersects(l.bounds, -1f) }
            ) {
                bounds = bounds.union(frameBounds)
                r.frames += frame
            }
            val number = r.number?.text?.trim()
            val alt = r.lines.sortedWith(compareBy({ it.baseline }, { it.bounds.left }))
                .joinToString(" ") { TextCleaner.finish(it.text) }.replace(Regex("\\s+"), " ").trim()
            val size = r.lines.map { it.fontSize }.sorted().let { it[it.size / 2] }
            // The picture never takes in a piece of the text line above or below.
            var crop = bounds.outset(min(2f, em * 0.15f))
            for (l in lines) {
                if (l in r.lines || l === r.number || l.bounds.horizontalOverlap(crop) <= 0f) continue
                if (l.bounds.centerY < bounds.top && l.bounds.bottom > crop.top) {
                    crop = Rect(crop.left, min(l.bounds.bottom, bounds.top + em * 0.1f), crop.right, crop.bottom)
                }
                if (l.bounds.centerY > bounds.bottom && l.bounds.top < crop.bottom) {
                    crop = Rect(crop.left, crop.top, crop.right, max(l.bounds.top, bounds.bottom - em * 0.1f))
                }
            }
            out += FigureRegion(
                crop.clamp(page.width, page.height), FigureKind.EQUATION, number,
                textSize = size, text = alt,
            )
            absorbed += r.lines
            r.number?.let { absorbed += it }
            usedPaths += r.bars
            usedPaths += r.frames
        }
        return Result(out, absorbed, usedPaths)
    }

    /**
     * The steps of a calculation are printed as a centred column of lines ("I = V/R",
     * "= 230/460", "= 0.5 A"). Stacked display formulas with little space between them are one
     * derivation and become one picture, so its steps keep their alignment and sizes, and a
     * last step without a fraction ("= 0.5 A") does not end up in the text that follows.
     * Formulas aligned with the prose (data lines, inline formulas) are left alone.
     */
    private fun mergeDerivations(regions: MutableList<Region>, lines: List<TextLine>, feats: Map<TextLine, Feat>, bodySize: Float) {
        if (regions.size < 2) return
        val prose = lines.filter { feats.getValue(it).prose }
        val textLeft = (prose.ifEmpty { lines }).minOf { it.bounds.left }
        fun display(r: Region): Boolean {
            val em = emOf(r, bodySize)
            if (r.bounds.left < textLeft + em * 2f) return false
            if (r.lines.any { feats.getValue(it).let { f -> f.prose || f.labelled } }) return false
            // A formula sharing its rows with prose is inline math.
            return prose.none { l -> l.bounds.verticalOverlap(r.bounds) > l.bounds.height * 0.5f }
        }
        var merged = true
        while (merged) {
            merged = false
            val sorted = regions.sortedBy { it.bounds.top }
            outer@ for (i in sorted.indices) {
                val a = sorted[i]
                if (!display(a)) continue
                for (j in i + 1 until sorted.size) {
                    val b = sorted[j]
                    val em = max(emOf(a, bodySize), emOf(b, bodySize))
                    val gap = b.bounds.top - a.bounds.bottom
                    if (gap > em * 1.6f) break
                    if (gap < -em * 0.3f || !display(b)) continue
                    val centred = abs(a.bounds.centerX - b.bounds.centerX) < max(a.bounds.width, b.bounds.width) * 0.75f + em * 2f
                    if (a.bounds.horizontalOverlap(b.bounds) <= 0f && !centred) continue
                    // Nothing else printed between the two.
                    val between = Rect(min(a.bounds.left, b.bounds.left), a.bounds.bottom, max(a.bounds.right, b.bounds.right), b.bounds.top)
                    if (gap > 0f && lines.any { l -> l !in a.lines && l !in b.lines && l !== a.number && l !== b.number && between.intersects(l.bounds, -0.5f) }) continue
                    a.lines += b.lines
                    a.labels += b.labels
                    a.wordy += b.wordy
                    a.bars += b.bars
                    if (a.number == null) a.number = b.number
                    a.bounds = a.bounds.union(b.bounds)
                    regions.remove(b)
                    merged = true
                    break@outer
                }
            }
        }
    }

    /**
     * The numbers printed beside a formula put together from its rows: "(9.9)" set between the
     * two identities it numbers (level with neither row), and two numbered formulas taken in as
     * one ("(4.9.2)" and "(4.9.3)"), which become two formulas again, each with its number.
     */
    private fun numberRows(regions: MutableList<Region>, lines: List<TextLine>, feats: Map<TextLine, Feat>, page: RawPage, free: (Rect) -> Boolean) {
        val taken = regions.mapNotNullTo(HashSet()) { it.number }
        for (r in regions.toList()) {
            val numbers = lines.filter { l ->
                feats.getValue(l).number && free(l.bounds) && (l === r.number || (l !in taken && regions.none { l in it.lines })) &&
                    l.bounds.left >= r.bounds.right - 1f && l.bounds.left - r.bounds.right <= page.width * 0.45f &&
                    l.bounds.centerY in r.bounds.top..r.bounds.bottom
            }.sortedBy { it.bounds.centerY }
            if (numbers.isEmpty()) continue
            if (numbers.size == 1) {
                if (r.number == null) {
                    r.number = numbers[0]
                    taken += numbers[0]
                }
                continue
            }
            // each number takes the rows nearest to it
            val cuts = numbers.zipWithNext { a, b -> (a.bounds.centerY + b.bounds.centerY) / 2f }
            fun part(y: Float) = cuts.count { y > it }
            val parts = numbers.map { Region(it.bounds).also { p -> p.number = it } }
            for (l in r.lines) {
                val p = parts[part(l.bounds.centerY)]
                p.lines += l
                if (l in r.labels) p.labels += l
                if (l in r.wordy) p.wordy += l
            }
            for (b in r.bars) parts[part(b.bounds.centerY)].bars += b
            if (parts.any { p -> p.lines.none { it !in p.labels } }) continue
            for (p in parts) p.bounds = Rect.bounding(p.lines.map { it.bounds } + p.bars.map { it.bounds })!!
            regions.remove(r)
            regions += parts
            taken += numbers
        }
        // A number between two formulas, level with neither ("(2.52)" beside a fraction and the
        // "= …" under it): the two are one formula with that number.
        for (n in lines) {
            if (!feats.getValue(n).number || n in taken || regions.any { n in it.lines } || !free(n.bounds)) continue
            val y = n.bounds.centerY
            val near = regions.filter { r ->
                r.number == null && n.bounds.left >= r.bounds.right - 1f && n.bounds.left - r.bounds.right <= page.width * 0.45f &&
                    max(r.bounds.top - y, y - r.bounds.bottom) <= n.fontSize * 1.2f
            }
            val above = near.filter { it.bounds.centerY < y }.maxByOrNull { it.bounds.bottom } ?: continue
            val below = near.filter { it.bounds.centerY > y }.minByOrNull { it.bounds.top } ?: continue
            if (below.bounds.top - above.bounds.bottom > n.fontSize * 1.6f || above.bounds.horizontalOverlap(below.bounds) <= 0f) continue
            above.lines += below.lines
            above.labels += below.labels
            above.wordy += below.wordy
            above.bars += below.bars
            above.bounds = above.bounds.union(below.bounds)
            above.number = n
            regions.remove(below)
            taken += n
        }
    }

    /**
     * "However,", "so that", "and", "resulting in" printed level with the lines of a derivation,
     * to the left of its formulas: the words belong to the derivation's picture.
     */
    private fun absorbRowLabels(regions: List<Region>, lines: List<TextLine>, bodySize: Float, proseLefts: List<Float>): Boolean {
        var any = false
        for (r in regions) {
            val em = emOf(r, bodySize)
            for (l in lines) {
                if (regions.any { l in it.lines } || !ROW_LABEL.matches(l.text.trim())) continue
                val t = l.text.trim()
                if (!(t.first().isLowerCase() || t.endsWith(',')) || t.count { it.isLetter() } < 2) continue
                // (printed where the lines of the text start, not a label of a drawing beside it)
                if (proseLefts.count { abs(it - l.bounds.left) <= em } < 2) continue
                // (measured from the formulas, not from labels taken in already)
                val left = r.lines.filter { it !in r.labels }.minOfOrNull { it.bounds.left } ?: r.bounds.left
                val gap = left - l.bounds.right
                val between = Rect(l.bounds.right, l.bounds.top, left, l.bounds.bottom)
                if (l.bounds.centerY !in r.bounds.top..r.bounds.bottom || gap < em * 0.5f || gap > em * 12f) continue
                if (lines.any { o -> o !== l && o !in r.lines && between.intersects(o.bounds, -0.5f) }) continue
                r.lines += l
                r.labels += l
                r.bounds = r.bounds.union(l.bounds)
                any = true
            }
        }
        return any
    }

    private fun emOf(r: Region, bodySize: Float): Float =
        r.lines.map { it.fontSize }.maxOrNull()?.coerceAtMost(bodySize * 1.6f) ?: bodySize

    /** Rows of a formula: groups of member lines sharing a baseline. */
    private fun rowCount(lines: Collection<TextLine>): Int {
        val sorted = lines.sortedBy { it.baseline }
        var rows = 0
        var last = Float.NEGATIVE_INFINITY
        for (l in sorted) {
            if (l.baseline - last > l.fontSize * 0.45f) rows++
            last = l.baseline
        }
        return rows
    }

    /** Rows of a formula when each has a relation sign (the equations of a system), else 0. */
    private fun equationRows(lines: Collection<TextLine>): Int {
        var rows = 0
        var last = Float.NEGATIVE_INFINITY
        var related = false
        for (l in lines.sortedBy { it.baseline }) {
            if (l.baseline - last > l.fontSize * 0.45f) {
                if (rows > 0 && !related) return 0
                rows++
                related = false
            }
            if (l.text.any { it in MathText.RELATIONS }) related = true
            last = l.baseline
        }
        return if (related) rows else 0
    }

    /** A one-row formula that is just a line of a paragraph (aligned with the prose around it). */
    private fun inParagraph(r: Region, lines: List<TextLine>, feats: Map<TextLine, Feat>, em: Float): Boolean {
        val first = r.lines.minByOrNull { it.bounds.left } ?: return false
        return lines.any { l ->
            if (l in r.lines || !feats.getValue(l).prose) return@any false
            val gap = max(r.bounds.top - l.bounds.bottom, l.bounds.top - r.bounds.bottom)
            gap in -1f..em * 0.75f && abs(l.bounds.left - first.bounds.left) < em * 1.2f
        }
    }

    /**
     * The next line of a worked formula: "= 160 px/m" under "D = 1920 / 12", starting with the
     * relation sign, directly below a stacked formula and overlapping it horizontally.
     */
    private fun continues(top: Region, next: Region, em: Float): Boolean {
        if (top.bars.isEmpty() || next.bars.isNotEmpty() || next.lines.size != 1) return false
        val text = next.lines.first().text.trimStart()
        if (!(text.startsWith("=") || text.startsWith("≈") || text.startsWith("→"))) return false
        val gap = next.bounds.top - top.bounds.bottom
        return gap in -1f..em * 1.6f && next.bounds.horizontalOverlap(top.bounds) > 0f
    }

    /** The formula starts with an option's label ("b.", "*f."). */
    private fun labelled(r: Region): Boolean = r.lines.minByOrNull { it.bounds.left }?.let { OPTION_LABEL.matches(it.text.trim()) } == true

    private fun mergeOverlapping(regions: MutableList<Region>, bodySize: Float) {
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in regions.indices) for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                val em = min(emOf(a, bodySize), emOf(b, bodySize))
                // (two options side by side, "e. … f. …", are two formulas)
                val (left, right) = if (a.bounds.left <= b.bounds.left) a to b else b to a
                val sameRows = a.bounds.verticalOverlap(b.bounds) > 0f &&
                    max(a.bounds.left - b.bounds.right, b.bounds.left - a.bounds.right) <= em * 3f &&
                    !(right.bounds.left >= left.bounds.right && labelled(right))
                val stacked = a.bounds.horizontalOverlap(b.bounds) > 0f &&
                    max(a.bounds.top - b.bounds.bottom, b.bounds.top - a.bounds.bottom) <= em * 0.35f
                if (sameRows || stacked || continues(a, b, em) || continues(b, a, em)) {
                    a.lines += b.lines
                    a.labels += b.labels
                    a.wordy += b.wordy
                    a.bars += b.bars
                    if (a.number == null) a.number = b.number
                    a.bounds = a.bounds.union(b.bounds)
                    regions.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
    }

    private companion object {
        /** Two words opening the line: "That is,", "Thus, from", "where the". */
        val SENTENCE_START = Regex("^\\s*\\p{L}{2,}[,:]?\\s+\\p{Ll}{2,}[\\s,:]")
        /** "b.", "*f.", "(c)": the label of an option in a list of exercises. */
        val OPTION_LABEL = Regex("^\\*?\\(?\\p{Ll}[.)]$")
        /** "a. (−0.001)²": an option of an exercise, with its label. */
        val OPTION_START = Regex("^\\s*\\*?\\(?\\p{Ll}[.)]\\s")
        /** A connective opening a sentence before its formula: "Thus, I1 = …", "Since ω0 = …". */
        val OPENER = Regex(
            "^\\s*(?:that is|thus|hence|since|then|therefore|so|where|also|similarly|here|if|when|from|using|" +
                "substituting|solving|but|now|again|note that|i\\.e\\.)[,:]?\\s",
        )
        val ROW_LABEL = Regex("^\\p{L}+,?(?:\\s+\\p{L}+,?){0,2}$")
        /** "(1.6)", "(4.9.2)", "(2.44b)", and in an appendix "(A.12)", "(E.3.3)". */
        val EQ_NUMBER = Regex("\\(\\s*(?:[0-9০-৯]{1,3}(?:[.\\-][0-9০-৯]{1,3}){0,2}|[A-Z](?:[.\\-][0-9]{1,3}){1,2})[a-z]?\\s*\\)")
    }
}
