package com.raptibooks

import com.raptibooks.core.database.ContentDao
import com.raptibooks.core.database.NewBook
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.TableBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real conversion pipeline on real PDFs (OCR and rasterising are not available here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversionTest {

    private fun convert(pdf: String): Pair<Long, ContentDao> {
        val app = TestSupport.app
        val books = app.container.bookDao
        val content = app.container.contentDao
        val files = app.container.files
        val id = books.insert(NewBook(pdf, null, pdf, "", null, 0, 0))
        TestSupport.resourcePdf(pdf, files.source(id))
        val progress = mutableListOf<Float>()
        runBlocking { app.container.pipeline.convert(id) { progress += it.fraction } }
        val book = books.get(id)!!
        assertEquals(book.errorMessage, BookStatus.READY, book.status)
        assertTrue("progress must increase: $progress", progress.zipWithNext().all { (a, b) -> b >= a } && progress.isNotEmpty())
        assertFalse("page cache must be removed", files.pageCache(id).listFiles()?.isNotEmpty() == true)
        return id to content
    }

    @Test
    fun convertsEnglishBook() {
        val (id, content) = convert("english_book.pdf")
        val blocks = content.blocks(id, 0, 10_000)
        val text = blocks.joinToString("\n") { it.plainText }
        assertFalse("running header removed", text.contains("The Art of Reading\n"))
        assertTrue(blocks.filterIsInstance<HeadingBlock>().any { it.text == "Chapter 1: Why Reflow Matters" && it.level == 1 })
        assertTrue(blocks.filterIsInstance<ListItemBlock>().size >= 6)
        val table = blocks.filterIsInstance<TableBlock>().single()
        assertEquals(listOf("Device", "Screen", "Words per line"), table.rows.first())
        val toc = content.toc(id).map { it.title }
        assertTrue(toc.toString(), toc.containsAll(listOf("Chapter 1: Why Reflow Matters", "Chapter 2: Building the Reader")))
    }

    @Test
    fun convertsBanglaBook() {
        val (id, content) = convert("bangla_book.pdf")
        val paragraphs = content.blocks(id, 0, 10_000).filterIsInstance<ParagraphBlock>().map { it.text }
        val all = paragraphs.joinToString(" ")
        for (word in listOf("বাংলা", "শক্তি", "কর্ম", "ধর্মে", "স্বাধীনতা", "কৌশল", "ইংরেজি")) {
            assertTrue("missing '$word' in: $all", all.contains(word))
        }
        assertTrue(paragraphs.any { it.contains("আমার সোনার বাংলা,\nআমি তোমায় ভালোবাসি।") })
        assertTrue(content.search(id, "যুক্তাক্ষর").isNotEmpty())
    }

    /** Contents page, flow/radial diagrams with labels, a table followed by rules, a callout and MCQs. */
    @Test
    fun convertsTextbookLayouts() {
        val (id, content) = convert("electrical_book.pdf")
        val blocks = content.blocks(id, 0, 10_000)
        val dump = blocks.joinToString("\n") { it.javaClass.simpleName + ": " + it.plainText }

        val contents = blocks.filterIsInstance<ParagraphBlock>().filter { it.kind == ParagraphKind.TOC }
        assertEquals(dump, 9, contents.size)
        assertEquals("অধ্যায় ০২ ইলেকট্রিক্যাল টুলস\t13", contents[1].text)
        assertTrue(dump, blocks.filterIsInstance<HeadingBlock>().none { it.text.contains("....") })

        // Diagram labels stay inside the figures instead of leaking into the text.
        for (label in listOf("চারপাশ ঘিরে দেওয়া", "পুনঃসংযোগ প্রতিরোধ", "সম্পূর্ণ বিচ্ছিন্ন করা", "সাপ্লাই বন্ধ")) {
            assertTrue("leaked '$label':\n$dump", blocks.none { it !is ImageBlock && it.plainText.contains(label) })
        }
        val captions = blocks.mapNotNull { (it as? ImageBlock)?.caption ?: (it as? ParagraphBlock)?.takeIf { p -> p.kind == ParagraphKind.CAPTION }?.text }
        assertTrue(dump, captions.any { it.startsWith("চিত্র ১.১০") } && captions.any { it.startsWith("চিত্র ১.১১") })
        blocks.filterIsInstance<ImageBlock>().forEach { assertTrue(it.textSize > 0f && it.sourceWidth > 200f) }

        // Only the real table; headings, the summary box and the MCQs stay text.
        val table = blocks.filterIsInstance<TableBlock>().single()
        assertEquals(listOf("সরঞ্জাম", "মেঝে থেকে উচ্চতা", "সূত্র"), table.rows.first())
        assertEquals(3, table.rows.size)
        assertTrue(dump, blocks.filterIsInstance<HeadingBlock>().any { it.text == "অনুশীলনী — বহুনির্বাচনী প্রশ্ন" })
        val options = blocks.filterIsInstance<ListItemBlock>().filter { it.level == 1 && it.marker.startsWith("(") }.map { it.marker + " " + it.text }
        assertEquals(dump, listOf("(ক) তিন", "(খ) চার", "(গ) পাঁচ", "(ঘ) দুই"), options.take(4))
        assertTrue(dump, blocks.filterIsInstance<ListItemBlock>().any { it.text.startsWith("কনসিল্ড পাইপে") })
    }

    @Test
    fun convertsTwoColumnArticle() {
        val (id, content) = convert("two_column.pdf")
        val paragraphs = content.blocks(id, 0, 10_000).filterIsInstance<ParagraphBlock>().map { it.text }
        assertTrue(paragraphs.none { it == "printer." })
        assertTrue(content.blocks(id, 0, 100).filterIsInstance<HeadingBlock>().any { it.text == "Method" })
    }
}
