package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.Chunk
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.text.BijoyConverter
import com.raptibooks.core.structure.text.TextCleaner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Groups positioned text fragments into lines.
 *
 * * Fragments sharing a baseline form a row; superscripts and drop caps are folded into the
 *   row they belong to.
 * * Within a row, content-stream order is kept for overlapping glyphs (Bangla vowel signs and
 *   other marks are drawn on top of their base glyph) and x-order is enforced otherwise.
 * * A row is split into separate [TextLine]s at gaps wider than [segmentGapEm] (column gutters,
 *   table cells); narrower gaps above [chunkGapEm] are recorded as [Chunk]s for table detection.
 */
class LineBuilder(
    private val segmentGapEm: Float = 1.6f,
    private val chunkGapEm: Float = 0.8f,
) {
    /**
     * Whether the current page's text layer has real space glyphs. Set per page by
     * [PageAnalyzer] so that small fragment sets (table cells) use the page's convention;
     * null = decide per line.
     */
    var explicitSpacesHint: Boolean? = null

    private class Frag(val f: TextFragment, val order: Int) {
        val left get() = f.left
        val right get() = f.right
        val size get() = f.fontSize
    }

    private class Row(val frags: MutableList<Frag>) {
        var baseline = frags.first().f.baseline
        val size: Float get() = medianSize(frags)
        val top: Float get() = frags.minOf { it.f.top }
        val bottom: Float get() = frags.maxOf { it.f.bottom }
        val left: Float get() = frags.minOf { it.left }
        val right: Float get() = frags.maxOf { it.right }
        val charCount: Int get() = frags.sumOf { it.f.text.trim().length }
    }

    /** Text in scripts whose glyph advances are reliable (Latin, Greek, Cyrillic, digits, math). */
    private fun simpleScript(text: String): Boolean = text.all { it.code < 0x0590 || it.code in 0x1D00..0x2BFF || it == TextCleaner.PLACEHOLDER }

    /**
     * @param splitColumns split rows at the page's column gutters even where the gutter is
     *        narrower than [segmentGapEm] (a margin column of captions and notes next to the text).
     */
    fun build(fragments: List<TextFragment>, splitColumns: Boolean = false): List<TextLine> {
        val frags = fragments.mapIndexedNotNull { i, f ->
            if (f.text.isEmpty() || f.fontSize <= 0f || f.right < f.left) null else Frag(f, i)
        }
        if (frags.isEmpty()) return emptyList()
        val rows = groupRows(frags)
        mergeSuperscripts(rows)
        mergeDropCaps(rows)
        var segments = rows.sortedBy { it.baseline }.flatMap { splitSegments(orderRow(it.frags)) }
        segments = attachStrayScripts(segments)
        if (splitColumns && segments.size >= 6) segments = splitAtGutters(segments)
        return segments.mapNotNull { buildLine(it) }
    }

    /**
     * An exponent that landed on the baseline row of another column ("10¹⁸" in a margin table
     * next to a line of text) is put back right after the number it belongs to.
     */
    private fun attachStrayScripts(segments: List<List<Frag>>): List<List<Frag>> {
        val out = segments.map { it.toMutableList() }.toMutableList()
        val moved = HashSet<Int>()
        for ((k, seg) in out.withIndex()) {
            val solid = seg.filter { !it.f.isSpace }
            if (solid.isEmpty() || solid.size > 4) continue
            val size = solid.maxOf { it.size }
            val left = solid.minOf { it.left }
            val base = solid.first().f.baseline
            val target = out.indices.firstOrNull { j ->
                if (j == k || j in moved) return@firstOrNull false
                val t = out[j].filter { !it.f.isSpace }
                if (t.isEmpty()) return@firstOrNull false
                val tSize = medianSize(t)
                val tBase = t.map { it.f.baseline }.sorted()[t.size / 2]
                size <= tSize * 0.87f && abs(t.maxOf { it.right } - left) <= tSize * 0.15f &&
                    base < tBase - tSize * 0.15f && base > tBase - tSize * 0.9f
            } ?: continue
            out[target] = orderRow(out[target] + seg).toMutableList()
            moved += k
        }
        return out.filterIndexed { i, _ -> i !in moved }
    }

    private fun solidBounds(segment: List<Frag>): Rect? {
        val solid = segment.filter { !it.f.isSpace }
        if (solid.isEmpty()) return null
        return Rect(solid.minOf { it.left }, solid.minOf { it.f.top }, solid.maxOf { it.right }, solid.maxOf { it.f.bottom })
    }

    /**
     * A row running across a narrow gutter (a caption line in the margin column on the same
     * baseline as a line of text) is split where the text after the gap starts at the left edge
     * of the column the lines above and below start at, and nothing nearby crosses the gap.
     */
    private fun splitAtGutters(segments: List<List<Frag>>): List<List<Frag>> {
        val bounds = segments.map { solidBounds(it) }
        val out = ArrayList<List<Frag>>(segments.size)
        for ((k, seg) in segments.withIndex()) {
            val b = bounds[k]
            if (b == null) {
                out += seg
                continue
            }
            val em = medianSize(seg.filter { !it.f.isSpace })
            val near = bounds.filterIndexed { j, o -> j != k && o != null && abs(o.centerY - b.centerY) <= em * 4.5f }.map { it!! }
            fun columnEdge(x: Float) = near.count { abs(it.left - x) <= 2f } >= 2
            fun crossed(x: Float) = near.any { it.left < x - 1f && it.right > x + 1f }
            var current = mutableListOf<Frag>()
            var maxRight = -Float.MAX_VALUE
            for (fr in seg) {
                if (!fr.f.isSpace && maxRight > -Float.MAX_VALUE) {
                    val gap = fr.left - maxRight
                    val mid = (maxRight + fr.left) / 2f
                    // a hanging number or bullet ("2.7   Find the…") stays with its text
                    val marker = current.filter { !it.f.isSpace }.joinToString("") { it.f.text }.trim().let { MARKER_ONLY.matches(it) }
                    if (gap >= em * chunkGapEm && !marker && columnEdge(fr.left) && !crossed(mid) &&
                        near.count { it.right <= maxRight + 2f } >= 1
                    ) {
                        out += current
                        current = mutableListOf()
                        maxRight = -Float.MAX_VALUE
                    }
                }
                current += fr
                if (!fr.f.isSpace) maxRight = max(maxRight, fr.right)
            }
            if (current.any { !it.f.isSpace }) out += current
        }
        return out
    }

    /**
     * Builds text for an arbitrary fragment set (e.g. one table cell), lines joined by spaces.
     * With [unicodeScripts], superscript/subscript digits become Unicode scripts ("10¹⁸"),
     * since cells carry no styles.
     */
    fun assembleText(fragments: List<TextFragment>, unicodeScripts: Boolean = false): String =
        build(fragments).sortedWith(compareBy({ it.baseline }, { it.bounds.left }))
            .joinToString(" ") { if (unicodeScripts) scriptText(it) else it.text }.trim()

    private fun groupRows(frags: List<Frag>): MutableList<Row> {
        val sorted = frags.sortedWith(compareBy({ it.f.baseline }, { it.left }))
        val rows = mutableListOf<Row>()
        var current: Row? = null
        var baseSum = 0f
        var count = 0
        for (fr in sorted) {
            val row = current
            if (row != null) {
                val tol = 0.3f * min(fr.size, row.frags.last().size).coerceAtLeast(1f)
                if (abs(fr.f.baseline - row.baseline) <= tol) {
                    row.frags += fr
                    baseSum += fr.f.baseline
                    count++
                    row.baseline = baseSum / count
                    continue
                }
            }
            current = Row(mutableListOf(fr))
            baseSum = fr.f.baseline
            count = 1
            rows += current
        }
        return rows
    }

    private fun mergeSuperscripts(rows: MutableList<Row>) {
        var changed = true
        while (changed) {
            changed = false
            val sorted = rows.sortedBy { it.baseline }
            for ((i, r) in sorted.withIndex()) {
                if (r.charCount > 12) continue
                val rSize = r.size
                // Not only the rows right above and below: a line of another column may sit in between.
                val candidates = (maxOf(0, i - 4)..minOf(sorted.size - 1, i + 4)).filter { it != i }.map { sorted[it] }
                val target = candidates.filter { n ->
                    rSize <= n.size * 0.87f &&
                        r.baseline > n.baseline - n.size * 0.9f && r.baseline < n.baseline + n.size * 0.45f &&
                        r.left < n.right + n.size && r.right > n.left - n.size
                }.minByOrNull { abs(it.baseline - r.baseline) } ?: continue
                target.frags += r.frags
                rows.remove(r)
                changed = true
                break
            }
        }
    }

    private fun mergeDropCaps(rows: MutableList<Row>) {
        if (rows.size < 2) return
        val pageMedian = medianSize(rows.flatMap { it.frags })
        for (r in rows.toList()) {
            val text = r.frags.joinToString("") { it.f.text }.trim()
            if (text.isEmpty() || text.length > 2 || r.size < pageMedian * 1.8f) continue
            val target = rows.filter { it !== r && it.size < r.size * 0.6f }
                .filter { it.left >= r.right - 2f && it.left - r.right < pageMedian * 2.5f }
                .filter { it.baseline > r.top && it.baseline < r.bottom + pageMedian }
                .minByOrNull { it.baseline } ?: continue
            val size = target.size
            for (fr in r.frags) {
                val f = fr.f
                val copy = TextFragment(
                    f.text, target.left - size * 0.6f * f.text.length, target.frags.first().f.top,
                    target.left - 0.01f, target.frags.first().f.bottom, target.baseline, size,
                    f.fontName, f.bold, f.italic, size * 0.25f,
                )
                target.frags += Frag(copy, -1)
            }
            rows.remove(r)
        }
    }

    /**
     * Content order, but a fragment that lies completely left of its predecessor is moved left
     * until order is restored. Overlapping fragments (marks) keep their drawing order.
     */
    private fun orderRow(frags: List<Frag>): List<Frag> {
        val out = ArrayList<Frag>(frags.size)
        for (fr in frags.sortedBy { it.order }) {
            var pos = out.size
            val tol = max(0.5f, fr.f.width * 0.25f)
            while (pos > 0) {
                val prev = out[pos - 1]
                if (fr.right <= prev.left + tol && fr.left < prev.left - tol) pos-- else break
            }
            out.add(pos, fr)
        }
        return out
    }

    private fun splitSegments(ordered: List<Frag>): List<List<Frag>> {
        val segments = mutableListOf<MutableList<Frag>>()
        var current = mutableListOf<Frag>()
        var lastSolid: Frag? = null
        var maxRight = -Float.MAX_VALUE
        for (fr in ordered) {
            if (fr.f.isSpace) {
                current += fr
                continue
            }
            val prev = lastSolid
            if (prev != null) {
                val em = max(prev.size, fr.size)
                if (fr.left - maxRight > segmentGapEm * em) {
                    segments += current
                    current = mutableListOf()
                    maxRight = -Float.MAX_VALUE
                }
            }
            current += fr
            lastSolid = fr
            maxRight = max(maxRight, fr.right)
        }
        if (current.isNotEmpty()) segments += current
        return segments.filter { seg -> seg.any { !it.f.isSpace } }
    }

    private fun buildLine(segment: List<Frag>): TextLine? {
        val solid = segment.filter { !it.f.isSpace }
        if (solid.isEmpty()) return null
        var lineSize = medianSize(solid.filter { it.order >= 0 }.ifEmpty { solid })
        // "10⁻¹²": more characters in the raised exponent than in the number it belongs to.
        val big = solid.filter { it.size >= lineSize * 1.25f }
        if (big.isNotEmpty()) {
            val bigSize = medianSize(big)
            val bigBase = big.map { it.f.baseline }.sorted()[big.size / 2]
            val small = solid.filter { it.size < bigSize * 0.87f }
            val smallBase = small.map { it.f.baseline }.sorted()[small.size / 2]
            if (smallBase < bigBase - bigSize * 0.15f || smallBase > bigBase + bigSize * 0.08f) lineSize = bigSize
        }
        val main = solid.filter { it.size >= lineSize * 0.8f }.ifEmpty { solid }
        val baseline = main.map { it.f.baseline }.sorted()[main.size / 2]

        val sb = StringBuilder()
        val ranges = ArrayList<IntArray>() // start, end, flags
        val chunks = ArrayList<Chunk>()
        var chunkStart = 0
        var chunkLeft = solid.first().left
        var chunkRight = chunkLeft
        var prev: Frag? = null
        // Gaps are measured from the furthest right edge so far: combining marks (Bangla vowel
        // signs, nukta) are zero-width glyphs placed inside their base glyph.
        var maxRight = -Float.MAX_VALUE
        var pendingSpace = false
        // When the text layer contains real space glyphs, trust them: glyph advances of
        // complex scripts (Bangla clusters positioned by the shaper) understate their extent,
        // so gap-based inference would split words. Without spaces (e.g. LaTeX), infer.
        val explicitSpaces = explicitSpacesHint ?: segment.any { it.f.isSpace }
        var startNewChunk = false
        var boldChars = 0
        var italicChars = 0
        var totalChars = 0
        val fontChars = HashMap<String, Int>()

        // Bijoy (ANSI Bangla) runs are converted as a whole so that reordering works per word.
        var bijoyRun: StringBuilder? = null
        var bijoyFlags = 0
        fun flushBijoy() {
            val run = bijoyRun ?: return
            val raw = run.toString()
            val converted = if (BijoyConverter.needsConversion(raw)) BijoyConverter.convert(raw) else raw
            val start = sb.length
            sb.append(converted)
            if (bijoyFlags != 0) ranges += intArrayOf(start, sb.length, bijoyFlags)
            bijoyRun = null
        }

        for (fr in segment) {
            val f = fr.f
            if (f.isSpace) {
                pendingSpace = true
                continue
            }
            val text = TextCleaner.clean(f.text)
            if (text.isEmpty()) continue
            val p = prev
            var needSpace = false
            if (p != null) {
                val gap = f.left - maxRight
                val sw = min(p.f.spaceWidth, f.spaceWidth).takeIf { it > 0f } ?: (lineSize * 0.25f)
                val inferred = (sw * 0.5f).coerceIn(lineSize * 0.1f, lineSize * 0.3f)
                // Explicit spaces are trusted for complex scripts only: Latin typesetters drop
                // the space glyph in tightly justified lines and just move the next word.
                val threshold = when {
                    !explicitSpaces -> inferred
                    simpleScript(p.f.text) && simpleScript(text) -> max(inferred, lineSize * 0.15f)
                    else -> lineSize * 0.6f
                }
                needSpace = pendingSpace || gap > threshold
                if (gap > chunkGapEm * lineSize) {
                    flushBijoy()
                    chunks += Chunk(chunkLeft, chunkRight, chunkStart, sb.length)
                    startNewChunk = true
                    chunkLeft = f.left
                }
            }
            val isBijoy = BijoyConverter.isBijoyFont(f.fontName)
            var flags = 0
            if (f.bold) flags = flags or InlineStyle.BOLD
            if (f.italic) flags = flags or InlineStyle.ITALIC
            if (f.fontSize < lineSize * 0.87f) {
                if (f.baseline < baseline - lineSize * 0.15f) flags = flags or InlineStyle.SUPERSCRIPT
                else if (f.baseline > baseline + lineSize * 0.08f) flags = flags or InlineStyle.SUBSCRIPT
            }
            if (isBijoy) {
                val run = bijoyRun ?: StringBuilder().also {
                    if (needSpace && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    if (startNewChunk) {
                        chunkStart = sb.length
                        startNewChunk = false
                    }
                    bijoyRun = it
                    bijoyFlags = flags
                    needSpace = false
                }
                if (needSpace && run.isNotEmpty()) run.append(' ')
                run.append(text)
            } else {
                flushBijoy()
                if (needSpace && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                if (startNewChunk) {
                    chunkStart = sb.length
                    startNewChunk = false
                }
                val start = sb.length
                sb.append(text)
                if (flags != 0) ranges += intArrayOf(start, sb.length, flags)
            }
            val n = text.length
            totalChars += n
            if (f.bold) boldChars += n
            if (f.italic) italicChars += n
            fontChars.merge(f.fontName, n, Int::plus)
            chunkRight = max(chunkRight, f.right)
            maxRight = max(maxRight, f.right)
            prev = fr
            pendingSpace = false
        }
        flushBijoy()
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isBlank()) return null
        chunks += Chunk(chunkLeft, chunkRight, chunkStart.coerceAtMost(sb.length), sb.length)

        val bounds = Rect(
            solid.minOf { it.left }, solid.minOf { it.f.top },
            solid.maxOf { it.right }, solid.maxOf { it.f.bottom },
        )
        return TextLine(
            text = sb.toString(),
            styles = mergeRanges(ranges, sb),
            bounds = bounds,
            baseline = baseline,
            fontSize = lineSize,
            boldRatio = if (totalChars == 0) 0f else boldChars.toFloat() / totalChars,
            italicRatio = if (totalChars == 0) 0f else italicChars.toFloat() / totalChars,
            fontName = fontChars.maxByOrNull { it.value }?.key ?: "",
            chunks = if (chunks.size > 1) chunks else emptyList(),
        )
    }

    private fun mergeRanges(ranges: List<IntArray>, text: CharSequence): List<InlineStyle> {
        if (ranges.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>()
        for (r in ranges.sortedBy { it[0] }) {
            val last = out.lastOrNull()
            if (last != null && last[2] == r[2]) {
                val between = text.subSequence(last[1].coerceAtMost(r[0]), r[0])
                if (between.isBlank()) {
                    last[1] = max(last[1], r[1])
                    continue
                }
            }
            out += r.copyOf()
        }
        return out.filter { it[1] > it[0] }.map { InlineStyle(it[0], it[1].coerceAtMost(text.length), it[2]) }
    }

    companion object {
        private const val SUPER_FROM = "0123456789+-−=()ni"
        private const val SUPER_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁻⁼⁽⁾ⁿⁱ"
        private const val SUB_FROM = "0123456789+-−=()aeox"
        private const val SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₋₌₍₎ₐₑₒₓ"

        /**
         * The line's text with superscript/subscript runs written as Unicode script characters
         * where possible (one character for one, so offsets stay valid).
         */
        fun scriptText(line: TextLine): String {
            if (line.styles.none { it.superscript || it.subscript }) return line.text
            val chars = line.text.toCharArray()
            for (s in line.styles) {
                val sup = s.superscript
                if (!sup && !s.subscript) continue
                val from = if (sup) SUPER_FROM else SUB_FROM
                val to = if (sup) SUPER_TO else SUB_TO
                val a = s.start.coerceIn(0, chars.size)
                val b = s.end.coerceIn(a, chars.size)
                if ((a until b).any { chars[it] != ' ' && from.indexOf(chars[it]) < 0 }) continue
                for (k in a until b) {
                    val i = from.indexOf(chars[k])
                    if (i >= 0) chars[k] = to[i]
                }
            }
            return String(chars)
        }

        private val MARKER_ONLY = Regex("^[(\\[]?[0-9০-৯]{1,3}(?:\\.[0-9০-৯]{1,3})*[.)\\]]?$|^[•●○◦▪■♦◆►▸*–-]$|^[(]?[a-zA-Z][.)]$")

        private fun medianSize(frags: List<Frag>): Float {
            if (frags.isEmpty()) return 0f
            // Character-weighted median so that a few large glyphs don't dominate.
            val weighted = frags.map { it.size to max(1, it.f.text.trim().length) }.sortedBy { it.first }
            val total = weighted.sumOf { it.second }
            var acc = 0
            for ((size, w) in weighted) {
                acc += w
                if (acc * 2 > total) return size // ties go to the larger size (base text vs. its scripts)
            }
            return weighted.last().first
        }
    }
}
