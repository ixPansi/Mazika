package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.groove.repositories.PlaylistRepository
import io.github.zyrouge.symphony.ui.helpers.ViewContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistGrid(
    context: ViewContext,
    playlistIds: List<String>,
    playlistsCount: Int? = null,
    leadingContent: @Composable () -> Unit = {},
) {
    val sortBy by context.symphony.settings.lastUsedPlaylistsSortBy.flow.collectAsState()
    val sortReverse by context.symphony.settings.lastUsedPlaylistsSortReverse.flow.collectAsState()
    val updateId by context.symphony.groove.playlist.updateId.collectAsState()
    // MAZIKA: updateId is in the key because the custom order lives in settings, not in
    // playlistIds - reordering changes neither the ids nor the sort, so nothing else here
    // would tell this to recompute.
    val sortedPlaylistIds by remember(playlistIds, sortBy, sortReverse, updateId) {
        derivedStateOf {
            context.symphony.groove.playlist.sort(playlistIds, sortBy, sortReverse)
        }
    }
    val horizontalGridColumns by context.symphony.settings.lastUsedPlaylistsHorizontalGridColumns.flow.collectAsState()
    val verticalGridColumns by context.symphony.settings.lastUsedPlaylistsVerticalGridColumns.flow.collectAsState()
    val gridColumns by remember(horizontalGridColumns, verticalGridColumns) {
        derivedStateOf {
            ResponsiveGridColumns(horizontalGridColumns, verticalGridColumns)
        }
    }
    val layout by context.symphony.settings.lastUsedPlaylistsLayout.flow.collectAsState()
    var showModifyLayoutSheet by remember { mutableStateOf(false) }

    // Dragging only means something when the user is looking at their own order.
    val canReorder = sortBy == PlaylistRepository.SortBy.CUSTOM

    MediaSortBarScaffold(
        mediaSortBar = {
            Column {
                leadingContent()
                MediaSortBar(
                    context,
                    reverse = sortReverse,
                    onReverseChange = {
                        context.symphony.settings.lastUsedPlaylistsSortReverse.setValue(it)
                    },
                    sort = sortBy,
                    sorts = PlaylistRepository.SortBy.entries
                        .associateWith { x -> ViewContext.parameterizedFn { x.label(it) } },
                    onSortChange = {
                        context.symphony.settings.lastUsedPlaylistsSortBy.setValue(it)
                    },
                    label = {
                        Text(
                            context.symphony.t.XPlaylists(
                                (playlistsCount ?: playlistIds.size).toString()
                            )
                        )
                    },
                    onShowModifyLayout = {
                        showModifyLayoutSheet = true
                    },
                )
            }
        },
        content = {
            when {
                playlistIds.isEmpty() -> IconTextBody(
                    icon = { modifier ->
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            null,
                            modifier = modifier,
                        )
                    },
                    content = {
                        Text(context.symphony.t.DamnThisIsSoEmpty)
                    }
                )

                else -> ReorderableMediaContent(
                    ids = sortedPlaylistIds,
                    canReorder = canReorder,
                    layout = layout,
                    gridColumns = gridColumns,
                    sortReverse = sortReverse,
                    sourceVersion = Triple(playlistIds, sortBy, sortReverse),
                    contentType = Groove.Kind.PLAYLIST,
                    onReorder = { context.symphony.groove.playlist.setCustomOrder(it) },
                    listItem = { playlistId ->
                        context.symphony.groove.playlist.get(playlistId)?.let {
                            PlaylistListItem(context, it)
                        }
                    },
                    gridItem = { _, playlistId, _ ->
                        context.symphony.groove.playlist.get(playlistId)?.let {
                            PlaylistTile(context, it)
                        }
                    },
                )
            }

            if (showModifyLayoutSheet) {
                MediaLayoutAdjustDialog(
                    context,
                    layout = layout,
                    onLayoutChange = {
                        context.symphony.settings.lastUsedPlaylistsLayout.setValue(it)
                    },
                    columns = gridColumns,
                    onColumnsChange = {
                        context.symphony.settings.lastUsedPlaylistsHorizontalGridColumns.setValue(
                            it.horizontal
                        )
                        context.symphony.settings.lastUsedPlaylistsVerticalGridColumns.setValue(
                            it.vertical
                        )
                    },
                    onDismissRequest = {
                        showModifyLayoutSheet = false
                    }
                )
            }
        }
    )
}

private fun PlaylistRepository.SortBy.label(context: ViewContext) = when (this) {
    PlaylistRepository.SortBy.CUSTOM -> context.symphony.t.Custom
    PlaylistRepository.SortBy.TITLE -> context.symphony.t.Title
    PlaylistRepository.SortBy.TRACKS_COUNT -> context.symphony.t.TrackCount
}
