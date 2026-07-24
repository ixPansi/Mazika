package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.absoluteValue

enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

/**
 * MAZIKA: pure swipe-direction decision (unit-tested) used by [swipeable].
 *
 * A horizontal swipe wins when it passes the threshold and its magnitude exceeds
 * the vertical movement; otherwise a vertical swipe past the threshold is used. A
 * drag shorter than the threshold yields no direction. This guarantees the
 * downward "open lyrics" swipe only fires on a deliberate, mostly-vertical drag and
 * never conflicts with the horizontal track-change swipes.
 */
fun resolveSwipeDirection(dx: Float, dy: Float, minimumDragAmount: Float): SwipeDirection? {
    val xAbs = dx.absoluteValue
    val yAbs = dy.absoluteValue
    return when {
        xAbs > minimumDragAmount && xAbs > yAbs ->
            if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT

        yAbs > minimumDragAmount ->
            if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP

        else -> null
    }
}

fun Modifier.swipeable(
    minimumDragAmount: Float = 50f,
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
) = pointerInput(Unit) {
    var offset = Offset.Zero
    detectDragGestures(
        onDrag = { pointer, dragAmount ->
            pointer.consume()
            offset += dragAmount
        },
        onDragEnd = {
            when (resolveSwipeDirection(offset.x, offset.y, minimumDragAmount)) {
                SwipeDirection.LEFT -> onSwipeLeft?.invoke()
                SwipeDirection.RIGHT -> onSwipeRight?.invoke()
                SwipeDirection.UP -> onSwipeUp?.invoke()
                SwipeDirection.DOWN -> onSwipeDown?.invoke()
                null -> {}
            }
            offset = Offset.Zero
        },
        onDragCancel = {
            offset = Offset.Zero
        }
    )
}
