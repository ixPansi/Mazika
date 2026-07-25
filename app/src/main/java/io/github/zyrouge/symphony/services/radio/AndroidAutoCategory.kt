package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony

/**
 * MAZIKA: a browsable category on the Android Auto root screen.
 *
 * The user chooses which of these appear and in what order (Settings -> Android Auto),
 * so the first thing they see in the car can be Playlists rather than Songs.
 * [Symphony.settings.androidAutoCategories] holds the enabled ones, in order.
 */
enum class AndroidAutoCategory(val mediaId: String) {
    QUEUE(MediaId.CATEGORY_QUEUE),
    SONGS(MediaId.CATEGORY_SONGS),
    ALBUMS(MediaId.CATEGORY_ALBUMS),
    ARTISTS(MediaId.CATEGORY_ARTISTS),
    PLAYLISTS(MediaId.CATEGORY_PLAYLISTS),
    GENRES(MediaId.CATEGORY_GENRES),
    FOLDERS(MediaId.CATEGORY_FOLDERS),
    LYRICS(MediaId.CATEGORY_LYRICS);

    fun label(symphony: Symphony) = when (this) {
        QUEUE -> symphony.t.Queue
        SONGS -> symphony.t.Songs
        ALBUMS -> symphony.t.Albums
        ARTISTS -> symphony.t.Artists
        PLAYLISTS -> symphony.t.Playlists
        GENRES -> symphony.t.Genres
        FOLDERS -> symphony.t.Folders
        LYRICS -> symphony.t.Lyrics
    }

    companion object {
        /**
         * Default order. [QUEUE] leads deliberately: Android Auto decides the order of
         * its own panes, and the pane it shows when you swipe away from the player is
         * the *first* root category - so putting the queue there is what makes the
         * queue, rather than the song list, the thing next to the player.
         *
         * [LYRICS] sits last: it is a wall of text, which is the least appropriate
         * thing to land on while driving.
         */
        val Default = listOf(QUEUE, SONGS, ALBUMS, ARTISTS, PLAYLISTS, GENRES, FOLDERS, LYRICS)
    }
}
