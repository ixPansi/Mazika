package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.components.settings.SettingsSliderDialog
import io.github.zyrouge.symphony.ui.helpers.ViewContext

/** MAZIKA: how a browse tab draws its entries. */
enum class MediaLayout {
    GRID,
    LIST,
}

data class ResponsiveGridData(val columnsCount: Int)

data class ResponsiveGridColumns(val horizontal: Int, val vertical: Int) {
    internal fun calculateColumns(height: Int, width: Int): Int {
        val columns = when {
            height > width -> vertical
            else -> horizontal
        }
        val columnWidth = width / columns
        return when {
            columnWidth < MIN_GRID_WIDTH -> width / MIN_GRID_WIDTH
            else -> columns
        }
    }

    companion object {
        const val MIN_GRID_WIDTH = 75
        const val DEFAULT_HORIZONTAL_COLUMNS = 4
        const val DEFAULT_VERTICAL_COLUMNS = 2
    }
}

/**
 * [state] is hoisted so a caller can share it with a reorder gesture, which has to read the
 * same layout info the grid is drawing from.
 */
@Composable
fun ResponsiveGrid(
    columns: ResponsiveGridColumns,
    state: LazyGridState? = null,
    modifier: Modifier = Modifier,
    content: LazyGridScope.(ResponsiveGridData) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val effectiveColumn = columns.calculateColumns(
            this@BoxWithConstraints.maxHeight.value.toInt(),
            this@BoxWithConstraints.maxWidth.value.toInt(),
        )
        val gridState = state ?: rememberLazyGridState()
        val responsiveGridData = ResponsiveGridData(columnsCount = effectiveColumn)

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(effectiveColumn),
            modifier = modifier.drawScrollBar(gridState, effectiveColumn)
        ) {
            content(responsiveGridData)
        }
    }
}

/**
 * MAZIKA: picks grid or list, and the column count when it applies.
 *
 * The column slider is meaningless in list layout, so it is only offered for a grid. The
 * layout choice applies as soon as it is tapped - it is one setting and the effect is
 * visible behind the dialog - while the slider keeps the existing commit-on-Done contract
 * of [SettingsSliderDialog], because dragging it would otherwise relayout on every frame.
 */
@Composable
fun MediaLayoutAdjustDialog(
    context: ViewContext,
    layout: MediaLayout,
    onLayoutChange: (MediaLayout) -> Unit,
    columns: ResponsiveGridColumns,
    onColumnsChange: (ResponsiveGridColumns) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val isVertical = LocalConfiguration.current.run { screenHeightDp > screenWidthDp }
    val maxWidth = LocalConfiguration.current.screenWidthDp
    val maxColumns = maxWidth / ResponsiveGridColumns.MIN_GRID_WIDTH
    val effectiveColumns = when {
        isVertical -> columns.vertical
        else -> columns.horizontal
    }
    var value by remember(effectiveColumns) { mutableFloatStateOf(effectiveColumns.toFloat()) }

    ScaffoldDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(context.symphony.t.Layout)
        },
        content = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MediaLayout.entries.forEach { entry ->
                    val onClick = { onLayoutChange(entry) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        RadioButton(selected = entry == layout, onClick = onClick)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            when (entry) {
                                MediaLayout.GRID -> Icons.Filled.GridView
                                MediaLayout.LIST -> Icons.AutoMirrored.Filled.ViewList
                            },
                            null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            when (entry) {
                                MediaLayout.GRID -> context.symphony.t.Grid
                                MediaLayout.LIST -> context.symphony.t.List
                            }
                        )
                    }
                }
                if (layout == MediaLayout.GRID) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        context.symphony.t.GridColumns,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Slider(
                        value = value,
                        onChange = { value = it.toInt().toFloat() },
                        range = 1f..maxColumns.toFloat(),
                        label = { Text(it.toInt().toString()) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        actions = {
            TextButton(
                onClick = {
                    if (layout == MediaLayout.GRID) {
                        onColumnsChange(
                            when {
                                isVertical -> columns.copy(vertical = value.toInt())
                                else -> columns.copy(horizontal = value.toInt())
                            }
                        )
                    }
                    onDismissRequest()
                }
            ) {
                Text(context.symphony.t.Done)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsiveGridSizeAdjustBottomSheet(
    context: ViewContext,
    columns: ResponsiveGridColumns,
    onColumnsChange: (ResponsiveGridColumns) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val isVertical = LocalConfiguration.current.run { screenHeightDp > screenWidthDp }
    val maxWidth = LocalConfiguration.current.screenWidthDp
    val maxColumns = maxWidth / ResponsiveGridColumns.MIN_GRID_WIDTH
    val effectiveColumns by remember(isVertical, columns) {
        derivedStateOf {
            when {
                isVertical -> columns.vertical
                else -> columns.horizontal
            }
        }
    }

    SettingsSliderDialog(
        context,
        title = {
            Text(context.symphony.t.GridColumns)
        },
        initialValue = effectiveColumns.toFloat(),
        range = 1f..maxColumns.toFloat(),
        label = {
            Text(it.toInt().toString())
        },
        onValue = {
            it.toInt().toFloat()
        },
        onChange = {
            val nColumns = when {
                isVertical -> columns.copy(vertical = it.toInt())
                else -> columns.copy(horizontal = it.toInt())
            }
            onColumnsChange(nColumns)
        },
        onDismissRequest = onDismissRequest,
    )
}
