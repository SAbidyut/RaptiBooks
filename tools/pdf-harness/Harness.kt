import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.SeparatorBlock
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.ocr.OcrPageResult
import com.raptibooks.core.ocr.TesseractPage
import com.raptibooks.core.pdf.PdfExtractor
import com.raptibooks.core.structure.layout.FigureKind
import com.raptibooks.core.structure.layout.FigureRegion
import com.raptibooks.core.structure.layout.PageLayout
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.reflow.OutlineItem
import com.raptibooks.core.reflow.ReflowEngine
import com.raptibooks.core.reflow.TocBuilder
import com.raptibooks.core.structure.analysis.DocumentStats
import com.raptibooks.core.structure.analysis.OcrDecision
import com.raptibooks.core.structure.analysis.PageAnalyzer
import com.raptibooks.core.structure.analysis.PageStructurer
import com.raptibooks.core.structure.analysis.ScanPage
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.text.GrayImage
import com.raptibooks.core.structure.text.OcrWords
import com.raptibooks.core.structure.text.SymbolRecognizer
import com.raptibooks.core.structure.text.Vocabulary
import java.io.File

/** Runs the real extraction + structure + reflow code on PDFs and prints the reflowed book. */
fun main(args: Array<String>) {
    val field = Class.forName("com.tom_roush.pdfbox.android.PDFBoxResourceLoader").getDeclaredField("ASSET_MANAGER")
    field.isAccessible = true
    field.set(null, android.content.res.AssetManager(File(args[0])))
    for (path in args.drop(1)) convert(File(path))
}

