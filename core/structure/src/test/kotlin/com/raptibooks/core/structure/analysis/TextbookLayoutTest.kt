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
