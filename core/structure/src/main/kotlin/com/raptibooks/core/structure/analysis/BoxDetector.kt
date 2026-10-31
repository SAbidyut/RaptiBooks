package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.layout.BoxRegion
import com.raptibooks.core.structure.layout.TableRegion
import com.raptibooks.core.structure.layout.TextLine
import com.raptibooks.core.structure.raw.VectorPath
import com.raptibooks.core.structure.raw.PathShape
import com.raptibooks.core.structure.raw.RawPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds callout boxes: a tinted panel around running text — worked examples, definitions,
 * notes, warnings. The reader shows their content as a card in the box's colours, so the
 * structure of the page (what belongs to the example, where the warning ends) survives.
 */
class BoxDetector {

    fun detect(page: RawPage, lines: List<TextLine>, tables: List<TableRegion>): List<BoxRegion> {
        if (lines.isEmpty()) return emptyList()
        val tinted = tintedBoxes(page, lines, tables)
        val ruled = ruledExamples(page, lines, tinted)
        return tinted + ruled + closingRule(page, lines, tables, tinted + ruled)
    }

    /** Rules across the column: lines, and bars drawn as one thick stroke or a thin fill. */
    private fun rules(page: RawPage): List<VectorPath> = page.paths.filter {
        it.bounds.width >= page.width * 0.35f && (it.shape == PathShape.HORIZONTAL_LINE ||
            (it.shape == PathShape.RECTANGLE && it.bounds.height <= 12f && (it.segments == 1 || (it.filled && !it.lightFill))))
    }.sortedBy { it.bounds.top }

    /**
     * The rule that closes a worked example begun on an earlier page: a coloured rule across the
     * column with the text of the example above it, from the top of the page, and no new example
     * under it. Its box only closes a box already open (see [BoxRegion.closing]).
     */
    private fun closingRule(page: RawPage, lines: List<TextLine>, tables: List<TableRegion>, boxes: List<BoxRegion>): List<BoxRegion> {
        val margin = page.height * 0.07f
        val body = lines.filter { it.bounds.top > margin && it.bounds.bottom < page.height - margin }
        val rule = rules(page).firstOrNull { r ->
            saturation(r.color) >= 60 && tables.none { it.bounds.outset(2f).intersects(r.bounds) } &&
                boxes.none { it.bounds.outset(2f).intersects(r.bounds) }
        } ?: return emptyList()
        val r = rule.bounds
        val above = body.filter { it.bounds.bottom <= r.top + 1f && it.bounds.horizontalOverlap(r) >= it.bounds.width * 0.5f }
        if (above.isEmpty() || above.none { it.wordCount >= 4 }) return emptyList()
        // (the bar over the next example's label opens that example)
        if (body.any { it.bounds.centerY > r.bottom && it.bounds.top - r.bottom <= it.fontSize * 2.5f && EXAMPLE_START.containsMatchIn(it.text) }) return emptyList()
        // (nothing but that text above it: no heading, no example label)
        if (above.any { EXAMPLE_START.containsMatchIn(it.text) || (it.isBold && it.fontSize >= profileless(above) * 1.1f && it.wordCount <= 8) }) return emptyList()
        val top = above.minOf { it.bounds.top } - 2f
        val accent = rule.color
        return listOf(BoxRegion(Rect(r.left, top, r.right, r.bottom), lighten(accent), accent, openTop = true, closing = true))
    }

    /** The size of most of [lines]. */
    private fun profileless(lines: List<TextLine>): Float = lines.map { it.fontSize }.sorted()[lines.size / 2]

