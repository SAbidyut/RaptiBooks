package com.raptibooks.core.data.conversion

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import com.raptibooks.core.data.BookFiles
import com.raptibooks.core.database.BookDao
import com.raptibooks.core.database.ContentDao
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.ocr.OcrEngine
import com.raptibooks.core.ocr.OcrUnavailableException
import com.raptibooks.core.ocr.TesseractOcrEngine
import com.raptibooks.core.pdf.PageRenderer
import com.raptibooks.core.pdf.PdfExtractor
import com.raptibooks.core.pdf.PdfSupport
import com.raptibooks.core.reflow.OutlineItem
import com.raptibooks.core.reflow.ReflowEngine
import com.raptibooks.core.reflow.TocBuilder
import com.raptibooks.core.structure.analysis.DocumentStats
import com.raptibooks.core.structure.analysis.OcrDecision
import com.raptibooks.core.structure.analysis.PageAnalyzer
import com.raptibooks.core.structure.analysis.PageStructurer
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.raw.RawPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** A conversion error with a message that can be shown to the user. */
class ConversionException(message: String) : Exception(message)

/**
 * PDF -> analyse -> extract -> reformat -> mobile book.
 *
 * Pass 1 (per page, constant memory): extract glyphs/graphics, OCR pages without a usable
 * text layer, detect lines/tables/figures, render figures to images and cache the page
 * analysis on disk. Interrupted conversions resume from this cache.
 *
 * Pass 2: build the document profile (body font, heading sizes, running headers, Bangla glyph
 * order), structure every page and stream the reflowed blocks into the database.
 */
