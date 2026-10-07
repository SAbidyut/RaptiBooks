package com.raptibooks.core.reflow

import com.raptibooks.core.model.TocEntry
import java.text.Normalizer

/** An entry of the PDF's own outline (bookmarks). */
data class OutlineItem(val title: String, val pageIndex: Int, val level: Int)

/** A line of the book's printed table of contents ("1.2 Systems of Units ..... 4"). */
data class PrintedEntry(val title: String, val pageLabel: String, val main: Boolean, val blockIndex: Int)

/**
 * Builds the table of contents shown in the reader.
 *
 * 1. A PDF outline, when present, is the author's intended TOC and wins; each entry is anchored
 *    to the matching detected heading on its page, or to the first block of that page.
 * 2. Else the book's printed contents page, when it can be tied to the book: entries are
 *    matched to detected headings, and the printed page numbers are translated to PDF pages
 *    with the offset those matches show (front matter is usually numbered separately).
 * 3. Else the detected headings (levels 1–3), cleaned of noise.
 */
object TocBuilder {

    fun build(
        outline: List<OutlineItem>,
        headings: List<TocEntry>,
        pageStarts: Map<Int, Int>,
        blockCount: Int,
        printed: List<PrintedEntry> = emptyList(),
    ): List<TocEntry> {
        if (blockCount == 0) return emptyList()
        val sortedStarts = pageStarts.toSortedMap()
        val usable = outline.filter { it.title.isNotBlank() && it.pageIndex >= 0 }
        if (usable.size >= 2) {
            return usable.map { item ->
                val onPage = headings.filter { it.pageIndex == item.pageIndex }
                val match = onPage.firstOrNull { similar(it.title, item.title) }
                val block = match?.blockIndex
                    ?: sortedStarts.tailMap(item.pageIndex).values.firstOrNull()
                    ?: (blockCount - 1)
                TocEntry(item.title.trim(), (item.level + 1).coerceIn(1, 4), block, item.pageIndex)
            }.sortedBy { it.blockIndex }
        }
        fromPrinted(printed, headings, sortedStarts, blockCount)?.let { return it }
        return clean(headings)
    }

    private fun fromPrinted(
        printed: List<PrintedEntry>,
        headings: List<TocEntry>,
        starts: java.util.SortedMap<Int, Int>,
        blockCount: Int,
    ): List<TocEntry>? {
        if (printed.size < 4) return null
        val contentsEnd = printed.maxOf { it.blockIndex }
        val after = headings.filter { it.blockIndex > contentsEnd }
        // Match each printed line to a heading after the contents, in order.
        val matches = HashMap<PrintedEntry, TocEntry>()
        var from = 0
        for (p in printed) {
            val k = after.subList(from, after.size).indexOfFirst { similar(it.title, p.title) }
            if (k >= 0) {
                matches[p] = after[from + k]
                from += k + 1
            }
        }
        val offsets = matches.mapNotNull { (p, h) -> pageNumber(p.pageLabel)?.let { h.pageIndex - it } }.sorted()
        val offset = if (offsets.isNotEmpty()) offsets[offsets.size / 2] else null
        val firstPage = starts.keys.firstOrNull() ?: 0
        val lastPage = starts.keys.lastOrNull() ?: 0
        val hasMain = printed.any { it.main } && printed.any { !it.main }
        val out = printed.mapNotNull { p ->
            val level = if (!hasMain || p.main) 1 else 2
            val h = matches[p]
            if (h != null) return@mapNotNull TocEntry(p.title, level, h.blockIndex, h.pageIndex)
            val label = pageNumber(p.pageLabel) ?: return@mapNotNull null
            val page = (label + (offset ?: return@mapNotNull null)).takeIf { it in firstPage..lastPage } ?: return@mapNotNull null
            if (page < 0) return@mapNotNull null
            val block = starts.tailMap(page).values.firstOrNull() ?: return@mapNotNull null
            TocEntry(p.title, level, block.coerceAtMost(blockCount - 1), page)
        }
        // Only trust the printed contents when it really describes this book.
        if (out.size * 10 < printed.size * 6 || (matches.isEmpty() && out.size < printed.size)) return null
        if (matches.isEmpty() && offset == null) return null
        return out.sortedBy { it.blockIndex }
    }

    /** Detected headings without noise: duplicates, symbols-only lines, overly long "headings". */
    private fun clean(headings: List<TocEntry>): List<TocEntry> {
        var out = headings.filter { h ->
            val letters = h.title.count { it.isLetter() }
            letters >= 2 && h.title.length <= 150
        }
        out = out.filterIndexed { i, h -> i == 0 || norm(out[i - 1].title) != norm(h.title) || h.blockIndex - out[i - 1].blockIndex > 20 }
        if (out.size > 400) out = out.filter { it.level <= 2 }
        return out
    }

    private fun pageNumber(s: String): Int? =
        s.trim().map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("").toIntOrNull()

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase().filter { it.isLetterOrDigit() || it.code in 0x0980..0x09FF }

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 4 && (x.startsWith(y) || y.startsWith(x))) ||
            (x.length > 6 && y.contains(x)) || (y.length > 6 && x.contains(y))
    }
}
