package com.raptibooks.core.ocr

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.tesseract.ResultIterator
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract

/**
 * One page read by Tesseract: its words as positioned fragments, its picture blocks as
 * pictures. Plain JVM code (no Android), so the desktop harness reads scans exactly as the
 * app does.
 */
object TesseractPage {
    /** The settings every page is read with. */
    fun configure(api: TessBaseAPI) {
        api.SetPageSegMode(tesseract.PSM_AUTO)
    }

    /** Recommended render size for OCR: ~300 dpi, capped for low-memory devices. */
    fun renderWidthFor(pageWidth: Float, pageHeight: Float, lowMemory: Boolean): Int {
        val maxLong = if (lowMemory) 2400f else 3200f
        val dpiScale = 300f / 72f
        val longSide = maxOf(pageWidth, pageHeight) * dpiScale
        val scale = if (longSide > maxLong) maxLong / maxOf(pageWidth, pageHeight) else dpiScale
        return (pageWidth * scale).toInt().coerceAtLeast(600)
    }

    /**
     * Reads [gray] ([w] x [h] pixels, one byte each), a rendering of page [pageIndex] whose
     * size in points is [pageWidth] x [pageHeight].
     */
    fun recognize(tess: TessBaseAPI, gray: ByteArray, w: Int, h: Int, pageIndex: Int, pageWidth: Float, pageHeight: Float): OcrPageResult =
        assemble(read(tess, gray, w, h, (w / pageWidth * 72f).toInt()), pageIndex, pageWidth, pageHeight)

    /** What Tesseract read on a rendering of a page ([width] x [height] pixels), before it is made into text. */
    class PageRead(val width: Int, val height: Int, val lines: List<ReadLine>, val pictures: List<IntArray>, val wordCount: Int, val meanConfidence: Float)

    /** A line as Tesseract read it (pixels), and how thick its words are printed ([stroke], pixels). */
    class ReadLine(val top: Int, val bottom: Int, val baseline: Float, val words: List<ReadWord>, val stroke: Float?) {
        val left: Int get() = words.minOfOrNull { it.l } ?: 0
        val right: Int get() = words.maxOfOrNull { it.r } ?: 0
        val text: String get() = words.joinToString(" ") { it.text }
    }

    class ReadWord(val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val conf: Float)

    /** Runs Tesseract on [gray] ([w] x [h] pixels, [dpi] dots per inch): its lines and words, its picture blocks. */
    fun read(tess: TessBaseAPI, gray: ByteArray, w: Int, h: Int, dpi: Int): PageRead {
        tess.SetImage(gray, w, h, 1, w)
        tess.SetSourceResolution(dpi.coerceIn(70, 600))
        if (tess.Recognize(null) != 0) {
            tess.Clear()
            return PageRead(w, h, emptyList(), emptyList(), 0, 0f)
        }
        val pictures = ArrayList<IntArray>()
        var confSum = 0f
        var words = 0
        val l = IntArray(1)
        val t = IntArray(1)
        val r = IntArray(1)
        val b = IntArray(1)

        // Picture blocks become figures.
        val blocks: ResultIterator? = tess.GetIterator()
        if (blocks != null) {
            try {
                do {
                    val type = blocks.BlockType()
                    val picture = type == tesseract.PT_FLOWING_IMAGE || type == tesseract.PT_HEADING_IMAGE ||
                        type == tesseract.PT_PULLOUT_IMAGE
                    if (picture && blocks.BoundingBox(tesseract.RIL_BLOCK, l, t, r, b)) pictures += intArrayOf(l[0], t[0], r[0], b[0])
                } while (blocks.Next(tesseract.RIL_BLOCK))
            } finally {
                blocks.close()
            }
        }

        // The words, line by line.
        class Building(val top: Int, val bottom: Int, val baseline: Float) {
            val words = ArrayList<ReadWord>()
        }
        val lines = ArrayList<Building>()
        val iterator: ResultIterator? = tess.GetIterator()
        if (iterator != null) {
            try {
                val x1 = IntArray(1)
                val y1 = IntArray(1)
                val x2 = IntArray(1)
                val y2 = IntArray(1)
                do {
                    if (iterator.IsAtBeginningOf(tesseract.RIL_TEXTLINE) || lines.isEmpty()) {
                        val box = iterator.BoundingBox(tesseract.RIL_TEXTLINE, l, t, r, b)
                        val top = if (box) t[0] else 0
                        val bottom = if (box) b[0] else 0
                        val base = if (iterator.Baseline(tesseract.RIL_TEXTLINE, x1, y1, x2, y2)) (y1[0] + y2[0]) / 2f else bottom - (bottom - top) * 0.2f
                        lines += Building(top, bottom, base)
                    }
                    val ptr: BytePointer = iterator.GetUTF8Text(tesseract.RIL_WORD) ?: continue
                    val text = try {
                        ptr.getString(Charsets.UTF_8.name()).trim()
                    } finally {
                        tesseract.TessDeleteText(ptr)
                    }
                    if (text.isEmpty()) continue
                    val conf = iterator.Confidence(tesseract.RIL_WORD)
                    if (!iterator.BoundingBox(tesseract.RIL_WORD, l, t, r, b)) continue
                    if (conf < 20f && text.length <= 2) continue // speckles
                    // (a word printed turned on its side, up the margin, "২০২৩": not part of the text)
                    if (text.length >= 3 && b[0] - t[0] > (r[0] - l[0]) * 1.8f) continue
                    confSum += conf
                    words++
                    lines.last().words += ReadWord(text, l[0], t[0], r[0], b[0], conf)
                } while (iterator.Next(tesseract.RIL_WORD))
            } finally {
                iterator.close()
            }
        }
        tess.Clear()
        // How thick each line is printed: a line's words agree on it (one word alone says little).
        val ink = otsu(gray)
        val read = lines.filter { it.words.isNotEmpty() }.map { line ->
            val stroke = line.words.mapNotNull { strokeWidth(gray, w, h, it.l, it.t, it.r, it.b, ink) }.sorted().let { it.getOrNull(it.size / 2) }
            ReadLine(line.top, line.bottom, line.baseline, line.words, stroke)
        }
        return PageRead(w, h, read, pictures, words, if (words == 0) 0f else confSum / words)
    }

