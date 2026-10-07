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
            var w = 0
            var wordChars = 0
            for (token in text.split(' ', ' ')) {
                val t = token.trim { !it.isLetterOrDigit() }
                if (isWord(t)) {
                    w++
                    wordChars += t.length
                }
            }
            words = w
            rel = text.count { it in RELATIONS }
            big = text.count { it in BIG_OPERATORS }
            unknown = text.count { it == TextCleaner.PLACEHOLDER }
            val solid = text.count { !it.isWhitespace() }
            nonWordRatio = if (solid == 0) 0f else (solid - wordChars).toFloat() / solid
        }

        /** Short math piece: a numerator, a limit, a symbol, an equation number. */
        val fragment: Boolean get() = number || (words == 0 && text.length <= 40) || (words <= 1 && text.length <= 12)

        val seed: Boolean get() = !number && words <= 2 && nonWordRatio >= 0.4f && (rel > 0 || unknown > 0 || big > 0)

        val prose: Boolean get() = words >= 4
    }

    private class Region(var bounds: Rect) {
        val lines = LinkedHashSet<TextLine>()
        val bars = mutableListOf<VectorPath>()
        val frames = mutableListOf<VectorPath>()
        var number: TextLine? = null
    }

    fun detect(page: RawPage, lines: List<TextLine>, bodySize: Float, obstacles: List<Rect> = emptyList()): Result {
        if (lines.isEmpty()) return Result(emptyList(), emptySet(), emptySet())
        val feats = lines.associateWith { Feat(it) }
        val free = { r: Rect -> obstacles.none { it.intersects(r) } }

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
            fun over(l: TextLine) = l.bounds.horizontalOverlap(b) >= min(l.bounds.width, b.width) * 0.5f
            val num = lines.firstOrNull { l ->
                val em = l.fontSize
                over(l) && l.baseline in y - em * 1.0f..y - em * 0.05f && feats.getValue(l).fragment
            } ?: continue
            val den = lines.firstOrNull { l ->
                val em = l.fontSize
                over(l) && l.bounds.top in y - em * 0.25f..y + em * 0.6f && l.baseline > y && feats.getValue(l).fragment
            } ?: continue
            if (feats.getValue(num).words + feats.getValue(den).words > 1) continue
            // A fraction bar is centred under its numerator and barely wider than the wider
            // part; a table rule runs under a whole row of header cells.
            val em = max(num.fontSize, den.fontSize)
            val widest = max(num.bounds.width, den.bounds.width)
            if (b.width > widest * 1.6f + em || abs(num.bounds.centerX - b.centerX) > b.width * 0.3f + 2f ||
                abs(den.bounds.centerX - b.centerX) > b.width * 0.3f + 2f
            ) continue
            val r = Region(b.union(num.bounds).union(den.bounds))
            r.lines += num
            r.lines += den
            r.bars += bar
            regions += r
        }
        for (l in lines) {
            val f = feats.getValue(l)
            if (f.seed && free(l.bounds) && regions.none { l in it.lines }) {
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
                    if (sameRow && (f.fragment || f.seed) && hGap <= em * 2.5f) {
                        r.lines += l
                        r.bounds = r.bounds.union(l.bounds)
                        changed = true
                        continue
                    }
                    val vGap = max(0f, max(r.bounds.top - l.bounds.bottom, l.bounds.top - r.bounds.bottom))
                    val within = l.bounds.left >= r.bounds.left - em * 1.5f && l.bounds.right <= r.bounds.right + em * 1.5f
                    if (vGap <= em * 0.35f && within && (f.words == 0 || f.seed) && !f.number) {
                        r.lines += l
                        r.bounds = r.bounds.union(l.bounds)
                        changed = true
                    }
                }
            }
        }

        val out = mutableListOf<FigureRegion>()
        val absorbed = HashSet<TextLine>()
        val usedPaths = HashSet<VectorPath>()
        for (r in regions) {
            val members = r.lines.map { feats.getValue(it) }
            val em = emOf(r, bodySize)
            val rows = rowCount(r.lines)
            val stacked = rows >= 2 || r.bars.isNotEmpty()
            val special = members.any { it.unknown > 0 || it.big > 0 }
            // A numbered formula is display math even when it fits on one line.
            val numbered = r.number != null && members.any { it.rel > 0 }
            if (!stacked && !special && !numbered) continue
            if (members.sumOf { it.words } >= 5) continue
            if (!stacked && !numbered && inParagraph(r, lines, feats, em)) continue
            // A line of prose sharing the rows of the formula: it is inline math, keep the text.
            val prose = lines.any { l ->
                l !in r.lines && l !== r.number && feats.getValue(l).prose &&
                    l.bounds.verticalOverlap(r.bounds) > l.bounds.height * 0.5f &&
                    abs(l.bounds.centerX - r.bounds.centerX) < page.width * 0.35f
            }
            if (prose) continue
            // Labels inside a drawing ("R1 = 10 Ω" next to a resistor) belong to the figure.
            val near = r.bounds.outset(em)
            if (page.images.any { it.bounds.intersects(near) }) continue
            val shapes = page.paths.filter { p ->
                p !in r.bars && p.bounds.intersects(near) && !p.bounds.contains(r.bounds.outset(-1f)) && p.bounds.width < page.width * 0.8f
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
            out += FigureRegion(
                bounds.outset(min(2f, em * 0.15f)).clamp(page.width, page.height), FigureKind.EQUATION, number,
                textSize = size, text = alt,
            )
            absorbed += r.lines
            r.number?.let { absorbed += it }
            usedPaths += r.bars
            usedPaths += r.frames
        }
        return Result(out, absorbed, usedPaths)
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

    /** A one-row formula that is just a line of a paragraph (aligned with the prose around it). */
    private fun inParagraph(r: Region, lines: List<TextLine>, feats: Map<TextLine, Feat>, em: Float): Boolean {
        val first = r.lines.minByOrNull { it.bounds.left } ?: return false
        return lines.any { l ->
            if (l in r.lines || !feats.getValue(l).prose) return@any false
            val gap = max(r.bounds.top - l.bounds.bottom, l.bounds.top - r.bounds.bottom)
            gap in -1f..em * 0.75f && abs(l.bounds.left - first.bounds.left) < em * 1.2f
        }
    }

    private fun mergeOverlapping(regions: MutableList<Region>, bodySize: Float) {
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in regions.indices) for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                val em = min(emOf(a, bodySize), emOf(b, bodySize))
                val sameRows = a.bounds.verticalOverlap(b.bounds) > 0f &&
                    max(a.bounds.left - b.bounds.right, b.bounds.left - a.bounds.right) <= em * 3f
                val stacked = a.bounds.horizontalOverlap(b.bounds) > 0f &&
                    max(a.bounds.top - b.bounds.bottom, b.bounds.top - a.bounds.bottom) <= em * 0.35f
                if (sameRows || stacked) {
                    a.lines += b.lines
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
        const val RELATIONS = "=≈≠≤≥<>≜≡∝→⇒⟹↔≅∼≃"
        const val BIG_OPERATORS = "∫∬∭∮∑∏√∂∇∞"
        val EQ_NUMBER = Regex("\\(\\s*[0-9০-৯]{1,3}(?:[.\\-][0-9০-৯]{1,3}){0,2}[a-z]?\\s*\\)")
        val MATH_FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "log", "exp", "lim", "max", "min", "det",
            "sinh", "cosh", "tanh", "arg", "mod", "rms", "avg")

        /** A word of prose: three Latin letters in a row (not a function name), or a Bangla word. */
        fun isWord(t: String): Boolean {
            if (t.length < 2) return false
            var latin = 0
            var maxLatin = 0
            var bangla = 0
            for (c in t) {
                if (c in 'a'..'z' || c in 'A'..'Z') {
                    latin++
                    maxLatin = max(maxLatin, latin)
                } else {
                    latin = 0
                }
                if (c in 'অ'..'হ' || c in 'ড়'..'য়') bangla++
            }
            if (bangla >= 2) return true
            return maxLatin >= 3 && t.lowercase() !in MATH_FUNCTIONS
        }
    }
}
