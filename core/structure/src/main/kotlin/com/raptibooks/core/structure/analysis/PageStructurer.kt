package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.BoxRegion
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.text.BanglaText
import com.raptibooks.core.structure.text.TextCleaner
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Logical page content in reading order, produced by [PageStructurer]. */
sealed interface StructuredElement {
    val pageIndex: Int

    data class Heading(override val pageIndex: Int, val level: Int, val text: String) : StructuredElement

    data class Paragraph(
        override val pageIndex: Int,
        val text: String,
        val styles: List<InlineStyle>,
        val kind: ParagraphKind,
        /** First line is indented (typical paragraph start in books). */
        val indented: Boolean,
        val fontSize: Float,
    ) : StructuredElement

    data class ListItem(
        override val pageIndex: Int,
        val marker: String,
        val text: String,
        val styles: List<InlineStyle>,
        val level: Int,
    ) : StructuredElement

    data class Figure(override val pageIndex: Int, val region: FigureRegion) : StructuredElement

    data class Table(override val pageIndex: Int, val region: TableRegion) : StructuredElement

    /** Start ([start] = true) or end of the content of a callout box. */
    data class Box(override val pageIndex: Int, val start: Boolean, val region: BoxRegion) : StructuredElement
}

/**
 * Second pass: turns one page's [PageLayout] into [StructuredElement]s using the document-wide
 * [DocumentProfile] (body size, heading sizes, running headers, Bangla glyph order).
 */
class PageStructurer(private val profile: DocumentProfile) {

    /** A line with normalised text. */
    private class NLine(val src: TextLine, val text: String, val styles: List<InlineStyle>) {
        val bounds: Rect get() = src.bounds
        val size: Float get() = src.fontSize
    }

    private sealed interface Item {
        val bounds: Rect
        val em: Float
    }

    private class LineItem(val line: NLine) : Item {
        override val bounds get() = line.bounds
        override val em get() = line.size
    }

    private class FigureItem(val figure: FigureRegion) : Item {
        override val bounds get() = figure.bounds
        override val em get() = 0f
    }

    private class TableItem(val table: TableRegion) : Item {
        override val bounds get() = table.bounds
        override val em get() = 0f
    }

    /** Paragraph/list item under construction. */
    private class Builder(first: NLine, var column: Rect, val marker: String?, val markerLeft: Float) {
        val lines = mutableListOf(first)
        val text = StringBuilder()
        val styles = mutableListOf<InlineStyle>()
        val last: NLine get() = lines.last()
    }

    fun structure(layout: PageLayout): List<StructuredElement> {
        // A title in the top or bottom margin printed beside the page number is a running header.
        val folios = layout.lines.filter { DocumentStats.edgeZone(it, layout.height) != null && Patterns.pageNumber.matches(it.text.trim()) }
        fun besideFolio(l: TextLine) = DocumentStats.edgeZone(l, layout.height) != null &&
            folios.any { f -> f !== l && abs(f.baseline - l.baseline) <= 1.5f } && l.fontSize <= profile.bodySize * 1.05f
        fun furniture(l: TextLine) = profile.isRunningHeaderOrFooter(l, layout.height, layout.index) || besideFolio(l)
        // A piece that looks like a running header ("Chapter 8", "5.3") but shares its row with
        // ordinary text (a contents entry at the top of the page) belongs to that text.
        fun besideText(l: TextLine) = layout.lines.any { o ->
            o !== l && abs(o.baseline - l.baseline) <= 1.5f && abs(o.fontSize - l.fontSize) <= l.fontSize * 0.2f && !furniture(o)
        }
        val normalized = layout.lines
            .mapNotNull { l ->
                normalize(l, layout.fromOcr)?.takeIf {
                    // the word "chapter" repeats on every chapter opener but is not a running header
                    !furniture(l) || besideText(l) || CHAPTER_WORD.matches(it.text)
                }
            }
        // A printed table of contents (also its continuation pages without the "Contents" title).
        val contentsPage = normalized.any { Patterns.contentsTitle.matches(it.text) } || looksLikeContents(normalized)
        val lines = if (contentsPage) joinContentsRows(normalized) else chapterOpener(mergeSectionNumbers(mergeRows(normalized)))
        val items = mutableListOf<Item>()
        lines.forEach { items += LineItem(it) }
        layout.figures.forEach { items += FigureItem(it) }
        layout.tables.forEach { items += TableItem(it) }
        if (items.isEmpty()) return emptyList()

        // A printed table of contents without dot leaders: titles with page numbers at the right.
        val contents = contentsLines(lines, layout.width, contentsPage)

        val placed = ReadingOrder.order(items, { it.bounds }, { it.em }, shortLineColumns = contentsPage)
        val leading = estimateLeading(placed.mapNotNull { (it.item as? LineItem)?.line })
        val main = run(placed.filter { !it.aside }, layout, contents, contentsPage, leading, aside = false)
        val side = placed.filter { it.aside }
        if (side.isEmpty()) return groupOptions(labelsFirst(main, leading).map { it.first })
        // Margin notes, figures and tables go after the paragraph printed beside them, so the
        // text of the main column is never interrupted.
        val asides = run(side, layout, contents, contentsPage, leading, aside = true)
        val out = main.toMutableList()
        var pos = 0
        for ((el, top) in asides) {
            while (pos < out.size && out[pos].second < top - leading * 0.8f) pos++
            // a figure printed level with a section heading belongs under it
            while (pos < out.size && out[pos].first is StructuredElement.Heading && out[pos].second <= top + leading * 1.5f) pos++
            // never inside a callout box of the main column
            var depth = 0
            for (k in 0 until pos) {
                val b = out[k].first as? StructuredElement.Box ?: continue
                depth += if (b.start) 1 else -1
            }
            while (depth > 0 && pos < out.size) {
                val b = out[pos].first as? StructuredElement.Box
                if (b != null) depth += if (b.start) 1 else -1
                pos++
            }
            out.add(pos, el to top)
            pos++
        }
        return groupOptions(labelsFirst(out, leading).map { it.first })
    }

    /**
     * "Example 2.14" printed level with the first line of its question (in the margin, or on a
     * banner beside it) comes before the question.
     */
    private fun labelsFirst(elements: List<Pair<StructuredElement, Float>>, leading: Float): List<Pair<StructuredElement, Float>> {
        val out = elements.toMutableList()
        for (i in 1 until out.size) {
            val (h, top) = out[i]
            if (h !is StructuredElement.Heading || !NUMBERED_LABEL.matches(h.text)) continue
            var j = i
            while (j > 0 && out[j - 1].first !is StructuredElement.Heading && out[j - 1].first !is StructuredElement.Box &&
                out[j - 1].second >= top - leading * 0.8f
            ) j--
            if (j < i) out.add(j, out.removeAt(i))
        }
        return out
    }