    /** What Tesseract read ([read]) made into page [pageIndex] ([pageWidth] x [pageHeight] points). */
    fun assemble(raw: PageRead, pageIndex: Int, pageWidth: Float, pageHeight: Float): OcrPageResult {
        val read = PageRead(
            raw.width, raw.height, raw.lines.map { ReadLine(it.top, it.bottom, it.baseline, withDari(it.words), it.stroke) },
            raw.pictures, raw.wordCount, raw.meanConfidence,
        )
        val scale = read.width / pageWidth // pixels per point
        // The size of a line's type, from the height of its box: a line no taller than the page's
        // usual one is set in the text's size (a short line without tall letters, "কর।", has a
        // low box but is no smaller), and a taller one, a title, keeps its height unless that
        // is only a tall bracket or a mark beside it (it is then no taller than its words).
        val usual = read.lines.filter { it.words.size >= 2 }.map { it.bottom - it.top }.sorted().let { it.getOrNull(it.size / 2) ?: 0 }
        fun lineSize(line: ReadLine): Float {
            val height = line.bottom - line.top
            if (usual == 0 || height <= usual * 1.15f) return maxOf(usual, height).toFloat()
            val heights = line.words.map { it.b - it.t }.sorted()
            val median = heights.getOrNull(heights.size / 2) ?: 0
            return minOf(height, maxOf(median, usual)).toFloat()
        }
        // Formulas (a fraction's numerator and denominator are read as lines of their own, its
        // bar not at all) are shown as printed: their words are left out.
        val formulas = formulaZones(read.lines, usual)
        // Drawings whose letters the OCR read ("A", "a", "Cc" round a triangle) are pictures.
        val drawings = labelZones(read.lines, usual).filter { d -> formulas.none { it.contains(d.cx, d.cy) } }
        // A line set in bold (a heading): its strokes clearly thicker than the usual line's.
        val regular = read.lines.filter { it.words.size >= 3 }.mapNotNull { it.stroke }.sorted().let { if (it.size >= 4) it[it.size / 2] else null }
        val fragments = ArrayList<TextFragment>()
        for (line in read.lines) {
            val size = (lineSize(line) / scale / 1.2f).coerceAtLeast(3f)
            val top = line.top / scale
            val bottom = line.bottom / scale
            val bold = regular != null && (line.stroke ?: 0f) >= regular * BOLD_WEIGHT
            for (word in line.words) {
                val cx = (word.l + word.r) / 2
                val cy = (word.t + word.b) / 2
                if (formulas.any { it.contains(cx, cy) } || drawings.any { it.contains(cx, cy) }) continue
                fragments += TextFragment(
                    text = word.text,
                    left = word.l / scale,
                    top = minOf(top, word.t / scale),
                    right = word.r / scale,
                    bottom = maxOf(bottom, word.b / scale),
                    baseline = line.baseline / scale,
                    fontSize = size,
                    fontName = TextFragment.OCR_FONT,
                    bold = bold,
                    spaceWidth = size * 0.3f,
                )
            }
        }
        val page = RawPage(
            index = pageIndex,
            width = pageWidth,
            height = pageHeight,
            fragments = fragments,
            fromOcr = true,
            ocrPictures = read.pictures.map { Rect(it[0] / scale, it[1] / scale, it[2] / scale, it[3] / scale) } +
                drawings.map { Rect(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale) },
            ocrFormulas = formulas.map { Rect(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale) },
        )
        return OcrPageResult(page, read.wordCount, read.meanConfidence)
    }

