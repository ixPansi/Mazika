package io.github.zyrouge.symphony.ui.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.ui.components.AnimatedNowPlayingBottomBar
import io.github.zyrouge.symphony.ui.components.IconTextBody
import io.github.zyrouge.symphony.ui.components.PlaylistDropdownMenu
import io.github.zyrouge.symphony.ui.components.SongList
import io.github.zyrouge.symphony.ui.components.SongListType
import io.github.zyrouge.symphony.ui.components.SongSelectionTopAppBar
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.helpers.rememberSongSelectionState
import io.github.zyrouge.symphony.ui.theme.ThemeColors
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistViewRoute(val playlistId: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistView(context: ViewContext, route: PlaylistViewRoute) {
    val allPlaylistIds by context.symphony.groove.playlist.all.collectAsState()
    val updateId by context.symphony.groove.playlist.updateId.collectAsState()
    var updateCounter by remember { mutableIntStateOf(0) }
    val playlist by remember(route.playlistId, updateId) {
        derivedStateOf { context.symphony.groove.playlist.get(route.playlistId) }
    }
    val songIds by remember(playlist) {
        derivedStateOf { playlist?.getSongIds(context.symphony) ?: emptyList() }
    }
    val isViable by remember(allPlaylistIds, route.playlistId) {
        derivedStateOf { allPlaylistIds.contains(route.playlistId) }
    }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var isReordering by remember(route.playlistId) { mutableStateOf(false) }
    val isFavoritesPlaylist by remember(playlist) {
        derivedStateOf {
            playlist?.let { context.symphony.groove.playlist.isFavoritesPlaylist(it) } == true
        }
    }

    val incrementUpdateCounter = {
        updateCounter = if (updateCounter > 25) 0 else updateCounter + 1
    }

    val selection = rememberSongSelectionState()
    // Back leaves the selection before it leaves the screen.
    BackHandler(enabled = selection.isActive) { selection.clear() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            when {
                selection.isActive -> SongSelectionTopAppBar(
                    context,
                    selection = selection,
                    // Removing is by source index, not song id: a playlist may hold the
                    // same song twice and only the picked copy should go.
                    extraActions = { _, sourceIndices, onDismissRequest ->
                        playlist?.takeIf { it.isNotLocal }?.let { target ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.DeleteForever,
                                        null,
                                        tint = ThemeColors.Red,
                                    )
                                },
                                text = {
                                    Text(context.symphony.t.RemoveFromPlaylist)
                                },
                                onClick = {
                                    onDismissRequest()
                                    val drop = sourceIndices.toSet()
                                    context.symphony.groove.playlist.update(
                                        target.id,
                                        songIds.filterIndexed { i, _ -> i !in drop },
                                    )
                                    selection.clear()
                                    incrementUpdateCounter()
                                },
                            )
                        }
                    },
                )

                else -> CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { context.navController.popBackStack() }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    },
                    title = {
                        TopAppBarMinimalTitle {
                            Text(
                                context.symphony.t.Playlist
                                        + (playlist?.let { " - ${it.title}" } ?: "")
                            )
                        }
                    },
                    actions = {
                        if (isViable) {
                            IconButton(
                                onClick = {
                                    showOptionsMenu = true
                                }
                            ) {
                                Icon(Icons.Filled.MoreVert, null)
                                PlaylistDropdownMenu(
                                    context,
                                    playlist!!,
                                    expanded = showOptionsMenu,
                                    includeShufflePlay = false,
                                    onSongsChanged = {
                                        incrementUpdateCounter()
                                    },
                                    onRename = {
                                        incrementUpdateCounter()
                                    },
                                    onDelete = {
                                        context.navController.popBackStack()
                                    },
                                    // MAZIKA: reordering claims the long press on this
                                    // screen, so selection is reached from the menu.
                                    onSelect = {
                                        showOptionsMenu = false
                                        selection.startEmpty()
                                    },
                                    onDismissRequest = {
                                        showOptionsMenu = false
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                )
            }
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
            ) {
                when {
                    isViable -> SongList(
                        context,
                        songIds = songIds,
                        selection = selection,
                        trailingContent = {
                            item { Spacer(modifier = Modifier.height(72.dp)) }
                        },
                        type = SongListType.Playlist,
                        disableHeartIcon = isFavoritesPlaylist,
                        showShufflePlay = false,
                        playSource = PlaySource.playlist(route.playlistId),
                        // MAZIKA: long-press a row to reorder; persisted on release.
                        onReorder = if (playlist?.isNotLocal == true) {
                            { orderedSongIds ->
                                playlist?.let {
                                    context.symphony.groove.playlist.update(it.id, orderedSongIds)
                                    incrementUpdateCounter()
                                }
                            }
                        } else {
                            null
                        },
                        onReorderStateChange = { isReordering = it },
                        trailingOptionsContent = { _, sourceIndex, song, onDismissRequest ->
                            playlist?.takeIf { it.isNotLocal }?.let {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.DeleteForever,
                                            null,
                                            tint = ThemeColors.Red,
                                        )
                                    },
                                    text = {
                                        Text(context.symphony.t.RemoveFromPlaylist)
                                    },
                                    onClick = {
                                        onDismissRequest()
                                        if (sourceIndex in songIds.indices) {
                                            context.symphony.groove.playlist.update(
                                                it.id,
                                                songIds.toMutableList().apply {
                                                    removeAt(sourceIndex)
                                                },
                                            )
                                        }
                                    }
                                )
                            }
                        },
                    )

                    else -> UnknownPlaylist(context, route.playlistId)
                }
            }
        },
        floatingActionButton = {
            playlist?.takeIf { isViable }?.let { currentPlaylist ->
                PlaylistPlaybackControls(
                    context = context,
                    enabled = songIds.isNotEmpty() && !isReordering,
                    onPlayAll = {
                        context.symphony.radio.shorty.playQueue(
                            currentPlaylist.getSortedSongIds(context.symphony),
                            source = PlaySource.playlist(currentPlaylist.id),
                        )
                    },
                    onShuffle = {
                        context.symphony.radio.shorty.playQueue(
                            currentPlaylist.getSortedSongIds(context.symphony),
                            shuffle = true,
                            source = PlaySource.playlist(currentPlaylist.id),
                        )
                    },
                )
            }
        },
        bottomBar = {
            AnimatedNowPlayingBottomBar(context)
        }
    )
}

@Composable
private fun PlaylistPlaybackControls(
    context: ViewContext,
    enabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier.graphicsLayer {
            alpha = if (enabled) 1f else 0.42f
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onShuffle,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 1.dp,
            shadowElevation = 3.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    context.symphony.t.ShufflePlay,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Surface(
            onClick = onPlayAll,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.86f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 1.dp,
            shadowElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    context.symphony.t.PlayAll,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun UnknownPlaylist(context: ViewContext, playlistId: String) {
    IconTextBody(
        icon = { modifier ->
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                null,
                modifier = modifier
            )
        },
        content = {
            Text(context.symphony.t.UnknownPlaylistX(playlistId))
        }
    )
}