    /** Structures items of one reading sequence; returns the elements with the top of their source. */
    private fun run(
        placed: List<ReadingOrder.Placed<Item>>,
        layout: PageLayout,
        contents: Map<NLine, StructuredElement.Paragraph?>,
        contentsPage: Boolean,
        leading: Float,
        aside: Boolean,
    ): List<Pair<StructuredElement, Float>> {
        val out = mutableListOf<StructuredElement>()
        val tops = mutableListOf<Float>()
        val page = layout.index
        var current: Builder? = null
        var lastTop = 0f
        // Where the lines of a column end: the column's area may reach past its text.
        val rights = HashMap<Rect, Float>()
        fun textRight(col: Rect): Float = rights.getOrPut(col) {
            val ends = placed.filter { it.column == col }.mapNotNull { (it.item as? LineItem)?.line }
                .filter { it.src.wordCount >= 5 }.map { it.bounds.right }.sorted()
            if (ends.size < 3) col.right else min(col.right, ends[ends.size * 3 / 4])
        }

        fun record() {
            while (tops.size < out.size) tops += lastTop
        }

        fun flush() {
            val b = current ?: return
            current = null
            record()
            emit(b, page, out, aside)
            while (tops.size < out.size) tops += b.lines.first().bounds.top
        }

        // Callout boxes: everything printed inside one is marked as its content.
        var box: BoxRegion? = null
        fun boxOf(item: Item): BoxRegion? {
            val b = item.bounds
            return layout.boxes.firstOrNull { it.bounds.outset(1f).contains(b.centerX, b.centerY) }
        }

        for ((i, p) in placed.withIndex()) {
            record()
            lastTop = p.bounds.top
            val itemBox = boxOf(p.item)
            if (itemBox !== box) {
                flush()
                box?.let { out += StructuredElement.Box(page, false, it) }
                itemBox?.let { out += StructuredElement.Box(page, true, it) }
                box = itemBox
            }
            when (val item = p.item) {
                is FigureItem -> {
                    flush(); out += StructuredElement.Figure(page, item.figure)
                }
                is TableItem -> {
                    flush()
                    val entries = contentsFromTable(item.table, contentsPage)
                    if (entries != null) entries.forEach { out += it.copy(pageIndex = page) } else out += StructuredElement.Table(page, item.table)
                }
                is LineItem -> {
                    val line = item.line
                    if (line in contents) {
                        val entry = contents[line]
                        if (entry != null) {
                            flush(); out += entry.copy(pageIndex = page)
                        }
                        continue
                    }
                    val toc = tocEntry(line)
                    if (toc != null) {
                        flush(); out += toc.copy(pageIndex = page)
                        continue
                    }
                    val options = if (line.src.chunks.size >= 2) splitOptions(line.text) else emptyList()
                    if (options.size >= 2) {
                        flush()
                        options.forEach { (m, t) -> out += StructuredElement.ListItem(page, m, t, emptyList(), 1) }
                        continue
                    }
                    // A formula set on its own line (centred or indented): one block of its own.
                    if (MathText.isFormula(line.text) && displayed(line, p.column)) {
                        flush()
                        out += StructuredElement.Paragraph(page, line.text, line.styles, ParagraphKind.FORMULA, false, line.size)
                        continue
                    }
                    val prevLine = (placed.getOrNull(i - 1)?.item as? LineItem)?.line
                    val nextLine = (placed.getOrNull(i + 1)?.item as? LineItem)?.line
                    val level = headingLevel(line, prevLine, nextLine, leading)
                    if (level != null) {
                        flush()
                        val prev = out.lastOrNull()
                        val close = prev is StructuredElement.Heading && prevLine != null && line.bounds.top - prevLine.bounds.bottom < line.size * 0.8f
                        // "1.1 ELECTRICAL MACHINES, / TRANSFORMERS, AND DAILY LIFE": the second line of a
                        // numbered title carries no number (and may look like a lower level).
                        val wrapped = close && prevLine != null && (prev as StructuredElement.Heading).text.endsWith(prevLine.text.trim()) &&
                            abs(prevLine.size - line.size) < 0.5f && Patterns.numberedHeading.containsMatchIn(prev.text) &&
                            !Patterns.numberedHeading.containsMatchIn(line.text) && !Patterns.endsSentence(prev.text)
                        if (close && prev is StructuredElement.Heading && (wrapped || prev.level == level && headingLevel(prevLine!!, null, null, leading) == level)) {
                            out[out.size - 1] = prev.copy(text = joinHeading(prev.text, line.text))
                        } else {
                            out += StructuredElement.Heading(page, level, withoutFootnoteMark(line))
                        }
                        continue
                    }
                    val b = current
                    // "a. In other words…" in the middle of a sentence is a variable, not an enumerator.
                    val midSentence = b != null && b.marker == null && b.column == p.column && !Patterns.endsSentence(b.last.text) &&
                        b.last.bounds.right >= textRight(p.column) - b.last.size * 2.5f
                    // (a bold exercise number always starts an exercise)
                    val marker = Patterns.listMarker.find(line.text)?.takeIf { !midSentence } ?: problemNumber(line)
                    if (b != null && marker == null && continues(b, line, p.column, leading, textRight(p.column))) {
                        val sameColumn = b.column == p.column
                        b.column = p.column // a paragraph may continue into the next column
                        append(b, line, lineBreak = sameColumn && isLineBreak(b, line, p.column, textRight(p.column)))
                        continue
                    }
                    flush()
                    current = if (marker != null) {
                        val m = marker.groupValues[1]
                        val markerEnd = marker.range.last + 1
                        val nb = Builder(line, p.column, m, line.bounds.left)
                        nb.text.append(line.text.substring(markerEnd))
                        nb.styles += shiftStyles(line.styles, markerEnd, line.text.length, -markerEnd)
                        nb
                    } else {
                        Builder(line, p.column, null, line.bounds.left).also {
                            it.text.append(line.text)
                            it.styles += line.styles
                        }
                    }
                }
            }
        }
        flush()
        box?.let { out += StructuredElement.Box(page, false, it) }
        record()
        return out.zip(tops)
    }

    // ---------------------------------------------------------------- rows of short items

