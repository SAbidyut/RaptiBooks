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
    fun foldsScriptsOnlyALittleSmallerIntoTheirLine() {
        // HTML sub/sup in a smaller math span: "E" at 11.3 pt, its "k" at 10.7 pt, 5.3 pt lower
        val main = words("E = E + E", 72f, 100f, size = 11.3f)
        val end = main.last().right
        val sub = listOf(TextFragment("k", end, 98f, end + 5.3f, 108f, 105.3f, 10.7f))
        val line = builder.build(main + sub).single()
        assertEquals("E = E + Ek", line.text)
        assertTrue(line.styles.any { it.flags and InlineStyle.SUBSCRIPT != 0 })
        // …and an exponent above a numerator
        val num = words("(5)", 72f, 100f, size = 11.7f)
        val exp = listOf(TextFragment("7", num.last().right, 88f, num.last().right + 5.3f, 98f, 94.7f, 10.7f))
        assertEquals("(5)7", builder.build(num + exp).single().text)
        // but the next line of a paragraph is a line of its own
        val next = words("k", end, 113.5f)
        assertEquals(2, builder.build(main + next).size)
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

    /** Glyphs of [text] placed one after another from [x], in the order they are drawn. */
    private fun glyphs(text: String, x: Float, baseline: Float, size: Float = 12f): List<TextFragment> {
        var cx = x
        return text.map { c ->
            val w = if (c == ' ') size * 0.25f else size * 0.5f
            TextFragment(c.toString(), cx, baseline - size * 0.8f, cx + w, baseline + size * 0.2f, baseline, size).also { cx += w }
        }
    }

    @Test
    fun putsArabicIntoReadingOrder() {
        // The PDF draws "هذا الكتاب" from the left: the glyphs come out reversed.
        val visual = "For example, " + "هذا الكتاب".reversed() + " means this book"
        assertEquals("For example, هذا الكتاب means this book", builder.build(glyphs(visual, 72f, 100f)).single().text)
        // An Arabic line reads from the right; its number keeps its digits in order.
        val line = "عام 1990 في العدد 25."
        val drawn = ".25 " + "ددعلا" + " يف 1990 " + "ماع"
        assertEquals(line, builder.build(glyphs(drawn, 72f, 100f)).single().text)
    }

    @Test
    fun showsGarbledWordsAsPictures() {
        // Text from a scan read by a Latin-only OCR: the Arabic words became junk.
        val text = listOf(
            "For example, y\\:iSll 11\\ means this book and that is all",
            "Like Spanish, \\:iS is masculine but o.Jt;.... is feminine here",
            "The magazine wl....JI oJ\\.c. writes a regular feature for us",
            // a passage in the other script
            "\\_;jWI \\A.J \\.;3 r:;\\S 1\\.c. \\:i.J",
            "c.JA \\! I.J\\b:i oy\\ic. ,JI\\_;i.ll \\.o.J o\\l",
        )
        val frags = text.flatMapIndexed { i, t -> glyphs(t, 72f, 100f + i * 16f) }
        val lines = builder.pictureWords(builder.build(frags))
        val first = lines.first { it.text.startsWith("For example") }
        assertEquals("For example, \u2B1A means this book and that is all", first.text)
        assertEquals(1, first.pictures.size)
        assertTrue(first.styles.single().picture)
        // the picture is where the garbled words were printed
        assertTrue(first.pictures[0].left >= 72f + 12 * 6f && first.pictures[0].right < 72f + 30 * 6f)
        assertTrue(lines.none { it.text.contains("\\") || it.text.contains("Jt;") })
        // a line of the other script is read from the right, word by word
        val passage = lines.last()
        assertTrue(passage.text, passage.text.startsWith("‏") && passage.pictures.size >= 6)
        val order = passage.styles.map { InlineStyle.pictureId(it.flags) }
        assertEquals(order.sortedDescending(), order)
    }
}
