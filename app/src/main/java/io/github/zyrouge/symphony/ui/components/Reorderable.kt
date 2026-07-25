package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive

/**
 * MAZIKA: drag-to-reorder for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Built on plain Compose gestures rather than a reordering library, matching how the
 * rest of the app handles gestures (see [swipeable]).
 *
 * Two coordinate spaces are in play and mixing them up is the classic bug here:
 *
 *  - **data indices** — positions in the caller's list, what [onMove] speaks in;
 *  - **lazy indices** — positions in the `LazyColumn`, what [LazyListState.layoutInfo]
 *    reports. A list with a header `item {}` before its rows is offset by one.
 *
 * [firstItemIndex] is the lazy index of data item 0, and [itemCount] is how many rows
 * are reorderable. Everything outside that window (headers, footers, a second section)
 * is ignored as a drop target.
 *
 * The dragged row's visual offset is **derived from live layout every frame** rather
 * than accumulated by hand. After a move the lazy list has not relaid out yet, so any
 * offset computed from positions read at that moment is stale and the row visibly
 * jumps. Deriving it means the row stays exactly under the finger no matter how many
 * swaps happen or how fast the drag is.
 */
class ReorderableState internal constructor(
    internal val listState: LazyListState,
    private val firstItemIndex: () -> Int,
    private val itemCount: () -> Int,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettle: () -> Unit,
) {
    /** Data index currently being dragged, or null when idle. */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** Where the dragged row sat when the drag began. */
    private var initialOffset by mutableIntStateOf(0)

    /** Total distance dragged since the gesture started. */
    private var dragged by mutableFloatStateOf(0f)

    /**
     * How far the list wants to scroll this frame because the row was pushed past an
     * edge. Read by the auto-scroll loop in [rememberReorderableState]; a plain value
     * rather than a coroutine launch per pointer event, which used to spawn dozens of
     * competing scrolls a second.
     */
    internal var scrollDelta by mutableFloatStateOf(0f)
        private set

    /** Whether this gesture actually reordered anything, so a tap-and-release on the
     * handle does not trigger a pointless persist. */
    private var moved = false

    private fun lazyIndexOf(dataIndex: Int) = firstItemIndex() + dataIndex

    private fun itemInfoAt(dataIndex: Int): LazyListItemInfo? {
        val lazyIndex = lazyIndexOf(dataIndex)
        return listState.layoutInfo.visibleItemsInfo.fastFirstOrNull { it.index == lazyIndex }
    }

    private val draggingItem: LazyListItemInfo?
        get() = draggingIndex?.let { itemInfoAt(it) }

    /**
     * Pixel offset to apply to the dragged row. Derived, never accumulated, so it is
     * always correct against the current layout.
     */
    val draggingOffset: Float
        get() = draggingItem?.let { initialOffset + dragged - it.offset } ?: 0f

    internal fun onDragStart(dataIndex: Int) {
        if (dataIndex !in 0 until itemCount()) return
        val item = itemInfoAt(dataIndex)
        draggingIndex = dataIndex
        initialOffset = item?.offset ?: 0
        dragged = 0f
        scrollDelta = 0f
        moved = false
    }

    internal fun onDrag(delta: Float) {
        dragged += delta
        val current = draggingItem ?: return

        // Where the dragged row actually is on screen right now.
        val start = current.offset + draggingOffset
        val middle = start + current.size / 2f

        // A move triggers when the dragged row's midpoint crosses into another row,
        // which gives one clean swap per boundary instead of several per frame.
        val first = firstItemIndex()
        val last = first + itemCount() - 1
        val target = listState.layoutInfo.visibleItemsInfo.fastFirstOrNull { item ->
            item.index != current.index &&
                    item.index in first..last &&
                    middle.toInt() in item.offset..(item.offset + item.size)
        }
        if (target != null) {
            val to = target.index - first
            val from = current.index - first
            onMove(from, to)
            draggingIndex = to
            moved = true
        }

        updateScrollDelta(start, start + current.size)
    }

    internal fun onDragEnd() {
        val shouldSettle = draggingIndex != null && moved
        draggingIndex = null
        dragged = 0f
        initialOffset = 0
        scrollDelta = 0f
        moved = false
        if (shouldSettle) {
            onSettle()
        }
    }

    /** Nudges the list when the dragged row is pushed past a viewport edge. */
    private fun updateScrollDelta(start: Float, end: Float) {
        val info = listState.layoutInfo
        scrollDelta = when {
            end > info.viewportEndOffset -> (end - info.viewportEndOffset)
            start < info.viewportStartOffset -> (start - info.viewportStartOffset)
            else -> 0f
        }.coerceIn(-AUTO_SCROLL_STEP, AUTO_SCROLL_STEP)
    }

    companion object {
        private const val AUTO_SCROLL_STEP = 24f
    }
}

