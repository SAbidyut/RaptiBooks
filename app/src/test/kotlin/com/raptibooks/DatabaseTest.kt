package com.raptibooks

import com.raptibooks.core.data.LibraryRepository
import com.raptibooks.core.database.NewBook
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
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
}
