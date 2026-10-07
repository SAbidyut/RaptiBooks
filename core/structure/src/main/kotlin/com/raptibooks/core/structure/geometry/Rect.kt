package com.raptibooks.core.structure.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned rectangle in page "display points" (1/72 inch, origin at the top-left of the
 * visible page, y growing downwards).
 */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun union(o: Rect): Rect = Rect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun intersect(o: Rect): Rect? {
        val l = max(left, o.left)
        val t = max(top, o.top)
        val r = min(right, o.right)
        val b = min(bottom, o.bottom)
        return if (r > l && b > t) Rect(l, t, r, b) else null
    }

    fun intersects(o: Rect, slack: Float = 0f): Boolean =
        left - slack < o.right && o.left < right + slack && top - slack < o.bottom && o.top < bottom + slack

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun contains(o: Rect, slack: Float = 0f): Boolean =
        o.left >= left - slack && o.right <= right + slack && o.top >= top - slack && o.bottom <= bottom + slack

    fun inset(d: Float): Rect = Rect(left + d, top + d, right - d, bottom - d)

    fun outset(d: Float): Rect = Rect(left - d, top - d, right + d, bottom + d)

    /** Fraction of this rectangle covered by [o]. */
    fun coveredBy(o: Rect): Float {
        if (area <= 0f) return 0f
        return (intersect(o)?.area ?: 0f) / area
    }

    fun horizontalOverlap(o: Rect): Float = max(0f, min(right, o.right) - max(left, o.left))

    fun verticalOverlap(o: Rect): Float = max(0f, min(bottom, o.bottom) - max(top, o.top))

    fun clamp(maxWidth: Float, maxHeight: Float): Rect =
        Rect(left.coerceIn(0f, maxWidth), top.coerceIn(0f, maxHeight), right.coerceIn(0f, maxWidth), bottom.coerceIn(0f, maxHeight))

    companion object {
        fun bounding(rects: Iterable<Rect>): Rect? {
            var acc: Rect? = null
            for (r in rects) acc = acc?.union(r) ?: r
            return acc
        }
    }
}
