package com.raptibooks.feature.library

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raptibooks.core.data.ImportException
import com.raptibooks.core.data.LibraryRepository
import com.raptibooks.core.data.SettingsRepository
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.OcrMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryMessage {
    data object Imported : LibraryMessage
    data class Failed(val reason: String) : LibraryMessage
}

class LibraryViewModel(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** null while loading. */
    val books: StateFlow<List<Book>?> = library.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val defaultOcrMode: StateFlow<OcrMode> = settings.defaultOcrMode

    private val _messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val messages: Flow<LibraryMessage> = _messages.receiveAsFlow()

    var importing by mutableStateOf(false)
        private set

    fun import(uri: Uri) {
        viewModelScope.launch {
            importing = true
            try {
                library.importPdf(uri)
                _messages.send(LibraryMessage.Imported)
            } catch (e: ImportException) {
                _messages.send(LibraryMessage.Failed(e.message ?: ""))
            } catch (e: Exception) {
                _messages.send(LibraryMessage.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                importing = false
            }
        }
    }

    fun delete(book: Book) = viewModelScope.launch { library.delete(book.id) }

    fun reconvert(book: Book, mode: OcrMode) = viewModelScope.launch { library.reconvert(book.id, mode) }

    fun cancel(book: Book) = viewModelScope.launch { library.cancel(book.id) }

    fun retry(book: Book) = viewModelScope.launch { library.retry(book.id) }

    fun setDefaultOcrMode(mode: OcrMode) = settings.setDefaultOcrMode(mode)
}
