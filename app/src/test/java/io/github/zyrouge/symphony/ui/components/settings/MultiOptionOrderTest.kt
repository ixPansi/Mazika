package io.github.zyrouge.symphony.ui.components.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiOptionOrderTest {
    @Test
    fun orderOnlyChange_isModified() {
        val original = listOf("songs", "albums", "artists")

        assertTrue(
            orderedSelectionChanged(
                listOf("albums", "songs", "artists"),
                original,
            )
        )
    }

    @Test
    fun unchangedOrderAndSelection_isNotModified() {
        val original = listOf("songs", "albums", "artists")

        assertFalse(orderedSelectionChanged(original, original))
    }

    @Test
    fun listEqualityDetectsOrderChanges() {
        val saved = listOf("albums", "songs", "artists")

        assertEquals(listOf("albums", "songs", "artists"), saved)
    }
}
