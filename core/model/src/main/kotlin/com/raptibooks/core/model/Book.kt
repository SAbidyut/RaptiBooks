package com.raptibooks.core.model

enum class BookStatus { QUEUED, PROCESSING, READY, FAILED, CANCELLED }

/** How OCR is used while converting a book. */
enum class OcrMode {
    /** OCR only pages that have no usable text layer (scans, broken fonts). */
    AUTO,

    /** OCR every page, ignoring the PDF text layer. */
    ALWAYS,

    /** Never OCR; pages without text become page images. */
    NEVER,
}

data class Book(
    val id: Long,
    val title: String,
    val author: String?,
    val fileName: String,
    val pdfPath: String,
    val coverPath: String?,
    val pageCount: Int,
    val fileSize: Long,
    val status: BookStatus,
    /** Conversion progress in 0..1. */
    val progress: Float,
    val progressMessage: String?,
    val errorMessage: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val position: ReadingPosition,
    /** Reading progress in 0..1. */
    val readingProgress: Float,
    val blockCount: Int,
    val language: String?,
    val usedOcr: Boolean,
    val ocrMode: OcrMode,
) {
    val isReadable: Boolean get() = status == BookStatus.READY && blockCount > 0
    val isConverting: Boolean get() = status == BookStatus.QUEUED || status == BookStatus.PROCESSING
}

data class ReadingPosition(val blockIndex: Int = 0, val offset: Int = 0)

data class TocEntry(
    val title: String,
    val level: Int,
    val blockIndex: Int,
    val pageIndex: Int,
)

/**
 * Blocks [startIndex]..[endIndex] were printed inside a tinted callout box (worked example,
 * definition, note, warning); colours are 0xRRGGBB.
 */
data class BoxSpan(
    val startIndex: Int,
    val endIndex: Int,
    val fill: Int,
    val accent: Int,
)

data class Bookmark(
    val id: Long,
    val bookId: Long,
    val blockIndex: Int,
    val pageIndex: Int,
    val snippet: String,
    val createdAt: Long,
)

data class SearchHit(
    val blockIndex: Int,
    val pageIndex: Int,
    val snippet: String,
    /** Range of the match inside [snippet]. */
    val matchStart: Int,
    val matchEnd: Int,
)
