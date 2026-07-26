package io.github.zyrouge.symphony.services.groove.repositories

import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.ActivityUtils
import io.github.zyrouge.symphony.utils.FuzzySearchOption
import io.github.zyrouge.symphony.utils.FuzzySearcher
import io.github.zyrouge.symphony.utils.KeyGenerator
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.CustomCovers
import io.github.zyrouge.symphony.utils.mutate
import io.github.zyrouge.symphony.utils.withCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

class PlaylistRepository(private val symphony: Symphony) {
    enum class SortBy {
        CUSTOM,
        TITLE,
        TRACKS_COUNT,
    }

    private val cache = ConcurrentHashMap<String, Playlist>()
    internal val idGenerator = KeyGenerator.TimeIncremental()
    private val searcher = FuzzySearcher<String>(
        options = listOf(FuzzySearchOption({ v -> get(v)?.title?.let { compareString(it) } }))
    )

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating = _isUpdating.asStateFlow()
    private val _updateId = MutableStateFlow(0L)
    val updateId = _updateId.asStateFlow()
    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all = _all.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()
    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites = _favorites.asStateFlow()

    private fun emitUpdate(value: Boolean) = _isUpdating.update {
        value
    }

    private fun emitUpdateId() = _updateId.update {
        System.currentTimeMillis()
    }

    private fun emitCount() = _count.update {
        cache.size
    }

    private fun emitArtworkChanged(playlistId: String) {
        runCatching { symphony.radio.session.refreshPlaylistArtwork(playlistId) }
            .onFailure { Logger.warn("PlaylistRepository", "unable to refresh artwork: $it") }
    }

    suspend fun fetch() {
        emitUpdate(true)
        // MAZIKA: whether every stored playlist made it into the cache. Orphan cleanup
        // deletes cover files that no playlist references, so running it against a
        // partially loaded cache destroys the covers of the playlists that failed to
        // load - permanently, and off disk. Only clean up when the whole set is present.
        var loadedCleanly = true
        try {
            val context = symphony.applicationContext
            val playlists = symphony.database.playlists.entries()
            playlists.values.map { x ->
                val playlist = when {
                    x.isLocal -> {
                        // A single unreadable .m3u must not cost every later playlist its
                        // cover, so a parse failure falls back to the stored row rather
                        // than aborting the loop. Playlist.parse dereferences the
                        // document uri, which throws when the persisted read permission
                        // is gone - exactly what happens when Android Auto cold-starts
                        // the process with no activity.
                        runCatching {
                            ActivityUtils.makePersistableReadableUri(context, x.uri!!)
                            // Re-parsing a local playlist rebuilds it from its .m3u file,
                            // so carry over the stored custom cover reference.
                            Playlist.parse(symphony, x.id, x.uri)
                                .copy(customCoverPath = x.customCoverPath)
                        }.getOrElse { err ->
                            loadedCleanly = false
                            Logger.warn(
                                "PlaylistRepository",
                                "unable to parse local playlist ${x.id}: $err",
                            )
                            x
                        }
                    }

                    else -> x
                }
                cache[playlist.id] = playlist
                _all.update {
                    it + playlist.id
                }
                emitUpdateId()
                emitCount()
            }
            if (!cache.containsKey(FAVORITE_PLAYLIST)) {
                add(getFavorites())
            }
        } catch (_: FileNotFoundException) {
            loadedCleanly = false
        } catch (err: Exception) {
            loadedCleanly = false
            Logger.error("PlaylistRepository", "fetch failed", err)
        }
        _favorites.update {
            getFavorites().getSongIds(symphony)
        }
        // Remove cover files no longer referenced by any playlist.
        when {
            loadedCleanly -> CustomCovers.cleanupOrphans(
                symphony,
                cache.values.mapNotNull { it.customCoverPath }.toSet(),
            )

            else -> Logger.warn(
                "PlaylistRepository",
                "skipping cover cleanup: playlists did not load completely",
            )
        }
        emitUpdateId()
        emitUpdate(false)
    }

    fun reset() {
        emitUpdate(true)
        cache.clear()
        _all.update {
            emptyList()
        }
        emitCount()
        _favorites.update {
            emptyList()
        }
        emitUpdateId()
        emitUpdate(false)
    }

    fun search(playlistIds: List<String>, terms: String, limit: Int = 7) = searcher
        .search(terms, playlistIds, maxLength = limit)

    fun sort(playlistIds: List<String>, by: SortBy, reverse: Boolean): List<String> {
        val sensitive = symphony.settings.caseSensitiveSorting.value
        val sorted = when (by) {
            SortBy.CUSTOM -> {
                val prefix = listOfNotNull(FAVORITE_PLAYLIST)
                val others = playlistIds.toMutableList()
                prefix.forEach { others.remove(it) }
                prefix + others
            }

            SortBy.TITLE -> playlistIds.sortedBy { get(it)?.title?.withCase(sensitive) }
            SortBy.TRACKS_COUNT -> playlistIds.sortedBy { get(it)?.numberOfTracks }
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun count() = cache.size
    fun ids() = cache.keys.toList()
    fun values() = cache.values.toList()

    fun get(id: String) = cache[id]
    fun get(ids: List<String>) = ids.mapNotNull { get(it) }

    fun getFavorites() = cache[FAVORITE_PLAYLIST]
        ?: create(FAVORITE_PLAYLIST, "Favorites", emptyList())

    fun create(title: String, songIds: List<String>) = create(idGenerator.next(), title, songIds)
    private fun create(id: String, title: String, songIds: List<String>) = Playlist(
        id = id,
        title = title,
        songPaths = songIds.mapNotNull { symphony.groove.song.get(it)?.path },
        uri = null,
        path = null,
    )

    fun add(playlist: Playlist) {
        cache[playlist.id] = playlist
        _all.update {
            it + playlist.id
        }
        emitUpdateId()
        emitCount()
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.insert(playlist)
        }
    }

    fun delete(id: String) {
        val removed = cache.remove(id)
        removed?.uri?.let {
            runCatching {
                ActivityUtils.makePersistableReadableUri(symphony.applicationContext, it)
            }
        }
        _all.update {
            it - id
        }
        emitUpdateId()
        emitCount()
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.delete(id)
            // Clean up the app-owned custom cover file (never the user's gallery).
            CustomCovers.deleteAfterGracePeriod(symphony, removed?.customCoverPath)
        }
    }

