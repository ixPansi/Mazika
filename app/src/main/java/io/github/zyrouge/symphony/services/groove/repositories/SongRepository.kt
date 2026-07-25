package io.github.zyrouge.symphony.services.groove.repositories

import android.net.Uri
import androidx.core.net.toUri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.groove.SongCover
import io.github.zyrouge.symphony.ui.helpers.Assets
import io.github.zyrouge.symphony.ui.helpers.createHandyImageRequest
import io.github.zyrouge.symphony.utils.FuzzySearchOption
import io.github.zyrouge.symphony.utils.FuzzySearcher
import io.github.zyrouge.symphony.utils.CustomCovers
import io.github.zyrouge.symphony.utils.KeyGenerator
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.SimpleFileSystem
import io.github.zyrouge.symphony.utils.SimplePath
import io.github.zyrouge.symphony.utils.joinToStringIfNotEmpty
import io.github.zyrouge.symphony.utils.withCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class SongRepository(private val symphony: Symphony) {
    enum class SortBy {
        CUSTOM,
        TITLE,
        ARTIST,
        ALBUM,
        DURATION,
        DATE_MODIFIED,
        COMPOSER,
        ALBUM_ARTIST,
        YEAR,
        FILENAME,
        TRACK_NUMBER,
    }

    private val cache = ConcurrentHashMap<String, Song>()

    // MAZIKA: user-chosen per-song covers, keyed by song path so they survive the id
    // regeneration that happens on every rescan.
    private val customCovers = ConcurrentHashMap<String, String>()
    internal val pathCache = ConcurrentHashMap<String, String>()
    internal val idGenerator = KeyGenerator.TimeIncremental()
    private val searcher = FuzzySearcher<String>(
        options = listOf(
            FuzzySearchOption({ v -> get(v)?.title?.let { compareString(it) } }, 3),
            FuzzySearchOption({ v -> get(v)?.filename?.let { compareString(it) } }, 2),
            FuzzySearchOption({ v -> get(v)?.artists?.let { compareCollection(it) } }),
            FuzzySearchOption({ v -> get(v)?.album?.let { compareString(it) } })
        )
    )

    val isUpdating get() = symphony.groove.exposer.isUpdating
    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all = _all.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()
    private val _id = MutableStateFlow(System.currentTimeMillis())
    val id = _id.asStateFlow()
    var explorer = SimpleFileSystem.Folder()

    private fun emitCount() = _count.update { cache.size }

    private fun emitIds() = _id.update {
        System.currentTimeMillis()
    }

    internal fun onSong(song: Song) {
        cache[song.id] = song
        pathCache[song.path] = song.id
        explorer.addChildFile(SimplePath(song.path)).data = song.id
        emitIds()
        _all.update {
            it + song.id
        }
        emitCount()
    }

    fun reset() {
        cache.clear()
        pathCache.clear()
        explorer = SimpleFileSystem.Folder()
        emitIds()
        _all.update {
            emptyList()
        }
        emitCount()
    }

    fun search(songIds: List<String>, terms: String, limit: Int = 7) = searcher
        .search(terms, songIds, maxLength = limit)

    fun sort(songIds: List<String>, by: SortBy, reverse: Boolean): List<String> {
        val sensitive = symphony.settings.caseSensitiveSorting.value
        val sorted = when (by) {
            SortBy.CUSTOM -> songIds
            SortBy.TITLE -> songIds.sortedBy { get(it)?.title?.withCase(sensitive) }
            SortBy.ARTIST -> songIds.sortedBy { get(it)?.artists?.joinToStringIfNotEmpty(sensitive) }
            SortBy.ALBUM -> songIds.sortedBy { get(it)?.album?.withCase(sensitive) }
            SortBy.DURATION -> songIds.sortedBy { get(it)?.duration }
            SortBy.DATE_MODIFIED -> songIds.sortedBy { get(it)?.dateModified }
            SortBy.COMPOSER -> songIds.sortedBy {
                get(it)?.composers?.joinToStringIfNotEmpty(sensitive)
            }

            SortBy.ALBUM_ARTIST -> songIds.sortedBy {
                get(it)?.albumArtists?.joinToStringIfNotEmpty(sensitive)
            }

            SortBy.YEAR -> songIds.sortedBy { get(it)?.year }
            SortBy.FILENAME -> songIds.sortedBy { get(it)?.filename?.withCase(sensitive) }
            SortBy.TRACK_NUMBER -> songIds.sortedWith(
                compareBy({ get(it)?.discNumber }, { get(it)?.trackNumber }),
            )
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun count() = cache.size
    fun ids() = cache.keys.toList()
    fun values() = cache.values.toList()

    fun get(id: String) = cache[id]
    fun get(ids: List<String>) = ids.mapNotNull { get(it) }

    /** Custom cover file name for a song, if the user set one. */
    fun getCustomCoverFile(songId: String): String? =
        get(songId)?.path?.let { customCovers[it] }

    fun hasCustomCover(songId: String) = getCustomCoverFile(songId) != null

    // MAZIKA artwork precedence: a valid custom cover, then the embedded artwork,
    // then the placeholder. A missing custom file falls through instead of blanking.
    fun getArtworkUri(songId: String): Uri {
        getCustomCoverFile(songId)
            ?.let { CustomCovers.resolveFile(symphony, it, CustomCovers.SONG_DIRECTORY) }
            ?.takeIf { it.exists() }
            ?.let { return it.toUri() }
        return get(songId)?.coverFile
            ?.let { symphony.database.artworkCache.get(it) }?.toUri()
            ?: getDefaultArtworkUri()
    }

    /** Loads stored custom covers; called once the library has been scanned. */
    internal suspend fun fetchCustomCovers() {
        try {
            val entries = symphony.database.songCovers.entries()
            customCovers.clear()
            entries.forEach { customCovers[it.path] = it.coverFile }
            CustomCovers.cleanupOrphans(
                symphony,
                customCovers.values.toSet(),
                CustomCovers.SONG_DIRECTORY,
            )
        } catch (err: Exception) {
            Logger.error("SongRepository", "unable to load custom covers", err)
        }
    }

    /** Stores a user-selected image as this song's cover. */
    fun setCustomCover(
        songId: String,
        sourceUri: Uri,
        crop: CustomCovers.CropRegion? = null,
        onResult: (Boolean) -> Unit,
    ) {
        val song = get(songId) ?: return onResult(false)
        symphony.groove.coroutineScope.launch {
            val name = CustomCovers.saveFromUri(
                symphony, song.id, sourceUri, crop, CustomCovers.SONG_DIRECTORY,
            )
            if (name == null) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            val previous = customCovers.put(song.path, name)
            symphony.database.songCovers.upsert(SongCover(song.path, name))
            if (previous != null && previous != name) {
                CustomCovers.delete(symphony, previous, CustomCovers.SONG_DIRECTORY)
            }
            emitIds()
            withContext(Dispatchers.Main) { onResult(true) }
        }
    }

    /** Clears a song's custom cover, restoring its embedded artwork. */
    fun removeCustomCover(songId: String) {
        val song = get(songId) ?: return
        val previous = customCovers.remove(song.path) ?: return
        emitIds()
        symphony.groove.coroutineScope.launch {
            symphony.database.songCovers.delete(song.path)
            CustomCovers.delete(symphony, previous, CustomCovers.SONG_DIRECTORY)
        }
    }

    fun getDefaultArtworkUri() = Assets.getPlaceholderUri(symphony)

    fun createArtworkImageRequest(songId: String) = createHandyImageRequest(
        symphony.applicationContext,
        image = getArtworkUri(songId),
        fallback = Assets.getPlaceholderId(symphony),
    )

    suspend fun getLyrics(song: Song): String? {
        try {
            val lrcPath = SimplePath(song.path).let {
                it.parent?.join(it.nameWithoutExtension + ".lrc")?.pathString
            }
            symphony.groove.exposer.uris[lrcPath]?.let { uri ->
                symphony.applicationContext.contentResolver.openInputStream(uri)?.use {
                    return String(it.readBytes())
                }
            }
            return symphony.database.lyricsCache.get(song.id)
        } catch (err: Exception) {
            Logger.error("LyricsRepository", "fetch lyrics failed", err)
        }
        return null
    }
}
