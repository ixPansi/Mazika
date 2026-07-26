package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * MAZIKA: drag-to-reorder for a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * Deliberately a sibling of [Reorderable], not a generalisation of it. That engine is
 * built on [androidx.compose.foundation.lazy.LazyListState] and hit-tests on `y` alone;
 * folding both into one abstraction would put a lot of hard-won list behaviour at risk to
 * save some duplication. What is genuinely shared is already pure and is reused directly:
 * [ReorderSession], [movedItem], [dataIndexForLazyIndex] and [calculateAutoScrollVelocity]
 * (a grid scrolls vertically, so that one applies unchanged).
 *
 * Two things differ by design:
 *
 * - **Targeting is 2D.** Cells sit in rows *and* columns, so the target is the nearest cell
 *   center by straight-line distance rather than by vertical distance.
 * - **There is no "make room" preview.** In a list, rows displace along one axis, which
 *   [calculateReorderPreviewOffset] expresses in a line. Moving a cell in a grid reflows
 *   every following cell across row boundaries; animating that convincingly is a large
 *   problem and animating it badly is worse than not animating it. The source cell is
 *   hidden, its copy floats under the finger, and an insertion bar marks where it will
 *   land - which reads clearly on its own.
 *
 * As in the list engine, the caller's list is never modified during the gesture: a normal
 * release emits at most one move.
 */

internal data class ReorderGridItemBounds(val index: Int, val rect: Rect) {
    val center: Offset get() = rect.center
}

/**
 * Picks the cell center nearest the dragged center. The current target wins a dead band
 * around the boundary, so cells cannot flap when layout and touch samples land on opposite
 * sides of the same pixel.
 */
internal fun calculateReorderTargetGrid(
    currentTarget: Int,
    draggedCenter: Offset,
    items: List<ReorderGridItemBounds>,
    hysteresisPx: Float,
): Int {
    val candidate = items.minWithOrNull(
        compareBy<ReorderGridItemBounds> { (draggedCenter - it.center).getDistance() }
            .thenBy { if (it.index == currentTarget) 0 else 1 }
            .thenBy { it.index }
    ) ?: return currentTarget
    if (candidate.index == currentTarget) return currentTarget

    val current = items.firstOrNull { it.index == currentTarget } ?: return candidate.index
    val currentDistance = (draggedCenter - current.center).getDistance()
    val candidateDistance = (draggedCenter - candidate.center).getDistance()
    val deadBand = hysteresisPx.coerceAtLeast(0f)
        .coerceAtMost((candidate.center - current.center).getDistance() / 2f)
    return if (candidateDistance + deadBand >= currentDistance) {
        currentTarget
    } else {
        candidate.index
    }
}

/** Waits without consuming until a cell reaches long-press, then owns the gesture. */
private suspend fun PointerInputScope.detectGridReorderGestures(state: ReorderableGridState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!state.startDrag(longPress.position)) return@awaitEachGesture

        var pointerId = longPress.id
        var completed = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                var change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (change.isConsumed) break

                if (change.changedToUpIgnoreConsumed()) {
                    val replacement = event.changes.firstOrNull { it.pressed }
                    if (replacement == null) {
                        change.consume()
                        completed = true
                        if (state.draggingKey != null) state.endDrag() else state.cancelDrag()
                        break
                    }
                    pointerId = replacement.id
                    change = replacement
                }

                change.consume()
                if (state.draggingKey != null) state.dragTo(change.position)
            }
        } finally {
            if (!completed) state.cancelDrag()
        }
    }
}

