package com.raptibooks.core.data

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Read access to a converted book. Everything comes from the local database. */
class ReaderRepository(
    private val books: BookDao,
    private val content: ContentDao,
    private val files: BookFiles,
) {
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

    suspend fun firstBlockOfPage(id: Long, page: Int): Int? = withContext(Dispatchers.IO) { content.firstBlockAtOrAfterPage(id, page) }

    suspend fun pageOfBlock(id: Long, index: Int): Int? = withContext(Dispatchers.IO) { content.pageOfBlock(id, index) }

    /** Opens the original PDF for the page view. Close it when done. */
    suspend fun openOriginal(id: Long): OriginalPdf = withContext(Dispatchers.IO) {
        OriginalPdf(PageRenderer.open(files.source(id), files.scratch(id)))
    }
}

/** Renders pages of the original PDF ("original view"). */
class OriginalPdf internal constructor(private val renderer: PageRenderer) : Closeable {
    val pageCount: Int get() = renderer.pageCount
    private val sizes = HashMap<Int, Pair<Float, Float>>()

    @Synchronized
    fun pageSize(index: Int): Pair<Float, Float> = sizes.getOrPut(index) {
        runCatching { renderer.pageSize(index) }.getOrDefault(595f to 842f)
    }

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

    override fun close() = renderer.close()
}
