package com.raptibooks.core.structure.text

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import kotlin.math.abs
import kotlin.math.roundToInt

/** Grey levels (0 = black, 255 = white), row-major. */
class GrayImage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Gives glyphs without a Unicode mapping ([TextCleaner.PLACEHOLDER] fragments) their character
 * by looking at them: each line of such glyphs is rendered once and every glyph is classified
 * with [SymbolShapes]. Unrecognised glyphs stay placeholders.
 */
object SymbolRecognizer {

    /** Upper bound per page, to keep pages full of unknown glyphs (broken fonts) bounded. */
    private const val MAX_GLYPHS = 600

    /**
     * @param render renders a page region (display points) at the given pixels-per-point scale.
     */
    fun recognize(page: RawPage, render: (Rect, Float) -> GrayImage?): RawPage {
        val unknown = page.fragments.withIndex().filter { it.value.text.length == 1 && it.value.text[0] == TextCleaner.PLACEHOLDER }
        if (unknown.isEmpty() || unknown.size > MAX_GLYPHS) return page
        val replaced = HashMap<Int, TextFragment>()
        // One rendering per text line.
        val strips = mutableListOf<MutableList<IndexedValue<TextFragment>>>()
        for (u in unknown.sortedBy { it.value.baseline }) {
            val strip = strips.lastOrNull()
            if (strip != null && abs(strip.first().value.baseline - u.value.baseline) <= u.value.fontSize * 0.3f) strip += u else strips += mutableListOf(u)
        }
        for (strip in strips) {
            val size = strip.maxOf { it.value.fontSize }
            val scale = (44f / size).coerceIn(2f, 10f)
            val baseline = strip.first().value.baseline
            val left = strip.minOf { it.value.left } - size * 0.4f
            val right = strip.maxOf { it.value.right } + size * 0.4f
            val region = Rect(left, baseline - size * 1.15f, right, baseline + size * 0.45f).clamp(page.width, page.height)
            if (region.width < 1f || region.height < 1f) continue
            val img = try {
                render(region, scale)
            } catch (e: Exception) {
                null
            } ?: continue
            val sx = img.width / region.width
            val sy = img.height / region.height
            for ((i, f) in strip) {
                val c = SymbolShapes.classify(
                    img.pixels, img.width, img.height,
                    (f.left - region.left) * sx, (f.right - region.left) * sx,
                    (f.baseline - region.top) * sy, f.fontSize * sy,
                ) ?: continue
                replaced[i] = TextFragment(
                    c.toString(), f.left, f.top, f.right, f.bottom, f.baseline, f.fontSize, f.fontName,
                    f.bold, f.italic, f.spaceWidth, f.glyph,
                )
            }
        }
        if (replaced.isEmpty()) return page
        val fragments = page.fragments.mapIndexed { i, f -> replaced[i] ?: f }
        return RawPage(page.index, page.width, page.height, fragments, page.images, page.paths, page.rotatedText, page.fromOcr, page.ocrPictures)
    }

    /** Converts ARGB pixels to grey levels (white background assumed for transparency). */
    fun gray(argb: IntArray, width: Int, height: Int): GrayImage {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val p = argb[i]
            val a = p ushr 24 and 0xFF
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            out[i] = 255 - ((255 - lum) * a / 255.0).roundToInt()
        }
        return GrayImage(out, width, height)
    }
}
