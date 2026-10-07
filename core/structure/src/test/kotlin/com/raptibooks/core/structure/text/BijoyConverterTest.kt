package com.raptibooks.core.structure.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BijoyConverterTest {

    @Test
    fun convertsNationalAnthemLine() {
        assertEquals("আমার সোনার বাংলা", BijoyConverter.convert("Avgvi †mvbvi evsjv"))
        assertEquals("আমি তোমায় ভালোবাসি", BijoyConverter.convert("Avwg †Zvgvq fv‡jvevwm"))
    }

    @Test
    fun convertsDigitsDandaAndConjuncts() {
        assertEquals("১৯৭১ সালে।", BijoyConverter.convert("1971 mv‡j|"))
        assertEquals("শক্তি", BijoyConverter.convert("kw³"))
    }

    @Test
    fun recognisesBijoyFonts() {
        assertTrue(BijoyConverter.isBijoyFont("ABCDEF+SutonnyMJ"))
        assertTrue(BijoyConverter.isBijoyFont("SutonnyMJ-Bold"))
        assertFalse(BijoyConverter.isBijoyFont("NotoSansBengali-Regular"))
        assertFalse(BijoyConverter.isBijoyFont("TimesNewRomanPSMT"))
    }

    @Test
    fun unicodeTextInMjFontIsNotConverted() {
        assertFalse(BijoyConverter.needsConversion("আমার সোনার বাংলা"))
        assertTrue(BijoyConverter.needsConversion("Avgvi †mvbvi evsjv"))
    }
}
