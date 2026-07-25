package io.github.zyrouge.symphony.ui.view.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.services.groove.PlayedItemType
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.components.AlbumArtistTile
import io.github.zyrouge.symphony.ui.components.AlbumTile
import io.github.zyrouge.symphony.ui.components.ArtistTile
import io.github.zyrouge.symphony.ui.components.PlaylistTile
import io.github.zyrouge.symphony.ui.components.SongDropdownMenu
import io.github.zyrouge.symphony.ui.components.SquareGrooveTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.GenreViewRoute
import io.github.zyrouge.symphony.utils.SimpleFileSystem
import io.github.zyrouge.symphony.utils.SimplePath

/**
 * MAZIKA: the things most recently played *from* — a playlist, an album, an artist, a
 * genre, a folder, or a lone song where there was no larger thing to point at.
 *
 * Each kind renders as the tile it already has elsewhere in the app, so tapping opens it
 * and the tile's own play button plays it, exactly as those tiles behave everywhere else.
 *
 * Entries that no longer resolve — a deleted playlist, an album that vanished in a
 * rescan — are skipped rather than drawn as holes. The history records what happened; it
 * cannot promise the thing still exists.
 */
@Composable
fun RecentlyPlayedRow(context: ViewContext) {
    val recent by context.symphony.groove.playHistory.recent.collectAsState()
    val resolved = recent.filter { it.resolves(context) }.take(MAX_TILES)
    if (resolved.isEmpty()) {
        // Nothing played yet: no heading either, rather than an empty shelf.
        return
    }

    Spacer(modifier = Modifier.height(24.dp))
    Box(modifier = Modifier.padding(20.dp, 0.dp)) {
        ProvideTextStyle(MaterialTheme.typography.titleLarge) {
            Text(context.symphony.t.RecentlyPlayed)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        resolved.forEach { source ->
            Box(modifier = Modifier.width(TILE_WIDTH)) {
                RecentlyPlayedTile(context, source)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun RecentlyPlayedTile(context: ViewContext, source: PlaySource) {
    val groove = context.symphony.groove
    when (source.type) {
        PlayedItemType.ALBUM -> groove.album.get(source.id)?.let { AlbumTile(context, it) }
        PlayedItemType.ARTIST -> groove.artist.get(source.id)?.let { ArtistTile(context, it) }
        PlayedItemType.ALBUM_ARTIST ->
            groove.albumArtist.get(source.id)?.let { AlbumArtistTile(context, it) }

        PlayedItemType.PLAYLIST ->
            groove.playlist.get(source.id)?.let { PlaylistTile(context, it) }

        PlayedItemType.SONG -> source.song(context)?.let { SongTile(context, it) }
        PlayedItemType.GENRE -> GenreTile(context, source.id)
        PlayedItemType.FOLDER -> source.folder(context)?.let { FolderTile(context, source.id, it) }
    }
}

@Composable
private fun SongTile(context: ViewContext, song: Song) {
    val coverUpdateId by context.symphony.groove.song.customCoverUpdateId.collectAsState()
    val favorites by context.symphony.groove.playlist.favorites.collectAsState()
    SquareGrooveTile(
        image = androidx.compose.runtime.remember(song.id, coverUpdateId) {
            song.createArtworkImageRequest(context.symphony).build()
        },
        options = { expanded, onDismissRequest ->
            SongDropdownMenu(
                context,
                song,
                isFavorite = favorites.contains(song.id),
                expanded = expanded,
                onDismissRequest = onDismissRequest,
            )
        },
        content = {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artists.isNotEmpty()) {
                Text(
                    song.artists.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onPlay = { context.symphony.radio.shorty.playQueue(song.id, source = PlaySource.song(song.path)) },
        onClick = { context.symphony.radio.shorty.playQueue(song.id, source = PlaySource.song(song.path)) },
    )
}

@Composable
private fun GenreTile(context: ViewContext, name: String) {
    val songIds = context.symphony.groove.genre.getSongIds(name)
    val artwork = songIds.firstOrNull()
        ?.let { context.symphony.groove.song.createArtworkImageRequest(it).build() }
        ?: return
    SquareGrooveTile(
        image = artwork,
        options = { _, _ -> },
        content = {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onPlay = {
            context.symphony.radio.shorty.playQueue(songIds, source = PlaySource.genre(name))
        },
        onClick = { context.navController.navigate(GenreViewRoute(name)) },
    )
}

@Composable
private fun FolderTile(context: ViewContext, path: String, folder: SimpleFileSystem.Folder) {
    val songIds = folder.children.values
        .filterIsInstance<SimpleFileSystem.File>()
        .mapNotNull { it.data as? String }
    val artwork = songIds.firstOrNull()
        ?.let { context.symphony.groove.song.createArtworkImageRequest(it).build() }
        ?: return
    SquareGrooveTile(
        image = artwork,
        options = { _, _ -> },
        content = {
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onPlay = {
            context.symphony.radio.shorty.playQueue(songIds, source = PlaySource.folder(path))
        },
        onClick = {
            context.symphony.radio.shorty.playQueue(songIds, source = PlaySource.folder(path))
        },
    )
}

private fun PlaySource.song(context: ViewContext) = context.symphony.groove.song
    .let { it.pathCache[id]?.let(it::get) }

/** Walks the media explorer down to a folder path, mirroring the Android Auto browser. */
private fun PlaySource.folder(context: ViewContext): SimpleFileSystem.Folder? {
    var current = context.symphony.groove.song.explorer
    val parts = SimplePath(id).parts
    var index = if (parts.isNotEmpty() && parts[0] == current.name) 1 else 0
    while (index < parts.size) {
        current = current.children[parts[index]] as? SimpleFileSystem.Folder ?: return null
        index++
    }
    return current
}

private fun PlaySource.resolves(context: ViewContext): Boolean {
    val groove = context.symphony.groove
    return when (type) {
        PlayedItemType.ALBUM -> groove.album.get(id) != null
        PlayedItemType.ARTIST -> groove.artist.get(id) != null
        PlayedItemType.ALBUM_ARTIST -> groove.albumArtist.get(id) != null
        PlayedItemType.PLAYLIST -> groove.playlist.get(id) != null
        PlayedItemType.SONG -> song(context) != null
        PlayedItemType.GENRE -> groove.genre.getSongIds(id).isNotEmpty()
        PlayedItemType.FOLDER -> folder(context) != null
    }
}

private val TILE_WIDTH = 140.dp
private const val MAX_TILES = 12
