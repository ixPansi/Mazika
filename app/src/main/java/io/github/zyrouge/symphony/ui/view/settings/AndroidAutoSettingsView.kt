package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.zyrouge.symphony.services.radio.AndroidAutoCategory
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.rememberReorderableState
import io.github.zyrouge.symphony.ui.components.reorderableHandle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
object AndroidAutoSettingsViewRoute

/**
 * MAZIKA: chooses which categories appear on the Android Auto root screen and in what
 * order, so the first thing shown in the car can be Playlists rather than Songs.
 *
 * Enabled categories are listed first and can be dragged; disabled ones sit below and
 * are appended to the end when switched back on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidAutoSettingsView(context: ViewContext) {
    val enabled by context.symphony.settings.androidAutoCategories.flow.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Local copy so dragging stays smooth; persisted once on release.
    val order = remember { mutableStateListOf<AndroidAutoCategory>() }
    LaunchedEffect(enabled) {
        order.clear()
        order.addAll(enabled)
    }
    val disabled = AndroidAutoCategory.entries.filter { it !in order }

    val persist = {
        context.symphony.settings.androidAutoCategories.setValue(order.toList())
    }
    val reorderState = rememberReorderableState(
        listState = listState,
        coroutineScope = coroutineScope,
        onMove = { from, to ->
            if (from in order.indices && to in order.indices) {
                order.add(to, order.removeAt(from))
            }
        },
        onSettle = { persist() },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("${context.symphony.t.Settings} - ${context.symphony.t.AndroidAuto}")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = { context.navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = { IconButtonPlaceholder() },
            )
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
            ) {
                LazyColumn(state = listState) {
                    item {
                        Column {
                            SettingsSideHeading(context.symphony.t.AndroidAutoCategories)
                            Text(
                                context.symphony.t.AndroidAutoCategoriesHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp),
                            )
                        }
                    }
                    itemsIndexed(
                        order,
                        key = { _, x -> "enabled-${x.name}" },
                    ) { i, category ->
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
                            CategoryRow(
                                label = category.label(context.symphony),
                                checked = true,
                                // Never let the last category be switched off - an empty
                                // root screen would look like a broken app in the car.
                                canDisable = order.size > 1,
                                dragHandle = {
                                    Icon(
                                        Icons.Filled.DragIndicator,
                                        context.symphony.t.Reorder,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .reorderableHandle(reorderState, i),
                                    )
                                },
                                onCheckedChange = {
                                    if (order.size > 1) {
                                        order.remove(category)
                                        persist()
                                    }
                                },
                            )
                        }
                    }
                    if (disabled.isNotEmpty()) {
                        item {
                            SettingsSideHeading(context.symphony.t.Disabled)
                        }
                        items@ itemsIndexed(
                            disabled,
                            key = { _, x -> "disabled-${x.name}" },
                        ) { _, category ->
                            CategoryRow(
                                label = category.label(context.symphony),
                                checked = false,
                                canDisable = true,
                                dragHandle = { Spacer(modifier = Modifier.width(20.dp)) },
                                onCheckedChange = {
                                    order.add(category)
                                    persist()
                                },
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun CategoryRow(
    label: String,
    checked: Boolean,
    canDisable: Boolean,
    dragHandle: @Composable () -> Unit,
    onCheckedChange: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) { dragHandle() }
        },
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = !checked || canDisable,
                onCheckedChange = { onCheckedChange() },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
