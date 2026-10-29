package com.raptibooks.feature.reader

import android.content.ClipData
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.raptibooks.core.model.MarkKey

/** A character of a text on screen: its text ([MarkKey]) and its offset in it. */
internal data class TextPos(val key: Long, val offset: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int = compareValuesBy(this, other, { it.key }, { it.offset })
}

/**
 * A passage selected with two fingers, from the character where they came down to the one they
 * were dragged to, across paragraphs. The texts it ran over are kept, so a passage longer than
 * the screen can still be copied.
 */
@Stable
internal class TwoFingerSelection {
    var anchor by mutableStateOf<TextPos?>(null)
        private set
    var focus by mutableStateOf<TextPos?>(null)
        private set

    /** The fingers are still on the page. */
    var dragging by mutableStateOf(false)

    private val texts = HashMap<Long, String>()

    val active: Boolean get() = anchor != null && focus != null && anchor != focus

    fun start(at: TextPos) {
        texts.clear()
        anchor = at
        focus = at
    }

    fun extend(to: TextPos) {
        focus = to
    }

    fun remember(key: Long, text: String) {
        texts[key] = text
    }

    /** Keeps the texts on screen that are in the selection, to copy them later. */
    fun rememberTexts(registry: MarkRegistry) {
        val (from, to) = bounds() ?: return
        for ((key, t) in registry.targets) {
            if (key in from.key..to.key) t.layout?.let { remember(key, it.layoutInput.text.text) }
        }
    }

    /** The first ([start]) or the last character of the selection moved to [to] (not past the other end). */
    fun moveEdge(start: Boolean, to: TextPos) {
        val (from, end) = bounds() ?: return
        if (start) {
            anchor = if (to < end) to else end
            focus = end
        } else {
            anchor = from
            focus = if (to > from) to else from
        }
    }

    /** The first and last character selected, in order. */
    fun edges(): Pair<TextPos, TextPos>? = bounds()

    fun clear() {
        anchor = null
        focus = null
        dragging = false
        texts.clear()
    }

    private fun bounds(): Pair<TextPos, TextPos>? {
        val a = anchor ?: return null
        val b = focus ?: return null
        return if (a <= b) a to b else b to a
    }

    /** The selected characters of text [key] ([length] long), if any. */
    fun rangeIn(key: Long, length: Int): IntRange? {
        val (from, to) = bounds() ?: return null
        if (key < from.key || key > to.key) return null
        val start = if (key == from.key) from.offset else 0
        val end = if (key == to.key) to.offset else length
        return if (end > start) start.coerceIn(0, length) until end.coerceIn(0, length) else null
    }

    /** The selected text: paragraphs on lines of their own, the cells of a row side by side. */
    fun text(): String {
        val (from, to) = bounds() ?: return ""
        val sb = StringBuilder()
        var lastBlock = -1
        for (key in texts.keys.filter { it in from.key..to.key }.sorted()) {
            val t = texts.getValue(key)
            val r = rangeIn(key, t.length) ?: continue
            val piece = t.substring(r.first, r.last + 1).trim()
            if (piece.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(if (MarkKey.block(key) == lastBlock) " " else "\n")
            sb.append(piece)
            lastBlock = MarkKey.block(key)
        }
        return sb.toString()
    }
}

internal val LocalSelection = staticCompositionLocalOf { TwoFingerSelection() }

/** The character at [p] (root coordinates) of the texts on screen, or of the text nearest it. */
internal fun charAt(p: Offset, registry: MarkRegistry): TextPos? {
    var best: Pair<Long, TextTarget>? = null
    var bestDistance = Float.MAX_VALUE
    for ((key, t) in registry.targets) {
        val c = t.coords ?: continue
        val l = t.layout ?: continue
        if (!c.isAttached) continue
        val o = c.positionInRoot()
        val top = o.y
        val bottom = o.y + l.size.height
        val dy = if (p.y < top) top - p.y else if (p.y > bottom) p.y - bottom else 0f
        val dx = if (p.x < o.x) o.x - p.x else if (p.x > o.x + l.size.width) p.x - o.x - l.size.width else 0f
        val d = dy * 4f + dx
        if (d < bestDistance) {
            bestDistance = d
            best = key to t
        }
    }
    val (key, t) = best ?: return null
    val o = t.coords!!.positionInRoot()
    val l = t.layout!!
    val local = Offset((p.x - o.x).coerceIn(0f, l.size.width.toFloat()), (p.y - o.y).coerceIn(0f, l.size.height - 1f))
    return TextPos(key, l.getOffsetForPosition(local))
}

/**
 * Two fingers on the page select text: from where they came down to where they are dragged
 * (the page scrolls when they reach its top or bottom edge). One finger scrolls as always.
 */
internal fun Modifier.twoFingerSelect(selection: TwoFingerSelection, registry: MarkRegistry, list: ScrollableState, enabled: Boolean): Modifier = composed {
    if (!enabled) return@composed this
    // (where the page is on screen, as the texts are placed in root coordinates)
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onGloballyPositioned { place[0] = it }.pointerInput(selection, registry) {
        val edge = 56.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // (until a second finger comes down, it is the page's gesture)
            var event = awaitPointerEvent(PointerEventPass.Initial)
            while (event.changes.count { it.pressed } < 2) {
                if (event.changes.none { it.pressed }) return@awaitEachGesture
                event = awaitPointerEvent(PointerEventPass.Initial)
            }
            fun centre(): Offset {
                val down = event.changes.filter { it.pressed }
                return down.fold(Offset.Zero) { acc, c -> acc + c.position } / down.size.toFloat()
            }
            fun root(local: Offset): Offset = place[0]?.takeIf { it.isAttached }?.let { it.positionInRoot() + local } ?: local
            val origin = centre()
            charAt(root(origin), registry)?.let { selection.start(it) } ?: return@awaitEachGesture
            selection.dragging = true
            var moved = false
            while (true) {
                event.changes.forEach { it.consume() }
                val down = event.changes.filter { it.pressed }
                if (down.isEmpty()) break
                val at = centre()
                if (!moved && (at - origin).getDistance() > slop) moved = true
                if (moved) {
                    // (near the top or bottom edge the page scrolls on under the fingers)
                    if (at.y < edge) list.dispatchRawDelta(-(edge - at.y) * 0.4f)
                    if (at.y > size.height - edge) list.dispatchRawDelta((at.y - size.height + edge) * 0.4f)
                    charAt(root(at), registry)?.let { selection.extend(it) }
                    selection.rememberTexts(registry)
                }
                event = awaitPointerEvent(PointerEventPass.Initial)
            }
            selection.dragging = false
            if (!moved || !selection.active) selection.clear()
        }
    }
}


