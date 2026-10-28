package com.raptibooks.core.structure.text

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.layout.Chunk
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.TextFragment
import kotlin.math.abs
import kotlin.math.max

/** How often each word (lower case) occurs in a book, from a sample of its pages. */
class Vocabulary(private val counts: Map<String, Int>) {
    fun count(word: String): Int = counts[word.lowercase()] ?: 0

    private val sorted: Array<String> by lazy { counts.keys.sorted().toTypedArray() }

    /** How often words beginning with [prefix] occur ("determin": determined, determines...). */
    fun countPrefix(prefix: String): Int {
        val p = prefix.lowercase()
        var i = sorted.binarySearch(p).let { if (it < 0) -it - 1 else it }
        var n = 0
        while (i < sorted.size && sorted[i].startsWith(p)) n += counts[sorted[i++]] ?: 0
        return n
    }

    val isEmpty: Boolean get() = counts.isEmpty()

    /** One word and its count per line, for a cache file. */
    fun write(): String = counts.entries.joinToString("\n") { "${it.key} ${it.value}" }

    /** Counts the words of a page's text, adding them to [counts]. */
    class Builder {
        private val counts = HashMap<String, Int>()

        fun add(fragments: List<TextFragment>) {
            val sb = StringBuilder()
            var prev: TextFragment? = null
            for (f in fragments) {
                val p = prev
                if (p != null && (abs(f.baseline - p.baseline) > p.fontSize * 0.5f || f.left - p.right > p.fontSize * 0.2f)) sb.append(' ')
                sb.append(f.text)
                prev = f
            }
            for (w in WORD.findAll(sb)) {
                val key = w.value.lowercase()
                counts[key] = (counts[key] ?: 0) + 1
            }
        }

        fun build(): Vocabulary = Vocabulary(counts)
    }

    companion object {
        private val WORD = Regex("[A-Za-z]{2,}")

        fun read(text: String): Vocabulary = Vocabulary(
            text.lineSequence().mapNotNull { l ->
                val sp = l.indexOf(' ')
                if (sp <= 0) null else l.substring(sp + 1).toIntOrNull()?.let { l.substring(0, sp) to it }
            }.toMap(),
        )
    }
}

/**
 * Words an OCR misread in the way OCRs do ("transfonner" for transformer, "1lle" for The,
 * "nux" for flux, "currenl" for current, "tum" for turn, "ofa" for "of a"), put right when the
 * book itself prints the right word: the [Vocabulary] of the book says what its words are.
 */
object OcrWords {
    private val TOKEN = Regex("[A-Za-z1]{3,}")

    private val TH = listOf("1ll", "1l", "TIl", "Tll")

