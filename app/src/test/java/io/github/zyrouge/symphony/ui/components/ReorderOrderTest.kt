package io.github.zyrouge.symphony.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ReorderOrderTest {
    @Test
    fun reverseCustomDisplayOrder_isConvertedBackToStoredOrder() {
        val reorderedDisplay = listOf("b", "c", "a")

        assertEquals(
            listOf("a", "c", "b"),
            reorderedDisplay.toStoredCustomOrder(reverse = true),
        )
    }

    @Test
    fun forwardCustomDisplayOrder_isStoredAsDisplayed() {
        val reorderedDisplay = listOf("b", "a", "c")

        assertEquals(
            reorderedDisplay,
            reorderedDisplay.toStoredCustomOrder(reverse = false),
        )
    }

    @Test
    fun duplicateValues_receiveDistinctStableKeys() {
        val entries = listOf("a", "b", "a").toReorderableEntries()

        assertNotEquals(entries[0].uid, entries[2].uid)
        assertEquals("a", entries[0].value)
        assertEquals("a", entries[2].value)
        assertEquals(0, entries[0].sourceIndex)
        assertEquals(2, entries[2].sourceIndex)
    }

    @Test
    fun reverseDisplayOrder_mapsDuplicatesToTheirExactSourceRows() {
        val entries = listOf("a", "b", "a").toReorderableEntriesInOrder(
            orderedValues = listOf("a", "b", "a"),
            reverseOccurrences = true,
        )

        assertEquals(listOf(2, 1, 0), entries.map { it.sourceIndex })
    }
}
