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
    SONGS(MediaId.CATEGORY_SONGS),
    ALBUMS(MediaId.CATEGORY_ALBUMS),
    ARTISTS(MediaId.CATEGORY_ARTISTS),
    PLAYLISTS(MediaId.CATEGORY_PLAYLISTS),
    GENRES(MediaId.CATEGORY_GENRES),
    FOLDERS(MediaId.CATEGORY_FOLDERS);

    fun label(symphony: Symphony) = when (this) {
        SONGS -> symphony.t.Songs
        ALBUMS -> symphony.t.Albums
        ARTISTS -> symphony.t.Artists
        PLAYLISTS -> symphony.t.Playlists
        GENRES -> symphony.t.Genres
        FOLDERS -> symphony.t.Folders
    }

    companion object {
        /** Fresh-install categories in their default root-screen order. */
        val Default = listOf(PLAYLISTS, SONGS, ARTISTS, GENRES, ALBUMS)
    }
}
