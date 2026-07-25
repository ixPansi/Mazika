package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.groove.repositories.SongRepository
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.SettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.GrooveSettingsViewRoute

enum class SongListType {
    Default,
    Playlist,
    Album,
}

@Composable
fun SongList(
    context: ViewContext,
    songIds: List<String>,
    songsCount: Int? = null,
    leadingContent: (LazyListScope.() -> Unit)? = null,
    trailingContent: (LazyListScope.() -> Unit)? = null,
    trailingOptionsContent: (@Composable ColumnScope.(Int, Song, () -> Unit) -> Unit)? = null,
    cardThumbnailLabel: (@Composable (Int, Song) -> Unit)? = null,
    cardThumbnailLabelStyle: SongCardThumbnailLabelStyle = SongCardThumbnailLabelStyle.Default,
    type: SongListType = SongListType.Default,
    disableHeartIcon: Boolean = false,
    enableAddMediaFoldersHint: Boolean = false,
    /**
     * MAZIKA: when supplied, rows get a drag handle and can be reordered. Called once
     * on release with the new full ordering. Reordering is only offered while the list
     * is on its Custom sort - any other sort is derived, so a manual order would be
     * discarded on the next recomposition. Requires [leadingContent] to be null, since
     * extra leading items would offset the lazy-list indices the drag maths relies on.
     */
    onReorder: ((List<String>) -> Unit)? = null,
    /**
     * MAZIKA: what this list *is* — the album, artist or playlist it belongs to — so
     * playing from it is recorded against that rather than against the track. Null on a
     * plain library list, where the song itself is the only thing to record.
     */
    playSource: PlaySource? = null,
) {
    val sortBy by type.getLastUsedSortBy(context).flow.collectAsState()
    val sortReverse by type.getLastUsedSortReverse(context).flow.collectAsState()
    val sortedSongIds by remember(songIds, sortBy, sortReverse) {
        derivedStateOf {
            context.symphony.groove.song.sort(songIds, sortBy, sortReverse)
        }
    }

    MediaSortBarScaffold(
        mediaSortBar = {
            MediaSortBar(
                context,
                reverse = sortReverse,
                onReverseChange = {
                    type.setLastUsedSortReverse(context, it)
                },
                sort = sortBy,
                sorts = SongRepository.SortBy.entries
                    .associateWith { x -> ViewContext.parameterizedFn { x.label(it) } },
                onSortChange = {
                    type.setLastUsedSortBy(context, it)
                },
                label = {
                    Text(context.symphony.t.XSongs((songsCount ?: songIds.size).toString()))
                },
                onShufflePlay = {
                    context.symphony.radio.shorty.playQueue(
                        sortedSongIds,
                        shuffle = true,
                        source = playSource,
                    )
                }
            )
        },
        content = {
            when {
                songIds.isEmpty() -> IconTextBody(
                    icon = { modifier ->
                        Icon(Icons.Filled.MusicNote, null, modifier = modifier)
                    },
                    content = {
                        Text(context.symphony.t.DamnThisIsSoEmpty)
                        if (enableAddMediaFoldersHint) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                context.symphony.t.HintAddMediaFolders,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .clickable {
                                        context.navController.navigate(
                                            GrooveSettingsViewRoute(SettingsViewRoute.ELEMENT_MEDIA_FOLDERS)
                                        )
                                    }
                                    .padding(2.dp),
                            )
                        }
                    }
                )

                else -> {
                    val lazyListState = rememberLazyListState()
                    val canReorder = onReorder != null &&
                            leadingContent == null &&
                            sortBy == SongRepository.SortBy.CUSTOM
                    val localOrder = remember { mutableStateListOf<ReorderableEntry<String>>() }
                    LaunchedEffect(sortedSongIds) {
                        localOrder.clear()
                        localOrder.addAll(sortedSongIds.toReorderableEntries())
                    }
                    val displayedEntries = when {
                        canReorder -> localOrder
                        else -> sortedSongIds.toReorderableEntries()
                    }
                    val reorderState = rememberReorderableState(
                        listState = lazyListState,
                        itemCount = { if (canReorder) localOrder.size else 0 },
                        onMove = { from, to ->
                            if (from in localOrder.indices && to in localOrder.indices) {
                                localOrder.add(to, localOrder.removeAt(from))
                            }
                        },
                        onSettle = { onReorder?.invoke(localOrder.map { it.value }) },
                    )

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.drawScrollBar(lazyListState)
                    ) {
                        leadingContent?.invoke(this)
                        itemsIndexed(
                            displayedEntries,
                            key = { _, entry -> entry.uid },
                            contentType = { _, _ -> Groove.Kind.SONG }
                        ) { i, entry ->
                            context.symphony.groove.song.get(entry.value)?.let { song ->
                                Box(
                                    modifier = reorderableItemModifier(
                                        reorderState,
                                        entry.uid,
                                        enabled = canReorder,
                                    )
                                ) {
                                    SongCard(
                                        context,
                                        song = song,
                                        leading = {
                                            if (canReorder) {
                                                Icon(
                                                    Icons.Filled.DragIndicator,
                                                    context.symphony.t.Reorder,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .reorderableHandle(reorderState, entry.uid),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                        },
                                        thumbnailLabel = cardThumbnailLabel?.let {
                                            { it(i, song) }
                                        },
                                        thumbnailLabelStyle = cardThumbnailLabelStyle,
                                        disableHeartIcon = disableHeartIcon,
                                        trailingOptionsContent = trailingOptionsContent?.let {
                                            { onDismissRequest -> it(i, song, onDismissRequest) }
                                        },
                                    ) {
                                        context.symphony.radio.shorty.playQueue(
                                            displayedEntries.map { it.value },
                                            Radio.PlayOptions(index = i),
                                            // Falls back to the song itself when the
                                            // list is not part of a larger thing.
                                            source = playSource ?: PlaySource.song(song.path),
                                        )
                                    }
                                }
                            }
                        }
                        trailingContent?.invoke(this)
                    }
                }
            }
        }
    )
}

