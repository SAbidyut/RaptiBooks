package com.raptibooks.core.structure.layout

import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.geometry.Rect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** A horizontal piece of a line between two wide gaps (used for table/column analysis). */
data class Chunk(val left: Float, val right: Float, val textStart: Int, val textEnd: Int)

/**
 * One visual line of text (or one column-segment of a line). Text is still in extraction
 * order; Bangla normalisation happens in the second pass once the document profile is known.
 */
class TextLine(
    val text: String,
    val styles: List<InlineStyle>,
    val bounds: Rect,
    val baseline: Float,
    val fontSize: Float,
    val boldRatio: Float,
    val italicRatio: Float,
    val fontName: String,
    val chunks: List<Chunk> = emptyList(),
) {
    val isBold: Boolean get() = boldRatio >= 0.75f
    val isItalic: Boolean get() = italicRatio >= 0.75f
    val wordCount: Int get() = text.split(' ').count { it.isNotBlank() }

    override fun toString(): String = "Line('$text' size=$fontSize @${bounds.left},${bounds.top})"
}

enum class FigureKind { RASTER, VECTOR, MIXED, SCAN }

class FigureRegion(
    val bounds: Rect,
    val kind: FigureKind,
    val caption: String? = null,
    var imagePath: String? = null,
    var imageWidth: Int = 0,
    var imageHeight: Int = 0,
)

class TableRegion(
    val bounds: Rect,
    val rows: List<List<String>>,
    val headerRows: Int,
    val ruled: Boolean,
    val caption: String? = null,
    var imagePath: String? = null,
    var imageWidth: Int = 0,
    var imageHeight: Int = 0,
) {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

class PageLayout(
    val index: Int,
    val width: Float,
    val height: Float,
    val lines: List<TextLine>,
    val figures: List<FigureRegion>,
    val tables: List<TableRegion>,
    val fromOcr: Boolean,
    /** Number of meaningful characters on the page (all lines, including figure labels). */
    val charCount: Int,
) {
    companion object {
        private const val MAGIC = 0x52424C31 // "RBL1"

        fun write(layout: PageLayout, file: File) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(64 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(layout.index)
                out.writeFloat(layout.width)
                out.writeFloat(layout.height)
                out.writeBoolean(layout.fromOcr)
                out.writeInt(layout.charCount)
                out.writeInt(layout.lines.size)
                for (l in layout.lines) writeLine(out, l)
                out.writeInt(layout.figures.size)
                for (f in layout.figures) {
                    writeRect(out, f.bounds)
                    out.writeByte(f.kind.ordinal)
                    writeNullable(out, f.caption)
                    writeNullable(out, f.imagePath)
                    out.writeInt(f.imageWidth)
                    out.writeInt(f.imageHeight)
                }
                out.writeInt(layout.tables.size)
                for (t in layout.tables) {
                    writeRect(out, t.bounds)
                    out.writeInt(t.headerRows)
                    out.writeBoolean(t.ruled)
                    writeNullable(out, t.caption)
                    writeNullable(out, t.imagePath)
                    out.writeInt(t.imageWidth)
                    out.writeInt(t.imageHeight)
                    out.writeInt(t.rows.size)
                    for (row in t.rows) {
                        out.writeInt(row.size)
                        for (cell in row) writeString(out, cell)
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("cannot write ${file.path}")
            }
        }

        fun read(file: File): PageLayout =
            DataInputStream(file.inputStream().buffered(64 * 1024)).use { inp ->
                if (inp.readInt() != MAGIC) throw IOException("bad page cache ${file.name}")
                val index = inp.readInt()
                val width = inp.readFloat()
                val height = inp.readFloat()
                val ocr = inp.readBoolean()
                val chars = inp.readInt()
                val lines = List(inp.readInt()) { readLine(inp) }
                val figures = List(inp.readInt()) {
                    val b = readRect(inp)
                    val kind = FigureKind.entries[inp.readByte().toInt()]
                    val caption = readNullable(inp)
                    val path = readNullable(inp)
                    FigureRegion(b, kind, caption, path, inp.readInt(), inp.readInt())
                }
                val tables = List(inp.readInt()) {
                    val b = readRect(inp)
                    val header = inp.readInt()
                    val ruled = inp.readBoolean()
                    val caption = readNullable(inp)
                    val path = readNullable(inp)
                    val w = inp.readInt()
                    val h = inp.readInt()
                    val rows = List(inp.readInt()) { List(inp.readInt()) { readString(inp) } }
                    TableRegion(b, rows, header, ruled, caption, path, w, h)
                }
                PageLayout(index, width, height, lines, figures, tables, ocr, chars)
            }

        private fun writeLine(out: DataOutputStream, l: TextLine) {
            writeString(out, l.text)
            out.writeInt(l.styles.size)
            for (s in l.styles) {
                out.writeInt(s.start); out.writeInt(s.end); out.writeInt(s.flags)
            }
            writeRect(out, l.bounds)
            out.writeFloat(l.baseline)
            out.writeFloat(l.fontSize)
            out.writeFloat(l.boldRatio)
            out.writeFloat(l.italicRatio)
            writeString(out, l.fontName)
            out.writeInt(l.chunks.size)
            for (c in l.chunks) {
                out.writeFloat(c.left); out.writeFloat(c.right); out.writeInt(c.textStart); out.writeInt(c.textEnd)
            }
        }

        private fun readLine(inp: DataInputStream): TextLine {
            val text = readString(inp)
            val styles = List(inp.readInt()) { InlineStyle(inp.readInt(), inp.readInt(), inp.readInt()) }
            val bounds = readRect(inp)
            val baseline = inp.readFloat()
            val size = inp.readFloat()
            val bold = inp.readFloat()
            val italic = inp.readFloat()
            val font = readString(inp)
            val chunks = List(inp.readInt()) { Chunk(inp.readFloat(), inp.readFloat(), inp.readInt(), inp.readInt()) }
            return TextLine(text, styles, bounds, baseline, size, bold, italic, font, chunks)
        }

        private fun writeRect(out: DataOutputStream, r: Rect) {
            out.writeFloat(r.left); out.writeFloat(r.top); out.writeFloat(r.right); out.writeFloat(r.bottom)
        }

        private fun readRect(inp: DataInputStream) = Rect(inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat())

        private fun writeString(out: DataOutputStream, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readString(inp: DataInputStream): String {
            val n = inp.readInt()
            if (n < 0 || n > 16 * 1024 * 1024) throw IOException("corrupt string length $n")
            val bytes = ByteArray(n)
            inp.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun writeNullable(out: DataOutputStream, s: String?) {
            out.writeBoolean(s != null)
            if (s != null) writeString(out, s)
        }

        private fun readNullable(inp: DataInputStream): String? = if (inp.readBoolean()) readString(inp) else null
    }
}
