package com.raptibooks.feature.reader

import com.raptibooks.core.data.PageLine
import com.raptibooks.core.model.Highlight
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.MarkKey
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A stretch of a printed line underlined, in fractions of the page; [key], [range]: the marked text it shows. */
internal class PageUnderline(val x1: Float, val x2: Float, val y: Float, val color: MarkColor, val key: Long, val range: IntRange)

/**
 * The pen on the original pages and the same marks in the reflowed text: a stroke drawn along
 * a printed line becomes a straight underline of its words, and the underlines and notes of
 * either view are found in the other by their words.
 */
internal object PageMarks {
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private class Word(val norm: String, val start: Int, val end: Int)

    private fun words(text: String): List<Word> =
        WORD.findAll(text).map { Word(it.value.lowercase(), it.range.first, it.range.last + 1) }.toList()

    /** Where the underline of a printed line goes: just below its letters. */
    fun underlineY(l: PageLine): Float = l.bottom + (l.bottom - l.top) * 0.12f

    private fun xOf(l: PageLine, char: Int): Float =
        l.left + (l.right - l.left) * char.coerceIn(0, l.text.length) / l.text.length.coerceAtLeast(1)

    private fun charAt(l: PageLine, x: Float): Int =
        (((x - l.left) / (l.right - l.left).coerceAtLeast(1e-4f)) * l.text.length).roundToInt().coerceIn(0, l.text.length)

    /** The whole words of line [l] between [x1] and [x2] (characters of its text), or null. */
    fun wordsUnder(l: PageLine, x1: Float, x2: Float): IntRange? {
        val a = charAt(l, min(x1, x2))
        val b = charAt(l, max(x1, x2))
        val hit = words(l.text).filter { it.end > a && it.start < b }
        if (hit.isEmpty()) return null
        return hit.first().start until hit.last().end
    }

    private fun underline(l: PageLine, chars: IntRange): FloatArray {
        val y = underlineY(l)
        return floatArrayOf(xOf(l, chars.first), y, xOf(l, chars.last + 1), y)
    }

    // ------------------------------------------------------------------ a stroke made straight