fun SongRepository.SortBy.label(context: ViewContext) = when (this) {
    SongRepository.SortBy.CUSTOM -> context.symphony.t.Custom
    SongRepository.SortBy.TITLE -> context.symphony.t.Title
    SongRepository.SortBy.ARTIST -> context.symphony.t.Artist
    SongRepository.SortBy.ALBUM -> context.symphony.t.Album
    SongRepository.SortBy.DURATION -> context.symphony.t.Duration
    SongRepository.SortBy.DATE_MODIFIED -> context.symphony.t.LastModified
    SongRepository.SortBy.COMPOSER -> context.symphony.t.Composer
    SongRepository.SortBy.ALBUM_ARTIST -> context.symphony.t.AlbumArtist
    SongRepository.SortBy.YEAR -> context.symphony.t.Year
    SongRepository.SortBy.FILENAME -> context.symphony.t.Filename
    SongRepository.SortBy.TRACK_NUMBER -> context.symphony.t.TrackNumber
}

fun SongListType.getLastUsedSortBy(context: ViewContext) = when (this) {
    SongListType.Default -> context.symphony.settings.lastUsedSongsSortBy
    SongListType.Album -> context.symphony.settings.lastUsedAlbumSongsSortBy
    SongListType.Playlist -> context.symphony.settings.lastUsedPlaylistSongsSortBy
}

fun SongListType.setLastUsedSortBy(context: ViewContext, sort: SongRepository.SortBy) =
    when (this) {
        SongListType.Default -> context.symphony.settings.lastUsedSongsSortBy.setValue(sort)
        SongListType.Playlist -> context.symphony.settings.lastUsedPlaylistSongsSortBy.setValue(sort)
        SongListType.Album -> context.symphony.settings.lastUsedAlbumSongsSortBy.setValue(sort)
    }

fun SongListType.getLastUsedSortReverse(context: ViewContext) = when (this) {
    SongListType.Default -> context.symphony.settings.lastUsedSongsSortReverse
    SongListType.Playlist -> context.symphony.settings.lastUsedPlaylistSongsSortReverse
    SongListType.Album -> context.symphony.settings.lastUsedAlbumSongsSortReverse
}

fun SongListType.setLastUsedSortReverse(context: ViewContext, reverse: Boolean) = when (this) {
    SongListType.Default -> context.symphony.settings.lastUsedSongsSortReverse.setValue(reverse)
    SongListType.Playlist -> context.symphony.settings.lastUsedPlaylistSongsSortReverse.setValue(
        reverse
    )

    SongListType.Album -> context.symphony.settings.lastUsedAlbumSongsSortReverse.setValue(reverse)
}
