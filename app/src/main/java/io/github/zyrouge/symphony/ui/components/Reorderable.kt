package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.abs

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
     * Which way the list should auto-scroll: `-1` towards the start, `+1` towards the
     * end, `0` not at all. Deliberately a *direction* and not a distance — the speed
     * lives entirely in [rememberReorderableState]'s loop, so there is no quantity left
     * that could come out different depending on which way the row is being dragged.
     */
    internal var scrollDirection by mutableIntStateOf(0)
        private set

    /**
     * Width of the band at each end of the viewport inside which auto-scroll engages.
     * Set from the composable, which is where density lives. The same value is used at
     * both ends.
     */
    internal var scrollBandPx = 0f

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
        scrollDirection = 0
        moved = false
    }

    internal fun onDrag(delta: Float) {
        dragged += delta
        recheckTarget()
        updateScrollDirection()
    }

    internal fun onDragEnd() {
        val shouldSettle = draggingIndex != null && moved
        draggingIndex = null
        dragged = 0f
        initialOffset = 0
        scrollDirection = 0
        moved = false
        if (shouldSettle) {
            onSettle()
        }
    }

    /**
     * Swaps the dragged row with whichever row its midpoint now sits inside.
     *
     * Called from the drag gesture *and* from the auto-scroll loop. It has to be both:
     * when the finger is held still at an edge the list keeps moving underneath, and if
     * only the gesture drove this the row would sit pinned while the list scrolled past
     * it, then jump a single position the moment the finger twitched.
     */
    internal fun recheckTarget() {
        val current = draggingItem ?: return

        // Where the dragged row actually is on screen right now.
        val start = current.offset + draggingOffset
        val middle = start + current.size / 2f

        // A move triggers when the dragged row's midpoint crosses into another row,
        // which gives one clean swap per boundary instead of several per frame. The
        // range is half-open: an inclusive one matches two rows at an exact boundary,
        // and the scan would always resolve that to the upper one.
        val first = firstItemIndex()
        val last = first + itemCount() - 1
        val target = listState.layoutInfo.visibleItemsInfo.fastFirstOrNull { item ->
            item.index != current.index &&
                    item.index in first..last &&
                    middle >= item.offset &&
                    middle < item.offset + item.size
        } ?: return
        onMove(current.index - first, target.index - first)
        draggingIndex = target.index - first
        moved = true
    }

    /**
     * Decides whether the list should be scrolling, and which way.
     *
     * The test is the same at both ends — same band, same comparison — so neither
     * direction can engage earlier or run further than the other.
     */
    internal fun updateScrollDirection() {
        val current = draggingItem ?: return
        val info = listState.layoutInfo
        val start = current.offset + draggingOffset
        val end = start + current.size
        val band = minOf(scrollBandPx, current.size.toFloat())
        scrollDirection = when {
            end > info.viewportEndOffset - band -> 1
            start < info.viewportStartOffset + band -> -1
            else -> 0
        }
    }

    /** Called by the loop when the list refused to move, so it stops pushing. */
    internal fun onScrollExhausted() {
        scrollDirection = 0
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
    // Density is only reachable from composition, so resolve the two dp values here and
    // hand the state the pixel band it needs.
    val density = LocalDensity.current
    val speedPxPerSecond = with(density) { REORDER_SCROLL_SPEED.toPx() }
    val bandPx = with(density) { REORDER_SCROLL_BAND.toPx() }
    SideEffect { state.scrollBandPx = bandPx }

    // One scroll loop for the whole drag instead of a coroutine per pointer event.
    val isDragging = state.draggingIndex != null
    LaunchedEffect(state, isDragging, speedPxPerSecond) {
        if (!isDragging) return@LaunchedEffect
        var previousFrame = 0L
        while (isActive) {
            val frame = withFrameNanos { it }
            val elapsed = when (previousFrame) {
                // Nothing to integrate over on the first frame of the drag.
                0L -> 0f
                // Cap the step so a dropped frame cannot turn into one long jump.
                else -> ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(MAX_FRAME_STEP)
            }
            previousFrame = frame
            val direction = state.scrollDirection
            if (direction == 0 || elapsed <= 0f) {
                continue
            }
            // Speed is per second, not per frame: otherwise a 120Hz screen scrolls at
            // twice the rate of a 60Hz one for the same code.
            val consumed = listState.scrollBy(direction * speedPxPerSecond * elapsed)
            if (abs(consumed) < 0.5f) {
                // The list is already at an end; stop pushing against it.
                state.onScrollExhausted()
            } else {
                // Keep swapping rows as the list moves under a stationary finger.
                state.recheckTarget()
            }
        }
    }
    return state
}

/**
 * Auto-scroll rate while a drag sits at the edge of the list, in dp per second, applied
 * identically in both directions. This is the one number to change if the scroll wants
 * to be faster or slower.
 */
private val REORDER_SCROLL_SPEED = 120.dp

/** How close to either end of the viewport a dragged row gets before the list starts
 * scrolling. Capped at the row height so short rows behave sensibly. */
private val REORDER_SCROLL_BAND = 48.dp

/** Longest frame step to integrate over (~4 frames at 60Hz). */
private const val MAX_FRAME_STEP = 0.064f

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
    // animateItem stays in the chain for every row and only its spec changes. Adding or
    // removing it mid-drag restructures the item's modifier chain, which resets the
    // placement animator and shows up as a glitch exactly when a row starts or stops
    // being dragged. The dragged row passes null: its position is driven by hand.
    return Modifier
        .zIndex(if (dragging) 1f else 0f)
        .then(
            when {
                dragging -> Modifier.graphicsLayer {
                    translationY = state.draggingOffset
                    shadowElevation = 8f
                }

                else -> Modifier
            }
        )
        .animateItem(placementSpec = if (dragging) null else ReorderPlacementSpec)
}

/**
 * How a displaced row travels to its new place. Deliberately slower and softer than
 * Compose's default (`StiffnessMediumLow`, ~400): at the default the rows snap past
 * each other faster than the eye follows, which reads as a glitch rather than as a
 * swap. No bounce, because a row overshooting its slot looks like a second move.
 */
private val ReorderPlacementSpec = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessVeryLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

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
