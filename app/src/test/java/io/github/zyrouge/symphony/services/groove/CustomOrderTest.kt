package io.github.zyrouge.symphony.services.groove

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CustomOrderTest {
    @Test
    fun withNothingStored_favoritesComesFirst() {
        val ordered = applyStoredOrder(
            ids = listOf("b", "favorites", "a"),
            storedOrder = emptyList(),
            pinnedFirst = FAVORITES,
        )

        assertEquals(listOf("favorites", "b", "a"), ordered)
    }

    @Test
    fun withNothingStored_nonFavouritesKeepTheirIncomingOrder() {
        val ordered = applyStoredOrder(
            ids = listOf("c", "a", "b"),
            storedOrder = emptyList(),
            pinnedFirst = FAVORITES,
        )

        assertEquals(listOf("c", "a", "b"), ordered)
    }

    @Test
    fun storedOrderIsApplied() {
        val ordered = applyStoredOrder(
            ids = listOf("a", "b", "favorites"),
            storedOrder = listOf("b", "favorites", "a"),
            pinnedFirst = FAVORITES,
        )

        assertEquals(listOf("b", "favorites", "a"), ordered)
    }

    @Test
    fun favoritesIsDraggableOnceAnOrderExists() {
        // The default pins it first; an explicit order must be allowed to override that,
        // otherwise the user cannot move it and the pin looks like a bug.
        val ordered = applyStoredOrder(
            ids = listOf("favorites", "a"),
            storedOrder = listOf("a", "favorites"),
            pinnedFirst = FAVORITES,
        )

        assertEquals(listOf("a", "favorites"), ordered)
    }

    @Test
    fun newPlaylistsLandAtTheEnd() {
        val ordered = applyStoredOrder(
            ids = listOf("a", "b", "fresh"),
            storedOrder = listOf("b", "a"),
        )

        assertEquals(listOf("b", "a", "fresh"), ordered)
    }

    @Test
    fun severalNewPlaylistsKeepTheirRelativeOrder() {
        // sortedBy is stable, so unranked ids must not shuffle amongst themselves.
        val ordered = applyStoredOrder(
            ids = listOf("x", "a", "y", "b", "z"),
            storedOrder = listOf("b", "a"),
        )

        assertEquals(listOf("b", "a", "x", "y", "z"), ordered)
    }

    @Test
    fun deletedPlaylistsInTheStoredOrderAreIgnored() {
        val ordered = applyStoredOrder(
            ids = listOf("a", "c"),
            storedOrder = listOf("c", "gone", "a"),
        )

        assertEquals(listOf("c", "a"), ordered)
    }

    @Test
    fun everyPlaylistSurvivesTheSort() {
        val playlistIds = listOf("a", "b", "c", "d")

        val ordered = applyStoredOrder(
            ids = playlistIds,
            storedOrder = listOf("d", "b"),
        )

        assertEquals(playlistIds.size, ordered.size)
        assertEquals(playlistIds.toSet(), ordered.toSet())
    }

    @Test
    fun withoutAPin_nothingIsPromoted() {
        // Albums, artists, album artists and genres pin nothing, so an unset order has to
        // leave their incoming order completely alone.
        val ids = listOf("c", "favorites", "a")

        assertEquals(ids, applyStoredOrder(ids, storedOrder = emptyList()))
    }

    @Test
    fun aPinnedIdThatDoesNotExistIsSkipped() {
        val ordered = applyStoredOrder(
            ids = listOf("b", "a"),
            storedOrder = emptyList(),
            pinnedFirst = FAVORITES,
        )

        assertEquals(listOf("b", "a"), ordered)
    }

    @Test
    fun aDuplicatedStoredIdDoesNotChangeItsRank() {
        // A corrupt or hand-edited preference must not reorder anything unexpectedly; the
        // first occurrence wins.
        val ordered = applyStoredOrder(
            ids = listOf("a", "b"),
            storedOrder = listOf("b", "a", "b"),
        )

        assertEquals(listOf("b", "a"), ordered)
    }

    companion object {
        private val FAVORITES = listOf("favorites")
    }
}
