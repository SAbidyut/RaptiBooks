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
import java.io.File

/** Runs the real extraction + structure + reflow code on PDFs and prints the reflowed book. */
fun main(args: Array<String>) {
    val field = Class.forName("com.tom_roush.pdfbox.android.PDFBoxResourceLoader").getDeclaredField("ASSET_MANAGER")
    field.isAccessible = true
    field.set(null, android.content.res.AssetManager(File(args[0])))
    for (path in args.drop(1)) convert(File(path))
}

fun convert(pdf: File) {
    println("=".repeat(100)); println("FILE ${pdf.name}")
    val t0 = System.currentTimeMillis()
    val ex = PdfExtractor.open(pdf, File(System.getProperty("java.io.tmpdir"), "harness-scratch"))
    val meta = ex.metadata()
    println("meta: $meta")
    val analyzer = PageAnalyzer()
    val stats = DocumentStats()
    val layouts = (0 until ex.pageCount).map { i ->
        val e = ex.extractPage(i)
        val reason = OcrDecision.evaluate(e.page, e.glyphsDrawn, e.glyphsMapped)
        val layout = analyzer.analyze(e.page)
        layout.figures.forEachIndexed { k, f -> f.imagePath = "fig-p$i-$k.png"; f.imageWidth = f.bounds.width.toInt(); f.imageHeight = f.bounds.height.toInt() }
        stats.add(layout)
        println("page $i: ${e.page.width}x${e.page.height} frags=${e.page.fragments.size} glyphs=${e.glyphsDrawn}/${e.glyphsMapped} images=${e.page.images.size} paths=${e.page.paths.size} -> lines=${layout.lines.size} figures=${layout.figures.size} tables=${layout.tables.size} ocr=$reason")
        if (System.getenv("HARNESS_PATHS") != null) {
            e.page.paths.forEach { println("     path ${it.shape} ${it.bounds} stroked=${it.stroked} filled=${it.filled} light=${it.lightFill} seg=${it.segments}") }
        }
        if (System.getenv("HARNESS_REGIONS") != null) {
            layout.figures.forEach { println("     FIGURE ${it.kind} ${it.bounds}") }
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
    val toc = TocBuilder.build(ex.outline().map { OutlineItem(it.title, it.pageIndex, it.level) }, headings, engine.pageStarts(), engine.blockCount)
    println("-".repeat(100))
    for (b in blocks) println(render(b))
    println("-".repeat(100))
    println("TOC: " + toc.joinToString(" | ") { "${"  ".repeat(it.level - 1)}${it.title} @${it.blockIndex}" })
    println("time: ${System.currentTimeMillis() - t0} ms, blocks=${blocks.size}")
    ex.close()
}

fun render(b: ContentBlock): String = when (b) {
    is HeadingBlock -> "#".repeat(b.level) + " " + b.text + "   [p${b.pageIndex}]"
    is ParagraphBlock -> (if (b.kind.name != "BODY") "(${b.kind}) " else "") + b.text.replace("\n", " ⏎ ") +
        (if (b.styles.isNotEmpty()) "   {styles: " + b.styles.joinToString { "'" + b.text.substring(b.start(it), b.end(it)) + "'=" + it.flags } + "}" else "") + "   [p${b.pageIndex}]"
    is ListItemBlock -> "  ${b.marker} ${b.text}"
    is ImageBlock -> "[IMAGE ${b.kind} ${b.width}x${b.height} caption=${b.caption}]"
    is TableBlock -> "[TABLE header=${b.headerRows} caption=${b.caption}]\n" + b.rows.joinToString("\n") { "  | " + it.joinToString(" | ") + " |" }
    is SeparatorBlock -> "----"
}

fun ParagraphBlock.start(s: com.raptibooks.core.model.InlineStyle) = s.start.coerceIn(0, text.length)
fun ParagraphBlock.end(s: com.raptibooks.core.model.InlineStyle) = s.end.coerceIn(0, text.length)
