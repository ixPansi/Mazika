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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

/** A unique identity for an occurrence in a list that may contain duplicate values. */
data class ReorderableEntry<T>(val uid: String, val value: T, val sourceIndex: Int)

fun <T> List<T>.toReorderableEntries(): List<ReorderableEntry<T>> {
    val seen = HashMap<T, Int>(size)
    return mapIndexed { index, value ->
        val occurrence = seen.getOrDefault(value, 0)
        seen[value] = occurrence + 1
        ReorderableEntry("$value#$occurrence", value, sourceIndex = index)
    }
}

/** Associates a sorted display order back to the exact occurrences in its source list. */
internal fun <T> List<T>.toReorderableEntriesInOrder(
    orderedValues: List<T>,
    reverseOccurrences: Boolean,
): List<ReorderableEntry<T>> {
    val entriesByValue = HashMap<T, ArrayDeque<ReorderableEntry<T>>>(size)
    toReorderableEntries().forEach { entry ->
        entriesByValue.getOrPut(entry.value) { ArrayDeque() }.addLast(entry)
    }
    return orderedValues.map { value ->
        val entries = checkNotNull(entriesByValue[value]?.takeIf { it.isNotEmpty() })
        if (reverseOccurrences) entries.removeLast() else entries.removeFirst()
    }
}

/** Returns one atomic remove-and-insert move without changing this list. */
internal fun <T> List<T>.movedItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return toList()
    return toMutableList().apply {
        add(to, removeAt(from))
    }
}

internal data class ReorderMove(val from: Int, val to: Int)

/**
 * Pure lifetime model for one drag. Its source snapshot is never changed during the
 * gesture, and [finish] can yield at most one move.
 */
internal class ReorderSession {
    private var sourceKeys: List<Any> = emptyList()
    private var sourceVersion: Any? = null

    var sourceIndex: Int? = null
        private set
    var targetIndex: Int? = null
        private set

    val isActive: Boolean
        get() = sourceIndex != null

    fun start(index: Int, keys: List<Any>, version: Any?) {
        require(index in keys.indices)
        sourceKeys = keys.toList()
        sourceVersion = version
        sourceIndex = index
        targetIndex = index
    }

    fun updateTarget(index: Int) {
        if (isActive && index in sourceKeys.indices) targetIndex = index
    }

    fun matches(keys: List<Any>, version: Any?): Boolean =
        isActive && sourceKeys == keys && sourceVersion == version

    fun finish(keys: List<Any>, version: Any?): ReorderMove? {
        val from = sourceIndex
        val to = targetIndex
        val move = if (from != null && to != null && from != to && matches(keys, version)) {
            ReorderMove(from, to)
        } else {
            null
        }
        cancel()
        return move
    }

    fun cancel() {
        sourceKeys = emptyList()
        sourceVersion = null
        sourceIndex = null
        targetIndex = null
    }
}

internal data class ReorderItemBounds(
    val index: Int,
    val start: Float,
    val end: Float,
) {
    val center: Float
        get() = (start + end) / 2f
}

/** Maps lazy-list indices into a bounded data region, excluding headers and footers. */
internal fun dataIndexForLazyIndex(
    lazyIndex: Int,
    firstItemIndex: Int,
    itemCount: Int,
): Int? = (lazyIndex - firstItemIndex).takeIf { it in 0 until itemCount }

/**
 * Picks the row center nearest the dragged center. The current target wins a dead band
 * around the boundary, preventing adjacent rows from flapping when layout and touch
 * samples land on opposite sides of the same pixel.
 */
internal fun calculateReorderTarget(
    currentTarget: Int,
    draggedCenter: Float,
    items: List<ReorderItemBounds>,
    hysteresisPx: Float,
): Int {
    val candidate = items.minWithOrNull(
        compareBy<ReorderItemBounds> { abs(draggedCenter - it.center) }
            .thenBy { if (it.index == currentTarget) 0 else 1 }
            .thenBy { it.index }
    ) ?: return currentTarget
    if (candidate.index == currentTarget) return currentTarget

    val current = items.firstOrNull { it.index == currentTarget } ?: return candidate.index
    val currentDistance = abs(draggedCenter - current.center)
    val candidateDistance = abs(draggedCenter - candidate.center)
    val deadBand = hysteresisPx.coerceAtLeast(0f)
        .coerceAtMost(abs(candidate.center - current.center) / 2f)
    return if (candidateDistance + deadBand >= currentDistance) {
        currentTarget
    } else {
        candidate.index
    }
}

/** Visual displacement for rows that make room for the dragged item. */
internal fun calculateReorderPreviewOffset(
    index: Int,
    sourceIndex: Int?,
    targetIndex: Int?,
    draggedSizePx: Int,
): Int {
    if (sourceIndex == null || targetIndex == null || draggedSizePx <= 0) return 0
    return when {
        sourceIndex < targetIndex && index in (sourceIndex + 1)..targetIndex -> -draggedSizePx
        targetIndex < sourceIndex && index in targetIndex until sourceIndex -> draggedSizePx
        else -> 0
    }
}

