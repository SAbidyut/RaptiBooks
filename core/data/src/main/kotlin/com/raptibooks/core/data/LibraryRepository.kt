package com.raptibooks.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.raptibooks.core.data.conversion.ConversionPipeline
import com.raptibooks.core.data.conversion.ConversionScheduler
import com.raptibooks.core.database.BookDao
import com.raptibooks.core.database.ContentDao
import com.raptibooks.core.database.NewBook
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.pdf.PageRenderer
import com.raptibooks.core.pdf.PdfExtractor
import com.raptibooks.core.pdf.PdfPasswordException
import com.raptibooks.core.pdf.PdfSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ImportException(message: String) : IOException(message)

/** The user's library: importing PDFs and managing conversions. */
class LibraryRepository(
    private val context: Context,
    private val books: BookDao,
    private val content: ContentDao,
    private val files: BookFiles,
    private val settings: SettingsRepository,
    private val scheduler: ConversionScheduler,
) {
    val library: Flow<List<Book>> = books.observeAll()

    fun observeBook(id: Long): Flow<Book?> = books.observe(id)

    /** Copies the PDF into private storage, creates the book and schedules conversion. */
    suspend fun importPdf(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: "Document.pdf"
        val tmp = File(files.importDir, "import-${System.nanoTime()}.pdf")
        try {
            val input = resolver.openInputStream(uri) ?: throw ImportException(context.getString(R.string.import_error_read))
            input.use { src -> FileOutputStream(tmp).use { src.copyTo(it, 1 shl 16) } }
        } catch (e: SecurityException) {
            tmp.delete()
            throw ImportException(context.getString(R.string.import_error_read))
        } catch (e: IOException) {
            tmp.delete()
            if (e is ImportException) throw e
            val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true
            throw ImportException(context.getString(if (noSpace) R.string.error_storage else R.string.import_error_read))
        }
        if (!looksLikePdf(tmp)) {
            tmp.delete()
            throw ImportException(context.getString(R.string.import_error_not_pdf))
        }

        PdfSupport.init(context)
        var title = displayName.substringBeforeLast('.').ifBlank { displayName }
        var author: String? = null
        var pageCount = 0
        var error: String? = null
        try {
            PdfExtractor.open(tmp, files.importDir).use { pdf ->
                val meta = pdf.metadata()
                pageCount = meta.pageCount
                cleanTitle(meta.title)?.let { title = it }
                author = meta.author
            }
        } catch (e: PdfPasswordException) {
            error = context.getString(R.string.error_password)
        } catch (e: Exception) {
            error = context.getString(R.string.error_damaged)
        } catch (e: OutOfMemoryError) {
            error = context.getString(R.string.error_memory)
        }

        val id = books.insert(
            NewBook(
                title = title,
                author = author,
                fileName = displayName,
                pdfPath = "",
                coverPath = null,
                pageCount = pageCount,
                fileSize = tmp.length(),
                status = if (error == null) BookStatus.QUEUED else BookStatus.FAILED,
                errorMessage = error,
                ocrMode = settings.defaultOcrMode.value,
            ),
        )
        val dest = files.source(id)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        val cover = if (error == null) renderCover(dest, files.cover(id)) else null
        books.updateFiles(id, dest.absolutePath, cover?.absolutePath)
        if (error == null) scheduler.enqueue(id)
        id
    }

    suspend fun delete(bookId: Long) = withContext(Dispatchers.IO) {
        scheduler.cancel(bookId)
        books.delete(bookId)
        files.deleteBook(bookId)
    }

    /**
     * Converts again from scratch, e.g. with OCR forced on. Reading continues on the same page
     * afterwards and bookmarks are kept (they follow their pages).
     */
    suspend fun reconvert(bookId: Long, ocrMode: OcrMode) = withContext(Dispatchers.IO) {
        val book = books.get(bookId) ?: return@withContext
        val page = if (book.isReadable && book.position.blockIndex > 0) content.pageOfBlock(bookId, book.position.blockIndex) ?: -1 else -1
        scheduler.cancel(bookId)
        content.deleteContent(bookId)
        files.clearConversionCache(bookId)
        files.clearFigures(bookId)
        books.prepareReconversion(bookId, ocrMode, page)
        scheduler.enqueue(bookId, replace = true)
    }

    /** Re-converts books made by an older version of the converter, so they get the improvements. */
    suspend fun upgradeOutdated() = withContext(Dispatchers.IO) {
        for (id in books.outdated(ConversionPipeline.LAYOUT_VERSION)) {
            val book = books.get(id) ?: continue
            reconvert(id, book.ocrMode)
        }
    }

    suspend fun cancel(bookId: Long) = withContext(Dispatchers.IO) {
        books.updateStatus(bookId, BookStatus.CANCELLED, context.getString(R.string.status_cancelled))
        scheduler.cancel(bookId)
    }

    /** Continues a cancelled or failed conversion, reusing already analysed pages. */
    suspend fun retry(bookId: Long) = withContext(Dispatchers.IO) {
        books.updateStatus(bookId, BookStatus.QUEUED)
        scheduler.enqueue(bookId, replace = true)
    }

    /** Re-enqueues conversions interrupted by the process being killed. */
    suspend fun resumeInterrupted() = withContext(Dispatchers.IO) {
        for (book in books.unfinished()) scheduler.enqueue(book.id)
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (e: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun looksLikePdf(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(1024)
            val n = input.read(head)
            n > 4 && String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF")
        }
    } catch (e: IOException) {
        false
    }

    private fun renderCover(pdf: File, out: File): File? = try {
        PageRenderer.open(pdf, files.importDir).use { renderer ->
            val bmp = renderer.renderPage(0, 360)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()
            out
        }
    } catch (e: Throwable) {
        null
    }

    companion object {
        /** Drops useless metadata titles ("about:blank", "Microsoft Word - x.docx", "Untitled"). */
        fun cleanTitle(raw: String?): String? {
            var t = raw?.trim() ?: return null
            t = t.removePrefix("Microsoft Word - ").removePrefix("Microsoft PowerPoint - ")
            t = t.replace(Regex("\\.(docx?|pdf|pptx?|odt|rtf|txt)$", RegexOption.IGNORE_CASE), "").trim()
            val lower = t.lowercase()
            if (t.length < 2 || lower == "about:blank" || lower.startsWith("untitled") || lower == "untitled document" ||
                lower.startsWith("http") || t.contains('\\') || t.count { it == '/' } > 1 || t.all { it.isDigit() || it == ' ' }
            ) {
                return null
            }
            return t
        }
    }
}
