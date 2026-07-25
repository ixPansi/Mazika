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
    /**
     * Key of the row being dragged, or null when idle.
     *
     * Identity, never position. A `LazyColumn` is measured once per frame but a
     * touchscreen reports several times per frame, so between a move and the next
     * measurement `layoutInfo` still describes the old arrangement. An index looked up
     * against it names *a different row* — or nothing at all, once the position it names
     * has scrolled out of view. A key always resolves to the row it belongs to, at
     * whatever position layout currently believes it occupies.
     */
    var draggingKey by mutableStateOf<Any?>(null)
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

    /**
     * Height of the dragged row, remembered from the last frame its slot was measured.
     *
     * A `LazyColumn` only reports items whose slot is inside the viewport, so at the very
     * top or bottom of a drag the dragged row can briefly vanish from `layoutInfo`. The
     * clamp below needs its height in exactly those frames, which is why it is kept here
     * rather than read live.
     */
    private var draggedSize = 0

    /**
     * Last offset computed while the row's slot was measurable. [draggingOffset] falls
     * back to this instead of zero: zero means "sitting in its own slot", and if the slot
     * has scrolled off screen that reads as the row vanishing mid-drag.
     */
    private var lastGoodOffset by mutableFloatStateOf(0f)

    /**
     * Lazy index the dragged row is expected to occupy once layout applies the last move.
     * While `layoutInfo` still disagrees, no further move is considered — see
     * [recheckTarget].
     */
    private var awaitingIndex: Int? = null

    private fun itemInfoWithKey(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.fastFirstOrNull { it.key == key }

    private val draggingItem: LazyListItemInfo?
        get() = draggingKey?.let { itemInfoWithKey(it) }

    /**
     * Pixel offset to apply to the dragged row. Derived, never accumulated, so it is
     * always correct against the current layout.
     */
    val draggingOffset: Float
        get() = draggingItem?.let { initialOffset + dragged - it.offset } ?: lastGoodOffset

    internal fun onDragStart(key: Any) {
        // Refuse to start rather than starting a drag that tracks nothing: an
        // unmeasurable row, or one outside the reorderable window, has no anchor.
        val item = itemInfoWithKey(key) ?: return
        val first = firstItemIndex()
        if (item.index - first !in 0 until itemCount()) {
            return
        }
        draggingKey = key
        initialOffset = item.offset
        draggedSize = item.size
        dragged = 0f
        lastGoodOffset = 0f
        scrollDirection = 0
        moved = false
        awaitingIndex = null
    }

    internal fun onDrag(delta: Float) {
        dragged += delta
        clampToViewport()
        recheckTarget()
        updateScrollDirection()
    }

    /**
     * Keeps the dragged row inside the visible area.
     *
     * Without this the row can be pushed past either end of the viewport. Its *slot*
     * follows it out, the `LazyColumn` disposes anything whose slot is off screen, and
     * the row simply stops being drawn — which is what "it disappears when I go too high
     * or too low" is. Clamping the travel means the row always has somewhere visible to
     * be, and the finger can move past the edge without dragging the row out with it.
     *
     * Auto-scroll still engages, because it triggers on a band *inside* the viewport
     * rather than on the row overshooting it.
     */
    private fun clampToViewport() {
        val info = listState.layoutInfo
        val size = draggingItem?.size?.also { draggedSize = it }
            ?: draggedSize.takeIf { it > 0 }
            // No height known at all: clamping to a zero-height row would let the drag
            // travel a whole row past the bottom, which is the case this exists to stop.
            ?: return
        val min = (info.viewportStartOffset - initialOffset).toFloat()
        val max = (info.viewportEndOffset - size - initialOffset).toFloat()
        if (max < min) {
            // Viewport shorter than one row; nothing sensible to clamp to.
            return
        }
        dragged = dragged.coerceIn(min, max)
    }

    internal fun onDragEnd() {
        val shouldSettle = draggingKey != null && moved
        draggingKey = null
        dragged = 0f
        initialOffset = 0
        scrollDirection = 0
        moved = false
        awaitingIndex = null
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
        draggedSize = current.size

        // Where the dragged row actually is on screen right now.
        val offset = initialOffset + dragged - current.offset
        lastGoodOffset = offset

        // At most one move per layout pass. A touchscreen reports two to four times per
        // drawn frame, and until the list has been measured again `layoutInfo` still
        // describes the pre-move arrangement — the midpoint is still sitting inside the
        // neighbour it just swapped with, so re-testing would swap past it again, two or
        // three more times, from a single finger movement.
        awaitingIndex?.let {
            if (current.index != it) {
                return
            }
            awaitingIndex = null
        }

        val start = current.offset + offset
        val middle = start + current.size / 2f

        // A move triggers when the dragged row's midpoint crosses into another row. The
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
        awaitingIndex = target.index
        moved = true
    }

    /**
     * Decides whether the list should be scrolling, and which way.
     *
     * The test is the same at both ends — same band, same comparison — so neither
     * direction can engage earlier or run further than the other.
     */
    internal fun updateScrollDirection() {
        // No measurable slot means the row is momentarily off screen. Stop scrolling
        // rather than leaving the previous direction running, which would carry it
        // further away and keep it there.
        val current = draggingItem ?: run {
            scrollDirection = 0
            return
        }
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

    /** Re-applies the viewport clamp from the scroll loop, where the row's slot moves
     * without the finger having moved at all. */
    internal fun clampAndRecover() {
        clampToViewport()
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
    val isDragging = state.draggingKey != null
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
                // The list is already at an end; stop pushing against it. Re-clamp
                // first, in case the row had drifted past the edge on the way here.
                state.clampAndRecover()
                state.onScrollExhausted()
            } else {
                // Keep swapping rows as the list moves under a stationary finger, and
                // re-clamp: the row's slot has just moved relative to the viewport.
                state.clampAndRecover()
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
    key: Any,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) {
        // Placement animation is deliberately not applied to plain lists: re-sorting a
        // library of thousands of songs would animate every row at once.
        return Modifier
    }
    // Matched on the row's key, not its index: an index comparison hands the lift and
    // the offset to whichever row currently sits at that position, which during a drag
    // is regularly not the row being dragged.
    val dragging by remember(state, key) {
        derivedStateOf { state.draggingKey == key }
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
 * How a displaced row travels to its new place. Half the speed of Compose's default
 * (`StiffnessMediumLow`, 400), because at the default rows snap past each other faster
 * than the eye follows. Not slower than this: a spring retargeted mid-flight keeps its
 * velocity, so at `StiffnessVeryLow` a run of quick swaps compounded into rows visibly
 * flinging past their slots. No bounce either — a row overshooting looks like a second
 * move that never happened.
 */
private val ReorderPlacementSpec = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * Makes a whole row draggable after a long press. Use where there is no room for a
 * handle; the long-press gate keeps it from fighting the row's own click.
 */
@Composable
fun Modifier.reorderableItem(state: ReorderableState, key: Any): Modifier {
    val currentKey by rememberUpdatedState(key)
    return this.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(currentKey) },
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
 * The `pointerInput` key is the *state*, never the row. Compose cancels and relaunches a
 * `pointerInput` block whenever its key changes, and a row's position changes on every
 * swap — keying on that destroyed the in-flight gesture mid-drag, so a drag died after
 * moving a single position.
 */
@Composable
fun Modifier.reorderableHandle(state: ReorderableState, key: Any): Modifier {
    val currentKey by rememberUpdatedState(key)
    return this.pointerInput(state) {
        detectDragGestures(
            onDragStart = { state.onDragStart(currentKey) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount.y)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragEnd() },
        )
    }
}
