package com.raptibooks.feature.reader

import androidx.compose.runtime.mutableStateMapOf
import com.raptibooks.core.model.ContentBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Loads book blocks from the database in chunks as the reader scrolls and keeps only a window
 * of chunks in memory, so a 2,000-page book reads as smoothly as a short one.
 */
class BlockSource(
    private val scope: CoroutineScope,
    val count: Int,
    private val load: suspend (from: Int, to: Int) -> List<ContentBlock>,
) {
    private val chunks = mutableStateMapOf<Int, Array<ContentBlock?>>()
    private val loading = HashSet<Int>()
    private var focus = 0

    /** Block at [index], or null while its chunk is loading (and schedules the load). */
    fun get(index: Int): ContentBlock? {
        val c = index / CHUNK
        focus = c
        val chunk = chunks[c]
        if (chunk == null) {
            request(c)
            return null
        }
        val pos = index - c * CHUNK
        if (pos > CHUNK * 3 / 4) request(c + 1) else if (pos < CHUNK / 4) request(c - 1)
        return chunk.getOrNull(pos)
    }

    /** Block at [index] if already loaded; never triggers loading. */
    fun peek(index: Int): ContentBlock? = chunks[index / CHUNK]?.getOrNull(index % CHUNK)

    /** Loads the chunk containing [index] (and its neighbours) before the first frame. */
    suspend fun preload(index: Int) {
        val c = (index / CHUNK).coerceAtLeast(0)
        for (k in listOf(c, c + 1, c - 1)) {
            if (k < 0 || k * CHUNK >= count || chunks.containsKey(k)) continue
            chunks[k] = fetch(k)
        }
    }

    private fun request(c: Int) {
        if (c < 0 || c * CHUNK >= count || chunks.containsKey(c) || !loading.add(c)) return
        scope.launch {
            try {
                chunks[c] = fetch(c)
                evict()
            } finally {
                loading.remove(c)
            }
        }
    }

    private suspend fun fetch(c: Int): Array<ContentBlock?> {
        val from = c * CHUNK
        val to = minOf(count, from + CHUNK)
        val arr = arrayOfNulls<ContentBlock>(to - from)
        for (b in load(from, to)) {
            val i = b.index - from
            if (i in arr.indices) arr[i] = b
        }
        return arr
    }

    private fun evict() {
        if (chunks.size <= MAX_CHUNKS) return
        val far = chunks.keys.sortedByDescending { abs(it - focus) }
        for (k in far.take(chunks.size - MAX_CHUNKS)) chunks.remove(k)
    }

    companion object {
        const val CHUNK = 40
        private const val MAX_CHUNKS = 30
    }
}