    /**
     * "প্রদত্ত : N(H) = 1920 পিক্সেল    W = 12 মিটার": a row of short pieces printed with wide
     * gaps is one line of text, not columns. Pieces are joined (with an em space) when the row
     * is well inside the text width, no piece looks like a list marker or a page number, and
     * the gaps are modest. Rows of long pieces (real columns) and contents entries stay apart.
     */
    private fun mergeRows(lines: List<NLine>): List<NLine> {
        if (lines.size < 2) return lines
        val prose = lines.filter { it.src.wordCount >= 6 }
        if (prose.isEmpty()) return lines
        val textWidth = prose.maxOf { it.bounds.width }
        val bodySize = prose.map { it.size }.sorted()[prose.size / 2]
        val used = HashSet<NLine>()
        val out = ArrayList<NLine>(lines.size)
        for (a in lines) {
            if (a in used) continue
            val row = lines.filter { b ->
                b === a || (b !in used && abs(b.src.baseline - a.src.baseline) <= a.size * 0.25f &&
                    b.size / a.size in 0.85f..1.18f && b.bounds.horizontalOverlap(a.bounds) <= 0f)
            }.sortedBy { it.bounds.left }
            if (row.size !in 2..4 || !isShortRow(row, textWidth, bodySize)) {
                out += a
                continue
            }
            used += row
            val text = StringBuilder()
            val styles = mutableListOf<InlineStyle>()
            for (piece in row) {
                if (text.isNotEmpty()) text.append(" \u2003 ")
                val offset = text.length
                text.append(piece.text)
                styles += shiftStyles(piece.styles, 0, piece.text.length, offset)
            }
            val first = row.first().src
            val bounds = Rect.bounding(row.map { it.bounds })!!
            out += NLine(
                TextLine(
                    text.toString(), emptyList(), bounds, first.baseline, first.fontSize, first.boldRatio, first.italicRatio,
                    first.fontName,
                ),
                text.toString(), styles,
            )
        }
        return out.sortedWith(compareBy({ lines.indexOf(it) }))
    }

    /** Most lines are contents entries: "1.2 Systems of Units 4", "Chapter 3", "3.1". */
    private fun looksLikeContents(lines: List<NLine>): Boolean {
        val text = lines.filter { it.text.isNotBlank() }
        if (text.size < 12) return false
        val entries = text.count { l ->
            INLINE_PAGE.matches(l.text) || SECTION_ONLY.matches(l.text) || CONTENTS_LABEL.matches(l.text) ||
                Patterns.numberedHeading.containsMatchIn(l.text) && l.src.wordCount <= 8
        }
        val prose = text.count { it.src.wordCount >= 8 }
        return entries * 10 >= text.size * 6 && prose * 10 <= text.size
    }

    /**
     * Contents entries are printed as pieces: "3.1" and "Introduction 82" on one baseline,
     * "Chapter 3" beside "Methods of Analysis 81", and long titles wrapped onto a second line.
     */
    private fun joinContentsRows(lines: List<NLine>): List<NLine> {
        val dropped = HashSet<NLine>()
        val replaced = HashMap<NLine, NLine>()
        fun cur(l: NLine) = replaced[l] ?: l
        for (a in lines.sortedBy { it.bounds.left }) {
            if (a in dropped || !(SECTION_ONLY.matches(a.text) || CONTENTS_LABEL.matches(a.text) || a.text.trim() == "†")) continue
            val b = lines.filter { t ->
                t !== a && t !in dropped && abs(t.src.baseline - a.src.baseline) <= a.size * 0.35f &&
                    t.bounds.left > a.bounds.right && t.bounds.left - a.bounds.right <= a.size * 8f
            }.minByOrNull { it.bounds.left } ?: continue
            val bb = cur(b)
            val sep = if (a.text.trim() == "†") "" else " "
            val prefix = a.text.trim() + sep
            replaced[b] = joined(a, bb, prefix + bb.text, shiftStyles(bb.styles, 0, bb.text.length, prefix.length), bb.src)
            dropped += a
        }
        // a dagger printed raised before a title ("†Applications") is a mark, not an entry
        val merged = lines.filter { it !in dropped && it.text.trim() != "†" }.map { cur(it) }
        // Wrapped titles: a line without a page number continued on the next line.
        val out = merged.toMutableList()
        var k = 0
        val byTop = out.sortedBy { it.bounds.top }.toMutableList()
        while (k < byTop.size) {
            val a = byTop[k]
            if (INLINE_PAGE.matches(a.text) || Patterns.contentsTitle.matches(a.text) || a.text.count { it.isLetter() } < 2) {
                k++
                continue
            }
            val b = byTop.firstOrNull { t ->
                t !== a && t.bounds.top >= a.bounds.top + a.size * 0.5f && t.bounds.top - a.bounds.bottom <= a.size * 0.8f &&
                    t.bounds.left >= a.bounds.left - 1f && t.bounds.left <= max(a.bounds.left + a.size * 4f, a.bounds.right - a.size * 2f) &&
                    !Patterns.numberedHeading.containsMatchIn(t.text) && !CONTENTS_LABEL.containsMatchIn(t.text) &&
                    INLINE_PAGE.matches(t.text) && abs(t.size - a.size) < 1f
            }
            if (b == null) {
                k++
                continue
            }
            val text = if (a.text.endsWith("-")) a.text.dropLast(1) + b.text else a.text + " " + b.text
            val offset = text.length - b.text.length
            val j = joined(a, b, text, a.styles + shiftStyles(b.styles, 0, b.text.length, offset), a.src)
            out[out.indexOf(a)] = j
            out.remove(b)
            byTop[k] = j
            byTop.remove(b)
        }
        return out
    }

    /** A line made of [text] with the geometry of [a] and [b] together. */
    private fun joined(a: NLine, b: NLine, text: String, styles: List<InlineStyle>, src: TextLine = b.src): NLine {
        val bounds = a.bounds.union(b.bounds)
        return NLine(
            TextLine(text, styles, bounds, src.baseline, src.fontSize, src.boldRatio, src.italicRatio, src.fontName),
            text, styles,
        )
    }

