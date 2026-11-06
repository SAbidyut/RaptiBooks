package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layouts of typeset engineering textbooks: margin columns, numbered sections, contents pages. */
class TextbookLayoutTest {

    private fun structure(frags: List<TextFragment>): List<StructuredElement> {
        val layout = PageAnalyzer().analyze(RawPage(0, 570f, 735f, frags))
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

    @Test
    fun monospaceLinesInAPaddedPanelAreOneCodeBlockWithTheirIndentation() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 8) frags += words("body line $k of the running text in the main column of the page", 72f, 100f + k * 13f, size = 10f)
        // a panel padded 14 pt: lines of code, the third indented by four characters
        val code = listOf("services:", "  api:", "    build: .", "  db:")
        for ((k, l) in code.withIndex()) {
            val indent = l.length - l.trimStart().length
            val text = l.trimStart()
            val x = 86f + indent * 5f
            frags += TextFragment(text, x, 220f + k * 12f, x + text.length * 5f, 230f + k * 12f, 228f + k * 12f, 8.5f, "DejaVu-Sans-Mono")
        }
        for (k in 0 until 4) frags += words("more body text after the code block $k that goes on for a while", 72f, 300f + k * 13f, size = 10f)
        val blocks = structure(frags).filterIsInstance<StructuredElement.Paragraph>().filter { it.kind == ParagraphKind.CODE }
        assertEquals(1, blocks.size)
        assertEquals("services:\n  api:\n    build: .\n  db:", blocks.single().text)
    }

    @Test
    fun wordsOfTightlyJustifiedLinesKeepTheirSpaces() {
        // The text layer has space glyphs, but this line was set without them: words are moved.
        val glyphs = mutableListOf<TextFragment>()
        var x = 72f
        for (w in listOf("The", "presence", "of", "charges")) {
            for (c in w) {
                glyphs += TextFragment(c.toString(), x, 92f, x + 5f, 102f, 100f, 10f, spaceWidth = 2.5f)
                x += 5f
            }
            x += 2.8f
        }
        val builder = LineBuilder().apply { explicitSpacesHint = true }
        assertEquals("The presence of charges", builder.build(glyphs).single().text)
    }

    @Test
    fun marginCaptionIsNotJoinedToTheTextLineBesideIt() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 12) frags += words("body line $k of the running text in the main column", 214f, 100f + k * 12f, size = 10f)
        // A caption in the margin column ending 14 pt (1.4 em) before the text column.
        frags += words("Figure 1.5", 46f, 160f, size = 10f, bold = true)
        frags += words("Two kinds of flow", 125f, 172f, size = 10f)
        frags += words("for the reader.", 125f, 184f, size = 10f)
        val lines = LineBuilder().build(frags, splitColumns = true).map { it.text }
        assertTrue(lines.toString(), "body line 6 of the running text in the main column" in lines)
        assertTrue(lines.toString(), "Two kinds of flow" in lines)
    }

    @Test
    fun sectionNumberPrintedApartFromItsTitleIsOneHeading() {
        val frags = mutableListOf<TextFragment>()
        frags += words("1.2", 214f, 80f, size = 14f, bold = true)
        frags += words("Systems of Units", 273f, 80f, size = 14f)
        for (k in 0 until 10) frags += words("As electrical engineers we deal with measurable quantities every day $k", 214f, 110f + k * 12f, size = 10f)
        val t = texts(structure(frags))
        assertEquals("H2:1.2 Systems of Units", t.first())
    }

    @Test
    fun chapterOpenerBecomesOneChapterHeading() {
        val frags = mutableListOf<TextFragment>()
        frags += words("c h a p t e r", 392f, 50f, size = 17f, bold = true)
        frags += words("Basic Concepts", 67f, 100f, size = 36f)
        frags += words("1", 420f, 110f, size = 90f)
        for (k in 0 until 10) frags += words("Some books are to be tasted and others to be swallowed $k", 70f, 200f + k * 12f, size = 10f)
        val t = texts(structure(frags))
        assertEquals("H1:Chapter 1: Basic Concepts", t.first())
    }

    @Test
    fun contentsEntriesWithSectionNumbersAndInlinePages() {
        val frags = mutableListOf<TextFragment>()
        frags += words("Contents", 64f, 60f, size = 30f)
        val entries = listOf("Introduction 4", "Systems of Units 4", "Charge and Current 6", "Voltage 9", "Power and Energy 10")
        frags += words("Chapter 1", 64f, 100f, size = 10f, bold = true)
        frags += words("Basic Concepts 3", 130f, 100f, size = 10f)
        entries.forEachIndexed { k, e ->
            frags += words("1.${k + 1}", 70f, 115f + k * 12f, size = 10f)
            frags += words(e, 100f, 115f + k * 12f, size = 10f)
        }
        frags += words("Nodal Analysis with Voltage", 100f, 180f, size = 10f)
        frags += words("Sources 88", 100f, 192f, size = 10f)
        val elements = structure(frags)
        val toc = elements.filterIsInstance<StructuredElement.Paragraph>().filter { it.kind == ParagraphKind.TOC }.map { it.text }
        assertTrue(toc.toString(), "Chapter 1 Basic Concepts\t3" in toc)
        assertTrue(toc.toString(), "1.2 Systems of Units\t4" in toc)
        assertTrue(toc.toString(), "Nodal Analysis with Voltage Sources\t88" in toc)
    }

    @Test
    fun marginCaptionOnTheBaselinesOfTwoTextLines() {
        val frags = mutableListOf<TextFragment>()
        val body = "a circuit is a network providing one or more closed paths and so on"
        for (k in 0 until 6) frags += words(body, 70f, 100f + k * 12f, size = 10f)
        val right = frags.maxOf { it.right }
        // the caption starts 1.5 em after the text column, on the baselines of the next two lines
        frags += words(body, 70f, 172f, size = 10f) + words("Figure 2.10", right + 15f, 172f, size = 10f, bold = true)
        frags += words(body, 70f, 184f, size = 10f) + words("Nodes, branches, and loops.", right + 15f, 184f, size = 10f)
        for (k in 0 until 4) frags += words(body, 70f, 196f + k * 12f, size = 10f)
        val lines = LineBuilder().build(frags, splitColumns = true).map { it.text }
        assertTrue(lines.toString(), "Figure 2.10" in lines)
        assertTrue(lines.toString(), "Nodes, branches, and loops." in lines)
    }

    @Test
    fun runningHeadWithItsPageNumberIsNotText() {
        val titles = listOf("A BRIEF HISTORY", "UNITS OF MEASUREMENT", "SYSTEMS OF UNITS", "SIGNIFICANT FIGURES", "POWERS OF TEN", "SYMBOLS")
        val layouts = titles.mapIndexed { i, t ->
            val frags = mutableListOf<TextFragment>()
            frags += words("$t | ${i + 3}", 400f, 40f, size = 9f)
            for (k in 0 until 10) frags += words("The text of page $i goes on here with its line number $k", 70f, 100f + k * 12f, size = 10f)
            PageAnalyzer().analyze(RawPage(i, 570f, 735f, frags))
        }
        val profile = DocumentStats().apply { layouts.forEach { add(it) } }.profile()
        val t = texts(PageStructurer(profile).structure(layouts[2]))
        assertTrue(t.toString(), t.none { "SYSTEMS OF UNITS" in it })
    }

    @Test
    fun numberedPointsRunningOnAreListItemsNotHeadings() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += words("Before substituting numerical values into an equation try to establish $k", 70f, 100f + k * 12f, size = 10f)
        frags += words("a range of answers, and be absolutely sure of the following:", 70f, 136f, size = 10f)
        val points = listOf("Each quantity has the proper unit of measurement as defined by" to "the equation.",
            "The proper magnitude of each quantity as determined by the" to "defining equation is substituted.")
        var y = 160f
        for ((k, p) in points.withIndex()) {
            frags += words("${k + 1}. ${p.first}", 70f, y, size = 10f, bold = true, italic = true)
            frags += words(p.second, 82f, y + 12f, size = 10f, bold = true, italic = true)
            y += 26f
        }
        val elements = structure(frags)
        assertTrue(texts(elements).toString(), elements.none { it is StructuredElement.Heading })
        val items = elements.filterIsInstance<StructuredElement.ListItem>()
        assertEquals(texts(elements).toString(), "Each quantity has the proper unit of measurement as defined by the equation.", items.first().text)
    }

    @Test
    fun chapterNumberPrintedOverTheLetterSpacedWord() {
        val frags = mutableListOf<TextFragment>()
        frags += words("CHAPT1E R", 396f, 90f, size = 60f)
        frags += words("INTRODUCTION", 325f, 205f, size = 22.6f)
        frags += words("TO MACHINERY", 324f, 228f, size = 22.6f)
        frags += words("PRINCIPLES", 371f, 250f, size = 22.6f)
        frags += words("1.1 ELECTRICAL MACHINES,", 77f, 425f, size = 14.7f, bold = true)
        frags += words("TRANSFORMERS, AND DAILY LIFE", 76f, 441f, size = 14.7f, bold = true)
        for (k in 0 until 10) frags += words("An electrical machine is a device that can convert energy $k", 77f, 466f + k * 15f, size = 12.6f)
        val layout = PageAnalyzer().analyze(RawPage(0, 569f, 844f, frags))
        val profile = DocumentStats().apply { add(layout) }.profile()
        val t = texts(PageStructurer(profile).structure(layout))
        assertEquals(t.toString(), "H1:Chapter 1: INTRODUCTION TO MACHINERY PRINCIPLES", t[0])
        assertEquals(t.toString(), "H2:1.1 ELECTRICAL MACHINES, TRANSFORMERS, AND DAILY LIFE", t[1])
    }

    @Test
    fun subscriptsStayWithTheirLineBesideAnotherColumnsLabels() {
        val frags = mutableListOf<TextFragment>()
        // right column: "υ and the current i for …" with subscripts C below υ and i
        frags += words("υ and the current i for the discharge phase.", 334f, 465f, size = 9f)
        frags += TextFragment("C", 339f, 463f, 343f, 469f, 467.8f, 5.85f)
        frags += TextFragment("C", 404f, 463f, 408f, 469f, 467.8f, 5.85f)
        // left column: labels of a figure on (nearly) the subscripts' baseline
        frags += words("100 V", 111f, 469f, size = 8f) + words("1 μF", 150f, 469f, size = 8f) + words("E", 84f, 469f, size = 8f)
        val lines = LineBuilder().build(frags)
        assertTrue(lines.map { it.text }.toString(), lines.none { it.text.trim() == "C" || it.text.trim() == "C C" })
    }

    @Test
    fun inlineExampleLabelIsTheExamplesHeading() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 4) frags += words("Running text of the section before the example goes on here $k.", 70f, 100f + k * 12f, size = 10f)
        frags += words("EXAMPLE 6.11", 70f, 160f, size = 10f, bold = true) +
            words("Determine the values of R1, R2 and R3 in Fig. 6.16 if R2 = 2R1.", 140f, 160f, size = 10f)
        val t = texts(structure(frags))
        val k = t.indexOfFirst { it.endsWith("EXAMPLE 6.11") }
        assertTrue(t.toString(), k >= 0 && t[k].startsWith("H"))
        assertTrue(t.toString(), t[k + 1].startsWith("Determine the values"))
    }

    @Test
    fun exponentLongerThanItsNumberStaysWithIt() {
        val frags = mutableListOf<TextFragment>()
        frags += TextFragment("1", 373f, 671f, 377.5f, 681f, 679f, 9f)
        frags += TextFragment("0", 377.5f, 671f, 382f, 681f, 679f, 9f)
        for ((k, c) in "−18".withIndex()) {
            frags += TextFragment(c.toString(), 382f + k * 3.4f, 670f, 385.4f + k * 3.4f, 676f, 675f, 5.94f)
        }
        val line = LineBuilder().build(frags).single()
        assertEquals(9f, line.fontSize, 0.01f)
        assertEquals("10⁻¹⁸", LineBuilder.scriptText(line))
    }
}
