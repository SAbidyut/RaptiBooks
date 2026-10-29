package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.InlinePicture
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.ImageRegion
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.text.UnreadableText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The parts of a page in a script its text layer could not hold (Arabic in an English book whose
 * OCR read only Latin letters), beyond single words (see [LineBuilder.pictureWords]): lines the
 * OCR left as images, grids of words, tables with a column of them, patches of the scan stuck
 * over a line of text.
 */
object ForeignText {
    /** Starts a line of words shown as printed from a right-to-left script. */
    const val RTL_MARK = '‏'

    fun foreign(l: TextLine): Boolean = l.text.startsWith(RTL_MARK)

    /**
     * Lines of the other script left as images: pictures the height of a line of text, on no line
     * of the text layer, two or more of them ending at the same right edge. Each becomes a line
     * read from the right holding that one picture.
     */
    fun imageLines(page: RawPage, lines: List<TextLine>): List<Pair<ImageRegion, TextLine>> {
        val candidates = page.images.filter { img ->
            val b = img.bounds
            b.height in 7f..34f && b.width >= b.height * 1.8f && b.width <= page.width * 0.6f && b.centerX > page.width * 0.5f &&
                b.top > page.height * 0.06f && b.bottom < page.height * 0.94f &&
                lines.none { l -> l.bounds.horizontalOverlap(b) > 2f && l.bounds.verticalOverlap(b) > min(l.bounds.height, b.height) * 0.3f }
        }
        return candidates.filter { a -> candidates.count { abs(it.bounds.right - a.bounds.right) <= 4f } >= 2 }.map { img ->
            val b = img.bounds
            val size = b.height / 1.5f
            img to TextLine(
                "$RTL_MARK${InlinePicture.MARKER}", listOf(InlineStyle(1, 2, InlineStyle.pictureFlags(0))),
                b, b.bottom - size * 0.45f, size, 0f, 0f, "", emptyList(), listOf(b.outset(0.5f)),
            )
        }
    }

    /**
     * A line of the other script printed level with the first line of a sentence that goes on
     * below it (the two cells of a table row without rules: "6. The office director's car (the
     * car of the director / of the office)." and its Arabic) comes at the end of the sentence, as
     * one picture.
     */
    fun joinCells(lines: List<TextLine>): List<TextLine> {
        val moved = HashMap<TextLine, TextLine>()
        val out = lines.toMutableList()
        for (f in lines) {
            if (!foreign(f) || f.pictures.isEmpty()) continue
            val first = lines.firstOrNull { e ->
                !foreign(e) && e.pictures.isEmpty() && e.bounds.right < f.bounds.left &&
                    e.bounds.verticalOverlap(f.bounds) >= min(e.bounds.height, f.bounds.height) * 0.4f && !Patterns.endsSentence(e.text)
            } ?: continue
            var last = first
            while (true) {
                val l = last
                val next = lines.firstOrNull { e ->
                    e !== l && !foreign(e) && e.bounds.top >= l.bounds.top + l.fontSize * 0.5f &&
                        e.bounds.top - l.bounds.bottom <= l.fontSize * 0.8f && abs(e.bounds.left - first.bounds.left) <= l.fontSize * 1.5f &&
                        e.bounds.right < f.bounds.left && abs(e.fontSize - l.fontSize) < 1f
                } ?: break
                last = next
                if (Patterns.endsSentence(next.text)) break
            }
            if (last === first) continue
            // nothing else beside the sentence's lines
            if (lines.any { o -> o !== f && o.bounds.left >= f.bounds.left - 2f && o.bounds.top < last.bounds.bottom && o.bounds.bottom > f.bounds.bottom }) continue
            val target = moved[last] ?: last
            val picture = Rect.bounding(f.pictures)!!
            val text = target.text.trimEnd() + " " + InlinePicture.MARKER
            val styles = target.styles + InlineStyle(text.length - 1, text.length, InlineStyle.pictureFlags(target.pictures.size))
            moved[last] = TextLine(
                text, styles, target.bounds, target.baseline, target.fontSize, target.boldRatio, target.italicRatio, target.fontName,
                target.chunks, target.pictures + picture,
            )
            out.remove(f)
        }
        return out.map { moved[it] ?: it }
    }

