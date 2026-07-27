package io.github.zyrouge.symphony.ui.view.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.ui.components.AddToPlaylistDialog
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.LoaderScaffold
import io.github.zyrouge.symphony.ui.components.MediaSortBar
import io.github.zyrouge.symphony.ui.components.MediaSortBarScaffold
import io.github.zyrouge.symphony.ui.components.ResponsiveGrid
import io.github.zyrouge.symphony.ui.components.ResponsiveGridColumns
import io.github.zyrouge.symphony.ui.components.FolderListItem
import io.github.zyrouge.symphony.ui.components.MediaLayout
import io.github.zyrouge.symphony.ui.components.MediaLayoutAdjustDialog
import io.github.zyrouge.symphony.ui.components.SongList
import io.github.zyrouge.symphony.ui.components.SquareGrooveTile
import io.github.zyrouge.symphony.ui.components.label
import io.github.zyrouge.symphony.ui.helpers.Assets
import io.github.zyrouge.symphony.ui.helpers.FadeTransition
import io.github.zyrouge.symphony.ui.helpers.SlideTransition
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.ui.helpers.SongSelectionState
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.nowPlaying.defaultHorizontalPadding
import io.github.zyrouge.symphony.utils.SimpleFileSystem
import io.github.zyrouge.symphony.utils.StringListUtils
import java.util.Stack

