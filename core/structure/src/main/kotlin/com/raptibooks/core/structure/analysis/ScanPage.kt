package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.InkMap
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.text.GrayImage
import com.raptibooks.core.structure.text.TextCleaner
import com.raptibooks.core.structure.text.UnreadableText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pages that are a scan under an invisible OCR text layer (a book run through Acrobat or CVISION:
 * one picture of the page, the OCR's words on top of it). The text layer has the words, but not
 * the drawings, which are part of the picture, and it garbles the formulas ("F = nuJ" for
 * F = ma). The page's [InkMap] shows both: drawings are ink no line of text covers, and display
 * formulas are shown as printed.
 */
object ScanPage {
    /** Size of the cells of the ink map, in points. */
    private const val CELL = 2f

    /** Grey level under which a pixel of the page rendered at one pixel per point is ink. */
    private const val DARK = 160

    /** One picture covering the page, under an invisible text layer (not our own OCR's). */
    fun isScan(page: RawPage): Boolean =
        !page.fromOcr && page.invisibleText && page.fragments.count { !it.isSpace } >= 20 &&
            page.images.any { it.bounds.area >= page.width * page.height * 0.85f }

    /** [page] with its [InkMap], from the page rendered at one pixel per point. */
    fun withInk(page: RawPage, render: (Rect, Float) -> GrayImage?): RawPage {
        val img = try {
            render(Rect(0f, 0f, page.width, page.height), 1f)
        } catch (e: Exception) {
            null
        } ?: return page
        val cols = ceil(page.width / CELL).toInt()
        val rows = ceil(page.height / CELL).toInt()
        val sx = img.width / page.width
        val sy = img.height / page.height
        val dark = BooleanArray(cols * rows)
        for (y in 0 until img.height) {
            val r = (y / sy / CELL).toInt().coerceAtMost(rows - 1)
            for (x in 0 until img.width) {
                if (img.pixels[y * img.width + x] < DARK) dark[r * cols + (x / sx / CELL).toInt().coerceAtMost(cols - 1)] = true
            }
        }
        return RawPage(
            page.index, page.width, page.height, page.fragments, page.images, page.paths, page.rotatedText, page.fromOcr,
            page.ocrPictures, InkMap(cols, rows, CELL, dark), page.invisibleText,
        )
    }

    /** "(1-7)", "( 1-21)", "(I- 25b)", "(3--4)", "(1 -1 0)": an equation's number as the OCR read it. */
    private val EQ_NUMBER = Regex("^\\(\\s*[0-9IlA-Z]{1,2}\\s*[-–—−]+\\s*[0-9Il](?:\\s?[0-9Il]){0,2}[a-z]?\\s*\\)$")

    /** The equation number in the book's form: "(1–7)". */
    private fun number(text: String): String {
        val t = text.filter { !it.isWhitespace() }
        val dash = t.indexOfFirst { it in "-–—−" }
        fun digits(s: String) = s.replace('I', '1').replace('l', '1')
        return "(" + digits(t.substring(1, dash)) + "–" + digits(t.substring(dash).trimStart('-', '–', '—', '−').dropLast(1)) + ")"
    }

    /**
     * What the ink of a page shows beyond its text: display formulas (shown as printed, their
     * numbers as captions) and drawings (handed to the figure detector as pictures).
     */
    class Ink(val equations: EquationDetector.Result, val drawings: List<Rect>)

    fun read(page: RawPage, lines: List<TextLine>, bodySize: Float): Ink {
        val ink = page.ink ?: return Ink(EquationDetector.Result(emptyList(), emptySet(), emptySet()), emptyList())
        // The ink of the text is not a drawing (the boxes of the OCR's words are a little smaller
        // than their letters). Junk the OCR read in a drawing (", , • cPM", "II ,,", at sizes no
        // text has) is no text: the drawing's lines are under it.
        val text = lines.filter { l -> l.fontSize <= bodySize * 1.6f && texty(l.text) }
        val comps = components(ink, text.map { Rect(it.bounds.left - 2f, it.bounds.top - it.fontSize * 0.2f, it.bounds.right + 2f, it.bounds.bottom + it.fontSize * 0.2f) })
        val prose = lines.filter { it.wordCount >= 6 && prose(it) }
        // Drawings: big pieces of ink outside the text (a formula leaves only bars and frames),
        // and tables ruled across the page
        val rules = comps.filter { it.height <= 4f && it.width >= page.width * 0.45f }
        // (ink with an equation's number on its row is a formula: a fraction, a matrix)
        val numbers = lines.filter { EQ_NUMBER.matches(it.text.trim()) && it.bounds.left > page.width * 0.5f }
        var drawings = comps.filter { c ->
            c.width >= bodySize * 3f && c.height >= bodySize * 3f && numbers.none { n -> n.bounds.verticalOverlap(c) > 0f && n.bounds.left >= c.right }
        } + ruledTables(rules, lines, prose, bodySize)
        // with the lines and arrows that touch them (a dimension line above a drawing) and the
        // labels printed across them ("T = (perpendicular distance) (force)"), not a caption or
        // a line of the text
        val captions = captionBlocks(lines)
        val pieces = comps.filter { it !in drawings && it.width + it.height >= bodySize * 1.5f }
        drawings = drawings.map { d ->
            var b = d
            var grown = true
            while (grown) {
                grown = false
                for (p in pieces) {
                    if (b.contains(p) || !b.intersects(p, 6f)) continue
                    b = b.union(p)
                    grown = true
                }
                for (l in lines) {
                    if (b.contains(l.bounds) || !b.intersects(l.bounds) || l in captions) continue
                    if (l.wordCount >= 8 && l.bounds.width >= page.width * 0.4f) continue
                    b = b.union(l.bounds)
                    grown = true
                }
            }
            b
        }
        // panels of one figure, close together with no text between them, are one drawing
        val merged = drawings.toMutableList()
        var joined = true
        while (joined) {
            joined = false
            loop@ for (i in merged.indices) for (j in i + 1 until merged.size) {
                val a = merged[i]
                val b = merged[j]
                val gap = max(max(a.left - b.right, b.left - a.right), max(a.top - b.bottom, b.top - a.bottom))
                if (gap > bodySize * 5f) continue
                val u = a.union(b)
                // (with a gap wider than a line, only labels between them: "(a)", "Note:")
                val between = lines.filter { l -> u.intersects(l.bounds) && !a.intersects(l.bounds) && !b.intersects(l.bounds) }
                if (between.any { it in prose || Patterns.caption.containsMatchIn(it.text) }) continue
                if (gap > bodySize * 2.5f && between.any { l -> UnreadableText.tokens(l.text).count { UnreadableText.englishLike(it.second) } >= 3 }) continue
                merged[i] = u
                merged.removeAt(j)
                joined = true
                break@loop
            }
        }
        // a caption printed beside a drawing, over the end of its axis: the drawing stops at it
        val captionBoxes = captions.groupBy { c -> lines.firstOrNull { it in captions && Patterns.caption.containsMatchIn(it.text) && it.bounds.left == c.bounds.left } }
            .values.map { Rect.bounding(it.map { l -> l.bounds })!! }
        val clipped = merged.map { d ->
            captionBoxes.filter { it.intersects(d) && it.left > d.left + d.width * 0.5f }.minOfOrNull { it.left }?.let { Rect(d.left, d.top, it - 2f, d.bottom) } ?: d
        }
        drawings = figuresAboveCaptions(page, clipped, lines, prose, captionBoxes, bodySize)
        // program listings, garbled by the OCR, are shown as printed too
        drawings = drawings + codeBlocks(lines, bodySize).filter { c -> drawings.none { it.intersects(c) } }
        val inDrawing = { l: TextLine -> drawings.any { it.outset(bodySize * 0.5f).contains(l.bounds.centerX, l.bounds.centerY) } }
        // (pictures CVISION kept apart from the page: formulas in display, beside no text)
        val pictures = page.images.map { it.bounds }.filter { b ->
            b.area < page.width * page.height * 0.85f && b.height <= bodySize * 5f && b.width <= page.width * 0.6f &&
                drawings.none { it.intersects(b) } && prose.none { it.bounds.intersects(b) }
        }
        val found = equations(page, lines.filter { !inDrawing(it) }, pictures, comps - drawings.toSet(), bodySize)
        // a drawing that is mostly a formula's ink (a big fraction) is part of the formula; a
        // formula grown into a drawing is one of its labels
        val kept = drawings.filter { d -> found.equations.none { e -> (e.bounds.intersect(d)?.area ?: 0f) >= d.area * 0.6f } }
        val equations = found.equations.filter { e -> kept.none { it.intersects(e.bounds) } }
        val dropped = found.equations - equations.toSet()
        return Ink(
            EquationDetector.Result(equations, found.absorbed.filter { l -> dropped.none { it.bounds.intersects(l.bounds) } }.toSet(), emptySet()),
            kept,
        )
    }

    /** A line of a program (MATLAB): a comment, a statement ended by ";", a command. */
    private val CODE = Regex("^\\s*%|;\\s*(%.*)?$|^\\s*(?:for|end|if|else|elseif|while|function|disp|plot|title|xlabel|ylabel|axis|hold|figure|grid|legend)\\b")

    /** Listings: three lines of code or more, one under another, a line of other text among them at most. */
    private fun codeBlocks(lines: List<TextLine>, bodySize: Float): List<Rect> {
        val sorted = lines.sortedBy { it.bounds.top }
        val out = mutableListOf<Rect>()
        var block = mutableListOf<TextLine>()
        var code = 0
        var others = 0
        fun close() {
            if (code >= 3) out += Rect.bounding(block.map { it.bounds })!!.outset(2f)
            block = mutableListOf()
            code = 0
            others = 0
        }
        for (l in sorted) {
            val isCode = CODE.containsMatchIn(l.text)
            val last = block.lastOrNull()
            // (a listing's lines follow one another; its comments share their rows)
            if (last != null && l.bounds.top - block.maxOf { it.bounds.bottom } > bodySize * 1.5f) close()
            if (!isCode && (others >= 1 || block.isEmpty())) {
                close()
                continue
            }
            block += l
            if (isCode) code++ else others++
        }
        close()
        return out
    }

    /** Captions: "FIGURE 5-9" and the lines of its description under it. */
    private fun captionBlocks(lines: List<TextLine>): Set<TextLine> {
        val out = HashSet<TextLine>()
        for (c in lines.filter { Patterns.caption.containsMatchIn(it.text) }) {
            out += c
            var last = c
            while (true) {
                val l = last
                val next = lines.filter { o ->
                    o !in out && o.bounds.top >= l.bounds.top + l.fontSize * 0.5f && o.bounds.top - l.bounds.bottom <= l.fontSize * 0.8f &&
                        abs(o.bounds.left - c.bounds.left) <= c.fontSize
                }.minByOrNull { it.bounds.top } ?: break
                out += next
                last = next
                if (out.size > 8) break
            }
        }
        return out
    }

    /**
     * A figure whose caption ("FIGURE 2-44", at the left edge of the text) is printed under it
     * reaches up from the caption to the text above it: drawings, labels and formulas of its
     * panels, one picture.
     */
    private fun figuresAboveCaptions(
        page: RawPage,
        drawings: List<Rect>,
        lines: List<TextLine>,
        prose: List<TextLine>,
        captionBoxes: List<Rect>,
        bodySize: Float,
    ): List<Rect> {
        if (drawings.isEmpty() || lines.isEmpty()) return drawings
        // (a page of figures has no text to tell where the column starts: its captions do)
        val colLeft = prose.map { it.bounds.left }.groupBy { (it / 4f).toInt() }.maxByOrNull { it.value.size }?.value?.min()
            ?: lines.minOf { it.bounds.left }
        val out = drawings.toMutableList()
        val captions = lines.filter { Patterns.caption.containsMatchIn(it.text) && !Patterns.tableCaption.containsMatchIn(it.text) && it.bounds.left <= colLeft + bodySize }
        for (c in captions) {
            var top = c.bounds.top
            val taken = mutableListOf<Rect>()
            var grew = true
            while (grew) {
                grew = false
                val next = (out.map { it to (null as TextLine?) } + lines.map { it.bounds to it }).filter { (r, l) ->
                    // (a gap of a few lines only between the panels of the figure)
                    l !== c && r !in taken && r.bottom <= top + 2f && top - r.bottom <= bodySize * (if (l == null) 6f else 3f) && r.top < top &&
                        r.bottom > page.height * 0.08f
                }
                // (a drawing with its own caption beside it is a figure of its own)
                fun captioned(r: Rect) = captionBoxes.any { b ->
                    !b.intersects(c.bounds) && b.verticalOverlap(r) >= b.height * 0.3f && max(b.left - r.right, r.left - b.right) <= bodySize * 3f
                }
                if (next.isEmpty() || next.any { (r, l) -> if (l == null) captioned(r) else l in prose || Patterns.caption.containsMatchIn(l.text) }) break
                for ((r, _) in next) taken += r
                top = min(top, next.minOf { it.first.top })
                grew = true
            }
            if (taken.none { it in out }) continue
            val figure = Rect.bounding(taken)!!
            out.removeAll { it.intersects(figure) }
            out += figure
        }
        return out
    }

    /**
     * Tables ruled across the page: two rules or more one above another (above and under the
     * header row), with the rows printed under the last one.
     */
    private fun ruledTables(rules: List<Rect>, lines: List<TextLine>, prose: List<TextLine>, bodySize: Float): List<Rect> {
        val out = mutableListOf<Rect>()
        val sorted = rules.sortedBy { it.top }
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].top - sorted[j].bottom <= bodySize * 6f &&
                sorted[j + 1].horizontalOverlap(sorted[j]) >= min(sorted[j].width, sorted[j + 1].width) * 0.8f &&
                prose.none { l -> l.bounds.top >= sorted[j].bottom && l.bounds.bottom <= sorted[j + 1].top }
            ) j++
            if (j > i) {
                var b = Rect.bounding(sorted.subList(i, j + 1))!!
                // the rows under the last rule, up to a gap or a line of text
                var bottom = b.bottom
                for (l in lines.filter { it.bounds.top >= b.bottom - 1f && it.bounds.horizontalOverlap(b) > 0f }.sortedBy { it.bounds.top }) {
                    if (l.bounds.top - bottom > bodySize * 1.2f || l in prose) break
                    bottom = max(bottom, l.bounds.bottom)
                }
                // and its caption above ("Table A-1" and the line under it), printed with it
                var top = b.top
                val above = lines.filter { it.bounds.bottom <= b.top + 1f && it.bounds.horizontalOverlap(b) > 0f }.sortedByDescending { it.bounds.bottom }.take(2)
                val label = above.indexOfFirst { Patterns.tableCaption.containsMatchIn(it.text) }
                if (label >= 0 && b.top - above[label].bounds.top <= bodySize * 4f) top = above[label].bounds.top
                b = Rect(b.left, top, b.right, bottom)
                out += b
            }
            i = j + 1
        }
        return out
    }

    /** Pieces of ink outside [covered], joined across gaps of a few points; small specks dropped. */
    private fun components(ink: InkMap, covered: List<Rect>): List<Rect> {
        val free = ink.dark.copyOf()
        for (r in covered) {
            val c0 = (r.left / ink.cell).toInt().coerceAtLeast(0)
            val c1 = (r.right / ink.cell).toInt().coerceAtMost(ink.cols - 1)
            val r0 = (r.top / ink.cell).toInt().coerceAtLeast(0)
            val r1 = (r.bottom / ink.cell).toInt().coerceAtMost(ink.rows - 1)
            for (y in r0..r1) for (x in c0..c1) free[y * ink.cols + x] = false
        }
        val seen = BooleanArray(free.size)
        val out = mutableListOf<Rect>()
        val queue = IntArray(free.size)
        val reach = 2
        for (start in free.indices) {
            if (!free[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            var count = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % ink.cols
                val y = i / ink.cols
                count++
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -reach..reach) for (dx in -reach..reach) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= ink.cols || ny >= ink.rows) continue
                    val j = ny * ink.cols + nx
                    if (free[j] && !seen[j]) {
                        seen[j] = true
                        queue[tail++] = j
                    }
                }
            }
            if (count < 3) continue
            out += Rect(minX * ink.cell, minY * ink.cell, (maxX + 1) * ink.cell, (maxY + 1) * ink.cell)
        }
        return out
    }

    /** Words or numbers, not the junk an OCR reads in the lines of a drawing. */
    private fun texty(text: String): Boolean {
        val solid = text.count { !it.isWhitespace() }
        if (solid == 0 || text.count { it.isLetterOrDigit() } < solid * 0.6f) return false
        return UnreadableText.tokens(text).any { (_, t) ->
            val core = t.trim { !it.isLetterOrDigit() }
            (core.length >= 2 && core.all { it.isLetter() } && core.any { it.lowercaseChar() in "aeiouy" }) || (core.isNotEmpty() && core.all { it.isDigit() || it == '.' })
        }
    }

    /** Names of functions in formulas, which look like words. */
    private val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "log", "exp", "max", "min", "rms", "avg")

    private fun prose(l: TextLine): Boolean {
        val tokens = UnreadableText.tokens(l.text)
        val english = tokens.count { (_, t) -> UnreadableText.englishLike(t) && t.trim('(', ')', ',', '.').lowercase() !in FUNCTIONS }
        return english >= 3 || (english >= 2 && english * 2 >= tokens.size)
    }

    /**
     * "ℱ = magnetomotive force of circuit": a symbol, set in from the text, defined in words.
     * The OCR garbles the symbol; the line is shown as printed with the formula it explains.
     */
    private fun definition(l: TextLine, colLeft: Float, bodySize: Float): Boolean {
        val eq = l.text.indexOf('=')
        return eq in 1..6 && l.bounds.left >= colLeft + bodySize * 1.5f && l.wordCount <= 10
    }

    private fun relation(text: String) = text.any { it in MathText.RELATIONS || it in MathText.BIG_OPERATORS }

    /**
     * Display formulas: lines of math (a relation sign, an equation number on their row, or set
     * in from the text) between the lines of text, with the bars, symbols and frames the text
     * layer left out around them.
     */
    private class Block(var bounds: Rect, val lines: MutableList<TextLine> = mutableListOf(), var picture: Boolean = false)

    private fun equations(page: RawPage, lines: List<TextLine>, pictures: List<Rect>, specks: List<Rect>, bodySize: Float): EquationDetector.Result {
        val prose = lines.filter { prose(it) && it.wordCount >= 5 }
        val colLeft = prose.map { it.bounds.left }.let { lefts -> lefts.groupBy { (it / 4f).toInt() }.maxByOrNull { it.value.size }?.value?.min() }
            ?: return EquationDetector.Result(emptyList(), emptySet(), emptySet())
        val numbers = lines.filter { EQ_NUMBER.matches(it.text.trim()) && it.bounds.left > page.width * 0.5f }
        fun numbered(r: Rect) = numbers.any { n -> n.bounds.verticalOverlap(r) >= min(n.bounds.height, r.height) * 0.4f && n.bounds.left >= r.right - 1f }
        // (words only, "and", "where": no formula)
        fun wordsOnly(l: TextLine) = UnreadableText.tokens(l.text).all { (_, t) -> t.all { it.isLetter() || it in ",.:;" } }
        val candidates = lines.filter { l ->
            l !in numbers && !Patterns.caption.containsMatchIn(l.text) && l.text.any { it.isLetterOrDigit() || it == TextCleaner.PLACEHOLDER } &&
                ((numbered(l.bounds) && !wordsOnly(l)) || ((!prose(l) || definition(l, colLeft, bodySize)) &&
                    l.bounds.left >= colLeft + bodySize * (if (relation(l.text)) 1.2f else 2.5f)))
        }
        // a formula CVISION kept as a picture of its own, set in from the text, and one whose
        // number is all the text layer has of it
        val seeds = pictures.filter { it.left >= colLeft + bodySize * 2f } + numbers.filter { n -> candidates.none { numbered(it.bounds) && it.bounds.verticalOverlap(n.bounds) > 0f } }
            .mapNotNull { n -> Rect.bounding(specks.filter { it.verticalOverlap(n.bounds) > 0f && it.right <= n.bounds.left && it.left >= colLeft + bodySize }) }
        val blocks = mutableListOf<Block>()
        val items = candidates.map { it.bounds to it } + seeds.map { it to null }
        for ((r, l) in items.sortedBy { it.first.top }) {
            val b = blocks.lastOrNull()
            if (b != null && r.top <= b.bounds.bottom + bodySize * 0.8f &&
                r.right >= b.bounds.left - bodySize * 4f && r.left <= b.bounds.right + bodySize * 4f &&
                // (no line of text between them)
                lines.none { o -> o !in b.lines && o !== l && o !in numbers && o.bounds.top >= b.bounds.bottom - 1f && o.bounds.bottom <= r.top + 1f }
            ) {
                b.bounds = b.bounds.union(r)
                if (l != null) b.lines += l else b.picture = true
            } else {
                blocks += Block(r, if (l != null) mutableListOf(l) else mutableListOf(), l == null)
            }
        }
        val kept = blocks.filter { b -> (b.picture || numbered(b.bounds) || b.lines.any { relation(it.text) }) && b.lines.size <= 10 }
        for (b in kept) {
            // the bars, symbols and frame of the formula
            var grown = true
            while (grown) {
                grown = false
                val reach = Rect(b.bounds.left - bodySize, b.bounds.top - bodySize * 0.6f, b.bounds.right + bodySize, b.bounds.bottom + bodySize * 0.6f)
                for (s in specks) {
                    if (b.bounds.contains(s) || !reach.intersects(s) || s.height > bodySize * 4f) continue
                    // (not a piece of the line above or below)
                    if (lines.any { o -> o !in b.lines && o !in numbers && o.bounds.intersects(s) }) continue
                    b.bounds = b.bounds.union(s)
                    grown = true
                }
            }
        }
        // formulas grown into one another are one
        val merged = kept.toMutableList()
        var joined = true
        while (joined) {
            joined = false
            loop@ for (i in merged.indices) for (j in i + 1 until merged.size) {
                if (!merged[i].bounds.intersects(merged[j].bounds)) continue
                merged[i].bounds = merged[i].bounds.union(merged[j].bounds)
                merged[i].lines += merged[j].lines
                merged[i].picture = merged[i].picture || merged[j].picture
                merged.removeAt(j)
                joined = true
                break@loop
            }
        }
        val out = mutableListOf<FigureRegion>()
        val absorbed = HashSet<TextLine>()
        for (b in merged) {
            // words on the formula's rows ("under the pole faces") and anything printed inside it
            for (l in lines) {
                if (l in b.lines || l in numbers || l in absorbed) continue
                val row = l.bounds.verticalOverlap(b.bounds) >= l.bounds.height * 0.6f && l.wordCount <= 6 &&
                    max(b.bounds.left - l.bounds.right, l.bounds.left - b.bounds.right) <= bodySize * 3f && l.bounds.right <= page.width * 0.85f
                if (row || l.bounds.coveredBy(b.bounds) >= 0.7f) {
                    b.lines += l
                    b.bounds = b.bounds.union(l.bounds)
                }
            }
            absorbed += b.lines
            // a derivation with a number on several of its rows: one formula per number
            val own = numbers.filter { n ->
                n !in absorbed && n.bounds.centerY in b.bounds.top..b.bounds.bottom && n.bounds.left >= b.bounds.right - 1f &&
                    // (the formula next to it, not a word further left on its row)
                    merged.none { o -> o !== b && n.bounds.centerY in o.bounds.top..o.bounds.bottom && o.bounds.right in b.bounds.right..n.bounds.left }
            }.sortedBy { it.bounds.centerY }
            absorbed += own
            for ((piece, number) in split(b, own, specks)) {
                // a sentence ending in "is" or "=" leads in; the formula is no part of the text around
                val others = lines.filter { o -> o !in b.lines && o !in numbers && o.bounds.horizontalOverlap(piece) > 0f }
                var crop = piece.outset(2f)
                for (o in others) {
                    if (o.bounds.centerY < piece.top && o.bounds.bottom > crop.top) crop = Rect(crop.left, min(o.bounds.bottom, piece.top), crop.right, crop.bottom)
                    if (o.bounds.centerY > piece.bottom && o.bounds.top < crop.bottom) crop = Rect(crop.left, crop.top, crop.right, max(o.bounds.top, piece.bottom))
                }
                val members = b.lines.filter { it.bounds.centerY in piece.top..piece.bottom }
                val size = if (members.isEmpty()) bodySize else members.map { it.fontSize }.sorted()[members.size / 2]
                out += FigureRegion(
                    crop.clamp(page.width, page.height), FigureKind.EQUATION, number?.let { number(it.text) },
                    textSize = min(size, bodySize), text = members.joinToString(" ") { it.text.trim() }.ifEmpty { null },
                )
            }
        }
        return EquationDetector.Result(out, absorbed, emptySet())
    }

    /**
     * [b] cut across its blank rows into one formula per number of [numbers] (in order down the
     * page), each with its number; where no blank row parts two numbers, they share a formula.
     */
    private fun split(b: Block, numbers: List<TextLine>, specks: List<Rect>): List<Pair<Rect, TextLine?>> {
        if (numbers.size < 2) return listOf(b.bounds to numbers.firstOrNull())
        // the rows that have ink
        val spans = (b.lines.map { it.bounds } + specks.filter { it.intersects(b.bounds) }).map { it.top to it.bottom }.sortedBy { it.first }
        val gaps = mutableListOf<Pair<Float, Float>>()
        var reach = b.bounds.top
        for ((t, bt) in spans) {
            if (t > reach) gaps += reach to t
            reach = max(reach, bt)
        }
        val out = mutableListOf<Pair<Rect, TextLine?>>()
        var top = b.bounds.top
        var pending = mutableListOf<TextLine>()
        for ((k, n) in numbers.withIndex()) {
            pending += n
            val next = numbers.getOrNull(k + 1) ?: break
            val gap = gaps.filter { (a, z) -> a >= n.bounds.centerY && z <= next.bounds.centerY }.maxByOrNull { it.second - it.first } ?: continue
            val cut = (gap.first + gap.second) / 2f
            out += Rect(b.bounds.left, top, b.bounds.right, cut) to pending.first()
            pending = mutableListOf()
            top = cut
        }
        out += Rect(b.bounds.left, top, b.bounds.right, b.bounds.bottom) to pending.firstOrNull()
        return out
    }

    /** Lines ending in a word broken by a hyphen the text layer has no character for ("rela￼"). */
    fun lineEndHyphens(lines: List<TextLine>): List<TextLine> = lines.map { l ->
        val t = l.text
        if (t.length < 3 || (t.last() != TextCleaner.PLACEHOLDER && t.last() != '−') || !t[t.length - 2].isLowerCase() || l.wordCount < 4) return@map l
        TextLine(t.dropLast(1) + "-", l.styles, l.bounds, l.baseline, l.fontSize, l.boldRatio, l.italicRatio, l.fontName, l.chunks, l.pictures)
    }
}