/** Parent-owned reorder state for a vertical grid. Mirrors [ReorderableState]. */
class ReorderableGridState internal constructor(
    internal val gridState: LazyGridState,
    private val itemKeys: () -> List<Any>,
    private val firstItemIndex: () -> Int,
    private val sourceVersion: () -> Any?,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    private val session = ReorderSession()

    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var draggedIndex by mutableStateOf<Int?>(null)
        private set
    var targetIndex by mutableStateOf<Int?>(null)
        private set
    internal var draggedWidthPx by mutableIntStateOf(0)
        private set
    internal var draggedHeightPx by mutableIntStateOf(0)
        private set

    private var pointer by mutableStateOf(Offset.Zero)
    private var pointerOffsetInItem = Offset.Zero

    private fun LazyGridItemInfo.rect() = Rect(
        offset = Offset(offset.x.toFloat(), offset.y.toFloat()),
        size = Size(size.width.toFloat(), size.height.toFloat()),
    )

    private fun visibleReorderableItems(keys: List<Any>): List<Pair<LazyGridItemInfo, Int>> {
        val first = firstItemIndex()
        return gridState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val dataIndex = dataIndexForLazyIndex(item.index, first, keys.size)
                ?: return@mapNotNull null
            if (item.size.width <= 0 || item.size.height <= 0) return@mapNotNull null
            if (item.key != keys[dataIndex]) return@mapNotNull null
            item to dataIndex
        }
    }

    internal fun startDrag(position: Offset): Boolean {
        val keys = itemKeys()
        val hit = visibleReorderableItems(keys).firstOrNull { (item, _) ->
            item.rect().contains(position)
        } ?: return false
        val (item, index) = hit
        val rect = item.rect()

        session.start(index, keys, sourceVersion())
        pointer = position
        pointerOffsetInItem = Offset(
            (position.x - rect.left).coerceIn(0f, rect.width),
            (position.y - rect.top).coerceIn(0f, rect.height),
        )
        draggedWidthPx = item.size.width
        draggedHeightPx = item.size.height
        draggedIndex = index
        targetIndex = index
        draggingKey = item.key
        return true
    }

    internal fun dragTo(position: Offset) {
        if (session.isActive) pointer = position
    }

    internal fun updateTarget(hysteresisPx: Float) {
        if (!session.isActive) return
        val current = session.targetIndex ?: return
        val keys = itemKeys()
        val bounds = visibleReorderableItems(keys).map { (item, index) ->
            ReorderGridItemBounds(index = index, rect = item.rect())
        }
        val origin = overlayOrigin()
        val target = calculateReorderTargetGrid(
            currentTarget = current,
            draggedCenter = Offset(
                origin.x + draggedWidthPx / 2f,
                origin.y + draggedHeightPx / 2f,
            ),
            items = bounds,
            hysteresisPx = hysteresisPx,
        )
        session.updateTarget(target)
        targetIndex = target
    }

    internal fun endDrag() {
        val move = session.finish(itemKeys(), sourceVersion())
        clearVisuals()
        move?.let { onMove(it.from, it.to) }
    }

    internal fun cancelDrag() {
        session.cancel()
        clearVisuals()
    }

    internal fun cancelIfSourceChanged(): Boolean {
        if (!session.isActive) return false
        if (!session.matches(itemKeys(), sourceVersion())) {
            cancelDrag()
            return false
        }
        return true
    }

    private fun clearVisuals() {
        draggingKey = null
        draggedIndex = null
        targetIndex = null
        draggedWidthPx = 0
        draggedHeightPx = 0
        pointerOffsetInItem = Offset.Zero
    }

    /** Top-left of the floating copy, kept inside the viewport. */
    internal fun overlayOrigin(): Offset {
        val info = gridState.layoutInfo
        val viewportTop = info.viewportStartOffset.coerceAtLeast(0).toFloat()
        val viewportBottom = info.viewportEndOffset.toFloat()
        val viewportRight = info.viewportSize.width.toFloat()
        if (draggedHeightPx <= 0) return Offset(0f, viewportTop)
        val maxY = (viewportBottom - draggedHeightPx).coerceAtLeast(viewportTop)
        val maxX = (viewportRight - draggedWidthPx).coerceAtLeast(0f)
        return Offset(
            (pointer.x - pointerOffsetInItem.x).coerceIn(0f, maxX),
            (pointer.y - pointerOffsetInItem.y).coerceIn(viewportTop, maxY),
        )
    }

    /**
     * Top-left of the insertion bar: the leading edge of the target cell, or its trailing
     * edge when the item is moving forward - the same convention the list engine uses.
     */
    internal fun insertionOrigin(markerWidthPx: Float): Offset? {
        val source = session.sourceIndex ?: return null
        val target = session.targetIndex ?: return null
        val first = firstItemIndex()
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == first + target
        } ?: return null
        val rect = item.rect()
        val x = when {
            target > source -> rect.right
            else -> rect.left
        } - markerWidthPx / 2f
        val viewportRight = gridState.layoutInfo.viewportSize.width.toFloat()
        return Offset(
            x.coerceIn(0f, (viewportRight - markerWidthPx).coerceAtLeast(0f)),
            rect.top,
        )
    }

    internal fun autoScrollVelocity(edgeBandPx: Float, maxSpeedPxPerSecond: Float): Float {
        val info = gridState.layoutInfo
        return calculateAutoScrollVelocity(
            pointer = pointer.y,
            viewportStart = info.viewportStartOffset.coerceAtLeast(0).toFloat(),
            viewportEnd = info.viewportEndOffset.toFloat(),
            edgeBandPx = edgeBandPx,
            maxSpeedPxPerSecond = maxSpeedPxPerSecond,
        )
    }

    internal fun canAutoScroll(velocity: Float): Boolean {
        val keys = itemKeys()
        if (keys.isEmpty() || velocity == 0f) return false
        val first = firstItemIndex()
        val boundaryIndex = if (velocity < 0f) first else first + keys.lastIndex
        val boundaryTarget = if (velocity < 0f) 0 else keys.lastIndex
        if (session.targetIndex == boundaryTarget &&
            gridState.layoutInfo.visibleItemsInfo.any { it.index == boundaryIndex }
        ) {
            return false
        }
        return if (velocity < 0f) gridState.canScrollBackward else gridState.canScrollForward
    }
}

