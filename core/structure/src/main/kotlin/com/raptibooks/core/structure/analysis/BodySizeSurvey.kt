package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.raw.TextFragment
import kotlin.math.roundToInt

/**
 * The body text size of a book from a sample of its pages: the size that carries the most
 * characters. Lines printed the same on many of the pages (a copyright notice above every page
 * foot, a running head) are page furniture and do not count, however much text they carry.
 */
class BodySizeSurvey {
    private val chars = HashMap<Int, Long>()
    /** Per line text (digits folded): on how many pages, and its characters by size. */
    private val linePages = HashMap<String, Int>()
    private val lineChars = HashMap<String, HashMap<Int, Long>>()
    private var pages = 0

    fun add(fragments: List<TextFragment>) {
        pages++
        val rows = fragments.filter { !it.isSpace }.groupBy { (it.baseline / 2f).roundToInt() }
        val seen = HashSet<String>()
        for (row in rows.values) {
            val sorted = row.sortedBy { it.left }
            val text = sorted.joinToString("") { it.text }
            val sig = if (text.length >= 20) Patterns.signature(text) else null
            if (sig != null && seen.add(sig)) linePages[sig] = (linePages[sig] ?: 0) + 1
            for (f in sorted) {
                val key = (f.fontSize * 2f).roundToInt()
                chars[key] = (chars[key] ?: 0L) + f.text.length
                if (sig != null) lineChars.getOrPut(sig) { HashMap() }.merge(key, f.text.length.toLong(), Long::plus)
            }
        }
    }

    /** The body size in points, or null if the sample had no text. */
    fun bodySize(): Float? {
        val counts = HashMap(chars)
        if (pages >= 4) {
            val min = maxOf(3, (pages * 0.3f).toInt())
            for ((sig, n) in linePages) {
                if (n < min) continue
                lineChars[sig]?.forEach { (k, c) -> counts[k] = ((counts[k] ?: 0L) - c).coerceAtLeast(0L) }
            }
        }
        return counts.filter { it.value > 0 }.maxByOrNull { it.value }?.key?.let { it / 2f }
    }
}
