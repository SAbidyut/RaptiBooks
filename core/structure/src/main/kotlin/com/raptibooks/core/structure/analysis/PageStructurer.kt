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
        val lines = layout.lines
            .filter { !profile.isRunningHeaderOrFooter(it, layout.height) }
            .mapNotNull { normalize(it, layout.fromOcr) }
        val items = mutableListOf<Item>()
        lines.forEach { items += LineItem(it) }
        layout.figures.forEach { items += FigureItem(it) }
        layout.tables.forEach { items += TableItem(it) }
        if (items.isEmpty()) return emptyList()

        // A printed table of contents without dot leaders: titles with page numbers at the right.
        val contentsPage = lines.any { Patterns.contentsTitle.matches(it.text) }
        val contents = contentsLines(lines, layout.width, contentsPage)

        val placed = ReadingOrder.order(items, { it.bounds }, { it.em })
        val leading = estimateLeading(placed.mapNotNull { (it.item as? LineItem)?.line })
        val out = mutableListOf<StructuredElement>()
        val page = layout.index
        var current: Builder? = null

        fun flush() {
            val b = current ?: return
            current = null
            emit(b, page, out)
        }

        // Callout boxes: everything printed inside one is marked as its content.
        var box: BoxRegion? = null
        fun boxOf(item: Item): BoxRegion? {
            val b = item.bounds
            return layout.boxes.firstOrNull { it.bounds.outset(1f).contains(b.centerX, b.centerY) }
        }

        for ((i, p) in placed.withIndex()) {
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
                        if (prev is StructuredElement.Heading && prev.level == level && prevLine != null &&
                            line.bounds.top - prevLine.bounds.bottom < line.size * 0.8f &&
                            headingLevel(prevLine, null, null, leading) == level
                        ) {
                            out[out.size - 1] = prev.copy(text = joinHeading(prev.text, line.text))
                        } else {
                            out += StructuredElement.Heading(page, level, line.text)
                        }
                        continue
                    }
                    val marker = Patterns.listMarker.find(line.text)
                    val b = current
                    if (b != null && marker == null && continues(b, line, p.column, leading)) {
                        val sameColumn = b.column == p.column
                        b.column = p.column // a paragraph may continue into the next column
                        append(b, line, lineBreak = sameColumn && isLineBreak(b, line, p.column))
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
        return groupOptions(out)
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
        val candidates = lines.count { !Patterns.contentsTitle.matches(it.text) && it.text.isNotBlank() }
        if (pairs.size < 3 || (!contentsPage && (pairs.size < 5 || pairs.size * 2 < candidates))) return emptyMap()
        val minLeft = pairs.minOf { it.title.bounds.left }
        val indented = pairs.any { it.title.bounds.left > minLeft + it.title.size }
        val out = HashMap<NLine, StructuredElement.Paragraph?>()
        for (p in pairs) {
            val t = p.title
            val main = t.src.isBold || t.size > profile.bodySize * 1.05f || Patterns.chapter.containsMatchIn(p.titleText) ||
                (indented && t.bounds.left <= minLeft + t.size * 0.5f)
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
        // "Example 1.4" on its own line (often on a coloured banner): a minor heading.
        if (Patterns.exampleLabel.matches(text) && (bold || ratio >= 1.0f || isolated)) {
            return profile.headingLevelForSize(line.size)?.coerceAtLeast(3) ?: 4
        }
        val bySize = profile.headingLevelForSize(line.size)
        if (bySize != null && words <= 16 && !(text.endsWith('.') && words > 10)) return bySize
        val numbered = Patterns.numberedHeading.find(text)
        if (numbered != null && (bold || ratio >= 1.08f) && words <= 14 && !Patterns.endsSentence(text)) {
            val depth = numbered.groupValues[1].count { it == '.' }
            return (2 + depth).coerceAtMost(4)
        }
        // A bold line between paragraphs at body size: minor heading.
        if (bold && ratio >= 0.95f && words <= 12 && isolated && !text.endsWith(',') &&
            (!Patterns.endsSentence(text) || text.endsWith(':')) &&
            !(prev != null && prev.src.isBold && gapAbove < leading * 0.6f)
        ) {
            return 4
        }
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it in 'A'..'Z' } && words <= 8 && ratio >= 1.0f && isolated) return 4
        return null
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
        isCentered(l, column) || (l.bounds.left > column.left + l.size * 2f && l.bounds.width < column.width * 0.8f)

    /** "প্রদত্ত :", "উত্তর :", "Note:" printed in bold at the start of a line: a new paragraph. */
    private fun startsWithLabel(l: NLine): Boolean {
        val bold = l.styles.firstOrNull { it.bold && it.start == 0 } ?: return false
        val colon = l.text.indexOfFirst { it == ':' || it == 'ঃ' }
        if (colon < 0 || colon > 30 || colon > bold.end + 2) return false
        return l.text.substring(0, colon).trim().split(' ').count { it.isNotBlank() } in 1..4
    }

    private fun continues(b: Builder, cur: NLine, column: Rect, leading: Float): Boolean {
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
        val sizeRatio = cur.size / prev.size
        if (sizeRatio < 0.85f || sizeRatio > 1.18f) return false
        val expected = max(leading, prev.size * 1.05f)
        if (dy > expected * 1.45f + 1f) return false
        if (prev.bounds.horizontalOverlap(cur.bounds) <= 0f && cur.bounds.left > prev.bounds.right) return false
        val em = prev.size
        val colLeft = column.left
        val colRight = column.right
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
    private fun isLineBreak(b: Builder, cur: NLine, column: Rect): Boolean {
        val prev = b.last
        val prevShort = prev.bounds.right < column.right - prev.size * 2.5f
        val curShort = cur.bounds.width < column.width * 0.7f
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

    private fun emit(b: Builder, page: Int, out: MutableList<StructuredElement>) {
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
