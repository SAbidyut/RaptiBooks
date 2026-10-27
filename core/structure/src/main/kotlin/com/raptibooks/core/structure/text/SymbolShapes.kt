package com.raptibooks.core.structure.text

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Identifies common math symbols from their printed shape. Used for glyphs whose font has no
 * Unicode mapping (the "=" and "×" of Mathematical-Pi-style fonts in typeset textbooks): the
 * glyph is rendered and its ink is analysed as connected components (bars, dots, crosses).
 *
 * Only shapes that can be told apart reliably are recognised; anything else returns null and
 * the glyph stays unknown.
 */
object SymbolShapes {

    /** A connected piece of ink, in pixels. */
    private class Comp(var x0: Int, var y0: Int, var x1: Int, var y1: Int, var pixels: Int = 0) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
        val cx get() = (x0 + x1) / 2f
        val cy get() = (y0 + y1) / 2f
        val fill get() = pixels.toFloat() / (w * h)
    }

    /**
     * @param gray grey levels (0 = black, 255 = white), row-major, [w] x [h]
     * @param boxLeft / [boxRight] horizontal extent of the glyph's advance in pixels; ink whose
     *        centre is outside belongs to neighbouring glyphs
     * @param baseline baseline row in pixels
     * @param em font size in pixels
     */
    fun classify(gray: IntArray, w: Int, h: Int, boxLeft: Float, boxRight: Float, baseline: Float, em: Float): Char? {
        if (w <= 0 || h <= 0 || em < 6f || gray.size < w * h) return null
        // Look only at a window around the glyph (the image may hold a whole line of text).
        val wx0 = (boxLeft - em * 0.4f).toInt().coerceIn(0, w - 1)
        val wx1 = (boxRight + em * 0.4f).toInt().coerceIn(wx0, w - 1)
        if (wx0 > 0 || wx1 < w - 1) {
            val ww = wx1 - wx0 + 1
            val window = IntArray(ww * h) { gray[(it / ww) * w + wx0 + it % ww] }
            return classify(window, ww, h, boxLeft - wx0, boxRight - wx0, baseline, em)
        }
        val ink = BooleanArray(w * h) { gray[it] < 150 }
        val comps = components(ink, w, h)
            .filter { it.pixels >= max(2f, em * em * 0.0006f) }
            // Ink cut by the top or bottom edge belongs to the lines above and below.
            .filter { it.y0 > 0 && it.y1 < h - 1 }
            .filter { it.cx >= boxLeft - em * 0.05f && it.cx <= boxRight + em * 0.05f }
            .sortedBy { it.cy }
        if (comps.isEmpty() || comps.size > 3) return null
        val axis = baseline - em * 0.27f // the math axis, where = + − × sit
        fun nearAxis(c: Comp) = abs(c.cy - axis) <= em * 0.3f
        fun bar(c: Comp) = c.w >= em * 0.22f && c.h <= max(em * 0.14f, 2.5f) && c.w >= c.h * 3f && c.fill >= 0.55f
        fun dot(c: Comp) = c.w <= em * 0.22f && c.h <= em * 0.22f && c.w.toFloat() / c.h in 0.4f..2.5f && c.fill >= 0.45f
        fun similar(a: Comp, b: Comp) = a.w.toFloat() / b.w in 0.6f..1.65f &&
            min(a.x1, b.x1) - max(a.x0, b.x0) >= min(a.w, b.w) * 0.6f

        when (comps.size) {
            1 -> {
                val c = comps[0]
                if (bar(c)) return if (nearAxis(c)) '−' else null
                if (dot(c)) return if (nearAxis(c) && c.w >= 1) '·' else null
                return single(c, ink, w, em, baseline, ::nearAxis)
            }
            2 -> {
                val (a, b) = comps
                if (bar(a) && bar(b) && similar(a, b) && b.y0 - a.y1 <= em * 0.45f && nearAxis(a) && nearAxis(b)) return '='
                // A sign over a bar: ±, ≤, ≥.
                if (bar(b) && !bar(a) && b.y0 - a.y1 <= em * 0.35f && a.w >= em * 0.2f) {
                    when (single(a, ink, w, em, baseline) { true }) {
                        '+' -> return '±'
                        '<' -> return '≤'
                        '>' -> return '≥'
                    }
                }
                return null
            }
            else -> {
                val (a, b, c) = comps
                if (bar(a) && bar(b) && bar(c) && similar(a, b) && similar(b, c)) return '≡'
                if (dot(a) && bar(b) && dot(c) && abs(a.cx - b.cx) <= b.w * 0.2f && abs(c.cx - b.cx) <= b.w * 0.2f) return '÷'
                return null
            }
        }
    }

    /** One connected shape: + × → ← < > Δ. */
    private fun single(c: Comp, ink: BooleanArray, w: Int, em: Float, baseline: Float, nearAxis: (Comp) -> Boolean): Char? {
        if (c.w < em * 0.2f || c.h < em * 0.12f) return null
        fun at(x: Int, y: Int) = ink[y * w + x]
        val t = max(1, (min(c.w, c.h) * 0.12f).toInt())
        // Coverage of the central row and column bands.
        val midY = c.y0 + c.h / 2
        val midX = c.x0 + c.w / 2
        val rowCov = (c.x0..c.x1).count { x -> (midY - t..midY + t).any { y -> at(x, y.coerceIn(c.y0, c.y1)) } }.toFloat() / c.w
        val colCov = (c.y0..c.y1).count { y -> (midX - t..midX + t).any { x -> at(x.coerceIn(c.x0, c.x1), y) } }.toFloat() / c.h
        val cw = max(1, c.w / 5)
        val ch = max(1, c.h / 5)
        fun corner(x0: Int, y0: Int) = (y0 until y0 + ch).any { y -> (x0 until x0 + cw).any { x -> at(x, y) } }
        val corners = listOf(
            corner(c.x0, c.y0), corner(c.x1 - cw + 1, c.y0), corner(c.x0, c.y1 - ch + 1), corner(c.x1 - cw + 1, c.y1 - ch + 1),
        )
        val aspect = c.w.toFloat() / c.h
        if (aspect in 0.6f..1.7f && c.fill < 0.55f) {
            if (rowCov >= 0.85f && colCov >= 0.85f && corners.none { it } && nearAxis(c)) return '+'
            // "×" floats above the baseline; a letter x stands on it.
            if (corners.count { it } >= 4 && rowCov < 0.7f && colCov < 0.7f && at(midX, midY) && nearAxis(c) &&
                (c.y1 <= baseline - em * 0.04f || (c.h >= em * 0.52f && c.y1 + 1.5f <= baseline))
            ) return '×'
            // Triangle: full bottom edge, narrow apex, hollow.
            val bottom = (c.x0..c.x1).count { x -> (c.y1 - t..c.y1).any { y -> at(x, y) } }.toFloat() / c.w
            val top = (c.x0..c.x1).count { x -> (c.y0..c.y0 + t).any { y -> at(x, y) } }.toFloat() / c.w
            if (bottom >= 0.85f && top <= 0.35f && c.h >= em * 0.45f && !at(midX, c.y0 + c.h * 2 / 3)) return 'Δ'
            // Angle brackets: compare where the ink is in the top, middle and bottom bands.
            fun meanX(y0: Int, y1: Int): Float? {
                var sum = 0f
                var n = 0
                for (y in y0..y1) for (x in c.x0..c.x1) if (at(x, y)) { sum += x; n++ }
                return if (n == 0) null else sum / n
            }
            val band = max(1, c.h / 6)
            val topX = meanX(c.y0, c.y0 + band)
            val midXm = meanX(midY - band / 2, midY + band / 2)
            val botX = meanX(c.y1 - band, c.y1)
            if (topX != null && midXm != null && botX != null && c.fill < 0.4f) {
                val d = c.w * 0.3f
                if (topX - midXm > d && botX - midXm > d && abs(topX - botX) < c.w * 0.2f) return '<'
                if (midXm - topX > d && midXm - botX > d && abs(topX - botX) < c.w * 0.2f) return '>'
            }
        }
        // Arrows: a shaft with a head that is much taller than the shaft.
        if (aspect >= 1.5f && nearAxis(c)) {
            val q = max(1, c.w / 5)
            fun height(x: Int) = (c.y0..c.y1).count { y -> at(x, y) }
            val left = (c.x0 until c.x0 + q).maxOf { height(it) }
            val right = (c.x1 - q + 1..c.x1).maxOf { height(it) }
            val middle = (c.x0 + 2 * q until c.x1 - 2 * q).minOfOrNull { height(it) } ?: return null
            if (right >= middle * 2.2f && right >= left * 1.6f) return '→'
            if (left >= middle * 2.2f && left >= right * 1.6f) return '←'
        }
        return null
    }

    private fun components(ink: BooleanArray, w: Int, h: Int): List<Comp> {
        val label = IntArray(w * h)
        val out = mutableListOf<Comp>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!ink[start] || label[start] != 0) continue
            val c = Comp(start % w, start / w, start % w, start / w)
            out += c
            var sp = 0
            stack[sp++] = start
            label[start] = out.size
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                c.pixels++
                if (x < c.x0) c.x0 = x
                if (x > c.x1) c.x1 = x
                if (y < c.y0) c.y0 = y
                if (y > c.y1) c.y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (ink[q] && label[q] == 0) {
                        label[q] = out.size
                        stack[sp++] = q
                    }
                }
            }
            if (out.size > 12) return out // text, not a symbol
        }
        return out
    }
}
