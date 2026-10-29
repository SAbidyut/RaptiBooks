package com.raptibooks.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raptibooks.core.data.ReaderRepository
import com.raptibooks.core.data.SettingsRepository
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.Bookmark
import com.raptibooks.core.model.Highlight
import com.raptibooks.core.model.InkStroke
import com.raptibooks.core.model.MarkKey
import com.raptibooks.core.model.Note
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.ReaderSettings
import com.raptibooks.core.model.ReadingPosition
import com.raptibooks.core.model.SearchHit
import com.raptibooks.core.model.TocEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val bookId: Long,
    private val repo: ReaderRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    sealed interface Load {
        data object Loading : Load
        data object NotReady : Load
        data class Ready(
            val book: Book,
            val toc: List<TocEntry>,
            val source: BlockSource,
            val initial: ReadingPosition,
            /** Callout boxes (worked examples, notes) drawn as cards around their blocks. */
            val boxes: BoxLookup = BoxLookup(emptyList()),
        ) : Load
    }

    var load by mutableStateOf<Load>(Load.Loading)
        private set

    val settings: StateFlow<ReaderSettings> = settingsRepo.reader

    val bookmarks: StateFlow<List<Bookmark>> =
        repo.bookmarks(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user's underlined passages, by text (see [MarkKey]). */
    val highlights: StateFlow<Map<Long, List<Highlight>>> by lazy {
        combine(repo.highlights(bookId), fromPages) { all, pages ->
            val own = all.groupBy { MarkKey.of(it.blockIndex, it.part) }
            if (pages.spans.isEmpty()) own else (own.keys + pages.spans.keys).associateWith { own[it].orEmpty() + pages.spans[it].orEmpty() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    /** Underlines [edits] (character ranges per text) in [color]; a null colour erases them. */
    fun mark(edits: Map<Long, List<IntRange>>, color: MarkColor?) {
        viewModelScope.launch {
            repo.mark(bookId, edits, color)
            if (color == null) {
                // (an underline made on the original page is erased there)
                val lines = edits.flatMap { (key, ranges) ->
                    fromPages.value.spans[key].orEmpty().filter { h -> ranges.any { it.first < h.end && it.last + 1 > h.start } }.map { -it.id }
                }
                if (lines.isNotEmpty()) repo.eraseInk(lines.distinct())
            }
        }
    }

    /** Marks made on the original pages, as they show in the reflowed text. */
    private class FromPages(val spans: Map<Long, List<Highlight>>, val notes: Map<Int, List<Note>>)

    /**
     * The underlines and notes made on the original pages, found in the reflowed text by their
     * words: an underline as a highlight (with the negative id of its line), a note moved to its
     * paragraph.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fromPages: StateFlow<FromPages> =
        combine(repo.ink(bookId), repo.notes(bookId)) { ink, notes -> ink.filter { it.blockIndex < 0 } to notes.filter { it.blockIndex < 0 } }
            .mapLatest { (ink, notes) -> pageMarks(ink, notes) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, FromPages(emptyMap(), emptyMap()))

    private suspend fun pageMarks(ink: List<InkStroke>, notes: List<Note>): FromPages {
        if (ink.isEmpty() && notes.isEmpty()) return FromPages(emptyMap(), emptyMap())
        val spans = ArrayList<Highlight>()
        val moved = ArrayList<Note>()
        val pages = (ink.map { -it.blockIndex - 1 } + notes.map { -it.blockIndex - 1 }).distinct()
        for (page in pages) {
            val lines = repo.originalLines(bookId, page)
            if (lines.isEmpty()) continue
            val texts = repo.blocksOfPages(bookId, (page - 1).coerceAtLeast(0), page).mapNotNull { b -> markedText(b)?.let { b.index to it } }
            fun locate(line: com.raptibooks.core.data.PageLine, chars: IntRange): Pair<Int, IntRange>? =
                texts.mapNotNull { (index, text) -> PageMarks.inText(text, line, chars)?.let { Triple(index, it.first, it.second) } }
                    .maxByOrNull { it.second }?.let { it.first to it.third }
            for (stroke in ink) {
                if (-stroke.blockIndex - 1 != page) continue
                val (line, chars) = PageMarks.underlined(stroke.points, lines) ?: continue
                val (block, range) = locate(line, chars) ?: continue
                spans += Highlight(-stroke.id, bookId, block, range.first, range.last + 1, stroke.color)
            }
            for (note in notes) {
                if (-note.blockIndex - 1 != page) continue
                val (line, chars) = PageMarks.wordAt(lines, note.x, note.y) ?: continue
                val (block, range) = locate(line, chars) ?: continue
                val length = texts.first { it.first == block }.second.length.coerceAtLeast(1)
                val across = ((note.x - line.left) / (line.right - line.left).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                moved += note.copy(blockIndex = block, x = across, y = range.first.toFloat() / length)
            }
        }
        return FromPages(spans.groupBy { MarkKey.of(it.blockIndex, it.part) }, moved.groupBy { it.blockIndex })
    }

    /** The user's freehand lines on pictures, by block. */
    val ink: StateFlow<Map<Int, List<InkStroke>>> =
        repo.ink(bookId).map { all -> all.groupBy { it.blockIndex } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The text printed in a picture of the book, for copying (see [ReaderRepository.pictureText]). */
    suspend fun pictureText(path: String): String? = repo.pictureText(path)

    fun addInk(block: Int, color: MarkColor, points: FloatArray) {
        viewModelScope.launch { repo.addInk(bookId, block, color, points) }
    }

    fun eraseInk(ids: List<Long>) {
        viewModelScope.launch { repo.eraseInk(ids) }
    }

    /** The user's pinned notes, by block. */
    val notes: StateFlow<Map<Int, List<Note>>> by lazy {
        combine(repo.notes(bookId), fromPages) { all, pages ->
            val own = all.filter { it.blockIndex >= 0 }.groupBy { it.blockIndex }
            if (pages.notes.isEmpty()) own else (own.keys + pages.notes.keys).associateWith { own[it].orEmpty() + pages.notes[it].orEmpty() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    fun newVoiceFile() = repo.newVoiceFile(bookId)

    fun addNote(block: Int, page: Int, x: Float, y: Float, kind: NoteKind, text: String, audioPath: String?, durationMs: Long) {
        viewModelScope.launch { repo.addNote(bookId, block, page, x, y, kind, text, audioPath, durationMs) }
    }

    fun updateNoteText(noteId: Long, text: String) {
        viewModelScope.launch { repo.updateNoteText(noteId, text) }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch { repo.deleteNote(note) }
    }

    /** Search term highlighted in the text after jumping to a search result. */
    var highlight by mutableStateOf<String?>(null)

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<SearchHit>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    init {
        // The original's text kept open while the book is read (marks made on its pages are
        // shown here, and a passage held here is found there).
        repo.holdOriginalText(bookId, null)
        viewModelScope.launch {
            val book = repo.book(bookId)
            if (book == null || !book.isReadable) {
                load = Load.NotReady
                return@launch
            }
            val source = BlockSource(viewModelScope, book.blockCount) { from, to -> repo.blocks(bookId, from, to) }
            val start = ReadingPosition(book.position.blockIndex.coerceIn(0, maxOf(0, book.blockCount - 1)), book.position.offset)
            source.preload(start.blockIndex)
            val toc = repo.toc(bookId)
            val boxes = BoxLookup(runCatching { repo.boxes(bookId) }.getOrDefault(emptyList()))
            repo.markOpened(bookId)
            load = Load.Ready(book, toc, source, start, boxes)
            // The original's page read ahead (a moment later, not to slow the first page): a
            // passage held here is then found on the original page at once.
            delay(1500)
            source.peek(start.blockIndex)?.pageIndex?.let { repo.prefetchOriginalLines(bookId, it) }
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = settingsRepo.update(transform)

    fun savePosition(index: Int, offset: Int) {
        val ready = load as? Load.Ready ?: return
        val count = ready.book.blockCount
        val progress = if (count <= 1) 1f else index.toFloat() / (count - 1)
        viewModelScope.launch { repo.savePosition(bookId, ReadingPosition(index, offset), progress) }
    }

    fun search(text: String) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) {
            results = null
            searching = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            searching = true
            results = repo.search(bookId, text.trim())
            searching = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        results = null
    }

    /** Toggles a bookmark on the first visible block (or removes one in the visible range). */
    fun toggleBookmark(first: Int, last: Int): Boolean? {
        val ready = load as? Load.Ready ?: return null
        val existing = bookmarks.value.firstOrNull { it.blockIndex in first..last }
        return if (existing != null) {
            viewModelScope.launch { repo.removeBookmark(existing.id) }
            false
        } else {
            val block = ready.source.peek(first)
            viewModelScope.launch {
                repo.addBookmark(bookId, first, block?.pageIndex ?: 0, block?.plainText?.take(160).orEmpty())
            }
            true
        }
    }

    fun removeBookmark(bookmark: Bookmark) = viewModelScope.launch { repo.removeBookmark(bookmark.id) }

    suspend fun blockForPage(page: Int): Int? = repo.firstBlockOfPage(bookId, page)

    /**
     * The block of the reflowed book that holds the printed [line] of [page] (a paragraph may
     * have begun on the page before), or null when none does.
     */
    suspend fun findPassage(page: Int, line: String): Int? =
        repo.blocksOfPages(bookId, (page - 1).coerceAtLeast(0), page)
            .mapNotNull { b -> Passage.rangeIn(b.plainText, line)?.let { Triple(b.index, it.first, b.pageIndex == page) } }
            .maxWithOrNull(compareBy({ it.second }, { it.third }))?.first

    /**
     * Where a line of the printed contents (at block [from]) points to: the matching chapter in
     * the book's table of contents, or else the next place its title appears.
     */
    suspend fun contentsTarget(title: String, from: Int, toc: List<TocEntry>): Int? {
        val key = contentsKey(title)
        if (key.length < 3) return null
        toc.firstOrNull { e ->
            val k = contentsKey(e.title)
            e.blockIndex > from && (k == key || (minOf(k.length, key.length) >= 4 && (k.startsWith(key) || key.startsWith(k))))
        }?.let { return it.blockIndex }
        return repo.search(bookId, title).firstOrNull { it.blockIndex > from }?.blockIndex
    }

    private fun contentsKey(s: String): String = s.filter { it.isLetterOrDigit() || it.code in 0x0980..0x09FF }.lowercase()

    suspend fun pageForBlock(index: Int): Int = repo.pageOfBlock(bookId, index) ?: 0

    /** Reads the lines of an original page ahead, while the screen goes over to it. */
    fun prefetchOriginal(page: Int) = repo.prefetchOriginalLines(bookId, page)

    override fun onCleared() {
        repo.releaseOriginalText(bookId)
        // Let the library refresh reading progress once the reader closes.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { repo.publishPosition() }
    }
}