/**
 * A list entry carrying an identity that survives reordering.
 *
 * LazyColumn keys must be unique *and* stable for a reorder to look right. The obvious
 * choices both fail here: a song id is not unique (the same song can sit in a queue or
 * playlist twice) and an index is not stable — it changes for every row a move shifts,
 * so Compose tears rows down and rebuilds them instead of moving them.
 *
 * [uid] combines the value with how many times it has already appeared, so it is unique
 * within the list *and* unchanged by reordering. That matters at the end of a drag: the
 * list is rebuilt from the persisted order, and with index-derived keys every moved row
 * would get a new key and visibly flash.
 */
data class ReorderableEntry<T>(val uid: String, val value: T)

fun <T> List<T>.toReorderableEntries(): List<ReorderableEntry<T>> {
    val seen = HashMap<T, Int>(size)
    return map { value ->
        val occurrence = seen.getOrDefault(value, 0)
        seen[value] = occurrence + 1
        ReorderableEntry("$value#$occurrence", value)
    }
}

/**
 * Creates a [ReorderableState].
 *
 * [onMove] runs continuously while dragging so the list reorders in memory;
 * [onSettle] fires once on release and is where the order should be persisted, so a
 * drag does not hammer storage.
 *
 * [firstItemIndex] is the lazy-list index of data item 0 — pass 1 if the list emits a
 * single header `item {}` before its rows. [itemCount] bounds the reorderable region so
 * a row can never be dropped onto a header or a following section.
 */
@Composable
fun rememberReorderableState(
    listState: LazyListState,
    itemCount: () -> Int,
    firstItemIndex: () -> Int = { 0 },
    onMove: (from: Int, to: Int) -> Unit,
    onSettle: () -> Unit = {},
): ReorderableState {
    val currentCount by rememberUpdatedState(itemCount)
    val currentFirst by rememberUpdatedState(firstItemIndex)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSettle by rememberUpdatedState(onSettle)
    val state = remember(listState) {
        ReorderableState(
            listState = listState,
            firstItemIndex = { currentFirst() },
            itemCount = { currentCount() },
            onMove = { from, to -> currentOnMove(from, to) },
            onSettle = { currentOnSettle() },
        )
    }
    // One scroll loop for the whole drag instead of a coroutine per pointer event.
    val isDragging = state.draggingIndex != null
    LaunchedEffect(state, isDragging) {
        if (!isDragging) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val delta = state.scrollDelta
            if (delta != 0f) {
                listState.scrollBy(delta)
            }
        }
    }
    return state
}

/**
 * Styling for a reorderable row: the dragged one is lifted and follows the finger,
 * every other one animates into its new place instead of teleporting.
 *
 * The `dragging` check goes through [derivedStateOf] deliberately. Reading
 * [ReorderableState.draggingIndex] directly in composition invalidates *every* visible
 * row on every swap, and these rows are not cheap to recompose; derived state only
 * notifies the two rows whose value actually flips.
 */
@Composable
fun LazyItemScope.reorderableItemModifier(
    state: ReorderableState,
    index: Int,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) {
        // Placement animation is deliberately not applied to plain lists: re-sorting a
        // library of thousands of songs would animate every row at once.
        return Modifier
    }
    val dragging by remember(state, index) {
        derivedStateOf { state.draggingIndex == index }
    }
    return when {
        dragging -> Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggingOffset
                shadowElevation = 8f
            }

        else -> Modifier.animateItem()
    }
}

/**
 * Makes a whole row draggable after a long press. Use where there is no room for a
 * handle; the long-press gate keeps it from fighting the row's own click.
 */
@Composable
fun Modifier.reorderableItem(state: ReorderableState, index: Int): Modifier {
    val currentIndex by rememberUpdatedState(index)
    return this.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(currentIndex) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount.y)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragEnd() },
        )
    }
}

/**
 * Turns a small element into a drag handle. Dragging starts immediately, which is the
 * expected feel for a handle, and because the gesture lives on the handle the row's
 * own tap keeps working.
 *
 * The `pointerInput` key is the *state*, never the index. Compose cancels and relaunches
 * a `pointerInput` block whenever its key changes, and a row's index changes on every
 * swap — keying on the index destroyed the in-flight gesture mid-drag, so a drag died
 * after moving a single position. The index is read through [rememberUpdatedState]
 * instead, which stays current without restarting anything.
 */
@Composable
fun Modifier.reorderableHandle(state: ReorderableState, index: Int): Modifier {
    val currentIndex by rememberUpdatedState(index)
    return this.pointerInput(state) {
        detectDragGestures(
            onDragStart = { state.onDragStart(currentIndex) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount.y)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragEnd() },
        )
    }
}
