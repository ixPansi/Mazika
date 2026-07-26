package io.github.zyrouge.symphony.services.radio

import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Playlist

internal fun isSafeArtworkFileName(name: String): Boolean =
    name.isNotBlank() &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..")

/**
 * MAZIKA: identifies the *contents* an artwork uri points at, so the uri changes whenever
 * the file does.
 *
 * Android Auto caches browse icons by uri, persistently and across reconnects, and it does
 * not retry one it has already failed to load. Custom covers normally dodge that because
 * every save mints a new file name - but a file that never changes keeps its uri forever,
 * so a fetch that failed for reasons *outside* the file (a missing permission grant, say)
 * stays failed even after the app is fixed. That is exactly what left covers set before
 * v2026.7.118 showing as blank tiles while covers set afterwards worked.
 *
 * Appending this token gives every previously published uri a new identity exactly once,
 * then holds it stable for as long as the file is untouched.
 */
internal fun artworkVersionToken(lastModified: Long, length: Long): String =
    "$lastModified-$length"

/**
 * Resolves custom and embedded candidates in order. A null result means that the
 * candidate is missing or invalid, so resolution continues to the next source.
 */
internal inline fun <T : Any, R : Any> resolvePreferredArtwork(
    custom: T?,
    embedded: T?,
    default: () -> R,
    resolve: (T) -> R?,
): R {
    custom?.let(resolve)?.let { return it }
    embedded?.let(resolve)?.let { return it }
    return default()
}

/** Central artwork URI policy for Android Auto browse items and media-session queues. */
internal class ArtworkUriResolver(private val symphony: Symphony) {
    private enum class Directory {
        EMBEDDED,
        SONG,
        PLAYLIST,
    }

    private data class FileArtwork(val directory: Directory, val name: String)

    private val context get() = symphony.applicationContext

    fun song(songId: String): Uri {
        val song = symphony.groove.song.get(songId)
        return resolvePreferredArtwork(
            custom = symphony.groove.song.getCustomCoverFile(songId)
                ?.let { FileArtwork(Directory.SONG, it) },
            embedded = song?.coverFile?.let { FileArtwork(Directory.EMBEDDED, it) },
            default = ::defaultArtwork,
            resolve = ::resolveFileArtwork,
        )
    }

    fun firstSong(songIds: Iterable<String>): Uri =
        songIds.firstOrNull()?.let(::song) ?: defaultArtwork()

    fun playlist(playlist: Playlist): Uri = resolvePreferredArtwork(
        custom = playlist.customCoverPath?.let { FileArtwork(Directory.PLAYLIST, it) },
        embedded = null,
        // Resolve the first song through song(), rather than using coverFile directly,
        // so its custom cover has precedence too.
        default = { firstSong(playlist.getSongIds(symphony)) },
        resolve = ::resolveFileArtwork,
    )

    private fun resolveFileArtwork(artwork: FileArtwork): Uri? = when (artwork.directory) {
        Directory.EMBEDDED -> ArtworkProvider.coversUri(context, artwork.name)
        Directory.SONG -> ArtworkProvider.songCoverUri(context, artwork.name)
        Directory.PLAYLIST -> ArtworkProvider.playlistCoverUri(context, artwork.name)
    }

    private fun defaultArtwork() = symphony.groove.song.getDefaultArtworkUri()
}
