package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.radio.AndroidAutoCategory
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.ReorderableContainer
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.movedItem
import io.github.zyrouge.symphony.ui.components.rememberReorderableState
import io.github.zyrouge.symphony.ui.components.reorderableItemModifier
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.serialization.Serializable

@Serializable
object AndroidAutoSettingsViewRoute

/**
 * Lazy-list key for an enabled category row. Shared by the `key =` lambda and the
 * reorder modifiers so the two cannot disagree about a row's identity — which is what
 * the drag now tracks rows by.
 */
private fun enabledRowKey(category: AndroidAutoCategory) = "enabled-${category.name}"

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

    val disabled = AndroidAutoCategory.entries.filter { it !in enabled }

    val persist: (List<AndroidAutoCategory>) -> Unit = {
        context.symphony.settings.androidAutoCategories.setValue(it)
    }
    val enabledKeys = enabled.map(::enabledRowKey)
    val reorderState = rememberReorderableState(
        listState = listState,
        itemKeys = { enabledKeys },
        // The list emits a heading item before the rows, so data index 0 is lazy index 1.
        firstItemIndex = { 1 },
        sourceVersion = { enabled },
        onMove = { from, to ->
            if (from in enabled.indices && to in enabled.indices) {
                persist(enabled.movedItem(from, to))
            }
        },
    )

    val enabledRow: @Composable (Int) -> Unit = { index ->
        enabled.getOrNull(index)?.let { category ->
            CategoryRow(
                label = category.label(context.symphony),
                checked = true,
                // Never let the last category be switched off - an empty root screen
                // would look like a broken app in the car.
                canDisable = enabled.size > 1,
                onCheckedChange = {
                    if (enabled.size > 1) {
                        persist(enabled.filterNot { it == category })
                    }
                },
            )
        }
    }

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
                ReorderableContainer(
                    state = reorderState,
                    modifier = Modifier.fillMaxSize(),
                    draggedItem = enabledRow,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
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
                            enabled,
                            key = { _, category -> enabledRowKey(category) },
                        ) { index, category ->
                            Box(
                                modifier = reorderableItemModifier(
                                    reorderState,
                                    enabledRowKey(category),
                                )
                            ) {
                                enabledRow(index)
                            }
                        }
                        if (disabled.isNotEmpty()) {
                            item {
                                SettingsSideHeading(context.symphony.t.Disabled)
                            }
                            items@ itemsIndexed(
                                disabled,
                                key = { _, category -> "disabled-${category.name}" },
                            ) { _, category ->
                                CategoryRow(
                                    label = category.label(context.symphony),
                                    checked = false,
                                    canDisable = true,
                                    onCheckedChange = {
                                        persist(enabled + category)
                                    },
                                )
                            }
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
    onCheckedChange: () -> Unit,
) {
    ListItem(
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
