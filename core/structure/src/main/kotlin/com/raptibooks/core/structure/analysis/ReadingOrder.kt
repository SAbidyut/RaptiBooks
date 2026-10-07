package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Orders page elements for reading, handling multi-column layouts.
 *
 * Column gutters are vertical strips that no narrow element crosses, with enough text on both
 * sides. Elements crossing a gutter (titles, full-width figures, footnote rules) split the page
 * into horizontal bands; inside a band elements are read column by column.
 */
object ReadingOrder {

    class Placed<T>(val item: T, val bounds: Rect, val column: Rect)

    fun <T> order(items: List<T>, boundsOf: (T) -> Rect, emOf: (T) -> Float): List<Placed<T>> {
        if (items.isEmpty()) return emptyList()
        val area = Rect.bounding(items.map(boundsOf))!!
        val ems = items.map(emOf).filter { it > 0f }.sorted()
        val em = if (ems.isEmpty()) 10f else ems[ems.size / 2]
        val gutters = findGutters(items, boundsOf, area, em)
        if (gutters.isEmpty()) {
            val sorted = sortTopDown(items, boundsOf)
            return sorted.map { Placed(it, boundsOf(it), area) }
        }

        val result = mutableListOf<Placed<T>>()
        val sorted = items.sortedBy { boundsOf(it).top }
        val band = mutableListOf<T>()
        fun columnIndex(r: Rect): Int = gutters.count { g -> r.centerX > g.second }
        fun crosses(r: Rect) = gutters.any { g -> r.left < g.first - 1f && r.right > g.second + 1f }
        fun flush() {
            if (band.isEmpty()) return
            val byCol = band.groupBy { columnIndex(boundsOf(it)) }.toSortedMap()
            for ((col, members) in byCol) {
                val colLeft = if (col == 0) area.left else gutters[col - 1].second
                val colRight = if (col >= gutters.size) area.right else gutters[col].first
                val colRect = Rect(colLeft, area.top, colRight, area.bottom)
                for (m in sortTopDown(members, boundsOf)) result += Placed(m, boundsOf(m), colRect)
            }
            band.clear()
        }
        for (it in sorted) {
            val b = boundsOf(it)
            if (crosses(b)) {
                flush()
                result += Placed(it, b, area)
            } else {
                band += it
            }
        }
        flush()
        return result
    }

    private fun medianWidth(rects: List<Rect>): Float = rects.map { it.width }.sorted()[rects.size / 2]

    private fun <T> sortTopDown(items: List<T>, boundsOf: (T) -> Rect): List<T> =
        items.sortedWith { a, b ->
            val ra = boundsOf(a)
            val rb = boundsOf(b)
            val sameRow = ra.verticalOverlap(rb) > 0.5f * min(ra.height, rb.height)
            if (sameRow) ra.left.compareTo(rb.left) else ra.top.compareTo(rb.top)
        }

    /** Returns gutters as (left, right) x-ranges, sorted left to right. */
    private fun <T> findGutters(items: List<T>, boundsOf: (T) -> Rect, area: Rect, em: Float): List<Pair<Float, Float>> {
        if (items.size < 6 || area.width < 100f) return emptyList()
        val narrow = items.map(boundsOf).filter { it.width < area.width * 0.62f }
        if (narrow.size < 6) return emptyList()
        val step = 1f
        val bins = (area.width / step).toInt() + 2
        val cover = FloatArray(bins)
        for (r in narrow) {
            val a = ((r.left - area.left) / step).toInt().coerceIn(0, bins - 1)
            val b = ((r.right - area.left) / step).toInt().coerceIn(0, bins - 1)
            for (k in a..b) cover[k] += r.height
        }
        val minGap = max(8f, em * 0.7f)
        val lo = (area.width * 0.15f / step).toInt()
        val hi = (area.width * 0.85f / step).toInt()
        // A few elements (a centred title, a wide figure caption) may cross a real gutter, so
        // "empty" means covered by at most 10% of the narrow text height.
        val limit = narrow.sumOf { it.height.toDouble() }.toFloat() * 0.1f
        val gutters = mutableListOf<Pair<Float, Float>>()
        var k = lo
        while (k < hi) {
            if (cover[k] <= limit) {
                val start = k
                while (k < hi && cover[k] <= limit) k++
                // Use the least covered part of the run as the gutter proper.
                val min = (start until k).minOf { cover[it] }
                val core = (start until k).filter { cover[it] <= min + 0.01f }
                val gl = area.left + core.first() * step
                val gr = area.left + (core.last() + 1) * step
                if ((k - start) * step >= minGap && gr - gl >= minOf(minGap, 4f)) gutters += gl to gr
            } else {
                k++
            }
        }
        // Require real columns on both sides of each gutter.
        return gutters.filter { (gl, gr) ->
            val left = narrow.filter { it.right <= gl + 0.5f }
            val right = narrow.filter { it.left >= gr - 0.5f }
            val lh = left.sumOf { it.height.toDouble() }
            val rh = right.sumOf { it.height.toDouble() }
            left.size >= 3 && right.size >= 3 && lh >= area.height * 0.2 && rh >= area.height * 0.2 &&
                abs(left.maxOf { it.bottom } - right.maxOf { it.bottom }) < area.height && // sanity
                // Columns of text are filled with long lines; side-by-side short items (answer
                // options, label lists) are rows to read left to right instead.
                medianWidth(left) >= (gl - area.left) * 0.5f && medianWidth(right) >= (area.right - gr) * 0.5f
        }
    }
}
