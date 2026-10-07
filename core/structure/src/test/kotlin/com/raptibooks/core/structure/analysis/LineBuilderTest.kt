package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.raw.TextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineBuilderTest {
    private val builder = LineBuilder()

    @Test
    fun groupsWordsIntoOneLine() {
        val lines = builder.build(words("Hello reflowed world", 72f, 100f))
        assertEquals(1, lines.size)
        assertEquals("Hello reflowed world", lines[0].text)
        assertEquals(11f, lines[0].fontSize, 0.01f)
    }

    @Test
    fun infersSpacesFromGapsWhenThereAreNoSpaceGlyphs() {
        val frags = words("one two", 72f, 100f).filter { !it.isSpace }
        assertEquals("one two", builder.build(frags).single().text)
    }

    @Test
    fun keepsGlyphsWithoutGapsTogether() {
        // individual glyphs of a single word, touching each other
        val glyphs = "word".mapIndexed { i, c ->
            TextFragment(c.toString(), 72f + i * 5.5f, 91f, 72f + (i + 1) * 5.5f, 102f, 100f, 11f)
        }
        assertEquals("word", builder.build(glyphs).single().text)
    }

    @Test
    fun restoresXOrderForOutOfOrderWords() {
        val a = words("first", 72f, 100f)
        val b = words("second", 110f, 100f)
        assertEquals("first second", builder.build(b + a).single().text)
    }

    @Test
    fun splitsColumnsAtWideGaps() {
        val frags = words("left column text", 72f, 100f) + words("right column text", 320f, 100f)
        val lines = builder.build(frags).sortedBy { it.bounds.left }
        assertEquals(2, lines.size)
        assertEquals("left column text", lines[0].text)
        assertEquals("right column text", lines[1].text)
    }

    @Test
    fun separatesLinesByBaseline() {
        val frags = words("line one", 72f, 100f) + words("line two", 72f, 115f)
        val lines = builder.build(frags)
        assertEquals(listOf("line one", "line two"), lines.map { it.text })
    }

    @Test
    fun foldsSuperscriptIntoItsLine() {
        val main = words("E = mc", 72f, 100f)
        val sup = listOf(TextFragment("2", 100f, 90f, 103.5f, 96f, 95f, 7f))
        val line = builder.build(main + sup).single()
        assertEquals("E = mc2", line.text)
        assertTrue(line.styles.any { it.flags and InlineStyle.SUPERSCRIPT != 0 })
    }

    @Test
    fun recordsBoldRanges() {
        val space = TextFragment(" ", 99.5f, 91.2f, 102.25f, 102.2f, 100f, 11f)
        val frags = words("Note:", 72f, 100f, bold = true) + space + words("plain text", 72f + 5 * 5.5f + 2.75f, 100f)
        val line = builder.build(frags).single()
        assertEquals("Note: plain text", line.text)
        val bold = line.styles.single { it.bold }
        assertEquals("Note:", line.text.substring(bold.start, bold.end))
    }

    @Test
    fun convertsBijoyRuns() {
        val frags = words("Avgvi †mvbvi evsjv", 72f, 100f, font = "ABCDEF+SutonnyMJ")
        assertEquals("আমার সোনার বাংলা", builder.build(frags).single().text)
    }

    @Test
    fun mergesDropCap() {
        val cap = TextFragment("T", 72f, 80f, 90f, 112f, 110f, 34f)
        val l1 = words("his is the first line", 92f, 88f)
        val l2 = words("and the second line", 92f, 103f)
        val lines = builder.build(listOf(cap) + l1 + l2)
        assertEquals("This is the first line", lines.first().text.replace(" his", "his"))
    }
}
