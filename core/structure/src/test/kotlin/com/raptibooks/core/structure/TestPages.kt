package com.raptibooks.core.structure

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.raw.VectorPath

/** Helpers that lay out synthetic text the way a PDF text layer would present it. */
object TestPages {
    const val CHAR_WIDTH = 0.5f

    /** One fragment per word (plus explicit space fragments), left to right. */
    fun words(
        text: String,
        x: Float,
        baseline: Float,
        size: Float = 11f,
        bold: Boolean = false,
        italic: Boolean = false,
        font: String = "Serif",
    ): List<TextFragment> {
        val out = mutableListOf<TextFragment>()
        var cx = x
        val space = size * 0.25f
        for ((i, w) in text.split(' ').withIndex()) {
            if (i > 0) {
                out += TextFragment(" ", cx, baseline - size * 0.8f, cx + space, baseline + size * 0.2f, baseline, size, font, bold, italic, space)
                cx += space
            }
            val width = w.length * size * CHAR_WIDTH
            out += TextFragment(w, cx, baseline - size * 0.8f, cx + width, baseline + size * 0.2f, baseline, size, font, bold, italic, space)
            cx += width
        }
        return out
    }

    /** Wraps [text] into lines no wider than [width], returning the fragments and the next baseline. */
    fun paragraph(
        text: String,
        x: Float,
        startBaseline: Float,
        width: Float,
        size: Float = 11f,
        leading: Float = size * 1.35f,
        indent: Float = 0f,
    ): Pair<List<TextFragment>, Float> {
        val out = mutableListOf<TextFragment>()
        val maxChars = (width / (size * CHAR_WIDTH)).toInt()
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in text.split(' ')) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) {
                lines += cur.toString(); cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        var b = startBaseline
        for ((i, l) in lines.withIndex()) {
            out += words(l, x + if (i == 0) indent else 0f, b, size)
            b += leading
        }
        return out to b
    }

    fun hLine(x0: Float, x1: Float, y: Float) =
        VectorPath(Rect(x0, y - 0.25f, x1, y + 0.25f), PathShape.HORIZONTAL_LINE, stroked = true, filled = false)

    fun vLine(x: Float, y0: Float, y1: Float) =
        VectorPath(Rect(x - 0.25f, y0, x + 0.25f, y1), PathShape.VERTICAL_LINE, stroked = true, filled = false)

    const val LOREM =
        "Reading on a phone should feel natural. The converter rebuilds every paragraph so that the text " +
            "flows to the width of the screen instead of forcing the reader to pan around a fixed page. " +
            "Headings, lists, tables and figures are detected and kept in their logical order."
}
