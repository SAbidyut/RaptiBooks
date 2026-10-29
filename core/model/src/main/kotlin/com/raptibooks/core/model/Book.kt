package com.raptibooks.core.model

/** (stored by name: new values may go anywhere) [ORIGINAL_ONLY]: opened from another app, not converted until asked. */
enum class BookStatus { QUEUED, PROCESSING, READY, FAILED, CANCELLED, ORIGINAL_ONLY }

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

/** The colours of the reader's marking pen. [id] is what is stored in the database. */
enum class MarkColor(val id: Int, val argb: Long) {
    RED(1, 0xFFE53935),
    BLUE(2, 0xFF1E88E5),
    YELLOW(3, 0xFFF9B90C),
    GREEN(4, 0xFF43A047),
    ;

    companion object {
        fun fromId(id: Int): MarkColor? = entries.firstOrNull { it.id == id }
    }
}

/**
 * An underlined range [start, end) of a text of block [blockIndex]. [part] tells which text of
 * the block: 0 for its body text, else a table cell or answer option (see [MarkKey]).
 */
data class Highlight(
    val id: Long,
    val bookId: Long,
    val blockIndex: Int,
    val start: Int,
    val end: Int,
    val color: MarkColor,
    val part: Int = 0,
)

/** A range of a block's text with its colour; what is stored per block. */
data class MarkSpan(val start: Int, val end: Int, val color: MarkColor)

object Marking {
    /**
     * Result of marking [ranges] with [color] (or erasing them when [color] is null) on top of
     * the [existing] spans of one block: overlapped parts of older marks are cut away, touching
     * marks of the same colour are joined.
     */
    fun apply(existing: List<MarkSpan>, ranges: List<IntRange>, color: MarkColor?): List<MarkSpan> {
        var spans = existing
        for (r in ranges) {
            val start = r.first
            val end = r.last + 1
            if (end <= start) continue
            val next = ArrayList<MarkSpan>(spans.size + 2)
            for (s in spans) {
                if (s.end <= start || s.start >= end) {
                    next += s
                    continue
                }
                if (s.start < start) next += MarkSpan(s.start, start, s.color)
                if (s.end > end) next += MarkSpan(end, s.end, s.color)
            }
            if (color != null) next += MarkSpan(start, end, color)
            spans = next
        }
        val sorted = spans.sortedBy { it.start }
        val out = ArrayList<MarkSpan>(sorted.size)
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.color == s.color && s.start <= last.end) {
                out[out.size - 1] = MarkSpan(last.start, maxOf(last.end, s.end), s.color)
            } else {
                out += s
            }
        }
        return out
    }
}
