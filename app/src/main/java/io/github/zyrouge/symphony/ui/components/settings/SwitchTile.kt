package io.github.zyrouge.symphony.ui.components.settings

import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSwitchTile(
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    // MAZIKA: when disabled the tile is greyed out and non-interactive, but its
    // stored value is preserved so it is restored when re-enabled.
    enabled: Boolean = true,
    subtitle: (@Composable () -> Unit)? = null,
) {
    Card(
        colors = SettingsTileDefaults.cardColors(),
        enabled = enabled,
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
        onClick = {
            onChange(!value)
        }
    ) {
        ListItem(
            colors = SettingsTileDefaults.listItemColors(),
            leadingContent = { icon() },
            headlineContent = { title() },
            supportingContent = subtitle,
            trailingContent = {
                Switch(
                    enabled = enabled,
                    checked = value,
                    onCheckedChange = {
                        onChange(!value)
                    },
                )
            }
        )
    }
}
