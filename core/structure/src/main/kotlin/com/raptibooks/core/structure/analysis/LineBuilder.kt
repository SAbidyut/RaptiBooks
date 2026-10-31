package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.Chunk
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.text.BijoyConverter
import com.raptibooks.core.structure.text.RtlText
import com.raptibooks.core.structure.text.TextCleaner
import com.raptibooks.core.structure.text.UnreadableText
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
        pageTrustsSpaces = scanSpacing(segments)
        if (splitColumns) scannedPage = pageTrustsSpaces
        return segments.mapNotNull { buildLine(it) }.also { pageTrustsSpaces = false }
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
        val starts = segments.map { gapStarts(it) }
        val out = ArrayList<List<Frag>>(segments.size)
        for ((k, seg) in segments.withIndex()) {
            val b = bounds[k]
            if (b == null) {
                out += seg
                continue
            }
            val em = medianSize(seg.filter { !it.f.isSpace })
            val nearIx = bounds.indices.filter { j -> j != k && bounds[j] != null && abs(bounds[j]!!.centerY - b.centerY) <= em * 4.5f }
            val near = nearIx.map { bounds[it]!! }
            fun columnEdge(x: Float, rest: () -> String): Boolean {
                val n = near.count { abs(it.left - x) <= 2f }
                // text after a gap at the same place in the rows around it (a margin caption
                // printed on the baselines of the text lines)
                val beside = nearIx.flatMap { starts[it] }.filter { abs(it.first - x) <= 2f }
                // a caption in the margin column ("Figure 1.6") may have one line under it
                return n >= 2 || (Patterns.caption.containsMatchIn(rest()) && n + beside.size >= 1) || beside.any { it.second } ||
                    // "Practice Problem 15.15" in the margin column right of a line of text
                    NUMBERED_LABEL.matches(rest().trim())
            }
            // (a row that also breaks there, beside the same margin column, does not cross it)
            fun crossed(x: Float, resume: Float) = nearIx.any { j ->
                bounds[j]!!.let { it.left < x - 1f && it.right > x + 1f } && starts[j].none { abs(it.first - resume) <= 2f }
            }
            var current = mutableListOf<Frag>()
            var maxRight = -Float.MAX_VALUE
            for (fr in seg) {
                if (!fr.f.isSpace && maxRight > -Float.MAX_VALUE) {
                    val gap = fr.left - maxRight
                    val mid = (maxRight + fr.left) / 2f
                    // a hanging number or bullet ("2.7   Find the…") stays with its text
                    val marker = current.filter { !it.f.isSpace }.joinToString("") { it.f.text }.trim().let { MARKER_ONLY.matches(it) }
                    val restText = { seg.subList(seg.indexOf(fr), seg.size).joinToString("") { if (it.f.isSpace) " " else it.f.text } }
                    // ("Practice Problem 12.15" in the margin, level with the first line of its text)
                    val label = { NUMBERED_LABEL.matches(current.joinToString("") { if (it.f.isSpace) " " else it.f.text }) }
                    // A caption ("Figure 3.117", bold, larger) right after a label of the drawing
                    // beside it ("2 A"), set in another font: the caption starts its own line.
                    val prevSolid = current.lastOrNull { !it.f.isSpace }
                    val captionStart = prevSolid != null && !marker && gap >= em * 0.4f && CAPTION_START.containsMatchIn(restText()) &&
                        (fr.f.bold != prevSolid.f.bold || abs(fr.f.fontSize - prevSolid.f.fontSize) >= prevSolid.f.fontSize * 0.12f)
                    // A running head in the margin beside a line of text ("…from the n-type
                    // SEMICONDUCTOR DIODE 13", "276 BJT AC ANALYSIS   But"): capitals in another
                    // face and size.
                    fun caps(r: String) = r.split(' ').count { it.isNotBlank() } <= 5 && r.none { it.isLowerCase() } && r.count { it.isUpperCase() } >= 4
                    fun prose(r: String) = r.split(' ').count { w -> w.count { it.isLowerCase() } >= 2 } >= 3
                    val headStart = prevSolid != null && !marker && gap >= em * 1.2f && fr.f.fontName != prevSolid.f.fontName &&
                        abs(fr.f.fontSize - prevSolid.f.fontSize) >= prevSolid.f.fontSize * 0.08f &&
                        current.joinToString("") { if (it.f.isSpace) " " else it.f.text }.let { before ->
                            (caps(restText()) && prose(before)) || (caps(before) && prose(restText()))
                        }
                    if (captionStart || headStart || gap >= em * chunkGapEm && !marker && columnEdge(fr.left, restText) && !crossed(mid, fr.left) &&
                        (near.count { it.right <= maxRight + 2f } >= 1 || label())
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

    /** Where text resumes after a wide gap in [segment], and whether a caption starts there. */
    private fun gapStarts(segment: List<Frag>): List<Pair<Float, Boolean>> {
        val solid = segment.filter { !it.f.isSpace }
        if (solid.size < 2) return emptyList()
        val em = medianSize(solid)
        val out = mutableListOf<Pair<Float, Boolean>>()
        var maxRight = solid[0].right
        for (fr in solid.drop(1)) {
            if (fr.left - maxRight >= em * chunkGapEm) {
                val rest = segment.subList(segment.indexOf(fr), segment.size).joinToString("") { if (it.f.isSpace) " " else it.f.text }
                out += fr.left to Patterns.caption.containsMatchIn(rest)
            }
            maxRight = max(maxRight, fr.right)
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

    /** The size of a row that is one capital letter with its subscripts right after it ("VCC"). */
    private fun symbolSize(r: Row): Float? {
        val solid = r.frags.filter { !it.f.isSpace }.sortedBy { it.left }
        val base = solid.firstOrNull() ?: return null
        if (solid.size < 2 || base.f.text.singleOrNull()?.isUpperCase() != true) return null
        val subscripts = solid.drop(1)
        if (subscripts.any { it.size > base.size * 0.87f || it.f.baseline < base.f.baseline + base.size * 0.1f }) return null
        return if (solid.zipWithNext().all { (a, b) -> b.left - a.right <= base.size * 0.15f }) base.size else null
    }

    private fun mergeSuperscripts(rows: MutableList<Row>) {
        // A row runs across the page: its pieces are judged on their own (the subscripts of a
        // line in one column share a baseline with the labels of a figure in the other).
        val pieces = rows.flatMap { r ->
            val sorted = r.frags.sortedBy { it.left }
            val gap = max(r.size * 3f, 12f)
            val out = mutableListOf(mutableListOf(sorted.first()))
            var right = sorted.first().right
            for (fr in sorted.drop(1)) {
                if (!fr.f.isSpace && fr.left - right > gap) out += mutableListOf<Frag>()
                out.last() += fr
                right = max(right, fr.right)
            }
            out.map { Row(it).also { p -> p.baseline = r.baseline } }
        }
        rows.clear()
        rows.addAll(pieces)
        var changed = true
        while (changed) {
            changed = false
            val sorted = rows.sortedBy { it.baseline }
            for ((i, r) in sorted.withIndex()) {
                if (r.charCount > 12) continue
                // (a symbol and its subscript, "VCC" over a fraction bar, is as big as the symbol)
                val rSize = symbolSize(r) ?: r.size
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

    /** The page's text comes from a scan (see [buildLine]): only its space glyphs part words. */
    private var pageTrustsSpaces = false

    /** The page last built in full ([build] with columns) has text made from a scan. */
    private var scannedPage = false

    /**
     * A page whose lines set their words unevenly apart, as measured across their space glyphs:
     * glyph places are unreliable there (text an OCR laid over a scan, like Acrobat's
     * ClearScan). A typeset line keeps the same distance between all its words.
     */
    private fun scanSpacing(segments: List<List<Frag>>): Boolean {
        val spread = ArrayList<Float>()
        // (and some of its space glyphs overlap the letter after them)
        var overlapping = 0
        for (seg in segments) {
            val gaps = ArrayList<Float>()
            for ((i, fr) in seg.withIndex()) {
                if (!fr.f.isSpace) continue
                val before = seg.subList(0, i).lastOrNull { !it.f.isSpace } ?: continue
                val after = seg.subList(i + 1, seg.size).firstOrNull { !it.f.isSpace } ?: continue
                gaps += after.left - before.right
                if (after.left - before.right < (fr.f.spaceWidth.takeIf { it > 0f } ?: (fr.f.fontSize * 0.25f)) * 0.35f) overlapping++
            }
            if (gaps.size < 4) continue
            val mean = gaps.average().toFloat()
            if (mean <= 0f) continue
            val sd = kotlin.math.sqrt(gaps.sumOf { ((it - mean) * (it - mean)).toDouble() } / gaps.size).toFloat()
            spread += sd / mean
        }
        return spread.size >= 6 && spread.sorted()[spread.size / 2] >= 0.2f && overlapping >= 5
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
        // A tightly justified line set without any space glyph ("Ifthis29thelectrongains…"): its
        // words are parted by less than the usual gap, but by clearly more than its letters.
        val tightGap = if (explicitSpaces && solid.size == segment.size && solid.size >= 15) {
            val gaps = solid.zipWithNext { a, b -> b.left - a.right }.sorted()
            (gaps[gaps.size / 2] + lineSize * 0.08f).coerceAtLeast(lineSize * 0.1f)
        } else {
            null
        }
        // Where the glyphs' places are unreliable (text made from a scan: the gaps between letters
        // say nothing), the space glyphs alone part the words.
        val trustSpaces = pageTrustsSpaces && segment.any { it.f.isSpace }
        var startNewChunk = false
        // where each glyph's text starts (right-to-left lines are put in reading order by glyph)
        val glyphStarts = ArrayList<Int>()
        // where each character was printed
        val charLeft = ArrayList<Float>()
        val charRight = ArrayList<Float>()
        fun place(upTo: Int, left: Float, right: Float) {
            while (charLeft.size < upTo) { charLeft += left; charRight += right }
        }
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
                    simpleScript(p.f.text) && simpleScript(text) ->
                        min(max(inferred, lineSize * 0.15f), tightGap ?: Float.MAX_VALUE)
                    else -> lineSize * 0.6f
                }
                // (where only the space glyphs are trusted, an operator set apart still has its
                // spaces: "+VCC − IBRB − VBE = 0" in a book whose formulas have no space glyphs)
                val operator = trustSpaces && gap > lineSize * 0.3f && (p.f.text in OPERATORS || text in OPERATORS)
                needSpace = pendingSpace || (!trustSpaces && gap > threshold) || operator
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
                place(sb.length, maxRight, f.left)
                if (startNewChunk) {
                    chunkStart = sb.length
                    startNewChunk = false
                }
                val start = sb.length
                sb.append(text)
                place(sb.length, f.left, f.right)
                glyphStarts += start
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
        var lineChunks: List<Chunk> = chunks
        var lineRanges: List<IntArray> = ranges
        val positions = charLeft.size >= sb.length
        if (RtlText.hasRtl(sb)) {
            val logical = toReadingOrder(sb, glyphStarts, ranges, chunks)
            sb.setLength(0)
            sb.append(logical.first)
            lineRanges = logical.second
            lineChunks = logical.third
        }

        val bounds = Rect(
            solid.minOf { it.left }, solid.minOf { it.f.top },
            solid.maxOf { it.right }, solid.maxOf { it.f.bottom },
        )
        val built = TextLine(
            text = sb.toString(),
            styles = mergeRanges(lineRanges, sb),
            bounds = bounds,
            baseline = baseline,
            fontSize = lineSize,
            boldRatio = if (totalChars == 0) 0f else boldChars.toFloat() / totalChars,
            italicRatio = if (totalChars == 0) 0f else italicChars.toFloat() / totalChars,
            fontName = fontChars.maxByOrNull { it.value }?.key ?: "",
            chunks = if (lineChunks.size > 1) lineChunks else emptyList(),
        )
        if (positions && !RtlText.hasRtl(sb)) {
            charPlaces[built] = charLeft.subList(0, sb.length).toFloatArray() to charRight.subList(0, sb.length).toFloatArray()
        }
        return built
    }

    /** Starts a line of words shown as printed from a right-to-left script. */
    private val RTL_MARK = '\u200F'

    /** Where the characters of the lines built since [pictureWords] last ran were printed. */
    private val charPlaces = java.util.IdentityHashMap<TextLine, Pair<FloatArray, FloatArray>>()

    /**
     * Words whose text layer is garbage (a script the scan's OCR could not read: Arabic in an
     * English book run through Acrobat's ClearScan) are shown as printed: each run of them becomes
     * an [InlineStyle.PICTURE] character whose place on the page is in [TextLine.pictures]. A line
     * that is all such words (an Arabic line) becomes one picture per word, in right-to-left order.
     * Only on a page with clear signs of such garbage, or with lines of that script left as images
     * ([foreignPage]). Returns the lines, changed where needed.
     */
    fun pictureWords(lines: List<TextLine>, foreignPage: Boolean = false): List<TextLine> {
        val places = HashMap(charPlaces)
        charPlaces.clear()
        // Only where the text layer was made by an OCR (glyphs placed loosely) and holds such
        // garbage, or where garbage is a good part of the page: a page of formulas with symbols
        // the text layer cannot name is not one.
        val tokens = lines.sumOf { l -> UnreadableText.tokens(l.text).size }
        val garbled = lines.sumOf { l -> UnreadableText.tokens(l.text).count { (_, t) -> UnreadableText.strong(t) } }
        if (!foreignPage && (garbled < 3 || !(scannedPage || (garbled >= 10 && garbled >= tokens * 0.15f)))) return lines
        val out = lines.map { l ->
            val (lefts, rights) = places[l] ?: return@map l
            pictureLine(l, lefts, rights) ?: l
        }.toMutableList()
        // Bits of the other script left between its lines (dots, marks read as ".J", "0", "_)"):
        // a line with nothing like an English word, close to a line of that script.
        val foreign = out.filter { it.text.startsWith(RTL_MARK) }
        for ((k, l) in out.withIndex()) {
            if (l.text.startsWith(RTL_MARK) || l.pictures.isNotEmpty()) continue
            val (lefts, rights) = places[lines[k]] ?: continue
            val tokens = UnreadableText.tokens(l.text)
            if (tokens.isEmpty() || tokens.any { UnreadableText.englishLike(it.second) } || tokens.size > 6) continue
            if (foreign.none { f -> abs(f.baseline - l.baseline) < max(f.fontSize, l.fontSize) * 2.2f && f.bounds.horizontalOverlap(l.bounds) > 0f }) continue
            out[k] = pictureLine(l, lefts, rights, force = true) ?: l
        }
        return joinForeignRows(out)
    }

    /**
     * Pieces of one line of the other script parted by a wide gap are one line, read from the
     * right: the rightmost piece first.
     */
    private fun joinForeignRows(lines: List<TextLine>): List<TextLine> {
        val foreign = lines.filter { it.text.startsWith(RTL_MARK) }
        if (foreign.size < 2) return lines
        val used = HashSet<TextLine>()
        val out = ArrayList<TextLine>(lines.size)
        for (l in lines) {
            if (l in used) continue
            if (!l.text.startsWith(RTL_MARK)) { out += l; continue }
            val row = foreign.filter { o -> o !in used && abs(o.baseline - l.baseline) < l.fontSize * 0.4f }.sortedByDescending { it.bounds.right }
            used += row
            if (row.size == 1) { out += l; continue }
            // the pieces' pictures, read from the right; pieces of one word read as two lines
            // (its letters and the marks over them) overlap, and are one picture
            val pictures = ArrayList<Rect>()
            for (piece in row) {
                for (st in piece.styles.filter { it.picture }) {
                    var r = piece.pictures[InlineStyle.pictureId(st.flags)]
                    var k = 0
                    while (k < pictures.size) {
                        val o = pictures[k]
                        if (r.left < o.right - 0.5f && o.left < r.right - 0.5f) { r = r.union(o); pictures.removeAt(k); k = 0 } else k++
                    }
                    pictures += r
                }
            }
            pictures.sortByDescending { it.right }
            tile(pictures, l.fontSize)
            val sb = StringBuilder().append(RTL_MARK)
            val styles = ArrayList<InlineStyle>()
            for (k in pictures.indices) {
                if (k > 0) sb.append(' ')
                styles += InlineStyle(sb.length, sb.length + 1, InlineStyle.pictureFlags(k))
                sb.append(com.raptibooks.core.model.InlinePicture.MARKER)
            }
            val bounds = Rect.bounding(row.map { it.bounds })!!
            val first = row.first()
            out += TextLine(sb.toString(), styles, bounds, first.baseline, first.fontSize, first.boldRatio, first.italicRatio, first.fontName, emptyList(), pictures)
        }
        return out
    }

    /**
     * The words of a line of the other script meet halfway across the gaps between them, so that
     * nothing printed there (a letter the text layer missed) is lost; gaps between columns stay.
     */
    private fun tile(pictures: MutableList<Rect>, size: Float) {
        val order = pictures.indices.sortedBy { pictures[it].left }
        for ((a, b) in order.zipWithNext()) {
            val gap = pictures[b].left - pictures[a].right
            if (gap <= 0f || gap > size * 1.2f) continue
            val mid = pictures[a].right + gap / 2f
            pictures[a] = Rect(pictures[a].left, pictures[a].top, mid, pictures[a].bottom)
            pictures[b] = Rect(mid, pictures[b].top, pictures[b].right, pictures[b].bottom)
        }
    }

    private fun pictureLine(l: TextLine, lefts: FloatArray, rights: FloatArray, force: Boolean = false): TextLine? {
        val tokens = UnreadableText.words(l.text)
        if (tokens.isEmpty()) return null
        val bad = tokens.map { (_, t) -> UnreadableText.garbled(t) }
        if (!force && bad.none { it } && tokens.count { UnreadableText.junk(it.second) } < 2) return null
        val wordy = tokens.indices.filter { tokens[it].second.any { c -> c.isLetter() || c == TextCleaner.PLACEHOLDER } }
        val garbled = bad.count { it }
        val english = tokens.count { UnreadableText.englishLike(it.second) }
        // a line of the other script: nothing like an English word in it, or mostly garbage
        val foreign = force || (english == 0 && (garbled >= 1 || tokens.count { UnreadableText.junk(it.second) } >= 2)) ||
            (garbled >= 3 && english * 3 < garbled && garbled >= wordy.size * 0.4f)
        val size = l.fontSize
        fun rect(range: IntRange): Rect {
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (i in range) if (l.text[i] != ' ') { left = min(left, lefts[i]); right = max(right, rights[i]) }
            return Rect(left, l.baseline - size * 1.05f, right, l.baseline + size * 0.5f)
        }
        // runs of words shown as printed, as character ranges
        val runs = mutableListOf<IntRange>()
        if (foreign) {
            // every word (pieces set much closer than words are one word)
            var cur: IntRange? = null
            for ((range, _) in tokens) {
                val c = cur
                cur = if (c != null && lefts[range.first] - rights[c.last] < size * 0.15f) c.first..range.last else {
                    if (c != null) runs += c
                    range
                }
            }
            cur?.let { runs += it }
        } else {
            var k = 0
            while (k < tokens.size) {
                if (!bad[k]) { k++; continue }
                var j = k
                // short pieces between two garbled words belong to them ("y\:iSll 11\")
                while (j + 1 < tokens.size && (bad[j + 1] || (UnreadableText.fragment(tokens[j + 1].second) && j + 2 < tokens.size && bad[j + 2]))) j++
                runs += tokens[k].first.first..tokens[j].first.last
                k = j + 1
            }
        }
        if (runs.isEmpty()) return null
        val pictures = runs.map { rect(it) }.toMutableList()
        if (foreign) tile(pictures, size)
        // the line's text with one marker per picture
        val sb = StringBuilder()
        val newIndex = IntArray(l.text.length + 1) { -1 }
        val styles = ArrayList<InlineStyle>()
        if (foreign) {
            // reading order of a right-to-left line: its rightmost word first
            sb.append(RTL_MARK)
            runs.indices.reversed().forEachIndexed { n, r ->
                if (n > 0) sb.append(' ')
                styles += InlineStyle(sb.length, sb.length + 1, InlineStyle.pictureFlags(r))
                sb.append(com.raptibooks.core.model.InlinePicture.MARKER)
            }
        } else {
            var i = 0
            var r = 0
            while (i < l.text.length) {
                if (r < runs.size && i == runs[r].first) {
                    // (a word stuck to the picture is a word of its own)
                    if (sb.isNotEmpty() && sb.last().isLetterOrDigit()) sb.append(' ')
                    styles += InlineStyle(sb.length, sb.length + 1, InlineStyle.pictureFlags(r))
                    sb.append(com.raptibooks.core.model.InlinePicture.MARKER)
                    i = runs[r].last + 1
                    if (i < l.text.length && l.text[i].isLetterOrDigit()) sb.append(' ')
                    r++
                    continue
                }
                newIndex[i] = sb.length
                sb.append(l.text[i])
                i++
            }
            for (st in l.styles) {
                val a = (st.start until st.end).firstNotNullOfOrNull { newIndex.getOrNull(it)?.takeIf { x -> x >= 0 } } ?: continue
                val b = (st.start until st.end).reversed().firstNotNullOfOrNull { newIndex.getOrNull(it)?.takeIf { x -> x >= 0 } } ?: continue
                if (b >= a) styles += InlineStyle(a, b + 1, st.flags)
            }
        }
        return TextLine(sb.toString(), styles.sortedBy { it.start }, l.bounds, l.baseline, l.fontSize, l.boldRatio, l.italicRatio, l.fontName, emptyList(), pictures)
    }

    /**
     * A line holding right-to-left text (Arabic, Hebrew), built in visual order, put into reading
     * order: its glyphs (each with its combining marks) are reordered, style ranges and chunks
     * follow them, and Arabic presentation forms become plain letters.
     */
    private fun toReadingOrder(
        text: CharSequence,
        glyphStarts: List<Int>,
        ranges: List<IntArray>,
        chunks: List<Chunk>,
    ): Triple<String, List<IntArray>, List<Chunk>> {
        // units: each glyph's text, and each space between them
        val cuts = sortedSetOf(0, text.length)
        glyphStarts.forEach { if (it < text.length) cuts += it }
        for (i in text.indices) if (text[i] == ' ') { cuts += i; cuts += i + 1 }
        val bounds = cuts.toList().zipWithNext().filter { (a, b) -> b > a }.toMutableList()
        // a mark drawn as a glyph of its own belongs to the letter before it
        var k = 1
        while (k < bounds.size) {
            val (a, b) = bounds[k]
            if ((a until b).all { RtlText.isMark(text[it]) } && text[bounds[k - 1].first] != ' ') {
                bounds[k - 1] = bounds[k - 1].first to b
                bounds.removeAt(k)
            } else {
                k++
            }
        }
        val units = bounds.map { (a, b) -> text.substring(a, b) }
        val order = RtlText.visualToLogical(units, RtlText.rtlBase(text))
        val sb = StringBuilder(text.length)
        val newIndex = IntArray(text.length + 1)
        for (u in order) {
            val (a, b) = bounds[u]
            val piece = RtlText.normalizeForms(units[u])
            val start = sb.length
            sb.append(piece)
            for (i in a until b) newIndex[i] = start + ((i - a) * piece.length / (b - a)).coerceAtMost(piece.length - 1)
        }
        newIndex[text.length] = sb.length
        // styles: per character, then back into ranges
        val flags = IntArray(sb.length)
        for (r in ranges) for (i in r[0] until minOf(r[1], text.length)) {
            val j = newIndex[i]
            if (j < flags.size) flags[j] = flags[j] or r[2]
        }
        val newRanges = ArrayList<IntArray>()
        var i = 0
        while (i < flags.size) {
            if (flags[i] == 0) { i++; continue }
            var j = i
            while (j + 1 < flags.size && flags[j + 1] == flags[i]) j++
            newRanges += intArrayOf(i, j + 1, flags[i])
            i = j + 1
        }
        // chunks keep their place on the page; their text is wherever their characters went
        val newChunks = chunks.map { c ->
            val idx = (c.textStart until c.textEnd).map { newIndex[it] }
            if (idx.isEmpty()) c else Chunk(c.left, c.right, idx.min(), idx.max() + 1)
        }
        val contiguous = newChunks.sortedBy { it.textStart }.zipWithNext().all { (x, y) -> x.textEnd <= y.textStart }
        return Triple(sb.toString(), newRanges, if (contiguous) newChunks else emptyList())
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

        private val NUMBERED_LABEL = Regex(
            "^\\s*(?:example|worked example|practice problem|exercise|problem|উদাহরণ|অনুশীলনী)\\s*[0-9০-৯]+(?:[.\\-][0-9০-৯]+)*\\s*$",
            RegexOption.IGNORE_CASE,
        )
        /** "Figure 3.117", "Fig. 2.4", "Table 1.3": a numbered caption label opening the text. */
        private val CAPTION_START = Regex("^\\s*(?:figure|fig\\.|table)\\s*[0-9]+(?:\\.[0-9]+)*", RegexOption.IGNORE_CASE)
        /** Operators and relations, set with space around them in a formula. */
        private val OPERATORS = setOf("=", "+", "−", "×", "÷", "±", "<", ">", "≤", "≥", "≅", "≈", "≠", "→", "⇒", "≫", "≪")
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
