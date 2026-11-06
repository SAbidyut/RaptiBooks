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

    /** A displayed formula or calculation step kept as text ("P = V × I = 230 × 0.43 ≈ 99 W"). */
    FORMULA,

    /** Program code or terminal output set in a monospace font: line breaks and indentation kept. */
    CODE,
}

data class ParagraphBlock(
    override val index: Int,
    override val pageIndex: Int,
    val text: String,
    val styles: List<InlineStyle> = emptyList(),
    val kind: ParagraphKind = ParagraphKind.BODY,
    /** Words shown as printed, in the text at their [InlinePicture.start]. */
    val pictures: List<InlinePicture> = emptyList(),
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
    val pictures: List<InlinePicture> = emptyList(),
) : ContentBlock {
    override val plainText: String get() = "$marker $text"
}

/**
 * A word (or a few) whose text could not be read from the PDF — script the text layer garbles,
 * such as Arabic in a scan converted with Latin-only OCR — shown as printed, inside the text.
 * It stands at character [start] of the block's text (an [InlinePicture.MARKER]); [path] is an
 * ink image (black with alpha, drawn in the text colour), [height] is in ems of the text.
 */
data class InlinePicture(val start: Int, val path: String, val pixelWidth: Int, val pixelHeight: Int, val height: Float) {
    /** Width in ems of the text. */
    val width: Float get() = if (pixelHeight <= 0) height else height * pixelWidth / pixelHeight

    companion object {
        /** Placeholder character standing for a picture in the text. */
        const val MARKER = '\u2B1A'
    }
}

enum class ImageKind {
    FIGURE, DIAGRAM, PHOTO, PAGE,

    /**
     * A display equation, stored as an ink mask (black with alpha) so the reader can draw it in
     * the text colour of any theme; [ImageBlock.caption] is the equation number.
     */
    EQUATION,
}

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
    /** Text content of the picture (an equation), for search. */
    val altText: String? = null,
) : ContentBlock {
    override val plainText: String
        get() = listOfNotNull(altText, caption).joinToString(" ")
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
    val picture: Boolean get() = flags and PICTURE != 0

    companion object {
        const val BOLD = 1
        const val ITALIC = 2
        const val SUPERSCRIPT = 4
        const val SUBSCRIPT = 8

        /**
         * A word shown as printed (see [InlinePicture]); bits from [PICTURE_SHIFT] up carry its
         * number, while the book is being converted.
         */
        const val PICTURE = 16
        const val PICTURE_SHIFT = 5

        fun pictureFlags(id: Int): Int = PICTURE or (id shl PICTURE_SHIFT)
        fun pictureId(flags: Int): Int = flags ushr PICTURE_SHIFT

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