fun convert(pdf: File) {
    // HARNESS_FINDGLYPH=H11004,H11006: where glyphs without Unicode mapping of these names are used.
    System.getenv("HARNESS_FINDGLYPH")?.let { wanted ->
        val names = wanted.split(",").toSet()
        val ex = PdfExtractor.open(pdf, File(System.getProperty("java.io.tmpdir"), "harness-scratch"))
        val found = HashMap<String, Int>()
        for (i in 0 until ex.pageCount) {
            for (f in runCatching { ex.extractPage(i).page.fragments }.getOrDefault(emptyList())) {
                // "Font:c" finds a mapped character of a font
                val key = if (f.glyph in names) f.glyph else "${f.fontName.substringAfter('+')}:${f.text}".takeIf { it in names } ?: continue
                if ((found[key] ?: 0) >= 2) continue
                found[key] = (found[key] ?: 0) + 1
                println("glyph $key page=${i + 1} x=${f.left} base=${f.baseline} size=${f.fontSize} w=${f.right - f.left} ${f.fontName}")
            }
        }
        ex.close()
        return
    }
    println("=".repeat(100)); println("FILE ${pdf.name}")
    val t0 = System.currentTimeMillis()
    vocabulary = Vocabulary.Builder() // (each book its own words, as in the app)
    val ex = PdfExtractor.open(pdf, File(System.getProperty("java.io.tmpdir"), "harness-scratch"))
    val meta = ex.metadata()
    println("meta: $meta")
    // Like the app: the body size of the whole book from up to 60 pages spread over it
    // (HARNESS_BODY overrides it, e.g. for a few pages cut out of a book).
    val bodySize = System.getenv("HARNESS_BODY")?.toFloatOrNull() ?: surveyBodySize(ex)
    // (HARNESS_VOCAB=whole book: its words, for pages cut out of it)
    System.getenv("HARNESS_VOCAB")?.let { book ->
        val whole = PdfExtractor.open(File(book), File(System.getProperty("java.io.tmpdir"), "harness-scratch"))
        surveyBodySize(whole)
        whole.close()
    }
    println("survey body size: $bodySize")
    val analyzer = PageAnalyzer(bodySize, vocabulary.build())
    val stats = DocumentStats()
    // HARNESS_PAGES=a-b: only these pages (from 0)
    val pageRange = System.getenv("HARNESS_PAGES")?.split("-")?.let { it[0].toInt()..minOf(it[1].toInt(), ex.pageCount - 1) } ?: (0 until ex.pageCount)
    val layouts = pageRange.map { i ->
        val e = ex.extractPage(i)
        val reason = OcrDecision.evaluate(e.page, e.glyphsDrawn, e.glyphsMapped)
        // Like the app (OCR mode "auto"), with HARNESS_OCR: pages without usable text are read by Tesseract.
        val wantOcr = ocrApi != null && when (reason) {
            OcrDecision.Reason.NONE -> false
            OcrDecision.Reason.SCANNED, OcrDecision.Reason.OUTLINED_TEXT -> !digital
            else -> true
        }
        val ocr = if (wantOcr) ocrPage(pdf, i, e.page.width, e.page.height) else null
        val read = ocr?.takeIf { it.wordCount >= 3 }?.page?.let { OcrWords.keepSymbols(it, e.page) } ?: e.page
        val lowConfidence = ocr != null && ocr.wordCount < 30 && ocr.meanConfidence < 45f
        // HARNESS_FRAGS=page index: print that page's raw fragments.
        if (System.getenv("HARNESS_FRAGS")?.split(",")?.contains("$i") == true) {
            e.page.fragments.forEach { println("     frag '${it.text}' x=${it.left}..${it.right} base=${it.baseline} size=${it.fontSize} sw=${it.spaceWidth} font=${it.fontName} glyph=${it.glyph}") }
        }
        // Like the app: symbols without Unicode mapping are identified from their rendering.
        val page = if (read.fromOcr) read else SymbolRecognizer.recognize(read) { r, scale -> renderGray(pdf, i, r, scale) }.let {
            // and a scan under an OCR text layer gets its ink map
            if (ScanPage.isScan(it)) ScanPage.withInk(it) { r, scale -> renderGray(pdf, i, r, scale) } else it
        }
        if (System.getenv("HARNESS_SYMBOLS") != null) {
            e.page.fragments.indices.filter { e.page.fragments[it].text == "\uFFFC" }.forEach {
                val f = page.fragments[it]
                println("     symbol @${f.left},${f.baseline} size=${f.fontSize} w=${f.right - f.left} ${f.fontName} ${f.glyph} -> '${f.text}'")
            }
        }
        val ta = System.nanoTime()
        var layout = analyzer.analyze(page)
        analyzeNanos += System.nanoTime() - ta
        val hasContent = layout.lines.isNotEmpty() || layout.tables.isNotEmpty() || layout.figures.isNotEmpty()
        val visualOnly = page.images.isNotEmpty() || page.paths.size > 20 || wantOcr
        if ((!hasContent && visualOnly) || lowConfidence) {
            // (like the app: nothing readable, the page is kept as a picture)
            layout = PageLayout(i, page.width, page.height, emptyList(), listOf(FigureRegion(Rect(0f, 0f, page.width, page.height), FigureKind.SCAN)), emptyList(), page.fromOcr, 0)
        }
        layout.figures.forEachIndexed { k, f -> f.imagePath = "fig-p$i-$k.png"; f.imageWidth = f.bounds.width.toInt(); f.imageHeight = f.bounds.height.toInt() }
        // HARNESS_IMAGES=dir: save the figure and equation crops as the app would cut them.
        System.getenv("HARNESS_IMAGES")?.let { dir ->
            File(dir).mkdirs()
            layout.figures.forEachIndexed { k, f ->
                val g = renderGray(pdf, i, f.bounds, 3f) ?: return@forEachIndexed
                val img = java.awt.image.BufferedImage(g.width, g.height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
                img.raster.setPixels(0, 0, g.width, g.height, g.pixels)
                javax.imageio.ImageIO.write(img, "png", File(dir, "${pdf.nameWithoutExtension}-p$i-${f.kind}-$k.png"))
            }
        }
        stats.add(layout)
        // HARNESS_MEM=n: heap in use after a full collection, every n pages
        System.getenv("HARNESS_MEM")?.toIntOrNull()?.let { n ->
            if (i % n == 0) { System.gc(); val rt = Runtime.getRuntime(); System.err.println("mem p$i: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB") }
        }
        println("page $i: ${e.page.width}x${e.page.height} frags=${e.page.fragments.size} glyphs=${e.glyphsDrawn}/${e.glyphsMapped} images=${e.page.images.size} paths=${e.page.paths.size} -> lines=${layout.lines.size} figures=${layout.figures.size} tables=${layout.tables.size} ocr=$reason${if (ocr != null) " read=${ocr.wordCount}w conf=${ocr.meanConfidence.toInt()}" else ""}")
        if (System.getenv("HARNESS_PATHS") != null) {
            e.page.paths.forEach { println("     path ${it.shape} ${it.bounds} stroked=${it.stroked} filled=${it.filled} light=${it.lightFill} seg=${it.segments}") }
        }
        if (System.getenv("HARNESS_RAWIMAGES") != null) {
            e.page.images.forEach { println("     image ${it.bounds}") }
        }
        if (System.getenv("HARNESS_REGIONS") != null) {
            layout.figures.forEach { println("     FIGURE ${it.kind} ${it.bounds} ts=${it.textSize} caption=${it.caption}") }
            layout.pictures.forEach { println("     PICTURE ${it.id} ${it.bounds}") }
            layout.tables.forEach { println("     TABLE ruled=${it.ruled} ${it.bounds}") }
        }
        if (System.getenv("HARNESS_LINES") != null) layout.lines.forEach { println("     ${it.bounds.left.toInt()},${it.bounds.top.toInt()} s=${it.fontSize} b=${it.boldRatio} | ${it.text}") }
        layout
    }
    val profile = stats.profile()
    println("profile: body=${profile.bodySize} headings=${profile.headingSizes} visualOrder=${profile.visualOrder} lang=${profile.language}")
    val structurer = PageStructurer(profile)
    val blocks = mutableListOf<ContentBlock>()
    val engine = ReflowEngine(keepsHyphen = profile::keepsHyphen) { blocks += it }
    layouts.forEach { engine.addPage(structurer.structure(it)) }
    val headings = engine.finish()
    val toc = TocBuilder.build(ex.outline().map { OutlineItem(it.title, it.pageIndex, it.level) }, headings, engine.pageStarts(), engine.blockCount, engine.printedContents())
    println("-".repeat(100))
    val boxes = engine.boxes()
    for (b in blocks) {
        val box = boxes.firstOrNull { b.index in it.startIndex..it.endIndex }
        val mark = when {
            box == null -> ""
            b.index == box.startIndex -> "┌%06X ".format(box.accent)
            else -> "│ "
        }
        println(mark + render(b))
    }
    println("-".repeat(100))
    println("TOC: " + toc.joinToString(" | ") { "${"  ".repeat(it.level - 1)}${it.title} @${it.blockIndex}" })
    println("time: ${System.currentTimeMillis() - t0} ms, analysis ${analyzeNanos / 1000000} ms, renders $renders, blocks=${blocks.size}")
    ex.close()
}

/** The book's words, from the pages of the survey (filled by [surveyBodySize]). */
var vocabulary = Vocabulary.Builder()

fun surveyBodySize(ex: PdfExtractor): Float? {
    val n = ex.pageCount
    val pages = if (n <= 60) (0 until n).toList() else List(60) { it * (n - 1) / 59 }.distinct()
    val wordPages = if (n <= 120) (0 until n).toSet() else List(120) { it * (n - 1) / 119 }.toSet()
    for (i in wordPages) runCatching { ex.extractPage(i).page }.getOrNull()?.let { vocabulary.add(it.fragments) }
    val chars = HashMap<Int, Long>()
    var sampled = 0
    var textPages = 0
    for (i in pages) {
        val page = runCatching { ex.extractPage(i).page }.getOrNull() ?: continue
        var pageChars = 0
        for (f in page.fragments) {
            if (f.isSpace) continue
            val key = Math.round(f.fontSize * 2f)
            chars[key] = (chars[key] ?: 0L) + f.text.length
            pageChars += f.text.length
        }
        sampled++
        if (pageChars >= 200) textPages++
    }
    // (like the app: most pages carry real text, a born-digital book)
    digital = sampled >= 3 && textPages * 10 >= sampled * 7
    return chars.maxByOrNull { it.value }?.key?.let { it / 2f }
}

/** Whether the book is born digital (see [surveyBodySize]). */
var digital = true

/** Tesseract as the app sets it up, when HARNESS_OCR is set. */
val ocrApi: org.bytedeco.tesseract.TessBaseAPI? by lazy {
    if (System.getenv("HARNESS_OCR") == null) return@lazy null
    org.bytedeco.tesseract.TessBaseAPI().also {
        check(it.Init(System.getenv("HARNESS_TESSDATA"), "ben+eng") == 0) { "tessdata" }
        TesseractPage.configure(it)
    }
}

/**
 * Page [i] read by Tesseract from a rendering as large as the app's; kept in
 * HARNESS_OCR_CACHE (default build/harness/ocr-cache) to be read again without OCR.
 */
fun ocrPage(pdf: File, i: Int, w: Float, h: Float): OcrPageResult? {
    val api = ocrApi ?: return null
    val cache = File(System.getenv("HARNESS_OCR_CACHE") ?: "build/harness/ocr-cache", "${pdf.nameWithoutExtension}/p$i.read")
    if (cache.exists()) {
        // (what Tesseract read is kept; what is made of it is made again, by the current code)
        val rows = cache.readLines().map { it.split("\t") }
        val head = rows[0]
        val lines = ArrayList<TesseractPage.ReadLine>()
        val pictures = ArrayList<IntArray>()
        var words = ArrayList<TesseractPage.ReadWord>()
        var line: List<String>? = null
        fun flush() {
            val l = line ?: return
            lines += TesseractPage.ReadLine(l[1].toInt(), l[2].toInt(), l[3].toFloat(), words, l[4].toFloatOrNull())
            words = ArrayList()
        }
        for (c in rows.drop(1)) {
            when (c[0]) {
                "P" -> pictures += intArrayOf(c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt())
                "L" -> { flush(); line = c }
                "W" -> words += TesseractPage.ReadWord(c[1], c[2].toInt(), c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6].toFloat())
            }
        }
        flush()
        val read = TesseractPage.PageRead(head[0].toInt(), head[1].toInt(), lines, pictures, head[2].toInt(), head[3].toFloat())
        return TesseractPage.assemble(read, i, w, h)
    }
    val width = TesseractPage.renderWidthFor(w, h, false)
    val out = File.createTempFile("harness-ocr", "")
    try {
        val cmd = listOf("pdftoppm", "-cropbox", "-gray", "-singlefile", "-f", "${i + 1}", "-l", "${i + 1}",
            "-scale-to-x", "$width", "-scale-to-y", "-1", pdf.path, out.path)
        if (ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() != 0) return null
        val bytes = File(out.path + ".pgm").readBytes()
        var pos = 0
        val fields = mutableListOf<Int>()
        while (fields.size < 4) {
            while (bytes[pos].toInt().toChar().isWhitespace()) pos++
            val start = pos
            while (!bytes[pos].toInt().toChar().isWhitespace()) pos++
            val token = String(bytes, start, pos - start)
            fields += if (token == "P5") 0 else token.toInt()
        }
        pos++
        val pw = fields[1]
        val ph = fields[2]
        val read = TesseractPage.read(api, bytes.copyOfRange(pos, pos + pw * ph), pw, ph, (pw / w * 72f).toInt())
        cache.parentFile.mkdirs()
        cache.writeText(buildString {
            append("${read.width}\t${read.height}\t${read.wordCount}\t${read.meanConfidence}\n")
            for (r in read.pictures) append("P\t${r[0]}\t${r[1]}\t${r[2]}\t${r[3]}\n")
            for (l in read.lines) {
                append("L\t${l.top}\t${l.bottom}\t${l.baseline}\t${l.stroke ?: "-"}\n")
                for (wd in l.words) append("W\t${wd.text}\t${wd.l}\t${wd.t}\t${wd.r}\t${wd.b}\t${wd.conf}\n")
            }
        })
        return TesseractPage.assemble(read, i, w, h)
    } finally {
        out.delete()
        File(out.path + ".pgm").delete()
    }
}

