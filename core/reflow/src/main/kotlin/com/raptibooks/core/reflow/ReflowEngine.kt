package com.raptibooks.core.reflow

import com.raptibooks.core.model.BoxSpan
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlinePicture
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.TocEntry
import com.raptibooks.core.structure.analysis.Patterns
import com.raptibooks.core.structure.analysis.StructuredElement
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.PictureRegion
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
class ReflowEngine(
    /** The rendered word pictures of the book, by number (see [InlinePicture]). */
    private val pictureOf: (Int) -> PictureRegion? = { null },
    /** Whether a word broken at a hyphen across a page is a hyphenated word ("three-phase"). */
    private val keepsHyphen: (String, String) -> Boolean = { _, _ -> false },
    private val sink: (ContentBlock) -> Unit,
) {

    private var nextIndex = 0
    private var held: StructuredElement.Paragraph? = null

    /** The last table, kept back in case it continues on the next page. */
    private var pendingTable: TableBlock? = null
    private val deferred = mutableListOf<StructuredElement>()
    private val headings = mutableListOf<TocEntry>()
    private val printed = mutableListOf<PrintedEntry>()
    private val firstBlockOfPage = sortedMapOf<Int, Int>()

    /** Callout boxes: the open one, one that may continue on the next page, and finished ones. */
    private class OpenBox(val start: Int, val fill: Int, val accent: Int)
    private var openBox: OpenBox? = null
    private var continuingBox: OpenBox? = null
    private val boxes = mutableListOf<BoxSpan>()

    /**
     * A worked example or practice problem ("Example 2.15", "Practice Problem 1.4") printed
     * without a box: shown as a card from its heading to the next section, example or answer.
     */
    private var labelBox: OpenBox? = null

    val blockCount: Int get() = nextIndex

    fun addPage(pageElements: List<StructuredElement>) {
        var elements = pageElements
        if (elements.isEmpty()) return
        // A box running on from the previous page: its end and new start are one box, so a
        // paragraph split by the page break can still be joined.
        val first = elements.first()
        val pendingEnd = deferred.singleOrNull() as? StructuredElement.Box
        if (held != null && pendingEnd != null && !pendingEnd.start && pendingEnd.region.openBottom &&
            first is StructuredElement.Box && first.start && first.region.openTop && sameColours(pendingEnd, first)
        ) {
            deferred.clear()
            elements = elements.drop(1)
            if (elements.isEmpty()) return
        }
        // Answer options split by the page break: (ক) (খ) at the foot, (গ) (ঘ) on the next page.
        val heldOptions = held?.takeIf { it.kind == ParagraphKind.OPTIONS }
        if (heldOptions != null) {
            var text = heldOptions.text
            var k = 0
            while (k < elements.size) {
                val el = elements[k]
                val more = when {
                    el is StructuredElement.ListItem && nextOption(text, el.marker) -> el.marker + "\t" + el.text.replace('\n', ' ')
                    el is StructuredElement.Paragraph && el.kind == ParagraphKind.OPTIONS && nextOption(text, el.text.substringBefore('\t')) -> el.text
                    else -> null
                } ?: break
                text += "\n" + more
                k++
            }
            if (k > 0) {
                held = heldOptions.copy(text = text)
                elements = elements.drop(k)
                if (elements.isEmpty()) return
            }
        }
        // Figures, tables and margin notes printed at the top of the page beside the end of a
        // paragraph from the previous page: the paragraph comes first.
        val h0 = held
        if (h0 != null) {
            var k = 0
            while (k < elements.size && isFloat(elements[k])) k++
            val next = elements.getOrNull(k)
            if (k > 0 && next is StructuredElement.Paragraph && canMerge(h0, next)) {
                elements = listOf(next) + elements.subList(0, k) + elements.subList(k + 1, elements.size)
            }
        }
        // The last body paragraph may continue on the next page; trailing footnotes, figures and
        // tables are deferred.
        var holdIndex = -1
        for (i in elements.indices.reversed()) {
            val el = elements[i]
            if (isFloat(el)) continue
            if (el is StructuredElement.Box) continue
            if (el is StructuredElement.Paragraph && (el.kind == ParagraphKind.BODY || el.kind == ParagraphKind.OPTIONS)) holdIndex = i
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

    // (a display equation is part of the text: a paragraph does not continue past it)
    private fun isFloat(el: StructuredElement): Boolean =
        (el is StructuredElement.Figure && el.region.kind != FigureKind.EQUATION) || el is StructuredElement.Table ||
            (el is StructuredElement.Paragraph && (el.kind == ParagraphKind.NOTE || el.kind == ParagraphKind.CAPTION))

    private fun addDeferred(elements: List<StructuredElement>, from: Int) {
        for (k in from until elements.size) deferred += elements[k]
    }

    /** Flushes everything; returns the headings found (for the table of contents). */
    fun finish(): List<TocEntry> {
        flushHeld()
        flushTable()
        closeLabelBox()
        closeContinuing()
        openBox?.let { closeBox(it) }
        openBox = null
        return headings.toList()
    }

    /** Ranges of blocks printed inside callout boxes, in order. Valid after [finish]. */
    fun boxes(): List<BoxSpan> = boxes.sortedBy { it.startIndex }

    private fun sameColours(a: StructuredElement.Box, b: StructuredElement.Box) =
        a.region.fill == b.region.fill && a.region.accent == b.region.accent

    private fun closeBox(b: OpenBox) {
        val end = nextIndex - 1
        if (end >= b.start) boxes += BoxSpan(b.start, end, b.fill, b.accent)
    }

    private fun closeContinuing() {
        continuingBox?.let { closeBox(it) }
        continuingBox = null
    }

    private fun closeLabelBox(through: Int = nextIndex - 1) {
        val b = labelBox ?: return
        labelBox = null
        if (through > b.start) boxes += BoxSpan(b.start, through, b.fill, b.accent)
    }

    private fun boxMarker(el: StructuredElement.Box) {
        flushTable()
        val r = el.region
        if (el.start) {
            closeLabelBox()
            val cont = continuingBox
            continuingBox = null
            openBox?.let { closeBox(it) }
            openBox = if (cont != null && r.openTop && cont.fill == r.fill && cont.accent == r.accent) cont else OpenBox(nextIndex, r.fill, r.accent)
            if (openBox !== cont) cont?.let { closeBox(it) }
        } else {
            val b = openBox ?: return
            openBox = null
            if (r.openBottom) continuingBox = b else closeBox(b)
        }
    }

    /** Lines of the book's own printed table of contents, in order. */
    fun printedContents(): List<PrintedEntry> = printed.toList()

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
            is StructuredElement.Box -> boxMarker(el)
            is StructuredElement.Heading -> {
                val label = EXAMPLE.find(el.text)
                if (labelBox != null && (el.level <= 3 || label != null || Patterns.chapter.containsMatchIn(el.text))) {
                    flushTable()
                    closeLabelBox()
                }
                val idx = emit { HeadingBlock(it, el.pageIndex, el.level, el.text) }
                if (el.level <= 3) headings += TocEntry(el.text, el.level, idx, el.pageIndex)
                if (label != null && openBox == null && continuingBox == null) {
                    val practice = label.groupValues[1].lowercase().let { it.startsWith("practice") || it.startsWith("exercise") || it == "অনুশীলনী" }
                    labelBox = if (practice) OpenBox(idx, PRACTICE_FILL, PRACTICE_ACCENT) else OpenBox(idx, EXAMPLE_FILL, EXAMPLE_ACCENT)
                }
            }
            is StructuredElement.Paragraph -> emitParagraph(el)
            is StructuredElement.ListItem -> {
                val (styles, pictures) = withPictures(el.text, el.styles)
                emit { ListItemBlock(it, el.pageIndex, el.marker, el.text, styles, el.level, pictures) }
            }
            is StructuredElement.Figure -> {
                val region = el.region
                var caption = region.caption
                var consumed = 1
                val next = elements.getOrNull(i + 1)
                if (caption == null && region.kind != FigureKind.EQUATION && next is StructuredElement.Paragraph && next.text.length < 400 &&
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
                        FigureKind.EQUATION -> ImageKind.EQUATION
                    }
                    emit {
                        ImageBlock(it, el.pageIndex, path, region.imageWidth, region.imageHeight, caption, kind,
                            region.bounds.width, region.textSize, region.text)
                    }
                } else if (region.kind == FigureKind.EQUATION) {
                    // Not rendered (no renderer): the formula's text is better than nothing.
                    val text = listOfNotNull(region.text, caption).joinToString("   ")
                    if (text.isNotBlank()) emit { ParagraphBlock(it, el.pageIndex, text, kind = ParagraphKind.CENTERED) }
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
                val prev = pendingTable
                if (prev != null && caption == null && continuesTable(prev, el)) {
                    // Same columns, next page, nothing in between: one table split by the page break.
                    val repeatedHeader = t.headerRows > 0 && prev.headerRows > 0 &&
                        t.rows.take(t.headerRows) == prev.rows.take(prev.headerRows)
                    val rows = prev.rows + if (repeatedHeader) t.rows.drop(t.headerRows) else t.rows
                    pendingTable = prev.copy(rows = rows, imagePath = null, imageWidth = 0, imageHeight = 0)
                    return consumed
                }
                flushTable()
                pendingTable = TableBlock(-1, el.pageIndex, t.rows, t.headerRows, t.imagePath, t.imageWidth, t.imageHeight, caption)
                return consumed
            }
        }
        return 1
    }

    private fun emitParagraph(p: StructuredElement.Paragraph) {
        val text = p.text.trim()
        if (text.isEmpty()) return
        val lead = p.text.length - p.text.trimStart().length
        val shifted = if (lead == 0) p.styles else p.styles.mapNotNull {
            val a = (it.start - lead).coerceAtLeast(0)
            val b = (it.end - lead).coerceAtMost(text.length)
            if (b > a) InlineStyle(a, b, it.flags) else null
        }
        val (styles, pictures) = withPictures(text, shifted)
        val idx = emit { ParagraphBlock(it, p.pageIndex, text, styles, p.kind, pictures) }
        labelBox?.let { b ->
            // The answer ends a practice problem; a very long stretch is not one example.
            if (ANSWER.containsMatchIn(text) || idx - b.start > 150) closeLabelBox(idx)
        }
        if (p.kind == ParagraphKind.TOC) {
            val tab = text.indexOf('\t')
            val title = if (tab >= 0) text.substring(0, tab) else text
            val page = if (tab >= 0) text.substring(tab + 1) else ""
            printed += PrintedEntry(title.trim(), page.trim(), p.styles.any { it.bold }, idx)
        }
    }

    /** Whether [marker] is the option letter after the last one in [options] ("(খ)" → "(গ)"). */
    private fun nextOption(options: String, marker: String): Boolean {
        val last = options.substringAfterLast('\n').substringBefore('\t')
        val a = optionLetter(last) ?: return false
        val b = optionLetter(marker) ?: return false
        return a.first == b.first && b.second == a.second + 1
    }

    private fun optionLetter(marker: String): Pair<String, Int>? {
        val core = marker.trim().removePrefix("(").removeSuffix(")").removeSuffix(".")
        if (core.length != 1) return null
        for (alphabet in listOf("কখগঘঙচ", "abcdef", "ABCDEF")) {
            val k = alphabet.indexOf(core[0])
            if (k >= 0) return alphabet to k
        }
        return null
    }

    private fun continuesTable(prev: TableBlock, next: StructuredElement.Table): Boolean {
        val t = next.region
        val columns = prev.rows.maxOfOrNull { it.size } ?: 0
        return next.pageIndex == prev.pageIndex + 1 && t.columnCount == columns &&
            (t.headerRows == 0 || prev.headerRows == 0 || t.rows.take(t.headerRows) == prev.rows.take(prev.headerRows))
    }

    private fun flushTable() {
        val t = pendingTable ?: return
        pendingTable = null
        emit { t.copy(index = it) }
    }

    private companion object {
        /** "Example 2.15", "Practice Problem 1.4", "উদাহরণ ৭.৩" (with a number). */
        val EXAMPLE = Regex(
            "^\\s*(example|worked example|practice problem|exercise|উদাহরণ|অনুশীলনী)\\s*[0-9০-৯]+(?:[.\\-][0-9০-৯]+)*\\s*[:.ঃ]?\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val ANSWER = Regex("^\\s*(answers?|উত্তর)\\s*[:ঃ]", RegexOption.IGNORE_CASE)
        const val EXAMPLE_FILL = 0xFFF1E0
        const val EXAMPLE_ACCENT = 0xE07800
        const val PRACTICE_FILL = 0xE6F1FA
        const val PRACTICE_ACCENT = 0x1F6FB2
    }

    private inline fun emit(make: (Int) -> ContentBlock): Int {
        if (pendingTable != null) flushTable()
        // Something printed between a box at the foot of a page and the next page: the box ended.
        if (continuingBox != null) closeContinuing()
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

    /** Picture styles become the block's pictures (a picture not rendered stays a marker). */
    private fun withPictures(text: String, styles: List<InlineStyle>): Pair<List<InlineStyle>, List<InlinePicture>> {
        if (styles.none { it.picture }) return styles to emptyList()
        val pictures = ArrayList<InlinePicture>()
        for (st in styles) {
            if (!st.picture || st.start !in text.indices || text[st.start] != InlinePicture.MARKER) continue
            val r = pictureOf(InlineStyle.pictureId(st.flags)) ?: continue
            val path = r.imagePath ?: continue
            if (r.imageWidth <= 0 || r.imageHeight <= 0 || r.fontSize <= 0f) continue
            pictures += InlinePicture(st.start, path, r.imageWidth, r.imageHeight, r.bounds.height / r.fontSize)
        }
        return styles.filter { !it.picture } to pictures
    }

    private fun merge(a: StructuredElement.Paragraph, b: StructuredElement.Paragraph): StructuredElement.Paragraph {
        val broken = a.text.endsWith("-") && a.text.length > 1 && a.text[a.text.length - 2].isLetter() &&
            a.text[a.text.length - 2].code < 0x250 && b.text.first().isLowerCase()
        val joined: String
        val offset: Int
        if (broken && keepsHyphen(a.text.dropLast(1), b.text)) {
            joined = a.text + b.text
            offset = a.text.length
        } else if (broken) {
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
