package com.raptibooks.core.structure.raw

import com.raptibooks.core.structure.geometry.Rect

/**
 * A piece of text with its position, as produced by the PDF text extractor (usually one glyph
 * or a short run of glyphs) or by OCR (one word). Coordinates are display points.
 */
class TextFragment(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val baseline: Float,
    val fontSize: Float,
    val fontName: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Width of a space in this font at this size, if known. */
    val spaceWidth: Float = fontSize * 0.25f,
    /** The glyph's name in its font, for glyphs without a Unicode mapping ("H11005"). */
    val glyph: String = "",
) {
    val width: Float get() = right - left
    val bounds: Rect get() = Rect(left, top, right, bottom)
    val isSpace: Boolean get() = text.isBlank()

    override fun toString(): String = "Fragment('$text' @$left,$baseline size=$fontSize)"
}

/** A raster image drawn on the page. */
class ImageRegion(val bounds: Rect)

enum class PathShape {
    /** Thin horizontal line or very flat rectangle (rules, underlines, table borders). */
    HORIZONTAL_LINE,

    /** Thin vertical line or very narrow rectangle. */
    VERTICAL_LINE,

    /** Axis-aligned rectangle. */
    RECTANGLE,

    /** Anything with curves or diagonal segments. */
    COMPLEX,
}

/** A painted vector path (stroke and/or fill). */
class VectorPath(
    val bounds: Rect,
    val shape: PathShape,
    val stroked: Boolean,
    val filled: Boolean,
    /** Fill colour is white or nearly white (typical for page/background rectangles). */
    val lightFill: Boolean = false,
    /** Number of path segments, a rough measure of drawing complexity. */
    val segments: Int = 1,
    /** Paint colour as 0xRRGGBB (fill colour of filled paths, else stroke colour); -1 if unknown. */
    val color: Int = -1,
)

/**
 * Where a scanned page (one picture under an invisible OCR text layer) is dark: a grid of
 * [cell]-point squares, row by row. It shows what the text layer leaves out: drawings, the
 * symbols and bars of a formula, the frame around it.
 */
class InkMap(val cols: Int, val rows: Int, val cell: Float, val dark: BooleanArray) {
    fun at(col: Int, row: Int): Boolean = col in 0 until cols && row in 0 until rows && dark[row * cols + col]
}

class RawPage(
    val index: Int,
    val width: Float,
    val height: Float,
    val fragments: List<TextFragment>,
    val images: List<ImageRegion> = emptyList(),
    val paths: List<VectorPath> = emptyList(),
    /** Bounds of rotated (non-horizontal) text, typically axis labels inside figures. */
    val rotatedText: List<Rect> = emptyList(),
    /** True if [fragments] came from OCR rather than from the PDF text layer. */
    val fromOcr: Boolean = false,
    /** Regions OCR classified as pictures. */
    val ocrPictures: List<Rect> = emptyList(),
    /** For a scan under an OCR text layer: where the page has ink. */
    val ink: InkMap? = null,
    /** The text is drawn invisible: an OCR's text layer over the picture of a scanned page. */
    val invisibleText: Boolean = false,
)