/** Time spent in page analysis, and region renderings. */
var analyzeNanos = 0L
var renders = 0

/** Renders a page region with poppler (the app uses Android's renderer). */
fun renderGray(pdf: File, page: Int, r: Rect, scale: Float): GrayImage? {
    renders++
    val out = File.createTempFile("harness", "")
    try {
        val dpi = scale * 72f
        // HARNESS_PDFIUM=script: render with pdfium like Android does (script args: pdf page left top width height scale out.pgm)
        val pdfium = System.getenv("HARNESS_PDFIUM")
        if (pdfium != null) File(out.path + ".pgm").let { pgm ->
            out.delete()
            val ok = ProcessBuilder(listOf("python3", pdfium, pdf.path, "$page", "${r.left}", "${r.top}", "${r.width}", "${r.height}", "$scale", pgm.path))
                .redirectErrorStream(true).start().waitFor() == 0
            if (!ok) return null
        }
        val cmd = if (pdfium != null) listOf("true") else listOf("pdftoppm","-cropbox", "-gray", "-singlefile", "-r", dpi.toString(), "-f", "${page + 1}", "-l", "${page + 1}",
            "-x", "${(r.left * scale).toInt()}", "-y", "${(r.top * scale).toInt()}",
            "-W", "${(r.width * scale).toInt()}", "-H", "${(r.height * scale).toInt()}", pdf.path, out.path)
        if (ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() != 0) return null
        val bytes = File(out.path + ".pgm").readBytes()
        // P5 header: magic, width, height, maxval, each followed by whitespace.
        var pos = 0
        val fields = mutableListOf<Int>()
        while (fields.size < 4) {
            while (bytes[pos].toInt().toChar().isWhitespace()) pos++
            val start = pos
            while (!bytes[pos].toInt().toChar().isWhitespace()) pos++
            val token = String(bytes, start, pos - start)
            fields += if (token == "P5") 0 else token.toInt()
        }
        pos++
        val w = fields[1]
        val h = fields[2]
        return GrayImage(IntArray(w * h) { bytes[pos + it].toInt() and 0xFF }, w, h)
    } finally {
        out.delete()
        File(out.path + ".pgm").delete()
    }
}

