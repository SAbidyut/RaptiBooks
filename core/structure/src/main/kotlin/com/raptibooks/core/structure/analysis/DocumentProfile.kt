package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.text.BanglaText
import com.raptibooks.core.structure.text.TextQuality
import kotlin.math.abs
import kotlin.math.roundToInt

/** Accumulates cross-page statistics during the first pass (constant memory per page). */
class DocumentStats {
    private val sizeChars = HashMap<Int, Long>()
    private val sizePages = HashMap<Int, Int>()
    private val edgePages = HashMap<String, Int>()
    private var visualImpossible = 0
    private var preBaseTotal = 0
    private var bengaliLetters = 0L
    private var latinLetters = 0L
    var pageCount = 0
        private set
    var pagesWithText = 0
        private set

    fun add(layout: PageLayout) {
        pageCount++
        val lines = layout.lines
        if (lines.isNotEmpty()) pagesWithText++
        val seenSizes = HashSet<Int>()
        val seenEdges = HashSet<String>()
        for (l in lines) {
            val key = sizeKey(l.fontSize)
            val letters = TextQuality.letterCount(l.text)
            sizeChars[key] = (sizeChars[key] ?: 0L) + letters
            if (seenSizes.add(key)) sizePages[key] = (sizePages[key] ?: 0) + 1
            edgeZone(l, layout.height)?.let { zone ->
                if (l.text.length <= 150) {
                    val sig = zone + Patterns.signature(l.text)
                    if (seenEdges.add(sig)) edgePages[sig] = (edgePages[sig] ?: 0) + 1
                }
            }
            if (!layout.fromOcr) {
                val (imp, total) = BanglaText.visualOrderEvidence(l.text)
                visualImpossible += imp
                preBaseTotal += total
            }
            val (bn, la) = BanglaText.scriptCounts(l.text)
            bengaliLetters += bn
            latinLetters += la
        }
        for (t in layout.tables) for (row in t.rows) for (cell in row) {
            val (bn, la) = BanglaText.scriptCounts(cell)
            bengaliLetters += bn
            latinLetters += la
        }
    }

    fun profile(): DocumentProfile {
        val body = sizeChars.maxByOrNull { it.value }?.key?.let { it / 2f } ?: 11f
        // Candidate heading sizes, largest first, clustered within ~6%.
        val big = sizeChars.filter { (k, chars) -> k / 2f >= body * 1.15f && chars >= 2 }.keys.map { it / 2f }.sortedDescending()
        val clusters = mutableListOf<MutableList<Float>>()
        for (s in big) {
            val last = clusters.lastOrNull()
            if (last != null && last.last() / s < 1.06f) last += s else clusters += mutableListOf(s)
        }
        var reps = clusters.map { it.last() }
        // A size used only on a title page should not push chapter headings down a level.
        if (reps.size > 3) {
            val firstPages = clusters.first().sumOf { sizePages[sizeKey(it)] ?: 0 }
            if (firstPages <= 2) reps = reps.drop(1)
        }
        val minEdgeRepeats = when {
            pagesWithText >= 6 -> 3
            pagesWithText >= 3 -> 2
            else -> Int.MAX_VALUE
        }
        val repeated = edgePages.filter { it.value >= minEdgeRepeats }.keys
        val visual = visualImpossible >= 3 && visualImpossible >= preBaseTotal * 0.12
        val total = (bengaliLetters + latinLetters).coerceAtLeast(1)
        val language = when {
            bengaliLetters * 100 / total >= 80 -> "bn"
            latinLetters * 100 / total >= 80 -> "en"
            bengaliLetters + latinLetters == 0L -> "unknown"
            else -> "bn+en"
        }
        return DocumentProfile(body, reps.take(3), repeated, visual, language)
    }

    companion object {
        fun sizeKey(size: Float): Int = (size * 2f).roundToInt()

        /** "T|" or "B|" if the line sits in the top/bottom margin band of the page. */
        fun edgeZone(l: TextLine, pageHeight: Float): String? = when {
            l.bounds.bottom <= pageHeight * 0.1f -> "T|"
            l.bounds.top >= pageHeight * 0.9f -> "B|"
            else -> null
        }
    }
}

class DocumentProfile(
    val bodySize: Float,
    /** Representative heading font sizes, index 0 = level 1. */
    val headingSizes: List<Float>,
    private val repeatedEdges: Set<String>,
    /** Bangla text layer is in visual glyph order and must be reordered. */
    val visualOrder: Boolean,
    val language: String,
) {
    fun headingLevelForSize(size: Float): Int? {
        if (size < bodySize * 1.15f) return null
        headingSizes.forEachIndexed { i, rep -> if (size >= rep * 0.94f) return i + 1 }
        return (headingSizes.size + 1).coerceAtMost(3)
    }

    fun isRunningHeaderOrFooter(line: TextLine, pageHeight: Float): Boolean {
        val zone = DocumentStats.edgeZone(line, pageHeight) ?: return false
        if (Patterns.pageNumber.matches(line.text.trim())) return true
        return (zone + Patterns.signature(line.text)) in repeatedEdges
    }

    fun isSmallText(size: Float): Boolean = size < bodySize * 0.86f

    fun similarToBody(size: Float): Boolean = abs(size - bodySize) <= bodySize * 0.08f
}
