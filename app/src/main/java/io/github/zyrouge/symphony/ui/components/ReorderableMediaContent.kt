package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * MAZIKA: the body of a browse tab - grid or list, draggable or not.
 *
 * Every browse tab needs the same six things: a grid, a list, a reorder engine for each, the
 * item modifiers that animate them, and the rule that a drag only writes one move. Six tabs
 * hand-wiring that is six chances for them to drift apart, which is exactly how the grid
 * ended up feeling different from the list. It lives here once instead.
 *
 * [ids] must already be sorted for display. A completed drag hands back the whole new order,
 * flipped for a reversed sort so what the user built on screen is what gets stored.
 *
 * When [canReorder] is false no gesture detector is installed at all, so a long press stays
 * available to whatever the tiles want to do with it.
 */
@Composable
fun ReorderableMediaContent(
    ids: List<String>,
    canReorder: Boolean,
    layout: MediaLayout,
    gridColumns: ResponsiveGridColumns,
    sortReverse: Boolean,
    sourceVersion: Any?,
    contentType: Any?,
    onReorder: (List<String>) -> Unit,
    listItem: @Composable (id: String) -> Unit,
    gridItem: @Composable (index: Int, id: String, gridData: ResponsiveGridData) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val onMove: (Int, Int) -> Unit = { from, to ->
        if (canReorder && from in ids.indices && to in ids.indices) {
            onReorder(ids.movedItem(from, to).toStoredCustomOrder(sortReverse))
        }
    }
    // The ids are unique within a tab, so they are their own reorder keys - no
    // ReorderableEntry needed here, unlike a playlist's songs where one song can appear
    // twice and identity has to survive that.
    val itemKeys: () -> List<Any> = { if (canReorder) ids else emptyList() }
    val currentVersion: () -> Any? = { sourceVersion }

    val gridReorderState = rememberReorderableGridState(
        gridState = gridState,
        itemKeys = itemKeys,
        sourceVersion = currentVersion,
        onMove = onMove,
    )
    val listReorderState = rememberReorderableState(
        listState = listState,
        itemKeys = itemKeys,
        sourceVersion = currentVersion,
        onMove = onMove,
    )

    when (layout) {
        MediaLayout.LIST -> {
            val content = @Composable {
                LazyColumn(state = listState) {
                    items(ids, key = { it }, contentType = { contentType }) { id ->
                        Column(
                            modifier = reorderableItemModifier(
                                listReorderState,
                                id,
                                enabled = canReorder,
                            )
                        ) {
                            listItem(id)
                        }
                    }
                }
            }
            when {
                canReorder -> ReorderableContainer(
                    state = listReorderState,
                    modifier = Modifier.fillMaxSize(),
                    draggedItem = { index ->
                        ids.getOrNull(index)?.let { listItem(it) }
                    },
                ) { content() }

                else -> content()
            }
        }

        MediaLayout.GRID -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The same column count ResponsiveGrid derives internally, from the same
            // constraints. A cell may need it - GenreGrid pads by column position - and the
            // dragged copy is rendered outside the grid, where the scope is out of reach.
            val gridData = ResponsiveGridData(
                columnsCount = gridColumns.calculateColumns(
                    this@BoxWithConstraints.maxHeight.value.toInt(),
                    this@BoxWithConstraints.maxWidth.value.toInt(),
                )
            )
            val content = @Composable {
                ResponsiveGrid(gridColumns, state = gridState) {
                    itemsIndexed(
                        ids,
                        key = { _, x -> x },
                        contentType = { _, _ -> contentType },
                    ) { index, id ->
                        Column(
                            modifier = reorderableGridItemModifier(
                                gridReorderState,
                                id,
                                enabled = canReorder,
                            )
                        ) {
                            gridItem(index, id, gridData)
                        }
                    }
                }
            }
            when {
                canReorder -> ReorderableGridContainer(
                    state = gridReorderState,
                    modifier = Modifier.fillMaxSize(),
                    draggedItem = { index ->
                        ids.getOrNull(index)?.let { gridItem(index, it, gridData) }
                    },
                ) { content() }

                else -> content()
            }
        }
    }
}
