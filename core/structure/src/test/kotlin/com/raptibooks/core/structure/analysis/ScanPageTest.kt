package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.ImageRegion
import com.raptibooks.core.structure.raw.InkMap
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.text.OcrWords
import com.raptibooks.core.structure.text.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** A book scanned and run through an OCR that left its text layer under the picture of each page. */
class ScanPageTest {

    private fun ink(width: Float, height: Float, dark: List<Rect>): InkMap {
        val cols = ceil(width / 2f).toInt()
        val rows = ceil(height / 2f).toInt()
        val cells = BooleanArray(cols * rows)
        for (r in dark) {
            for (y in (r.top / 2f).toInt()..(r.bottom / 2f).toInt()) for (x in (r.left / 2f).toInt()..(r.right / 2f).toInt()) {
                if (x in 0 until cols && y in 0 until rows) cells[y * cols + x] = true
            }
        }
        return InkMap(cols, rows, 2f, cells)
    }

    @Test
    fun drawingsAndFormulasOfAScanAreShownAsPrinted() {
        val frags = mutableListOf<TextFragment>()
        // a drawing at the top, its caption beside it
        frags += words("FIGURE 1-1", 400f, 120f, size = 10f, bold = true)
        frags += words("Derivation of the torque on an object.", 400f, 133f, size = 9f)
        for (k in 0 until 4) frags += words("Newton's law for objects moving along a straight line describes the force $k", 65f, 330f + k * 16f, size = 12f)
        // a display formula with its number, its text garbled by the OCR
        frags += words("F = nuJ", 257f, 410f, size = 12f)
        frags += words("(1- 7)", 464f, 410f, size = 12f)
        for (k in 0 until 4) frags += words("where the net force applied to an object gives the resulting acceleration $k", 65f, 440f + k * 16f, size = 12f)
        // ink: the letters, the outline of the drawing, and the bar the OCR left out of the formula
        val letters = frags.filter { !it.isSpace }.map { it.bounds }
        val drawing = listOf(Rect(90f, 80f, 290f, 82f), Rect(90f, 288f, 290f, 290f), Rect(90f, 80f, 92f, 290f), Rect(288f, 80f, 290f, 290f), Rect(92f, 82f, 288f, 288f).let { Rect(it.left, 180f, it.right, 182f) })
        val bar = Rect(250f, 418f, 300f, 419f)
        val page = RawPage(0, 569f, 844f, frags, images = listOf(ImageRegion(Rect(0f, 0f, 569f, 844f))), ink = ink(569f, 844f, letters + drawing + bar))
        // (a scan: the picture of the page under invisible text; a photo behind printed text is not)
        val scanned = RawPage(0, 569f, 844f, frags, images = listOf(ImageRegion(Rect(0f, 0f, 569f, 844f))), invisibleText = true)
        assertTrue(ScanPage.isScan(scanned) && !ScanPage.isScan(RawPage(0, 569f, 844f, frags, images = scanned.images)))
        val layout = PageAnalyzer(12f).analyze(page)
        val formula = layout.figures.single { it.kind == FigureKind.EQUATION }
        assertEquals("(1–7)", formula.caption)
        assertTrue(formula.bounds.contains(bar))
        val figure = layout.figures.single { it.kind != FigureKind.EQUATION }
        assertTrue(figure.caption, figure.caption!!.startsWith("FIGURE 1-1 Derivation"))
        assertTrue(figure.bounds.contains(Rect(90f, 80f, 290f, 290f), 1f) && figure.bounds.right < 400f)
        assertTrue(layout.lines.none { "nuJ" in it.text || "(1- 7)" in it.text || "Derivation" in it.text })
        assertEquals(8, layout.lines.size)
    }

    @Test
    fun hyphenWithoutACharacterEndsTheLine() {
        val l = TextLine("an object and its resulting acceleration. This rela\uFFFC", emptyList(), Rect(65f, 400f, 470f, 413f), 410f, 12f, 0f, 0f, "Times")
        assertEquals("an object and its resulting acceleration. This rela-", ScanPage.lineEndHyphens(listOf(l)).single().text)
    }

    private val vocab = Vocabulary(
        mapOf(
            "transformer" to 30, "transfonner" to 25, "the" to 500, "this" to 80, "flux" to 40, "nux" to 4, "current" to 120,
            "term" to 2, "tenn" to 2, "now" to 50, "flow" to 60, "all" to 80, "alt" to 1, "annual" to 1, "turn" to 20, "tum" to 2,
        ),
    )

    @Test
    fun wordsTheOcrMisreadArePutRight() {
        assertEquals("transformer", OcrWords.correct("transfonner", vocab))
        assertEquals("TRANSFORMER", OcrWords.correct("TRANSFONNER", vocab))
        assertEquals("The", OcrWords.correct("1lle", vocab))
        assertEquals("This", OcrWords.correct("1llis", vocab))
        assertEquals("flux", OcrWords.correct("nux", vocab))
        assertEquals("current", OcrWords.correct("currenl", vocab))
        assertEquals("term", OcrWords.correct("tenn", vocab))
        assertEquals("turn", OcrWords.correct("tum", vocab))
        // words of their own stay
        assertNull(OcrWords.correct("now", vocab))
        assertNull(OcrWords.correct("all", vocab))
        assertNull(OcrWords.correct("annual", vocab))
        assertNull(OcrWords.correct("transformer", vocab))
    }

    @Test
    fun stylesFollowTheWordsPutRight() {
        val text = "1lle transfonner core ofa machine"
        val bold = InlineStyle(5, 16, InlineStyle.BOLD)
        val line = TextLine(text, listOf(bold), Rect(65f, 400f, 300f, 413f), 410f, 12f, 0f, 0f, "Times")
        val fixed = OcrWords.fix(listOf(line), vocab).single()
        assertEquals("The transformer core of a machine", fixed.text)
        val s = fixed.styles.single()
        assertEquals("transformer", fixed.text.substring(s.start, s.end))
    }
}