    /**
     * The dari "।" that the OCR read as a bar after a Bangla word ("করব |", "৪ |"), or after a
     * number, an abbreviation or a word in English that ends a Bangla sentence ("মান 3.606 |
     * উদাহরণ", "৯৬০ সে.মি. | পাত").
     */
    private fun withDari(words: List<ReadWord>): List<ReadWord> {
        fun bangla(c: Char?) = c != null && c in '\u0980'..'\u09FF'
        val banglaWords = words.count { w -> w.text.any { bangla(it) } }
        return words.mapIndexed { i, w ->
            val before = when {
                w.text == "|" -> words.getOrNull(i - 1)?.text?.lastOrNull()
                w.text.endsWith('|') -> w.text[w.text.length - 2]
                else -> null
            }
            val next = words.getOrNull(i + 1)?.text?.firstOrNull()
            val endsSentence = w.text == "|" && banglaWords >= 3 && before != null &&
                (before.isLetterOrDigit() || before in "%”)" || words[i - 1].text.any { bangla(it) }) && (next == null || bangla(next))
            if (bangla(before) || endsSentence) ReadWord(w.text.dropLast(1) + "।", w.l, w.t, w.r, w.b, w.conf) else w
        }
    }

    /** A box in pixels of the rendering. */
    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

    /**
     * Where the page has drawings the OCR only read the letters of: three or more lines of
     * a few short labels ("A", "BC", "a  b"), near one another; the drawing is around them.
     */
    private fun labelZones(lines: List<ReadLine>, usual: Int): List<Box> {
        if (usual <= 0) return emptyList()
        val labels = lines.filter { line ->
            line.words.size <= 4 && line.words.all { w -> w.text.length <= 3 } && line.text.count { it.isLetterOrDigit() } >= 1 &&
                // (an option "ক)" or a list number "২." alone on its line is no label)
                !LIST_MARK.matches(line.text)
        }
        val reach = usual * 4
        fun near(a: ReadLine, b: ReadLine) = maxOf(a.left, b.left) - minOf(a.right, b.right) <= reach &&
            maxOf(a.top, b.top) - minOf(a.bottom, b.bottom) <= reach
        val out = ArrayList<Box>()
        val remaining = labels.toMutableList()
        while (remaining.isNotEmpty()) {
            val group = mutableListOf(remaining.removeAt(0))
            while (true) {
                val next = remaining.firstOrNull { c -> group.any { near(it, c) } } ?: break
                remaining.remove(next)
                group += next
            }
            if (group.size < 3) continue
            val pad = usual
            out += Box(group.minOf { it.left } - pad, group.minOf { it.top } - pad, group.maxOf { it.right } + pad, group.maxOf { it.bottom } + pad)
        }
        return out
    }

    private val LIST_MARK = Regex("^\\(?[০-৯0-9a-zA-Zক-ঘ]{1,2}[).।]$")

    private val MATH = Regex("[=+×÷/<>≤≥−]")

