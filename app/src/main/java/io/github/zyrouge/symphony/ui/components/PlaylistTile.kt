package io.github.zyrouge.symphony.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.groove.MediaExposer
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.theme.ThemeColors
import io.github.zyrouge.symphony.ui.view.PlaylistViewRoute
import io.github.zyrouge.symphony.utils.Logger

@Composable
fun PlaylistTile(context: ViewContext, playlist: Playlist) {
    val updateId by context.symphony.groove.playlist.updateId.collectAsState()

    // MAZIKA: the caller resolves the Playlist with a plain repository lookup, which
    // is not a snapshot read, so its instance goes stale as soon as the playlist is
    // edited - which is why a newly set cover only appeared after an app restart.
    // Re-resolve here whenever the repository signals a change.
    val current = remember(updateId, playlist.id) {
        context.symphony.groove.playlist.get(playlist.id) ?: playlist
    }

    // A playlist with no cover of its own falls back to its first song's artwork, so a
    // custom song cover has to invalidate this tile too.
    val coverUpdateId by context.symphony.groove.song.customCoverUpdateId.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = {
            context.navController.navigate(PlaylistViewRoute(playlist.id))
        }
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    AsyncImage(
                        remember(updateId, current, coverUpdateId) {
                            current.createArtworkImageRequest(context.symphony).build()
                        },
                        null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp)
                    ) {
                        var showOptionsMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showOptionsMenu = !showOptionsMenu }
                        ) {
                            Icon(Icons.Filled.MoreVert, null)
                            PlaylistDropdownMenu(
                                context,
                                current,
                                expanded = showOptionsMenu,
                                onDismissRequest = {
                                    showOptionsMenu = false
                                }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    ) {
                        IconButton(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(12.dp)
                                )
                                .then(Modifier.size(36.dp)),
                            onClick = {
                                context.symphony.radio.shorty.playQueue(
                                    current.getSortedSongIds(context.symphony),
                                    source = PlaySource.playlist(current.id),
                                )
                            }
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun PlaylistDropdownMenu(
    context: ViewContext,
    playlist: Playlist,
    expanded: Boolean,
    onSongsChanged: (() -> Unit) = {},
    onRename: (() -> Unit) = {},
    onDelete: (() -> Unit) = {},
    onDismissRequest: () -> Unit,
) {
    val savePlaylistLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MediaExposer.MIMETYPE_M3U)
    ) { uri ->
        uri?.let { _ ->
            try {
                context.symphony.groove.playlist.savePlaylistToUri(playlist, uri)
                Toast.makeText(
                    context.activity,
                    context.symphony.t.ExportedX(playlist.title),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (err: Exception) {
                Logger.error("PlaylistTile", "export failed (activity result)", err)
                Toast.makeText(
                    context.activity,
                    context.symphony.t.ExportFailedX(
                        err.localizedMessage ?: err.toString()
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    var showSongsPicker by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // MAZIKA: custom playlist cover picker. Uses the modern Android photo picker,
    // which automatically falls back to the Storage Access Framework on devices
    // without it. The selected image is previewed and confirmed before it is saved.
    var pickedCoverUri by remember { mutableStateOf<Uri?>(null) }
    val coverPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedCoverUri = uri
        }
    }

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
                    playlist.getSortedSongIds(context.symphony),
                    shuffle = true,
                    source = PlaySource.playlist(playlist.id),
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
                    playlist.getSortedSongIds(context.symphony),
                    context.symphony.radio.queue.currentSongIndex + 1
                )
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
            },
            text = {
                Text(context.symphony.t.AddToQueue)
            },
            onClick = {
                onDismissRequest()
                context.symphony.radio.queue.add(playlist.getSongIds(context.symphony))
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
        if (playlist.isNotLocal) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
                },
                text = {
                    Text(context.symphony.t.ManageSongs)
                },
                onClick = {
                    onDismissRequest()
                    showSongsPicker = true
                }
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Filled.Info, null)
            },
            text = {
                Text(context.symphony.t.Details)
            },
            onClick = {
                onDismissRequest()
                showInfoDialog = true
            }
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Filled.Image, null)
            },
            text = {
                Text(context.symphony.t.ChangePlaylistCover)
            },
            onClick = {
                onDismissRequest()
                coverPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        )
        if (playlist.hasCustomCover) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.HideImage, null)
                },
                text = {
                    Text(context.symphony.t.RemoveCustomCover)
                },
                onClick = {
                    onDismissRequest()
                    context.symphony.groove.playlist.removeCustomCover(playlist)
                    Toast.makeText(
                        context.activity,
                        context.symphony.t.PlaylistCoverRemoved,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            )
        }
        if (playlist.isNotLocal) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Save, null)
                },
                text = {
                    Text(context.symphony.t.Export)
                },
                onClick = {
                    onDismissRequest()
                    try {
                        savePlaylistLauncher.launch("${playlist.title}.m3u")
                    } catch (err: Exception) {
                        Logger.error("PlaylistTile", "export failed", err)
                        Toast.makeText(
                            context.activity,
                            context.symphony.t.ExportFailedX(
                                err.localizedMessage ?: err.toString()
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Edit, null)
                },
                text = {
                    Text(context.symphony.t.Rename)
                },
                onClick = {
                    onDismissRequest()
                    showRenameDialog = true
                }
            )
        }
        if (!context.symphony.groove.playlist.isBuiltInPlaylist(playlist)) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        Icons.Filled.DeleteForever,
                        null,
                        tint = ThemeColors.Red,
                    )
                },
                text = {
                    Text(context.symphony.t.Delete)
                },
                onClick = {
                    onDismissRequest()
                    showDeleteDialog = true
                }
            )
        }
    }

    if (showInfoDialog) {
        PlaylistInformationDialog(
            context,
            playlist = playlist,
            onDismissRequest = {
                showInfoDialog = false
            }
        )
    }

    if (showSongsPicker) {
        PlaylistManageSongsDialog(
            context,
            selectedSongIds = playlist.getSongIds(context.symphony),
            onDone = {
                context.symphony.groove.playlist.update(playlist.id, it)
                onSongsChanged()
                showSongsPicker = false
            }
        )
    }

    if (showDeleteDialog) {
        ConfirmationDialog(
            context,
            title = {
                Text(context.symphony.t.DeletePlaylist)
            },
            description = {
                Text(context.symphony.t.AreYouSureThatYouWantToDeleteThisPlaylist)
            },
            onResult = { result ->
                showDeleteDialog = false
                if (result) {
                    onDelete()
                    context.symphony.groove.playlist.delete(playlist.id)
                }
            }
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            context,
            songIds = playlist.getSongIds(context.symphony),
            onDismissRequest = {
                showAddToPlaylistDialog = false
            }
        )
    }

    if (showRenameDialog) {
        RenamePlaylistDialog(
            context,
            playlist = playlist,
            onRename = onRename,
            onDismissRequest = {
                showRenameDialog = false
            }
        )
    }

    pickedCoverUri?.let { uri ->
        PlaylistCoverCropDialog(
            context,
            uri = uri,
            onDismissRequest = { pickedCoverUri = null },
            onConfirm = { crop ->
                pickedCoverUri = null
                context.symphony.groove.playlist.setCustomCover(playlist, uri, crop) { success ->
                    Toast.makeText(
                        context.activity,
                        if (success) context.symphony.t.PlaylistCoverUpdated
                        else context.symphony.t.UnableToSavePlaylistCover,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}