    /** The word [word] stands for, or null when it stands for itself. */
    fun correct(word: String, vocab: Vocabulary): String? {
        val lower = word.lowercase()
        val own = vocab.count(lower)
        fun cased(fixed: String): String = when {
            word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } -> fixed.uppercase()
            word.first().isUpperCase() || word.first() == '1' -> fixed.replaceFirstChar { it.uppercaseChar() }
            else -> fixed
        }
        // "Th" read as "1l", "1ll", "TIl" (1lle, 1llis, TIlere)
        TH.firstOrNull { word.startsWith(it) }?.let { prefix ->
            val fixed = "th" + lower.substring(prefix.length)
            return if (vocab.count(fixed) >= 1) cased(fixed) else null
        }
        if (lower.any { !it.isLetter() }) return null
        // "rm" read as "nn" (transfonner, tenn, fonn, penneability): as common as the misreading
        if ("nn" in lower) {
            val fixed = lower.replace("nn", "rm")
            if (vocab.count(fixed) >= max(1, own / 10)) return cased(fixed)
            var i = lower.indexOf("nn")
            while (i >= 0) {
                val one = lower.substring(0, i) + "rm" + lower.substring(i + 2)
                if (vocab.count(one) >= max(1, own / 10)) return cased(one)
                // (a long word the pages read never print right: its family does, "detennine"
                // beside "determined" and "determines")
                val stem = one.dropLast(2)
                if (stem.length >= 6 && i + 2 <= stem.length && vocab.countPrefix(stem) >= max(2, own / 10)) return cased(one)
                i = lower.indexOf("nn", i + 1)
            }
        }
        // the rarer misreadings: the right word must be much more common than this one
        val bar = max(6, own * 5)
        // "fl" read as "n" (nux, nuid)
        if (lower.startsWith("n") && vocab.count("fl" + lower.substring(1)) >= bar) return cased("fl" + lower.substring(1))
        // "t" read as "I" at the start of a word (Ihe, Ihat)
        if (word.length >= 3 && word[0] == 'I' && word.drop(1).all { it.isLowerCase() } && vocab.count("t" + lower.substring(1)) >= bar) return "t" + lower.substring(1)
        // "t" read as "l" or "i" (currenl, magnelic, thai), "rn" read as "m" (tum, tums)
        for ((from, to) in listOf("l" to "t", "i" to "t", "m" to "rn")) {
            var i = lower.indexOf(from)
            while (i >= 0) {
                val fixed = lower.substring(0, i) + to + lower.substring(i + from.length)
                if (vocab.count(fixed) >= bar) return cased(fixed)
                i = lower.indexOf(from, i + 1)
            }
        }
        return null
    }

    /** "ofa", "ofthe", "ofMATLAB": two words read as one. */
    private val JOINED = Regex("^(?:[Oo]f(?:a|an|the)|OF(?:A|AN|THE)|[Oo]f[A-Z][A-Za-z]+)$")

    /** A figure's label as the OCR read it: '""GURE l-S' for "FIGURE 1-5", "FlGURES-9" for "FIGURE 5-9", "H GURE 2-11", "fo'IGURE 1-8", '""GURE \-10', "FIGURE PI- IS" for "FIGURE P1-15", "HGURE )'1-3" for "FIGURE P1-3", '""GURE 7-1J' for "FIGURE 7-13". */
    private val FIGURE_LABEL = Regex("^\\s*\\S{0,4}\\s?GURE\\s*(P|\\)'?)?([0-9lIOSoBJZ\\\\|]{1,2})\\s*([-–—]+)\\s*([0-9lIOSoBJZ\\\\|]{1,3})(?![A-Za-z0-9])")

    private fun digits(s: String) = s.map { c -> when (c) { 'l', 'I', '\\', '|' -> '1'; 'O', 'o' -> '0'; 'S' -> '5'; 'B' -> '8'; 'J' -> '3'; 'Z' -> '2'; else -> c } }.joinToString("")

    /** The lines with their misread words put right. */
    fun fix(lines: List<TextLine>, vocab: Vocabulary): List<TextLine> {
        val labelled = lines.map { l ->
            val m = FIGURE_LABEL.find(l.text) ?: return@map l
            val label = "FIGURE " + (if (m.groupValues[1].isEmpty()) "" else "P") + digits(m.groupValues[2]) + m.groupValues[3] + digits(m.groupValues[4])
            val start = m.range.first + (m.value.length - m.value.trimStart().length)
            if (l.text.substring(start, m.range.last + 1) == label) l else edit(l, listOf(Triple(start, m.range.last + 1, label)))
        }
        if (vocab.isEmpty) return labelled
        return labelled.map { l ->
            val edits = ArrayList<Triple<Int, Int, String>>()
            for (m in TOKEN.findAll(l.text)) {
                // (a word, not a piece of one: "IL" in "VIL" or a symbol's subscript)
                val a = m.range.first
                val b = m.range.last + 1
                if ((a > 0 && l.text[a - 1].isLetterOrDigit()) || (b < l.text.length && l.text[b].isLetterOrDigit())) continue
                val joined = JOINED.find(m.value)
                val fixed = if (joined != null) m.value.substring(0, 2) + " " + m.value.substring(2) else correct(m.value, vocab)
                if (fixed != null && fixed != m.value) edits += Triple(a, b, fixed)
            }
            if (edits.isEmpty()) l else edit(l, edits)
        }
    }

    /** [l] with the text ranges [edits] (start, end, new text) replaced; styles and chunks follow. */
    private fun edit(l: TextLine, edits: List<Triple<Int, Int, String>>): TextLine {
        val sb = StringBuilder()
        val map = IntArray(l.text.length + 1)
        var i = 0
        for ((a, b, t) in edits) {
            while (i < a) { map[i] = sb.length; sb.append(l.text[i]); i++ }
            val start = sb.length
            sb.append(t)
            while (i < b) { map[i] = start + ((i - a) * t.length / (b - a)).coerceAtMost(t.length - 1); i++ }
        }
        while (i < l.text.length) { map[i] = sb.length; sb.append(l.text[i]); i++ }
        map[l.text.length] = sb.length
        fun end(e: Int): Int = if (e >= l.text.length) sb.length else map[e]
        val styles = l.styles.mapNotNull { s ->
            val a = map[s.start.coerceIn(0, l.text.length)]
            val b = end(s.end)
            if (b > a) InlineStyle(a, b, s.flags) else null
        }
        val chunks = l.chunks.map { c -> Chunk(c.left, c.right, map[c.textStart.coerceIn(0, l.text.length)], end(c.textEnd)) }
        return TextLine(sb.toString(), styles, l.bounds, l.baseline, l.fontSize, l.boldRatio, l.italicRatio, l.fontName, chunks, l.pictures)
    }
}