/** Grid counterpart to [rememberReorderableState]; same contract for every parameter. */
@Composable
fun rememberReorderableGridState(
    gridState: LazyGridState,
    itemKeys: () -> List<Any>,
    firstItemIndex: () -> Int = { 0 },
    sourceVersion: () -> Any? = { Unit },
    onMove: (from: Int, to: Int) -> Unit,
): ReorderableGridState {
    val currentKeys by rememberUpdatedState(itemKeys)
    val currentFirst by rememberUpdatedState(firstItemIndex)
    val currentVersion by rememberUpdatedState(sourceVersion)
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(gridState) {
        ReorderableGridState(
            gridState = gridState,
            itemKeys = { currentKeys() },
            firstItemIndex = { currentFirst() },
            sourceVersion = { currentVersion() },
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }

    SideEffect { state.cancelIfSourceChanged() }

    val density = LocalDensity.current
    val edgeBandPx = with(density) { GRID_REORDER_EDGE_BAND.toPx() }
    val maxSpeedPxPerSecond = with(density) { GRID_REORDER_MAX_SCROLL_SPEED.toPx() }
    val hysteresisPx = with(density) { GRID_REORDER_HYSTERESIS.toPx() }
    val isDragging = state.draggingKey != null
    LaunchedEffect(state, isDragging, edgeBandPx, maxSpeedPxPerSecond, hysteresisPx) {
        if (!isDragging) return@LaunchedEffect
        var previousFrame = 0L
        while (isActive && state.draggingKey != null) {
            val frame = withFrameNanos { it }
            if (!state.cancelIfSourceChanged()) break
            val elapsedSeconds = when (previousFrame) {
                0L -> 0f
                else -> ((frame - previousFrame) / 1_000_000_000f)
                    .coerceAtMost(GRID_MAX_FRAME_STEP_SECONDS)
            }
            previousFrame = frame

            val velocity = state.autoScrollVelocity(edgeBandPx, maxSpeedPxPerSecond)
            if (state.canAutoScroll(velocity) && elapsedSeconds > 0f) {
                gridState.scrollBy(velocity * elapsedSeconds)
            }

            // The sole target calculation for the frame; pointer events only record the
            // latest coordinate this loop reads.
            state.updateTarget(hysteresisPx)
        }
    }
    return state
}

/** Gesture owner and overlay for a reorderable grid. Mirrors [ReorderableContainer]. */
@Composable
fun ReorderableGridContainer(
    state: ReorderableGridState,
    modifier: Modifier = Modifier,
    draggedItem: @Composable (index: Int) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val markerWidth = GRID_REORDER_MARKER_WIDTH
    val markerWidthPx = with(density) { markerWidth.toPx() }
    val markerOrigin = state.insertionOrigin(markerWidthPx)
    val animatedMarkerX by animateFloatAsState(
        targetValue = markerOrigin?.x ?: 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "grid-reorder-marker-x",
    )
    val animatedMarkerY by animateFloatAsState(
        targetValue = markerOrigin?.y ?: 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "grid-reorder-marker-y",
    )
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(state) {
                detectGridReorderGestures(state)
            },
    ) {
        content()

        if (state.targetIndex != null && state.draggedHeightPx > 0) {
            Box(
                modifier = Modifier
                    .zIndex(1f)
                    .offset {
                        IntOffset(
                            animatedMarkerX.roundToInt(),
                            animatedMarkerY.roundToInt(),
                        )
                    }
                    .width(markerWidth)
                    .height(with(density) { state.draggedHeightPx.toDp() })
                    .padding(vertical = 8.dp)
                    .graphicsLayer {
                        alpha = if (markerOrigin == null) 0f else 1f
                    }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
            )
        }

        val draggedIndex = state.draggedIndex
        if (draggedIndex != null && state.draggedHeightPx > 0) {
            Surface(
                modifier = Modifier
                    .zIndex(2f)
                    .offset {
                        val origin = state.overlayOrigin()
                        IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
                    }
                    .size(
                        width = with(density) { state.draggedWidthPx.toDp() },
                        height = with(density) { state.draggedHeightPx.toDp() },
                    ),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                draggedItem(draggedIndex)
            }
        }
    }
}

/** Hides the source cell while the parent overlay renders its opaque copy. */
@Composable
fun reorderableGridItemModifier(
    state: ReorderableGridState,
    key: Any,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return Modifier
    return Modifier.graphicsLayer {
        alpha = if (state.draggingKey == key) 0f else 1f
    }
}

private val GRID_REORDER_EDGE_BAND = 56.dp
private val GRID_REORDER_MAX_SCROLL_SPEED = 420.dp
private val GRID_REORDER_HYSTERESIS = 16.dp
private val GRID_REORDER_MARKER_WIDTH = 3.dp
private const val GRID_MAX_FRAME_STEP_SECONDS = 0.064f
