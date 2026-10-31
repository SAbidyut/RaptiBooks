package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.raw.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Textbooks with a wide margin column: running heads there, chapter openers, formulas. */
class MarginLayoutTest {

    private fun structure(frags: List<TextFragment>, paths: List<VectorPath> = emptyList()): List<StructuredElement> {
        val layout = PageAnalyzer(10f).analyze(RawPage(0, 603f, 774f, frags, paths = paths))
        val profile = DocumentStats().apply { add(layout) }.profile()
        return PageStructurer(profile).structure(layout)
    }

    private fun texts(elements: List<StructuredElement>) = elements.map {
        when (it) {
            is StructuredElement.Heading -> "H${it.level}:" + it.text
            is StructuredElement.Paragraph -> (if (it.kind == ParagraphKind.FORMULA) "F:" else "") + it.text
            is StructuredElement.ListItem -> it.marker + " " + it.text
            else -> it.toString()
        }
    }

    @Test
    fun runningHeadInTheSideMarginIsNotText() {
        // "2  SEMICONDUCTOR / DIODES" at the top left of even pages, "COVALENT BONDING / AND
        // INTRINSIC / MATERIALS  3" at the top right of odd ones, level with the text
        val layouts = (0 until 6).map { i ->
            val frags = mutableListOf<TextFragment>()
            val even = i % 2 == 0
            val textLeft = if (even) 175f else 67f
            if (even) {
                frags += words("${i + 2}", 31f, 30f, size = 10f, bold = true, font = "Sans-Bold")
                frags += words("SEMICONDUCTOR", 55f, 30f, size = 9f, font = "Sans")
                frags += words("DIODES", 55f, 40f, size = 9f, font = "Sans")
            } else {
                frags += words("COVALENT BONDING", 457f, 30f, size = 9f, font = "Sans")
                frags += words("${i + 2}", 566f, 30f, size = 10f, bold = true, font = "Sans-Bold")
                frags += words("AND INTRINSIC", 483f, 40f, size = 9f, font = "Sans")
                frags += words("MATERIALS", 501f, 50f, size = 9f, font = "Sans")
            }
            // (lines of their own: none repeats on the other pages)
            for (k in 0 until 20) frags += words("The text of ${NAMES[i]} goes on here with its ${NAMES[k % 6]} line ${NAMES[(i + k) % 6]}", textLeft, 30f + k * 12f, size = 10f)
            PageAnalyzer(10f).analyze(RawPage(i, 603f, 774f, frags))
        }
        val profile = DocumentStats().apply { layouts.forEach { add(it) } }.profile()
        for (k in listOf(2, 3)) {
            val t = texts(PageStructurer(profile).structure(layouts[k]))
            assertTrue(t.toString(), t.none { "SEMICONDUCTOR" in it || "DIODES" in it || "BONDING" in it || "MATERIALS" in it })
            assertTrue(t.toString(), t.first().startsWith("The text of ${NAMES[k]} goes on here with its ${NAMES[0]} line"))
        }
    }

    private companion object {
        val NAMES = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
    }

    @Test
    fun chapterOpenerWithAHugeNumberBesideTheTitle() {
        val frags = mutableListOf<TextFragment>()
        frags += words("Semiconductor Diodes", 139f, 97f, size = 24f, bold = true)
        frags += words("1", 507f, 141f, size = 125f)
        frags += words("1.1 INTRODUCTION", 68f, 379f, size = 12f, bold = true)
        // the dot at the end of the rule under the heading
        frags += words("●", 354f, 384f, size = 16.75f)
        for (k in 0 until 8) frags += words("One of the noteworthy things about this field is how little changes $k", 68f, 398f + k * 12f, size = 10f)
        val t = texts(structure(frags))
        assertEquals(t.toString(), "H1:1 Semiconductor Diodes", t[0])
        assertEquals(t.toString(), "H2:1.1 INTRODUCTION", t[1])
        assertTrue(t.toString(), t.none { it.trim() == "●" })
    }

