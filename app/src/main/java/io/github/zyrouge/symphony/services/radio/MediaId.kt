package io.github.zyrouge.symphony.services.radio

import java.util.Base64

/**
 * MAZIKA: stable, separator-safe media ids for Android Auto browsing/playback.
 *
 * Format: `type|<base64url(id)>` optionally followed by `|contextType|<base64url(contextId)>`.
 * Raw ids (album ids, artist/genre names, folder paths, …) can contain any
 * character, so every raw segment is Base64-url encoded — the base64url alphabet
 * never contains the `|` separator, so decoding is unambiguous.
 */
object MediaId {
    const val ROOT = "root"

    // Category (browsable) roots.
    const val CATEGORY_SONGS = "category:songs"
    const val CATEGORY_ALBUMS = "category:albums"
    const val CATEGORY_ARTISTS = "category:artists"
    const val CATEGORY_PLAYLISTS = "category:playlists"
    const val CATEGORY_GENRES = "category:genres"
    const val CATEGORY_FOLDERS = "category:folders"

    // Item types.
    const val TYPE_SONG = "song"
    const val TYPE_ALBUM = "album"
    const val TYPE_ARTIST = "artist"
    const val TYPE_PLAYLIST = "playlist"
    const val TYPE_GENRE = "genre"
    const val TYPE_FOLDER = "folder"

    // Context types (how a played song builds its queue).
    const val CONTEXT_ALL = "all"

    data class Parsed(
        val type: String,
        val id: String,
        val contextType: String? = null,
        val contextId: String? = null,
    )

    fun of(
        type: String,
        id: String,
        contextType: String? = null,
        contextId: String? = null,
    ): String {
        val builder = StringBuilder()
        builder.append(type).append('|').append(encode(id))
        if (contextType != null) {
            builder.append('|').append(contextType).append('|').append(encode(contextId ?: ""))
        }
        return builder.toString()
    }

    fun parse(mediaId: String): Parsed? {
        val parts = mediaId.split('|')
        return try {
            when (parts.size) {
                2 -> Parsed(parts[0], decode(parts[1]))
                4 -> Parsed(parts[0], decode(parts[1]), parts[2], decode(parts[3]))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun encode(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        Charsets.UTF_8,
    )
}