/** Symmetric, frame-rate-independent edge-scroll velocity. */
internal fun calculateAutoScrollVelocity(
    pointer: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeBandPx: Float,
    maxSpeedPxPerSecond: Float,
): Float {
    if (viewportEnd <= viewportStart || edgeBandPx <= 0f || maxSpeedPxPerSecond <= 0f) {
        return 0f
    }
    val band = edgeBandPx.coerceAtMost((viewportEnd - viewportStart) / 2f)
    if (band <= 0f) return 0f
    val towardStart = ((viewportStart + band - pointer) / band).coerceIn(0f, 1f)
    val towardEnd = ((pointer - (viewportEnd - band)) / band).coerceIn(0f, 1f)
    return when {
        towardStart > towardEnd -> -maxSpeedPxPerSecond * towardStart
        towardEnd > 0f -> maxSpeedPxPerSecond * towardEnd
        else -> 0f
    }
}

/**
 * Waits without consuming while taps and normal scrolling are still possible. Once a
 * valid row reaches long-press, events are consumed in the Initial pass so the child
 * LazyColumn cannot steal a fast first drag movement.
 */
private suspend fun PointerInputScope.detectReorderGestures(state: ReorderableState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!state.startDrag(longPress.position.y)) return@awaitEachGesture

        var pointerId = longPress.id
        var completed = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                var change = event.changes.firstOrNull { it.id == pointerId }
                    ?: break
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
                if (state.draggingKey != null) state.dragTo(change.position.y)
            }
        } finally {
            if (!completed) state.cancelDrag()
        }
    }
}

/**
 * Parent-owned reorder state for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * The pointer detector and dragged-row overlay live in [ReorderableContainer], not in
 * a lazy item. A dragged item's slot may therefore scroll out and be disposed without
 * cancelling the gesture or removing its visual. The caller's list remains unchanged
 * until a normal release emits one [onMove].
 */
class ReorderableState internal constructor(
    internal val listState: LazyListState,
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
    internal var draggedSizePx by mutableIntStateOf(0)
        private set

    private var pointerY by mutableFloatStateOf(0f)
    private var pointerOffsetInItem = 0f

    private fun visibleReorderableItems(keys: List<Any>): List<Pair<LazyListItemInfo, Int>> {
        val first = firstItemIndex()
        return listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val dataIndex = dataIndexForLazyIndex(item.index, first, keys.size)
                ?: return@mapNotNull null
            if (item.size <= 0 || item.key != keys[dataIndex]) return@mapNotNull null
            item to dataIndex
        }
    }

    internal fun startDrag(y: Float): Boolean {
        val keys = itemKeys()
        val hit = visibleReorderableItems(keys).firstOrNull { (item, _) ->
            y >= item.offset && y < item.offset + item.size
        } ?: return false
        val (item, index) = hit

        session.start(index, keys, sourceVersion())
        pointerY = y
        pointerOffsetInItem = (y - item.offset).coerceIn(0f, item.size.toFloat())
        draggedSizePx = item.size
        draggedIndex = index
        targetIndex = index
        draggingKey = item.key
        return true
    }

    internal fun dragTo(y: Float) {
        if (session.isActive) pointerY = y
    }

    internal fun updateTarget(hysteresisPx: Float) {
        if (!session.isActive) return
        val current = session.targetIndex ?: return
        val keys = itemKeys()
        val bounds = visibleReorderableItems(keys).map { (item, index) ->
            ReorderItemBounds(
                index = index,
                start = item.offset.toFloat(),
                end = (item.offset + item.size).toFloat(),
            )
        }
        val target = calculateReorderTarget(
            currentTarget = current,
            draggedCenter = overlayTop() + draggedSizePx / 2f,
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
        draggedSizePx = 0
        pointerOffsetInItem = 0f
    }

    internal fun overlayTop(): Float {
        val info = listState.layoutInfo
        val viewportStart = info.viewportStartOffset.coerceAtLeast(0).toFloat()
        val viewportEnd = info.viewportEndOffset.toFloat()
        val size = draggedSizePx.toFloat()
        val maximum = viewportEnd - size
        if (size <= 0f || maximum < viewportStart) return viewportStart
        return (pointerY - pointerOffsetInItem).coerceIn(viewportStart, maximum)
    }

    internal fun insertionTop(markerHeightPx: Float): Float? {
        val source = session.sourceIndex ?: return null
        val target = session.targetIndex ?: return null
        val first = firstItemIndex()
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == first + target
        } ?: return null
        val insertion = when {
            target > source -> item.offset + item.size
            else -> item.offset
        }.toFloat() - markerHeightPx / 2f
        val viewportStart = listState.layoutInfo.viewportStartOffset.coerceAtLeast(0).toFloat()
        val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
        return insertion.coerceIn(viewportStart, (viewportEnd - markerHeightPx).coerceAtLeast(viewportStart))
    }

    internal fun previewOffsetFor(key: Any): Int = calculateReorderPreviewOffset(
        index = itemKeys().indexOf(key),
        sourceIndex = draggedIndex,
        targetIndex = targetIndex,
        draggedSizePx = draggedSizePx,
    )

    internal fun autoScrollVelocity(
        edgeBandPx: Float,
        maxSpeedPxPerSecond: Float,
    ): Float {
        val info = listState.layoutInfo
        return calculateAutoScrollVelocity(
            pointer = pointerY,
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
        // Headers and following sections may still make the LazyColumn scrollable. Stop
        // once that boundary is the selected target so those sections cannot carry the
        // dragged region entirely out of the viewport. A merely peeking boundary may
        // still need to move far enough inward for a variable-height row to be targeted.
        if (session.targetIndex == boundaryTarget &&
            listState.layoutInfo.visibleItemsInfo.any { it.index == boundaryIndex }
        ) {
            return false
        }
        return if (velocity < 0f) listState.canScrollBackward else listState.canScrollForward
    }
}