    /**
     * Worked examples set between two rules across the column, the "EXAMPLE 1.4" label right
     * under the first: the rules are the example's box.
     */
    private fun ruledExamples(page: RawPage, lines: List<TextLine>, boxes: List<BoxRegion>): List<BoxRegion> {
        val rules = rules(page)
        if (rules.isEmpty()) return emptyList()
        val margin = page.height * 0.07f
        val body = lines.filter { it.bounds.top > margin && it.bounds.bottom < page.height - margin }
        val out = mutableListOf<BoxRegion>()
        for ((k, rule) in rules.withIndex()) {
            val top = rule.bounds
            // (a thick bar's bounds take in its stroke: the label's ascenders may reach into them)
            fun under(l: TextLine) = l.bounds.top >= top.bottom - max(1f, top.height * 0.5f) && l.bounds.horizontalOverlap(top) >= l.bounds.width * 0.5f
            // (the label may have a formula beside it, whose numerator is printed higher)
            val first = body.filter { under(it) && it.bounds.top - top.bottom <= it.fontSize * 2.5f }
                .firstOrNull { EXAMPLE_START.containsMatchIn(it.text) } ?: continue
            if (body.any { l -> l.bounds.top < first.bounds.top - first.fontSize && under(l) && l.text.count { it.isLetter() } >= 4 }) continue
            // (a label in a margin column, beside the text of the example, is not under the rule's text)
            if (body.any { it !== first && it.bounds.verticalOverlap(first.bounds) > 0f && it.text.count { c -> c.isLetter() } >= 4 }) continue
            // Without a closing rule the example ends before the next section's heading (or the next
            // example); one that runs on to the next page is left to the reader's example card,
            // which follows it there.
            // (the caption "Example 1.3." of the example's own figure is no next example)
            val label = EXAMPLE_START.find(first.text)!!.value.filter { it.isDigit() || it == '.' || it == '-' }
            fun nextExample(l: TextLine) = EXAMPLE_START.find(l.text)?.value?.filter { it.isDigit() || it == '.' || it == '-' }?.let { it != label } == true
            val stop = body.filter { l ->
                under(l) && l.bounds.top > first.bounds.bottom &&
                    // (a heading starts the column's lines: not the "." on a calculator key)
                    // ("Solution:" over the steps is part of the example)
                    ((l.isBold && l.fontSize >= first.fontSize * 1.1f && l.text.count { it.isLetter() } >= 3 && !l.text.trimEnd().endsWith(':') &&
                        abs(l.bounds.left - top.left) <= l.fontSize * 1.5f) || nextExample(l))
            }.minByOrNull { it.bounds.top }
            val close = rules.drop(k + 1).firstOrNull {
                abs(it.bounds.left - top.left) <= 4f && abs(it.bounds.right - top.right) <= 4f && it.bounds.top < (stop?.bounds?.top ?: Float.MAX_VALUE)
            }
            val bottom = close?.bounds?.bottom ?: stop?.let { it.bounds.top - 2f } ?: continue
            val rect = Rect(top.left, top.top, top.right, bottom)
            // (one rule may close an example and open the next)
            if (boxes.any { it.bounds.intersects(rect) } || out.any { it.bounds.intersects(rect, -2f) }) continue
            val accent = rule.color.takeIf { it >= 0 && saturation(it) >= 60 } ?: EXAMPLE_ACCENT
            out += BoxRegion(rect, if (accent == EXAMPLE_ACCENT) EXAMPLE_FILL else lighten(accent), accent, openBottom = false)
        }
        return out
    }

    /** [rgb] mixed with much white: a light tint of the same hue. */
    private fun lighten(rgb: Int): Int {
        fun ch(c: Int) = (c + (255 - c) * 0.88f).toInt().coerceIn(0, 255)
        return (ch(rgb shr 16 and 0xFF) shl 16) or (ch(rgb shr 8 and 0xFF) shl 8) or ch(rgb and 0xFF)
    }

