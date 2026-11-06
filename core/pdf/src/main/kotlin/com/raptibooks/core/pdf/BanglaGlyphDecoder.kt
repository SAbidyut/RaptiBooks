package com.raptibooks.core.pdf

import com.raptibooks.core.structure.text.BanglaGlyphs
import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.text.Normalizer

/**
 * Reads the glyphs of an embedded TrueType font whose ToUnicode map lost the cluster boundaries
 * (see [BanglaGlyphs]): a glyph's own character comes from the font's `cmap`, a glyph that has
 * none is told apart by its outline.
 *
 * [create] returns null for every font that does not show the damage, so PDFs with a sound text
 * layer are read as before.
 */
internal class BanglaGlyphDecoder private constructor(
    private val font: PDType0Font,
    private val ttf: TrueTypeFont,
    private val lookup: CmapLookup,
) {
    private val unitsPerEm = ttf.unitsPerEm.toFloat().coerceAtLeast(1f)
    private val identity = System.identityHashCode(font).toLong() shl 32
    private val cache = HashMap<Int, Optional>()

    /** (a null result is cached too) */
    private class Optional(val glyph: BanglaGlyphs.Glyph?)

    /** The glyph shown by [code], or null if the ordinary text extraction should handle it. */
    fun decode(code: Int, unicode: String?): BanglaGlyphs.Glyph? = cache.getOrPut(code) {
        Optional(
            try {
                decodeUncached(code, unicode)
            } catch (_: Exception) {
                null
            },
        )
    }.glyph

    private fun decodeUncached(code: Int, unicode: String?): BanglaGlyphs.Glyph? {
        val gid = font.codeToGID(code)
        val codes = lookup.getCharCodes(gid)
        val key = identity or (gid.toLong() and 0xffffffffL)
        if (!codes.isNullOrEmpty()) {
            val bengali = codes.firstOrNull { it in BENGALI }
            val c = bengali ?: return null
            return BanglaGlyphs.classify(String(Character.toChars(c)), unicode, null, key)
        }
        return BanglaGlyphs.classify(null, unicode, shapeOf(gid), key)
    }

    private fun shapeOf(gid: Int): BanglaGlyphs.Shape? {
        val data = ttf.glyph.getGlyph(gid) ?: return null
        val advance = ttf.getAdvanceWidth(gid) / unitsPerEm
        if (data.numberOfContours.toInt() == 0) return BanglaGlyphs.Shape(advance, 0f, 0f, 0f)
        return BanglaGlyphs.Shape(advance, data.xMaximum / unitsPerEm, data.yMinimum / unitsPerEm, data.yMaximum / unitsPerEm)
    }

    companion object {
        private val BENGALI = 0x0980..0x09FF

        /**
         * A decoder for [font] if it is an embedded Bangla TrueType font (Identity-H, with its
         * `cmap`) whose ToUnicode map disagrees with that cmap for some plain Bangla character:
         * a vowel sign that maps to nothing, a letter that has taken the text of its neighbour.
         */
        fun create(font: PDFont): BanglaGlyphDecoder? = try {
            createOrThrow(font)
        } catch (_: Exception) {
            null
        }

        private fun createOrThrow(font: PDFont): BanglaGlyphDecoder? {
            if (font !is PDType0Font) return null
            val descendant = font.descendantFont as? PDCIDFontType2 ?: return null
            if (!descendant.isEmbedded) return null
            val ttf = descendant.trueTypeFont ?: return null
            val lookup = ttf.getUnicodeCmapLookup(false) ?: return null
            var bengali = false
            var damaged = 0
            for (cp in BENGALI) {
                val gid = lookup.getGlyphId(cp)
                if (gid <= 0) continue
                bengali = true
                if (font.codeToGID(gid) != gid) return null // only glyph ids as character codes
                val own = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFC)
                val mapped = font.toUnicode(gid)
                if (mapped.isNullOrEmpty() || Normalizer.normalize(mapped, Normalizer.Form.NFC) != own) damaged++
            }
            return if (bengali && damaged > 0) BanglaGlyphDecoder(font, ttf, lookup) else null
        }
    }
}
