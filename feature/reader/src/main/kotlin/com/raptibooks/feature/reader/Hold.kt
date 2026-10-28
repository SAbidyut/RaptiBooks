package com.raptibooks.feature.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/** How long one finger is held still to switch between the reflowed text and the original page. */
private const val HOLD_MS = 600L

/** How long two fingers are held still to copy the text under them. */
private const val TWO_FINGER_HOLD_MS = 600L

/**
 * One finger held still on the page for 0.6 s ([onHold]), or two fingers held still
 * ([onTwoFingerHold], at the first finger). Scrolling, lifting a finger or a pinch is neither,
 * and taps go on to the page as before.
 */
internal fun Modifier.holdGestures(
    enabled: Boolean,
    onHold: (Offset) -> Unit,
    onTwoFingerHold: ((Offset) -> Unit)? = null,
): Modifier = composed {
    val hold by rememberUpdatedState(onHold)
    val two by rememberUpdatedState(onTwoFingerHold)
    val haptic = LocalHapticFeedback.current
    if (!enabled) return@composed this
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            // (true: a second finger came down; false: the gesture ended or moved; null: held)
            val second = withTimeoutOrNull(HOLD_MS) {
                while (true) {
                    val event = awaitPointerEvent()
                    val c = event.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed || (c.position - start).getDistance() > slop) return@withTimeoutOrNull false
                    if (event.changes.count { it.pressed } >= 2) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            val fired = when (second) {
                null -> {
                    hold(start)
                    true
                }
                true -> {
                    val action = two
                    val stayed = action != null && withTimeoutOrNull(TWO_FINGER_HOLD_MS) {
                        val origins = HashMap<Long, Offset>()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } < 2) return@withTimeoutOrNull false
                            for (c in event.changes) {
                                val o = origins.getOrPut(c.id.value) { c.position }
                                if ((c.position - o).getDistance() > slop * 2f) return@withTimeoutOrNull false
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    } == null
                    if (stayed) action?.invoke(start)
                    stayed
                }
                false -> false
            }
            if (fired) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // The fingers lifting after it are no tap on the page.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
    }
}
