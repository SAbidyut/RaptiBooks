package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.TestPages
import com.raptibooks.core.structure.TestPages.hLine
import com.raptibooks.core.structure.TestPages.vLine
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.raw.ImageRegion
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.raw.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PageAnalysisTest {
    private val analyzer = PageAnalyzer()

    @Test
    fun ruledTableBecomesGrid() {
        val xs = listOf(72f, 200f, 330f, 460f)
        val ys = listOf(300f, 320f, 340f, 360f)
        val paths = xs.map { vLine(it, ys.first(), ys.last()) } + ys.map { hLine(xs.first(), xs.last(), it) }
        val cells = listOf(
            listOf("Name", "Age", "City"),
            listOf("Rahim", "34", "Dhaka"),
            listOf("Karim", "29", "Sylhet"),
        )
        val frags = mutableListOf<TextFragment>()
        cells.forEachIndexed { r, row ->
            row.forEachIndexed { c, text -> frags += words(text, xs[c] + 4f, ys[r] + 14f, bold = r == 0) }
        }
        frags += words("A paragraph above the table.", 72f, 250f)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags, paths = paths))
        val table = layout.tables.single()
        assertEquals(cells, table.rows)
        assertEquals(1, table.headerRows)
        assertEquals(listOf("A paragraph above the table."), layout.lines.map { it.text })
    }

    @Test
    fun alignedTextBecomesTable() {
        val frags = mutableListOf<TextFragment>()
        val rows = listOf(listOf("Item", "Qty", "Price"), listOf("Rice", "2", "120"), listOf("Lentil", "1", "95"), listOf("Oil", "3", "540"))
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, t -> frags += words(t, 72f + c * 110f, 200f + r * 16f) }
        }
        for (k in 0 until 4) frags += words("Some running text line number $k for context here.", 72f, 400f + k * 15f)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags))
        val table = layout.tables.single()
        assertEquals(rows, table.rows)
    }

    @Test
    fun vectorDiagramBecomesFigureWithCaption() {
        val shapes = listOf(
            VectorPath(Rect(100f, 300f, 200f, 350f), PathShape.RECTANGLE, stroked = true, filled = false),
            VectorPath(Rect(300f, 300f, 400f, 350f), PathShape.RECTANGLE, stroked = true, filled = false),
            VectorPath(Rect(200f, 320f, 300f, 330f), PathShape.COMPLEX, stroked = true, filled = false, segments = 3),
            VectorPath(Rect(220f, 360f, 280f, 420f), PathShape.COMPLEX, stroked = true, filled = true, segments = 8),
        )
        val frags = words("Input", 130f, 330f) + words("Output", 330f, 330f) +
            words("Figure 1: Data flow of the converter", 140f, 440f) +
            words("Body text after the figure continues here.", 72f, 480f)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags, paths = shapes))
        val fig = layout.figures.single()
        assertEquals("Figure 1: Data flow of the converter", fig.caption)
        assertEquals(listOf("Body text after the figure continues here."), layout.lines.map { it.text })
    }

    @Test
    fun rasterImageBecomesFigure() {
        val frags = words("Text above the photo.", 72f, 100f)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags, images = listOf(ImageRegion(Rect(72f, 150f, 400f, 400f)))))
        assertEquals(1, layout.figures.size)
    }

    @Test
    fun shadedTextBoxIsNotAFigure() {
        val (para, _) = TestPages.paragraph(TestPages.LOREM, 90f, 320f, 400f)
        val box = VectorPath(Rect(80f, 300f, 500f, 400f), PathShape.RECTANGLE, stroked = false, filled = true)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, para, paths = listOf(box)))
        assertTrue(layout.figures.isEmpty())
        assertTrue(layout.lines.size >= 3)
    }

    @Test
    fun layoutSurvivesSerialization() {
        val frags = words("Serialized line", 72f, 100f, bold = true)
        val layout = analyzer.analyze(RawPage(3, 595f, 842f, frags, images = listOf(ImageRegion(Rect(72f, 150f, 400f, 400f)))))
        layout.figures.first().imagePath = "/tmp/fig.png"
        val f = File.createTempFile("layout", ".bin")
        PageLayout.write(layout, f)
        val back = PageLayout.read(f)
        assertEquals(3, back.index)
        assertEquals("Serialized line", back.lines.single().text)
        assertEquals("/tmp/fig.png", back.figures.single().imagePath)
        assertEquals(layout.lines.single().styles, back.lines.single().styles)
        f.delete()
    }

    @Test
    fun twoColumnsAreReadColumnByColumn() {
        val frags = mutableListOf<TextFragment>()
        frags += words("A Two Column Title", 200f, 80f, size = 18f)
        for (k in 0 until 8) {
            frags += words("left $k text in the first column", 60f, 120f + k * 14f)
            frags += words("right $k text in the second column", 320f, 120f + k * 14f)
        }
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags))
        val profile = DocumentStats().apply { add(layout) }.profile()
        val elements = PageStructurer(profile).structure(layout)
        val texts = elements.map {
            when (it) {
                is StructuredElement.Heading -> "H:" + it.text
                is StructuredElement.Paragraph -> it.text
                else -> it.toString()
            }
        }
        assertEquals("H:A Two Column Title", texts.first())
        val all = texts.joinToString("\n")
        val leftIdx = all.indexOf("left 7")
        val rightIdx = all.indexOf("right 0")
        assertTrue("left column must come before right column: $texts", leftIdx in 0 until rightIdx)
        assertTrue(all.indexOf("left 0") < all.indexOf("left 1") && all.indexOf("right 6") < all.indexOf("right 7"))
    }

    @Test
    fun scannedPageIsDetectedForOcr() {
        val page = RawPage(0, 595f, 842f, emptyList(), images = listOf(ImageRegion(Rect(0f, 0f, 595f, 842f))))
        assertEquals(OcrDecision.Reason.SCANNED, OcrDecision.evaluate(page, 0, 0))
        val textPage = RawPage(0, 595f, 842f, TestPages.paragraph(TestPages.LOREM, 72f, 100f, 450f).first)
        assertEquals(OcrDecision.Reason.NONE, OcrDecision.evaluate(textPage, 300, 300))
        assertEquals(OcrDecision.Reason.UNMAPPED_GLYPHS, OcrDecision.evaluate(textPage, 900, 100))
        assertNotNull(textPage)
    }

    @Test
    fun systemOfEquationsInColumnsIsOnePicture() {
        val frags = TestPages.paragraph(TestPages.LOREM, 60f, 100f, 450f, size = 10f).first.toMutableList()
        frags += words("In determinant form,", 60f, 250f, size = 10f)
        val rows = listOf(
            listOf("I1(Z1 + ZL1) − I2Zm12", "+ I3Zm13", "= E1"),
            listOf("−I1Zm12", "+ I2(Z2 + ZL2) + 0", "= 0"),
            listOf("I1Zm13", "+ 0 + I3(Z3 + ZL3)", "= 0"),
        )
        for ((r, row) in rows.withIndex()) {
            for ((c, piece) in row.withIndex()) frags += words(piece, listOf(94f, 223f, 300f)[c] - (if (c == 1 && r > 0) 72f else 0f), 275f + r * 14f, size = 10f)
        }
        frags += TestPages.paragraph(TestPages.LOREM, 60f, 340f, 450f, size = 10f).first
        val layout = analyzer.analyze(RawPage(0, 594f, 783f, frags))
        val eq = layout.figures.single { it.kind == com.raptibooks.core.structure.layout.FigureKind.EQUATION }
        assertTrue("${eq.bounds} ${layout.lines}", eq.bounds.left <= 95f && eq.bounds.right >= 315f && eq.bounds.height >= 30f)
        assertTrue("${eq.bounds} ${layout.lines}", layout.lines.none { "Zm1" in it.text || it.text.trim().startsWith("= ") })
    }

    @Test
    fun centeredShortLinesAreCentered() {
        val frags = words("A short centered line", 230f, 300f) + words("another one here", 240f, 315f)
        val layout = analyzer.analyze(RawPage(0, 595f, 842f, frags + TestPages.paragraph(TestPages.LOREM, 72f, 400f, 450f).first))
        val profile = DocumentStats().apply { add(layout) }.profile()
        val els = PageStructurer(profile).structure(layout).filterIsInstance<StructuredElement.Paragraph>()
        assertEquals(ParagraphKind.CENTERED, els.first().kind)
    }
}
