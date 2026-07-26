package io.github.zyrouge.symphony.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReorderLogicTest {
    @Test
    fun lazyIndexMapping_excludesHeadersAndFollowingSections() {
        assertNull(dataIndexForLazyIndex(lazyIndex = 1, firstItemIndex = 2, itemCount = 3))
        assertEquals(0, dataIndexForLazyIndex(lazyIndex = 2, firstItemIndex = 2, itemCount = 3))
        assertEquals(2, dataIndexForLazyIndex(lazyIndex = 4, firstItemIndex = 2, itemCount = 3))
        assertNull(dataIndexForLazyIndex(lazyIndex = 5, firstItemIndex = 2, itemCount = 3))
    }

    @Test
    fun targetCalculation_usesVariableRowCenters() {
        val rows = listOf(
            ReorderItemBounds(index = 0, start = 0f, end = 80f),
            ReorderItemBounds(index = 1, start = 80f, end = 200f),
            ReorderItemBounds(index = 2, start = 200f, end = 260f),
        )

        assertEquals(
            1,
            calculateReorderTarget(
                currentTarget = 0,
                draggedCenter = 130f,
                items = rows,
                hysteresisPx = 10f,
            ),
        )
        assertEquals(
            2,
            calculateReorderTarget(
                currentTarget = 1,
                draggedCenter = 235f,
                items = rows,
                hysteresisPx = 10f,
            ),
        )
    }

    @Test
    fun targetCalculation_hasHysteresisOnBothSidesOfBoundary() {
        val rows = listOf(
            ReorderItemBounds(index = 0, start = 0f, end = 100f),
            ReorderItemBounds(index = 1, start = 100f, end = 200f),
        )

        assertEquals(0, calculateReorderTarget(0, 102f, rows, hysteresisPx = 10f))
        assertEquals(1, calculateReorderTarget(0, 106f, rows, hysteresisPx = 10f))
        assertEquals(1, calculateReorderTarget(1, 98f, rows, hysteresisPx = 10f))
        assertEquals(0, calculateReorderTarget(1, 94f, rows, hysteresisPx = 10f))
    }

    @Test
    fun edgeScrollVelocity_isSymmetricAndStopsInTheMiddle() {
        assertEquals(
            -100f,
            calculateAutoScrollVelocity(10f, 0f, 100f, 20f, 200f),
        )
        assertEquals(
            100f,
            calculateAutoScrollVelocity(90f, 0f, 100f, 20f, 200f),
        )
        assertEquals(
            0f,
            calculateAutoScrollVelocity(50f, 0f, 100f, 20f, 200f),
        )
    }

    @Test
    fun previewOffsets_shiftRowsIntoTheDraggedGap() {
        assertEquals(-80, calculateReorderPreviewOffset(1, 0, 2, 80))
        assertEquals(-80, calculateReorderPreviewOffset(2, 0, 2, 80))
        assertEquals(80, calculateReorderPreviewOffset(0, 2, 0, 80))
        assertEquals(80, calculateReorderPreviewOffset(1, 2, 0, 80))
        assertEquals(0, calculateReorderPreviewOffset(3, 0, 2, 80))
        assertEquals(0, calculateReorderPreviewOffset(1, 1, 1, 80))
    }

    @Test
    fun normalRelease_yieldsOneMoveOnly() {
        val keys = listOf<Any>("a#0", "b#0", "c#0")
        val session = ReorderSession()
        session.start(index = 0, keys = keys, version = "source-1")
        session.updateTarget(2)

        assertEquals(
            ReorderMove(from = 0, to = 2),
            session.finish(keys = keys, version = "source-1"),
        )
        assertNull(session.finish(keys = keys, version = "source-1"))
        assertFalse(session.isActive)
    }

    @Test
    fun cancellationAndSourceReplacement_doNotYieldMoves() {
        val keys = listOf<Any>("a#0", "b#0", "c#0")
        val cancelled = ReorderSession().apply {
            start(index = 0, keys = keys, version = "source-1")
            updateTarget(2)
            cancel()
        }
        assertNull(cancelled.finish(keys = keys, version = "source-1"))

        val replacedRows = ReorderSession().apply {
            start(index = 0, keys = keys, version = "source-1")
            updateTarget(2)
        }
        assertNull(
            replacedRows.finish(
                keys = listOf("a#0", "new#0", "c#0"),
                version = "source-1",
            )
        )

        val replacedOwner = ReorderSession().apply {
            start(index = 0, keys = keys, version = "source-1")
            updateTarget(2)
        }
        assertNull(replacedOwner.finish(keys = keys, version = "source-2"))
    }

    @Test
    fun atomicMove_preservesDuplicateOccurrenceIdentity() {
        val entries = listOf("a", "b", "a").toReorderableEntries()
        val moved = entries.movedItem(from = 0, to = 2)

        assertEquals(entries[0].uid, moved[2].uid)
        assertEquals(listOf("b", "a", "a"), moved.map { it.value })
    }
}
