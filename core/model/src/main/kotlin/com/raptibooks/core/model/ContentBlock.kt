package com.raptibooks.core.model

/**
 * One unit of reflowed book content. A converted book is a flat, ordered list of blocks;
 * [index] is the position in that list and [pageIndex] the PDF page it came from (used for
 * jumping between the reflowed text and the original page view).
 */
sealed interface ContentBlock {
    val index: Int
    val pageIndex: Int

    /** Plain text used for search, bookmarks and snippets. */
    val plainText: String
}

data class HeadingBlock(
    override val index: Int,
    override val pageIndex: Int,
    /** 1 = chapter, 2 = section, 3 = subsection, 4 = minor heading. */
    val level: Int,
    val text: String,
) : ContentBlock {
    override val plainText: String get() = text
}

enum class ParagraphKind {
    BODY, QUOTE, CAPTION, CENTERED, NOTE,

    /** A line of a printed table of contents; text is "title\tpage" (new values go last: stored by ordinal). */
    TOC,

    /** Answer options of a multiple-choice question; one "marker\ttext" per line. */
    OPTIONS,
}

data class ParagraphBlock(
    override val index: Int,
    override val pageIndex: Int,
    val text: String,
    val styles: List<InlineStyle> = emptyList(),
    val kind: ParagraphKind = ParagraphKind.BODY,
) : ContentBlock {
    override val plainText: String get() = text
}

data class ListItemBlock(
    override val index: Int,
    override val pageIndex: Int,
    /** The bullet or enumerator as printed, e.g. "•", "1.", "(ক)". */
    val marker: String,
    val text: String,
    val styles: List<InlineStyle> = emptyList(),
    val level: Int = 0,
) : ContentBlock {
    override val plainText: String get() = "$marker $text"
}

enum class ImageKind { FIGURE, DIAGRAM, PHOTO, PAGE }

data class ImageBlock(
    override val index: Int,
    override val pageIndex: Int,
    /** Absolute path of the extracted image (PNG/JPEG) in app storage. */
    val path: String,
    val width: Int,
    val height: Int,
    val caption: String? = null,
    val kind: ImageKind = ImageKind.FIGURE,
    /** Printed width of the figure in points (0 if unknown). */
    val sourceWidth: Float = 0f,
    /** Typical size (pt) of the text inside the figure (0 if none/unknown); used to keep labels readable. */
    val textSize: Float = 0f,
) : ContentBlock {
    override val plainText: String get() = caption ?: ""
}

data class TableBlock(
    override val index: Int,
    override val pageIndex: Int,
    val rows: List<List<String>>,
    val headerRows: Int = 0,
    /** Snapshot of the table as printed, for complex layouts. */
    val imagePath: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val caption: String? = null,
) : ContentBlock {
    override val plainText: String
        get() = buildString {
            caption?.let { append(it).append('\n') }
            rows.forEach { append(it.joinToString(" | ")).append('\n') }
        }.trimEnd()
}

data class SeparatorBlock(
    override val index: Int,
    override val pageIndex: Int,
) : ContentBlock {
    override val plainText: String get() = ""
}

/** A styled range inside a block's text. */
data class InlineStyle(val start: Int, val end: Int, val flags: Int) {
    val bold: Boolean get() = flags and BOLD != 0
    val italic: Boolean get() = flags and ITALIC != 0
    val superscript: Boolean get() = flags and SUPERSCRIPT != 0
    val subscript: Boolean get() = flags and SUBSCRIPT != 0

    companion object {
        const val BOLD = 1
        const val ITALIC = 2
        const val SUPERSCRIPT = 4
        const val SUBSCRIPT = 8

        /** Compact "start:end:flags;..." form used for storage. */
        fun encode(styles: List<InlineStyle>): String =
            styles.joinToString(";") { "${it.start}:${it.end}:${it.flags}" }

        fun decode(encoded: String?): List<InlineStyle> {
            if (encoded.isNullOrEmpty()) return emptyList()
            return encoded.split(';').mapNotNull { part ->
                val p = part.split(':')
                if (p.size != 3) return@mapNotNull null
                val start = p[0].toIntOrNull() ?: return@mapNotNull null
                val end = p[1].toIntOrNull() ?: return@mapNotNull null
                val flags = p[2].toIntOrNull() ?: return@mapNotNull null
                if (end > start) InlineStyle(start, end, flags) else null
            }
        }
    }
}
