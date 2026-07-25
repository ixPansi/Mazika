package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * MAZIKA: drag-to-reorder for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Written against plain Compose gestures rather than pulling in a reordering
 * library, matching how the rest of the app handles gestures (see
 * [io.github.zyrouge.symphony.ui.components.swipeable]).
 *
 * Behaviour: long-press an item to pick it up, drag to move it. The list reorders
 * live as you cross item boundaries, so what you see is the final order, and the
 * list auto-scrolls when you drag near an edge. Releasing commits the result via
 * [onSettle], which is the only point that touches persistent state.
 */
class ReorderableState internal constructor(
    internal val listState: LazyListState,
    private val coroutineScope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettle: () -> Unit,
) {
    /** Index currently being dragged, or null when idle. */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** Pixel offset of the dragged item from its resting position. */
    var draggingOffset by mutableFloatStateOf(0f)
        private set

    private var draggingItemInitialOffset = 0

    internal fun onDragStart(index: Int) {
        draggingIndex = index
        draggingOffset = 0f
        draggingItemInitialOffset = visibleItemOffset(index) ?: 0
    }

    internal fun onDrag(delta: Float) {
        val current = draggingIndex ?: return
        draggingOffset += delta

        // Absolute position of the dragged item's top and bottom edges.
        val size = visibleItemSize(current) ?: return
        val start = draggingItemInitialOffset + draggingOffset
        val end = start + size

        // Find the item whose midpoint the dragged item has crossed.
        val target = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                val middle = item.offset + item.size / 2f
                item.index != current && middle in start..end
            }
            ?: return

        onMove(current, target.index)
        draggingIndex = target.index
        // Keep the item visually under the finger across the swap.
        draggingOffset += (draggingItemInitialOffset - (visibleItemOffset(target.index) ?: 0))
        draggingItemInitialOffset = visibleItemOffset(target.index) ?: 0

        autoScroll(start, end)
    }

    internal fun onDragEnd() {
        if (draggingIndex != null) {
            onSettle()
        }
        draggingIndex = null
        draggingOffset = 0f
    }

    /** Scrolls the list when the dragged item is held near an edge. */
    private fun autoScroll(start: Float, end: Float) {
        val info = listState.layoutInfo
        val viewportStart = info.viewportStartOffset.toFloat()
        val viewportEnd = info.viewportEndOffset.toFloat()
        val overshoot = when {
            end > viewportEnd -> end - viewportEnd
            start < viewportStart -> start - viewportStart
            else -> 0f
        }
        if (abs(overshoot) > 0f) {
            coroutineScope.launch {
                listState.scrollBy(overshoot.coerceIn(-AUTO_SCROLL_STEP, AUTO_SCROLL_STEP))
            }
        }
    }

    private fun visibleItemOffset(index: Int) = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == index }?.offset

    private fun visibleItemSize(index: Int) = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == index }?.size

    companion object {
        private const val AUTO_SCROLL_STEP = 24f
    }
}

/**
 * Creates a [ReorderableState].
 *
 * [onMove] is called continuously while dragging so the list can be reordered in
 * memory; [onSettle] fires once on release, and is where the new order should be
 * persisted (a playlist write, a queue update) so dragging does not hammer storage.
 */
@Composable
fun rememberReorderableState(
    listState: LazyListState,
    coroutineScope: CoroutineScope,
    onMove: (from: Int, to: Int) -> Unit,
    onSettle: () -> Unit = {},
) = remember(listState) {
    ReorderableState(listState, coroutineScope, onMove, onSettle)
}

/**
 * Makes a whole row draggable after a long press. Use when there is no room for a
 * handle; note it competes with the row's own click, hence the long-press gate.
 */
fun Modifier.reorderableItem(state: ReorderableState, index: Int) = this.pointerInput(index) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.onDragStart(index) },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount.y)
        },
        onDragEnd = { state.onDragEnd() },
        onDragCancel = { state.onDragEnd() },
    )
}

/**
 * Turns a small element into a drag handle. Dragging starts immediately (no long
 * press), which is the expected feel for a handle, and because the gesture lives on
 * the handle rather than the row, tapping the row still plays the song.
 */
fun Modifier.reorderableHandle(state: ReorderableState, index: Int) = this.pointerInput(index) {
    detectDragGestures(
        onDragStart = { state.onDragStart(index) },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount.y)
        },
        onDragEnd = { state.onDragEnd() },
        onDragCancel = { state.onDragEnd() },
    )
}