fun render(b: ContentBlock): String = when (b) {
    is HeadingBlock -> "#".repeat(b.level) + " " + b.text + "   [p${b.pageIndex}]"
    is ParagraphBlock -> (if (b.kind.name != "BODY") "(${b.kind}) " else "") + b.text.replace("\n", " ⏎ ") +
        (if (b.styles.isNotEmpty()) "   {styles: " + b.styles.joinToString { "'" + b.text.substring(b.start(it), b.end(it)) + "'=" + it.flags } + "}" else "") + "   [p${b.pageIndex}]"
    is ListItemBlock -> "  ${b.marker} ${b.text}"
    is ImageBlock -> "[IMAGE ${b.kind} ${b.width}x${b.height} caption=${b.caption}" + (b.altText?.let { " alt=$it" } ?: "") + "]"
    is TableBlock -> "[TABLE header=${b.headerRows} caption=${b.caption}]\n" + b.rows.joinToString("\n") { "  | " + it.joinToString(" | ") + " |" }
    is SeparatorBlock -> "----"
}

fun ParagraphBlock.start(s: com.raptibooks.core.model.InlineStyle) = s.start.coerceIn(0, text.length)
fun ParagraphBlock.end(s: com.raptibooks.core.model.InlineStyle) = s.end.coerceIn(0, text.length)
