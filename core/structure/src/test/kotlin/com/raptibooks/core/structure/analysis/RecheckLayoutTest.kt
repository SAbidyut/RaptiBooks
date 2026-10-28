package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.raw.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Glossaries, exercises, numbered formulas and margin labels of engineering textbooks. */
class RecheckLayoutTest {

    private fun structure(frags: List<TextFragment>, paths: List<VectorPath> = emptyList()): List<StructuredElement> {
        val layout = PageAnalyzer(10f).analyze(RawPage(0, 570f, 735f, frags, paths = paths))
        val profile = DocumentStats().apply { add(layout) }.profile()
        return PageStructurer(profile).structure(layout)
    }

    private fun texts(elements: List<StructuredElement>) = elements.map {
        when (it) {
            is StructuredElement.Heading -> "H${it.level}:" + it.text
            is StructuredElement.Paragraph -> it.text
            is StructuredElement.ListItem -> it.marker + " " + it.text
            else -> it.toString()
        }
    }

    /** A bold term and its definition on one line. */
    private fun entry(term: String, rest: String, x: Float, y: Float): List<TextFragment> {
        val t = words(term, x, y, size = 9f, bold = true)
        return t + words(rest, t.last().right + 2.25f, y, size = 9f)
    }

    @Test
    fun glossaryEntriesHangFromTheirTerms() {
        val frags = mutableListOf<TextFragment>()
        frags += entry("Cathode-ray tube (CRT)", "A glass enclosure with a relatively flat", 60f, 100f)
        frags += words("face (screen) and a vacuum inside that will display the light", 72f, 111f, size = 9f)
        frags += words("generated from the bombardment of the screen by electrons.", 72f, 122f, size = 9f)
        frags += entry("CGS system", "The system of units employing the Centimeter,", 60f, 133f)
        frags += words("Gram, and Second as its fundamental units of measure.", 72f, 144f, size = 9f)
        frags += entry("Difference engine", "One of the first mechanical calculators.", 60f, 155f)
        frags += entry("Edison effect", "Establishing a flow of charge between two ele-", 60f, 166f)
        frags += words("ments in an evacuated tube.", 72f, 177f, size = 9f)
        val t = texts(structure(frags))
        assertEquals(
            t.toString(),
            listOf(
                "Cathode-ray tube (CRT) A glass enclosure with a relatively flat face (screen) and a vacuum inside that will display the light generated from the bombardment of the screen by electrons.",
                "CGS system The system of units employing the Centimeter, Gram, and Second as its fundamental units of measure.",
                "Difference engine One of the first mechanical calculators.",
                "Edison effect Establishing a flow of charge between two elements in an evacuated tube.",
            ),
            t,
        )
    }

    @Test
    fun problemsNumberedWithADashHangLikeListItems() {
        val frags = mutableListOf<TextFragment>()
        frags += words("1-10. The wire shown in Figure P1-7 is moving in the presence of a magnetic field. With", 66f, 100f, size = 10f)
        frags += words("the information given in the figure, determine the magnitude and direction of the", 84f, 112f, size = 10f)
        frags += words("induced voltage in the wire.", 84f, 124f, size = 10f)
        frags += words("I- II. Repeat Problem 1-10 for the wire in Figure P1-8.", 66f, 136f, size = 10f)
        val items = structure(frags).filterIsInstance<StructuredElement.ListItem>()
        assertEquals(listOf("1-10.", "1-11."), items.map { it.marker })
        assertTrue(items[0].text, items[0].text.endsWith("direction of the induced voltage in the wire."))
    }

