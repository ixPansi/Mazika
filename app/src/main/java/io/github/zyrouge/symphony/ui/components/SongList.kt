package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.services.groove.repositories.SongRepository
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.ui.helpers.SongSelectionState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.SettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.GrooveSettingsViewRoute

enum class SongListType {
    Default,
    Playlist,
    Album,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    context: ViewContext,
    songIds: List<String>,
    songsCount: Int? = null,
    leadingContent: (LazyListScope.() -> Unit)? = null,
    trailingContent: (LazyListScope.() -> Unit)? = null,
    trailingOptionsContent: (@Composable ColumnScope.(Int, Int, Song, () -> Unit) -> Unit)? = null,
    cardThumbnailLabel: (@Composable (Int, Song) -> Unit)? = null,
    cardThumbnailLabelStyle: SongCardThumbnailLabelStyle = SongCardThumbnailLabelStyle.Default,
    type: SongListType = SongListType.Default,
    disableHeartIcon: Boolean = false,
    enableAddMediaFoldersHint: Boolean = false,
    showShufflePlay: Boolean = true,
    /**
     * MAZIKA: when supplied, rows can be reordered with a long press. Called once on
     * release with the new full ordering. Reordering is only offered while the list is
     * on its Custom sort - any other sort is derived, so a manual order would be
     * discarded on the next recomposition. Requires [leadingContent] to be null because
     * this generic slot does not expose how many lazy items it emits.
     */
    onReorder: ((List<String>) -> Unit)? = null,
    onReorderStateChange: ((Boolean) -> Unit)? = null,
    /**
     * MAZIKA: what this list *is* — the album, artist or playlist it belongs to — so
     * playing from it is recorded against that rather than against the track. Null on a
     * plain library list, where the song itself is the only thing to record.
     */
    playSource: PlaySource? = null,
    /**
     * MAZIKA: multi-select. When supplied, a long press on a row enters selection mode
     * and taps toggle rows instead of playing them. Reordering is suppressed while a
     * selection is active, since both want the long press and the same rows.
     */
    selection: SongSelectionState? = null,
) {
    val sortBy by type.getLastUsedSortBy(context).flow.collectAsState()
    val sortReverse by type.getLastUsedSortReverse(context).flow.collectAsState()
    val sortedSongIds by remember(songIds, sortBy, sortReverse) {
        derivedStateOf {
            context.symphony.groove.song.sort(songIds, sortBy, sortReverse)
        }
    }
    val lazyListState = rememberLazyListState()
    val isSelecting = selection?.isActive == true
    val canReorder = onReorder != null &&
            leadingContent == null &&
            sortBy == SongRepository.SortBy.CUSTOM &&
            !isSelecting
    val displayedEntries = remember(songIds, sortedSongIds, sortReverse) {
        songIds.toReorderableEntriesInOrder(
            orderedValues = sortedSongIds,
            reverseOccurrences = sortReverse,
        )
    }
    val displayedKeys = displayedEntries.map { it.uid }
    val reorderState = rememberReorderableState(
        listState = lazyListState,
        itemKeys = { if (canReorder) displayedKeys else emptyList() },
        sourceVersion = { Triple(songIds, sortBy, sortReverse) },
        onMove = { from, to ->
            if (canReorder && from in displayedEntries.indices && to in displayedEntries.indices) {
                onReorder?.invoke(
                    displayedEntries
                        .map { it.value }
                        .movedItem(from, to)
                        .toStoredCustomOrder(sortReverse)
                )
            }
        },
    )
    val currentOnReorderStateChange by rememberUpdatedState(onReorderStateChange)
    val isReordering = reorderState.draggingKey != null
    LaunchedEffect(reorderState, isReordering) {
        currentOnReorderStateChange?.invoke(isReordering)
    }
    DisposableEffect(reorderState) {
        onDispose { currentOnReorderStateChange?.invoke(false) }
    }

    // MAZIKA: publish what is on screen so a selection bar hoisted out of this list can
    // resolve uids, and drop selections whose rows have gone - after a bulk remove or a
    // rescan - so the count in the bar can never outrun what is actually there.
    LaunchedEffect(displayedEntries, selection) {
        selection?.entries = displayedEntries
        if (selection?.isActive == true) selection.retain(displayedEntries)
    }

    val rowContent: @Composable (Int, ReorderableEntry<String>) -> Unit = { i, entry ->
        context.symphony.groove.song.get(entry.value)?.let { song ->
            val isSelected = isSelecting && selection?.contains(entry.uid) == true
            SongCard(
                context,
                song = song,
                selected = isSelected,
                leading = when {
                    isSelecting -> ({
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { selection?.toggle(entry.uid) },
                            modifier = Modifier.offset((-4).dp),
                        )
                    })

                    else -> ({})
                },
                thumbnailLabel = cardThumbnailLabel?.let {
                    { it(i, song) }
                },
                thumbnailLabelStyle = cardThumbnailLabelStyle,
                // The per-row heart is noise next to a checkbox, and its tap target
                // competes with toggling the row.
                disableHeartIcon = disableHeartIcon || isSelecting,
                trailingOptionsContent = when {
                    isSelecting -> null
                    else -> trailingOptionsContent?.let {
                        { onDismissRequest -> it(i, entry.sourceIndex, song, onDismissRequest) }
                    }
                },
                onLongClick = when {
                    // While reordering owns the long press, selection is reached from the
                    // screen's overflow menu instead.
                    selection == null || canReorder -> null
                    else -> ({ selection.start(entry.uid) })
                },
            ) {
                when {
                    isSelecting -> selection?.toggle(entry.uid)
                    else -> context.symphony.radio.shorty.playQueue(
                        displayedEntries.map { it.value },
                        Radio.PlayOptions(index = i),
                        source = playSource ?: PlaySource.song(song.path),
                    )
                }
            }
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
                onShufflePlay = if (showShufflePlay) {
                    {
                        context.symphony.radio.shorty.playQueue(
                            sortedSongIds,
                            shuffle = true,
                            source = playSource,
                        )
                    }
                } else {
                    null
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
                    val listContent: @Composable () -> Unit = {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .drawScrollBar(lazyListState)
                        ) {
                            leadingContent?.invoke(this)
                            itemsIndexed(
                                displayedEntries,
                                key = { _, entry -> entry.uid },
                                contentType = { _, _ -> Groove.Kind.SONG }
                            ) { i, entry ->
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            placementSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            )
                                        )
                                        .then(
                                            reorderableItemModifier(
                                                reorderState,
                                                entry.uid,
                                                enabled = canReorder,
                                            )
                                        )
                                ) {
                                    rowContent(i, entry)
                                }
                            }
                            trailingContent?.invoke(this)
                        }
                    }
                    if (canReorder) {
                        ReorderableContainer(
                            state = reorderState,
                            modifier = Modifier.fillMaxSize(),
                            draggedItem = { index ->
                                displayedEntries.getOrNull(index)?.let {
                                    rowContent(index, it)
                                }
                            },
                        ) {
                            listContent()
                        }
                    } else {
                        listContent()
                    }
                }
            }
        }
    )
}

internal fun <T> List<T>.toStoredCustomOrder(reverse: Boolean): List<T> =
    if (reverse) reversed() else toList()

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
