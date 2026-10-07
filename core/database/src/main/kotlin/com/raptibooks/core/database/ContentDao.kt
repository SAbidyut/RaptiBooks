package com.raptibooks.core.database

import android.content.ContentValues
import android.database.Cursor
import com.raptibooks.core.model.Bookmark
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.SearchHit
import com.raptibooks.core.model.SeparatorBlock
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.TocEntry
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Reflowed book content: blocks, table of contents, bookmarks and search. */
class ContentDao(private val db: RaptiDatabase) {

    // ------------------------------------------------------------------ blocks

    fun deleteContent(bookId: Long) {
        db.transaction {
            delete("blocks", "book_id = ?", arrayOf(bookId.toString()))
            delete("toc", "book_id = ?", arrayOf(bookId.toString()))
        }
        db.notifyChanged(RaptiDatabase.BLOCKS, RaptiDatabase.TOC)
    }

    fun insertBlocks(bookId: Long, blocks: List<ContentBlock>) {
        if (blocks.isEmpty()) return
        db.transaction {
            val stmt = compileStatement(
                "INSERT OR REPLACE INTO blocks(book_id, idx, page, type, level, text, styles, extra) VALUES (?,?,?,?,?,?,?,?)",
            )
            for (b in blocks) {
                val row = BlockCodec.encode(b)
                stmt.clearBindings()
                stmt.bindLong(1, bookId)
                stmt.bindLong(2, b.index.toLong())
                stmt.bindLong(3, b.pageIndex.toLong())
                stmt.bindLong(4, row.type.toLong())
                stmt.bindLong(5, row.level.toLong())
                stmt.bindString(6, row.text)
                if (row.styles != null) stmt.bindString(7, row.styles) else stmt.bindNull(7)
                if (row.extra != null) stmt.bindString(8, row.extra) else stmt.bindNull(8)
                stmt.executeInsert()
            }
        }
    }

    fun notifyContentChanged() = db.notifyChanged(RaptiDatabase.BLOCKS)

    /** Blocks with index in [from, to). */
    fun blocks(bookId: Long, from: Int, to: Int): List<ContentBlock> =
        db.readableDatabase.rawQuery(
            "SELECT idx, page, type, level, text, styles, extra FROM blocks WHERE book_id = ? AND idx >= ? AND idx < ? ORDER BY idx",
            arrayOf(bookId.toString(), from.toString(), to.toString()),
        ).use { c ->
            val out = ArrayList<ContentBlock>(c.count)
            while (c.moveToNext()) BlockCodec.decode(c)?.let { out += it }
            out
        }

    fun blockCount(bookId: Long): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM blocks WHERE book_id = ?", arrayOf(bookId.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** First block that came from page [page] or a later page. */
    fun firstBlockAtOrAfterPage(bookId: Long, page: Int): Int? =
        db.readableDatabase.rawQuery(
            "SELECT MIN(idx) FROM blocks WHERE book_id = ? AND page >= ?",
            arrayOf(bookId.toString(), page.toString()),
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }

    fun pageOfBlock(bookId: Long, index: Int): Int? =
        db.readableDatabase.rawQuery(
            "SELECT page FROM blocks WHERE book_id = ? AND idx <= ? ORDER BY idx DESC LIMIT 1",
            arrayOf(bookId.toString(), index.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else null }

    // ------------------------------------------------------------------ search

    /** Case-insensitive (for Latin) substring search; Bangla is matched in NFC form. */
    fun search(bookId: Long, query: String, limit: Int = 300): List<SearchHit> {
        val q = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
        if (q.isEmpty()) return emptyList()
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.readableDatabase.rawQuery(
            "SELECT idx, page, text FROM blocks WHERE book_id = ? AND text LIKE ? ESCAPE '\\' ORDER BY idx LIMIT ?",
            arrayOf(bookId.toString(), pattern, limit.toString()),
        ).use { c ->
            val out = ArrayList<SearchHit>()
            while (c.moveToNext()) {
                val text = c.getString(2)
                val at = text.indexOf(q, ignoreCase = true)
                if (at < 0) continue
                // Start the snippet at a word boundary shortly before the match.
                val from = maxOf(0, at - 60)
                val space = if (from == 0) -1 else text.lastIndexOf(' ', from)
                val start = if (space >= 0 && space >= from - 15) space + 1 else from
                val end = minOf(text.length, at + q.length + 80)
                val prefix = if (start > 0) "…" else ""
                val snippet = prefix + text.substring(start, end).replace('\n', ' ') + if (end < text.length) "…" else ""
                val matchStart = prefix.length + (at - start)
                out += SearchHit(c.getInt(0), c.getInt(1), snippet, matchStart, matchStart + q.length)
            }
            out
        }
    }

    // ------------------------------------------------------------------ toc

    fun saveToc(bookId: Long, entries: List<TocEntry>) {
        db.transaction {
            delete("toc", "book_id = ?", arrayOf(bookId.toString()))
            entries.forEachIndexed { i, e ->
                insert(
                    "toc", null,
                    ContentValues().apply {
                        put("book_id", bookId)
                        put("ord", i)
                        put("title", e.title)
                        put("level", e.level)
                        put("block_idx", e.blockIndex)
                        put("page", e.pageIndex)
                    },
                )
            }
        }
        db.notifyChanged(RaptiDatabase.TOC)
    }

    fun toc(bookId: Long): List<TocEntry> =
        db.readableDatabase.rawQuery(
            "SELECT title, level, block_idx, page FROM toc WHERE book_id = ? ORDER BY ord",
            arrayOf(bookId.toString()),
        ).use { c ->
            val out = ArrayList<TocEntry>(c.count)
            while (c.moveToNext()) out += TocEntry(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3))
            out
        }

