package com.raptibooks.core.data

import android.content.Context
import android.graphics.Bitmap
import com.raptibooks.core.database.BookDao
import com.raptibooks.core.database.ContentDao
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.Bookmark
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.Highlight
import com.raptibooks.core.model.InkStroke
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.Note
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.model.ReadingPosition
import com.raptibooks.core.model.SearchHit
import com.raptibooks.core.model.BoxSpan
import com.raptibooks.core.model.TocEntry
import com.raptibooks.core.pdf.PageRenderer
import com.raptibooks.core.pdf.PdfExtractor
import com.raptibooks.core.pdf.PdfSupport
import com.raptibooks.core.structure.analysis.PageAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Read access to a converted book. Everything comes from the local database. */
class ReaderRepository(
    context: Context,
    private val books: BookDao,
    private val content: ContentDao,
    private val files: BookFiles,
) {
    private val appContext = context.applicationContext
    private val pictureText = PictureText(context)

    fun observeBook(id: Long): Flow<Book?> = books.observe(id)

    suspend fun book(id: Long): Book? = withContext(Dispatchers.IO) { books.get(id) }

    suspend fun toc(id: Long): List<TocEntry> = withContext(Dispatchers.IO) { content.toc(id) }

    suspend fun boxes(id: Long): List<BoxSpan> = withContext(Dispatchers.IO) { content.boxes(id) }

    suspend fun blocks(id: Long, from: Int, to: Int): List<ContentBlock> =
        withContext(Dispatchers.IO) { content.blocks(id, from, to) }

    suspend fun search(id: Long, query: String): List<SearchHit> = withContext(Dispatchers.IO) { content.search(id, query) }

    fun bookmarks(id: Long): Flow<List<Bookmark>> = content.observeBookmarks(id)

    suspend fun addBookmark(id: Long, blockIndex: Int, page: Int, snippet: String) =
        withContext(Dispatchers.IO) { content.addBookmark(id, blockIndex, page, snippet) }

    suspend fun removeBookmark(bookmarkId: Long) = withContext(Dispatchers.IO) { content.deleteBookmark(bookmarkId) }

    fun highlights(id: Long): Flow<List<Highlight>> = content.observeHighlights(id)

    /** Underlines [edits] (character ranges per text, see MarkKey) in [color]; a null colour erases them. */
    suspend fun mark(id: Long, edits: Map<Long, List<IntRange>>, color: MarkColor?) =
        withContext(Dispatchers.IO) { content.markRanges(id, edits, color) }

    fun ink(id: Long): Flow<List<InkStroke>> = content.observeInk(id)

    suspend fun addInk(id: Long, blockIndex: Int, color: MarkColor, points: FloatArray) =
        withContext(Dispatchers.IO) { content.addInk(id, blockIndex, color, points) }

    suspend fun eraseInk(ids: List<Long>) = withContext(Dispatchers.IO) { content.deleteInk(ids) }

    fun notes(id: Long): Flow<List<Note>> = content.observeNotes(id)

    /** Where the next voice note of the book is recorded to. */
    fun newVoiceFile(id: Long): File = File(files.notes(id), "voice-${System.currentTimeMillis()}.m4a")

    suspend fun addNote(
        id: Long, blockIndex: Int, page: Int, x: Float, y: Float,
        kind: NoteKind, text: String, audioPath: String?, durationMs: Long,
    ) = withContext(Dispatchers.IO) { content.addNote(id, blockIndex, page, x, y, kind, text, audioPath, durationMs) }

    suspend fun updateNoteText(noteId: Long, text: String) = withContext(Dispatchers.IO) { content.updateNoteText(noteId, text) }

    suspend fun deleteNote(note: Note) = withContext(Dispatchers.IO) {
        content.deleteNote(note.id)
        note.audioPath?.let { File(it).delete() }
    }

    suspend fun savePosition(id: Long, position: ReadingPosition, progress: Float) =
        withContext(Dispatchers.IO) { books.updatePosition(id, position, progress) }

    /** Lets the library refresh progress bars after reading. */
    suspend fun publishPosition() = withContext(Dispatchers.IO) { books.touch() }

    suspend fun markOpened(id: Long) = withContext(Dispatchers.IO) { books.markOpened(id) }

    /** The blocks printed on pages [from] to [to]. */
    suspend fun blocksOfPages(id: Long, from: Int, to: Int): List<ContentBlock> = withContext(Dispatchers.IO) {
        val first = content.firstBlockAtOrAfterPage(id, from) ?: return@withContext emptyList()
        val after = content.firstBlockAtOrAfterPage(id, to + 1) ?: (first + 400)
        content.blocks(id, first, maxOf(first + 1, after)).filter { it.pageIndex in from..to }
    }

    suspend fun firstBlockOfPage(id: Long, page: Int): Int? = withContext(Dispatchers.IO) { content.firstBlockAtOrAfterPage(id, page) }

    suspend fun pageOfBlock(id: Long, index: Int): Int? = withContext(Dispatchers.IO) { content.pageOfBlock(id, index) }

    /** The text printed in a picture of the book, read with the OCR (null: none, or no OCR). */
    suspend fun pictureText(path: String): String? = pictureText.read(path)

    /** Opens the original PDF for the page view. Close it when done. */
    suspend fun openOriginal(id: Long): OriginalPdf = withContext(Dispatchers.IO) {
        OriginalPdf(PageRenderer.open(files.source(id), files.scratch(id))) { originalLines(id, it) }
    }

    // (one book's original text at a time, read in order on one thread)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val background = CoroutineScope(SupervisorJob() + serial)
    private val originalText = OriginalText { id ->
        PdfSupport.init(appContext)
        PdfExtractor.open(files.source(id), files.scratch(id))
    }

    /**
     * Keeps the text of book [id]'s original pages open while a screen of the book is shown, so
     * a passage is found on them at once; opened now in the background, with [page] read ahead.
     * Let go with [releaseOriginalText].
     */
    fun holdOriginalText(id: Long, page: Int?) {
        background.launch {
            originalText.acquire(id)
            if (page != null) originalText.lines(id, page)
        }
    }

    fun releaseOriginalText(id: Long) {
        background.launch { originalText.release(id) }
    }

    /** Reads the lines of an original page ahead, to be ready when the page is shown. */
    fun prefetchOriginalLines(id: Long, page: Int) {
        background.launch { originalText.lines(id, page) }
    }

    /** The printed lines of page [page] of book [id]'s original PDF. */
    suspend fun originalLines(id: Long, page: Int): List<PageLine> =
        originalText.cached(id, page) ?: withContext(serial) { originalText.lines(id, page) }

    /** The bookmarks of the original PDF itself (its outline), by page. */
    suspend fun originalOutline(id: Long): List<TocEntry> = withContext(serial) { originalText.outline(id) }
}

