package com.raptibooks.core.ocr

import android.graphics.Bitmap
import com.raptibooks.core.structure.raw.RawPage
import java.io.Closeable

class OcrUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Result of recognising one rendered page. */
class OcrPageResult(
    /** Recognised words as positioned fragments, in page display points. */
    val page: RawPage,
    val wordCount: Int,
    /** Mean word confidence 0..100. */
    val meanConfidence: Float,
)

interface OcrEngine : Closeable {
    /**
     * Recognises [bitmap], a rendering of page [pageIndex] whose size in points is
     * [pageWidth] x [pageHeight].
     */
    fun recognize(bitmap: Bitmap, pageIndex: Int, pageWidth: Float, pageHeight: Float): OcrPageResult
}