    /**
     * "1.2" printed in a coloured box and "Systems of Units" beside it, with a wide gap: one
     * section heading "1.2 Systems of Units".
     */
    private fun mergeSectionNumbers(lines: List<NLine>): List<NLine> {
        val numbers = lines.filter { SECTION_ONLY.matches(it.text) && (it.src.isBold || it.size >= profile.bodySize * 1.1f) }
        val equationNumbers = lines.filter { EQUATION_NUMBER.matches(it.text) }
        if (numbers.isEmpty() && equationNumbers.isEmpty()) return lines
        val replaced = HashMap<NLine, NLine>()
        val dropped = HashSet<NLine>()
        // "(1.4)" at the right margin belongs to the formula on its baseline.
        for (n in equationNumbers) {
            val formula = lines.filter { t ->
                t !== n && t !in dropped && t !in replaced && abs(t.src.baseline - n.src.baseline) <= n.size * 0.3f &&
                    t.bounds.right < n.bounds.left && t.text.any { it.isLetterOrDigit() }
            }.maxByOrNull { it.bounds.right } ?: continue
            replaced[formula] = joined(formula, n, formula.text + " \u2003 " + n.text.trim(), formula.styles, formula.src)
            dropped += n
        }
        for (n in numbers) {
            val title = lines.filter { t ->
                t !== n && t !in dropped && t !in replaced && abs(t.src.baseline - n.src.baseline) <= n.size * 0.3f &&
                    t.bounds.left > n.bounds.right && t.bounds.left - n.bounds.right <= n.size * 6f &&
                    t.size / n.size in 0.8f..1.25f && t.text.count { it.isLetter() } >= 2
            }.minByOrNull { it.bounds.left } ?: continue
            val prefix = n.text.trim().trimEnd('.') + " "
            val styles = shiftStyles(title.styles, 0, title.text.length, prefix.length)
            replaced[title] = joined(n, title, prefix + title.text, styles)
            dropped += n
        }
        return lines.filter { it !in dropped }.map { replaced[it] ?: it }
    }

