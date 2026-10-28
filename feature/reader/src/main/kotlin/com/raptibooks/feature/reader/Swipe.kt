package com.raptibooks.feature.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A quick sideways swipe across the page, either way (between the reflowed text and the
 * original pages). Vertical scrolling, a pinch, a drag something else took (a wide table or
 * formula scrolling sideways, a text selection) and slow sideways drags are not one.
 */
internal fun Modifier.horizontalSwipe(enabled: Boolean, onSwipe: () -> Unit): Modifier = composed {
    val action by rememberUpdatedState(onSwipe)
    if (!enabled) return@composed this
    pointerInput(Unit) {
        val minDistance = 90.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            var taken = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) taken = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val moved = change.positionChangeIgnoreConsumed()
                if (change.pressed && change.isConsumed && moved != Offset.Zero) taken = true
                dx += moved.x
                dy += moved.y
                if (!change.pressed) {
                    val quick = change.uptimeMillis - down.uptimeMillis <= 700L
                    if (!taken && quick && abs(dx) >= maxOf(minDistance, size.width * 0.25f) && abs(dx) > abs(dy) * 2.5f) action()
                    break
                }
            }
        }
    }
}