    @Test
    fun exampleLabelBesideTheFirstLineComesBeforeTheQuestion() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 4) frags += words("The voltage across the load is found by the method of transformation $k", 70f, 100f + k * 12f, size = 10f)
        frags += words("Calculate Vx in the circuit of Fig. 10.17 using the method of source", 70f, 189f, size = 10f)
        frags += words("Example 10.7", 433f, 187f, size = 14f)
        frags += words("transformation.", 70f, 201f, size = 10f)
        val t = texts(structure(frags))
        val label = t.indexOfFirst { it.endsWith("Example 10.7") }
        assertTrue(t.toString(), label >= 0 && t[label + 1] == "Calculate Vx in the circuit of Fig. 10.17 using the method of source transformation.")
    }

    @Test
    fun piecesOfAFormulasRowAreOneFormula() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += words("Applying KVL around the loop of the circuit gives the current through it $k", 214f, 600f + k * 12f, size = 10f)
        frags += words("−20 + 2i + 3i = 0", 233f, 647f, size = 10f)
        frags += words("or", 335f, 647f, size = 10f)
        frags += words("5i = 20", 364f, 647f, size = 10f)
        frags += words("⇒", 418f, 647f, size = 10f)
        frags += words("i = 4 A", 451f, 647f, size = 10f)
        val formulas = structure(frags).filterIsInstance<StructuredElement.Paragraph>().filter { it.kind == ParagraphKind.FORMULA }
        assertEquals(listOf("−20 + 2i + 3i = 0   or   5i = 20   ⇒   i = 4 A"), formulas.map { it.text })
    }

    @Test
    fun eachNumberedFormulaKeepsItsNumber() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += words("For loops two and three of the circuit, applying KVL produces the equations $k", 120f, 100f + k * 12f, size = 10f)
        frags += words("4i2 + 2(i2 − i1) + 6(i2 − i3) = 0", 200f, 150f, size = 10f)
        frags += words("(4.9.2)", 470f, 150f, size = 10f)
        frags += words("6(i3 − i2) + 2i3 + 1 = 0", 215f, 164f, size = 10f)
        frags += words("(4.9.3)", 470f, 164f, size = 10f)
        for (k in 0 until 3) frags += words("Solving these equations gives the currents in the three loops of the circuit $k", 120f, 190f + k * 12f, size = 10f)
        // two identities numbered once, the number between them
        frags += words("sin(A ± B) = sin A cos B ± cos A sin B", 180f, 240f, size = 10f)
        frags += words("cos(A ± B) = cos A cos B ∓ sin A sin B", 180f, 254f, size = 10f)
        frags += words("(9.9)", 470f, 247f, size = 10f)
        for (k in 0 until 3) frags += words("With these identities it is easy to show that the sinusoids are related $k", 120f, 280f + k * 12f, size = 10f)
        val layout = PageAnalyzer(10f).analyze(RawPage(0, 570f, 735f, frags))
        val equations = layout.figures.filter { it.kind == FigureKind.EQUATION }
        assertEquals(listOf("(4.9.2)", "(4.9.3)", "(9.9)"), equations.sortedBy { it.bounds.top }.map { it.caption })
        assertTrue(layout.lines.none { it.text.trim().startsWith("(") })
    }

    @Test
    fun iconBesideAProblemIsNoBullet() {
        val frags = words("7.21 In the circuit of Fig. 7.101, find the value of R for", 80f, 300f, size = 10f, bold = false) +
            words("which the steady-state energy stored in the inductor will be 0.25 J.", 80f, 312f, size = 10f)
        // the "e2d" icon of a design problem: pieces side by side, touching
        val icon = listOf(
            VectorPath(Rect(49.6f, 305.6f, 56.9f, 312.2f), PathShape.COMPLEX, stroked = false, filled = true, segments = 11),
            VectorPath(Rect(56.3f, 303.2f, 66.6f, 312.7f), PathShape.COMPLEX, stroked = false, filled = true, segments = 12),
            VectorPath(Rect(65.6f, 303.4f, 72.6f, 312.2f), PathShape.COMPLEX, stroked = false, filled = true, segments = 13),
        )
        val layout = PageAnalyzer(10f).analyze(RawPage(0, 570f, 735f, frags, paths = icon))
        assertFalse(layout.lines.toString(), layout.lines.any { it.text.startsWith("•") })
        // a bullet on its own is one
        val bullet = VectorPath(Rect(70f, 305f, 74f, 309f), PathShape.COMPLEX, stroked = false, filled = true, segments = 8)
        val listed = PageAnalyzer(10f).analyze(RawPage(0, 570f, 735f, frags, paths = listOf(bullet)))
        assertTrue(listed.lines.any { it.text.startsWith("• which") })
    }

    @Test
    fun compoundsBrokenAtTheirHyphenKeepIt() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += words("A three-phase transformer bank feeds the three-phase load $k", 70f, 100f + k * 12f, size = 10f)
        val layout = PageAnalyzer(10f).analyze(RawPage(0, 570f, 735f, frags))
        val profile = DocumentStats().apply { add(layout) }.profile()
        assertTrue(profile.keepsHyphen("the three", "phase power"))
        assertFalse(profile.keepsHyphen("trans", "former"))
    }

    @Test
    fun dashesTheOcrReadAsHyphensAreDashes() {
        fun chars(text: String, x: Float, gapAfterDash: Float): List<TextFragment> {
            val out = mutableListOf<TextFragment>()
            var cx = x
            for (c in text) {
                if (c == ' ') {
                    out += TextFragment(" ", cx, 90f, cx + 3f, 103f, 100f, 12.7f, "Times-Roman")
                    cx += 3f
                    continue
                }
                val w = if (c == '-') 4.2f else 5.6f
                out += TextFragment(c.toString(), cx, 90f, cx + w, 103f, 100f, 12.7f, "Times-Roman")
                cx += w + if (c == '-') gapAfterDash else 0.3f
            }
            return out
        }
        fun text(frags: List<TextFragment>) = LineBuilder().build(ScanPage.dashes(frags)).single().text
        assertEquals("all machines—both actions", text(chars("all machines-both actions", 60f, 9.9f)))
        assertEquals("a fixed torque-speed curve", text(chars("a fixed torque-speed curve", 60f, 2.7f)))
        assertEquals("two- and three", text(chars("two-and three", 60f, 3.2f)))
    }
}
