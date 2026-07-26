package io.github.zyrouge.symphony.services.radio

import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.SimpleFileSystem
import io.github.zyrouge.symphony.utils.SimplePath

/**
 * MAZIKA: builds the Android Auto browse tree and resolves browse/search actions
 * onto the shared MAZIKA playback engine. It reads the existing in-memory groove
 * repositories (no rescans, no heavy artwork decoding) and always routes playback
 * through [RadioShorty]/[Radio], so Android Auto shares one queue and one session
 * with the phone and respects the pause/resume fade preference.
 */
class RadioBrowser(private val symphony: Symphony) {
    // MAZIKA: the root screen follows the user's configured category order
    // (Settings -> Android Auto), falling back to the default order if they have
    // somehow disabled everything - an empty root would look like a broken app.
    fun rootChildren(): List<MediaItem> {
        val categories = symphony.settings.androidAutoCategories.value
            .takeIf { it.isNotEmpty() }
            ?: AndroidAutoCategory.Default
        return categories.map { category(it.mediaId, it.label(symphony)) }
    }

    fun getChildren(parentMediaId: String): List<MediaItem> = when (parentMediaId) {
        MediaId.ROOT -> rootChildren()
        MediaId.CATEGORY_SONGS ->
            allSongIdsSorted().take(MAX_CHILDREN).mapNotNull {
                songItem(it, MediaId.CONTEXT_ALL, null)
            }

        MediaId.CATEGORY_ALBUMS -> albumIdsSorted().take(MAX_CHILDREN).mapNotNull { albumItem(it) }
        MediaId.CATEGORY_ARTISTS -> artistNamesSorted().take(MAX_CHILDREN).map { artistItem(it) }
        MediaId.CATEGORY_PLAYLISTS ->
            playlistIdsSorted().mapNotNull { symphony.groove.playlist.get(it) }.map { playlistItem(it) }

        MediaId.CATEGORY_GENRES -> genreNamesSorted().map { genreItem(it) }
        MediaId.CATEGORY_FOLDERS -> folderChildren(symphony.groove.song.explorer)
        else -> {
            val parsed = MediaId.parse(parentMediaId) ?: return emptyList()
            when (parsed.type) {
                MediaId.TYPE_ALBUM ->
                    albumSongIdsSorted(parsed.id).take(MAX_CHILDREN).mapNotNull {
                        songItem(it, MediaId.TYPE_ALBUM, parsed.id)
                    }

                MediaId.TYPE_ARTIST ->
                    artistSongIdsSorted(parsed.id).take(MAX_CHILDREN).mapNotNull {
                        songItem(it, MediaId.TYPE_ARTIST, parsed.id)
                    }

                MediaId.TYPE_PLAYLIST ->
                    (symphony.groove.playlist.get(parsed.id)?.getSortedSongIds(symphony) ?: emptyList())
                        .take(MAX_CHILDREN)
                        .mapNotNull { songItem(it, MediaId.TYPE_PLAYLIST, parsed.id) }

                MediaId.TYPE_GENRE ->
                    genreSongIdsSorted(parsed.id).take(MAX_CHILDREN).mapNotNull {
                        songItem(it, MediaId.TYPE_GENRE, parsed.id)
                    }

                MediaId.TYPE_FOLDER ->
                    resolveFolder(parsed.id)?.let { folderChildren(it) } ?: emptyList()

                else -> emptyList()
            }
        }
    }

    // --- playback ---------------------------------------------------------------