    private fun tintedBoxes(page: RawPage, lines: List<TextLine>, tables: List<TableRegion>): List<BoxRegion> {
        val pageArea = page.width * page.height
        val candidates = page.paths.filter { p ->
            val b = p.bounds
            p.filled && p.color >= 0 && p.shape == PathShape.RECTANGLE &&
                b.width >= page.width * 0.35f && b.height >= 12f && b.area < pageArea * 0.7f &&
                // (a panel bleeding off the page, the backdrop of a chapter opener, is no callout)
                !(!p.stroked && b.area > pageArea * 0.2f && (b.left <= 2f || b.right >= page.width - 2f)) &&
                isTint(p.color) && tables.none { it.bounds.outset(2f).contains(b) }
        }.map { it.bounds to it.color }
            .distinctBy { (b, _) -> listOf(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()) }
        if (candidates.isEmpty()) return emptyList()
        // Outermost boxes only (a coloured title strip inside a box belongs to the box).
        val outer = candidates.filter { (b, _) -> candidates.none { (o, _) -> o !== b && o.contains(b, 1f) && o.area > b.area * 1.05f } }

        val margin = page.height * 0.07f
        val body = lines.filter { it.bounds.top > margin && it.bounds.bottom < page.height - margin }
        val out = mutableListOf<BoxRegion>()
        for ((b, fill) in outer) {
            val inside = body.filter { b.contains(it.bounds.centerX, it.bounds.centerY) }
            if (inside.isEmpty()) continue
            // A table's own background, or a banner behind one heading line, is not a callout.
            if (inside.size == 1 && b.height < inside[0].fontSize * 3.5f && inside[0].wordCount <= 6) continue
            val accent = accentOf(page, b, fill)
            val openTop = b.top < page.height * 0.25f && body.none { it.bounds.bottom <= b.top + 1f }
            val openBottom = b.bottom > page.height * 0.75f && body.none { it.bounds.top >= b.bottom - 1f }
            out += BoxRegion(b, fill, accent, openTop, openBottom)
        }
        return out
    }

    private companion object {
        val EXAMPLE_START = Regex("^\\s*(?:worked\\s+)?(?:example|উদাহরণ)\\s*[0-9০-৯]+(?:[.\\-][0-9০-৯]+)*(?![0-9০-৯])", RegexOption.IGNORE_CASE)
        const val EXAMPLE_FILL = 0xFFF1E0
        const val EXAMPLE_ACCENT = 0xE07800
    }

    /** A light, visibly coloured fill (not white paper, not a dark banner). */
    private fun isTint(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        return lum in 200..252 || (lum > 252 && max(r, max(g, b)) - min(r, min(g, b)) >= 6)
    }

    private fun saturation(rgb: Int): Int {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun luminance(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    /**
     * The box's accent: a coloured bar along its left edge, else its coloured border, else a
     * deeper shade of the fill.
     */
    private fun accentOf(page: RawPage, box: Rect, fill: Int): Int {
        val edges = page.paths.filter { p ->
            p.color >= 0 && p.color != fill && box.outset(3f).contains(p.bounds) &&
                (p.shape == PathShape.VERTICAL_LINE || p.shape == PathShape.HORIZONTAL_LINE || (p.filled && min(p.bounds.width, p.bounds.height) <= 6f))
        }
        val bar = edges.filter { p ->
            abs(p.bounds.left - box.left) <= 4f && p.bounds.height >= box.height * 0.6f && p.bounds.width <= 8f
        }.maxByOrNull { saturation(it.color) + (255 - luminance(it.color)) / 2 }
        if (bar != null && (saturation(bar.color) >= 40 || luminance(bar.color) < 150)) return bar.color
        val border = edges.maxByOrNull { saturation(it.color) }
        if (border != null && saturation(border.color) >= 60) return border.color
        return deepen(fill)
    }

    /** The fill's hue at a strong, readable depth. */
    private fun deepen(rgb: Int): Int {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val lo = min(r, min(g, b))
        val hi = max(r, max(g, b))
        if (hi - lo < 4) return 0x5F6B7A
        fun ch(c: Int) = ((c - lo).toFloat() / (hi - lo) * 150f + 40f).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
