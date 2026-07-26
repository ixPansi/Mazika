package io.github.zyrouge.symphony.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.zyrouge.symphony.ui.helpers.SongSelectionState
import io.github.zyrouge.symphony.ui.helpers.ViewContext

/**
 * MAZIKA: the app bar shown while songs are selected.
 *
 * Screens swap this in for their usual bar while [SongSelectionState.isActive]. It takes
 * the list's [entries] so it can resolve selected uids to song ids - and, for
 * [extraActions] such as removing from a playlist, to indices into the caller's own list.
 *
 * Actions that operate on a plain list of song ids live in the overflow menu, matching
 * [GenericSongListDropdown]; only selecting-all is frequent enough to earn an icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSelectionTopAppBar(
    context: ViewContext,
    selection: SongSelectionState,
    entries: List<ReorderableEntry<String>> = selection.entries,
    extraActions: (@Composable ColumnScope.(List<String>, List<Int>, () -> Unit) -> Unit)? = null,
) {
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var pickedCoverUri by remember { mutableStateOf<Uri?>(null) }
    val coverPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pickedCoverUri = uri }

    val selectedSongIds = selection.songIds(entries)
    val selectedIndices = selection.sourceIndices(entries)
    val favoriteSongIds by context.symphony.groove.playlist.favorites.collectAsState()
    val allFavorited = selectedSongIds.isNotEmpty() &&
            selectedSongIds.all(favoriteSongIds::contains)

    CenterAlignedTopAppBar(
        title = {
            TopAppBarMinimalTitle {
                Text(context.symphony.t.XSelected(selection.count.toString()))
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent
        ),
        navigationIcon = {
            IconButton(onClick = { selection.clear() }) {
                Icon(Icons.Filled.Close, null)
            }
        },
        actions = {
            IconButton(
                onClick = { selection.toggleAll(entries.map { it.uid }) }
            ) {
                Icon(Icons.Filled.DoneAll, null)
            }
            IconButton(onClick = { showOptionsMenu = true }) {
                Icon(Icons.Filled.MoreVert, null)
                DropdownMenu(
                    expanded = showOptionsMenu,
                    onDismissRequest = { showOptionsMenu = false },
                ) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                        text = { Text(context.symphony.t.Play) },
                        onClick = {
                            showOptionsMenu = false
                            context.symphony.radio.shorty.playQueue(selectedSongIds)
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.Shuffle, null) },
                        text = { Text(context.symphony.t.ShufflePlay) },
                        onClick = {
                            showOptionsMenu = false
                            context.symphony.radio.shorty
                                .playQueue(selectedSongIds, shuffle = true)
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                        text = { Text(context.symphony.t.PlayNext) },
                        onClick = {
                            showOptionsMenu = false
                            context.symphony.radio.queue.add(
                                selectedSongIds,
                                context.symphony.radio.queue.currentSongIndex + 1,
                            )
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                        text = { Text(context.symphony.t.AddToQueue) },
                        onClick = {
                            showOptionsMenu = false
                            context.symphony.radio.queue.add(selectedSongIds)
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        text = { Text(context.symphony.t.AddToPlaylist) },
                        onClick = {
                            showOptionsMenu = false
                            showAddToPlaylistDialog = true
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                when {
                                    allFavorited -> Icons.Filled.Favorite
                                    else -> Icons.Filled.FavoriteBorder
                                },
                                null,
                            )
                        },
                        text = {
                            Text(
                                when {
                                    allFavorited -> context.symphony.t.Unfavorite
                                    else -> context.symphony.t.Favorite
                                }
                            )
                        },
                        onClick = {
                            showOptionsMenu = false
                            context.symphony.groove.playlist.run {
                                when {
                                    allFavorited -> unfavorite(selectedSongIds)
                                    else -> favorite(selectedSongIds)
                                }
                            }
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.Image, null) },
                        text = { Text(context.symphony.t.ChangeCover) },
                        onClick = {
                            showOptionsMenu = false
                            coverPickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.HideImage, null) },
                        text = { Text(context.symphony.t.RemoveCustomCover) },
                        onClick = {
                            showOptionsMenu = false
                            context.symphony.groove.song.removeCustomCover(selectedSongIds)
                            selection.clear()
                        },
                    )
                    extraActions?.invoke(this, selectedSongIds, selectedIndices) {
                        showOptionsMenu = false
                    }
                }
            }
        },
    )

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            context,
            songIds = selectedSongIds,
            onDismissRequest = {
                showAddToPlaylistDialog = false
                selection.clear()
            },
        )
    }

    pickedCoverUri?.let { uri ->
        val targets = selectedSongIds
        PlaylistCoverCropDialog(
            context,
            uri = uri,
            onDismissRequest = { pickedCoverUri = null },
            onConfirm = { crop ->
                pickedCoverUri = null
                context.symphony.groove.song.setCustomCover(targets, uri, crop) { applied ->
                    Toast.makeText(
                        context.symphony.applicationContext,
                        when {
                            applied > 0 -> context.symphony.t.XCoversUpdated(applied.toString())
                            else -> context.symphony.t.UnableToSavePlaylistCover
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                selection.clear()
            },
        )
    }
}