    fun playFromMediaId(mediaId: String) {
        val parsed = MediaId.parse(mediaId) ?: return
        when (parsed.type) {
            MediaId.TYPE_SONG -> {
                val queue = queueForContext(parsed.contextType, parsed.contextId)
                val index = queue.indexOf(parsed.id).takeIf { it >= 0 } ?: 0
                val finalQueue = queue.ifEmpty { listOf(parsed.id) }
                symphony.radio.shorty.playQueue(
                    finalQueue,
                    Radio.PlayOptions(index = index),
                    // The media id already carries what the song was browsed from, so
                    // playing in the car feeds the same history as playing on the phone.
                    source = sourceFor(parsed.contextType, parsed.contextId)
                        ?: symphony.groove.song.get(parsed.id)?.path?.let { PlaySource.song(it) },
                )
            }

            MediaId.TYPE_ALBUM ->
                playSongs(albumSongIdsSorted(parsed.id), PlaySource.album(parsed.id))

            MediaId.TYPE_ARTIST ->
                playSongs(artistSongIdsSorted(parsed.id), PlaySource.artist(parsed.id))

            MediaId.TYPE_PLAYLIST -> playSongs(
                symphony.groove.playlist.get(parsed.id)?.getSortedSongIds(symphony) ?: emptyList(),
                PlaySource.playlist(parsed.id),
            )

            MediaId.TYPE_GENRE ->
                playSongs(genreSongIdsSorted(parsed.id), PlaySource.genre(parsed.id))

            MediaId.TYPE_FOLDER ->
                playSongs(folderSongIds(parsed.id), PlaySource.folder(parsed.id))

            else -> {}
        }
    }

    private fun sourceFor(contextType: String?, contextId: String?): PlaySource? {
        val id = contextId ?: return null
        return when (contextType) {
            MediaId.TYPE_ALBUM -> PlaySource.album(id)
            MediaId.TYPE_ARTIST -> PlaySource.artist(id)
            MediaId.TYPE_PLAYLIST -> PlaySource.playlist(id)
            MediaId.TYPE_GENRE -> PlaySource.genre(id)
            MediaId.TYPE_FOLDER -> PlaySource.folder(id)
            else -> null
        }
    }

