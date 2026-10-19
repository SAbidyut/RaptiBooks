package com.raptibooks.core.pdf

import android.graphics.Path
import android.graphics.PointF
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.ImageRegion
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.VectorPath
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Records where raster images are drawn and which vector paths are painted, in display
 * points. Images are never decoded (figures are rendered from the page later), which keeps
 * memory flat even for image-heavy PDFs.
 */
internal class GraphicsCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {

    val images = ArrayList<ImageRegion>()
    val paths = ArrayList<VectorPath>()
    private val transform = DisplayTransform(page)

    /** Axis-aligned pieces of the current path, in user space. */
    private class Piece(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rect: Boolean)

    private val pieces = ArrayList<Piece>()
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var complex = false
    private var segments = 0
    private val current = PointF()
    private val subpathStart = PointF()

    fun collect() = processPage(page)

    private fun include(x: Float, y: Float) {
        minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
    }

    private fun reset() {
        pieces.clear()
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        complex = false
        segments = 0
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
        include(p0.x, p0.y); include(p1.x, p1.y); include(p2.x, p2.y); include(p3.x, p3.y)
        val axisAligned = abs(p0.y - p1.y) < 0.5f || abs(p0.x - p1.x) < 0.5f
        if (axisAligned) {
            pieces += Piece(min(p0.x, p2.x), min(p0.y, p2.y), max(p0.x, p2.x), max(p0.y, p2.y), rect = true)
        } else {
            complex = true
        }
        segments += 4
        current.set(p0.x, p0.y)
        subpathStart.set(p0.x, p0.y)
    }

    override fun moveTo(x: Float, y: Float) {
        include(x, y)
        current.set(x, y)
        subpathStart.set(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        include(x, y)
        addLine(current.x, current.y, x, y)
        current.set(x, y)
    }

    private fun addLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        segments++
        if (abs(x0 - x1) < 0.5f || abs(y0 - y1) < 0.5f) {
            pieces += Piece(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), rect = false)
        } else {
            complex = true
        }
    }

    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        include(x1, y1); include(x2, y2); include(x3, y3)
        complex = true
        segments++
        current.set(x3, y3)
    }

    override fun getCurrentPoint(): PointF = current

    override fun closePath() {
        if (current.x != subpathStart.x || current.y != subpathStart.y) addLine(current.x, current.y, subpathStart.x, subpathStart.y)
        current.set(subpathStart.x, subpathStart.y)
    }

    override fun endPath() = reset()

    override fun clip(windingRule: Path.FillType) {
        // The clipping path is followed by endPath(); nothing is painted.
    }

    override fun strokePath() = paint(stroked = true, filled = false)

    override fun fillPath(windingRule: Path.FillType) = paint(stroked = false, filled = true)

    override fun fillAndStrokePath(windingRule: Path.FillType) = paint(stroked = true, filled = true)

    override fun shadingFill(shadingName: COSName?) {
        // Gradients fill the clip region; treated as decoration.
    }

    override fun drawImage(pdImage: PDImage) {
        val ctm = graphicsState.currentTransformationMatrix
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (u, v) -> ctm.transformPoint(u, v) }
        val xs = corners.map { transform.mapAbsoluteX(it.x, it.y) }
        val ys = corners.map { transform.mapAbsoluteY(it.x, it.y) }
        val r = Rect(xs.min(), ys.min(), xs.max(), ys.max())
        if (r.width > 1f && r.height > 1f && images.size < MAX_ITEMS) images += ImageRegion(r)
    }

    // Type 3 glyphs are drawn with paths; they are text, not graphics.
    override fun showType3Glyph(textRenderingMatrix: Matrix, font: PDType3Font, code: Int, unicode: String?, displacement: Vector) = Unit

    override fun showType3Glyph(textRenderingMatrix: Matrix, font: PDType3Font, code: Int, displacement: Vector) = Unit

    private fun paint(stroked: Boolean, filled: Boolean) {
        if (minX > maxX || paths.size >= MAX_ITEMS) {
            reset(); return
        }
        val lightFill = filled && isLight()
        val color = paintColor(filled)
        // A filled polygon made of line segments (e.g. a chart bar) is one shape, not edges.
        val filledPolygon = filled && pieces.none { it.rect }
        if (!complex && pieces.isNotEmpty() && pieces.size <= 400 && !filledPolygon) {
            // Decompose rules and rectangles: table borders are often one path with many parts.
            for (p in pieces) {
                val r = transform.mapAbsoluteRect(p.x0, p.y0, p.x1, p.y1)
                val lw = if (stroked) max(lineWidth(), 0.1f) else 0f
                val shape = classify(r, p.rect, lw)
                val bounds = if (r.height < lw) Rect(r.left, r.top - lw / 2, r.right, r.bottom + lw / 2)
                else if (r.width < lw) Rect(r.left - lw / 2, r.top, r.right + lw / 2, r.bottom) else r
                if (!filled && !stroked) continue
                if (!stroked && (r.width < 0.2f || r.height < 0.2f)) continue
                paths += VectorPath(bounds, shape, stroked, filled, lightFill, if (p.rect) 4 else 1, color)
            }
        } else {
            val r = transform.mapAbsoluteRect(minX, minY, maxX, maxY)
            paths += VectorPath(r, if (complex) PathShape.COMPLEX else PathShape.RECTANGLE, stroked, filled, lightFill, segments, color)
        }
        reset()
    }

    private fun classify(r: Rect, rect: Boolean, lineWidth: Float): PathShape {
        val h = max(r.height, lineWidth)
        val w = max(r.width, lineWidth)
        return when {
            h <= 2.5f && w >= h * 3f -> PathShape.HORIZONTAL_LINE
            w <= 2.5f && h >= w * 3f -> PathShape.VERTICAL_LINE
            rect -> PathShape.RECTANGLE
            else -> PathShape.RECTANGLE
        }
    }

    private fun lineWidth(): Float {
        val ctm = graphicsState.currentTransformationMatrix
        return graphicsState.lineWidth * max(abs(ctm.scalingFactorX), abs(ctm.scalingFactorY))
    }

    private fun paintColor(filled: Boolean): Int = try {
        val c = if (filled) graphicsState.nonStrokingColor else graphicsState.strokingColor
        c.toRGB() and 0xFFFFFF
    } catch (_: Exception) {
        -1
    }

    private fun isLight(): Boolean = try {
        val rgb = graphicsState.nonStrokingColor.toRGB()
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        r > 238 && g > 238 && b > 238
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val MAX_ITEMS = 25_000
    }
}
