package com.raptibooks.core.pdf

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.text.BanglaGlyphs
import com.raptibooks.core.structure.text.PiFonts
import com.raptibooks.core.structure.text.TextCleaner
import com.tom_roush.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequence
import com.tom_roush.pdfbox.contentstream.operator.markedcontent.BeginMarkedContentSequenceWithProperties
import com.tom_roush.pdfbox.contentstream.operator.markedcontent.EndMarkedContentSequence
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Collects every glyph of a page with its position and font style. PDFBox's own text
 * assembly is bypassed; layout analysis happens in core:structure.
 *
 * Supports `/ActualText` marked content: generators use it for ligatures and complex scripts
 * (Bangla clusters in Word-made PDFs), where glyph-by-glyph Unicode would be wrong.
 */
internal class TextCollector : PDFTextStripper() {

    val fragments = ArrayList<TextFragment>()
    val rotated = ArrayList<Rect>()
    var glyphsDrawn = 0
        private set
    var glyphsMapped = 0
        private set

    /** Fragments drawn invisible (text render mode 3: an OCR's text layer over a scan). */
    var invisible = 0
        private set

    private lateinit var transform: DisplayTransform
    private val styleCache = HashMap<PDFont, Pair<Boolean, Boolean>>()

    private class ActualTextSpan(val text: String) {
        val positions = ArrayList<TextPosition>()

        // Geometry from showGlyph, for glyphs that have no Unicode mapping of their own.
        var x0 = Float.NaN
        var x1 = Float.NaN
        var baseline = 0f
        var size = 0f
        var font: PDFont? = null
    }

    private val markedStack = ArrayDeque<ActualTextSpan?>()

    /** A glyph of a Bangla font with a damaged ToUnicode map, with where it was drawn. */
    private class BanglaGlyphEvent(
        val glyph: BanglaGlyphs.Glyph,
        val x0: Float,
        val x1: Float,
        val baseline: Float,
        val size: Float,
        val font: PDFont,
    )

    /** The page's Bangla glyphs in drawing order; a null ends a run (any other glyph was drawn). */
    private val banglaRun = ArrayList<BanglaGlyphEvent?>()
    private val banglaDecoders = IdentityHashMap<PDFont, BanglaGlyphDecoder?>()

    /** Set while PdfBox processes a glyph that was already recorded as a placeholder. */
    private var suppressText = false

    /**
     * Whether the glyph yields real text: a mapping, or (for simple fonts, as PdfBox does) a
     * printable character code. Control characters, private-use code points and missing
     * mappings mean the font does not say what the glyph is.
     */
    private fun hasText(font: PDFont, code: Int, unicode: String?): Boolean {
        val text = unicode ?: if (font is PDSimpleFont && code in 0x20..0x7E) code.toChar().toString() else return false
        if (text.isBlank()) return true
        return TextCleaner.clean(text).any { !it.isWhitespace() }
    }

