package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * MAZIKA: drag-to-reorder for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Built on plain Compose gestures rather than a reordering library, matching how the
 * rest of the app handles gestures (see [swipeable]).
 *
 * The dragged row's visual offset is **derived from live layout info every frame**
 * rather than accumulated by hand. That matters: after a move the lazy list has not
 * relaid out yet, so any offset computed from positions read at that moment is stale
 * and the row visibly jumps. Deriving it means the row stays exactly under the finger
 * no matter how many swaps happen or how fast the drag is.
 *
 * A move is triggered when the dragged row's midpoint crosses into another row's
 * bounds, which gives one clean swap per boundary instead of several per frame.
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

    /** Where the dragged row sat when the drag began. */
    private var initialOffset by mutableIntStateOf(0)

    /** Total distance dragged since the gesture started. */
    private var dragged by mutableFloatStateOf(0f)

    private val draggingItem: LazyListItemInfo?
        get() = draggingIndex?.let { index ->
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        }

    /**
     * Pixel offset to apply to the dragged row. Derived, never accumulated, so it is
     * always correct against the current layout.
     */
    val draggingOffset: Float
        get() = draggingItem?.let { initialOffset + dragged - it.offset } ?: 0f

    internal fun onDragStart(index: Int) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        draggingIndex = index
        initialOffset = item?.offset ?: 0
        dragged = 0f
    }

    internal fun onDrag(delta: Float) {
        dragged += delta
        val current = draggingItem ?: return

        // Where the dragged row actually is on screen right now.
        val start = current.offset + draggingOffset
        val middle = start + current.size / 2f

        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != current.index && middle.toInt() in item.offset..(item.offset + item.size)
        }
        if (target != null) {
            onMove(current.index, target.index)
            draggingIndex = target.index
        }

        autoScroll(start, start + current.size)
    }

    internal fun onDragEnd() {
        if (draggingIndex != null) {
            onSettle()
        }
        draggingIndex = null
        dragged = 0f
        initialOffset = 0
    }

    /** Nudges the list when the dragged row is pushed past a viewport edge. */
    private fun autoScroll(start: Float, end: Float) {
        val info = listState.layoutInfo
        val overshoot = when {
            end > info.viewportEndOffset -> end - info.viewportEndOffset
            start < info.viewportStartOffset -> start - info.viewportStartOffset
            else -> return
        }
        coroutineScope.launch {
            listState.scrollBy(overshoot.coerceIn(-AUTO_SCROLL_STEP, AUTO_SCROLL_STEP))
        }
    }

    companion object {
        private const val AUTO_SCROLL_STEP = 20f
    }
}

/**
 * A list entry carrying an identity that survives reordering.
 *
 * LazyColumn keys must be unique *and* stable for a reorder to look right. The obvious
 * choices both fail here: a song id is not unique (the same song can sit in a queue or
 * playlist twice) and an index is not stable (it changes for every row a move shifts,
 * so Compose tears rows down and rebuilds them instead of moving them, which is what
 * makes a drag feel janky). [uid] is assigned once per snapshot and travels with the
 * value as it moves.
 */
data class ReorderableEntry<T>(val uid: Long, val value: T)

fun <T> List<T>.toReorderableEntries(): List<ReorderableEntry<T>> =
    mapIndexed { index, value -> ReorderableEntry(index.toLong(), value) }

/**
 * Creates a [ReorderableState].
 *
 * [onMove] runs continuously while dragging so the list reorders in memory;
 * [onSettle] fires once on release and is where the order should be persisted, so a
 * drag does not hammer storage.
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
 * Makes a whole row draggable after a long press. Use where there is no room for a
 * handle; the long-press gate keeps it from fighting the row's own click.
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
 * Turns a small element into a drag handle. Dragging starts immediately, which is the
 * expected feel for a handle, and because the gesture lives on the handle the row's
 * own tap keeps working.
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