private val HandleColor = Color(0xFF3D7BD9)
private val HandleSize = 22.dp

/**
 * The two handles of a selection, under its first and last characters: dragged with one
 * finger, they make the selection longer or shorter, across paragraphs (the page scrolls when
 * one reaches the top or bottom edge). [place] is where the page (the list) is on screen;
 * the handles are placed in the same box.
 */
@Composable
internal fun SelectionHandles(selection: TwoFingerSelection, registry: MarkRegistry, list: ScrollableState, listState: LazyListState, place: () -> LayoutCoordinates?) {
    if (!selection.active) return
    for (start in listOf(true, false)) {
        // (where the finger is, root coordinates, while it drags this handle)
        val finger = remember { arrayOf(Offset.Zero) }
        Box(
            Modifier
                .offset {
                    // (read so the handle follows the page as it scrolls)
                    listState.firstVisibleItemIndex
                    listState.firstVisibleItemScrollOffset
                    val at = handleAt(selection, registry, start, place()) ?: return@offset IntOffset(-10_000, -10_000)
                    val size = HandleSize.roundToPx()
                    IntOffset((if (start) at.x - size else at.x).roundToInt(), at.y.roundToInt())
                }
                .size(HandleSize)
                .background(HandleColor, if (start) RoundedCornerShape(50, 0, 50, 50) else RoundedCornerShape(0, 50, 50, 50))
                .pointerInput(selection, start) {
                    val edge = 56.dp.toPx()
                    detectDragGestures(
                        onDragStart = {
                            val origin = place()?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                            val at = handleAt(selection, registry, start, place()) ?: return@detectDragGestures
                            // (the line just above the handle: where the finger points)
                            finger[0] = origin + at - Offset(0f, HandleSize.toPx() * 0.6f)
                            selection.dragging = true
                        },
                        onDragEnd = { selection.dragging = false },
                        onDragCancel = { selection.dragging = false },
                        onDrag = { change, delta ->
                            change.consume()
                            finger[0] += delta
                            val box = place()?.takeIf { it.isAttached }
                            if (box != null) {
                                val y = finger[0].y - box.positionInRoot().y
                                val height = box.size.height
                                if (y < edge) list.dispatchRawDelta(-(edge - y) * 0.4f)
                                if (y > height - edge) list.dispatchRawDelta((y - height + edge) * 0.4f)
                            }
                            charAt(finger[0], registry)?.let { selection.moveEdge(start, it) }
                            selection.rememberTexts(registry)
                        },
                    )
                },
        )
    }
}

/** Where a handle goes (in [place]'s coordinates): under the start or the end of the selection. */
private fun handleAt(selection: TwoFingerSelection, registry: MarkRegistry, start: Boolean, place: LayoutCoordinates?): Offset? {
    val (from, to) = selection.edges() ?: return null
    val pos = if (start) from else to
    val t = registry.targets[pos.key] ?: return null
    val c = t.coords?.takeIf { it.isAttached } ?: return null
    val l = t.layout ?: return null
    val box = place?.takeIf { it.isAttached } ?: return null
    val cursor = l.getCursorRect(pos.offset.coerceIn(0, l.layoutInput.text.length))
    return c.positionInRoot() - box.positionInRoot() + Offset(cursor.left, cursor.bottom)
}

/** Over the page while a passage is selected: copy it, or let it go. */
@Composable
internal fun SelectionBar(selection: TwoFingerSelection, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.text_copied)
    AnimatedVisibility(selection.active && !selection.dragging, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                TextButton(onClick = {
                    val text = selection.text()
                    selection.clear()
                    if (text.isNotEmpty()) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(stringResource(R.string.text_copy)) }
                IconButton(onClick = { selection.clear() }) { Icon(Icons.Default.Close, stringResource(R.string.image_close)) }
            }
        }
    }
}
