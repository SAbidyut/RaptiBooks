package com.raptibooks

import com.raptibooks.core.data.LibraryRepository
import com.raptibooks.core.database.NewBook
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.MarkKey
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.model.ReadingPosition
import com.raptibooks.core.model.SeparatorBlock
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.TocEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {

    @Test
    fun storesBooksBlocksTocAndBookmarks() {
        val books = TestSupport.app.container.bookDao
        val content = TestSupport.app.container.contentDao
        val id = books.insert(NewBook("Title", "Author", "a.pdf", "/a.pdf", null, 10, 1000))
        val blocks = listOf(
            HeadingBlock(0, 0, 1, "Chapter 1"),
            ParagraphBlock(1, 0, "Hello reflowed world", listOf(InlineStyle(6, 14, InlineStyle.BOLD or InlineStyle.ITALIC))),
            ListItemBlock(2, 1, "1.", "Item", emptyList(), 1),
            ImageBlock(3, 1, "/p.png", 100, 50, "Figure 1", ImageKind.DIAGRAM, sourceWidth = 352.5f, textSize = 7.5f),
            TableBlock(4, 2, listOf(listOf("a", "b"), listOf("c", "d")), 1, "/t.png", 10, 10, "Table 1"),
            SeparatorBlock(5, 2),
            ParagraphBlock(6, 3, "বাংলা ভাষায় লেখা বই"),
        )
        content.insertBlocks(id, blocks)
        assertEquals(blocks, content.blocks(id, 0, 100))
        assertEquals(blocks.subList(2, 4), content.blocks(id, 2, 4))
        assertEquals(7, content.blockCount(id))

        val hits = content.search(id, "REFLOWED")
        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals("reflowed", hit.snippet.substring(hit.matchStart, hit.matchEnd))
        assertEquals(1, content.search(id, "লেখা").size)
        assertEquals(1, content.search(id, "100%").size + content.search(id, "Table 1").size)

        content.saveToc(id, listOf(TocEntry("Chapter 1", 1, 0, 0)))
        assertEquals(listOf(TocEntry("Chapter 1", 1, 0, 0)), content.toc(id))
        assertEquals(4, content.firstBlockAtOrAfterPage(id, 2))
        assertEquals(1, content.pageOfBlock(id, 3))

        books.finishConversion(id, 7, "en", false)
        books.updatePosition(id, ReadingPosition(4, 12), 0.66f)
        val book = books.get(id)!!
        assertTrue(book.isReadable)
        assertEquals(ReadingPosition(4, 12), book.position)

        content.addBookmark(id, 4, 2, "a | b")
        runBlocking { assertEquals(1, content.observeBookmarks(id).first().size) }

        // Books from an older converter are found for upgrading; bookmarks follow their page.
        assertEquals(listOf(id), books.outdated(2))
        books.finishConversion(id, 7, "en", false, layoutVersion = 2)
        assertEquals(emptyList<Long>(), books.outdated(2))
        books.prepareReconversion(id, OcrMode.AUTO, restorePage = 1)
        assertEquals(1, books.restorePage(id))
        books.restorePosition(id, content.firstBlockAtOrAfterPage(id, 1)!!)
        assertEquals(-1, books.restorePage(id))
        content.remapBookmarks(id)
        runBlocking { assertEquals(4, content.observeBookmarks(id).first().single().blockIndex) }

        books.delete(id)
        assertNull(books.get(id))
        assertEquals(0, content.blockCount(id)) // cascades
    }

    @Test
    fun cleansMetadataTitles() {
        assertNull(LibraryRepository.cleanTitle("about:blank"))
        assertNull(LibraryRepository.cleanTitle("Untitled"))
        assertEquals("Thesis", LibraryRepository.cleanTitle("Microsoft Word - Thesis.docx"))
        assertEquals("বাংলা বই", LibraryRepository.cleanTitle("বাংলা বই"))
    }

    @Test
    fun storesUnderlinedPassages() {
        val books = TestSupport.app.container.bookDao
        val content = TestSupport.app.container.contentDao
        val id = books.insert(NewBook("Marks", null, "m.pdf", "/m.pdf", null, 1, 10))
        content.markRanges(id, mapOf(MarkKey.of(3) to listOf(0..4, 10..12)), MarkColor.RED)
        content.markRanges(id, mapOf(MarkKey.of(3) to listOf(2..3), MarkKey.of(5, MarkKey.cell(1, 2)) to listOf(1..1)), MarkColor.BLUE)
        content.markRanges(id, mapOf(MarkKey.of(3) to listOf(10..12)), null)
        val marks = runBlocking { content.observeHighlights(id).first() }
        assertEquals(
            listOf(Triple(3, 0, 2) to MarkColor.RED, Triple(3, 2, 4) to MarkColor.BLUE, Triple(3, 4, 5) to MarkColor.RED, Triple(5, 1, 2) to MarkColor.BLUE),
            marks.map { Triple(it.blockIndex, it.start, it.end) to it.color },
        )
        assertEquals(MarkKey.cell(1, 2), marks.last().part)

        content.addInk(id, 4, MarkColor.GREEN, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        content.addInk(id, 4, MarkColor.RED, floatArrayOf(0.5f, 0.5f))
        val ink = runBlocking { content.observeInk(id).first() }
        assertEquals(2, ink.size)
        assertEquals(MarkColor.GREEN, ink[0].color)
        assertEquals(0.3f, ink[0].points[2], 0.0001f)
        content.deleteInk(listOf(ink[0].id))
        assertEquals(1, runBlocking { content.observeInk(id).first() }.size)

        books.delete(id)
        assertTrue(runBlocking { content.observeHighlights(id).first() }.isEmpty())
        assertTrue(runBlocking { content.observeInk(id).first() }.isEmpty())
    }
}