    // ------------------------------------------------------------------ bookmarks

    fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> = db.observe(RaptiDatabase.BOOKMARKS) {
        db.readableDatabase.rawQuery(
            "SELECT id, book_id, block_idx, page, snippet, created_at FROM bookmarks WHERE book_id = ? ORDER BY block_idx",
            arrayOf(bookId.toString()),
        ).use { c ->
            val out = ArrayList<Bookmark>(c.count)
            while (c.moveToNext()) out += Bookmark(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getString(4), c.getLong(5))
            out
        }
    }

    fun addBookmark(bookId: Long, blockIndex: Int, page: Int, snippet: String): Long {
        val id = db.writableDatabase.insert(
            "bookmarks", null,
            ContentValues().apply {
                put("book_id", bookId)
                put("block_idx", blockIndex)
                put("page", page)
                put("snippet", snippet.take(200))
                put("created_at", System.currentTimeMillis())
            },
        )
        db.notifyChanged(RaptiDatabase.BOOKMARKS)
        return id
    }

    fun deleteBookmark(id: Long) {
        db.writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
        db.notifyChanged(RaptiDatabase.BOOKMARKS)
    }

    /** After a re-conversion, points bookmarks at the first block of the page they were on. */
    fun remapBookmarks(bookId: Long) {
        db.writableDatabase.execSQL(
            "UPDATE bookmarks SET block_idx = COALESCE((SELECT MIN(idx) FROM blocks b WHERE b.book_id = bookmarks.book_id " +
                "AND b.page >= bookmarks.page), 0) WHERE book_id = ?",
            arrayOf<Any>(bookId),
        )
        db.notifyChanged(RaptiDatabase.BOOKMARKS)
    }

    fun deleteBookmarks(bookId: Long) {
        db.writableDatabase.delete("bookmarks", "book_id = ?", arrayOf(bookId.toString()))
        db.notifyChanged(RaptiDatabase.BOOKMARKS)
    }
}

/** Row encoding of [ContentBlock]s. */
internal object BlockCodec {
    private const val HEADING = 1
    private const val PARAGRAPH = 2
    private const val LIST_ITEM = 3
    private const val IMAGE = 4
    private const val TABLE = 5
    private const val SEPARATOR = 6

    class Row(val type: Int, val level: Int, val text: String, val styles: String?, val extra: String?)

    fun encode(b: ContentBlock): Row = when (b) {
        is HeadingBlock -> Row(HEADING, b.level, b.text, null, null)
        is ParagraphBlock -> Row(PARAGRAPH, b.kind.ordinal, b.text, InlineStyle.encode(b.styles).ifEmpty { null }, null)
        is ListItemBlock -> Row(LIST_ITEM, b.level, b.text, InlineStyle.encode(b.styles).ifEmpty { null }, b.marker)
        is ImageBlock -> Row(
            IMAGE, b.kind.ordinal, b.caption ?: "", null,
            JSONObject().put("path", b.path).put("w", b.width).put("h", b.height)
                .put("pt", b.sourceWidth.toDouble()).put("ts", b.textSize.toDouble())
                .put("alt", b.altText ?: JSONObject.NULL).toString(),
        )
        is TableBlock -> Row(
            TABLE, b.headerRows, b.plainText, null,
            JSONObject()
                .put("rows", JSONArray().apply { b.rows.forEach { put(JSONArray(it)) } })
                .put("image", b.imagePath ?: JSONObject.NULL)
                .put("iw", b.imageWidth).put("ih", b.imageHeight)
                .put("caption", b.caption ?: JSONObject.NULL)
                .toString(),
        )
        is SeparatorBlock -> Row(SEPARATOR, 0, "", null, null)
    }

    fun decode(c: Cursor): ContentBlock? {
        val idx = c.getInt(0)
        val page = c.getInt(1)
        val level = c.getInt(3)
        val text = c.getString(4) ?: ""
        val styles = if (c.isNull(5)) null else c.getString(5)
        val extra = if (c.isNull(6)) null else c.getString(6)
        return try {
            when (c.getInt(2)) {
                HEADING -> HeadingBlock(idx, page, level, text)
                PARAGRAPH -> ParagraphBlock(idx, page, text, InlineStyle.decode(styles), ParagraphKind.entries.getOrElse(level) { ParagraphKind.BODY })
                LIST_ITEM -> ListItemBlock(idx, page, extra ?: "•", text, InlineStyle.decode(styles), level)
                IMAGE -> {
                    val o = JSONObject(extra ?: "{}")
                    ImageBlock(idx, page, o.getString("path"), o.optInt("w"), o.optInt("h"), text.ifEmpty { null },
                        ImageKind.entries.getOrElse(level) { ImageKind.FIGURE },
                        o.optDouble("pt", 0.0).toFloat(), o.optDouble("ts", 0.0).toFloat(),
                        if (o.isNull("alt")) null else o.optString("alt"))
                }
                TABLE -> {
                    val o = JSONObject(extra ?: "{}")
                    val rowsJson = o.getJSONArray("rows")
                    val rows = List(rowsJson.length()) { r ->
                        val row = rowsJson.getJSONArray(r)
                        List(row.length()) { row.optString(it) }
                    }
                    TableBlock(
                        idx, page, rows, level,
                        if (o.isNull("image")) null else o.optString("image"),
                        o.optInt("iw"), o.optInt("ih"),
                        if (o.isNull("caption")) null else o.optString("caption"),
                    )
                }
                SEPARATOR -> SeparatorBlock(idx, page)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