    /**
     * Where the page's formulas are: lines that hold only numbers and signs (the numerator or
     * denominator of a fraction, read apart from it), with the lines they are stacked against
     * (the line of the fraction, taller than text), and lines the OCR could not read. A lone
     * number far from any line (a page number) is no formula.
     */
    private fun formulaZones(lines: List<ReadLine>, usual: Int): List<Box> {
        if (usual <= 0) return emptyList()
        fun letters(line: ReadLine) = line.text.count { it.isLetter() }
        fun signs(line: ReadLine) = line.text.count { it.isDigit() || MATH.matches(it.toString()) }
        fun conf(line: ReadLine) = line.words.sumOf { (it.conf * it.text.length).toDouble() } / line.words.sumOf { it.text.length }.coerceAtLeast(1)
        fun near(a: ReadLine, b: ReadLine) = a !== b && minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > -usual * 0.5f &&
            minOf(a.right, b.right) - maxOf(a.left, b.left) > -usual * 2
        // (a line of formulas read with doubt: "2 _ 1 হলে, 2 _ 12" for "x = 1 হলে, x² = 1²")
        fun formulaLine(line: ReadLine): Boolean {
            val marks = line.text.filter { !it.isWhitespace() }
            val math = marks.count { !it.isLetter() && Character.getType(it) != Character.NON_SPACING_MARK.toInt() &&
                Character.getType(it) != Character.COMBINING_SPACING_MARK.toInt() && it !in ",.;:'\"!?।-" }
            return conf(line) < 80.0 && marks.length >= 4 && math >= marks.length * 0.25f
        }
        val single = lines.filter { formulaLine(it) }.toSet()
        val members = lines.filter { line ->
            val piece = letters(line) <= 2 && signs(line) >= 1
            val tall = line.bottom - line.top > usual * 1.5f && (MATH.containsMatchIn(line.text) || signs(line) >= 2)
            val unread = conf(line) < 45.0 && line.text.length >= 3
            ((piece || tall || unread) && lines.any { near(line, it) }) || line in single
        }
        if (members.isEmpty()) return emptyList()
        // (a piece of a fraction makes a formula of the lines stacked with it)
        val zone = members.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (line in lines) {
                if (line in zone) continue
                val stacked = zone.any { z ->
                    minOf(z.bottom, line.bottom) - maxOf(z.top, line.top) > minOf(z.bottom - z.top, line.bottom - line.top) * 0.3f &&
                        minOf(z.right, line.right) - maxOf(z.left, line.left) > -usual * 2
                }
                if (stacked && (MATH.containsMatchIn(line.text) || signs(line) >= 1 || conf(line) < 60.0)) {
                    zone += line
                    grew = true
                }
            }
        }
        // Zones: the members that touch, one formula each.
        val out = ArrayList<Box>()
        val remaining = zone.sortedBy { it.top }.toMutableList()
        while (remaining.isNotEmpty()) {
            val group = mutableListOf(remaining.removeAt(0))
            while (true) {
                val next = remaining.firstOrNull { c -> group.any { near(it, c) } } ?: break
                remaining.remove(next)
                group += next
            }
            // (a single line of numbers and signs, "২ × ৩ = ৬", reads well as text, unless read with doubt)
            if (group.size < 2 && group.none { it.bottom - it.top > usual * 1.5f || it in single }) continue
            val pad = usual / 6
            out += Box(group.minOf { it.left } - pad, group.minOf { it.top } - pad, group.maxOf { it.right } + pad, group.maxOf { it.bottom } + pad)
        }
        return out
    }

    /** How much thicker than the page's usual line a bold line's strokes are. */
    private const val BOLD_WEIGHT = 1.12f

    /** The grey level that parts ink from paper on this page (Otsu's method). */
    private fun otsu(gray: ByteArray): Int {
        val hist = LongArray(256)
        for (v in gray) hist[v.toInt() and 0xFF]++
        val total = gray.size.toDouble()
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 128
        for (i in 0 until 256) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        return threshold
    }

    /**
     * The width of a word's strokes (pixels): the usual length of the runs of ink across its
     * rows (their middle fifth to fourth fifth, averaged), the long runs of a headline (the
     * Bangla matra) left out. Null when too little ink.
     */
    private fun strokeWidth(gray: ByteArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, ink: Int): Float? {
        val runs = ArrayList<Int>()
        val longest = maxOf(2, (b - t) / 2)
        for (y in t.coerceAtLeast(0) until b.coerceAtMost(h)) {
            var run = 0
            val row = y * w
            for (x in l.coerceAtLeast(0) until r.coerceAtMost(w)) {
                if ((gray[row + x].toInt() and 0xFF) <= ink) {
                    run++
                } else if (run > 0) {
                    if (run <= longest) runs += run
                    run = 0
                }
            }
            if (run in 1..longest) runs += run
        }
        if (runs.size < 12) return null
        runs.sort()
        // (the middle of them, averaged: finer than a whole pixel)
        val middle = runs.subList(runs.size / 5, runs.size - runs.size / 5)
        return middle.average().toFloat()
    }
}
