package com.raptibooks.core.structure.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiFontsTest {

    @Test
    fun mathematicalPiGlyphNames() {
        assertEquals("=", PiFonts.decode("HNNHDF+MathematicalPi-One", "H11005", null))
        assertEquals("≠", PiFonts.decode("HNNHDF+MathematicalPi-One", "HS11005", null))
        assertEquals("°", PiFonts.decode("HNNHDF+MathematicalPi-One", "H11034", null))
        assertEquals("∫", PiFonts.decode("BLCOLF+MathematicalPi-Three", "H20910", null))
    }

    @Test
    fun greekFollowsUnicodeOrder() {
        assertEquals("ω", PiFonts.decode("BBMFNL+MathPiOneItalic", "H9275", null))
        assertEquals("μ", PiFonts.decode("BBMFNL+MathPiOneItalic", "H9262", null))
        assertEquals("π", PiFonts.decode("BBMFNL+MathPiOneItalic", "H9266", null))
        assertEquals("σ", PiFonts.decode("BBMFNL+MathPiOneItalic", "H9268", null))
        assertEquals("τ", PiFonts.decode("BBMFNL+MathPiOneItalic", "H9270", null))
        assertEquals("Δ", PiFonts.decode("HNNHDF+MathematicalPi-One", "H9004", null))
        assertEquals("Ω", PiFonts.decode("HNNHDF+MathematicalPi-One", "H9024", null))
    }

    @Test
    fun unknownPiGlyphIsLeftToItsShape() {
        assertEquals("", PiFonts.decode("HNNHDF+MathematicalPi-One", "H19999", null))
        assertEquals("", PiFonts.decode("HNNHMM+Optr", "", "#"))
    }

    @Test
    fun misleadingMappingsOfSymbolFonts() {
        assertEquals("<", PiFonts.decode("HNNHMM+Optr", "", "6"))
        assertEquals("Δ", PiFonts.decode("HNNHMM+Optr", "", "¢"))
        assertEquals("∠", PiFonts.decode("HNNHMM+Optr", "", "l"))
        assertEquals("θ", PiFonts.decode("BLCKCF+Grk", "", "u"))
        assertEquals("π", PiFonts.decode("BLCKCF+Grk", "", "p"))
        assertEquals("(", PiFonts.decode("HOANNK+PBB", "", "a"))
        assertEquals("ℒ", PiFonts.decode("XYZABC+Fancy", "", "L"))
    }

    @Test
    fun ordinaryFontsKeepTheirText() {
        assertNull(PiFonts.decode("Times-Roman", "", "u"))
        assertNull(PiFonts.decode("ABCDEF+Grkish-Sans", "", "u"))
    }
}
