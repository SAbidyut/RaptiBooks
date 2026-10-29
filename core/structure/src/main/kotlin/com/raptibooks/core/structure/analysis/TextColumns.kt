package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.layout.TextLine

/** Two columns of running text on a page (exercise pages, review questions). */
object TextColumns {

    /**
     * The x of the gutter between two columns of text, if the page has two: no line of several
     * words crosses it and there are lines on both sides. Running headers are ignored.
     */
    fun gutter(lines: List<TextLine>, pageWidth: Float, pageHeight: Float): Float? {
        val text = lines.filter { it.wordCount >= 4 && it.bounds.top > pageHeight * 0.07f && it.bounds.bottom < pageHeight * 0.93f }
        if (text.size < 6) return null
        var x = pageWidth * 0.25f
        var best: Float? = null
        var bestScore = 0
        while (x <= pageWidth * 0.75f) {
            val gx = x
            if (text.none { it.bounds.left < gx - 2f && it.bounds.right > gx + 2f }) {
                val score = minOf(text.count { it.bounds.right <= gx }, text.count { it.bounds.left >= gx })
                if (score >= 3 && score > bestScore) {
                    best = gx
                    bestScore = score
                }
            }
            x += 2f
        }
        return best
    }
}
