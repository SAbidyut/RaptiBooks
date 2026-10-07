package com.raptibooks.core.structure.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BanglaTextTest {

    @Test
    fun movesPreBaseVowelSignsAfterTheirConsonant() {
        assertEquals("কিছু", BanglaText.visualToLogical("িকছু"))
        assertEquals("আমি", BanglaText.visualToLogical("আিম"))
        assertEquals("সমিতি", BanglaText.visualToLogical("সিমিত"))
    }

    @Test
    fun handlesConjunctsAndTwoPartVowels() {
        // ক্তি: visual order puts ি before the whole conjunct
        assertEquals("শক্তি", BanglaText.visualToLogical("শিক্ত"))
        // ো is drawn as ে … া; NFC composes the logical sequence
        assertEquals("তোমার", TextCleaner.clean(BanglaText.visualToLogical("েতামার")))
        assertEquals("কৌশল", TextCleaner.clean(BanglaText.visualToLogical("েকৗশল")))
    }

    @Test
    fun movesRephBeforeItsConsonant() {
        assertEquals("কর্ম", BanglaText.visualToLogical("কমর্"))
        assertEquals("ধর্মে", BanglaText.visualToLogical("ধেমর্"))
        // ra + ya-phala (র্য) is legitimate and must stay
        assertEquals("কার্য", BanglaText.fixReph("কার্য"))
    }

    @Test
    fun logicalTextIsLeftAlone() {
        val logical = "আমার সোনার বাংলা, আমি তোমায় ভালোবাসি।"
        val (impossible, total) = BanglaText.visualOrderEvidence(logical)
        assertEquals(0, impossible)
        assertTrue(total > 0)
    }

    @Test
    fun detectsVisualOrder() {
        val (impossible, _) = BanglaText.visualOrderEvidence("আিম িক েতামায় বিল")
        assertTrue(impossible >= 3)
    }

    @Test
    fun cleanerRemovesInvisibleJunkAndLigatures() {
        assertEquals("office fine", TextCleaner.clean("oﬃce ﬁne­"))
        assertEquals("a b", TextCleaner.clean("a b​"))
    }

    @Test
    fun garbageRatio() {
        assertTrue(TextQuality.garbageRatio("Normal English text.") < 0.01f)
        assertTrue(TextQuality.garbageRatio("†‡ˆ©ª¨") > 0.5f)
        assertFalse(TextQuality.garbageRatio("বাংলা লেখা") > 0.01f)
    }
}
