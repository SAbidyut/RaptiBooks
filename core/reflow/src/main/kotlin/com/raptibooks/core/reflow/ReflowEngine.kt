package com.raptibooks.core.reflow

import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.TocEntry
import com.raptibooks.core.structure.analysis.Patterns
import com.raptibooks.core.structure.analysis.StructuredElement
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.text.BanglaText
import kotlin.math.abs

/**
 * Turns per-page structured elements into the flat, page-independent block stream of the
 * reflowed book. Pages are fed in order and blocks are emitted to [sink] as soon as they are
 * final, so memory use does not grow with the book size.
 *
 * Responsibilities:
 * * joining paragraphs that continue across a page break (footnotes in between are deferred);
 * * attaching captions to figures and tables;
 * * numbering blocks and remembering headings and the first block of every page.
 */
class ReflowEngine(private val sink: (ContentBlock) -> Unit) {

    private var nextIndex = 0
    private var held: StructuredElement.Paragraph? = null
    private val deferred = mutableListOf<StructuredElement>()
    private val headings = mutableListOf<TocEntry>()
    private val firstBlockOfPage = sortedMapOf<Int, Int>()

    val blockCount: Int get() = nextIndex

    fun addPage(elements: List<StructuredElement>) {
        if (elements.isEmpty()) return
        // The last body paragraph may continue on the next page; trailing footnotes are deferred.
        var holdIndex = -1
        for (i in elements.indices.reversed()) {
            val el = elements[i]
            if (el is StructuredElement.Paragraph && el.kind == ParagraphKind.NOTE) continue
            if (el is StructuredElement.Paragraph && el.kind == ParagraphKind.BODY) holdIndex = i
            break
        }

        var i = 0
        while (i < elements.size) {
            val el = elements[i]
            val h = held
            if (h != null) {
                if (el is StructuredElement.Paragraph && canMerge(h, el)) {
                    held = merge(h, el)
                    i++
                    if (i - 1 == holdIndex) {
                        // still the last paragraph of this page: keep holding
                        addDeferred(elements, i)
                        return
                    }
                    flushHeld()
                    continue
                }
                flushHeld()
            }
            if (i == holdIndex) {
                held = el as StructuredElement.Paragraph
                addDeferred(elements, i + 1)
                return
            }
            i += emitElement(elements, i)
        }
    }

    private fun addDeferred(elements: List<StructuredElement>, from: Int) {
        for (k in from until elements.size) deferred += elements[k]
    }

    /** Flushes everything; returns the headings found (for the table of contents). */
    fun finish(): List<TocEntry> {
        flushHeld()
        return headings.toList()
    }

    /** First block index for each page that produced blocks. */
    fun pageStarts(): Map<Int, Int> = firstBlockOfPage.toMap()

    // -------------------------------------------------------------------------------------

    private fun flushHeld() {
        val h = held
        held = null
        if (h != null) emitParagraph(h)
        if (deferred.isNotEmpty()) {
            val copy = deferred.toList()
            deferred.clear()
            var k = 0
            while (k < copy.size) k += emitElement(copy, k)
        }
    }

    /** Emits elements[i] (and possibly a following caption); returns how many were consumed. */
    private fun emitElement(elements: List<StructuredElement>, i: Int): Int {
        when (val el = elements[i]) {
            is StructuredElement.Heading -> {
                val idx = emit { HeadingBlock(it, el.pageIndex, el.level, el.text) }
                if (el.level <= 3) headings += TocEntry(el.text, el.level, idx, el.pageIndex)
            }
            is StructuredElement.Paragraph -> emitParagraph(el)
            is StructuredElement.ListItem -> emit { ListItemBlock(it, el.pageIndex, el.marker, el.text, el.styles, el.level) }
            is StructuredElement.Figure -> {
                val region = el.region
                var caption = region.caption
                var consumed = 1
                val next = elements.getOrNull(i + 1)
                if (caption == null && next is StructuredElement.Paragraph && next.text.length < 400 &&
                    Patterns.caption.containsMatchIn(next.text)
                ) {
                    caption = next.text
                    consumed = 2
                }
                val path = region.imagePath
                if (path != null && region.imageWidth > 0 && region.imageHeight > 0) {
                    val kind = when (region.kind) {
                        FigureKind.VECTOR -> ImageKind.DIAGRAM
                        FigureKind.RASTER -> ImageKind.PHOTO
                        FigureKind.SCAN -> ImageKind.PAGE
                        FigureKind.MIXED -> ImageKind.FIGURE
                    }
                    emit {
                        ImageBlock(it, el.pageIndex, path, region.imageWidth, region.imageHeight, caption, kind,
                            region.bounds.width, region.textSize)
                    }
                } else if (caption != null) {
                    emit { ParagraphBlock(it, el.pageIndex, caption, kind = ParagraphKind.CAPTION) }
                }
                return consumed
            }
            is StructuredElement.Table -> {
                val t = el.region
                var caption = t.caption
                var consumed = 1
                val next = elements.getOrNull(i + 1)
                if (caption == null && next is StructuredElement.Paragraph && next.text.length < 300 &&
                    Patterns.tableCaption.containsMatchIn(next.text)
                ) {
                    caption = next.text
                    consumed = 2
                }
                emit {
                    TableBlock(it, el.pageIndex, t.rows, t.headerRows, t.imagePath, t.imageWidth, t.imageHeight, caption)
                }
                return consumed
            }
        }
        return 1
    }

    private fun emitParagraph(p: StructuredElement.Paragraph) {
        val text = p.text.trim()
        if (text.isEmpty()) return
        emit { ParagraphBlock(it, p.pageIndex, text, p.styles, p.kind) }
    }

    private inline fun emit(make: (Int) -> ContentBlock): Int {
        val idx = nextIndex++
        val block = make(idx)
        firstBlockOfPage.putIfAbsent(block.pageIndex, idx)
        sink(block)
        return idx
    }

    private fun canMerge(a: StructuredElement.Paragraph, b: StructuredElement.Paragraph): Boolean {
        if (a.kind != ParagraphKind.BODY || b.kind != ParagraphKind.BODY) return false
        if (b.indented) return false
        if (abs(a.fontSize - b.fontSize) > 1.2f) return false
        if (Patterns.endsSentence(a.text)) return false
        if (Patterns.listMarker.containsMatchIn(b.text)) return false
        val first = b.text.firstOrNull() ?: return false
        return first.isLowerCase() || BanglaText.isBengali(first) || a.text.endsWith('-') ||
            a.text.endsWith(',') || first.isDigit()
    }

    private fun merge(a: StructuredElement.Paragraph, b: StructuredElement.Paragraph): StructuredElement.Paragraph {
        val dehyphenate = a.text.endsWith("-") && a.text.length > 1 && a.text[a.text.length - 2].isLetter() &&
            a.text[a.text.length - 2].code < 0x250 && b.text.first().isLowerCase()
        val joined: String
        val offset: Int
        if (dehyphenate) {
            joined = a.text.dropLast(1) + b.text
            offset = a.text.length - 1
        } else {
            joined = a.text + " " + b.text
            offset = a.text.length + 1
        }
        val styles = a.styles.map { InlineStyle(it.start, minOf(it.end, offset), it.flags) }.filter { it.end > it.start } +
            b.styles.map { InlineStyle(it.start + offset, it.end + offset, it.flags) }
        return a.copy(text = joined, styles = styles)
    }
}
