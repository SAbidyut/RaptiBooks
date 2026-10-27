import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.SeparatorBlock
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.pdf.PdfExtractor
import com.raptibooks.core.reflow.OutlineItem
import com.raptibooks.core.reflow.ReflowEngine
import com.raptibooks.core.reflow.TocBuilder
import com.raptibooks.core.structure.analysis.DocumentStats
import com.raptibooks.core.structure.analysis.OcrDecision
import com.raptibooks.core.structure.analysis.PageAnalyzer
import com.raptibooks.core.structure.analysis.PageStructurer
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.text.GrayImage
import com.raptibooks.core.structure.text.SymbolRecognizer
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
    val ex = PdfExtractor.open(pdf, File(System.getProperty("java.io.tmpdir"), "harness-scratch"))
    val meta = ex.metadata()
    println("meta: $meta")
    // Like the app: the body size of the whole book from up to 60 pages spread over it
    // (HARNESS_BODY overrides it, e.g. for a few pages cut out of a book).
    val bodySize = System.getenv("HARNESS_BODY")?.toFloatOrNull() ?: surveyBodySize(ex)
    println("survey body size: $bodySize")
    val analyzer = PageAnalyzer(bodySize)
    val stats = DocumentStats()
    val layouts = (0 until ex.pageCount).map { i ->
        val e = ex.extractPage(i)
        val reason = OcrDecision.evaluate(e.page, e.glyphsDrawn, e.glyphsMapped)
        // HARNESS_FRAGS=page index: print that page's raw fragments.
        if (System.getenv("HARNESS_FRAGS")?.split(",")?.contains("$i") == true) {
            e.page.fragments.forEach { println("     frag '${it.text}' x=${it.left}..${it.right} base=${it.baseline} size=${it.fontSize} sw=${it.spaceWidth} font=${it.fontName} glyph=${it.glyph}") }
        }
        // Like the app: symbols without Unicode mapping are identified from their rendering.
        val page = SymbolRecognizer.recognize(e.page) { r, scale -> renderGray(pdf, i, r, scale) }
        if (System.getenv("HARNESS_SYMBOLS") != null) {
            e.page.fragments.indices.filter { e.page.fragments[it].text == "\uFFFC" }.forEach {
                val f = page.fragments[it]
                println("     symbol @${f.left},${f.baseline} size=${f.fontSize} w=${f.right - f.left} ${f.fontName} ${f.glyph} -> '${f.text}'")
            }
        }
        val layout = analyzer.analyze(page)
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
        println("page $i: ${e.page.width}x${e.page.height} frags=${e.page.fragments.size} glyphs=${e.glyphsDrawn}/${e.glyphsMapped} images=${e.page.images.size} paths=${e.page.paths.size} -> lines=${layout.lines.size} figures=${layout.figures.size} tables=${layout.tables.size} ocr=$reason")
        if (System.getenv("HARNESS_PATHS") != null) {
            e.page.paths.forEach { println("     path ${it.shape} ${it.bounds} stroked=${it.stroked} filled=${it.filled} light=${it.lightFill} seg=${it.segments}") }
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
    val engine = ReflowEngine { blocks += it }
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
    println("time: ${System.currentTimeMillis() - t0} ms, blocks=${blocks.size}")
    ex.close()
}

fun surveyBodySize(ex: PdfExtractor): Float? {
    val n = ex.pageCount
    val pages = if (n <= 60) (0 until n).toList() else List(60) { it * (n - 1) / 59 }.distinct()
    val chars = HashMap<Int, Long>()
    for (i in pages) {
        val page = runCatching { ex.extractPage(i).page }.getOrNull() ?: continue
        for (f in page.fragments) {
            if (f.isSpace) continue
            val key = Math.round(f.fontSize * 2f)
            chars[key] = (chars[key] ?: 0L) + f.text.length
        }
    }
    return chars.maxByOrNull { it.value }?.key?.let { it / 2f }
}

/** Renders a page region with poppler (the app uses Android's renderer). */
fun renderGray(pdf: File, page: Int, r: Rect, scale: Float): GrayImage? {
    val out = File.createTempFile("harness", "")
    try {
        val dpi = scale * 72f
        val cmd = listOf("pdftoppm", "-cropbox", "-gray", "-singlefile", "-r", dpi.toString(), "-f", "${page + 1}", "-l", "${page + 1}",
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