    /**
     * A grid of words of the other script (a drill's word list: rows of words set wide apart) is
     * shown as printed, whole, with the marks and bits of junk the OCR left among them. Returns
     * each grid with the lines it takes in.
     */
    fun grids(lines: List<TextLine>): List<Pair<FigureRegion, Set<TextLine>>> {
        val foreignLines = lines.filter { foreign(it) && it.pictures.isNotEmpty() }.sortedBy { it.bounds.top }
        if (foreignLines.size < 4) return emptyList()
        val clusters = mutableListOf<MutableList<TextLine>>()
        for (l in foreignLines) {
            val c = clusters.lastOrNull()
            val bottom = c?.maxOf { it.bounds.bottom }
            if (c != null && bottom != null && l.bounds.top <= bottom + max(l.bounds.height, 12f) * 1.5f) c += l else clusters += mutableListOf(l)
        }
        val out = mutableListOf<Pair<FigureRegion, Set<TextLine>>>()
        for (c in clusters) {
            if (c.size < 4) continue
            val heights = c.flatMap { l -> l.pictures.map { it.height } }.sorted()
            val wordHeight = heights[heights.size / 2]
            // rows of words with a column's width between them, or pieces of a row side by side
            val wideRows = c.count { l ->
                l.pictures.sortedBy { it.left }.zipWithNext().any { (a, b) -> b.left - a.right >= max(36f, wordHeight * 2.5f) }
            }
            val sideBySide = c.count { a ->
                c.any { b -> b !== a && b.bounds.horizontalOverlap(a.bounds) <= 0f && b.bounds.verticalOverlap(a.bounds) >= min(a.bounds.height, b.bounds.height) * 0.3f }
            }
            if (wideRows < 2 && sideBySide < 4) continue
            val bounds = Rect.bounding(c.flatMap { it.pictures + it.bounds })!!.outset(2f)
            val inside = lines.filter { it.bounds.coveredBy(bounds) >= 0.5f }
            // (a grid has no English in it: not a passage with words of the other script)
            if (inside.any { l -> !foreign(l) && UnreadableText.tokens(l.text).count { UnreadableText.englishLike(it.second) } >= 2 }) continue
            out += FigureRegion(bounds, FigureKind.VECTOR, textSize = wordHeight / 1.5f) to (inside + c).toSet()
        }
        return out
    }

    /**
     * Patches of the scan kept as images over a line of text whose words are shown as printed
     * (the OCR could not read them): the pictures of those words show them, so they are no figure.
     */
    fun patches(images: List<ImageRegion>, lines: List<TextLine>): Set<ImageRegion> {
        val pictures = lines.flatMap { it.pictures }
        if (pictures.isEmpty()) return emptySet()
        return images.filter { img ->
            val b = img.bounds
            b.height <= 40f && pictures.any { p ->
                val common = p.intersect(b)?.area ?: 0f
                common >= b.area * 0.3f || common >= p.area * 0.5f
            }
        }.toSet()
    }

    /**
     * A table with a column of the other script that the text layer holds only as junk
     * ("/'.u'l ylUJI", ". 6.. t:l.-..i"): marks among the letters of the cell, and hardly a word
     * like English (junk can look like one: ".ljti.-").
     * On a page with no words shown as printed ([foreignPage] false), only with plain garbage
     * ("bl•l", ".l:JI", "lAI\") in two rows or more.
     */
    fun unreadable(t: TableRegion, foreignPage: Boolean = true): Boolean {
        if (t.rows.isEmpty()) return false
        val rows = t.rows.count { r ->
            r.any { cell ->
                val tokens = UnreadableText.tokens(cell)
                val bad = tokens.count { UnreadableText.garbled(it.second) || UnreadableText.junk(it.second) }
                bad >= 1 && tokens.count { UnreadableText.englishLike(it.second) } * 3 <= bad && cell.any { it.isLetter() }
            }
        }
        if (rows * 2 < t.rows.size) return false
        if (foreignPage) return true
        val garbled = t.rows.map { r -> r.sumOf { cell -> UnreadableText.tokens(cell).count { UnreadableText.garbled(it.second) } } }
        return garbled.sum() >= 3 && garbled.count { it > 0 } >= 2
    }
}