    fun update(id: String, songIds: List<String>) {
        val playlist = get(id) ?: return
        val updated = Playlist(
            id = id,
            title = playlist.title,
            songPaths = songIds.mapNotNull { symphony.groove.song.get(it)?.path },
            uri = playlist.uri,
            path = playlist.path,
            customCoverPath = playlist.customCoverPath,
        )
        cache[id] = updated
        emitUpdateId()
        emitCount()
        emitArtworkChanged(id)
        if (id == FAVORITE_PLAYLIST) {
            _favorites.update {
                songIds
            }
        }
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.update(updated)
        }
    }

    // MAZIKA: persist a user-selected image as this playlist's custom cover. The
    // heavy decode/resize/write runs off the main thread; onResult is delivered on
    // the main thread. The previous cover file is deleted only after the new one is
    // saved and persisted (atomic replacement).
    fun setCustomCover(
        playlist: Playlist,
        sourceUri: android.net.Uri,
        crop: CustomCovers.CropRegion? = null,
        onResult: (Boolean) -> Unit,
    ) {
        symphony.groove.coroutineScope.launch {
            val name = CustomCovers.saveFromUri(symphony, playlist.id, sourceUri, crop)
            if (name == null) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            val current = get(playlist.id) ?: playlist
            val previous = current.customCoverPath
            val updated = current.copy(customCoverPath = name)
            cache[updated.id] = updated
            emitUpdateId()
            symphony.database.playlists.update(updated)
            emitArtworkChanged(updated.id)
            if (previous != null && previous != name) {
                CustomCovers.deleteAfterGracePeriod(symphony, previous)
            }
            withContext(Dispatchers.Main) { onResult(true) }
        }
    }

    // MAZIKA: remove a custom cover, restoring the default artwork and deleting the
    // app-owned image file.
    fun removeCustomCover(playlist: Playlist) {
        val current = get(playlist.id) ?: playlist
        val previous = current.customCoverPath ?: return
        val updated = current.copy(customCoverPath = null)
        cache[updated.id] = updated
        emitUpdateId()
        emitArtworkChanged(updated.id)
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.update(updated)
            CustomCovers.deleteAfterGracePeriod(symphony, previous)
        }
    }

    // NOTE: maybe we shouldn't use groove's coroutine scope?
    fun favorite(songId: String) {
        val favorites = getFavorites()
        val songIds = favorites.getSongIds(symphony)
        if (songIds.contains(songId)) {
            return
        }
        update(favorites.id, songIds.mutate { add(songId) })
    }

    fun unfavorite(songId: String) {
        val favorites = getFavorites()
        val songIds = favorites.getSongIds(symphony)
        if (!songIds.contains(songId)) {
            return
        }
        update(favorites.id, songIds.mutate { remove(songId) })
    }

    /**
     * MAZIKA: favourites a batch in one write.
     *
     * Calling the single-song version in a loop would rewrite the whole favourites
     * playlist once per song, and emit an update for each - noticeable when a user
     * selects a few hundred tracks.
     */
    fun favorite(newSongIds: Collection<String>) {
        if (newSongIds.isEmpty()) return
        val favorites = getFavorites()
        val songIds = favorites.getSongIds(symphony)
        val additions = newSongIds.filterNot(songIds::contains).distinct()
        if (additions.isEmpty()) return
        update(favorites.id, songIds.mutate { addAll(additions) })
    }

    fun unfavorite(removedSongIds: Collection<String>) {
        if (removedSongIds.isEmpty()) return
        val favorites = getFavorites()
        val songIds = favorites.getSongIds(symphony)
        val removals = removedSongIds.toSet()
        if (songIds.none(removals::contains)) return
        update(favorites.id, songIds.filterNot(removals::contains))
    }

    fun isFavoritesPlaylist(playlist: Playlist) = playlist.id == FAVORITE_PLAYLIST
    fun isBuiltInPlaylist(playlist: Playlist) = isFavoritesPlaylist(playlist)

    fun savePlaylistToUri(playlist: Playlist, uri: Uri) {
        val outputStream = symphony.applicationContext.contentResolver.openOutputStream(uri, "w")
        outputStream?.use {
            val content = playlist.songPaths.joinToString("\n")
            it.write(content.toByteArray())
        }
    }

    fun renamePlaylist(playlist: Playlist, title: String) {
        val renamed = playlist.withTitle(title)
        cache[playlist.id] = renamed
        emitUpdateId()
        symphony.groove.coroutineScope.launch {
            symphony.database.playlists.update(renamed)
        }
    }

    internal fun onScanFinish() {
        _favorites.update {
            getFavorites().getSongIds(symphony)
        }
        emitUpdateId()
    }

    companion object {
        private const val FAVORITE_PLAYLIST = "favorites"
    }
}
