package com.raptibooks.feature.reader

import androidx.compose.runtime.Immutable
import com.raptibooks.core.model.BoxSpan

/** Finds the callout box a block belongs to (spans are sorted and do not overlap). */
@Immutable
class BoxLookup(spans: List<BoxSpan>) {
    private val sorted = spans.sortedBy { it.startIndex }
    private val starts = IntArray(sorted.size) { sorted[it].startIndex }

    fun find(index: Int): BoxSpan? {
        if (sorted.isEmpty()) return null
        var i = starts.binarySearch(index)
        if (i < 0) i = -i - 2
        if (i < 0) return null
        val s = sorted[i]
        return if (index <= s.endIndex) s else null
    }
}
