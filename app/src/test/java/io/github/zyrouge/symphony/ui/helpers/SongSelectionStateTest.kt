package io.github.zyrouge.symphony.ui.helpers

import io.github.zyrouge.symphony.ui.components.toReorderableEntries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MAZIKA multi-select tests.
 *
 * The interesting case throughout is a list holding the same song more than once, which
 * a playlist is allowed to do. Selection keys on the reorderable uid rather than the song
 * id precisely so the two copies stay distinguishable.
 */
class SongSelectionStateTest {
    private val duplicated = listOf("a", "b", "a", "c").toReorderableEntries()

    @Test
    fun startsInactive_andActivatesOnFirstPick() {
        val selection = SongSelectionState()
        assertFalse(selection.isActive)
        assertEquals(0, selection.count)

        selection.start(duplicated[0].uid)
        assertTrue(selection.isActive)
        assertEquals(1, selection.count)
    }

    @Test
    fun selectingOneCopy_doesNotSelectTheOther() {
        val selection = SongSelectionState()
        // The second occurrence of "a" - uid "a#1", source index 2.
        selection.start(duplicated[2].uid)

        assertEquals(listOf("a"), selection.songIds(duplicated))
        assertEquals(listOf(2), selection.sourceIndices(duplicated))
        assertFalse(selection.contains(duplicated[0].uid))
    }

    @Test
    fun bothCopiesSelected_yieldTheSongTwice() {
        val selection = SongSelectionState()
        selection.start(duplicated[0].uid)
        selection.toggle(duplicated[2].uid)

        assertEquals(listOf("a", "a"), selection.songIds(duplicated))
        assertEquals(listOf(0, 2), selection.sourceIndices(duplicated))
    }

    @Test
    fun toggle_addsThenRemoves() {
        val selection = SongSelectionState()
        val uid = duplicated[1].uid
        selection.toggle(uid)
        assertTrue(selection.contains(uid))
        selection.toggle(uid)
        assertFalse(selection.contains(uid))
    }

    @Test
    fun toggleAll_selectsEverythingThenClears() {
        val selection = SongSelectionState()
        val allUids = duplicated.map { it.uid }

        selection.toggleAll(allUids)
        assertEquals(4, selection.count)
        assertEquals(listOf("a", "b", "a", "c"), selection.songIds(duplicated))

        // Already all selected, so the same gesture clears.
        selection.toggleAll(allUids)
        assertEquals(0, selection.count)
    }

    @Test
    fun clear_leavesSelectionMode() {
        val selection = SongSelectionState()
        selection.start(duplicated[0].uid)
        selection.clear()
        assertFalse(selection.isActive)
        assertEquals(0, selection.count)
    }

    @Test
    fun startEmpty_activatesWithoutPickingAnything() {
        val selection = SongSelectionState()
        selection.startEmpty()
        assertTrue(selection.isActive)
        assertEquals(0, selection.count)
    }

    @Test
    fun retain_dropsRowsThatAreGone() {
        val selection = SongSelectionState()
        selection.start(duplicated[0].uid)
        selection.toggle(duplicated[3].uid)
        assertEquals(2, selection.count)

        // "c" removed from the list; its selection must not survive.
        selection.retain(listOf("a", "b", "a").toReorderableEntries())
        assertEquals(listOf("a"), selection.songIds(duplicated))
        assertEquals(1, selection.count)
    }

    @Test
    fun songIds_followDisplayOrderNotSelectionOrder() {
        val selection = SongSelectionState()
        // Picked last-first; the result still reads in list order.
        selection.start(duplicated[3].uid)
        selection.toggle(duplicated[1].uid)

        assertEquals(listOf("b", "c"), selection.songIds(duplicated))
        assertEquals(listOf(1, 3), selection.sourceIndices(duplicated))
    }

    @Test
    fun saverRoundTrip_preservesSelection() {
        val selection = SongSelectionState()
        selection.start(duplicated[2].uid)

        val restored = SongSelectionState.Saver.run {
            val saved = androidx.compose.runtime.saveable.SaverScope { true }
                .let { scope -> with(this) { scope.save(selection) } }
            restore(saved!!)
        }
        requireNotNull(restored)
        assertTrue(restored.isActive)
        assertEquals(listOf(2), restored.sourceIndices(duplicated))
    }
}
