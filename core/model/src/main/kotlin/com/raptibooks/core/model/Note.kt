package com.raptibooks.core.model

enum class NoteKind(val id: Int) {
    TEXT(0), VOICE(1),
    ;

    companion object {
        fun fromId(id: Int): NoteKind = entries.firstOrNull { it.id == id } ?: TEXT
    }
}

/**
 * A pin the reader left in the book: written note or voice recording. It sits at ([x], [y]) in
 * 0..1 of block [blockIndex] (x across the reading column, y down the block). [pageIndex] lets
 * it follow its page if the book is converted again.
 */
data class Note(
    val id: Long,
    val bookId: Long,
    val blockIndex: Int,
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val kind: NoteKind,
    val text: String,
    val audioPath: String?,
    val durationMs: Long,
    val createdAt: Long,
)
