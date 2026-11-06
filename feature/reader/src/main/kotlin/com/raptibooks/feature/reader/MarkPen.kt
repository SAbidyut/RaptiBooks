package com.raptibooks.feature.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.raptibooks.core.model.Highlight
import com.raptibooks.core.model.InkStroke
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.MarkKey
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.ui.ReaderColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the marking pen is doing: nothing (the page scrolls), drawing in a colour, erasing, or
 * waiting for a tap where a note should be left.
 */
internal enum class MarkTool(val color: MarkColor?) {
    NONE(null), RED(MarkColor.RED), BLUE(MarkColor.BLUE), YELLOW(MarkColor.YELLOW), GREEN(MarkColor.GREEN), ERASER(null), NOTE(null),
}

/** The laid-out text of a block that is on screen, so a stroke can be turned into characters. */
internal class TextTarget {
    var coords: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

/** A picture on screen that can be drawn on. */
internal class ImageTarget {
    var coords: LayoutCoordinates? = null
}

internal class MarkRegistry {
    /** Texts on screen by [MarkKey]. */
    val targets = HashMap<Long, TextTarget>()
    val images = HashMap<Int, ImageTarget>()
}

@Immutable
internal class ReaderMarks(
    val byKey: Map<Long, List<Highlight>>,
    val ink: Map<Int, List<InkStroke>>,
    val registry: MarkRegistry,
)

internal val LocalMarks = compositionLocalOf { ReaderMarks(emptyMap(), emptyMap(), MarkRegistry()) }

/** Body text of a block, with the passages marked by the user underlined in their colour. */
@Composable
internal fun MarkableText(
    blockIndex: Int,
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    part: Int = 0,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    softWrap: Boolean = true,
) {
    val marks = LocalMarks.current
    val key = MarkKey.of(blockIndex, part)
    val spans = marks.byKey[key]
    val target = remember(key) { TextTarget() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    DisposableEffect(key, marks.registry) {
        marks.registry.targets[key] = target
        onDispose { if (marks.registry.targets[key] === target) marks.registry.targets.remove(key) }
    }
    val thickness = with(LocalDensity.current) { 2.5.dp.toPx() }
    val drop = with(LocalDensity.current) { 3.dp.toPx() }
    val selection = LocalSelection.current
    val selected = style.color.takeOrElse { Color(0xFF3D7BD9) }.copy(alpha = 0.28f)
    val flash = LocalFlash.current
    val lit = if (flash.block == blockIndex) remember(text, flash.line) { Passage.rangeIn(text.text, flash.line)?.second } else null
    Text(
        text, style = style,
        inlineContent = inlineContent,
        softWrap = softWrap,
        onTextLayout = { layout = it; target.layout = it },
        modifier = modifier
            .onGloballyPositioned { target.coords = it }
            .drawBehind {
                val l = layout ?: return@drawBehind
                if (lit != null && flash.glow.value > 0f) {
                    drawPath(l.getPathForRange(lit.first, lit.last + 1), PASSAGE_GLOW.copy(alpha = flash.glow.value))
                }
                // (read while drawing: a selection being dragged redraws, no recomposition)
                selection.rangeIn(key, text.length)?.let { r ->
                    drawPath(l.getPathForRange(r.first, r.last + 1), selected)
                }
                if (!spans.isNullOrEmpty()) for (m in spans) drawUnderline(l, text.text, m.start, m.end, Color(m.color.argb), thickness, drop)
            },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnderline(
    l: TextLayoutResult, text: String, from: Int, to: Int, color: Color, thickness: Float, drop: Float,
) {
    val s = from.coerceIn(0, text.length)
    val e = to.coerceIn(s, text.length)
    if (e <= s) return
    for (line in l.getLineForOffset(s)..l.getLineForOffset(e - 1)) {
        var a = max(s, l.getLineStart(line))
        var b = min(e, l.getLineEnd(line, visibleEnd = true))
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (b <= a) continue
        val first = l.getBoundingBox(a)
        val last = l.getBoundingBox(b - 1)
        val x1 = min(first.left, last.left)
        val x2 = max(first.right, last.right)
        val y = min(l.getLineBaseline(line) + drop, l.getLineBottom(line) - thickness / 2)
        drawLine(color, Offset(x1, y), Offset(x2, y), strokeWidth = thickness, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------- from a stroke to characters

private class Placed(val index: Long, val origin: Offset, val layout: TextLayoutResult) {
    val text: String get() = layout.layoutInput.text.text
    val rect: Rect get() = Rect(origin, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
}

/**
 * The characters a pen stroke (in root coordinates) points at, per block. A loop drawn around
 * text takes everything inside it; any other stroke takes the words it runs along or through.
 */
internal fun strokeToRanges(points: List<Offset>, registry: MarkRegistry, density: Float): Map<Long, List<IntRange>> {
    if (points.isEmpty()) return emptyMap()
    val targets = registry.targets.mapNotNull { (index, t) ->
        val c = t.coords
        val l = t.layout
        if (c == null || l == null || !c.isAttached) null else Placed(index, c.positionInRoot(), l)
    }
    if (targets.isEmpty()) return emptyMap()
    if (isLoop(points, density)) {
        val inside = enclosed(points, targets)
        if (inside.isNotEmpty()) return inside
    }
    return traced(points, targets, 14f * density)
}

private fun isLoop(points: List<Offset>, density: Float): Boolean {
    if (points.size < 8) return false
    var length = 0f
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (i in points.indices) {
        val p = points[i]
        if (i > 0) length += hypot(p.x - points[i - 1].x, p.y - points[i - 1].y)
        minX = min(minX, p.x); maxX = max(maxX, p.x)
        minY = min(minY, p.y); maxY = max(maxY, p.y)
    }
    val gap = hypot(points.last().x - points.first().x, points.last().y - points.first().y)
    return length >= 100f * density && maxX - minX >= 24f * density && maxY - minY >= 16f * density && gap <= 0.22f * length
}

private fun inPolygon(p: Offset, poly: List<Offset>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

private fun enclosed(poly: List<Offset>, targets: List<Placed>): Map<Long, List<IntRange>> {
    val bounds = Rect(poly.minOf { it.x }, poly.minOf { it.y }, poly.maxOf { it.x }, poly.maxOf { it.y })
    val out = HashMap<Long, MutableList<IntRange>>()
    for (t in targets) {
        if (!t.rect.overlaps(bounds)) continue
        val l = t.layout
        val text = t.text
        for (line in 0 until l.lineCount) {
            if (t.origin.y + l.getLineBottom(line) < bounds.top || t.origin.y + l.getLineTop(line) > bounds.bottom) continue
            var first = -1
            var last = -1
            for (i in l.getLineStart(line) until min(l.getLineEnd(line, visibleEnd = true), text.length)) {
                if (text[i].isWhitespace()) continue
                val b = l.getBoundingBox(i)
                val center = Offset(t.origin.x + (b.left + b.right) / 2, t.origin.y + (b.top + b.bottom) / 2)
                if (inPolygon(center, poly)) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first >= 0) out.getOrPut(t.index) { ArrayList() } += first..last
        }
    }
    return out
}

private class Hit(val target: Placed, val line: Int) {
    var min = Int.MAX_VALUE
    var max = Int.MIN_VALUE
}

private fun traced(points: List<Offset>, targets: List<Placed>, tolerance: Float): Map<Long, List<IntRange>> {
    val hits = LinkedHashMap<Pair<Long, Int>, Hit>()
    for (p in points) {
        var best: Hit? = null
        var bestDistance = tolerance + 1f
        for (t in targets) {
            val l = t.layout
            val local = p - t.origin
            if (local.x < -tolerance || local.x > l.size.width + tolerance) continue
            if (local.y < -tolerance || local.y > l.size.height + tolerance) continue
            val dx = when {
                local.x < 0f -> -local.x
                local.x > l.size.width -> local.x - l.size.width
                else -> 0f
            }
            val line = l.getLineForVerticalPosition(local.y.coerceIn(0f, max(0f, l.size.height - 1f)))
            val top = l.getLineTop(line)
            val bottom = l.getLineBottom(line)
            val dy = when {
                local.y < top -> top - local.y
                local.y > bottom -> local.y - bottom
                else -> 0f
            }
            val distance = hypot(dx, dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = hits.getOrPut(t.index to line) { Hit(t, line) }
            }
        }
        val hit = best ?: continue
        val l = hit.target.layout
        val local = p - hit.target.origin
        val y = (l.getLineTop(hit.line) + l.getLineBottom(hit.line)) / 2
        val offset = l.getOffsetForPosition(Offset(local.x.coerceIn(0f, l.size.width.toFloat()), y))
        hit.min = min(hit.min, offset)
        hit.max = max(hit.max, offset)
    }
    val out = HashMap<Long, MutableList<IntRange>>()
    for (hit in hits.values) {
        if (hit.min > hit.max) continue
        val l = hit.target.layout
        val text = hit.target.text
        val lineStart = l.getLineStart(hit.line)
        val lineEnd = min(l.getLineEnd(hit.line, visibleEnd = true), text.length)
        if (lineEnd <= lineStart) continue
        var s = hit.min.coerceIn(lineStart, lineEnd - 1)
        var e = max(hit.max, s + 1).coerceIn(s + 1, lineEnd)
        // Whole words, so a short stroke or a tap marks the word under the pen.
        while (s > lineStart && !text[s - 1].isWhitespace()) s--
        while (e < lineEnd && !text[e].isWhitespace()) e++
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e > s) out.getOrPut(hit.target.index) { ArrayList() } += s until e
    }
    return out
}

// ---------------------------------------------------------------------- pen UI

/** Covers the page while a pen is selected: one finger draws instead of scrolling. */
@Composable
internal fun MarkOverlay(tool: MarkTool, onStroke: (points: List<Offset>, origin: Offset) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val strokeDone by rememberUpdatedState(onStroke)
    val live = remember { mutableStateListOf<Offset>() }
    var generation by remember { mutableLongStateOf(0L) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ink = tool.color?.let { Color(it.argb) } ?: Color(0xFF8E8E93)
    val width = with(density) { 4.dp.toPx() }
    Canvas(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(tool) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val gen = ++generation
                    live.clear()
                    live.add(down.position)
                    drag(down.id) { change: PointerInputChange ->
                        change.consume()
                        live.add(change.position)
                    }
                    strokeDone(live.toList(), origin)
                    scope.launch {
                        delay(320)
                        if (generation == gen) live.clear()
                    }
                }
            },
    ) {
        if (live.size > 1) {
            val path = Path().apply {
                moveTo(live[0].x, live[0].y)
                for (i in 1 until live.size) lineTo(live[i].x, live[i].y)
            }
            drawPath(path, ink.copy(alpha = 0.85f), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else if (live.size == 1) {
            drawCircle(ink.copy(alpha = 0.85f), width, live[0])
        }
    }
}

/**
 * Frosted-glass colour menu: red, blue, yellow, green, eraser. It can be dragged anywhere by
 * its top grip or its background. Without [onClose] there is no close button.
 */
@Composable
internal fun MarkPalette(
    tool: MarkTool,
    colors: ReaderColors,
    onSelect: (MarkTool) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    withNote: Boolean = true,
) {
    val shape = RoundedCornerShape(30.dp)
    val glass = if (colors.isDark) Color(0xFF3A3A40) else Color.White
    var drag by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
            .background(Brush.verticalGradient(listOf(glass.copy(alpha = 0.80f), glass.copy(alpha = 0.55f))), shape)
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (colors.isDark) 0.35f else 0.9f), Color.White.copy(alpha = 0.12f))),
                shape,
            )
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag += amount
                }
            }
            .padding(horizontal = 9.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 26.dp, height = 4.dp).background(colors.secondary.copy(alpha = 0.45f), RoundedCornerShape(2.dp)))
        for (t in MarkTool.entries) {
            if (t == MarkTool.NONE || (t == MarkTool.NOTE && !withNote)) continue
            val name = stringResource(
                when (t) {
                    MarkTool.RED -> R.string.mark_red
                    MarkTool.BLUE -> R.string.mark_blue
                    MarkTool.YELLOW -> R.string.mark_yellow
                    MarkTool.GREEN -> R.string.mark_green
                    MarkTool.NOTE -> R.string.mark_note
                    else -> R.string.mark_eraser
                },
            )
            val selected = tool == t
            val grow by animateFloatAsState(if (selected) 1.18f else 1f, label = "pen")
            Box(
                Modifier
                    .size(38.dp)
                    .scale(grow)
                    .semantics { contentDescription = name }
                    .clickable { onSelect(if (selected) MarkTool.NONE else t) },
                contentAlignment = Alignment.Center,
            ) {
                val ring = if (selected) Modifier.border(2.5.dp, colors.text, CircleShape).padding(3.dp) else Modifier.padding(3.dp)
                if (t.color != null) {
                    Box(Modifier.fillMaxSize().then(ring).background(Color(t.color.argb), CircleShape))
                } else {
                    Box(Modifier.fillMaxSize().then(ring).background(colors.secondary.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                        if (t == MarkTool.NOTE) NoteGlyph(NoteKind.TEXT, colors.text, 20.dp) else EraserGlyph(colors.text)
                    }
                }
            }
        }
        if (onClose != null) {
            Icon(
                Icons.Default.Close, stringResource(R.string.mark_close),
                tint = colors.secondary,
                modifier = Modifier.size(32.dp).clickable(onClick = onClose).padding(5.dp),
            )
        }
    }
}

@Composable
private fun EraserGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        rotate(-40f) {
            val w = size.width * 0.82f
            val h = size.height * 0.5f
            val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
            drawRoundRect(color, topLeft, Size(w, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.6.dp.toPx()))
            drawRoundRect(color, Offset(topLeft.x + w * 0.45f, topLeft.y), Size(w * 0.55f, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------- freehand ink on pictures

/** The picture under [p] (root coordinates), with the point relative to it in 0..1. */
internal fun imageAt(p: Offset, registry: MarkRegistry): Triple<Int, Offset, Size>? {
    for ((block, t) in registry.images) {
        val c = t.coords ?: continue
        if (!c.isAttached) continue
        val origin = c.positionInRoot()
        val size = Size(c.size.width.toFloat(), c.size.height.toFloat())
        if (size.width > 0f && Rect(origin, size).contains(p)) return Triple(block, origin, size)
    }
    return null
}

/** Points in [picture] pixels as 0..1 coordinates; consecutive points closer than [minStep] are dropped. */
internal fun normalizedInk(points: List<Offset>, picture: Size, minStep: Float): FloatArray {
    val out = ArrayList<Float>(points.size * 2)
    var last: Offset? = null
    for ((i, p) in points.withIndex()) {
        val prev = last
        val isLast = i == points.lastIndex
        if (prev != null && !isLast && hypot(p.x - prev.x, p.y - prev.y) < minStep) continue
        last = p
        out += (p.x / picture.width).coerceIn(0f, 1f)
        out += (p.y / picture.height).coerceIn(0f, 1f)
    }
    return out.toFloatArray()
}

/** Ids of the lines on a picture of size [picture] (px) that the eraser [path] (picture px) touches. */
internal fun inkHitIds(strokes: List<InkStroke>, path: List<Offset>, picture: Size, tolerance: Float): List<Long> {
    val hit = ArrayList<Long>()
    for (s in strokes) {
        var touched = false
        var i = 0
        while (!touched && i + 1 < s.points.size) {
            val a = Offset(s.points[i] * picture.width, s.points[i + 1] * picture.height)
            val b = if (i + 3 < s.points.size) Offset(s.points[i + 2] * picture.width, s.points[i + 3] * picture.height) else a
            for (p in path) {
                if (distanceToSegment(p, a, b) <= tolerance) {
                    touched = true
                    break
                }
            }
            i += 2
        }
        if (touched) hit += s.id
    }
    return hit
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Draws freehand [strokes] over a picture, scaled to the size of the canvas. */
@Composable
internal fun InkCanvas(strokes: List<InkStroke>, modifier: Modifier = Modifier) {
    val minWidth = with(LocalDensity.current) { 2.5.dp.toPx() }
    Canvas(modifier) {
        val width = max(minWidth, size.width * 0.006f)
        for (s in strokes) {
            if (s.points.size < 2) continue
            val color = Color(s.color.argb).copy(alpha = 0.92f)
            val x0 = s.points[0] * size.width
            val y0 = s.points[1] * size.height
            if (s.points.size < 4) {
                drawCircle(color, width / 2, Offset(x0, y0))
                continue
            }
            val path = Path().apply {
                moveTo(x0, y0)
                var i = 2
                while (i + 1 < s.points.size) {
                    lineTo(s.points[i] * size.width, s.points[i + 1] * size.height)
                    i += 2
                }
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Ink layer filling the picture of block [blockIndex]; also lets strokes find the picture. */
@Composable
internal fun InkLayer(blockIndex: Int, modifier: Modifier = Modifier) {
    val marks = LocalMarks.current
    val target = remember(blockIndex) { ImageTarget() }
    DisposableEffect(blockIndex, marks.registry) {
        marks.registry.images[blockIndex] = target
        onDispose { if (marks.registry.images[blockIndex] === target) marks.registry.images.remove(blockIndex) }
    }
    InkCanvas(marks.ink[blockIndex].orEmpty(), modifier.fillMaxSize().onGloballyPositioned { target.coords = it })
}
