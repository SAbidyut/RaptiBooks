package com.raptibooks

import com.raptibooks.core.database.ContentDao
import com.raptibooks.core.data.conversion.ConversionPipeline
import com.raptibooks.core.database.NewBook
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.TableBlock
import kotlinx.coroutines.flow.first
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

        // Converting again (e.g. after an app update) continues on the same page, bookmarks too.
        val app = TestSupport.app
        val pageThree = content.firstBlockAtOrAfterPage(id, 3)!!
        content.addBookmark(id, pageThree + 1, 3, "bookmark")
        app.container.bookDao.prepareReconversion(id, OcrMode.AUTO, restorePage = 3)
        runBlocking { app.container.pipeline.convert(id) { } }
        val again = app.container.bookDao.get(id)!!
        assertEquals(BookStatus.READY, again.status)
        assertEquals(content.firstBlockAtOrAfterPage(id, 3), again.position.blockIndex)
        assertEquals(content.firstBlockAtOrAfterPage(id, 3), runBlocking { content.observeBookmarks(id).first().single().blockIndex })
        assertTrue(app.container.bookDao.outdated(ConversionPipeline.LAYOUT_VERSION).isEmpty())
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

        // Only the real tables; headings, the summary box and the MCQs stay text.
        val tables = blocks.filterIsInstance<TableBlock>()
        assertEquals(dump, 2, tables.size)
        assertEquals(listOf("সরঞ্জাম", "মেঝে থেকে উচ্চতা", "সূত্র"), tables[0].rows.first())
        assertEquals(3, tables[0].rows.size)
        // A table running over three pages is one table, without the repeated header rows.
        assertEquals(listOf("ক্রম", "টুলের নাম", "সংখ্যা"), tables[1].rows.first())
        assertEquals(49, tables[1].rows.size)
        assertEquals(listOf("48", "টুল নম্বর 48", "১"), tables[1].rows.last())

        // The running footer ("publisher  page  phone") never reaches the text or a table.
        assertTrue(dump, blocks.none { it.plainText.contains("Resonance") || it.plainText.contains("01617") })
        // A callout box with an icon stays text; the note box under a diagram stays in the picture.
        assertTrue(dump, blocks.any { it is ParagraphBlock && it.text.startsWith("যেসব টুল হাতের শক্তির") })
        assertTrue(dump, blocks.none { it !is ImageBlock && it.plainText.contains("ব্লেডের দৈর্ঘ্য") })
        assertTrue(dump, blocks.any { it is ImageBlock && it.caption?.startsWith("চিত্র ১৫.১") == true })
        assertTrue(dump, blocks.filterIsInstance<HeadingBlock>().any { it.text == "অনুশীলনী — বহুনির্বাচনী প্রশ্ন" })
        // Answer options printed in two columns are read row by row and kept together.
        val options = blocks.filterIsInstance<ParagraphBlock>().filter { it.kind == ParagraphKind.OPTIONS }
        assertEquals(dump, 6, options.size)
        assertEquals("(ক)\tতিন\n(খ)\tচার\n(গ)\tপাঁচ\n(ঘ)\tদুই", options[0].text)
        assertEquals("(ক)\tহার্জ\n(খ)\tফ্যারাড\n(গ)\tওহম\n(ঘ)\tহেনরি", options[2].text)
        assertTrue(dump, blocks.filterIsInstance<ListItemBlock>().any { it.text.startsWith("কনসিল্ড পাইপে") })
    }

    /**
     * Textbook with display equations (fractions, integrals, symbols without Unicode mapping),
     * a margin column, a small labelled figure, an SI table and a contents page without leaders.
     */
    @Test
    fun convertsMathTextbook() {
        val (id, content) = convert("math_textbook.pdf")
        val blocks = content.blocks(id, 0, 10_000)
        val dump = blocks.joinToString("\n") { it.javaClass.simpleName + ": " + it.plainText }

        val contents = blocks.filterIsInstance<ParagraphBlock>().filter { it.kind == ParagraphKind.TOC }
        assertEquals(dump, 11, contents.size)
        assertEquals("Chapter 1 Basic Concepts\t3", contents[0].text)
        assertTrue(contents[0].styles.single().bold)
        assertEquals("1.2 Systems of Units\t4", contents[2].text)

        // Formulas are never tables; their stacked parts don't leak into the text.
        val tables = blocks.filterIsInstance<TableBlock>()
        assertEquals(dump, 1, tables.size)
        assertEquals(listOf("10¹⁸", "exa", "E"), tables[0].rows[1])
        assertEquals(listOf("10⁻⁶", "micro", "μ"), tables[0].rows.last())
        assertTrue(dump, blocks.none { it is ParagraphBlock && it.text in setOf("dw", "dt", "i", "+", "v", "−", "(1.5)", "(a)") })
        // The margin note follows the paragraph it stands next to instead of splitting it.
        assertTrue(dump, blocks.any { it is ParagraphBlock && it.text.contains("we need to know how much power an electric device can handle. We all know") })
        assertTrue(dump, blocks.any { it is ParagraphBlock && it.text.startsWith("Passive sign convention is satisfied") })
        // The figure in the margin layout keeps its caption.
        assertTrue(dump, blocks.any { it.plainText.startsWith("Figure 1.8 Reference polarities") })
        // Greek delta printed with a "cent" code is repaired.
        assertTrue(dump, blocks.none { it.plainText.contains("¢") })
    }

    @Test
    fun convertsBanglaWorkedExample() {
        val (id, content) = convert("bangla_math.pdf")
        val blocks = content.blocks(id, 0, 10_000)
        val dump = blocks.joinToString("\n") { it.javaClass.simpleName + ": " + it.plainText }
        assertTrue(dump, blocks.none { it is TableBlock })
        assertTrue(dump, blocks.none { it is ParagraphBlock && it.text.trim() in setOf("1", "11", "60", "T") })
        assertTrue(dump, blocks.any { it is ParagraphBlock && it.text.startsWith("উত্তর : সিরিজে মোট রোধ 60 Ω") })
    }

    @Test
    fun convertsTwoColumnArticle() {
        val (id, content) = convert("two_column.pdf")
        val paragraphs = content.blocks(id, 0, 10_000).filterIsInstance<ParagraphBlock>().map { it.text }
        assertTrue(paragraphs.none { it == "printer." })
        assertTrue(content.blocks(id, 0, 100).filterIsInstance<HeadingBlock>().any { it.text == "Method" })
    }
}
