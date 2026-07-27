package io.github.zyrouge.symphony.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReorderableGridTest {
    /** A 2-column grid of 100x100 cells, laid out the way LazyVerticalGrid would. */
    private fun grid(count: Int, columns: Int = 2) = (0 until count).map { index ->
        ReorderGridItemBounds(
            index = index,
            rect = Rect(
                offset = Offset(
                    x = (index % columns) * CELL,
                    y = (index / columns) * CELL,
                ),
                size = Size(CELL, CELL),
            ),
        )
    }

    private fun centerOf(index: Int, columns: Int = 2) = Offset(
        x = (index % columns) * CELL + CELL / 2,
        y = (index / columns) * CELL + CELL / 2,
    )

    @Test
    fun picksTheCellTheDraggedCenterSitsOn() {
        val target = calculateReorderTargetGrid(
            currentTarget = 0,
            draggedCenter = centerOf(3),
            items = grid(6),
            hysteresisPx = 0f,
        )

        assertEquals(3, target)
    }

    @Test
    fun movesSidewaysWithinARow() {
        // Cell 0 and cell 1 are in the same row - the choice is horizontal, which the
        // vertical-only list engine could not express at all.
        val target = calculateReorderTargetGrid(
            currentTarget = 0,
            draggedCenter = centerOf(1),
            items = grid(6),
            hysteresisPx = 0f,
        )

        assertEquals(1, target)
    }

    @Test
    fun currentTargetHoldsInsideTheDeadBand() {
        // Nudged a few pixels towards cell 1, but not past the hysteresis threshold.
        val nudged = centerOf(0) + Offset(4f, 0f)

        val target = calculateReorderTargetGrid(
            currentTarget = 0,
            draggedCenter = nudged,
            items = grid(6),
            hysteresisPx = 40f,
        )

        assertEquals(0, target)
    }

    @Test
    fun deadBandIsCrossedByACommittedMove() {
        val target = calculateReorderTargetGrid(
            currentTarget = 0,
            draggedCenter = centerOf(1) - Offset(4f, 0f),
            items = grid(6),
            hysteresisPx = 40f,
        )

        assertEquals(1, target)
    }

    @Test
    fun diagonalNeighbourLosesToTheCellDirectlyBelow() {
        // Dragging straight down from cell 0: cell 2 is CELL away, cell 3 is CELL*sqrt(2).
        // A naive vertical-only comparison would call them equal, since both start the
        // same row down.
        val target = calculateReorderTargetGrid(
            currentTarget = 0,
            draggedCenter = centerOf(2),
            items = grid(6),
            hysteresisPx = 0f,
        )

        assertEquals(2, target)
    }

    @Test
    fun keepsTheCurrentTargetWhenNothingIsVisible() {
        // Every cell scrolled out mid-drag: the target must not jump somewhere arbitrary.
        val target = calculateReorderTargetGrid(
            currentTarget = 4,
            draggedCenter = Offset.Zero,
            items = emptyList(),
            hysteresisPx = 0f,
        )

        assertEquals(4, target)
    }

    @Test
    fun adoptsACandidateWhenTheCurrentTargetScrolledAway() {
        val visible = grid(6).filter { it.index >= 2 }

        val target = calculateReorderTargetGrid(
            currentTarget = 0,
            draggedCenter = centerOf(3),
            items = visible,
            hysteresisPx = 40f,
        )

        assertEquals(3, target)
    }

    @Test
    fun draggingForward_slidesTheSpanBackOneSlot() {
        // Holding item 1 over slot 4: everything in 2..4 moves back to make room.
        assertEquals(null, calculateReorderPreviewSlot(0, sourceIndex = 1, targetIndex = 4))
        assertEquals(null, calculateReorderPreviewSlot(1, sourceIndex = 1, targetIndex = 4))
        assertEquals(1, calculateReorderPreviewSlot(2, sourceIndex = 1, targetIndex = 4))
        assertEquals(3, calculateReorderPreviewSlot(4, sourceIndex = 1, targetIndex = 4))
        assertEquals(null, calculateReorderPreviewSlot(5, sourceIndex = 1, targetIndex = 4))
    }

    @Test
    fun draggingBackwards_slidesTheSpanForwardOneSlot() {
        assertEquals(null, calculateReorderPreviewSlot(1, sourceIndex = 4, targetIndex = 2))
        assertEquals(3, calculateReorderPreviewSlot(2, sourceIndex = 4, targetIndex = 2))
        assertEquals(4, calculateReorderPreviewSlot(3, sourceIndex = 4, targetIndex = 2))
        assertEquals(null, calculateReorderPreviewSlot(4, sourceIndex = 4, targetIndex = 2))
    }

    @Test
    fun nothingMovesWithoutADrag() {
        assertEquals(null, calculateReorderPreviewSlot(2, sourceIndex = null, targetIndex = 4))
        assertEquals(null, calculateReorderPreviewSlot(2, sourceIndex = 1, targetIndex = null))
    }

    @Test
    fun nothingMovesWhenTheTargetIsTheSource() {
        (0..5).forEach { index ->
            assertEquals(null, calculateReorderPreviewSlot(index, sourceIndex = 3, targetIndex = 3))
        }
    }

    @Test
    fun theGridMovesExactlyTheItemsTheListMoves() {
        // The whole point of sharing the span conditions: a drag has to displace the same
        // items whichever layout the user is looking at. Only the distance differs.
        val size = 7
        for (source in 0 until size) {
            for (target in 0 until size) {
                for (index in 0 until size) {
                    val listMoves = calculateReorderPreviewOffset(
                        index = index,
                        sourceIndex = source,
                        targetIndex = target,
                        draggedSizePx = 100,
                    ) != 0
                    val gridMoves = calculateReorderPreviewSlot(
                        index = index,
                        sourceIndex = source,
                        targetIndex = target,
                    ) != null

                    assertEquals(
                        listMoves,
                        gridMoves,
                        "index=$index source=$source target=$target",
                    )
                }
            }
        }
    }

    @Test
    fun theDestinationSlotIsAlwaysTowardsTheSource() {
        // A cell must never be told to slide past the dragged item's own slot, which would
        // open the gap in the wrong place.
        val size = 7
        for (source in 0 until size) {
            for (target in 0 until size) {
                for (index in 0 until size) {
                    val slot = calculateReorderPreviewSlot(index, source, target) ?: continue
                    assertTrue(slot in 0 until size, "slot $slot out of bounds")
                    assertTrue(
                        if (source < target) slot < index else slot > index,
                        "index=$index source=$source target=$target slot=$slot",
                    )
                }
            }
        }
    }

    @Test
    fun storedOrderIsFlippedOnlyForAReversedSort() {
        val displayed = listOf("a", "b", "c")

        assertEquals(displayed, displayed.toStoredCustomOrder(reverse = false))
        assertEquals(listOf("c", "b", "a"), displayed.toStoredCustomOrder(reverse = true))
    }

    companion object {
        private const val CELL = 100f
    }
}
