package com.raptibooks.core.reflow

import com.raptibooks.core.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class TocBuilderTest {
    @Test
    fun aSolutionsManualGetsOneEntryPerChapter() {
        // hundreds of "Chapter N, Problem M" headings and no chapter titles
        val headings = ArrayList<TocEntry>()
        var block = 0
        for (ch in 1..4) for (p in 1..150) {
            headings += TocEntry("Chapter $ch, Problem $p.", 3, block, block / 4)
            block += 5
        }
        val toc = TocBuilder.build(emptyList(), headings, emptyMap(), block)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), toc.map { it.title })
        assertEquals(150 * 5, toc[1].blockIndex)

        // bookmarks a word processor made by itself (half without a title) are not used
        val outline = (0 until 40).map { OutlineItem(if (it % 2 == 0) " " else "Chapter 1, Solution $it ", it, 0) }
        val starts = (0 until 150).associateWith { it * 4 }
        assertEquals(toc, TocBuilder.build(outline, headings, starts, block))
    }
}