    fun playFromSearch(query: String?) {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) {
            // Generic "play music" voice command.
            playSongs(allSongIdsSorted())
            return
        }
        val ql = q.lowercase()
        val songMatches = allSongIdsSorted().filter { id ->
            symphony.groove.song.get(id)?.let { s ->
                s.title.lowercase().contains(ql) || s.artists.any { it.lowercase().contains(ql) }
            } == true
        }
        if (songMatches.isNotEmpty()) {
            playSongs(songMatches)
            return
        }
        albumIdsSorted().firstOrNull {
            symphony.groove.album.get(it)?.name?.lowercase()?.contains(ql) == true
        }?.let { playSongs(albumSongIdsSorted(it)); return }
        artistNamesSorted().firstOrNull { it.lowercase().contains(ql) }
            ?.let { playSongs(artistSongIdsSorted(it)); return }
        playlistIdsSorted().mapNotNull { symphony.groove.playlist.get(it) }
            .firstOrNull { it.title.lowercase().contains(ql) }
            ?.let { playSongs(it.getSortedSongIds(symphony)) }
    }

    fun search(query: String?): List<MediaItem> {
        val q = query?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) return emptyList()
        val items = mutableListOf<MediaItem>()
        allSongIdsSorted().asSequence()
            .mapNotNull { symphony.groove.song.get(it) }
            .filter { s ->
                s.title.lowercase().contains(q) ||
                        s.artists.any { it.lowercase().contains(q) } ||
                        (s.album?.lowercase()?.contains(q) == true)
            }
            .take(SEARCH_LIMIT)
            .forEach { songItem(it.id, MediaId.CONTEXT_ALL, null)?.let(items::add) }
        albumIdsSorted().asSequence()
            .filter { symphony.groove.album.get(it)?.name?.lowercase()?.contains(q) == true }
            .take(SEARCH_LIMIT)
            .forEach { albumItem(it)?.let(items::add) }
        artistNamesSorted().asSequence()
            .filter { it.lowercase().contains(q) }
            .take(SEARCH_LIMIT)
            .forEach { items.add(artistItem(it)) }
        playlistIdsSorted().asSequence()
            .mapNotNull { symphony.groove.playlist.get(it) }
            .filter { it.title.lowercase().contains(q) }
            .take(SEARCH_LIMIT)
            .forEach { items.add(playlistItem(it)) }
        return items
    }

    private fun playSongs(songIds: List<String>, source: PlaySource? = null) {
        if (songIds.isEmpty()) return
        symphony.radio.shorty.playQueue(songIds, source = source)
    }

    private fun queueForContext(contextType: String?, contextId: String?): List<String> =
        when (contextType) {
            MediaId.TYPE_ALBUM -> albumSongIdsSorted(contextId.orEmpty())
            MediaId.TYPE_ARTIST -> artistSongIdsSorted(contextId.orEmpty())
            MediaId.TYPE_PLAYLIST ->
                symphony.groove.playlist.get(contextId.orEmpty())?.getSortedSongIds(symphony) ?: emptyList()

            MediaId.TYPE_GENRE -> genreSongIdsSorted(contextId.orEmpty())
            MediaId.TYPE_FOLDER -> folderSongIds(contextId.orEmpty())
            else -> allSongIdsSorted()
        }

    // --- sorted id helpers (match the app's last-used sort options) -------------

    private fun allSongIdsSorted() = symphony.groove.song.sort(
        symphony.groove.song.ids(),
        symphony.settings.lastUsedSongsSortBy.value,
        symphony.settings.lastUsedSongsSortReverse.value,
    )

    private fun albumIdsSorted() = symphony.groove.album.sort(
        symphony.groove.album.ids(),
        symphony.settings.lastUsedAlbumsSortBy.value,
        symphony.settings.lastUsedAlbumsSortReverse.value,
    )

    private fun artistNamesSorted() = symphony.groove.artist.sort(
        symphony.groove.artist.ids(),
        symphony.settings.lastUsedArtistsSortBy.value,
        symphony.settings.lastUsedArtistsSortReverse.value,
    )

    private fun playlistIdsSorted() = symphony.groove.playlist.sort(
        symphony.groove.playlist.ids(),
        symphony.settings.lastUsedPlaylistsSortBy.value,
        symphony.settings.lastUsedPlaylistsSortReverse.value,
    )

    private fun genreNamesSorted() = symphony.groove.genre.sort(
        symphony.groove.genre.ids(),
        symphony.settings.lastUsedGenresSortBy.value,
        symphony.settings.lastUsedGenresSortReverse.value,
    )

    private fun albumSongIdsSorted(albumId: String) = symphony.groove.song.sort(
        symphony.groove.album.getSongIds(albumId),
        symphony.settings.lastUsedAlbumSongsSortBy.value,
        symphony.settings.lastUsedAlbumSongsSortReverse.value,
    )

    private fun artistSongIdsSorted(artistName: String) = symphony.groove.song.sort(
        symphony.groove.artist.getSongIds(artistName),
        symphony.settings.lastUsedSongsSortBy.value,
        symphony.settings.lastUsedSongsSortReverse.value,
    )

    private fun genreSongIdsSorted(genre: String) = symphony.groove.song.sort(
        symphony.groove.genre.getSongIds(genre),
        symphony.settings.lastUsedSongsSortBy.value,
        symphony.settings.lastUsedSongsSortReverse.value,
    )

    // --- folders ----------------------------------------------------------------

    private fun folderChildren(folder: SimpleFileSystem.Folder): List<MediaItem> {
        val folderPath = folder.fullPath.pathString
        return folder.children.values
            .sortedWith(compareBy({ it !is SimpleFileSystem.Folder }, { it.name.lowercase() }))
            .take(MAX_CHILDREN)
            .mapNotNull { child ->
                when (child) {
                    is SimpleFileSystem.Folder -> folderItem(child)
                    is SimpleFileSystem.File ->
                        (child.data as? String)?.let { songItem(it, MediaId.TYPE_FOLDER, folderPath) }
                }
            }
    }

    private fun folderSongIds(folderPath: String): List<String> {
        val folder = resolveFolder(folderPath) ?: return emptyList()
        return folder.children.values
            .filterIsInstance<SimpleFileSystem.File>()
            .mapNotNull { it.data as? String }
    }

    private fun resolveFolder(folderPath: String): SimpleFileSystem.Folder? {
        var current = symphony.groove.song.explorer
        val parts = SimplePath(folderPath).parts
        var index = 0
        if (parts.isNotEmpty() && parts[0] == current.name) {
            index = 1
        }
        while (index < parts.size) {
            current = current.children[parts[index]] as? SimpleFileSystem.Folder ?: return null
            index++
        }
        return current
    }

    // --- media items ------------------------------------------------------------

    private fun category(mediaId: String, title: String): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    /**
     * MAZIKA: hands the uri to the connected browser clients before it is published.
     *
     * A browse item's icon is fetched by the *client* process, which can only open the
     * app's unexported artwork provider if it holds a grant for that exact uri. Nothing
     * on this path granted anything before - the only grant in the app was a directory
     * prefix issued once at connect, and prefix grants are not honoured everywhere.
     */
    private fun grantedArtworkUri(uri: Uri): Uri {
        symphony.radio.session.grantArtworkUri(uri)
        return uri
    }

    private fun songItem(songId: String, contextType: String?, contextId: String?): MediaItem? {
        val song = symphony.groove.song.get(songId) ?: return null
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MediaId.of(MediaId.TYPE_SONG, songId, contextType, contextId))
            .setTitle(song.title)
            .setSubtitle(song.artists.joinToString().ifEmpty { null })
            .setIconUri(grantedArtworkUri(symphony.radio.artworkUris.song(song.id)))
            .build()
        return MediaItem(description, MediaItem.FLAG_PLAYABLE)
    }

    private fun albumItem(albumId: String): MediaItem? {
        val album = symphony.groove.album.get(albumId) ?: return null
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MediaId.of(MediaId.TYPE_ALBUM, albumId))
            .setTitle(album.name)
            .setSubtitle(album.artists.joinToString().ifEmpty { null })
            .setIconUri(
                grantedArtworkUri(
                    symphony.radio.artworkUris.firstSong(
                        symphony.groove.album.getSongIds(albumId)
                    )
                )
            )
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun artistItem(artistName: String): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MediaId.of(MediaId.TYPE_ARTIST, artistName))
            .setTitle(artistName)
            .setIconUri(
                grantedArtworkUri(
                    symphony.radio.artworkUris.firstSong(
                        symphony.groove.artist.getSongIds(artistName)
                    )
                )
            )
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun playlistItem(playlist: Playlist): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MediaId.of(MediaId.TYPE_PLAYLIST, playlist.id))
            .setTitle(playlist.title)
            .setIconUri(grantedArtworkUri(symphony.radio.artworkUris.playlist(playlist)))
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun genreItem(genre: String): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MediaId.of(MediaId.TYPE_GENRE, genre))
            .setTitle(genre)
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    private fun folderItem(folder: SimpleFileSystem.Folder): MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(MediaId.of(MediaId.TYPE_FOLDER, folder.fullPath.pathString))
            .setTitle(folder.name)
            .build()
        return MediaItem(description, MediaItem.FLAG_BROWSABLE)
    }

    /** Parents whose children can display this song's artwork. */
    internal fun artworkParentsForSong(songId: String): Set<String> {
        val parents = mutableSetOf(
            MediaId.CATEGORY_SONGS,
            MediaId.CATEGORY_ALBUMS,
            MediaId.CATEGORY_ARTISTS,
            MediaId.CATEGORY_PLAYLISTS,
            MediaId.CATEGORY_FOLDERS,
        )
        val song = symphony.groove.song.get(songId) ?: return parents
        symphony.groove.album.getIdFromSong(song)?.let {
            parents.add(MediaId.of(MediaId.TYPE_ALBUM, it))
        }
        song.artists.forEach { parents.add(MediaId.of(MediaId.TYPE_ARTIST, it)) }
        song.genres.forEach { parents.add(MediaId.of(MediaId.TYPE_GENRE, it)) }
        symphony.groove.playlist.values().forEach { playlist ->
            if (songId in playlist.getSongIds(symphony)) {
                parents.add(MediaId.of(MediaId.TYPE_PLAYLIST, playlist.id))
            }
        }
        SimplePath(song.path).parent?.let {
            val folderPath = SimplePath("root", it.pathString).pathString
            parents.add(MediaId.of(MediaId.TYPE_FOLDER, folderPath))
        }
        return parents
    }

    companion object {
        private const val MAX_CHILDREN = 500
        private const val SEARCH_LIMIT = 20
    }
}