/**
 * Creates state for a reorderable region. [itemKeys] must match the keys emitted by the
 * lazy rows and must contain only the bounded reorderable section. [firstItemIndex]
 * identifies data row zero when headers precede it. [sourceVersion] can include sort or
 * owner state not represented by the row keys; changing either it or [itemKeys] cancels
 * an active drag without calling [onMove].
 */
@Composable
fun rememberReorderableState(
    listState: LazyListState,
    itemKeys: () -> List<Any>,
    firstItemIndex: () -> Int = { 0 },
    sourceVersion: () -> Any? = { Unit },
    onMove: (from: Int, to: Int) -> Unit,
): ReorderableState {
    val currentKeys by rememberUpdatedState(itemKeys)
    val currentFirst by rememberUpdatedState(firstItemIndex)
    val currentVersion by rememberUpdatedState(sourceVersion)
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(listState) {
        ReorderableState(
            listState = listState,
            itemKeys = { currentKeys() },
            firstItemIndex = { currentFirst() },
            sourceVersion = { currentVersion() },
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }

    SideEffect { state.cancelIfSourceChanged() }

    val density = LocalDensity.current
    val edgeBandPx = with(density) { REORDER_EDGE_BAND.toPx() }
    val maxSpeedPxPerSecond = with(density) { REORDER_MAX_SCROLL_SPEED.toPx() }
    val hysteresisPx = with(density) { REORDER_HYSTERESIS.toPx() }
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
                    .coerceAtMost(MAX_FRAME_STEP_SECONDS)
            }
            previousFrame = frame

            val velocity = state.autoScrollVelocity(edgeBandPx, maxSpeedPxPerSecond)
            if (state.canAutoScroll(velocity) && elapsedSeconds > 0f) {
                listState.scrollBy(velocity * elapsedSeconds)
            }

            // This is the sole target calculation for the frame. Pointer events only
            // update the latest coordinate sampled by this loop.
            state.updateTarget(hysteresisPx)
        }
    }
    return state
}

/**
 * Stable gesture owner and visual overlay. [content] normally emits the LazyColumn;
 * [draggedItem] renders the source row from the unchanged backing order.
 */
@Composable
fun ReorderableContainer(
    state: ReorderableState,
    modifier: Modifier = Modifier,
    draggedItem: @Composable (index: Int) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val markerHeight = REORDER_MARKER_HEIGHT
    val markerHeightPx = with(density) { markerHeight.toPx() }
    val markerTop = state.insertionTop(markerHeightPx)
    val animatedMarkerTop by animateFloatAsState(
        targetValue = markerTop ?: 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "reorder-marker",
    )
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(state) {
                detectReorderGestures(state)
            },
    ) {
        content()

        if (state.targetIndex != null) {
            Box(
                modifier = Modifier
                    .zIndex(1f)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = animatedMarkerTop.roundToInt(),
                        )
                    }
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(markerHeight)
                    .graphicsLayer {
                        alpha = if (markerTop == null) 0f else 1f
                    }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
            )
        }

        val draggedIndex = state.draggedIndex
        if (draggedIndex != null && state.draggedSizePx > 0) {
            Surface(
                modifier = Modifier
                    .zIndex(2f)
                    .offset { IntOffset(0, state.overlayTop().roundToInt()) }
                    .fillMaxWidth()
                    .height(with(density) { state.draggedSizePx.toDp() }),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                draggedItem(draggedIndex)
            }
        }
    }
}

/** Hides the source slot while its opaque copy is rendered by the parent overlay. */
@Composable
fun reorderableItemModifier(
    state: ReorderableState,
    key: Any,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return Modifier
    val previewOffset by animateFloatAsState(
        targetValue = state.previewOffsetFor(key).toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "reorder-row-offset",
    )
    return Modifier.graphicsLayer {
        translationY = previewOffset
        alpha = if (state.draggingKey == key) 0f else 1f
    }
}

private val REORDER_EDGE_BAND = 56.dp
private val REORDER_MAX_SCROLL_SPEED = 420.dp
private val REORDER_HYSTERESIS = 10.dp
private val REORDER_MARKER_HEIGHT = 3.dp
private const val MAX_FRAME_STEP_SECONDS = 0.064f