    private fun glyphName(font: PDFont, code: Int): String = try {
        (font as? PDSimpleFont)?.encoding?.getName(code) ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun addPlaceholder(m: Matrix, font: PDFont, code: Int, displacement: Vector, text: String = TextCleaner.PLACEHOLDER.toString()) {
        if (!transform.isUpright(m.scaleX, m.shearY)) return
        val size = m.scalingFactorY
        if (size !in 0.5f..500f) return
        val tx = m.translateX
        val ty = m.translateY
        val x0 = transform.mapAbsoluteX(tx, ty)
        val baseline = transform.mapAbsoluteY(tx, ty)
        val advance = (displacement.x * m.scalingFactorX).takeIf { it.isFinite() && it > 0f } ?: (size * 0.5f)
        fragments += TextFragment(
            text, x0, baseline - size * 0.82f, x0 + advance, baseline + size * 0.22f,
            baseline, size, font.name ?: "", false, false, glyph = glyphName(font, code),
        )
    }


    // ---------------------------------------------------------------- Bangla with a damaged ToUnicode map

    private fun banglaDecoder(font: PDFont): BanglaGlyphDecoder? {
        if (banglaDecoders.containsKey(font)) return banglaDecoders[font]
        val decoder = BanglaGlyphDecoder.create(font)
        banglaDecoders[font] = decoder
        return decoder
    }

    /**
     * Takes the glyph for the syllable assembly of [flushBangla] if its font is a Bangla font
     * that does not say which character each glyph is; false: the ordinary text path handles it.
     */
    private fun addBanglaGlyph(m: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector): Boolean {
        val decoder = banglaDecoder(font) ?: return false
        if (!transform.isUpright(m.scaleX, m.shearY)) return false
        val size = m.scalingFactorY
        if (size !in 0.5f..500f) return false
        val glyph = decoder.decode(code, unicode) ?: return false
        val tx = m.translateX
        val ty = m.translateY
        val x0 = transform.mapAbsoluteX(tx, ty)
        val advance = (displacement.x * m.scalingFactorX).takeIf { it.isFinite() && it > 0f } ?: 0f
        banglaRun += BanglaGlyphEvent(glyph, x0, x0 + advance, transform.mapAbsoluteY(tx, ty), size, font)
        suppressText = true
        try {
            super.showGlyph(m, font, code, unicode, displacement)
        } finally {
            suppressText = false
        }
        return true
    }

    /**
     * Puts the page's Bangla glyphs together: syllable by syllable, in logical order, one fragment
     * spanning the glyphs of each (like an `/ActualText` span).
     */
    private fun flushBangla() {
        if (banglaRun.none { it != null }) return
        val glyphs = BanglaGlyphs.resolve(banglaRun.map { it?.glyph })
        for (syllable in BanglaGlyphs.assemble(glyphs)) {
            val events = syllable.members.map { banglaRun[it]!! }
            val body = events.firstOrNull { it.glyph.role == BanglaGlyphs.Role.BASE } ?: events.first()
            val left = events.minOf { it.x0 }
            val right = maxOf(events.maxOf { it.x1 }, left + body.size * 0.3f)
            val (bold, italic) = styleOf(body.font)
            fragments += TextFragment(
                text = syllable.text,
                left = left,
                top = body.baseline - body.size * 0.82f,
                right = right,
                bottom = body.baseline + body.size * 0.22f,
                baseline = body.baseline,
                fontSize = body.size,
                fontName = body.font.name ?: "",
                bold = bold,
                italic = italic,
                spaceWidth = body.size * 0.25f,
            )
        }
    }

    init {
        addOperator(BeginMarkedContentSequenceWithProperties())
        addOperator(BeginMarkedContentSequence())
        addOperator(EndMarkedContentSequence())
        sortByPosition = false
        suppressDuplicateOverlappingText = false
    }

    fun collect(document: PDDocument, pageIndex: Int) {
        fragments.clear()
        rotated.clear()
        markedStack.clear()
        banglaRun.clear()
        glyphsDrawn = 0
        invisible = 0
        glyphsMapped = 0
        startPage = pageIndex + 1
        endPage = pageIndex + 1
        try {
            getText(document)
            flushBangla()
        } finally {
            banglaRun.clear()
            banglaDecoders.clear()
            // (a font is a page's own object: kept, every page's fonts would stay in memory, a
            // scanned book's OCR layer has fonts of its own on each page)
            styleCache.clear()
        }
        removeShadows()
    }

    /**
     * Titles drawn twice, the second copy shifted by a point or two (a drop shadow, a fake
     * bold): each glyph has a twin of the same text and size right next to it. Neighbouring
     * letters of a word ("ll", "...") are at least a narrow glyph's width apart.
     */
    private fun removeShadows() {
        if (fragments.size < 2) return
        val drop = HashSet<Int>()
        val groups = fragments.indices.filter { fragments[it].text.isNotBlank() }.groupBy { fragments[it].text to (fragments[it].fontSize * 2f).toInt() }
        for (ix in groups.values) {
            if (ix.size < 2) continue
            val sorted = ix.sortedBy { fragments[it].left }
            for ((a, i) in sorted.withIndex()) {
                if (i in drop) continue
                val f = fragments[i]
                val tol = minOf(f.fontSize * 0.12f, 3f)
                for (b in a + 1 until sorted.size) {
                    val j = sorted[b]
                    val g = fragments[j]
                    if (g.left - f.left > tol) break
                    if (j !in drop && abs(g.baseline - f.baseline) <= tol && abs(g.right - g.left - (f.right - f.left)) <= tol) {
                        drop += minOf(i, j) // keep the copy drawn last, on top
                    }
                }
            }
        }
        if (drop.isEmpty()) return
        val kept = fragments.filterIndexed { i, _ -> i !in drop }
        fragments.clear()
        fragments.addAll(kept)
    }

    override fun startPage(page: PDPage) {
        transform = DisplayTransform(page)
        super.startPage(page)
    }

    override fun writePage() {
        // Text assembly is done by core:structure.
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector) {
        glyphsDrawn++
        val span = markedStack.lastOrNull { it != null }
        if (span == null && addBanglaGlyph(textRenderingMatrix, font, code, unicode, displacement)) return
        if (banglaRun.isNotEmpty() && banglaRun.last() != null) banglaRun += null
        // Symbol fonts of math typesetting: their own (missing or misleading) mapping is replaced.
        val pi = if (span == null) PiFonts.decode(font.name ?: "", glyphName(font, code), unicode) else null
        if (pi != null) {
            if (pi.isNotEmpty()) addPlaceholder(textRenderingMatrix, font, code, displacement, pi)
            else addPlaceholder(textRenderingMatrix, font, code, displacement)
            suppressText = true
            try {
                super.showGlyph(textRenderingMatrix, font, code, unicode, displacement)
            } finally {
                suppressText = false
            }
            return
        }
        if (span == null && !hasText(font, code, unicode)) {
            // A symbol without a usable Unicode mapping (e.g. "=" or "×" of a math font): keep its
            // place so that equations are recognised and the symbol can be identified by shape.
            addPlaceholder(textRenderingMatrix, font, code, displacement)
            suppressText = true
            try {
                super.showGlyph(textRenderingMatrix, font, code, unicode, displacement)
            } finally {
                suppressText = false
            }
            return
        }
        if (span != null) {
            val tx = textRenderingMatrix.translateX
            val ty = textRenderingMatrix.translateY
            val dx = transform.mapAbsoluteX(tx, ty)
            val advance = displacement.x * textRenderingMatrix.scalingFactorX
            if (span.x0.isNaN()) {
                span.x0 = dx
                span.x1 = dx
                span.baseline = transform.mapAbsoluteY(tx, ty)
                span.size = textRenderingMatrix.scalingFactorY
                span.font = font
            }
            span.x1 = maxOf(span.x1, dx + advance)
        }
        super.showGlyph(textRenderingMatrix, font, code, unicode, displacement)
    }

    override fun processTextPosition(text: TextPosition) {
        glyphsMapped++
        if (suppressText) return
        val span = markedStack.lastOrNull { it != null }
        if (span != null) {
            span.positions += text
            return
        }
        add(text, text.unicode ?: return)
    }

    override fun beginMarkedContentSequence(tag: COSName?, properties: COSDictionary?) {
        val actual = properties?.getString(ACTUAL_TEXT)
        markedStack.addLast(if (actual != null) ActualTextSpan(actual) else null)
        super.beginMarkedContentSequence(tag, properties)
    }

    override fun endMarkedContentSequence() {
        val span = if (markedStack.isEmpty()) null else markedStack.removeLast()
        if (span != null && span.text.isNotEmpty()) {
            if (span.positions.isNotEmpty()) {
                // One fragment carrying the actual text, spanning all glyphs of the sequence.
                add(span.positions.first(), span.text, span.positions)
            } else if (!span.x0.isNaN() && span.size > 0.5f) {
                val (bold, italic) = styleOf(span.font)
                fragments += TextFragment(
                    span.text, span.x0, span.baseline - span.size * 0.82f, maxOf(span.x1, span.x0 + span.size * 0.3f),
                    span.baseline + span.size * 0.22f, span.baseline, span.size, span.font?.name ?: "", bold, italic,
                )
            }
        }
        super.endMarkedContentSequence()
    }

    private fun add(t: TextPosition, mapped: String, group: List<TextPosition>? = null) {
        // Greek/math fonts of typeset books put "Δ" on the code of "¢".
        val unicode = if (mapped == "¢" && MATH_FONT.containsMatchIn(t.font?.name ?: "")) "Δ" else mapped
        val m = t.textMatrix
        val a = m.scaleX
        val b = m.shearY
        // The rendering matrix includes font size, text matrix and CTM: its y scale is the
        // glyph size as drawn on the page (fontSizeInPt ignores the CTM).
        val size = m.scalingFactorY.takeIf { it > 0.5f && it < 500f }
            ?: t.fontSizeInPt.takeIf { it > 0.5f && it < 500f }
            ?: abs(t.heightDir).coerceAtLeast(1f)
        var x0 = transform.mapRelativeX(m.translateX, m.translateY)
        val y0 = transform.mapRelativeY(m.translateX, m.translateY)
        if (!transform.isUpright(a, b)) {
            rotated += Rect(x0 - size, y0 - size, x0 + size, y0 + size)
            return
        }
        val renderingMode = try {
            graphicsState.textState.renderingMode
        } catch (_: Exception) {
            null
        }
        var width = t.widthDirAdj
        if (group != null && group.size > 1) {
            // Glyphs of a cluster are not necessarily drawn left to right (Bangla pre-base
            // vowel signs), so take the extent over all of them.
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            for (p in group) {
                val pm = p.textMatrix
                val px = transform.mapRelativeX(pm.translateX, pm.translateY)
                minX = minOf(minX, px)
                maxX = maxOf(maxX, px + p.widthDirAdj.coerceAtLeast(0f))
            }
            x0 = minX
            width = maxX - minX
        }
        if (width.isNaN() || width < 0f) width = 0f
        val (fontBold, fontItalic) = styleOf(t.font)
        val bold = fontBold || renderingMode == RenderingMode.FILL_STROKE
        // Synthetic italics: upright fonts drawn with a skewed matrix.
        val skew = if (abs(m.scaleY) > 0.01f) abs(m.shearX / m.scaleY) else 0f
        val italic = fontItalic || (transform.rotation == 0 && skew in 0.12f..0.6f)
        val space = t.widthOfSpace.takeIf { it.isFinite() && it > 0f && it < size * 2f } ?: (size * 0.25f)

        // Fake bold by overprinting the same glyph at (nearly) the same spot.
        for (k in fragments.size - 1 downTo maxOf(0, fragments.size - 4)) {
            val f = fragments[k]
            if (f.text == unicode && abs(f.left - x0) < 0.8f && abs(f.baseline - y0) < 0.8f) return
        }
        if (renderingMode == RenderingMode.NEITHER) invisible++
        fragments += TextFragment(
            text = unicode,
            left = x0,
            top = y0 - size * 0.82f,
            right = x0 + width,
            bottom = y0 + size * 0.22f,
            baseline = y0,
            fontSize = size,
            fontName = t.font?.name ?: "",
            bold = bold,
            italic = italic,
            spaceWidth = space,
        )
    }

    private fun styleOf(font: PDFont?): Pair<Boolean, Boolean> {
        if (font == null) return false to false
        return styleCache.getOrPut(font) {
            val name = (font.name ?: "").substringAfter('+').lowercase()
            val d = font.fontDescriptor
            val bold = BOLD.containsMatchIn(name) || (d?.isForceBold == true) || ((d?.fontWeight ?: 0f) >= 600f)
            val italic = ITALIC.containsMatchIn(name) || (d?.isItalic == true) || abs(d?.italicAngle ?: 0f) > 1f
            bold to italic
        }
    }

    companion object {
        private val ACTUAL_TEXT: COSName = COSName.getPDFName("ActualText")
        private val BOLD = Regex("bold|black|heavy|semibold|demi|extrabold|ultrabold|-bd|,bd")
        private val ITALIC = Regex("italic|oblique|-it\\b|,it\\b")
        private val MATH_FONT = Regex("greek|math|symbol|pi\\b|pione|pitwo|euclid", RegexOption.IGNORE_CASE)
    }
}
