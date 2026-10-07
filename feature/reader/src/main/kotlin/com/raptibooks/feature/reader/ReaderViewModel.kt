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
import kotlinx.coroutines.flow.map
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
        ) : Load
    }

    var load by mutableStateOf<Load>(Load.Loading)
        private set

    val settings: StateFlow<ReaderSettings> = settingsRepo.reader

    val bookmarks: StateFlow<List<Bookmark>> =
        repo.bookmarks(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user's underlined passages, by text (see [MarkKey]). */
    val highlights: StateFlow<Map<Long, List<Highlight>>> =
        repo.highlights(bookId).map { all -> all.groupBy { MarkKey.of(it.blockIndex, it.part) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Underlines [edits] (character ranges per text) in [color]; a null colour erases them. */
    fun mark(edits: Map<Long, List<IntRange>>, color: MarkColor?) {
        viewModelScope.launch { repo.mark(bookId, edits, color) }
    }

    /** The user's freehand lines on pictures, by block. */
    val ink: StateFlow<Map<Int, List<InkStroke>>> =
        repo.ink(bookId).map { all -> all.groupBy { it.blockIndex } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addInk(block: Int, color: MarkColor, points: FloatArray) {
        viewModelScope.launch { repo.addInk(bookId, block, color, points) }
    }

    fun eraseInk(ids: List<Long>) {
        viewModelScope.launch { repo.eraseInk(ids) }
    }

    /** The user's pinned notes, by block. */
    val notes: StateFlow<Map<Int, List<Note>>> =
        repo.notes(bookId).map { all -> all.groupBy { it.blockIndex } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
            repo.markOpened(bookId)
            load = Load.Ready(book, toc, source, start)
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

    override fun onCleared() {
        // Let the library refresh reading progress once the reader closes.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { repo.publishPosition() }
    }
}
