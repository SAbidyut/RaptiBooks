package com.raptibooks.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * SQLite storage for the library. Converted books are fully stored here (plus extracted
 * images on disk), so reading works offline and does not touch the PDF again.
 */
class RaptiDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    private val changes = MutableSharedFlow<Set<String>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                author TEXT,
                file_name TEXT NOT NULL,
                pdf_path TEXT NOT NULL,
                cover_path TEXT,
                page_count INTEGER NOT NULL DEFAULT 0,
                file_size INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                progress REAL NOT NULL DEFAULT 0,
                progress_message TEXT,
                error_message TEXT,
                added_at INTEGER NOT NULL,
                last_opened_at INTEGER,
                reading_block INTEGER NOT NULL DEFAULT 0,
                reading_offset INTEGER NOT NULL DEFAULT 0,
                reading_progress REAL NOT NULL DEFAULT 0,
                block_count INTEGER NOT NULL DEFAULT 0,
                language TEXT,
                used_ocr INTEGER NOT NULL DEFAULT 0,
                ocr_mode TEXT NOT NULL DEFAULT 'AUTO',
                layout_version INTEGER NOT NULL DEFAULT 0,
                restore_page INTEGER NOT NULL DEFAULT -1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE blocks (
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                idx INTEGER NOT NULL,
                page INTEGER NOT NULL,
                type INTEGER NOT NULL,
                level INTEGER NOT NULL DEFAULT 0,
                text TEXT NOT NULL DEFAULT '',
                styles TEXT,
                extra TEXT,
                PRIMARY KEY (book_id, idx)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX blocks_page ON blocks(book_id, page)")
        db.execSQL(
            """
            CREATE TABLE toc (
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                ord INTEGER NOT NULL,
                title TEXT NOT NULL,
                level INTEGER NOT NULL,
                block_idx INTEGER NOT NULL,
                page INTEGER NOT NULL,
                PRIMARY KEY (book_id, ord)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                block_idx INTEGER NOT NULL,
                page INTEGER NOT NULL,
                snippet TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX bookmarks_book ON bookmarks(book_id, block_idx)")
        createHighlights(db)
        createInk(db)
    }

    /** Underlined passages: a colour on a character range of one block's text. */
    private fun createHighlights(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                block_idx INTEGER NOT NULL,
                start_pos INTEGER NOT NULL,
                end_pos INTEGER NOT NULL,
                color INTEGER NOT NULL,
                part INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS highlights_book ON highlights(book_id, block_idx)")
    }

    /** Freehand lines on pictures. */
    private fun createInk(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ink (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
                block_idx INTEGER NOT NULL,
                color INTEGER NOT NULL,
                points TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ink_book ON ink(book_id, block_idx)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Which converter produced a book (to improve old books after an update), and the
            // page to return to once a re-conversion finishes.
            db.execSQL("ALTER TABLE books ADD COLUMN layout_version INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN restore_page INTEGER NOT NULL DEFAULT -1")
        }
        if (oldVersion < 3) {
            createHighlights(db)
        } else if (oldVersion < 4) {
            db.execSQL("ALTER TABLE highlights ADD COLUMN part INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) createInk(db)
    }

    internal fun notifyChanged(vararg tables: String) {
        changes.tryEmit(tables.toSet())
    }

    /** Re-runs [query] on a background thread whenever one of [tables] changes. */
    internal fun <T> observe(vararg tables: String, query: () -> T): Flow<T> {
        val watched = tables.toSet()
        return changes
            .filter { changed -> changed.any { it in watched } }
            .onStart { emit(watched) }
            .conflate()
            .map { query() }
            .flowOn(Dispatchers.IO)
    }

    internal inline fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val NAME = "raptibooks.db"
        private const val VERSION = 4

        const val BOOKS = "books"
        const val BLOCKS = "blocks"
        const val TOC = "toc"
        const val BOOKMARKS = "bookmarks"
        const val HIGHLIGHTS = "highlights"
        const val INK = "ink"
    }
}
