package com.raptibooks.core.model

enum class ReaderTheme { LIGHT, SEPIA, DARK, BLACK }

enum class ReaderFont {
    /** Tiro Bangla – a book serif covering Bangla and Latin. */
    SERIF,

    /** Hind Siliguri – a clean sans covering Bangla and Latin. */
    SANS,

    /** Device serif with the system Bangla fallback. */
    SYSTEM_SERIF,

    /** Device sans-serif with the system Bangla fallback. */
    SYSTEM_SANS,
}

data class ReaderSettings(
    /** Body text size in sp. */
    val fontSize: Float = 18f,
    val font: ReaderFont = ReaderFont.SERIF,
    /** Line height as a multiple of the font size. */
    val lineSpacing: Float = 1.6f,
    /** Horizontal page margin in dp. */
    val margin: Int = 20,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val justify: Boolean = true,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        const val MIN_FONT_SIZE = 12f
        const val MAX_FONT_SIZE = 32f
        const val MIN_LINE_SPACING = 1.2f
        const val MAX_LINE_SPACING = 2.2f
        const val MIN_MARGIN = 4
        const val MAX_MARGIN = 48
    }
}
