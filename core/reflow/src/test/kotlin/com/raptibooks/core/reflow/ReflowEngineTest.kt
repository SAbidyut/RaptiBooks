package com.raptibooks.core.reflow

import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.structure.analysis.DocumentStats
import com.raptibooks.core.structure.analysis.PageAnalyzer
import com.raptibooks.core.structure.analysis.PageStructurer
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.layout.BoxRegion
import com.raptibooks.core.structure.analysis.StructuredElement
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowEngineTest {

    private fun words(text: String, x: Float, baseline: Float, size: Float = 11f, bold: Boolean = false): List<TextFragment> {
        val out = mutableListOf<TextFragment>()
        var cx = x
        for ((i, w) in text.split(' ').withIndex()) {
            if (i > 0) cx += size * 0.25f
            val width = w.length * size * 0.5f
            out += TextFragment(w, cx, baseline - size * 0.8f, cx + width, baseline + size * 0.2f, baseline, size, "Serif", bold, false, size * 0.25f)
            cx += width
        }
        return out
    }

    /** Lays out lines at 11pt/15pt leading inside a 450pt column, wrapping by character count. */
    private class PageWriter(val index: Int) {
        val frags = mutableListOf<TextFragment>()
        var y = 110f
        fun line(text: String, x: Float = 72f, size: Float = 11f, bold: Boolean = false, gapBefore: Float = 0f): PageWriter {
            y += gapBefore
            frags += ReflowEngineTest().words(text, x, y, size, bold)
            y += size * 1.36f
            return this
        }
        fun para(text: String, indent: Float = 0f) {
            val maxChars = (450f / (11f * 0.5f)).toInt()
            val lines = mutableListOf<String>()
            var cur = StringBuilder()
            for (w in text.split(' ')) {
                if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) { lines += cur.toString(); cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
            }
            if (cur.isNotEmpty()) lines += cur.toString()
            lines.forEachIndexed { i, l -> line(l, x = 72f + if (i == 0) indent else 0f) }
        }
        fun build(): RawPage {
            frags += ReflowEngineTest().words("The Reflow Handbook", 230f, 50f, size = 9f)
            frags += ReflowEngineTest().words("${index + 1}", 295f, 800f, size = 9f)
            return RawPage(index, 595f, 842f, frags)
        }
    }

    private val sentence = "This sentence is long enough to wrap across several lines of the page when it is repeated"

    private fun convert(pages: List<RawPage>): Pair<List<ContentBlock>, ReflowEngine> {
        val analyzer = PageAnalyzer()
        val layouts: List<PageLayout> = pages.map { analyzer.analyze(it) }
        val stats = DocumentStats()
        layouts.forEach { stats.add(it) }
        val profile = stats.profile()
        val structurer = PageStructurer(profile)
        val blocks = mutableListOf<ContentBlock>()
        val engine = ReflowEngine { blocks += it }
        layouts.forEach { engine.addPage(structurer.structure(it)) }
        return blocks to engine
    }

    @Test
    fun rebuildsBookStructure() {
        val p0 = PageWriter(0).apply {
            line("Chapter 1", size = 22f, bold = true)
            line("Getting Started", size = 22f, bold = true)
            y += 20f
            para("$sentence. $sentence. $sentence.", indent = 18f)
            y += 4f
            para("A second paragraph begins here and $sentence and it does not end on this page but continues on the")
        }.build()
        val p1 = PageWriter(1).apply {
            para("next page without any break in the middle of the sentence. $sentence.")
            line("Things you need", size = 15f, bold = true, gapBefore = 12f)
            line("• A phone with the app installed", gapBefore = 4f)
            line("• A PDF file to read")
            line("• Some free time")
            y += 10f
            para("Closing words of the chapter. $sentence.", indent = 18f)
        }.build()
        val p2 = PageWriter(2).apply {
            line("Chapter 2", size = 22f, bold = true)
            y += 12f
            para("The second chapter starts on a fresh page. $sentence.", indent = 18f)
        }.build()
        val (blocks, engine) = convert(listOf(p0, p1, p2))
        val text = blocks.joinToString("\n") { "${it::class.simpleName}: ${it.plainText}" }

        // Running header and page numbers are gone.
        assertFalse(text, text.contains("The Reflow Handbook"))
        assertFalse(text, blocks.any { it.plainText.trim() == "2" })

        val headings = blocks.filterIsInstance<HeadingBlock>()
        assertEquals(text, "Chapter 1 Getting Started", headings[0].text)
        assertEquals(1, headings[0].level)
        assertTrue(text, headings.any { it.text == "Things you need" && it.level > 1 })
        assertTrue(text, headings.any { it.text == "Chapter 2" && it.level == 1 })

        // The paragraph split by the page break is joined.
        val joined = blocks.filterIsInstance<ParagraphBlock>().single { it.text.startsWith("A second paragraph") }
        assertTrue(joined.text, joined.text.contains("continues on the next page without any break"))

        val items = blocks.filterIsInstance<ListItemBlock>()
        assertEquals(text, listOf("A phone with the app installed", "A PDF file to read", "Some free time"), items.map { it.text })

        val toc = TocBuilder.build(emptyList(), engine.finish(), engine.pageStarts(), engine.blockCount)
        assertTrue(toc.map { it.title }.toString(), toc.map { it.title }.containsAll(listOf("Chapter 1 Getting Started", "Chapter 2")))
    }

    @Test
    fun outlineWinsOverDetectedHeadings() {
        val headings = listOf(com.raptibooks.core.model.TocEntry("Introduction", 1, 3, 0), com.raptibooks.core.model.TocEntry("Methods", 1, 10, 2))
        val toc = TocBuilder.build(
            listOf(OutlineItem("Introduction", 0, 0), OutlineItem("Methods and data", 2, 0), OutlineItem("Results", 4, 1)),
            headings, mapOf(0 to 0, 2 to 9, 4 to 20), 30,
        )
        assertEquals(listOf(3, 10, 20), toc.map { it.blockIndex })
        assertEquals(2, toc[2].level)
    }

    @Test
    fun printedContentsBecomesTheTableOfContents() {
        // Contents on page 1 (blocks 1-6); the book's page "1" is PDF page 3.
        val printed = listOf(
            PrintedEntry("Chapter 1 Basics", "1", true, 1),
            PrintedEntry("1.1 Units", "2", false, 2),
            PrintedEntry("1.2 Charge", "4", false, 3),
            PrintedEntry("Chapter 2 Laws", "9", true, 4),
            PrintedEntry("2.1 Ohm's law", "10", false, 5),
        )
        // Only some of them were detected as headings.
        val headings = listOf(
            com.raptibooks.core.model.TocEntry("Contents", 1, 0, 1),
            com.raptibooks.core.model.TocEntry("Chapter 1 Basics", 1, 10, 3),
            com.raptibooks.core.model.TocEntry("Chapter 2 Laws", 1, 60, 11),
        )
        val starts = (1..13).associateWith { (it - 1) * 6 }
        val toc = TocBuilder.build(emptyList(), headings, starts, 80, printed)
        assertEquals(listOf("Chapter 1 Basics", "1.1 Units", "1.2 Charge", "Chapter 2 Laws", "2.1 Ohm's law"), toc.map { it.title })
        assertEquals(listOf(1, 2, 2, 1, 2), toc.map { it.level })
        assertEquals(listOf(3, 4, 6, 11, 12), toc.map { it.pageIndex })
        assertEquals(10, toc[0].blockIndex)
        assertEquals(starts[4], toc[1].blockIndex)
    }

    @Test
    fun unrelatedPrintedContentsIsIgnored() {
        val printed = (1..5).map { PrintedEntry("Topic $it", "${it * 100}", false, it) }
        val headings = listOf(com.raptibooks.core.model.TocEntry("Real chapter", 1, 10, 2))
        val toc = TocBuilder.build(emptyList(), headings, mapOf(0 to 0, 2 to 9), 30, printed)
        assertEquals(listOf("Real chapter"), toc.map { it.title })
    }

    @Test
    fun boxRunningOverAPageBreakIsOneBox() {
        val blocks = mutableListOf<ContentBlock>()
        val engine = ReflowEngine { blocks += it }
        val bottom = BoxRegion(Rect(70f, 600f, 525f, 780f), 0xF7F9FB, 0x1D5B8F, openBottom = true)
        val top = BoxRegion(Rect(70f, 60f, 525f, 200f), 0xF7F9FB, 0x1D5B8F, openTop = true)
        fun para(p: Int, t: String, kind: ParagraphKind = ParagraphKind.BODY) = StructuredElement.Paragraph(p, t, emptyList(), kind, false, 11f)
        engine.addPage(
            listOf(
                para(0, "Before the box."),
                StructuredElement.Box(0, true, bottom),
                StructuredElement.Heading(0, 4, "উদাহরণ ৪.১"),
                para(0, "The question runs"),
                StructuredElement.Box(0, false, bottom),
            ),
        )
        engine.addPage(
            listOf(
                StructuredElement.Box(1, true, top),
                para(1, "onto the next page."),
                para(1, "(ক)\tএক\n(খ)\tদুই", ParagraphKind.OPTIONS),
                StructuredElement.Box(1, false, top),
            ),
        )
        engine.addPage(
            listOf(
                StructuredElement.ListItem(2, "(গ)", "তিন", emptyList(), 1),
                StructuredElement.ListItem(2, "(ঘ)", "চার", emptyList(), 1),
                para(2, "After the box."),
            ),
        )
        engine.finish()
        val texts = blocks.map { it.plainText }
        assertTrue(texts.toString(), texts.contains("The question runs onto the next page."))
        val box = engine.boxes().single()
        assertEquals(1, box.startIndex)
        assertEquals("উদাহরণ ৪.১", blocks[box.startIndex].plainText)
        assertEquals(0x1D5B8F, box.accent)
        // Answer options split by the page break are one block again.
        val options = blocks.filterIsInstance<ParagraphBlock>().single { it.kind == ParagraphKind.OPTIONS }
        assertEquals("(ক)\tএক\n(খ)\tদুই\n(গ)\tতিন\n(ঘ)\tচার", options.text)
        assertTrue(blocks.indexOf(options) <= box.endIndex)
        assertEquals("After the box.", blocks.last().plainText)
        assertTrue(box.endIndex < blocks.size - 1)
    }

    @Test
    fun workedExamplesAndPracticeProblemsBecomeCards() {
        val blocks = mutableListOf<ContentBlock>()
        val engine = ReflowEngine { blocks += it }
        fun para(p: Int, t: String) = StructuredElement.Paragraph(p, t, emptyList(), ParagraphKind.BODY, false, 11f)
        engine.addPage(
            listOf(
                para(0, "Some text before."),
                StructuredElement.Heading(0, 3, "Example 1.1"),
                para(0, "How much charge is represented by 4,600 electrons?"),
                StructuredElement.Heading(0, 4, "Solution:"),
                para(0, "Each electron has a charge."),
                StructuredElement.Heading(0, 3, "Practice Problem 1.1"),
                para(0, "Calculate the amount of charge."),
                para(0, "Answer: 6.408 C."),
                para(0, "Text after the problem."),
                StructuredElement.Heading(0, 2, "1.4 Voltage"),
            ),
        )
        engine.finish()
        val boxes = engine.boxes()
        assertEquals(2, boxes.size)
        assertEquals("Example 1.1", blocks[boxes[0].startIndex].plainText)
        assertEquals("Each electron has a charge.", blocks[boxes[0].endIndex].plainText)
        assertEquals("Practice Problem 1.1", blocks[boxes[1].startIndex].plainText)
        assertEquals("Answer: 6.408 C.", blocks[boxes[1].endIndex].plainText)
        assertTrue(boxes[0].accent != boxes[1].accent)
    }
}
