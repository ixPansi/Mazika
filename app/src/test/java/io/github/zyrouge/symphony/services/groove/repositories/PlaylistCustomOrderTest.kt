package io.github.zyrouge.symphony.services.groove.repositories

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaylistCustomOrderTest {
    @Test
    fun withNothingStored_favoritesComesFirst() {
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("b", "favorites", "a"),
            storedOrder = emptyList(),
        )

        assertEquals(listOf("favorites", "b", "a"), ordered)
    }

    @Test
    fun withNothingStored_nonFavouritesKeepTheirIncomingOrder() {
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("c", "a", "b"),
            storedOrder = emptyList(),
        )

        assertEquals(listOf("c", "a", "b"), ordered)
    }

    @Test
    fun storedOrderIsApplied() {
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("a", "b", "favorites"),
            storedOrder = listOf("b", "favorites", "a"),
        )

        assertEquals(listOf("b", "favorites", "a"), ordered)
    }

    @Test
    fun favoritesIsDraggableOnceAnOrderExists() {
        // The default pins it first; an explicit order must be allowed to override that,
        // otherwise the user cannot move it and the pin looks like a bug.
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("favorites", "a"),
            storedOrder = listOf("a", "favorites"),
        )

        assertEquals(listOf("a", "favorites"), ordered)
    }

    @Test
    fun newPlaylistsLandAtTheEnd() {
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("a", "b", "fresh"),
            storedOrder = listOf("b", "a"),
        )

        assertEquals(listOf("b", "a", "fresh"), ordered)
    }

    @Test
    fun severalNewPlaylistsKeepTheirRelativeOrder() {
        // sortedBy is stable, so unranked ids must not shuffle amongst themselves.
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("x", "a", "y", "b", "z"),
            storedOrder = listOf("b", "a"),
        )

        assertEquals(listOf("b", "a", "x", "y", "z"), ordered)
    }

    @Test
    fun deletedPlaylistsInTheStoredOrderAreIgnored() {
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("a", "c"),
            storedOrder = listOf("c", "gone", "a"),
        )

        assertEquals(listOf("c", "a"), ordered)
    }

    @Test
    fun everyPlaylistSurvivesTheSort() {
        val playlistIds = listOf("a", "b", "c", "d")

        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = playlistIds,
            storedOrder = listOf("d", "b"),
        )

        assertEquals(playlistIds.size, ordered.size)
        assertEquals(playlistIds.toSet(), ordered.toSet())
    }

    @Test
    fun aDuplicatedStoredIdDoesNotChangeItsRank() {
        // A corrupt or hand-edited preference must not reorder anything unexpectedly; the
        // first occurrence wins.
        val ordered = PlaylistRepository.applyCustomOrder(
            playlistIds = listOf("a", "b"),
            storedOrder = listOf("b", "a", "b"),
        )

        assertEquals(listOf("b", "a"), ordered)
    }
}
