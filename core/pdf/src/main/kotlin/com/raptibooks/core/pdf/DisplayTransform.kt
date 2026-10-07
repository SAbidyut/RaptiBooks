package com.raptibooks.core.pdf

import com.raptibooks.core.structure.geometry.Rect
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlin.math.max
import kotlin.math.min

/**
 * Maps PDF user space to "display points": origin at the top-left of the visible (cropped and
 * rotated) page, y pointing down, 1 unit = 1/72 inch. This is the coordinate system shared by
 * text, graphics, rendering and OCR.
 */
class DisplayTransform(page: PDPage) {
    private val crop = page.cropBox
    private val llx = crop.lowerLeftX
    private val lly = crop.lowerLeftY
    private val w = crop.width
    private val h = crop.height
    val rotation: Int = ((page.rotation % 360) + 360) % 360
    val width: Float = if (rotation == 90 || rotation == 270) h else w
    val height: Float = if (rotation == 90 || rotation == 270) w else h

    /** Maps a point given relative to the crop box's lower-left corner. */
    fun mapRelativeX(ux: Float, uy: Float): Float = when (rotation) {
        90 -> uy
        180 -> w - ux
        270 -> h - uy
        else -> ux
    }

    fun mapRelativeY(ux: Float, uy: Float): Float = when (rotation) {
        90 -> ux
        180 -> uy
        270 -> w - ux
        else -> h - uy
    }

    fun mapAbsoluteX(x: Float, y: Float): Float = mapRelativeX(x - llx, y - lly)

    fun mapAbsoluteY(x: Float, y: Float): Float = mapRelativeY(x - llx, y - lly)

    /** Display-space bounding box of a user-space (absolute) rectangle given by two corners. */
    fun mapAbsoluteRect(x0: Float, y0: Float, x1: Float, y1: Float): Rect {
        val ax = mapAbsoluteX(x0, y0)
        val ay = mapAbsoluteY(x0, y0)
        val bx = mapAbsoluteX(x1, y1)
        val by = mapAbsoluteY(x1, y1)
        return Rect(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
    }

    /**
     * True if a glyph whose text-space x axis points along user-space vector (a, b) reads
     * left-to-right horizontally on the displayed page.
     */
    fun isUpright(a: Float, b: Float): Boolean {
        val dx: Float
        val dy: Float
        when (rotation) {
            90 -> { dx = b; dy = a }
            180 -> { dx = -a; dy = b }
            270 -> { dx = -b; dy = -a }
            else -> { dx = a; dy = -b }
        }
        return dx > 0f && kotlin.math.abs(dy) <= dx * 0.08f
    }
}
