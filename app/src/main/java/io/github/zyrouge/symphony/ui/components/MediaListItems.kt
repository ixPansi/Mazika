package io.github.zyrouge.symphony.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import io.github.zyrouge.symphony.services.groove.Album
import io.github.zyrouge.symphony.services.groove.AlbumArtist
import io.github.zyrouge.symphony.services.groove.Artist
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.AlbumArtistViewRoute
import io.github.zyrouge.symphony.ui.view.AlbumViewRoute
import io.github.zyrouge.symphony.ui.view.ArtistViewRoute
import io.github.zyrouge.symphony.ui.view.GenreViewRoute
import io.github.zyrouge.symphony.ui.view.PlaylistViewRoute
import io.github.zyrouge.symphony.ui.view.home.FolderDropdownMenu
import io.github.zyrouge.symphony.ui.view.home.createArtworkImageRequest
import io.github.zyrouge.symphony.utils.SimpleFileSystem

/**
 * MAZIKA: the row forms of the browse tiles, for [MediaLayout.LIST].
 *
 * Each is a thin wrapper over [GenericGrooveCard], which already is this idiom - artwork,
 * title, subtitle, overflow menu - and each reuses the dropdown its tile already builds, so
 * the actions available on a playlist or album do not depend on which layout you are
 * looking at. Named `*ListItem` rather than `*Row` because [AlbumRow] is already taken by
 * the horizontal carousel of tiles.
 */

@Composable
fun PlaylistListItem(
    context: ViewContext,
    playlist: Playlist,
    onSongsChanged: () -> Unit = {},
) {
    val updateId by context.symphony.groove.playlist.updateId.collectAsState()

    // Same staleness problem SquareGrooveTile has: the caller resolved this Playlist with a
    // plain repository lookup, which is not a snapshot read, so a cover or title changed
    // elsewhere would not reach this row until the screen was rebuilt.
    val current = remember(updateId, playlist.id) {
        context.symphony.groove.playlist.get(playlist.id) ?: playlist
    }
    val coverUpdateId by context.symphony.groove.song.customCoverUpdateId.collectAsState()
    val artwork = remember(current, updateId, coverUpdateId) {
        current.createArtworkImageRequest(context.symphony).build()
    }

    GenericGrooveCard(
        image = artwork,
        title = {
            Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitle = {
            Text(context.symphony.t.XSongs(current.numberOfTracks.toString()))
        },
        options = { expanded, onDismissRequest ->
            PlaylistDropdownMenu(
                context,
                current,
                expanded = expanded,
                onSongsChanged = onSongsChanged,
                onDismissRequest = onDismissRequest,
            )
        },
        onClick = {
            context.navController.navigate(PlaylistViewRoute(current.id))
        },
    )
}

@Composable
fun AlbumListItem(context: ViewContext, album: Album) {
    GenericGrooveCard(
        image = album.createArtworkImageRequest(context.symphony).build(),
        title = {
            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitle = album.artists.joinToString().takeIf { it.isNotEmpty() }?.let { artists ->
            { Text(artists, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        options = { expanded, onDismissRequest ->
            AlbumDropdownMenu(
                context,
                album,
                expanded = expanded,
                onDismissRequest = onDismissRequest,
            )
        },
        onClick = {
            context.navController.navigate(AlbumViewRoute(album.id))
        },
    )
}

@Composable
fun ArtistListItem(context: ViewContext, artist: Artist) {
    GenericGrooveCard(
        image = artist.createArtworkImageRequest(context.symphony).build(),
        title = {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitle = {
            Text(context.symphony.t.XSongs(artist.numberOfTracks.toString()))
        },
        options = { expanded, onDismissRequest ->
            ArtistDropdownMenu(
                context,
                artist,
                expanded = expanded,
                onDismissRequest = onDismissRequest,
            )
        },
        onClick = {
            context.navController.navigate(ArtistViewRoute(artist.name))
        },
    )
}

@Composable
fun AlbumArtistListItem(context: ViewContext, albumArtist: AlbumArtist) {
    GenericGrooveCard(
        image = albumArtist.createArtworkImageRequest(context.symphony).build(),
        title = {
            Text(albumArtist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitle = {
            Text(context.symphony.t.XSongs(albumArtist.numberOfTracks.toString()))
        },
        options = { expanded, onDismissRequest ->
            AlbumArtistDropdownMenu(
                context,
                albumArtist,
                expanded = expanded,
                onDismissRequest = onDismissRequest,
            )
        },
        onClick = {
            context.navController.navigate(AlbumArtistViewRoute(albumArtist.name))
        },
    )
}

/**
 * Folders show what is inside them rather than a track count, since that is what you are
 * about to navigate into.
 */
@Composable
fun FolderListItem(
    context: ViewContext,
    folder: SimpleFileSystem.Folder,
    onClick: () -> Unit,
) {
    val subfolders = folder.children.values.count { it is SimpleFileSystem.Folder }
    val songs = folder.children.values.count { it is SimpleFileSystem.File }

    GenericGrooveCard(
        image = folder.createArtworkImageRequest(context).build(),
        title = {
            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitle = {
            Text(
                listOfNotNull(
                    subfolders.takeIf { it > 0 }?.let { context.symphony.t.XFolders(it.toString()) },
                    songs.takeIf { it > 0 }?.let { context.symphony.t.XSongs(it.toString()) },
                ).joinToString(" · ").ifEmpty { context.symphony.t.XSongs("0") },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        options = { expanded, onDismissRequest ->
            FolderDropdownMenu(context, folder, expanded, onDismissRequest)
        },
        onClick = onClick,
    )
}

/** Genres have neither artwork nor a menu, and [GenericGrooveCard] allows both to be null. */
@Composable
fun GenreListItem(context: ViewContext, genre: String, numberOfTracks: Int) {
    GenericGrooveCard(
        image = null,
        title = {
            Text(genre, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitle = {
            Text(context.symphony.t.XSongs(numberOfTracks.toString()))
        },
        options = null,
        onClick = {
            context.navController.navigate(GenreViewRoute(genre))
        },
    )
}
