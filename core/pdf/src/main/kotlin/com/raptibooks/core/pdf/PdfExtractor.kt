package com.raptibooks.core.pdf

import com.raptibooks.core.structure.raw.RawPage
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.ResourceCache
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColorSpace
import com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShading
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference

class PdfPasswordException(message: String) : IOException(message)

class PdfOpenException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class PdfMetadata(val title: String?, val author: String?, val pageCount: Int)

data class PdfOutlineEntry(val title: String, val pageIndex: Int, val level: Int)

/** Everything extracted from one page's content stream. */
class PageExtraction(val page: RawPage, val glyphsDrawn: Int, val glyphsMapped: Int)

/**
 * Reads PDF structure with PdfBox. Designed for large files on low-memory phones: the parser
 * spills to a scratch file, pages are processed one at a time and decoded images, colour
 * spaces etc. are never cached (only fonts, which are expensive to parse).
 */
class PdfExtractor private constructor(private val document: PDDocument) : Closeable {

    val pageCount: Int get() = document.numberOfPages

    private val textCollector = TextCollector()

    fun metadata(): PdfMetadata {
        val info = try {
            document.documentInformation
        } catch (_: Exception) {
            null
        }
        return PdfMetadata(
            title = info?.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals("untitled", true) },
            author = info?.author?.trim()?.takeIf { it.isNotEmpty() },
            pageCount = pageCount,
        )
    }

    fun outline(maxItems: Int = 3000): List<PdfOutlineEntry> {
        val root = try {
            document.documentCatalog.documentOutline
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<PdfOutlineEntry>()
        val seen = HashSet<COSDictionary>()
        fun walk(node: PDOutlineNode, level: Int) {
            var child = node.firstChild
            while (child != null && out.size < maxItems) {
                if (!seen.add(child.cosObject)) break // cyclic outline
                val title = child.title?.trim().orEmpty()
                val pageIndex = try {
                    child.findDestinationPage(document)?.let { document.pages.indexOf(it) } ?: -1
                } catch (_: Exception) {
                    -1
                }
                if (title.isNotEmpty() && pageIndex >= 0) out += PdfOutlineEntry(title, pageIndex, level)
                if (level < 6) walk(child, level + 1)
                child = child.nextSibling
            }
        }
        return try {
            walk(root, 0)
            out
        } catch (_: Exception) {
            out
        }
    }

    /** Extracts text glyphs, image placements and vector paths of one page. */
    fun extractPage(index: Int): PageExtraction {
        val page = document.getPage(index)
        val transform = DisplayTransform(page)
        textCollector.collect(document, index)
        val graphics = GraphicsCollector(page)
        try {
            graphics.collect()
        } catch (e: IOException) {
            // Broken graphics are not fatal: keep the text.
        }
        // Printer's marks (file name and time slug, crop marks) sit outside the crop box.
        val w = transform.width
        val h = transform.height
        fun visible(left: Float, top: Float, right: Float, bottom: Float) = right > 0f && bottom > 0f && left < w && top < h
        val raw = RawPage(
            index = index,
            width = w,
            height = h,
            fragments = textCollector.fragments.filter { f ->
                val cx = (f.left + f.right) / 2f
                val cy = (f.top + f.bottom) / 2f
                cx in 0f..w && cy in 0f..h
            },
            images = graphics.images.filter { visible(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) },
            paths = graphics.paths.filter { visible(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) },
            rotatedText = textCollector.rotated.filter { visible(it.left, it.top, it.right, it.bottom) },
            invisibleText = textCollector.fragments.isNotEmpty() && textCollector.invisible * 2 >= textCollector.fragments.size,
        )
        return PageExtraction(raw, textCollector.glyphsDrawn, textCollector.glyphsMapped)
    }

    fun pageSize(index: Int): Pair<Float, Float> {
        val t = DisplayTransform(document.getPage(index))
        return t.width to t.height
    }

    /** Exposed for the PdfBox-based fallback renderer. */
    internal val pdDocument: PDDocument get() = document

    override fun close() {
        try {
            document.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /**
         * @param scratchDir directory for PdfBox's temporary buffers.
         * @throws PdfPasswordException if the PDF needs a password that wasn't supplied/valid.
         * @throws PdfOpenException if the file is not a readable PDF.
         */
        fun open(file: File, scratchDir: File, password: String = ""): PdfExtractor {
            scratchDir.mkdirs()
            val memory = MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(scratchDir)
            val doc = try {
                PDDocument.load(file, password, memory)
            } catch (e: InvalidPasswordException) {
                throw PdfPasswordException("This PDF is protected with a password")
            } catch (e: IOException) {
                throw PdfOpenException("Not a readable PDF: ${e.message}", e)
            } catch (e: OutOfMemoryError) {
                throw PdfOpenException("Not enough memory to open this PDF", e)
            }
            doc.resourceCache = FontOnlyResourceCache()
            return PdfExtractor(doc)
        }
    }
}

/** Caches parsed fonts only; images and other resources are re-read when needed. */
private class FontOnlyResourceCache : ResourceCache {
    private val fonts = object : LinkedHashMap<COSObject, SoftReference<PDFont>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<COSObject, SoftReference<PDFont>>?) = size > 96
    }

    override fun getFont(indirect: COSObject): PDFont? = fonts[indirect]?.get()
    override fun put(indirect: COSObject, font: PDFont) {
        fonts[indirect] = SoftReference(font)
    }

    override fun getColorSpace(indirect: COSObject): PDColorSpace? = null
    override fun getExtGState(indirect: COSObject): PDExtendedGraphicsState? = null
    override fun getShading(indirect: COSObject): PDShading? = null
    override fun getPattern(indirect: COSObject): PDAbstractPattern? = null
    override fun getProperties(indirect: COSObject): PDPropertyList? = null
    override fun getXObject(indirect: COSObject): PDXObject? = null
    override fun put(indirect: COSObject, colorSpace: PDColorSpace) = Unit
    override fun put(indirect: COSObject, extGState: PDExtendedGraphicsState) = Unit
    override fun put(indirect: COSObject, shading: PDShading) = Unit
    override fun put(indirect: COSObject, pattern: PDAbstractPattern) = Unit
    override fun put(indirect: COSObject, propertyList: PDPropertyList) = Unit
    override fun put(indirect: COSObject, xobject: PDXObject) = Unit
}
