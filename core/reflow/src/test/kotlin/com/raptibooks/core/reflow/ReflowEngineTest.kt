package com.raptibooks.core.reflow

import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.structure.analysis.DocumentStats
import com.raptibooks.core.structure.analysis.PageAnalyzer
import com.raptibooks.core.structure.analysis.PageStructurer
import com.raptibooks.core.structure.layout.PageLayout
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
}