/**
 * The text of the original pages of one book, kept open while screens of it use it (counted),
 * with the lines of the pages read so far.
 */
private class OriginalText(private val open: (Long) -> PdfExtractor) {
    private var bookId = -1L
    private var users = 0
    private var extractor: PdfExtractor? = null
    private val lines = object : LinkedHashMap<Int, List<PageLine>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<PageLine>>?) = size > 48
    }

    private fun switchTo(id: Long) {
        if (bookId == id) return
        extractor?.close()
        extractor = null
        synchronized(lines) { lines.clear() }
        bookId = id
        users = 0
    }

    fun acquire(id: Long) {
        switchTo(id)
        users++
    }

    fun release(id: Long) {
        if (bookId != id) return
        users--
        if (users <= 0) {
            users = 0
            extractor?.close()
            extractor = null
        }
    }

    fun cached(id: Long, page: Int): List<PageLine>? = if (bookId == id) synchronized(lines) { lines[page] } else null

    private fun extractor(id: Long): PdfExtractor {
        switchTo(id)
        return extractor ?: open(id).also { extractor = it }
    }

    fun lines(id: Long, page: Int): List<PageLine> {
        cached(id, page)?.let { return it }
        val read = try {
            val raw = extractor(id).extractText(page)
            val w = raw.width.coerceAtLeast(1f)
            val h = raw.height.coerceAtLeast(1f)
            PageAnalyzer().analyze(raw).lines.map { l ->
                PageLine(l.text, l.bounds.left / w, l.bounds.top / h, l.bounds.right / w, l.bounds.bottom / h)
            }
        } catch (e: Exception) {
            emptyList()
        } catch (e: OutOfMemoryError) {
            emptyList()
        }
        synchronized(lines) { lines[page] = read }
        // (nobody holds the book: not kept open)
        if (users == 0) {
            extractor?.close()
            extractor = null
        }
        return read
    }

    fun outline(id: Long): List<TocEntry> {
        val out = try {
            extractor(id).outline().map { TocEntry(it.title, it.level + 1, -1, it.pageIndex) }
        } catch (e: Exception) {
            emptyList()
        }
        if (users == 0) {
            extractor?.close()
            extractor = null
        }
        return out
    }
}

/** A printed line of an original page, where it is as fractions of the page's width and height. */
class PageLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Renders pages of the original PDF ("original view"). */
class OriginalPdf internal constructor(
    private val renderer: PageRenderer,
    private val readLines: suspend (Int) -> List<PageLine>,
) : Closeable {
    /** The printed lines of page [index] (its text read as the conversion reads it), for finding a passage. */
    suspend fun lines(index: Int): List<PageLine> = readLines(index)

    val pageCount: Int get() = renderer.pageCount
    private val sizes = java.util.concurrent.ConcurrentHashMap<Int, Pair<Float, Float>>()

    @Synchronized
    fun pageSize(index: Int): Pair<Float, Float> = sizes.getOrPut(index) {
        runCatching { renderer.pageSize(index) }.getOrDefault(595f to 842f)
    }

    /** The size of page [index] if already known: never waits for a page being rendered. */
    fun knownSize(index: Int): Pair<Float, Float>? = sizes[index]

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            renderer.renderPage(index, widthPx)
        } catch (e: OutOfMemoryError) {
            System.gc()
            runCatching { renderer.renderPage(index, widthPx / 2) }.getOrNull()
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    override fun close() {
        renderer.close()
    }
}