    /**
     * A pen stroke ([points]: x, y pairs, fractions of the page) made tidy: drawn along or
     * through printed lines it becomes a straight underline of the words it ran over; drawn
     * round some lines, an underline of each; elsewhere a stroke that runs across is made
     * straight, and any other (a ring round a picture) is kept as drawn.
     */
    fun snap(points: FloatArray, lines: List<PageLine>): List<FloatArray> {
        val n = points.size / 2
        if (n < 2) return listOf(points)
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (i in 0 until n) {
            minX = min(minX, points[2 * i]); maxX = max(maxX, points[2 * i])
            minY = min(minY, points[2 * i + 1]); maxY = max(maxY, points[2 * i + 1])
        }
        val w = maxX - minX
        val h = maxY - minY
        val closed = hypot(points[0] - points[2 * n - 2], points[1] - points[2 * n - 1]) < 0.3f * max(w, h)
        val lineHeight = lines.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 0.015f else it[it.size / 2] }
        if (n >= 8 && closed && h > lineHeight * 1.6f && w > 0.04f) {
            // a ring round some lines: each of them underlined, as far as the ring goes
            val inside = lines.filter { (it.top + it.bottom) / 2f in minY..maxY && it.right > minX && it.left < maxX }
            val out = inside.mapNotNull { l -> wordsUnder(l, max(l.left, minX), min(l.right, maxX))?.let { underline(l, it) } }
            if (out.isNotEmpty()) return out
        }
        // along (or through) lines: each point counts for the line it is on or just under
        val cover = HashMap<PageLine, FloatArray>()
        for (i in 0 until n) {
            val x = points[2 * i]
            val y = points[2 * i + 1]
            val l = lines.filter { l ->
                val lh = l.bottom - l.top
                x in l.left - 0.01f..l.right + 0.01f && y in l.top - lh * 0.6f..l.bottom + lh * 0.9f
            }.minByOrNull { abs((it.top + it.bottom) / 2f - y) } ?: continue
            val c = cover.getOrPut(l) { floatArrayOf(x, x) }
            c[0] = min(c[0], x)
            c[1] = max(c[1], x)
        }
        val widest = cover.values.maxOfOrNull { it[1] - it[0] } ?: 0f
        val kept = cover.filter { (_, c) -> c[1] - c[0] >= max(0.02f, widest * 0.6f) }
            .entries.sortedBy { it.key.top }
            .mapNotNull { (l, c) -> wordsUnder(l, c[0], c[1])?.let { underline(l, it) } }
        if (kept.isNotEmpty()) return kept
        // no print under it: straight when it runs across, else as drawn
        if (w > 0.03f && h < w * 0.35f) {
            val ys = (0 until n).map { points[2 * it + 1] }.sorted()
            val y = ys[ys.size / 2]
            return listOf(floatArrayOf(minX, y, maxX, y))
        }
        return listOf(points)
    }

    // ------------------------------------------------------------------ reflowed text -> page

    private class PageWord(val line: Int, val word: Word)

    private fun pageWords(lines: List<PageLine>): List<PageWord> =
        lines.flatMapIndexed { i, l -> words(l.text).map { PageWord(i, it) } }

    /**
     * Where on the page the run of words [target] is: the start of the best stretch of [page]
     * words, allowing a word split or joined at a line end. Null when it is not there.
     */
    private fun find(page: List<PageWord>, target: List<String>): Int? {
        if (target.isEmpty() || page.isEmpty()) return null
        var best = -1
        var bestScore = 0
        for (pos in 0..max(0, page.size - 1)) {
            var s = 0
            for (i in target.indices) {
                val t = target[i]
                if (page.getOrNull(pos + i)?.word?.norm == t || page.getOrNull(pos + i - 1)?.word?.norm == t || page.getOrNull(pos + i + 1)?.word?.norm == t) s++
            }
            if (s > bestScore) {
                bestScore = s
                best = pos
            }
        }
        val needed = max(1, ceil(target.size * 0.6).toInt())
        return if (best >= 0 && bestScore >= needed) best else null
    }

    /**
     * The underlines in the reflowed text ([highlights] of blocks whose texts are [texts]) that
     * are on this page, drawn under its printed words.
     */
    fun underlines(lines: List<PageLine>, texts: Map<Int, String>, highlights: List<Highlight>): List<PageUnderline> {
        val page = pageWords(lines)
        if (page.isEmpty()) return emptyList()
        val out = ArrayList<PageUnderline>()
        for (h in highlights) {
            val text = texts[h.blockIndex] ?: continue
            val ws = words(text)
            val inner = ws.indices.filter { ws[it].end > h.start && ws[it].start < h.end }
            if (inner.isEmpty()) continue
            // (a few words either side, so a short passage is found in the right place)
            val from = (inner.first() - 4).coerceAtLeast(0)
            val to = (inner.last() + 4).coerceAtMost(ws.lastIndex)
            val pos = find(page, (from..to).map { ws[it].norm }) ?: continue
            val first = (pos + inner.first() - from).coerceIn(0, page.lastIndex)
            val last = (pos + inner.last() - from).coerceIn(first, page.lastIndex)
            val key = MarkKey.of(h.blockIndex, h.part)
            for ((line, run) in (first..last).map { page[it] }.groupBy { it.line }) {
                val l = lines[line]
                out += PageUnderline(xOf(l, run.first().word.start), xOf(l, run.last().word.end), underlineY(l), h.color, key, h.start until h.end)
            }
        }
        return out
    }

    /**
     * Where on the page a note pinned in the reflowed text goes: its place ([x], [y]: fractions
     * of the block) within the printed lines of the block whose text is [text].
     */
    fun notePlace(lines: List<PageLine>, text: String, x: Float, y: Float): Pair<Float, Float>? {
        val page = pageWords(lines)
        val ws = words(text)
        if (ws.isEmpty() || page.isEmpty()) return null
        val head = ws.take(6).map { it.norm }
        val tail = ws.takeLast(6).map { it.norm }
        val a = find(page, head)
        val b = find(page, tail)?.let { (it + tail.size - 1).coerceAtMost(page.lastIndex) }
        if (a == null && b == null) return null
        val first = lines[page[a ?: b!!].line]
        val last = lines[page[b ?: a!!].line]
        val top = min(first.top, last.top)
        val bottom = max(first.bottom, last.bottom)
        val left = min(first.left, last.left)
        val right = max(first.right, last.right)
        return (left + x.coerceIn(0f, 1f) * (right - left)) to (top + y.coerceIn(0f, 1f) * (bottom - top))
    }

    // ------------------------------------------------------------------ page -> reflowed text

    /**
     * Where in [text] (a block of the reflowed book) the characters [chars] of the printed
     * line [line] are, with how many of the line's words matched; null when the line is not
     * in it.
     */
    fun inText(text: String, line: PageLine, chars: IntRange): Pair<Int, IntRange>? {
        val lws = words(line.text)
        if (lws.isEmpty()) return null
        val a = lws.indexOfFirst { it.end > chars.first }.let { if (it < 0) lws.lastIndex else it }
        val b = lws.indexOfLast { it.start <= chars.last }.coerceAtLeast(a)
        val tws = words(text)
        if (tws.isEmpty()) return null
        val n = lws.size.coerceAtMost(tws.size)
        val wanted = lws.map { it.norm }
        var bestAt = 0
        var bestScore = -1
        for (start in 0..tws.size - n) {
            var s = 0
            for (k in 0 until n) if (tws[start + k].norm == wanted[k] || tws.getOrNull(start + k + 1)?.norm == wanted[k] || (start + k > 0 && tws[start + k - 1].norm == wanted[k])) s++
            if (s > bestScore) {
                bestScore = s
                bestAt = start
            }
        }
        if (bestScore < min(2, lws.size) || bestScore * 2 < lws.size) return null
        val from = (bestAt + a).coerceAtMost(tws.lastIndex)
        val to = (bestAt + b).coerceIn(from, tws.lastIndex)
        return bestScore to (tws[from].start until tws[to].end)
    }

    /** The printed line a straight underline (x, y pairs, fractions of the page) is under, with its words. */
    fun underlined(points: FloatArray, lines: List<PageLine>): Pair<PageLine, IntRange>? {
        val n = points.size / 2
        if (n < 2) return null
        var minX = 1f
        var maxX = 0f
        var ySum = 0f
        for (i in 0 until n) {
            minX = min(minX, points[2 * i]); maxX = max(maxX, points[2 * i])
            ySum += points[2 * i + 1]
        }
        val y = ySum / n
        val l = lines.filter { it.right > minX && it.left < maxX && abs(underlineY(it) - y) < (it.bottom - it.top) * 0.9f }
            .minByOrNull { abs(underlineY(it) - y) } ?: return null
        return wordsUnder(l, max(minX, l.left), min(maxX, l.right))?.let { l to it }
    }

    /** The printed line nearest a place on the page, and the word there. */
    fun wordAt(lines: List<PageLine>, x: Float, y: Float): Pair<PageLine, IntRange>? {
        val l = lines.minByOrNull { abs((it.top + it.bottom) / 2f - y) + if (x in it.left..it.right) 0f else 0.05f } ?: return null
        if (abs((l.top + l.bottom) / 2f - y) > 0.06f) return null
        val c = charAt(l, x.coerceIn(l.left, l.right))
        val ws = words(l.text)
        val w = ws.firstOrNull { it.end >= c } ?: ws.lastOrNull() ?: return null
        return l to (w.start until w.end)
    }
}
