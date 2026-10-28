package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlinePicture
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.TestPages
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.ImageRegion
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An English book about Arabic whose OCR could not read the Arabic. */
class ForeignTextTest {

    private fun line(text: String, left: Float, top: Float, size: Float = 12f, pictures: List<Rect> = emptyList()): TextLine {
        val right = left + text.length * size * TestPages.CHAR_WIDTH
        val styles = text.indices.filter { text[it] == InlinePicture.MARKER }.mapIndexed { k, i -> InlineStyle(i, i + 1, InlineStyle.pictureFlags(k)) }
        return TextLine(text, styles, Rect(left, top, right, top + size), top + size * 0.8f, size, 0f, 0f, "Serif", emptyList(), pictures)
    }

    /** A line of the other script: one picture per word, read from the right. */
    private fun foreign(words: List<Rect>): TextLine {
        val text = "‏" + words.joinToString(" ") { InlinePicture.MARKER.toString() }
        val b = Rect.bounding(words)!!
        val styles = (0 until words.size).map { k -> InlineStyle(1 + 2 * k, 2 + 2 * k, InlineStyle.pictureFlags(k)) }
        return TextLine(text, styles, b, b.bottom - 4f, 12f, 0f, 0f, "", emptyList(), words.sortedByDescending { it.right })
    }

    @Test
    fun linesLeftAsImagesBecomeLinesOfPictures() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += words("Answer the questions below and write in the case endings $k", 72f, 80f + k * 14f, size = 12f)
        // the questions: pictures of a line each, ending at the right margin
        val images = listOf(Rect(470f, 140f, 568f, 155f), Rect(490f, 190f, 568.5f, 206f), Rect(455f, 240f, 567f, 256f)).map { ImageRegion(it) }
        val layout = PageAnalyzer().analyze(RawPage(0, 612f, 792f, frags, images))
        val questions = layout.lines.filter { it.text.startsWith("‏") }
        assertEquals(3, questions.size)
        assertTrue(questions.all { it.text == "‏${InlinePicture.MARKER}" && it.styles.single().picture })
        assertEquals(3, layout.pictures.size)
        assertTrue(layout.figures.isEmpty())
    }

    @Test
    fun gridOfWordsIsShownAsPrinted() {
        // a drill's word list: four columns of words, with a stray piece of junk among them
        val rows = (0 until 6).map { r ->
            foreign(listOf(130f, 255f, 385f, 512f).map { x -> Rect(x, 240f + r * 26f, x + 30f, 258f + r * 26f) })
        }
        val junk = line("_>AJ", 523f, 252f, size = 9f)
        val text = line("Pronounce each word below, one at a time.", 44f, 200f)
        val grids = ForeignText.grids(rows + junk + text)
        assertEquals(1, grids.size)
        val (figure, lines) = grids.single()
        assertEquals(FigureKind.VECTOR, figure.kind)
        assertTrue(junk in lines && rows.all { it in lines } && text !in lines)
        // sentences of the other script, one under another, stay lines of pictures
        val sentences = (0 until 6).map { r -> foreign((0 until 5).map { w -> Rect(400f + w * 32f, 130f + r * 30f, 428f + w * 32f, 148f + r * 30f) }) }
        assertTrue(ForeignText.grids(sentences).isEmpty())
    }

    @Test
    fun tableWithAColumnOfJunkIsUnreadable() {
        val junk = TableRegion(
            Rect(150f, 520f, 535f, 594f),
            listOf(
                listOf("1. The student is in the house.", "/'.u'l ylUJI . '"),
                listOf("2. You are the director in this office.", "I I .l.JI dl ·\""),
                listOf("3. The library is near the university.", ".W• I · (.)A I .i ·.J! ."),
            ),
            0, true,
        )
        assertTrue(ForeignText.unreadable(junk))
        val values = TableRegion(
            Rect(72f, 300f, 460f, 360f),
            listOf(listOf("Resistor", "Value"), listOf("R1", "10 kΩ"), listOf("R2", "2.2 kΩ"), listOf("Ratio", "I/R")),
            1, true,
        )
        assertFalse(ForeignText.unreadable(values))
    }

    @Test
    fun lineOfTheOtherScriptBesideASentenceComesAtItsEnd() {
        val first = line("6. The office director's car (the car of the director", 75f, 79f)
        val second = line("of the office).", 74f, 94f)
        val arabic = foreign(listOf(Rect(469f, 79f, 499f, 101f), Rect(503f, 79f, 523f, 101f), Rect(526f, 79f, 551f, 101f)))
        val next = line("The translations in parentheses are literal translations.", 99f, 125f)
        val out = ForeignText.joinCells(listOf(first, arabic, second, next))
        assertEquals(3, out.size)
        val end = out[1]
        assertEquals("of the office). ${InlinePicture.MARKER}", end.text)
        assertEquals(Rect(469f, 79f, 551f, 101f), end.pictures.single())
        assertTrue(end.styles.single().picture)
    }

    @Test
    fun scanPatchOverWordsShownAsPrintedIsNoFigure() {
        val words = line("These are words such as ${InlinePicture.MARKER} Whenever you see", 75f, 445f, size = 13f, pictures = listOf(Rect(264f, 448f, 344f, 464f)))
        val patch = ImageRegion(Rect(279f, 435f, 349f, 462f))
        val photo = ImageRegion(Rect(408f, 634f, 536f, 733f))
        assertEquals(setOf(patch), ForeignText.patches(listOf(patch, photo), listOf(words)))
    }
}
