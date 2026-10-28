package com.raptibooks.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.raptibooks.core.data.PageLine
import kotlinx.coroutines.delay

/**
 * Finding the same passage in the reflowed text and on the original page: by its words, as
 * line breaks, hyphens and spacing differ between the two.
 */
internal object Passage {
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private class Word(val norm: String, val start: Int, val end: Int)

    private fun words(text: String): List<Word> =
        WORD.findAll(text).map { Word(it.value.lowercase(), it.range.first, it.range.last + 1) }.toList()

    /** About ten words of [text] around [offset]: what the finger was on. */
    fun around(text: String, offset: Int): String? {
        val ws = words(text)
        if (ws.isEmpty()) return null
        val at = ws.indexOfFirst { it.end > offset }.let { if (it < 0) ws.lastIndex else it }
        val from = (at - 5).coerceAtLeast(0)
        val to = (from + 10).coerceAtMost(ws.size)
        return text.substring(ws[from].start, ws[to - 1].end)
    }

    /** How many words of [snippet] (not counting one-letter words) are in [text]. */
    private fun score(snippet: Set<String>, text: String): Int = words(text).map { it.norm }.toSet().count { it in snippet }

    /** The printed lines of a page that show [snippet]: the best match and its neighbours that share it. */
    fun linesOf(lines: List<PageLine>, snippet: String): List<PageLine> {
        val wanted = words(snippet).map { it.norm }.filter { it.length >= 2 }.toSet()
        if (wanted.isEmpty() || lines.isEmpty()) return emptyList()
        val scores = lines.map { score(wanted, it.text) }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return emptyList()
        if (scores[best] < minOf(2, wanted.size)) return emptyList()
        val out = mutableListOf(lines[best])
        val ordered = lines.sortedBy { it.top }
        val i = ordered.indexOf(lines[best])
        for (j in listOf(i - 1, i + 1)) {
            val l = ordered.getOrNull(j) ?: continue
            if (score(wanted, l.text) >= 2 && kotlin.math.abs(l.left - lines[best].left) < 0.3f) out += l
        }
        return out
    }

    /**
     * Where in [text] (a block of the reflowed book) the printed [line] is: the run of words
     * that shares the most words with it, or null when it is not there.
     */
    fun rangeIn(text: String, line: String): Pair<Int, IntRange>? {
        val target = words(line).map { it.norm }
        if (target.isEmpty()) return null
        val ws = words(text)
        if (ws.isEmpty()) return null
        val n = target.size.coerceAtMost(ws.size)
        val wanted = target.toSet()
        var bestScore = -1
        var bestAt = 0
        for (start in 0..ws.size - n) {
            var s = 0
            for (k in start until start + n) if (ws[k].norm in wanted) s++
            if (s > bestScore) {
                bestScore = s
                bestAt = start
            }
        }
        if (bestScore < minOf(2, target.size) || bestScore * 2 < target.size) return null
        // (trimmed to the words that match, at both ends)
        var a = bestAt
        var b = bestAt + n - 1
        while (a < b && ws[a].norm !in wanted) a++
        while (b > a && ws[b].norm !in wanted) b--
        return bestScore to (ws[a].start until ws[b].end)
    }
}

/** The colour a passage lights up in where the user arrives from the other view. */
internal val PASSAGE_GLOW = Color(0xFFFFB300)

/**
 * A passage of the reflowed book lit up for a moment: the one held on the original page, so
 * the eye finds it on arriving here.
 */
@Stable
internal class PassageFlash {
    var block by mutableIntStateOf(-1)
        private set
    var line by mutableStateOf("")
        private set
    val glow = Animatable(0f)

    suspend fun show(block: Int, line: String) {
        this.line = line
        this.block = block
        try {
            glow.snapTo(0.55f)
            delay(500)
            glow.animateTo(0f, tween(1400))
        } finally {
            this.block = -1
        }
    }
}

internal val LocalFlash = staticCompositionLocalOf { PassageFlash() }
