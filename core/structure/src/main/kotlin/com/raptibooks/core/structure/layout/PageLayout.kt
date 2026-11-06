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
    /**
     * Words whose text is unreadable, shown as printed: where each is on the page, by the number
     * its [InlineStyle.PICTURE] style carries in this line.
     */
    val pictures: List<Rect> = emptyList(),
    /** Share of the line's letters set in a monospace font (code, terminal output). */
    val monoRatio: Float = 0f,
) {
    val isMonospace: Boolean get() = monoRatio >= 0.5f
    val isBold: Boolean get() = boldRatio >= 0.75f
    val isItalic: Boolean get() = italicRatio >= 0.75f
    val wordCount: Int get() = text.split(' ').count { it.isNotBlank() }

    override fun toString(): String = "Line('$text' size=$fontSize @${bounds.left},${bounds.top})"
}

enum class FigureKind {
    RASTER, VECTOR, MIXED, SCAN,

    /** A display equation; [FigureRegion.caption] is its number, [FigureRegion.text] its text. */
    EQUATION,
}

class FigureRegion(
    /** Region on the page; narrowed to the printed content when the image is cropped. */
    var bounds: Rect,
    val kind: FigureKind,
    val caption: String? = null,
    var imagePath: String? = null,
    var imageWidth: Int = 0,
    var imageHeight: Int = 0,
    /** Typical size (pt) of the text printed inside the figure, 0 if it has none. */
    val textSize: Float = 0f,
    /** Text content (an equation's text, for search), if any. */
    val text: String? = null,
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

/**
 * A tinted callout box around text (worked example, definition, note). Colours are 0xRRGGBB.
 * [openTop]/[openBottom]: the box runs on from the previous page / onto the next one.
 */
class BoxRegion(
    val bounds: Rect,
    val fill: Int,
    val accent: Int,
    val openTop: Boolean = false,
    val openBottom: Boolean = false,
)

/**
 * A word shown as printed (see InlinePicture): where it is on the page, and once rendered, its
 * ink image. Its number in the book is [id] (the line styles carry it).
 */
class PictureRegion(
    val id: Int,
    var bounds: Rect,
    /** Size of the text around it, to show it at the same scale. */
    val fontSize: Float,
    var imagePath: String? = null,
    var imageWidth: Int = 0,
    var imageHeight: Int = 0,
)

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
    val boxes: List<BoxRegion> = emptyList(),
    val pictures: List<PictureRegion> = emptyList(),
) {
    companion object {
        private const val MAGIC = 0x52424C35 // "RBL5"

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
                    out.writeFloat(f.textSize)
                    writeNullable(out, f.text)
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
                out.writeInt(layout.boxes.size)
                for (b in layout.boxes) {
                    writeRect(out, b.bounds)
                    out.writeInt(b.fill)
                    out.writeInt(b.accent)
                    out.writeBoolean(b.openTop)
                    out.writeBoolean(b.openBottom)
                }
                out.writeInt(layout.pictures.size)
                for (p in layout.pictures) {
                    out.writeInt(p.id)
                    writeRect(out, p.bounds)
                    out.writeFloat(p.fontSize)
                    writeNullable(out, p.imagePath)
                    out.writeInt(p.imageWidth)
                    out.writeInt(p.imageHeight)
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
                    FigureRegion(b, kind, caption, path, inp.readInt(), inp.readInt(), inp.readFloat(), readNullable(inp))
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
                val boxes = List(inp.readInt()) {
                    BoxRegion(readRect(inp), inp.readInt(), inp.readInt(), inp.readBoolean(), inp.readBoolean())
                }
                val pictures = List(inp.readInt()) {
                    PictureRegion(inp.readInt(), readRect(inp), inp.readFloat(), readNullable(inp), inp.readInt(), inp.readInt())
                }
                PageLayout(index, width, height, lines, figures, tables, ocr, chars, boxes, pictures)
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
            out.writeFloat(l.monoRatio)
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
            val mono = inp.readFloat()
            return TextLine(text, styles, bounds, baseline, size, bold, italic, font, chunks, monoRatio = mono)
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