@Composable
fun FoldersView(context: ViewContext, selection: SongSelectionState? = null) {
    val isUpdating by context.symphony.groove.song.isUpdating.collectAsState()
    val id by context.symphony.groove.song.id.collectAsState()
    val explorer = context.symphony.groove.song.explorer

    val folders = remember(id) {
        val entities = mutableMapOf<String, SimpleFileSystem.Folder>()
        val stack = Stack<SimpleFileSystem.Folder>()
        stack.add(explorer)
        while (stack.isNotEmpty()) {
            val current = stack.pop()
            if (current.isEmpty) continue
            var hasSongs = false
            current.children.values.forEach {
                when (it) {
                    is SimpleFileSystem.Folder -> stack.push(it)
                    is SimpleFileSystem.File -> {
                        hasSongs = true
                    }
                }
            }
            if (hasSongs) {
                entities[current.fullPath.pathString] = current
            }
        }
        entities.toMap()
    }
    var currentFolder by remember(id) {
        mutableStateOf<SimpleFileSystem.Folder?>(null)
    }

    BackHandler(currentFolder != null) {
        currentFolder = null
    }

    LoaderScaffold(context, isLoading = isUpdating) {
        AnimatedContent(
            label = "folders-view-content",
            targetState = currentFolder,
            transitionSpec = {
                val enter = when {
                    targetState != null -> SlideTransition.slideUp.enterTransition()
                    else -> FadeTransition.enterTransition()
                }
                enter.togetherWith(FadeTransition.exitTransition())
            },
        ) { folder ->
            if (folder != null) {
                val songIds by remember(folder) {
                    derivedStateOf {
                        folder.children.values.mapNotNull {
                            when (it) {
                                is SimpleFileSystem.File -> it.data as String
                                else -> null
                            }
                        }
                    }
                }

                Column {
                    Column(
                        modifier = Modifier.padding(
                            start = defaultHorizontalPadding,
                            end = defaultHorizontalPadding,
                            top = 4.dp,
                            bottom = 12.dp,
                        ),
                    ) {
                        folder.parent?.let { parent ->
                            Text(
                                "${parent.fullPath}/",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = LocalContentColor.current.copy(alpha = 0.7f),
                                ),
                            )
                        }
                        Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                    SongList(
                        context,
                        songIds = songIds,
                        songsCount = songIds.size,
                        selection = selection,
                    )
                }
            } else {
                FoldersGrid(
                    context,
                    folders = folders,
                    onClick = {
                        currentFolder = it
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersGrid(
    context: ViewContext,
    folders: Map<String, SimpleFileSystem.Folder>,
    onClick: (SimpleFileSystem.Folder) -> Unit,
) {
    val sortBy by context.symphony.settings.lastUsedFoldersSortBy.flow.collectAsState()
    val sortReverse by context.symphony.settings.lastUsedFoldersSortReverse.flow.collectAsState()
    val sortedFolderNames by remember(folders, sortBy, sortReverse) {
        derivedStateOf {
            StringListUtils.sort(folders.keys.toList(), sortBy, sortReverse)
        }
    }
    val horizontalGridColumns by context.symphony.settings.lastUsedFoldersHorizontalGridColumns.flow.collectAsState()
    val verticalGridColumns by context.symphony.settings.lastUsedFoldersVerticalGridColumns.flow.collectAsState()
    val gridColumns by remember(horizontalGridColumns, verticalGridColumns) {
        derivedStateOf {
            ResponsiveGridColumns(horizontalGridColumns, verticalGridColumns)
        }
    }
    val layout by context.symphony.settings.lastUsedFoldersLayout.flow.collectAsState()
    var showModifyLayoutSheet by remember { mutableStateOf(false) }

    MediaSortBarScaffold(
        mediaSortBar = {
            MediaSortBar(
                context,
                reverse = sortReverse,
                onReverseChange = {
                    context.symphony.settings.lastUsedFoldersSortReverse.setValue(it)
                },
                sort = sortBy,
                sorts = StringListUtils.SortBy.entries
                    .associateWith { x -> ViewContext.parameterizedFn { x.label(context) } },
                onSortChange = {
                    context.symphony.settings.lastUsedFoldersSortBy.setValue(it)
                },
                label = {
                    Text(context.symphony.t.XFolders(folders.size.toString()))
                },
                onShowModifyLayout = {
                    showModifyLayoutSheet = true
                }
            )
        },
        content = {
            when {
                sortedFolderNames.isEmpty() -> IconTextBody(
                    icon = { modifier ->
                        Icon(
                            Icons.Filled.FolderCopy,
                            null,
                            modifier = modifier,
                        )
                    },
                    content = { Text(context.symphony.t.DamnThisIsSoEmpty) }
                )

                layout == MediaLayout.LIST -> LazyColumn {
                    items(
                        sortedFolderNames,
                        key = { it },
                        contentType = { Groove.Kind.ARTIST },
                    ) { folderName ->
                        folders[folderName]?.let { folder ->
                            FolderListItem(
                                context, folder = folder,
                                onClick = { onClick(folder) },
                            )
                        }
                    }
                }

                else -> ResponsiveGrid(gridColumns) {
                    itemsIndexed(
                        sortedFolderNames,
                        key = { _, x -> x },
                        contentType = { _, _ -> Groove.Kind.ARTIST }
                    ) { _, folderName ->
                        folders[folderName]?.let { folder ->
                            FolderTile(
                                context, folder = folder,
                                onClick = { onClick(folder) },
                            )
                        }
                    }
                }
            }

            if (showModifyLayoutSheet) {
                MediaLayoutAdjustDialog(
                    context,
                    layout = layout,
                    onLayoutChange = {
                        context.symphony.settings.lastUsedFoldersLayout.setValue(it)
                    },
                    columns = gridColumns,
                    onColumnsChange = {
                        context.symphony.settings.lastUsedFoldersHorizontalGridColumns.setValue(
                            it.horizontal
                        )
                        context.symphony.settings.lastUsedFoldersVerticalGridColumns.setValue(
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

/**
 * MAZIKA: the folder actions, lifted out of [FolderTile] so the list layout offers exactly
 * the same set - what you can do with a folder should not depend on how you are looking at
 * it.
 */
@Composable
internal fun FolderDropdownMenu(
    context: ViewContext,
    folder: SimpleFileSystem.Folder,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
) {
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
            },
            text = {
                Text(context.symphony.t.ShufflePlay)
            },
            onClick = {
                onDismissRequest()
                context.symphony.radio.shorty.playQueue(
                    folder.getSortedSongIds(context),
                    shuffle = true,
                    source = PlaySource.folder(folder.fullPath.pathString),
                )
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
            },
            text = {
                Text(context.symphony.t.PlayNext)
            },
            onClick = {
                onDismissRequest()
                context.symphony.radio.queue.add(
                    folder.getSortedSongIds(context),
                    context.symphony.radio.queue.currentSongIndex + 1
                )
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
            },
            text = {
                Text(context.symphony.t.AddToPlaylist)
            },
            onClick = {
                onDismissRequest()
                showAddToPlaylistDialog = true
            }
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            context,
            songIds = folder.getSortedSongIds(context),
            onDismissRequest = {
                showAddToPlaylistDialog = false
            }
        )
    }
}

@Composable
private fun FolderTile(
    context: ViewContext,
    folder: SimpleFileSystem.Folder,
    onClick: () -> Unit,
) {
    SquareGrooveTile(
        image = folder.createArtworkImageRequest(context).build(),
        options = { expanded, onDismissRequest ->
            FolderDropdownMenu(context, folder, expanded, onDismissRequest)
        },
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
            val sortedSongIds = folder.getSortedSongIds(context)
            context.symphony.radio.shorty.playQueue(
                sortedSongIds,
                source = PlaySource.folder(folder.fullPath.pathString),
            )
        },
        onClick = onClick,
    )
}

internal fun SimpleFileSystem.Folder.createArtworkImageRequest(context: ViewContext) =
    children.values
        .find { it is SimpleFileSystem.File }
        ?.let {
            val songId = (it as SimpleFileSystem.File).data as String
            context.symphony.groove.song.createArtworkImageRequest(songId)
        }
        ?: Assets.createPlaceholderImageRequest(context.symphony)

private fun SimpleFileSystem.Folder.getSortedSongIds(context: ViewContext): List<String> {
    val songIds = children.values.mapNotNull {
        when (it) {
            is SimpleFileSystem.File -> it.data as String
            else -> null
        }
    }
    return context.symphony.groove.song.sort(
        songIds,
        context.symphony.settings.lastUsedSongsSortBy.value,
        context.symphony.settings.lastUsedSongsSortReverse.value,
    )
}
