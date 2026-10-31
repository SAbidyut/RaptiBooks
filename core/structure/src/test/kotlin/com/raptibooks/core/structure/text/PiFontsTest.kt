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
        // a cut known by glyph names only
        assertEquals("(", PiFonts.decode("GHAFHP+PBB", "one", null))
        assertEquals("}", PiFonts.decode("GHAFHP+PBB", "", "f"))
        assertEquals("π", PiFonts.decode("GMNMGD+MathTechnicalP12", "", "p"))
        assertEquals("∠", PiFonts.decode("GGNOBJ+MathematicalPi-Six", "H11028", null))
        assertEquals("≅", PiFonts.decode("GGNNJN+MathematicalPi-Three", "H33361", null))
    }

    @Test
    fun pearsonMathSets() {
        // Greek keyboard, capitals in the bold cut of headings ("Alpha (A)")
        assertEquals("β", PiFonts.decode("DEOCJM+PearsonMATH01", "", "b"))
        assertEquals("α", PiFonts.decode("DEOCJM+PearsonMATH01", "", "A"))
        assertEquals("μ", PiFonts.decode("DEOCJM+PearsonMATH01", "", "M"))
        // the fraction slash of "VD/ID", relations, parallel bars and radicals
        assertEquals("/", PiFonts.decode("ECHNFH+PearsonMATH18", "", ">"))
        assertEquals("‖", PiFonts.decode("ECHNFH+PearsonMATH18", "", "7"))
        assertEquals("≥", PiFonts.decode("DEMDLJ+PearsonMATH02", "", "Ú"))
        assertEquals("×", PiFonts.decode("DEMDLJ+PearsonMATH02", "", "*"))
        assertEquals("Ω", PiFonts.decode("DEMDLJ+PearsonMATH02", "", "Æ"))
        assertEquals("√", PiFonts.decode("ECHOCD+PearsonMATH20", "", "1"))
        assertEquals("-", PiFonts.decode("ECHOOO+PearsonMATH11", "", "@"))
        assertEquals("±", PiFonts.decode("ECHOJJ+PearsonMATH13", "", "{"))
        // a piece of a tall brace carries no text; a set not known keeps its own mapping
        assertEquals("", PiFonts.decode("ECHOJJ+PearsonMATH13", "", "5"))
        assertNull(PiFonts.decode("ABCDEF+PearsonMATH42", "", "x"))
    }

    @Test
    fun ordinaryFontsKeepTheirText() {
        assertNull(PiFonts.decode("Times-Roman", "", "u"))
        assertNull(PiFonts.decode("ABCDEF+Grkish-Sans", "", "u"))
    }
}
