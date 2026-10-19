package com.raptibooks.core.model

import kotlin.math.roundToInt

/** Identifies one text of a block: its body text (part 0), a table cell, or an answer option. */
object MarkKey {
    fun of(block: Int, part: Int = 0): Long = (block.toLong() shl 20) or part.toLong()
    fun block(key: Long): Int = (key shr 20).toInt()
    fun part(key: Long): Int = (key and 0xFFFFF).toInt()

    /** Part number of the table cell at [row], [col]. */
    fun cell(row: Int, col: Int): Int = row.coerceIn(0, 4000) * 256 + col.coerceIn(0, 254) + 1

    /** Part number of the [n]-th answer option of a multiple-choice block. */
    fun option(n: Int): Int = n.coerceIn(0, 100_000) + 1
}

/**
 * A freehand line drawn on the picture of block [blockIndex]. [points] are x, y pairs in 0..1
 * of the picture's width and height, so the line stays where it was drawn at any size.
 */
class InkStroke(
    val id: Long,
    val bookId: Long,
    val blockIndex: Int,
    val color: MarkColor,
    val points: FloatArray,
)

object InkCodec {
    /** "x,y;x,y;…" with coordinates in 1/10000 of the picture. */
    fun encode(points: FloatArray): String = buildString {
        var i = 0
        while (i + 1 < points.size) {
            if (i > 0) append(';')
            append((points[i] * 10000).roundToInt().coerceIn(0, 10000)).append(',').append((points[i + 1] * 10000).roundToInt().coerceIn(0, 10000))
            i += 2
        }
    }

    fun decode(encoded: String): FloatArray {
        if (encoded.isEmpty()) return FloatArray(0)
        val parts = encoded.split(';')
        val out = FloatArray(parts.size * 2)
        var n = 0
        for (part in parts) {
            val xy = part.split(',')
            val x = xy.getOrNull(0)?.toIntOrNull() ?: continue
            val y = xy.getOrNull(1)?.toIntOrNull() ?: continue
            out[n++] = x / 10000f
            out[n++] = y / 10000f
        }
        return out.copyOf(n)
    }
}
