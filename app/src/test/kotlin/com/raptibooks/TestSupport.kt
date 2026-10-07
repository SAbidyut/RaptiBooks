package com.raptibooks

import android.os.Looper
import com.raptibooks.core.data.conversion.ConversionPipeline
import com.raptibooks.core.database.NewBook
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.SeparatorBlock
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.TocEntry
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.util.Base64

object TestSupport {
    val app: RaptiBooksApp get() = RuntimeEnvironment.getApplication() as RaptiBooksApp

    /** Runs pending main-thread work and lets background coroutines finish. */
    fun pump(rounds: Int = 40) {
        repeat(rounds) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
    }

    fun pumpUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) pump(5)
    }

    fun resourcePdf(name: String, dest: File): File {
        dest.parentFile?.mkdirs()
        TestSupport::class.java.getResourceAsStream("/pdf/$name")!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAADAAAAAYCAIAAAAzn+mLAAAAcElEQVR4nGP88OEZw2ACTAPtAHQw6iBCgAWZo5dQR6r+SwuaqOcYBgY0B5FqARkeIAgGXZSNOogQGHUQITDoHISe7UkFlOR8rEUMpQ6iesE46KJs1EGEwKiDCIFB5yD0bE+LFgVJgHG0kU8ADDoHAQB+/RHFUriBNwAAAABJRU5ErkJggg=="

    private fun reindex(b: ContentBlock, i: Int): ContentBlock = when (b) {
        is HeadingBlock -> b.copy(index = i)
        is ParagraphBlock -> b.copy(index = i)
        is ListItemBlock -> b.copy(index = i)
        is ImageBlock -> b.copy(index = i)
        is TableBlock -> b.copy(index = i)
        is SeparatorBlock -> b.copy(index = i)
    }

    /** Seeds a converted book with every block type, plus a converting and a failed book. */
    fun seedLibrary(): Long {
        val books = app.container.bookDao
        val content = app.container.contentDao
        val image = File(app.filesDir, "fixture.png").apply { writeBytes(Base64.getDecoder().decode(PNG)) }
        val id = books.insert(NewBook("নমুনা বই – Sample Book", "Rapti", "sample.pdf", "/missing.pdf", null, 3, 2048))
        val blocks = listOf(
            HeadingBlock(0, 0, 1, "অধ্যায় ১: ভূমিকা"),
            ParagraphBlock(0, 0, "অধ্যায় ১: ভূমিকা\t5", listOf(InlineStyle(0, 16, InlineStyle.BOLD)), ParagraphKind.TOC),
            ParagraphBlock(0, 0, "1.1 Lists and tables\t9", kind = ParagraphKind.TOC),
            ImageBlock(0, 0, image.absolutePath, 48, 24, "চিত্র ১: wide flow chart", ImageKind.DIAGRAM, sourceWidth = 470f, textSize = 6f),
            ListItemBlock(0, 0, "১.", "কোনটি পরিবাহী?"),
            ParagraphBlock(0, 0, "(ক)\tতামা\n(খ)\tরাবার\n(গ)\tসিলিকন\n(ঘ)\tকাচ", kind = ParagraphKind.OPTIONS),
            ParagraphBlock(1, 0, "বাংলা ভাষায় লেখা বই মোবাইলে পড়ার সময় অনেক সমস্যা হয়। Mixed English text with bold words.", listOf(InlineStyle(66, 70, InlineStyle.BOLD))),
            ParagraphBlock(2, 0, "আমার সোনার বাংলা,\nআমি তোমায় ভালোবাসি।", kind = ParagraphKind.CENTERED),
            HeadingBlock(3, 1, 2, "1.1 Lists and tables"),
            ListItemBlock(4, 1, "•", "First point"),
            ListItemBlock(5, 1, "২.", "দ্বিতীয় বিষয়"),
            TableBlock(6, 1, listOf(listOf("বিভাগ", "জেলা"), listOf("ঢাকা", "১৩")), 1, image.absolutePath, 48, 24, "Table 1"),
            ImageBlock(7, 2, image.absolutePath, 48, 24, "Figure 1: Pipeline", ImageKind.DIAGRAM),
            ParagraphBlock(8, 2, "A quotation that stands apart from the text.", kind = ParagraphKind.QUOTE),
            ParagraphBlock(9, 2, "A footnote in small print.", kind = ParagraphKind.NOTE),
            SeparatorBlock(10, 2),
            ParagraphBlock(11, 2, "Last paragraph E = mc2", listOf(InlineStyle(21, 22, InlineStyle.SUPERSCRIPT))),
        )
            .mapIndexed { i, b -> reindex(b, i) }
        content.insertBlocks(id, blocks)
        content.saveToc(id, listOf(TocEntry("অধ্যায় ১: ভূমিকা", 1, 0, 0), TocEntry("1.1 Lists and tables", 2, 8, 1)))
        books.finishConversion(id, blocks.size, "bn+en", false, ConversionPipeline.LAYOUT_VERSION)
        val converting = books.insert(NewBook("Converting book", null, "c.pdf", "", null, 10, 1))
        books.updateProgress(converting, 0.4f, "Analyzing page 4 of 10")
        val failed = books.insert(NewBook("Locked book", null, "l.pdf", "", null, 0, 1))
        books.updateStatus(failed, BookStatus.FAILED, "This PDF is protected with a password.")
        return id
    }
}
