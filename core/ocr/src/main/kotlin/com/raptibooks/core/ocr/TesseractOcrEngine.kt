package com.raptibooks.core.ocr

import android.content.Context
import android.graphics.Bitmap
import org.bytedeco.tesseract.TessBaseAPI
import java.io.File

/**
 * Offline OCR with Tesseract 5 (LSTM) and the bundled `ben` + `eng` models, so mixed
 * Bangla/English scans work without a network connection.
 */
class TesseractOcrEngine(
    context: Context,
    private val languages: String = DEFAULT_LANGUAGES,
) : OcrEngine {

    private val dataDir = File(context.filesDir, "ocr")
    private var api: TessBaseAPI? = null

    init {
        TessdataInstaller.install(context, dataDir)
        val created = try {
            TessBaseAPI()
        } catch (t: Throwable) {
            // UnsatisfiedLinkError on unsupported CPUs, missing libraries, …
            throw OcrUnavailableException("OCR engine could not be loaded on this device", t)
        }
        if (created.Init(dataDir.absolutePath, languages) != 0) {
            created.close()
            throw OcrUnavailableException("OCR language data could not be loaded")
        }
        TesseractPage.configure(created)
        api = created
    }

    override fun recognize(bitmap: Bitmap, pageIndex: Int, pageWidth: Float, pageHeight: Float): OcrPageResult {
        val tess = api ?: throw IllegalStateException("OCR engine closed")
        return TesseractPage.recognize(tess, toGray(bitmap), bitmap.width, bitmap.height, pageIndex, pageWidth, pageHeight)
    }

    private fun toGray(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val c = row[x]
                val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                out[base + x] = lum.toByte()
            }
        }
        return out
    }

    override fun close() {
        api?.let {
            try {
                it.End()
            } finally {
                it.close()
            }
        }
        api = null
    }

    companion object {
        const val DEFAULT_LANGUAGES = "ben+eng"

        /** Recommended render size for OCR (see [TesseractPage.renderWidthFor]). */
        fun renderWidthFor(pageWidth: Float, pageHeight: Float, lowMemory: Boolean): Int =
            TesseractPage.renderWidthFor(pageWidth, pageHeight, lowMemory)
    }
}
