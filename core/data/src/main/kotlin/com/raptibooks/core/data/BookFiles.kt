package com.raptibooks.core.data

import android.content.Context
import java.io.File

/**
 * On-disk layout of a book:
 *
 *     files/books/<id>/source.pdf      private copy of the imported PDF (offline + original view)
 *     files/books/<id>/cover.jpg       library thumbnail
 *     files/books/<id>/figures/        extracted figures and table snapshots
 *     cache/books/<id>/pages/          per-page analysis cache (resumable conversion)
 *     cache/books/<id>/scratch/        PdfBox temporary buffers
 */
class BookFiles(context: Context) {
    private val filesRoot = File(context.filesDir, "books")
    private val cacheRoot = File(context.cacheDir, "books")
    private val importRoot = File(context.cacheDir, "import")
    val importDir: File get() = importRoot.apply { mkdirs() }

    fun bookDir(id: Long): File = File(filesRoot, id.toString()).apply { mkdirs() }
    fun source(id: Long): File = File(bookDir(id), "source.pdf")
    fun cover(id: Long): File = File(bookDir(id), "cover.jpg")
    fun figures(id: Long): File = File(bookDir(id), "figures").apply { mkdirs() }
    fun pageCache(id: Long): File = File(File(cacheRoot, id.toString()), "pages").apply { mkdirs() }
    fun scratch(id: Long): File = File(File(cacheRoot, id.toString()), "scratch").apply { mkdirs() }

    fun clearConversionCache(id: Long) {
        File(cacheRoot, id.toString()).deleteRecursively()
    }

    fun clearFigures(id: Long) {
        File(bookDir(id), "figures").deleteRecursively()
    }

    fun deleteBook(id: Long) {
        File(filesRoot, id.toString()).deleteRecursively()
        clearConversionCache(id)
    }
}
