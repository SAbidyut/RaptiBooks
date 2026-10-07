package com.raptibooks.core.database

import android.content.ContentValues
import android.database.Cursor
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.model.ReadingPosition
import kotlinx.coroutines.flow.Flow

data class NewBook(
    val title: String,
    val author: String?,
    val fileName: String,
    val pdfPath: String,
    val coverPath: String?,
    val pageCount: Int,
    val fileSize: Long,
    val status: BookStatus = BookStatus.QUEUED,
    val errorMessage: String? = null,
    val ocrMode: OcrMode = OcrMode.AUTO,
)

class BookDao(private val db: RaptiDatabase) {

    fun observeAll(): Flow<List<Book>> = db.observe(RaptiDatabase.BOOKS) { queryBooks(null) }

    fun observe(id: Long): Flow<Book?> = db.observe(RaptiDatabase.BOOKS) { get(id) }

    fun get(id: Long): Book? = queryBooks(id).firstOrNull()

    fun all(): List<Book> = queryBooks(null)

    private fun queryBooks(id: Long?): List<Book> {
        val sql = "SELECT * FROM books" + (if (id != null) " WHERE id = ?" else "") +
            " ORDER BY COALESCE(last_opened_at, added_at) DESC"
        val args = if (id != null) arrayOf(id.toString()) else null
        return db.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Book>(c.count)
            while (c.moveToNext()) out += c.toBook()
            out
        }
    }

    fun insert(book: NewBook): Long {
        val values = ContentValues().apply {
            put("title", book.title)
            put("author", book.author)
            put("file_name", book.fileName)
            put("pdf_path", book.pdfPath)
            put("cover_path", book.coverPath)
            put("page_count", book.pageCount)
            put("file_size", book.fileSize)
            put("status", book.status.name)
            put("error_message", book.errorMessage)
            put("added_at", System.currentTimeMillis())
            put("ocr_mode", book.ocrMode.name)
        }
        val id = db.writableDatabase.insertOrThrow("books", null, values)
        db.notifyChanged(RaptiDatabase.BOOKS)
        return id
    }

    fun updateFiles(id: Long, pdfPath: String, coverPath: String?) = update(id) {
        put("pdf_path", pdfPath)
        put("cover_path", coverPath)
    }

    fun updateMetadata(id: Long, title: String?, author: String?, pageCount: Int?) = update(id) {
        if (title != null) put("title", title)
        if (author != null) put("author", author)
        if (pageCount != null) put("page_count", pageCount)
    }

    fun updateStatus(id: Long, status: BookStatus, error: String? = null) = update(id) {
        put("status", status.name)
        put("error_message", error)
        if (status == BookStatus.QUEUED) {
            put("progress", 0f)
            putNull("progress_message")
        }
    }

    fun updateProgress(id: Long, progress: Float, message: String?) = update(id) {
        put("status", BookStatus.PROCESSING.name)
        put("progress", progress)
        put("progress_message", message)
    }

    fun finishConversion(id: Long, blockCount: Int, language: String?, usedOcr: Boolean, layoutVersion: Int = 0) = update(id) {
        put("layout_version", layoutVersion)
        put("status", BookStatus.READY.name)
        put("progress", 1f)
        putNull("progress_message")
        putNull("error_message")
        put("block_count", blockCount)
        put("language", language)
        put("used_ocr", if (usedOcr) 1 else 0)
    }

    /** Resets a book for converting again; [restorePage] is where reading continues afterwards (-1: start). */
    fun prepareReconversion(id: Long, ocrMode: OcrMode, restorePage: Int = -1) = update(id) {
        put("restore_page", restorePage)
        put("status", BookStatus.QUEUED.name)
        put("ocr_mode", ocrMode.name)
        put("progress", 0f)
        putNull("progress_message")
        putNull("error_message")
        put("block_count", 0)
        put("reading_block", 0)
        put("reading_offset", 0)
        put("reading_progress", 0f)
    }

    fun updatePosition(id: Long, position: ReadingPosition, progress: Float) = update(id, notify = false) {
        put("reading_block", position.blockIndex)
        put("reading_offset", position.offset)
        put("reading_progress", progress.coerceIn(0f, 1f))
    }

    fun markOpened(id: Long) = update(id) { put("last_opened_at", System.currentTimeMillis()) }

    fun delete(id: Long) {
        db.writableDatabase.delete("books", "id = ?", arrayOf(id.toString()))
        db.notifyChanged(RaptiDatabase.BOOKS, RaptiDatabase.BLOCKS, RaptiDatabase.TOC, RaptiDatabase.BOOKMARKS)
    }

    /** Ready books converted by an older converter than [layoutVersion]. */
    fun outdated(layoutVersion: Int): List<Long> =
        db.readableDatabase.rawQuery(
            "SELECT id FROM books WHERE status = ? AND layout_version < ?",
            arrayOf(BookStatus.READY.name, layoutVersion.toString()),
        ).use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } }

    /** Page to continue reading at after a re-conversion, or -1. */
    fun restorePage(id: Long): Int =
        db.readableDatabase.rawQuery("SELECT restore_page FROM books WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }

    fun restorePosition(id: Long, blockIndex: Int) = update(id) {
        put("reading_block", blockIndex)
        put("reading_offset", 0)
        put("restore_page", -1)
    }

    /** Books left in a working state by a killed process. */
    fun unfinished(): List<Book> = all().filter { it.isConverting }

    private inline fun update(id: Long, notify: Boolean = true, fill: ContentValues.() -> Unit) {
        val values = ContentValues().apply(fill)
        if (values.size() == 0) return
        db.writableDatabase.update("books", values, "id = ?", arrayOf(id.toString()))
        if (notify) db.notifyChanged(RaptiDatabase.BOOKS)
    }

    /** Notifies observers after position updates that skipped notification. */
    fun touch() = db.notifyChanged(RaptiDatabase.BOOKS)

    private fun Cursor.toBook(): Book {
        fun str(name: String) = getString(getColumnIndexOrThrow(name))
        fun strOrNull(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun int(name: String) = getInt(getColumnIndexOrThrow(name))
        fun long(name: String) = getLong(getColumnIndexOrThrow(name))
        fun float(name: String) = getFloat(getColumnIndexOrThrow(name))
        return Book(
            id = long("id"),
            title = str("title"),
            author = strOrNull("author"),
            fileName = str("file_name"),
            pdfPath = str("pdf_path"),
            coverPath = strOrNull("cover_path"),
            pageCount = int("page_count"),
            fileSize = long("file_size"),
            status = runCatching { BookStatus.valueOf(str("status")) }.getOrDefault(BookStatus.FAILED),
            progress = float("progress"),
            progressMessage = strOrNull("progress_message"),
            errorMessage = strOrNull("error_message"),
            addedAt = long("added_at"),
            lastOpenedAt = getColumnIndexOrThrow("last_opened_at").let { if (isNull(it)) null else getLong(it) },
            position = ReadingPosition(int("reading_block"), int("reading_offset")),
            readingProgress = float("reading_progress"),
            blockCount = int("block_count"),
            language = strOrNull("language"),
            usedOcr = int("used_ocr") != 0,
            ocrMode = runCatching { OcrMode.valueOf(str("ocr_mode")) }.getOrDefault(OcrMode.AUTO),
        )
    }
}
