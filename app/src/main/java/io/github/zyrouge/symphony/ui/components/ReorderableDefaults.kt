package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * MAZIKA: the shared feel of every drag-to-reorder surface in the app.
 *
 * [Reorderable] drives lists (songs, the queue, playlists in list layout) and
 * [ReorderableGrid] drives grids. They stay separate because a grid needs 2D targeting that
 * would complicate the list engine for no gain - but a drag has to *feel* the same wherever
 * the user does it, and two copies of these numbers is how that quietly stops being true.
 * Anything that shapes the feel belongs here; anything about geometry stays with its engine.
 */
internal object ReorderableDefaults {
    /** How far from an edge the drag starts scrolling the list. */
    val EdgeBand = 56.dp

    /** Peak edge-scroll speed, in dp per second - frame-rate independent by design. */
    val MaxScrollSpeed = 420.dp

    /** Thickness of the bar marking where the item will land. */
    val MarkerThickness = 3.dp

    /** Opacity of that bar. */
    const val MARKER_ALPHA = 0.65f

    /** Lift of the copy that follows the finger. */
    val DraggedElevation = 8.dp

    /** A drag never advances more than this much time in one frame. */
    const val MAX_FRAME_STEP_SECONDS = 0.064f

    /**
     * Items sliding aside to open a gap.
     *
     * Soft enough that a row of cells reflowing does not snap, firm enough that the gap is
     * already there when the finger arrives.
     */
    fun <T> displacementSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** The insertion marker moving between slots - slightly firmer, it should lead the eye. */
    fun <T> markerSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Visibility of an item while its opaque copy is being dragged. */
    fun alphaFor(isDragged: Boolean) = if (isDragged) 0f else 1f

    val NoDisplacement = Offset.Zero
}
