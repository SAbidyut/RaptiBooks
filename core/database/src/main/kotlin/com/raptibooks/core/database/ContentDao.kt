package com.raptibooks.core.database

import android.content.ContentValues
import android.database.Cursor
import com.raptibooks.core.model.Bookmark
import com.raptibooks.core.model.BoxSpan
import com.raptibooks.core.model.Highlight
import com.raptibooks.core.model.InkCodec
import com.raptibooks.core.model.InkStroke
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.MarkSpan
import com.raptibooks.core.model.Note
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.model.Marking
import com.raptibooks.core.model.MarkKey
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlinePicture
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
            delete("boxes", "book_id = ?", arrayOf(bookId.toString()))
            // Marks point into the old blocks; they cannot follow a re-conversion.
            delete("highlights", "book_id = ?", arrayOf(bookId.toString()))
            delete("ink", "book_id = ?", arrayOf(bookId.toString()))
        }
        db.notifyChanged(RaptiDatabase.BLOCKS, RaptiDatabase.TOC, RaptiDatabase.BOXES, RaptiDatabase.HIGHLIGHTS, RaptiDatabase.INK)
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

    // ------------------------------------------------------------------ boxes

    fun saveBoxes(bookId: Long, boxes: List<BoxSpan>) {
        db.transaction {
            delete("boxes", "book_id = ?", arrayOf(bookId.toString()))
            for (b in boxes) {
                insert(
                    "boxes", null,
                    ContentValues().apply {
                        put("book_id", bookId)
                        put("start_idx", b.startIndex)
                        put("end_idx", b.endIndex)
                        put("fill", b.fill)
                        put("accent", b.accent)
                    },
                )
            }
        }
        db.notifyChanged(RaptiDatabase.BOXES)
    }

    fun boxes(bookId: Long): List<BoxSpan> =
        db.readableDatabase.rawQuery(
            "SELECT start_idx, end_idx, fill, accent FROM boxes WHERE book_id = ? ORDER BY start_idx",
            arrayOf(bookId.toString()),
        ).use { c ->
            val out = ArrayList<BoxSpan>(c.count)
            while (c.moveToNext()) out += BoxSpan(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
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

    // ------------------------------------------------------------------ highlights

    fun observeHighlights(bookId: Long): Flow<List<Highlight>> = db.observe(RaptiDatabase.HIGHLIGHTS) {
        db.readableDatabase.rawQuery(
            "SELECT id, book_id, block_idx, start_pos, end_pos, color, part FROM highlights WHERE book_id = ? ORDER BY block_idx, part, start_pos",
            arrayOf(bookId.toString()),
        ).use { c ->
            val out = ArrayList<Highlight>(c.count)
            while (c.moveToNext()) {
                val color = MarkColor.fromId(c.getInt(5)) ?: continue
                out += Highlight(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), color, c.getInt(6))
            }
            out
        }
    }

    /**
     * Marks (or, with a null [color], un-marks) character [edits] per text (see [MarkKey]),
     * cutting and joining the marks already there. All texts are written in one transaction.
     */
    fun markRanges(bookId: Long, edits: Map<Long, List<IntRange>>, color: MarkColor?) {
        if (edits.isEmpty()) return
        db.transaction {
            for ((key, ranges) in edits) {
                val block = MarkKey.block(key)
                val part = MarkKey.part(key)
                val args = arrayOf(bookId.toString(), block.toString(), part.toString())
                val existing = ArrayList<MarkSpan>()
                rawQuery("SELECT start_pos, end_pos, color FROM highlights WHERE book_id = ? AND block_idx = ? AND part = ?", args).use { c ->
                    while (c.moveToNext()) MarkColor.fromId(c.getInt(2))?.let { existing += MarkSpan(c.getInt(0), c.getInt(1), it) }
                }
                val result = Marking.apply(existing, ranges, color)
                if (result == existing) continue
                delete("highlights", "book_id = ? AND block_idx = ? AND part = ?", args)
                for (s in result) {
                    insert(
                        "highlights", null,
                        ContentValues().apply {
                            put("book_id", bookId)
                            put("block_idx", block)
                            put("start_pos", s.start)
                            put("end_pos", s.end)
                            put("color", s.color.id)
                            put("part", part)
                        },
                    )
                }
            }
        }
        db.notifyChanged(RaptiDatabase.HIGHLIGHTS)
    }

    // ------------------------------------------------------------------ ink

    fun observeInk(bookId: Long): Flow<List<InkStroke>> = db.observe(RaptiDatabase.INK) {
        db.readableDatabase.rawQuery(
            "SELECT id, book_id, block_idx, color, points FROM ink WHERE book_id = ? ORDER BY id",
            arrayOf(bookId.toString()),
        ).use { c ->
            val out = ArrayList<InkStroke>(c.count)
            while (c.moveToNext()) {
                val color = MarkColor.fromId(c.getInt(3)) ?: continue
                val points = InkCodec.decode(c.getString(4))
                if (points.size >= 2) out += InkStroke(c.getLong(0), c.getLong(1), c.getInt(2), color, points)
            }
            out
        }
    }

    fun addInk(bookId: Long, blockIndex: Int, color: MarkColor, points: FloatArray) {
        if (points.size < 2) return
        db.writableDatabase.insert(
            "ink", null,
            ContentValues().apply {
                put("book_id", bookId)
                put("block_idx", blockIndex)
                put("color", color.id)
                put("points", InkCodec.encode(points))
            },
        )
        db.notifyChanged(RaptiDatabase.INK)
    }

    fun deleteInk(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.transaction { for (id in ids) delete("ink", "id = ?", arrayOf(id.toString())) }
        db.notifyChanged(RaptiDatabase.INK)
    }

    // ------------------------------------------------------------------ notes

    fun observeNotes(bookId: Long): Flow<List<Note>> = db.observe(RaptiDatabase.NOTES) {
        db.readableDatabase.rawQuery(
            "SELECT id, book_id, block_idx, page, x, y, kind, text, audio_path, duration, created_at FROM notes WHERE book_id = ? ORDER BY block_idx, y, id",
            arrayOf(bookId.toString()),
        ).use { c ->
            val out = ArrayList<Note>(c.count)
            while (c.moveToNext()) {
                out += Note(
                    c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getFloat(4), c.getFloat(5),
                    NoteKind.fromId(c.getInt(6)), c.getString(7) ?: "", if (c.isNull(8)) null else c.getString(8),
                    c.getLong(9), c.getLong(10),
                )
            }
            out
        }
    }

    fun addNote(
        bookId: Long, blockIndex: Int, page: Int, x: Float, y: Float,
        kind: NoteKind, text: String, audioPath: String?, durationMs: Long,
    ): Long {
        val id = db.writableDatabase.insert(
            "notes", null,
            ContentValues().apply {
                put("book_id", bookId)
                put("block_idx", blockIndex)
                put("page", page)
                put("x", x)
                put("y", y)
                put("kind", kind.id)
                put("text", text)
                put("audio_path", audioPath)
                put("duration", durationMs)
                put("created_at", System.currentTimeMillis())
            },
        )
        db.notifyChanged(RaptiDatabase.NOTES)
        return id
    }

    fun updateNoteText(id: Long, text: String) {
        db.writableDatabase.update("notes", ContentValues().apply { put("text", text) }, "id = ?", arrayOf(id.toString()))
        db.notifyChanged(RaptiDatabase.NOTES)
    }

    fun deleteNote(id: Long) {
        db.writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
        db.notifyChanged(RaptiDatabase.NOTES)
    }

    /** After a re-conversion, puts every note at the top of the first block of its page. */
    fun remapNotes(bookId: Long) {
        db.writableDatabase.execSQL(
            "UPDATE notes SET y = 0.05, block_idx = COALESCE((SELECT MIN(idx) FROM blocks b WHERE b.book_id = notes.book_id " +
                "AND b.page >= notes.page), 0) WHERE book_id = ?",
            arrayOf<Any>(bookId),
        )
        db.notifyChanged(RaptiDatabase.NOTES)
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
        is ParagraphBlock -> Row(
            PARAGRAPH, b.kind.ordinal, b.text, InlineStyle.encode(b.styles).ifEmpty { null },
            if (b.pictures.isEmpty()) null else JSONObject().put("pics", encodePictures(b.pictures)).toString(),
        )
        is ListItemBlock -> Row(
            LIST_ITEM, b.level, b.text, InlineStyle.encode(b.styles).ifEmpty { null },
            if (b.pictures.isEmpty()) b.marker else JSONObject().put("m", b.marker).put("pics", encodePictures(b.pictures)).toString(),
        )
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

    /** Word pictures as [start, path, width px, height px, height em] arrays. */
    private fun encodePictures(pictures: List<InlinePicture>): JSONArray = JSONArray().apply {
        for (p in pictures) put(JSONArray().put(p.start).put(p.path).put(p.pixelWidth).put(p.pixelHeight).put(p.height.toDouble()))
    }

    private fun decodePictures(a: JSONArray?): List<InlinePicture> {
        if (a == null) return emptyList()
        return List(a.length()) { i ->
            val p = a.getJSONArray(i)
            InlinePicture(p.getInt(0), p.getString(1), p.getInt(2), p.getInt(3), p.getDouble(4).toFloat())
        }
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
                PARAGRAPH -> ParagraphBlock(
                    idx, page, text, InlineStyle.decode(styles), ParagraphKind.entries.getOrElse(level) { ParagraphKind.BODY },
                    extra?.let { decodePictures(JSONObject(it).optJSONArray("pics")) } ?: emptyList(),
                )
                LIST_ITEM -> {
                    // the marker, or the marker and the item's word pictures
                    val o = if (extra != null && extra.startsWith("{\"m\"")) JSONObject(extra) else null
                    ListItemBlock(idx, page, o?.optString("m") ?: extra ?: "•", text, InlineStyle.decode(styles), level, decodePictures(o?.optJSONArray("pics")))
                }
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
