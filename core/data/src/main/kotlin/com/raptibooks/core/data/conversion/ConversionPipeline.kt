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
import com.raptibooks.core.structure.text.GrayImage
import com.raptibooks.core.structure.text.SymbolRecognizer
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

                // ------------------------------------------------------------- pass 0
                // Learn the book's body text size first, so every page is judged against the
                // same reference (labels, formulas and captions are measured against it).
                val bodySize = documentBodySize(extractor, pageCount, cacheDir) { done, total ->
                    onProgress(Progress(0.02f + 0.06f * done / total, context.getString(com.raptibooks.core.data.R.string.progress_scan)))
                }

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
                    onProgress(Progress(0.08f + 0.77f * i / pageCount, msg(com.raptibooks.core.data.R.string.progress_page, i + 1, pageCount)))
                    val layout = analysePage(i, pageCount, extractor, renderer, book.ocrMode, lowMemory, figuresDir, bodySize, ::ocrEngine) { message ->
                        onProgress(Progress(0.08f + 0.77f * i / pageCount, message))
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
                content.saveToc(bookId, TocBuilder.build(outline, headings, engine.pageStarts(), engine.blockCount, engine.printedContents()))
                content.notifyContentChanged()
                books.finishConversion(bookId, engine.blockCount, profile.language, usedOcr, LAYOUT_VERSION)
                // Converted again: continue reading on the same page, bookmarks follow their pages.
                val restore = books.restorePage(bookId)
                if (restore >= 0) {
                    books.restorePosition(bookId, content.firstBlockAtOrAfterPage(bookId, restore) ?: 0)
                    content.remapBookmarks(bookId)
                }
                // Notes follow their pages too (the blocks they pointed to are gone).
                content.remapNotes(bookId)
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
        bodySize: Float?,
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

        if (!raw.fromOcr && renderer != null && renderer.rendersRegionsDirectly) {
            // Symbols whose font has no Unicode mapping (the "=" and "×" of math fonts) are
            // identified from their printed shape.
            raw = try {
                SymbolRecognizer.recognize(raw) { region, scale -> renderGray(renderer, index, region, scale) }
            } catch (e: OutOfMemoryError) {
                System.gc()
                raw
            }
        }
        var layout = try {
            PageAnalyzer(bodySize).analyze(raw)
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
            renderFigures(index, layout, renderer, pageBitmap, figuresDir, lowMemory)
        } finally {
            pageBitmap?.recycle()
        }
        return layout
    }

    private fun renderFigures(index: Int, layout: PageLayout, renderer: PageRenderer?, pageBitmap: Bitmap?, dir: File, lowMemory: Boolean) {
        layout.figures.forEachIndexed { k, f ->
            val equation = f.kind == FigureKind.EQUATION
            val photo = f.kind != FigureKind.VECTOR && !equation
            val file = File(dir, "p$index-${if (equation) "e" else "f"}$k." + if (photo) "jpg" else "png")
            // Rendered sharp enough to be shown larger than the screen and zoomed: diagrams at
            // up to 5 px/pt, formulas at up to 7 px/pt (their strokes are thin).
            val maxScale = when {
                f.kind == FigureKind.SCAN -> 2.2f
                equation -> if (lowMemory) 4f else 7f
                else -> if (lowMemory) 3f else 5f
            }
            val maxWidth = when {
                f.kind == FigureKind.SCAN -> 1400
                lowMemory -> 1600
                else -> 3200
            }
            var bmp = image(index, f.bounds, renderer, pageBitmap, layout.width, maxScale, maxWidth) ?: return@forEachIndexed
            if (f.kind != FigureKind.SCAN) {
                // Tight crop: drop the empty margin around the printed content.
                val scale = bmp.width / f.bounds.width
                val ink = inkBounds(bmp, if (equation) 1f else 3f * scale)
                if (ink != null) {
                    if (ink.left > 0 || ink.top > 0 || ink.right < bmp.width || ink.bottom < bmp.height) {
                        val cropped = Bitmap.createBitmap(bmp, ink.left, ink.top, ink.width(), ink.height())
                        if (cropped !== bmp) bmp.recycle()
                        bmp = cropped
                    }
                    val b = f.bounds
                    f.bounds = Rect(b.left + ink.left / scale, b.top + ink.top / scale, b.left + ink.right / scale, b.top + ink.bottom / scale)
                }
            }
            if (equation) bmp = inkMask(bmp)
            if (save(bmp, file, photo)) {
                f.imagePath = file.absolutePath
                f.imageWidth = bmp.width
                f.imageHeight = bmp.height
            }
            bmp.recycle()
        }
        layout.tables.forEachIndexed { k, t ->
            val file = File(dir, "p$index-t$k.png")
            val bmp = image(index, t.bounds.outset(4f).clamp(layout.width, layout.height), renderer, pageBitmap, layout.width, if (lowMemory) 2.5f else 3.5f)
                ?: return@forEachIndexed
            if (save(bmp, file, photo = false)) {
                t.imagePath = file.absolutePath
                t.imageWidth = bmp.width
                t.imageHeight = bmp.height
            }
            bmp.recycle()
        }
    }

    /** Bounding box (pixels) of everything that is not background, with [pad] pixels around it. */
    private fun inkBounds(bmp: Bitmap, pad: Float): android.graphics.Rect? {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1
            var last = -1
            for (x in 0 until w) {
                val p = row[x]
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if ((p ushr 24) > 16 && lum < 244) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first >= 0) {
                if (top < 0) top = y
                bottom = y
                left = minOf(left, first)
                right = maxOf(right, last)
            }
        }
        if (top < 0) return null
        val d = pad.roundToInt()
        return android.graphics.Rect(
            (left - d).coerceAtLeast(0), (top - d).coerceAtLeast(0),
            (right + 1 + d).coerceAtMost(w), (bottom + 1 + d).coerceAtMost(h),
        )
    }

    /**
     * A formula as ink: black with the darkness of each pixel as its opacity, so the reader can
     * draw it in the text colour of any theme (light paper, sepia, night).
     */
    private fun inkMask(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val out = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return bmp
        }
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                // Paper and light tints (shaded boxes) vanish, strokes keep their antialiasing.
                val alpha = ((236 - lum) * 255 / 196).coerceIn(0, 255)
                row[x] = alpha shl 24
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        bmp.recycle()
        return out
    }

    private fun renderGray(renderer: PageRenderer, index: Int, region: Rect, scale: Float): GrayImage? {
        val bmp = try {
            renderer.renderRegion(index, region, scale)
        } catch (e: Exception) {
            return null
        } catch (e: LinkageError) {
            return null
        }
        return try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            SymbolRecognizer.gray(px, bmp.width, bmp.height)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Body text size of the whole book: the size that carries the most characters, from up to
     * [SAMPLE_PAGES] pages spread over the book. Cached so a resumed conversion skips it.
     */
    private suspend fun documentBodySize(
        extractor: PdfExtractor,
        pageCount: Int,
        cacheDir: File,
        report: suspend (Int, Int) -> Unit,
    ): Float? {
        val cache = File(cacheDir, "body-size.txt")
        cache.takeIf { it.exists() }?.readText()?.toFloatOrNull()?.let { return it.takeIf { v -> v > 0f } }
        val pages = if (pageCount <= SAMPLE_PAGES) (0 until pageCount).toList()
        else List(SAMPLE_PAGES) { it * (pageCount - 1) / (SAMPLE_PAGES - 1) }.distinct()
        val chars = HashMap<Int, Long>()
        for ((n, i) in pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (n % 5 == 0) report(n, pages.size)
            val page = try {
                extractor.extractPage(i).page
            } catch (e: Exception) {
                continue
            } catch (e: OutOfMemoryError) {
                System.gc()
                continue
            }
            for (f in page.fragments) {
                if (f.isSpace) continue
                val key = (f.fontSize * 2f).roundToInt()
                chars[key] = (chars[key] ?: 0L) + f.text.length
            }
        }
        val body = chars.maxByOrNull { it.value }?.key?.let { it / 2f }
        runCatching { cache.writeText((body ?: 0f).toString()) }
        return body
    }

    private fun image(
        index: Int,
        region: Rect,
        renderer: PageRenderer?,
        pageBitmap: Bitmap?,
        pageWidth: Float,
        maxScale: Float,
        maxWidthPx: Int = 1400,
    ): Bitmap? {
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
        var scale = PageRenderer.fitScale(region, maxWidthPx = maxWidthPx, maxScale = maxScale)
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
        /**
         * Version of the analysis/reflow. Raise it when conversion results improve, so books
         * converted by an older version are converted again automatically.
         */
        const val LAYOUT_VERSION = 3

        /** Pages read to learn the book's body text size before the real analysis. */
        private const val SAMPLE_PAGES = 60

        /** One conversion at a time keeps memory use predictable on low-end phones. */
        private val lock = Mutex()
    }
}