    @Test
    fun appendixLetterPrintedOverTheWord() {
        val frags = mutableListOf<TextFragment>()
        frags += words("AppenBdix", 420f, 80f, size = 25f)
        frags += words("Ripple Factor and", 139f, 110f, size = 24f, bold = true)
        frags += words("Voltage Calculations", 139f, 136f, size = 24f, bold = true)
        frags += words("B.1 RIPPLE FACTOR OF RECTIFIER", 68f, 220f, size = 12f, bold = true)
        for (k in 0 until 8) frags += words("The ripple factor of a voltage is defined by the ratio here $k", 68f, 240f + k * 12f, size = 10f)
        val t = texts(structure(frags))
        assertEquals(t.toString(), "H1:Appendix B: Ripple Factor and Voltage Calculations", t[0])
    }

    @Test
    fun formulaOfSubscriptedSymbolsIsNoCapitalsHeading() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += words("Writing Kirchhoff's voltage equation in the clockwise direction for the loop $k", 175f, 100f + k * 12f, size = 10f)
        frags += words("+VCC − IBRB − VBE = 0", 193f, 150f, size = 10f)
        for (k in 0 until 3) frags += words("Note the polarity of the voltage drop across the resistor as established $k", 175f, 170f + k * 12f, size = 10f)
        val t = texts(structure(frags))
        assertTrue(t.toString(), "F:+VCC − IBRB − VBE = 0" in t)
    }

    @Test
    fun subscriptedSymbolsAreNoWords() {
        assertTrue(MathText.isFormula("VCE = VCC − ICRC"))
        assertTrue(MathText.isFormula("ETh = R2VCC"))
        assertTrue(!MathText.isWord("RTh") && !MathText.isWord("IBRB"))
        assertTrue(MathText.isWord("Kirchhoff") && MathText.isWord("TOTAL"))
    }

    @Test
    fun tintedPanelsOfAChapterOpenerAreNoTable() {
        // the opener's backdrop: panels bleeding off the page, the text on them
        val paths = listOf(
            VectorPath(Rect(0f, 0.3f, 463.5f, 183.7f), PathShape.RECTANGLE, stroked = false, filled = true, color = 0x00ADEF),
            VectorPath(Rect(0f, 183.7f, 463.5f, 671f), PathShape.RECTANGLE, stroked = false, filled = true, color = 0xB5E3F8),
            VectorPath(Rect(462.8f, 572f, 603f, 671f), PathShape.RECTANGLE, stroked = false, filled = true, color = 0xB5E3F8),
        )
        val frags = mutableListOf<TextFragment>()
        frags += words("Semiconductor Diodes", 139f, 97f, size = 24f, bold = true)
        for (k in 0 until 20) frags += words("One of the noteworthy things about this field is how little changes $k", 68f, 250f + k * 12f, size = 10f)
        val layout = PageAnalyzer(10f).analyze(RawPage(0, 603f, 774f, frags, paths = paths))
        assertTrue(layout.tables.isEmpty())
        assertTrue(layout.figures.isEmpty())
        assertTrue(layout.boxes.isEmpty())
        assertEquals(21, layout.lines.size)
    }

    @Test
    fun numeratorWithSubscriptsIsNoSuperscript() {
        // "ICsat = VCC / RC": the numerator's subscript letters are small, its V is not
        val frags = mutableListOf<TextFragment>()
        frags += TextFragment("I", 331.9f, 213.6f, 335.3f, 223.6f, 221.6f, 10f)
        frags += TextFragment("C", 335.3f, 217.6f, 340.3f, 225.1f, 223.6f, 7.5f)
        frags += TextFragment("=", 351f, 213.6f, 357.6f, 223.6f, 221.6f, 10f)
        frags += TextFragment("V", 362.6f, 206f, 368.7f, 216f, 214f, 10f)
        frags += TextFragment("C", 368.6f, 210f, 373.6f, 217.5f, 216f, 7.5f)
        frags += TextFragment("C", 373.6f, 210f, 378.6f, 217.5f, 216f, 7.5f)
        frags += TextFragment("R", 365f, 220.6f, 371f, 230.6f, 228.6f, 10f)
        frags += TextFragment("C", 371f, 224.6f, 376f, 232.1f, 230.6f, 7.5f)
        val lines = LineBuilder().build(frags).map { it.text }
        assertTrue(lines.toString(), "VCC" in lines)
    }
}
