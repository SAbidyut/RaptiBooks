package com.raptibooks.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.raptibooks.core.ocr.TesseractOcrEngine
import com.raptibooks.core.structure.analysis.LineBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The text printed in a picture of the book (a figure's labels, a formula, a page kept as a
 * picture), read with the OCR so that it can be copied. The engine is made for each picture and
 * closed after it: its language data is large and pictures are read only when asked.
 */
internal class PictureText(private val context: Context) {
    private val lock = Mutex()

    /** The picture's lines of text, or null when the OCR is missing or reads nothing. */
    suspend fun read(path: String): String? = lock.withLock {
        withContext(Dispatchers.Default) {
            val picture = try {
                load(path)
            } catch (e: OutOfMemoryError) {
                null
            } ?: return@withContext null
            try {
                val engine = try {
                    TesseractOcrEngine(context)
                } catch (e: Exception) {
                    return@withContext null
                } catch (e: LinkageError) {
                    return@withContext null
                }
                val page = try {
                    // (in points at 300 dpi: the pictures are cut at about that)
                    engine.recognize(picture, 0, picture.width * PT_PER_PX, picture.height * PT_PER_PX).page
                } finally {
                    engine.close()
                }
                LineBuilder().build(page.fragments, splitColumns = true)
                    .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                    .joinToString("\n") { it.text.trim() }
                    .trim().takeIf { it.isNotEmpty() }
            } finally {
                picture.recycle()
            }
        }
    }

    /**
     * The picture on white paper (formulas are kept as ink: black with the darkness as opacity),
     * no larger than the OCR needs.
     */
    private fun load(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = Bitmap.createBitmap(src.width + 2 * MARGIN, src.height + 2 * MARGIN, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, MARGIN.toFloat(), MARGIN.toFloat(), null)
        }
        src.recycle()
        return out
    }

    private companion object {
        const val PT_PER_PX = 72f / 300f
        const val MAX_SIDE = 4000

        /** White around the picture: the OCR misses text that touches the edge. */
        const val MARGIN = 24
    }
}
