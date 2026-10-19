package com.raptibooks.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.raptibooks.core.structure.geometry.Rect
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rasterises PDF pages (whole pages for the original view and OCR, regions for figures). */
interface PageRenderer : Closeable {
    val pageCount: Int

    /** Page size in display points. */
    fun pageSize(index: Int): Pair<Float, Float>

    /** Renders a whole page [widthPx] pixels wide (white background). */
    fun renderPage(index: Int, widthPx: Int): Bitmap

    /** Renders [region] (display points) at [scale] pixels per point. */
    fun renderRegion(index: Int, region: Rect, scale: Float): Bitmap

    /** Whether [renderRegion] renders only the region (cheap for many small regions). */
    val rendersRegionsDirectly: Boolean

    companion object {
        /**
         * Opens the fastest available renderer, falling back to PdfBox when pdfium refuses the file
         * (or is unusable, e.g. a LinkageError from a broken platform build or a JVM test sandbox).
         */
        fun open(file: File, scratchDir: File): PageRenderer =
            try {
                AndroidPageRenderer(file)
            } catch (e: Exception) {
                PdfBoxPageRenderer(file, scratchDir)
            } catch (e: LinkageError) {
                PdfBoxPageRenderer(file, scratchDir)
            }

        /** Scale that fits [region] into [maxWidthPx] and [maxPixels], capped at [maxScale]. */
        fun fitScale(region: Rect, maxWidthPx: Int, maxScale: Float, maxPixels: Int = 6_000_000): Float {
            if (region.width <= 0f || region.height <= 0f) return 1f
            var s = minOf(maxScale, maxWidthPx / region.width)
            val px = region.width * s * region.height * s
            if (px > maxPixels) s *= sqrt(maxPixels / px)
            return max(s, 0.25f)
        }
    }
}

private class AndroidPageRenderer(file: File) : PageRenderer {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Throwable) {
        runCatching { fd.close() }
        throw e
    }

    override val pageCount: Int = renderer.pageCount
    override val rendersRegionsDirectly: Boolean get() = true

    @Synchronized
    override fun pageSize(index: Int): Pair<Float, Float> {
        val page = renderer.openPage(index)
        try {
            return page.width.toFloat() to page.height.toFloat()
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun renderPage(index: Int, widthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = widthPx / page.width.toFloat()
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(scale, scale) }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun renderRegion(index: Int, region: Rect, scale: Float): Bitmap {
        val page = renderer.openPage(index)
        try {
            val w = (region.width * scale).roundToInt().coerceAtLeast(1)
            val h = (region.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-region.left * scale, -region.top * scale)
            }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    override fun close() {
        try {
            renderer.close()
        } finally {
            fd.close()
        }
    }
}

/** Slower pure-Java renderer, used for PDFs that Android's renderer cannot open. */
private class PdfBoxPageRenderer(file: File, scratchDir: File) : PageRenderer {
    private val document: PDDocument = PDDocument.load(
        file, "", MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir.apply { mkdirs() }),
    )
    private val renderer = PDFRenderer(document).apply { isSubsamplingAllowed = true }

    override val pageCount: Int = document.numberOfPages
    override val rendersRegionsDirectly: Boolean get() = false

    override fun pageSize(index: Int): Pair<Float, Float> {
        val t = DisplayTransform(document.getPage(index))
        return t.width to t.height
    }

    @Synchronized
    override fun renderPage(index: Int, widthPx: Int): Bitmap {
        val (w, _) = pageSize(index)
        return renderer.renderImage(index, widthPx / w, ImageType.RGB)
    }

    @Synchronized
    override fun renderRegion(index: Int, region: Rect, scale: Float): Bitmap {
        val full = renderer.renderImage(index, scale, ImageType.RGB)
        val x = (region.left * scale).roundToInt().coerceIn(0, full.width - 1)
        val y = (region.top * scale).roundToInt().coerceIn(0, full.height - 1)
        val w = (region.width * scale).roundToInt().coerceIn(1, full.width - x)
        val h = (region.height * scale).roundToInt().coerceIn(1, full.height - y)
        val crop = Bitmap.createBitmap(full, x, y, w, h)
        if (crop !== full) full.recycle()
        return crop
    }

    override fun close() = document.close()
}
