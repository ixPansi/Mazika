package io.github.zyrouge.symphony.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import io.github.zyrouge.symphony.services.groove.repositories.ArtistRepository
import io.github.zyrouge.symphony.ui.helpers.ViewContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistGrid(
    context: ViewContext,
    artistName: List<String>,
    artistsCount: Int? = null,
) {
    val sortBy by context.symphony.settings.lastUsedArtistsSortBy.flow.collectAsState()
    val sortReverse by context.symphony.settings.lastUsedArtistsSortReverse.flow.collectAsState()
    // The order lives in settings, not in the ids, so a drag changes neither the ids
    // nor the sort - watching the setting is what tells this to re-sort.
    val customOrder by context.symphony.settings.artistsCustomOrder.flow.collectAsState()
    // Dragging only means something when the user is looking at their own order.
    val canReorder = sortBy == ArtistRepository.SortBy.CUSTOM
    val sortedArtistNames by remember(artistName, sortBy, sortReverse, customOrder) {
        derivedStateOf {
            context.symphony.groove.artist.sort(artistName, sortBy, sortReverse)
        }
    }
    val horizontalGridColumns by context.symphony.settings.lastUsedArtistsHorizontalGridColumns.flow.collectAsState()
    val verticalGridColumns by context.symphony.settings.lastUsedArtistsVerticalGridColumns.flow.collectAsState()
    val gridColumns by remember(horizontalGridColumns, verticalGridColumns) {
        derivedStateOf {
            ResponsiveGridColumns(horizontalGridColumns, verticalGridColumns)
        }
    }
    val layout by context.symphony.settings.lastUsedArtistsLayout.flow.collectAsState()
    var showModifyLayoutSheet by remember { mutableStateOf(false) }

    MediaSortBarScaffold(
        mediaSortBar = {
            MediaSortBar(
                context,
                reverse = sortReverse,
                onReverseChange = {
                    context.symphony.settings.lastUsedArtistsSortReverse.setValue(it)
                },
                sort = sortBy,
                sorts = ArtistRepository.SortBy.entries
                    .associateWith { x -> ViewContext.parameterizedFn { x.label(it) } },
                onSortChange = {
                    context.symphony.settings.lastUsedArtistsSortBy.setValue(it)
                },
                label = {
                    Text(context.symphony.t.XArtists((artistsCount ?: artistName.size).toString()))
                },
                onShowModifyLayout = {
                    showModifyLayoutSheet = true
                },
            )
        },
        content = {
            when {
                artistName.isEmpty() -> IconTextBody(
                    icon = { modifier ->
                        Icon(
                            Icons.Filled.Person,
                            null,
                            modifier = modifier,
                        )
                    },
                    content = { Text(context.symphony.t.DamnThisIsSoEmpty) }
                )

                else -> ReorderableMediaContent(
                    ids = sortedArtistNames,
                    canReorder = canReorder,
                    layout = layout,
                    gridColumns = gridColumns,
                    sortReverse = sortReverse,
                    sourceVersion = Triple(artistName, sortBy, sortReverse),
                    contentType = Groove.Kind.ARTIST,
                    onReorder = { context.symphony.groove.artist.setCustomOrder(it) },
                    listItem = { id ->
                        context.symphony.groove.artist.get(id)?.let { ArtistListItem(context, it) }
                    },
                    gridItem = { _, id, _ ->
                        context.symphony.groove.artist.get(id)?.let { ArtistTile(context, it) }
                    },
                )
            }

            if (showModifyLayoutSheet) {
                MediaLayoutAdjustDialog(
                    context,
                    layout = layout,
                    onLayoutChange = {
                        context.symphony.settings.lastUsedArtistsLayout.setValue(it)
                    },
                    columns = gridColumns,
                    onColumnsChange = {
                        context.symphony.settings.lastUsedArtistsHorizontalGridColumns.setValue(
                            it.horizontal
                        )
                        context.symphony.settings.lastUsedArtistsVerticalGridColumns.setValue(
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

private fun ArtistRepository.SortBy.label(context: ViewContext) = when (this) {
    ArtistRepository.SortBy.CUSTOM -> context.symphony.t.Custom
    ArtistRepository.SortBy.ARTIST_NAME -> context.symphony.t.Artist
    ArtistRepository.SortBy.ALBUMS_COUNT -> context.symphony.t.AlbumCount
    ArtistRepository.SortBy.TRACKS_COUNT -> context.symphony.t.TrackCount
}