    /**
     * Chapter openers print the word "chapter" (often letter-spaced) apart from a big chapter
     * number and the title ("Basic Concepts   1"): one heading "Chapter 1: Basic Concepts".
     */
    private fun chapterOpener(lines: List<NLine>): List<NLine> {
        // "CHAPT1E R": a big chapter number printed over the letter-spaced word
        fun numberInWord(l: NLine) = l.text.filter { it.isDigit() }.takeIf {
            it.length in 1..2 && l.text.filter { c -> c.isLetter() }.equals("chapter", ignoreCase = true) &&
                l.text.indexOfFirst { c -> c.isDigit() } < l.text.indexOfLast { c -> c.isLetter() }
        }
        val word = lines.firstOrNull { CHAPTER_WORD.matches(it.text) || numberInWord(it) != null } ?: return lines
        val inWord = numberInWord(word)
        val big = lines.filter { it !== word && it.size >= profile.bodySize * 1.6f && abs(it.bounds.top - word.bounds.top) <= word.size * 14f }
        val numberLine = big.firstOrNull { BARE_NUMBER.matches(it.text) }
        // The title, possibly over two lines ("Capacitors and / Inductors").
        val first = big.filter { it !== numberLine && it.text.count { c -> c.isLetter() } >= 3 }.minByOrNull { it.bounds.top } ?: return lines
        val titleLines = mutableListOf(first)
        for (l in big.filter { it !== numberLine && it !== first }.sortedBy { it.bounds.top }) {
            val last = titleLines.last()
            if (abs(l.size - first.size) > first.size * 0.1f || l.bounds.top - last.bounds.bottom > first.size * 1.2f || l.bounds.top < last.bounds.top) continue
            titleLines += l
        }
        var title = titleLines.joinToString(" ") { it.text.trim() }
        val number = if (inWord != null) {
            inWord
        } else if (numberLine != null) {
            numberLine.text.trim()
        } else {
            // the big chapter number printed on the title's baseline
            val m = NUMBER_TOKEN.find(title)
            if (m != null) title = (title.substring(0, m.range.first) + " " + title.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim()
            m?.groupValues?.get(1)
        }
        val label = word.text.filter { !it.isDigit() && !it.isWhitespace() }.lowercase().replaceFirstChar { it.uppercase() }
        // (the number may be part of the artwork: then the title alone)
        val text = if (number != null) "$label $number: $title" else title
        val merged = joined(word, first, text, emptyList(), first.src)
        val drop = titleLines.drop(1).toSet() + listOfNotNull(word, numberLine)
        return lines.filter { it !in drop }.map { if (it === first) merged else it }
    }

    /** A bold exercise number ("2.13 For the circuit…") hangs like a list marker. */
    private fun problemNumber(line: NLine): MatchResult? {
        val m = PROBLEM_MARKER.find(line.text) ?: return null
        val bold = line.styles.any { it.bold && it.start == 0 && it.end >= m.groupValues[1].length }
        return if (bold || line.src.boldRatio >= 0.99f) m else null
    }

    private fun isShortRow(row: List<NLine>, textWidth: Float, bodySize: Float): Boolean {
        val em = row.first().size
        // Headings and chapter numbers (larger than the text) are never joined.
        if (row.any { it.size > bodySize * 1.12f || it.text.none { c -> c.isLetter() } }) return false
        if (row.last().bounds.right - row.first().bounds.left > textWidth * 0.85f) return false
        for ((k, piece) in row.withIndex()) {
            if (piece.bounds.width > textWidth * 0.5f) return false
            if (k > 0) {
                val gap = piece.bounds.left - row[k - 1].bounds.right
                if (gap > em * 8f) return false
                // a list marker ("(খ) ...") or a page number starts the next item, not a continuation
                if (Patterns.listMarker.find(piece.text)?.range?.first == 0) return false
                if (piece.text.none { it.isLetter() }) return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- contents and options

    /** A printed table-of-contents line, kept as a "title\tpage" entry instead of a heading. */
    private fun tocEntry(line: NLine): StructuredElement.Paragraph? {
        val m = Patterns.tocEntry.matchEntire(line.text) ?: return null
        val title = m.groupValues[1].trimEnd('.', ' ', '·', '…', '_')
        if (title.count { it.isLetter() || BanglaText.isMatra(it) } < 2) return null
        val main = line.src.isBold || line.size > profile.bodySize * 1.05f
        val styles = if (main) listOf(InlineStyle(0, title.length, InlineStyle.BOLD)) else emptyList()
        return StructuredElement.Paragraph(0, title + "\t" + m.groupValues[2], styles, ParagraphKind.TOC, false, line.size)
    }

    /**
     * Contents lines without dot leaders: a title and, on the same baseline, a page number at
     * the right (as a separate line or as the last chunk). Returns entries keyed by title line;
     * the number lines map to null (consumed). Empty unless the page looks like contents.
     */
    private fun contentsLines(lines: List<NLine>, pageWidth: Float, contentsPage: Boolean): Map<NLine, StructuredElement.Paragraph?> {
        class Pair2(val title: NLine, val titleText: String, val number: NLine?, val page: String)
        val pairs = mutableListOf<Pair2>()
        val used = HashSet<NLine>()
        for (n in lines) {
            if (!Patterns.contentsPage.matches(n.text) || n.bounds.left < pageWidth * 0.45f) continue
            val title = lines.filter { t ->
                t !== n && t !in used && abs(t.src.baseline - n.src.baseline) <= n.size * 0.35f &&
                    t.bounds.right < n.bounds.left && !Patterns.contentsPage.matches(t.text) &&
                    t.text.count { it.isLetter() || BanglaText.isMatra(it) } >= 2
            }.maxByOrNull { it.bounds.right } ?: continue
            used += title
            used += n
            pairs += Pair2(title, title.text, n, n.text.trim())
        }
        for (l in lines) {
            if (l in used || l.src.chunks.size < 2) continue
            val last = l.src.chunks.last()
            val m = TRAILING_PAGE.matchEntire(l.text) ?: continue
            if (last.left < pageWidth * 0.45f || last.left - l.src.chunks[l.src.chunks.size - 2].right < l.size * 1.5f) continue
            pairs += Pair2(l, m.groupValues[1], null, m.groupValues[2])
        }
        if (contentsPage) {
            // "1.2 Systems of Units 4": the page number right after the title.
            for (l in lines) {
                if (l in used || pairs.any { it.title === l }) continue
                val m = INLINE_PAGE.matchEntire(l.text)
                if (m != null) {
                    pairs += Pair2(l, m.groupValues[1], null, m.groupValues[2])
                } else if (SUBSECTION.containsMatchIn(l.text) && l.src.wordCount <= 12) {
                    pairs += Pair2(l, l.text, null, "") // "1.7.1 TV Picture Tube": listed without a page
                }
            }
        }
        val candidates = lines.count { !Patterns.contentsTitle.matches(it.text) && it.text.isNotBlank() }
        if (pairs.size < 3 || (!contentsPage && (pairs.size < 5 || pairs.size * 2 < candidates))) return emptyMap()
        val minLeft = pairs.minOf { it.title.bounds.left }
        val indented = pairs.any { it.title.bounds.left > minLeft + it.title.size }
        val out = HashMap<NLine, StructuredElement.Paragraph?>()
        for (p in pairs) {
            val t = p.title
            val section = Patterns.numberedHeading.find(p.titleText)?.groupValues?.get(1)?.contains('.') == true
            val main = !section && (t.src.isBold || t.size > profile.bodySize * 1.05f || Patterns.chapter.containsMatchIn(p.titleText) ||
                (indented && t.bounds.left <= minLeft + t.size * 0.5f))
            out[t] = contentsEntry(p.titleText.trimEnd('.', ' ', '·', '…', '_'), p.page, main, t.size)
            p.number?.let { out[it] = null }
        }
        return out
    }

    /** A table whose last column holds page numbers, on a contents page: contents entries. */
    private fun contentsFromTable(t: TableRegion, contentsPage: Boolean): List<StructuredElement.Paragraph>? {
        if (t.columnCount < 2 || t.rows.size < 3) return null
        val parsed = t.rows.mapIndexed { r, row ->
            val k = row.indexOfLast { it.isNotBlank() }
            val page = row.getOrNull(k)?.takeIf { k > 0 && Patterns.contentsPage.matches(it) }?.trim()
            val title = row.take(if (page != null) k else row.size).filter { it.isNotBlank() }.joinToString(" ").trim()
            Triple(r, title, page)
        }.filter { (r, title, page) -> (r >= t.headerRows || page != null) && title.isNotEmpty() }
        val numbered = parsed.filter { it.third != null }
        if (numbered.size < 3 || numbered.size * 10 < parsed.size * 7) return null
        val pages = numbered.mapNotNull { pageNumber(it.third!!) }
        val rising = pages.zipWithNext().count { (a, b) -> b >= a }
        // Page numbers grow through the book; a column of counts or prices does not.
        val growing = pages.size >= 3 && pages.last() > pages.first() && pages.toSet().size * 10 >= pages.size * 6
        if (!growing || (!contentsPage && (pages.size < 4 || rising * 10 < (pages.size - 1) * 8))) return null
        val hasSections = parsed.any { SECTION_NUMBER.containsMatchIn(it.second) }
        return parsed.map { (_, title, page) ->
            val main = Patterns.chapter.containsMatchIn(title) || (hasSections && !SECTION_NUMBER.containsMatchIn(title)) || !hasSections
            contentsEntry(title, page ?: "", main, profile.bodySize)
        }
    }

    private fun contentsEntry(title: String, page: String, main: Boolean, size: Float): StructuredElement.Paragraph {
        val styles = if (main) listOf(InlineStyle(0, title.length, InlineStyle.BOLD)) else emptyList()
        return StructuredElement.Paragraph(0, title + "\t" + page, styles, ParagraphKind.TOC, false, size)
    }

    /** Runs of list items lettered (ক), (খ), (গ)… or (a), (b)… become one block of answer options. */
    private fun groupOptions(elements: List<StructuredElement>): List<StructuredElement> {
        val out = mutableListOf<StructuredElement>()
        var i = 0
        while (i < elements.size) {
            val first = elements[i] as? StructuredElement.ListItem
            val family = first?.let { optionLetter(it.marker) }
            if (first != null && family != null && family.second == 0) {
                var j = i + 1
                while (j < elements.size) {
                    val next = (elements[j] as? StructuredElement.ListItem)?.let { optionLetter(it.marker) } ?: break
                    if (next.first != family.first || next.second != j - i) break
                    j++
                }
                if (j - i >= 2) {
                    val items = elements.subList(i, j).map { it as StructuredElement.ListItem }
                    val text = items.joinToString("\n") { it.marker + "\t" + it.text.replace('\n', ' ') }
                    out += StructuredElement.Paragraph(first.pageIndex, text, emptyList(), ParagraphKind.OPTIONS, false, 0f)
                    i = j
                    continue
                }
            }
            out += elements[i]
            i++
        }
        return out
    }

    /** (alphabet, position) of an option marker such as "(খ)", "b)" or "(C)". */
    private fun optionLetter(marker: String): Pair<String, Int>? {
        val core = marker.trim().removePrefix("(").removeSuffix(")").removeSuffix(".")
        if (core.length != 1) return null
        for (alphabet in OPTION_ALPHABETS) {
            val k = alphabet.indexOf(core[0])
            if (k >= 0) return alphabet to k
        }
        return null
    }

    private companion object {
        val OPTION_ALPHABETS = listOf("কখগঘঙচ", "abcdef", "ABCDEF")
        val TRAILING_PAGE = Regex("^(.*?\\S)\\s+([0-9০-৯]{1,4})$")

        /** A section number printed apart from its title: "1.2", "12.10.3". */
        val SECTION_ONLY = Regex("^\\s*[0-9০-৯]{1,2}(?:\\.[0-9০-৯]{1,2}){1,3}\\.?\\s*$")
        val CHAPTER_WORD = Regex("^\\s*(?:chapter|অধ্যায়|অধ্যায়)\\s*$", RegexOption.IGNORE_CASE)
        val BARE_NUMBER = Regex("^\\s*(?:[0-9০-৯]{1,3}|[IVXL]{1,6})\\s*$")
        val NUMBER_TOKEN = Regex("(?<=^|\\s)([0-9০-৯]{1,3})(?=\\s|$)")
        private val BULLET_START = Regex("^\\s*[•●○◦▪■♦◆►▸]")
        val SECTION_TITLE = Regex("^\\s*(?:section|sections|sec\\.)\\s+[0-9]{1,2}(?:\\.[0-9]{1,2})*(?:\\s*(?:and|–|-|to)\\s*[0-9]{1,2}(?:\\.[0-9]{1,2})*)?\\s+\\p{Lu}[^.]*$", RegexOption.IGNORE_CASE)
        val NUMBERED_LABEL = Regex(
            "^\\s*(?:example|worked example|practice problem|exercise|problem|উদাহরণ|অনুশীলনী)\\s*[0-9০-৯]+(?:[.\\-][0-9০-৯]+)*\\s*[:.ঃ]?\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val EQUATION_NUMBER = Regex("^\\s*\\([0-9০-৯]{1,2}(?:\\.[0-9০-৯]{1,3}){1,2}[a-z]?\\)\\s*$")
        /** "Systems of Units 4", "Preface xiii" — not an index entry ("Ampere, 5"). */
        val INLINE_PAGE = Regex("^(.*\\p{L}.*?[^,\\s])\\s+([0-9০-৯]{1,4}|[ivxlc]{1,7})$")
        val SUBSECTION = Regex("^\\s*[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}\\s+\\S")
        val CONTENTS_LABEL = Regex("^\\s*(?:chapter|part|appendix|অধ্যায়|অধ্যায়|ভাগ)\\s+[0-9A-Z০-৯]{1,3}\\s*$", RegexOption.IGNORE_CASE)
        val PROBLEM_MARKER = Regex("^\\s*([0-9০-৯]{1,2}\\.[0-9০-৯]{1,3})\\s+(?=\\S)")
        val LETTER_SPACED = Regex("^(?:\\p{L} ){3,}\\p{L}$")

        /** "1.2 Systems of Units", "১.৩ ..." : a numbered section (not a chapter). */
        val SECTION_NUMBER = Regex("^\\s*[0-9০-৯]{1,2}[.][0-9০-৯]{1,2}")

        fun pageNumber(s: String): Int? {
            val digits = s.trim().map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")
            return digits.toIntOrNull()
        }
    }

    /** "(ক) তিন     (খ) চার" printed side by side: one list item per option. */
    private fun splitOptions(text: String): List<Pair<String, String>> {
        val first = Patterns.listMarker.find(text) ?: return emptyList()
        val marker = first.groupValues[1]
        if (!marker.endsWith(")")) return emptyList()
        val rest = Patterns.inlineEnumerator.findAll(text, first.range.last + 1).toList()
        if (rest.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        var m = marker
        var from = first.range.last + 1
        for (r in rest) {
            out += m to text.substring(from, r.range.first).trim()
            m = r.groupValues[1]
            from = r.range.last + 1
        }
        out += m to text.substring(from).trim()
        return if (out.all { it.second.isNotEmpty() }) out else emptyList()
    }

    // ---------------------------------------------------------------- normalisation

    private fun normalize(line: TextLine, ocr: Boolean): NLine? {
        val text = line.text
        if (text.isBlank()) return null
        // Normalise per style run so that style ranges stay correct when lengths change.
        val boundaries = sortedSetOf(0, text.length)
        line.styles.forEach { boundaries += it.start.coerceIn(0, text.length); boundaries += it.end.coerceIn(0, text.length) }
        val cuts = boundaries.toList()
        val sb = StringBuilder()
        val styles = mutableListOf<InlineStyle>()
        for (k in 0 until cuts.size - 1) {
            val a = cuts[k]
            val b = cuts[k + 1]
            if (b <= a) continue
            var part = text.substring(a, b)
            if (!ocr && profile.visualOrder) part = BanglaText.visualToLogical(part)
            part = TextCleaner.finish(Normalizer.normalize(TextCleaner.clean(part), Normalizer.Form.NFC))
            val flags = line.styles.filter { it.start <= a && it.end >= b }.fold(0) { acc, s -> acc or s.flags }
            val start = sb.length
            sb.append(part)
            if (flags != 0 && sb.length > start) styles += InlineStyle(start, sb.length, flags)
        }
        val collapsed = TextCleaner.collapseSpaces(sb.toString())
        if (collapsed.isBlank()) return null
        // collapseSpaces may shorten the text; clamp styles defensively.
        val trimmedStart = collapsed.length - collapsed.trimStart().length
        // "¢" and the letter after it may sit in different style runs.
        val finalText = TextCleaner.finish(collapsed.trim())
        val finalStyles = styles.mapNotNull {
            val s = (it.start - trimmedStart).coerceIn(0, finalText.length)
            val e = (it.end - trimmedStart).coerceIn(0, finalText.length)
            if (e > s) InlineStyle(s, e, it.flags) else null
        }
        // "c h a p t e r": a letter-spaced word.
        if (LETTER_SPACED.matches(finalText)) {
            val word = finalText.replace(" ", "")
            val flags = finalStyles.firstOrNull()?.flags ?: 0
            return NLine(line, word, if (flags != 0) listOf(InlineStyle(0, word.length, flags)) else emptyList())
        }
        return NLine(line, finalText, if (collapsed.length == sb.length) finalStyles else emptyList())
    }

    // ---------------------------------------------------------------- headings

    private fun headingLevel(line: NLine, prev: NLine?, next: NLine?, leading: Float): Int? {
        val text = line.text
        val words = text.split(' ').count { it.isNotBlank() }
        if (text.length < 2 || words > 20 || text.length > 160) return null
        if (Patterns.pageNumber.matches(text)) return null
        if (text.count { it.isLetter() || BanglaText.isMatra(it) } < 2) return null
        val ratio = line.size / profile.bodySize
        val bold = line.src.isBold
        val gapAbove = prev?.let { line.bounds.top - it.bounds.bottom } ?: Float.MAX_VALUE
        val gapBelow = next?.let { it.bounds.top - line.bounds.bottom } ?: Float.MAX_VALUE
        val isolated = gapAbove > leading * 0.6f || gapBelow > leading * 0.6f ||
            (next != null && !next.src.isBold && next.size <= line.size)

        if (MathText.isFormula(text)) return null
        val chapter = Patterns.chapter.find(text)
        if (chapter != null && words <= 10 && !(Patterns.endsSentence(text) && !text.endsWith(':'))) {
            // "অধ্যায় ৩", "Chapter IV" — a bare word ("ভাগ করে…", "Part of…") needs a heading's size.
            val numbered = chapter.value.trim().split(Regex("\\s+")).size >= 2
            if ((numbered && (ratio >= 1.05f || bold || words <= 4)) || ratio >= 1.3f) return 1
        }
        if (Patterns.frontMatter.matches(text) && (ratio >= 1.08f || bold)) return 1
        // "Section 2.4 Kirchhoff's Laws" over the exercises of that section
        if (SECTION_TITLE.matches(text) && words <= 12 && isolated) return 4
        // "Example 1.4" on its own line (often on a coloured banner): a minor heading.
        if (Patterns.exampleLabel.matches(text) && (bold || ratio >= 1.0f || isolated)) {
            return profile.headingLevelForSize(line.size)?.coerceAtLeast(3) ?: 4
        }
        val bySize = profile.headingLevelForSize(line.size)
        // "1. Each quantity has the proper unit of measurement as defined by / the equation.":
        // a numbered point set in bold that runs on into the next line is a list item.
        val runsOn = next != null && gapBelow <= leading * 0.6f && abs(next.size - line.size) < 0.5f &&
            next.src.isBold == line.src.isBold && next.text.firstOrNull()?.isLowerCase() == true
        val numbered = Patterns.numberedHeading.find(text)?.takeUnless { runsOn && !it.groupValues[1].contains('.') }
        if (runsOn && numbered == null && Patterns.numberedHeading.containsMatchIn(text)) return null
        // so is a bulleted one ("• Develop skills in interpreting and establishing the / Bode…")
        if (BULLET_START.containsMatchIn(text) && next != null && gapBelow <= leading * 0.6f && abs(next.size - line.size) < 0.5f &&
            next.src.isBold == line.src.isBold && !Patterns.endsSentence(text)
        ) {
            return null
        }
        if (bySize != null && words <= 16 && !(text.endsWith('.') && words > 10)) {
            // "1.2 Systems of Units" is a section, "7.9.1 Delay Circuits" a subsection, whatever their size.
            val depth = numbered?.groupValues?.get(1)?.count { it == '.' } ?: 0
            return if (depth >= 1) (1 + depth).coerceAtMost(4) else bySize
        }
        // (an exercise or an answer with its values, "12.2 If … Van = 100∠−20°", is not a title)
        if (numbered != null && (bold || ratio >= 1.08f) && words <= 14 && !Patterns.endsSentence(text) && text.none { it in "=≈∠" }) {
            // (the same levels as above: "1.2" a section, "1.2.3" a subsection)
            val depth = numbered.groupValues[1].count { it == '.' }
            return if (depth >= 1) (1 + depth).coerceAtMost(4) else 2
        }
        // A bold line between paragraphs at body size: minor heading.
        // A worked formula ("I = 0.58 × 30 = 17.4 A") set in bold is a result, not a heading.
        val formula = text.any { it in "=≈≠<>" } && text.any { it.isDigit() }
        if (bold && !formula && ratio >= 0.95f && words <= 12 && isolated && !text.endsWith(',') &&
            (!Patterns.endsSentence(text) || text.endsWith(':')) &&
            !(prev != null && prev.src.isBold && gapAbove < leading * 0.6f)
        ) {
            return 4
        }
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it in 'A'..'Z' } && words <= 8 && ratio >= 1.0f && isolated) return 4
        return null
    }

    /** "Problems¹": the footnote mark printed after a heading is not part of its title. */
    private fun withoutFootnoteMark(line: NLine): String {
        val mark = line.styles.lastOrNull { it.superscript && it.end >= line.text.length } ?: return line.text
        val stripped = line.text.substring(0, mark.start.coerceIn(0, line.text.length)).trimEnd()
        return if (stripped.count { it.isLetter() } >= 2 && line.text.substring(mark.start).all { it.isDigit() || it in "*†‡" }) stripped else line.text
    }

    private fun joinHeading(a: String, b: String): String =
        if (a.endsWith('-') && b.firstOrNull()?.isLowerCase() == true) a.dropLast(1) + b else "$a $b"

    // ---------------------------------------------------------------- paragraphs

    private fun estimateLeading(lines: List<NLine>): Float {
        val deltas = mutableListOf<Float>()
        for ((a, b) in lines.zipWithNext()) {
            val d = b.src.baseline - a.src.baseline
            if (d > 0f && d < a.size * 2.5f && abs(a.size - b.size) < 0.5f && a.bounds.horizontalOverlap(b.bounds) > 0f) {
                deltas += d
            }
        }
        if (deltas.isEmpty()) return profile.bodySize * 1.3f
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    /** A line set apart from the text column: centred, or indented well past its left edge. */
    private fun displayed(l: NLine, column: Rect): Boolean =
        isCentered(l, column) || (l.bounds.left > column.left + l.size * 1.5f && l.bounds.width < column.width * 0.9f)

    /** "প্রদত্ত :", "উত্তর :", "Note:" printed in bold at the start of a line: a new paragraph. */
    private fun startsWithLabel(l: NLine): Boolean {
        val bold = l.styles.firstOrNull { it.bold && it.start == 0 } ?: return false
        val colon = l.text.indexOfFirst { it == ':' || it == 'ঃ' }
        if (colon < 0 || colon > 30 || colon > bold.end + 2) return false
        return l.text.substring(0, colon).trim().split(' ').count { it.isNotBlank() } in 1..4
    }

    private fun continues(b: Builder, cur: NLine, column: Rect, leading: Float, textRight: Float = column.right): Boolean {
        val prev = b.last
        if (b.marker == null && startsWithLabel(cur)) return false
        // A centred line and a line at the left edge are never one paragraph.
        if (column == b.column) {
            val em = cur.size
            if (isCentered(prev, column) && !isCentered(cur, column) && cur.bounds.left < column.left + em) return false
            if (!isCentered(prev, column) && prev.bounds.left < column.left + em && isCentered(cur, column) &&
                cur.bounds.left > column.left + em * 2f
            ) return false
        }
        if (column != b.column) {
            // A paragraph running from the bottom of one column to the top of the next.
            val first = cur.text.firstOrNull() ?: return false
            return b.marker == null && column.left > b.column.left && cur.bounds.top < prev.bounds.top &&
                !Patterns.endsSentence(prev.text) && abs(cur.size - prev.size) < 0.6f &&
                (first.isLowerCase() || BanglaText.isBengali(first)) &&
                cur.bounds.left - column.left < cur.size * 0.5f
        }
        val dy = cur.src.baseline - prev.src.baseline
        if (dy <= 0f) return false
        // A bold run-in label ("উত্তর :", "Solution:") starts a new paragraph.
        if (cur.styles.any { it.start == 0 && it.bold && it.end <= 16 && cur.text.substring(0, it.end.coerceAtMost(cur.text.length)).trimEnd().endsWith(':') }) return false
        val sizeRatio = cur.size / prev.size
        if (sizeRatio < 0.85f || sizeRatio > 1.18f) return false
        val expected = max(leading, prev.size * 1.05f)
        if (dy > expected * 1.45f + 1f) return false
        if (prev.bounds.horizontalOverlap(cur.bounds) <= 0f && cur.bounds.left > prev.bounds.right) return false
        val em = prev.size
        val colLeft = column.left
        val colRight = textRight
        if (b.marker != null) {
            // List item continuation lines are indented past the marker.
            return cur.bounds.left > b.markerLeft + em * 0.4f
        }
        val curIndent = cur.bounds.left - colLeft
        val prevIndent = prev.bounds.left - colLeft
        if (curIndent > em * 0.9f && prevIndent < em * 0.5f && b.lines.size >= 1 && !isCentered(cur, column)) return false
        val prevShort = prev.bounds.right < colRight - em * 2.5f
        // Centred verse keeps going line by line even when a line ends with a danda/full stop.
        if (isCentered(prev, column) && isCentered(cur, column) && dy <= expected * 1.15f + 1f) return true
        if (prevShort && Patterns.endsSentence(prev.text)) return false
        return true
    }

    /** Short lines that don't end a sentence keep their line break (poetry, addresses). */
    private fun isLineBreak(b: Builder, cur: NLine, column: Rect, textRight: Float = column.right): Boolean {
        val prev = b.last
        val prevShort = prev.bounds.right < textRight - prev.size * 2.5f
        val curShort = cur.bounds.width < (textRight - column.left) * 0.7f
        return b.marker == null && prevShort && (curShort || isCentered(prev, column)) && !prev.text.endsWith('-')
    }

    private fun isCentered(l: NLine, column: Rect): Boolean =
        abs(l.bounds.centerX - column.centerX) < column.width * 0.06f && l.bounds.width < column.width * 0.8f

    private fun append(b: Builder, line: NLine, lineBreak: Boolean) {
        val sb = b.text
        val next = line.text
        val offset: Int
        if (lineBreak) {
            sb.append('\n')
            offset = sb.length
        } else if (sb.endsWith("-") && sb.length >= 2 && sb[sb.length - 2].isLatinLetter() &&
            next.firstOrNull()?.let { it.isLowerCase() && it.isLatinLetter() } == true
        ) {
            sb.setLength(sb.length - 1) // de-hyphenate "exam-\nple"
            offset = sb.length
        } else if (sb.endsWith("-") || sb.endsWith("‐") || sb.endsWith("/")) {
            offset = sb.length
        } else {
            sb.append(' ')
            offset = sb.length
        }
        sb.append(next)
        b.styles += shiftStyles(line.styles, 0, next.length, offset)
        b.lines += line
    }

    private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** Clips [styles] to the source range [from, to) and shifts them by [delta]. */
    private fun shiftStyles(styles: List<InlineStyle>, from: Int, to: Int, delta: Int): List<InlineStyle> =
        styles.mapNotNull {
            val s = max(it.start, from)
            val e = minOf(it.end, to)
            if (e > s) InlineStyle(s + delta, e + delta, it.flags) else null
        }

    private fun emit(b: Builder, page: Int, out: MutableList<StructuredElement>, aside: Boolean = false) {
        val text = b.text.toString().trim()
        if (text.isEmpty()) return
        val styles = b.styles.filter { it.start < text.length }.map { InlineStyle(it.start, it.end.coerceAtMost(text.length), it.flags) }
        if (b.marker != null) {
            val level = if (b.markerLeft - b.column.left > b.lines.first().size * 1.5f) 1 else 0
            out += StructuredElement.ListItem(page, b.marker, text, styles, level)
            return
        }
        val first = b.lines.first()
        val col = b.column
        val size = b.lines.map { it.size }.average().toFloat()
        val kind = when {
            // Side notes in the margin column (smaller than the text): notes.
            aside && size < profile.bodySize * 0.97f -> ParagraphKind.NOTE
            b.lines.all { isCentered(it, col) } && b.lines.size <= 8 -> ParagraphKind.CENTERED
            profile.isSmallText(size) && first.bounds.top > col.top + col.height * 0.6f -> ParagraphKind.NOTE
            b.lines.all { it.bounds.left > col.left + it.size * 1.5f && it.bounds.right < col.right - it.size * 1.2f } &&
                b.lines.size >= 2 -> ParagraphKind.QUOTE
            else -> ParagraphKind.BODY
        }
        val indented = b.lines.size > 1 && first.bounds.left - b.lines[1].bounds.left > first.size * 0.8f
        out += StructuredElement.Paragraph(page, text, mergeAdjacent(styles), kind, indented, size)
    }

    private fun mergeAdjacent(styles: List<InlineStyle>): List<InlineStyle> {
        if (styles.size < 2) return styles
        val out = mutableListOf<InlineStyle>()
        for (s in styles.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.flags == s.flags && s.start <= last.end + 1) {
                out[out.size - 1] = InlineStyle(last.start, max(last.end, s.end), last.flags)
            } else {
                out += s
            }
        }
        return out
    }
}
