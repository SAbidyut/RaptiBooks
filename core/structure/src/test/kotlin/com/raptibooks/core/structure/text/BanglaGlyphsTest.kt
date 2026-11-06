package com.raptibooks.core.structure.text

import com.raptibooks.core.structure.text.BanglaGlyphs.Glyph
import com.raptibooks.core.structure.text.BanglaGlyphs.Role
import com.raptibooks.core.structure.text.BanglaGlyphs.Shape
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Glyph runs as WeasyPrint draws them (visual order) with the ToUnicode text it records: the last
 * glyph of a cluster carries the cluster, the glyphs before it map to nothing.
 */
class BanglaGlyphsTest {
    // Kalpurush, in em
    private val eKarVariant = Shape(0.310f, 0.330f, 0.020f, 0.644f)
    private val aiKarVariant = Shape(0.299f, 0.342f, 0.020f, 0.897f)
    private val reph = Shape(0f, 0.109f, 0.693f, 0.930f)
    private val yaPhala = Shape(0.206f, 0.242f, -0.012f, 0.644f)
    private val conjunct = Shape(0.774f, 0.8f, -0.07f, 0.9f)

    private fun cmap(c: Char) = BanglaGlyphs.classify(c.toString(), null, null)!!

    private fun text(vararg glyphs: Glyph?): String {
        val resolved = BanglaGlyphs.resolve(glyphs.toList())
        return BanglaGlyphs.assemble(resolved).joinToString("") { it.text }.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
    }

    @Test
    fun glyphsOfTheFontsCmapAreThatCharacter() {
        assertEquals(Role.PRE, cmap('ি').role)
        assertEquals(Role.POST, cmap('া').role)
        assertEquals(Role.BASE, cmap('ক').role)
        assertEquals("য়", BanglaGlyphs.classify("য়", "য়ে", null)!!.text) // (the ToUnicode text has taken the ে)
        assertNull(BanglaGlyphs.classify("W", "W", null))
        assertNull(BanglaGlyphs.classify("১", "১", null)) // digits are left to the ordinary path
    }

    @Test
    fun aLigatureLosesTheVowelSignDrawnBeforeIt() {
        // "শক্তি": the ি glyph is its own glyph, the ligature's text ends with it
        val g = BanglaGlyphs.classify(null, "ক্তি", conjunct)!!
        assertEquals(Role.BASE, g.role)
        assertEquals("ক্ত", g.text)
        assertEquals("ষ্টি".replace("ি", ""), BanglaGlyphs.classify(null, "ষ্টি", conjunct)!!.text)
        // a ligature fused with a vowel sign keeps it
        assertEquals("গু", BanglaGlyphs.classify(null, "গু", conjunct)!!.text)
    }

    @Test
    fun theTwoPartVowelKeepsOnlyItsRightHalf() {
        // "ৌঁ" on one glyph: the ে was drawn before the consonant
        val g = BanglaGlyphs.classify(null, "ৌঁ", Shape(0.256f, 0.3f, 0f, 0.6f))!!
        assertEquals(Role.POST, g.role)
        assertEquals("ৗঁ", g.text)
    }

    @Test
    fun aRephAndAYaPhalaAreToldFromTheirText() {
        // reph given the text of its cluster ("র্থ", "র্কি"): only "র্" is its own
        assertEquals("র্", BanglaGlyphs.classify(null, "র্থ", reph)!!.text)
        assertEquals(Role.REPH, BanglaGlyphs.classify(null, "র্কি", reph)!!.role)
        // ya-phala with a vowel sign it never had ("্যু") or the cluster's start ("ক্যে")
        assertEquals("্য", BanglaGlyphs.classify(null, "্যু", yaPhala)!!.text)
        assertEquals("্য", BanglaGlyphs.classify(null, "ক্যে", yaPhala)!!.text)
        assertEquals(Role.PHALA, BanglaGlyphs.classify(null, "্য", yaPhala)!!.role)
    }

    @Test
    fun glyphsThatMapToNothingAreToldByShape() {
        assertEquals("ে", BanglaGlyphs.classify(null, "", eKarVariant)!!.text)
        assertEquals(Role.AMBIGUOUS, BanglaGlyphs.classify(null, "", eKarVariant)!!.role)
        assertEquals("ৈ", BanglaGlyphs.classify(null, "", aiKarVariant)!!.text)
        assertEquals(Role.REPH, BanglaGlyphs.classify(null, "", reph)!!.role)
        assertEquals(Role.UNKNOWN, BanglaGlyphs.classify(null, "", conjunct)!!.role)
        assertEquals("ি", BanglaGlyphs.classify(null, "", Shape(0.207f, 0.586f, 0f, 0.91f))!!.text)
    }

    @Test
    fun preBaseSignsGoAfterTheirConsonant() {
        // কাকে → [ক া] [ে-variant ক] → "কাকে"; কোনো → [ে ক া] [ে ন া]
        val e = Glyph(Role.PRE, "ে", 1)
        assertEquals("কাকে", text(cmap('ক'), cmap('া'), e, cmap('ক')))
        assertEquals("কোনো", text(e, cmap('ক'), cmap('া'), e, cmap('ন'), cmap('া')))
        // "শক্তি": [ি] [ক্ত]
        assertEquals("ক্তি", text(cmap('ি'), BanglaGlyphs.classify(null, "ক্তি", conjunct)!!))
    }

    @Test
    fun aRephDrawnAfterItsConsonantGoesInFront() {
        val r = BanglaGlyphs.classify(null, "র্থ", reph)!!
        // "পদার্থ": প দ া [থ র্]
        assertEquals("পদার্থ", text(cmap('প'), cmap('দ'), cmap('া'), cmap('থ'), r))
        // "কর্মদক্ষতা": the reph is followed by a consonant of the next syllable, not by a space
        val rm = BanglaGlyphs.classify(null, "র্ম", reph)!!
        assertEquals("কর্মদ", text(cmap('ক'), cmap('ম'), rm, cmap('দ')))
    }

    @Test
    fun aRunEndsAtAnyOtherGlyph() {
        assertEquals("কখ", text(cmap('ক'), null, cmap('খ')))
    }

    @Test
    fun anAmbiguousNarrowGlyphIsAYaPhalaWhenItHangsOnAConsonant() {
        val amb = BanglaGlyphs.classify(null, "", yaPhala)!!
        assertEquals(Role.AMBIGUOUS, amb.role)
        // "ব্যা": [ব ?] [া] — drawn after a consonant and followed by a vowel sign
        assertEquals("ব্যা", text(cmap('ব'), amb, cmap('া')))
        // …while the same glyph starting a syllable (after a vowel sign) is a ে
        assertEquals("কাকে", text(cmap('ক'), cmap('া'), amb, cmap('ক')))
    }

    @Test
    fun anUnknownLigatureKeepsItsPlaceAsAPlaceholder() {
        val u = BanglaGlyphs.classify(null, "", conjunct)!!
        val s = BanglaGlyphs.assemble(listOf(cmap('ক'), u, cmap('া')))
        assertEquals(3, s.size)
        assertNotNull(s.single { it.unknown })
        assertEquals(TextCleaner.PLACEHOLDER.toString(), s[1].text)
    }
}
