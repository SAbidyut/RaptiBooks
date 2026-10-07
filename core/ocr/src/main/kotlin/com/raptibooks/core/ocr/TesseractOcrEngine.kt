package com.raptibooks.core.ocr

import android.content.Context
import android.graphics.Bitmap
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.tesseract.ResultIterator
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract
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
        created.SetPageSegMode(tesseract.PSM_AUTO)
        api = created
    }

    override fun recognize(bitmap: Bitmap, pageIndex: Int, pageWidth: Float, pageHeight: Float): OcrPageResult {
        val tess = api ?: throw IllegalStateException("OCR engine closed")
        val w = bitmap.width
        val h = bitmap.height
        val gray = toGray(bitmap)
        val scale = w / pageWidth // pixels per point
        tess.SetImage(gray, w, h, 1, w)
        tess.SetSourceResolution((scale * 72f).toInt().coerceIn(70, 600))
        if (tess.Recognize(null) != 0) {
            tess.Clear()
            return OcrPageResult(RawPage(pageIndex, pageWidth, pageHeight, emptyList(), fromOcr = true), 0, 0f)
        }

        val fragments = ArrayList<TextFragment>()
        val pictures = ArrayList<Rect>()
        var confSum = 0f
        var words = 0
        val l = IntArray(1)
        val t = IntArray(1)
        val r = IntArray(1)
        val b = IntArray(1)

        // Picture blocks become figures.
        val blocks: ResultIterator? = tess.GetIterator()
        if (blocks != null) {
            try {
                do {
                    val type = blocks.BlockType()
                    val picture = type == tesseract.PT_FLOWING_IMAGE || type == tesseract.PT_HEADING_IMAGE ||
                        type == tesseract.PT_PULLOUT_IMAGE
                    if (picture && blocks.BoundingBox(tesseract.RIL_BLOCK, l, t, r, b)) {
                        pictures += Rect(l[0] / scale, t[0] / scale, r[0] / scale, b[0] / scale)
                    }
                } while (blocks.Next(tesseract.RIL_BLOCK))
            } finally {
                blocks.close()
            }
        }

        val iterator: ResultIterator? = tess.GetIterator()
        if (iterator != null) {
            try {
                var lineTop = 0f
                var lineBottom = 0f
                var baseline = 0f
                var size = 10f
                val x1 = IntArray(1)
                val y1 = IntArray(1)
                val x2 = IntArray(1)
                val y2 = IntArray(1)
                do {
                    if (iterator.IsAtBeginningOf(tesseract.RIL_TEXTLINE)) {
                        if (iterator.BoundingBox(tesseract.RIL_TEXTLINE, l, t, r, b)) {
                            lineTop = t[0] / scale
                            lineBottom = b[0] / scale
                        }
                        baseline = if (iterator.Baseline(tesseract.RIL_TEXTLINE, x1, y1, x2, y2)) {
                            (y1[0] + y2[0]) / 2f / scale
                        } else {
                            lineBottom - (lineBottom - lineTop) * 0.2f
                        }
                        size = ((lineBottom - lineTop) / 1.2f).coerceAtLeast(3f)
                    }
                    val ptr: BytePointer = iterator.GetUTF8Text(tesseract.RIL_WORD) ?: continue
                    val text = try {
                        ptr.getString(Charsets.UTF_8.name()).trim()
                    } finally {
                        tesseract.TessDeleteText(ptr)
                    }
                    if (text.isEmpty()) continue
                    val conf = iterator.Confidence(tesseract.RIL_WORD)
                    if (!iterator.BoundingBox(tesseract.RIL_WORD, l, t, r, b)) continue
                    if (conf < 20f && text.length <= 2) continue // speckles
                    confSum += conf
                    words++
                    fragments += TextFragment(
                        text = text,
                        left = l[0] / scale,
                        top = minOf(lineTop, t[0] / scale),
                        right = r[0] / scale,
                        bottom = maxOf(lineBottom, b[0] / scale),
                        baseline = baseline,
                        fontSize = size,
                        fontName = "ocr",
                        spaceWidth = size * 0.3f,
                    )
                } while (iterator.Next(tesseract.RIL_WORD))
            } finally {
                iterator.close()
            }
        }
        tess.Clear()
        val page = RawPage(
            index = pageIndex,
            width = pageWidth,
            height = pageHeight,
            fragments = fragments,
            fromOcr = true,
            ocrPictures = pictures,
        )
        return OcrPageResult(page, words, if (words == 0) 0f else confSum / words)
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

        /** Recommended render size for OCR: ~300 dpi, capped for low-memory devices. */
        fun renderWidthFor(pageWidth: Float, pageHeight: Float, lowMemory: Boolean): Int {
            val maxLong = if (lowMemory) 2400f else 3200f
            val dpiScale = 300f / 72f
            val longSide = maxOf(pageWidth, pageHeight) * dpiScale
            val scale = if (longSide > maxLong) maxLong / maxOf(pageWidth, pageHeight) else dpiScale
            return (pageWidth * scale).toInt().coerceAtLeast(600)
        }
    }
}
