package com.raptibooks.core.reflow

import com.raptibooks.core.model.TocEntry
import java.text.Normalizer

/** An entry of the PDF's own outline (bookmarks). */
data class OutlineItem(val title: String, val pageIndex: Int, val level: Int)

/**
 * Builds the table of contents. A PDF outline, when present, is the author's intended TOC and
 * wins; each entry is anchored to the matching detected heading on its page, or to the first
 * block of that page. Without an outline, detected headings (levels 1–3) are used.
 */
object TocBuilder {

    fun build(outline: List<OutlineItem>, headings: List<TocEntry>, pageStarts: Map<Int, Int>, blockCount: Int): List<TocEntry> {
        if (blockCount == 0) return emptyList()
        val usable = outline.filter { it.title.isNotBlank() && it.pageIndex >= 0 }
        if (usable.size >= 2) {
            val sortedStarts = pageStarts.toSortedMap()
            return usable.map { item ->
                val onPage = headings.filter { it.pageIndex == item.pageIndex }
                val match = onPage.firstOrNull { similar(it.title, item.title) }
                val block = match?.blockIndex
                    ?: sortedStarts.tailMap(item.pageIndex).values.firstOrNull()
                    ?: (blockCount - 1)
                TocEntry(item.title.trim(), (item.level + 1).coerceIn(1, 4), block, item.pageIndex)
            }.sortedBy { it.blockIndex }
        }
        return headings
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase().filter { it.isLetterOrDigit() || it.code in 0x0980..0x09FF }

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.startsWith(y) || y.startsWith(x) || (x.length > 6 && y.contains(x)) || (y.length > 6 && x.contains(y))
    }
}
