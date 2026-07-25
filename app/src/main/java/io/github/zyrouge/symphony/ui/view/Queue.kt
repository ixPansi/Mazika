package io.github.zyrouge.symphony.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholderSize
import io.github.zyrouge.symphony.ui.components.NewPlaylistDialog
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import io.github.zyrouge.symphony.ui.components.rememberReorderableState
import io.github.zyrouge.symphony.ui.components.reorderableHandle
import io.github.zyrouge.symphony.ui.components.ReorderableEntry
import io.github.zyrouge.symphony.ui.components.toReorderableEntries
import io.github.zyrouge.symphony.ui.components.SongCard
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.nowPlaying.NothingPlayingBody
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object QueueViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueView(context: ViewContext) {
    val coroutineScope = rememberCoroutineScope()
    val queue by context.symphony.radio.observatory.queue.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val selectedSongIndices = remember { mutableStateListOf<Int>() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = queueIndex,
    )
    var showSaveDialog by remember { mutableStateOf(false) }

    // MAZIKA: drag-to-reorder. The list is reordered in this local copy while the
    // finger is down so dragging stays smooth, and the queue is only rewritten once
    // on release - reordering never restarts or changes the playing song.
    val reorderableQueue = remember { mutableStateListOf<ReorderableEntry<String>>() }
    LaunchedEffect(queue) {
        reorderableQueue.clear()
        reorderableQueue.addAll(queue.toReorderableEntries())
    }
    // Only one item moves during a drag; everything else just shifts. So the whole
    // gesture collapses to a single move from where it started to where it ended,
    // which is what gets applied to the real queue on release.
    var pendingMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val reorderState = rememberReorderableState(
        listState = listState,
        coroutineScope = coroutineScope,
        onMove = { from, to ->
            if (from in reorderableQueue.indices && to in reorderableQueue.indices) {
                reorderableQueue.add(to, reorderableQueue.removeAt(from))
                pendingMove = (pendingMove?.first ?: from) to to
            }
        },
        onSettle = {
            pendingMove?.let { (from, to) ->
                if (from != to) {
                    context.symphony.radio.queue.move(from, to)
                }
            }
            pendingMove = null
        },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle(
                        modifier = Modifier.padding(start = IconButtonPlaceholderSize)
                    ) {
                        Text(context.symphony.t.Queue)
                    }
                },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            context.navController.popBackStack()
                        }
                    ) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            null,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                actions = {
                    when {
                        selectedSongIndices.isNotEmpty() -> IconButton(
                            onClick = {
                                context.symphony.radio.queue.remove(selectedSongIndices.toList())
                                selectedSongIndices.clear()
                            }
                        ) {
                            Icon(Icons.Filled.Delete, null)
                        }

                        else -> IconButton(
                            onClick = {
                                showSaveDialog = !showSaveDialog
                            }
                        ) {
                            Icon(Icons.Default.Save, null)
                        }
                    }

                    IconButton(
                        onClick = {
                            context.symphony.radio.stop()
                            selectedSongIndices.clear()
                        }
                    ) {
                        Icon(Icons.Filled.ClearAll, null)
                    }
                }
            )
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
            ) {
                if (queue.isEmpty()) {
                    NothingPlayingBody(context)
                } else {
                    LazyColumn(state = listState) {
                        itemsIndexed(
                            reorderableQueue,
                            key = { _, entry -> entry.uid },
                            contentType = { _, _ -> Groove.Kind.SONG },
                        ) { i, entry ->
                            context.symphony.groove.song.get(entry.value)?.let { song ->
                                Box(
                                    modifier = Modifier
                                        .zIndex(if (reorderState.draggingIndex == i) 1f else 0f)
                                        .graphicsLayer {
                                            if (reorderState.draggingIndex == i) {
                                                translationY = reorderState.draggingOffset
                                                shadowElevation = 8f
                                            }
                                        }
                                ) {
                                    SongCard(
                                        context,
                                        song,
                                        autoHighlight = false,
                                        highlighted = i == queueIndex,
                                        leading = {
                                            Checkbox(
                                                checked = selectedSongIndices.contains(i),
                                                onCheckedChange = {
                                                    if (selectedSongIndices.contains(i)) {
                                                        selectedSongIndices.remove(i)
                                                    } else {
                                                        selectedSongIndices.add(i)
                                                    }
                                                },
                                                modifier = Modifier.offset((-4).dp)
                                            )
                                            // Drag handle: press and drag to reorder.
                                            // A dedicated handle keeps tap-to-play working.
                                            Icon(
                                                Icons.Filled.DragIndicator,
                                                context.symphony.t.Reorder,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .reorderableHandle(reorderState, i),
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        },
                                        thumbnailLabel = {
                                            Text((i + 1).toString())
                                        },
                                        onClick = {
                                            context.symphony.radio.jumpTo(i)
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(i)
                                            }
                                        },
                                    )
                                    if (i < queueIndex) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(
                                                    MaterialTheme.colorScheme.background.copy(alpha = 0.3f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    if (showSaveDialog) {
        NewPlaylistDialog(
            context,
            initialSongIds = queue.toList(),
            onDone = { playlist ->
                showSaveDialog = false
                context.symphony.groove.playlist.add(playlist)
            },
            onDismissRequest = {
                showSaveDialog = false
            }
        )
    }
}