class ConversionPipeline(
    private val context: Context,
    private val books: BookDao,
    private val content: ContentDao,
    private val files: BookFiles,
) {
    data class Progress(val fraction: Float, val message: String)

    suspend fun convert(bookId: Long, onProgress: suspend (Progress) -> Unit) = lock.withLock {
        withContext(Dispatchers.Default) { run(bookId, onProgress) }
    }

    private suspend fun run(bookId: Long, onProgress: suspend (Progress) -> Unit) {
        val book = books.get(bookId) ?: throw ConversionException("Book not found")
        val pdf = files.source(bookId)
        if (!pdf.exists()) throw ConversionException(context.getString(com.raptibooks.core.data.R.string.error_missing_file))
        PdfSupport.init(context)
        val scratch = files.scratch(bookId)
        val lowMemory = isLowMemory()
        onProgress(Progress(0.01f, context.getString(com.raptibooks.core.data.R.string.progress_opening)))

        PdfExtractor.open(pdf, scratch).use { extractor ->
            val renderer: PageRenderer? = try {
                PageRenderer.open(pdf, scratch)
            } catch (e: Exception) {
                null
            } catch (e: LinkageError) {
                null
            }
            var ocr: OcrEngine? = null
            var ocrUnavailable = false
            fun ocrEngine(): OcrEngine? {
                if (ocr == null && !ocrUnavailable) {
                    ocr = try {
                        TesseractOcrEngine(context)
                    } catch (e: OcrUnavailableException) {
                        ocrUnavailable = true
                        null
                    } catch (e: Throwable) {
                        ocrUnavailable = true
                        null
                    }
                }
                return ocr
            }
            try {
                val pageCount = extractor.pageCount
                if (pageCount == 0) throw ConversionException(context.getString(com.raptibooks.core.data.R.string.error_no_pages))
                if (book.pageCount != pageCount) books.updateMetadata(bookId, null, null, pageCount)
                val cacheDir = files.pageCache(bookId)
                val figuresDir = files.figures(bookId)
                val stats = DocumentStats()
                var usedOcr = false

                // ------------------------------------------------------------- pass 1
                for (i in 0 until pageCount) {
                    currentCoroutineContext().ensureActive()
                    val cacheFile = pageFile(cacheDir, i)
                    if (cacheFile.exists()) {
                        val cached = runCatching { PageLayout.read(cacheFile) }.getOrNull()
                        if (cached != null) {
                            stats.add(cached)
                            usedOcr = usedOcr || cached.fromOcr
                            continue
                        }
                    }
                    onProgress(Progress(0.02f + 0.83f * i / pageCount, msg(com.raptibooks.core.data.R.string.progress_page, i + 1, pageCount)))
                    val layout = analysePage(i, pageCount, extractor, renderer, book.ocrMode, lowMemory, figuresDir, ::ocrEngine) { message ->
                        onProgress(Progress(0.02f + 0.83f * i / pageCount, message))
                    }
                    usedOcr = usedOcr || layout.fromOcr
                    PageLayout.write(layout, cacheFile)
                    stats.add(layout)
                }

                // ------------------------------------------------------------- pass 2
                onProgress(Progress(0.86f, context.getString(com.raptibooks.core.data.R.string.progress_layout)))
                content.deleteContent(bookId)
                val profile = stats.profile()
                val structurer = PageStructurer(profile)
                val batch = ArrayList<ContentBlock>(256)
                val engine = ReflowEngine { block ->
                    batch += block
                    if (batch.size >= 200) {
                        content.insertBlocks(bookId, batch)
                        batch.clear()
                    }
                }
                for (i in 0 until pageCount) {
                    currentCoroutineContext().ensureActive()
                    val layout = runCatching { PageLayout.read(pageFile(cacheDir, i)) }.getOrNull() ?: continue
                    engine.addPage(structurer.structure(layout))
                    if (i % 25 == 0) onProgress(Progress(0.86f + 0.12f * i / pageCount, context.getString(com.raptibooks.core.data.R.string.progress_layout)))
                }
                val headings = engine.finish()
                content.insertBlocks(bookId, batch)
                if (engine.blockCount == 0) throw ConversionException(context.getString(com.raptibooks.core.data.R.string.error_no_content))
                val outline = extractor.outline().map { OutlineItem(it.title, it.pageIndex, it.level) }
                content.saveToc(bookId, TocBuilder.build(outline, headings, engine.pageStarts(), engine.blockCount))
                content.notifyContentChanged()
                books.finishConversion(bookId, engine.blockCount, profile.language, usedOcr)
                files.clearConversionCache(bookId)
            } finally {
                renderer?.close()
                ocr?.close()
            }
        }
    }

    private suspend fun analysePage(
        index: Int,
        pageCount: Int,
        extractor: PdfExtractor,
        renderer: PageRenderer?,
        mode: OcrMode,
        lowMemory: Boolean,
        figuresDir: File,
        ocrEngine: () -> OcrEngine?,
        report: suspend (String) -> Unit,
    ): PageLayout {
        val extraction = try {
            extractor.extractPage(index)
        } catch (e: OutOfMemoryError) {
            System.gc()
            null
        } catch (e: Exception) {
            null
        }
        val size = extraction?.page?.let { it.width to it.height }
            ?: runCatching { renderer?.pageSize(index) }.getOrNull()
            ?: runCatching { extractor.pageSize(index) }.getOrNull()
            ?: (595f to 842f)
        val (w, h) = size
        val wantOcr = when (mode) {
            OcrMode.ALWAYS -> true
            OcrMode.NEVER -> false
            OcrMode.AUTO -> extraction == null ||
                OcrDecision.evaluate(extraction.page, extraction.glyphsDrawn, extraction.glyphsMapped) != OcrDecision.Reason.NONE
        }
        var raw: RawPage = extraction?.page ?: RawPage(index, w, h, emptyList())
        var pageBitmap: Bitmap? = null
        var lowConfidence = false
        if (wantOcr && renderer != null) {
            val engine = ocrEngine()
            if (engine != null) {
                report(msg(com.raptibooks.core.data.R.string.progress_ocr, index + 1, pageCount))
                try {
                    val bmp = renderer.renderPage(index, TesseractOcrEngine.renderWidthFor(w, h, lowMemory))
                    pageBitmap = bmp
                    val result = engine.recognize(bmp, index, w, h)
                    if (result.wordCount >= 3) raw = result.page
                    lowConfidence = result.wordCount < 30 && result.meanConfidence < 45f
                } catch (e: OutOfMemoryError) {
                    pageBitmap?.recycle()
                    pageBitmap = null
                    System.gc()
                }
            }
        }

        var layout = try {
            PageAnalyzer().analyze(raw)
        } catch (e: Exception) {
            PageLayout(index, w, h, emptyList(), emptyList(), emptyList(), raw.fromOcr, 0)
        }
        val hasContent = layout.lines.isNotEmpty() || layout.tables.isNotEmpty() || layout.figures.isNotEmpty()
        val visualOnly = raw.images.isNotEmpty() || raw.paths.size > 20 || extraction == null || wantOcr
        if ((!hasContent && visualOnly) || lowConfidence) {
            // Nothing readable (illustration, failed OCR, OCR disabled): keep the page as a picture.
            layout = PageLayout(index, w, h, emptyList(), listOf(FigureRegion(Rect(0f, 0f, w, h), FigureKind.SCAN)), emptyList(), raw.fromOcr, 0)
        }
        try {
            renderFigures(index, layout, renderer, pageBitmap, figuresDir)
        } finally {
            pageBitmap?.recycle()
        }
        return layout
    }

    private fun renderFigures(index: Int, layout: PageLayout, renderer: PageRenderer?, pageBitmap: Bitmap?, dir: File) {
        layout.figures.forEachIndexed { k, f ->
            val photo = f.kind != FigureKind.VECTOR
            val file = File(dir, "p$index-f$k." + if (photo) "jpg" else "png")
            val maxScale = if (f.kind == FigureKind.SCAN) 2.2f else 3f
            val bmp = image(index, f.bounds, renderer, pageBitmap, layout.width, maxScale) ?: return@forEachIndexed
            if (save(bmp, file, photo)) {
                f.imagePath = file.absolutePath
                f.imageWidth = bmp.width
                f.imageHeight = bmp.height
            }
            bmp.recycle()
        }
        layout.tables.forEachIndexed { k, t ->
            val file = File(dir, "p$index-t$k.png")
            val bmp = image(index, t.bounds.outset(4f).clamp(layout.width, layout.height), renderer, pageBitmap, layout.width, 2.5f)
                ?: return@forEachIndexed
            if (save(bmp, file, photo = false)) {
                t.imagePath = file.absolutePath
                t.imageWidth = bmp.width
                t.imageHeight = bmp.height
            }
            bmp.recycle()
        }
    }

    private fun image(index: Int, region: Rect, renderer: PageRenderer?, pageBitmap: Bitmap?, pageWidth: Float, maxScale: Float): Bitmap? {
        if (region.width < 2f || region.height < 2f) return null
        if (pageBitmap != null) {
            val s = pageBitmap.width / pageWidth
            val x = (region.left * s).roundToInt().coerceIn(0, pageBitmap.width - 1)
            val y = (region.top * s).roundToInt().coerceIn(0, pageBitmap.height - 1)
            val cw = (region.width * s).roundToInt().coerceIn(1, pageBitmap.width - x)
            val ch = (region.height * s).roundToInt().coerceIn(1, pageBitmap.height - y)
            return try {
                val crop = Bitmap.createBitmap(pageBitmap, x, y, cw, ch)
                if (crop === pageBitmap) crop.copy(Bitmap.Config.ARGB_8888, false) else crop
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        renderer ?: return null
        var scale = PageRenderer.fitScale(region, maxWidthPx = 1400, maxScale = maxScale)
        repeat(2) {
            try {
                return renderer.renderRegion(index, region, scale)
            } catch (e: OutOfMemoryError) {
                System.gc()
                scale *= 0.5f
            } catch (e: Exception) {
                return null
            } catch (e: LinkageError) {
                return null // optional codecs (e.g. JPEG 2000) missing in the fallback renderer
            }
        }
        return null
    }

    private fun save(bmp: Bitmap, file: File, photo: Boolean): Boolean = try {
        FileOutputStream(file).use { out ->
            bmp.compress(if (photo) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (photo) 85 else 100, out)
        }
    } catch (e: Exception) {
        file.delete()
        false
    }

    private fun pageFile(dir: File, index: Int) = File(dir, "page-%05d.bin".format(index))

    private fun msg(res: Int, vararg args: Any): String = context.getString(res, *args)

    private fun isLowMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isLowRamDevice || am.memoryClass <= 192
    }

    companion object {
        /** One conversion at a time keeps memory use predictable on low-end phones. */
        private val lock = Mutex()
    }
}
