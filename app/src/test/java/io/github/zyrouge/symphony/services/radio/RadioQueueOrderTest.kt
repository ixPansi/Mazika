package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RadioQueueOrderTest {
    @Test
    fun movingAnotherItem_keepsExactPlayingDuplicate() {
        val queue = mutableListOf("a", "b", "a", "c")

        val playingIndex = indexAfterMove(index = 2, from = 1, to = 3)
        queue.add(3, queue.removeAt(1))

        assertEquals(listOf("a", "a", "c", "b"), queue)
        assertEquals(1, playingIndex)
        assertEquals(0, queue.indexOf(queue[playingIndex]))
    }

    @Test
    fun movingPlayingOccurrence_followsItToDestination() {
        assertEquals(0, indexAfterMove(index = 2, from = 2, to = 0))
        assertEquals(3, indexAfterMove(index = 1, from = 1, to = 3))
    }

    @Test
    fun moveAcrossPlayingOccurrence_shiftsItsIndex() {
        assertEquals(1, indexAfterMove(index = 2, from = 0, to = 3))
        assertEquals(2, indexAfterMove(index = 1, from = 3, to = 0))
    }

    @Test
    fun selectedRows_followTheSameOccurrencesAfterMove() {
        val selected = listOf(0, 2).map { indexAfterMove(it, from = 0, to = 3) }

        assertEquals(listOf(3, 1), selected)
    }

    @Test
    fun failedSongRemoval_continuesAtNextAvailableIndex() {
        assertEquals(0, replacementIndexAfterRemoval(removedIndex = 0, remainingSize = 1))
        assertEquals(1, replacementIndexAfterRemoval(removedIndex = 1, remainingSize = 3))
        assertEquals(2, replacementIndexAfterRemoval(removedIndex = 3, remainingSize = 3))
        assertEquals(null, replacementIndexAfterRemoval(removedIndex = 0, remainingSize = 0))
    }

    @Test
    fun removingMultipleRows_adjustsCurrentIndexWithoutDescendingOffset() {
        assertEquals(
            0,
            indexAfterRemovals(index = 2, removedIndices = listOf(1, 0), remainingSize = 2),
        )
        assertEquals(
            1,
            indexAfterRemovals(index = 2, removedIndices = listOf(2, 0), remainingSize = 2),
        )
        assertEquals(
            -1,
            indexAfterRemovals(index = 0, removedIndices = listOf(0), remainingSize = 0),
        )
    }

    @Test
    fun inFlightPlayTarget_rejectsAnyStructuralQueueChange() {
        assertEquals(
            0,
            resolveUnchangedPlayTarget(
                values = listOf("a", "b", "a"),
                value = "a",
                preferredIndex = 0,
                targetVersion = 4,
                currentVersion = 4,
            ),
        )
        assertEquals(
            null,
            resolveUnchangedPlayTarget(
                values = listOf("a", "a", "b"),
                value = "a",
                preferredIndex = 2,
                targetVersion = 4,
                currentVersion = 5,
            ),
        )
        assertEquals(
            null,
            resolveUnchangedPlayTarget(
                values = listOf("b", "c"),
                value = "a",
                preferredIndex = 0,
                targetVersion = 4,
                currentVersion = 4,
            ),
        )
    }
}
