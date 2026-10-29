package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.TestPages
import com.raptibooks.core.structure.TestPages.hLine
import com.raptibooks.core.structure.TestPages.vLine
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.raw.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worked examples as textbooks print them: a tinted, framed box with a question, the given data,
 * a centred column of calculation steps and the answer.
 */
class WorkedExampleTest {
    private val analyzer = PageAnalyzer(11f)

    private fun structure(layout: PageLayout): List<StructuredElement> {
        val profile = DocumentStats().apply { add(layout) }.profile()
        return PageStructurer(profile).structure(layout)
    }

    /** A boxed worked example: frame + tint, prose question, data lines, three centred steps. */
    private fun examplePage(): RawPage {
        val frags = mutableListOf<TextFragment>()
        frags += TestPages.paragraph(TestPages.LOREM, 72f, 80f, 450f).first
        frags += words("উদাহরণ ৪.১", 76f, 220f, size = 12f, bold = true)
        frags += TestPages.paragraph(
            "একটি বাতির রোধ ৪৬০ ওহম এবং সরবরাহ ২৩০ ভোল্ট হলে বাতিটির মধ্য দিয়ে কত কারেন্ট প্রবাহিত হবে তা নির্ণয় করুন।",
            76f, 245f, 440f,
        ).first
        frags += words("প্রদত্ত :", 76f, 290f, bold = true) + words("V = 230 V R = 460 Ω", 120f, 290f)
        frags += words("নির্ণেয় :", 76f, 310f, bold = true) + words("I = ?", 120f, 310f)
        frags += words("I = V / R", 260f, 340f, size = 12f)
        frags += words("= 230 / 460", 262f, 358f, size = 12f)
        frags += words("= 0.5 A", 266f, 376f, size = 12f)
        frags += words("উত্তর : বাতিটির মধ্য দিয়ে 0.5 A কারেন্ট প্রবাহিত হবে।", 76f, 405f, bold = true)
        val box = Rect(70f, 205f, 525f, 415f)
        val paths = listOf(
            VectorPath(box, PathShape.RECTANGLE, stroked = false, filled = true, lightFill = true, segments = 4, color = 0xF7F9FB),
            hLine(box.left, box.right, box.top), hLine(box.left, box.right, box.bottom),
            vLine(box.left, box.top, box.bottom).copyWithColor(0x1D5B8F), vLine(box.right, box.top, box.bottom),
        )
        return RawPage(0, 595f, 842f, frags, paths = paths)
    }

    private fun VectorPath.copyWithColor(c: Int) = VectorPath(bounds, shape, stroked, filled, lightFill, segments, c)

    @Test
    fun framedExampleIsABoxNotATable() {
        val layout = analyzer.analyze(examplePage())
        assertTrue("a frame around prose is not a table: ${layout.tables.map { it.rows }}", layout.tables.isEmpty())
        val box = layout.boxes.single()
        assertEquals(0x1D5B8F, box.accent)
        assertEquals(0xF7F9FB, box.fill)
    }

    @Test
    fun calculationStepsBecomeOneEquation() {
        val layout = analyzer.analyze(examplePage())
        val eq = layout.figures.filter { it.kind == FigureKind.EQUATION }
        assertEquals(eq.map { it.text }.toString(), 1, eq.size)
        assertTrue(eq[0].text!!.contains("0.5 A"))
        // The last step does not end up in the answer.
        assertTrue(layout.lines.none { it.text.startsWith("= 0.5") })
    }

    @Test
    fun exampleKeepsItsPartsApartAndInsideTheBox() {
        val elements = structure(analyzer.analyze(examplePage()))
        val start = elements.indexOfFirst { it is StructuredElement.Box && it.start }
        val end = elements.indexOfFirst { it is StructuredElement.Box && !it.start }
        assertTrue(elements.toString(), start in 0 until end)
        val inside = elements.subList(start + 1, end)
        val texts = inside.filterIsInstance<StructuredElement.Paragraph>().map { it.text }
        assertTrue(texts.toString(), texts.any { it.startsWith("প্রদত্ত") && !it.contains("নির্ণেয়") })
        assertTrue(texts.toString(), texts.any { it.startsWith("নির্ণেয়") })
        assertTrue(texts.toString(), texts.any { it.startsWith("উত্তর") })
        assertTrue(inside.any { it is StructuredElement.Figure && it.region.kind == FigureKind.EQUATION })
        // The running text above the box is outside it.
        assertTrue(elements.subList(0, start).any { it is StructuredElement.Paragraph && it.text.startsWith("Reading on a phone") })
    }

    @Test
    fun exampleBetweenTwoRulesIsABox() {
        val frags = mutableListOf<TextFragment>()
        frags += TestPages.paragraph(TestPages.LOREM, 72f, 80f, 300f, size = 10f).first
        frags += words("EXAMPLE 1.4", 72f, 230f, size = 10f, bold = true)
        frags += words("a. 1/1000 = 10^-3 and b. 1/0.00001 = 10^5", 80f, 250f, size = 10f)
        frags += words("The product of powers of ten follows the same rule as before:", 72f, 290f, size = 10f)
        frags += TestPages.paragraph(TestPages.LOREM, 72f, 310f, 300f, size = 10f).first
        val paths = listOf(hLine(60f, 348f, 215f), hLine(60f, 348f, 265f))
        val elements = structure(analyzer.analyze(RawPage(0, 594f, 783f, frags, paths = paths)))
        val start = elements.indexOfFirst { it is StructuredElement.Box && it.start }
        val end = elements.indexOfFirst { it is StructuredElement.Box && !it.start }
        assertTrue(elements.toString(), start in 0 until end)
        val inside = elements.subList(start + 1, end).joinToString(" ") { it.toString() }
        assertTrue(inside, inside.contains("EXAMPLE 1.4") && inside.contains("10^-3"))
        assertFalse(inside, inside.contains("The product of powers"))
    }

    @Test
    fun formulaLineIsNeitherHeadingNorPartOfProse() {
        val frags = mutableListOf<TextFragment>()
        frags += TestPages.paragraph(TestPages.LOREM, 72f, 80f, 450f).first
        frags += words("RT = R1 + R2 + R3", 250f, 200f, size = 12f)
        frags += words("এখানে RT মোট রেজিস্ট্যান্স এবং R1, R2, R3 আলাদা রেজিস্ট্যান্স।", 72f, 230f)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags))
        val elements = structure(layout)
        assertTrue(elements.none { it is StructuredElement.Heading && it.text.contains("=") })
        val formula = elements.filterIsInstance<StructuredElement.Paragraph>().single { it.kind == ParagraphKind.FORMULA }
        assertEquals("RT = R1 + R2 + R3", formula.text)
    }

    @Test
    fun chapterWordInsideASentenceIsNotAChapter() {
        assertFalse(MathText.isFormula("ভাগ করে দেয়।"))
        val frags = mutableListOf<TextFragment>()
        frags += TestPages.paragraph(TestPages.LOREM, 72f, 80f, 450f).first
        frags += words("ভাগ করে দেয়।", 72f, 200f)
        frags += words("অধ্যায় ১৬-এর পাওয়ারের সূত্র।", 72f, 215f)
        val elements = structure(analyzer.analyze(RawPage(0, 595f, 842f, frags)))
        assertTrue(elements.toString(), elements.none { it is StructuredElement.Heading })
    }

    @Test
    fun hiddenTinyTextIsIgnored() {
        val frags = words("অধ্যায় ১ · ", 76f, 120f, size = 1f) + words("কর্মক্ষেত্রে নিরাপত্তা", 90f, 140f, size = 26f, bold = true) +
            TestPages.paragraph(TestPages.LOREM, 72f, 200f, 450f).first
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags))
        assertTrue(layout.lines.none { it.text.contains("অধ্যায়") })
        assertTrue(layout.lines.any { it.styles.isEmpty() || it.styles.all { s -> s.flags and InlineStyle.SUPERSCRIPT == 0 } })
    }
}
