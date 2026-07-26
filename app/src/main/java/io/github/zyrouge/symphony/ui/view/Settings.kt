package io.github.zyrouge.symphony.ui.view

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.R
import io.github.zyrouge.symphony.services.AppMeta
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.ScaffoldDialog
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.settings.AndroidAutoSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.AppearanceSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.GrooveSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.HomePageSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.MiniPlayerSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.NowPlayingSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.PlayerSettingsViewRoute
import io.github.zyrouge.symphony.ui.view.settings.UpdateSettingsViewRoute
import io.github.zyrouge.symphony.utils.ActivityUtils
import kotlinx.serialization.Serializable

@Serializable
data class SettingsViewRoute(val initialElement: String? = null) {
    companion object {
        const val ELEMENT_MEDIA_FOLDERS = "media_folders"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(context: ViewContext, route: SettingsViewRoute) {
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()
    var showAboutDialog by remember { mutableStateOf(false) }
    val updateState by AppMeta.updateState.collectAsState()
    val availableRelease = if (AppMeta.canCheckForUpdates) {
        (updateState as? AppMeta.UpdateState.Available)?.release
    } else {
        null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text(context.symphony.t.Settings)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            context.navController.popBackStack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButtonPlaceholder()
                },
            )
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
            ) {
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size((configuration.smallestScreenWidthDp * 0.25).dp)) {
                            // MAZIKA: the in-app logo is tinted with the active theme
                            // colour, so it follows whichever theme is selected.
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_monochrome),
                                contentDescription = AppMeta.appName,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Column {
                            Text(AppMeta.appName, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(AppMeta.version, style = MaterialTheme.typography.labelMedium)
                            availableRelease?.let { release ->
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    context.symphony.t.NewVersionAvailableX(release.tag),
                                    modifier = Modifier.clickable {
                                        ActivityUtils.startBrowserActivity(
                                            context.activity,
                                            Uri.parse(release.htmlUrl),
                                        )
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.LibraryMusic, null)
                        },
                        title = {
                            Text(context.symphony.t.Groove)
                        },
                        onClick = {
                            context.navController.navigate(
                                GrooveSettingsViewRoute(route.initialElement)
                            )
                        },
                    )
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.Radio, null)
                        },
                        title = {
                            Text(context.symphony.t.Player)
                        },
                        onClick = {
                            context.navController.navigate(PlayerSettingsViewRoute)
                        },
                    )
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.Palette, null)
                        },
                        title = {
                            Text(context.symphony.t.Appearance)
                        },
                        onClick = {
                            context.navController.navigate(AppearanceSettingsViewRoute)
                        },
                    )
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.Home, null)
                        },
                        title = {
                            Text(context.symphony.t.Home)
                        },
                        onClick = {
                            context.navController.navigate(HomePageSettingsViewRoute)
                        }
                    )
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.DirectionsCar, null)
                        },
                        title = {
                            Text(context.symphony.t.AndroidAuto)
                        },
                        onClick = {
                            context.navController.navigate(AndroidAutoSettingsViewRoute)
                        },
                    )
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.MusicNote, null)
                        },
                        title = {
                            Text(context.symphony.t.MiniPlayer)
                        },
                        onClick = {
                            context.navController.navigate(MiniPlayerSettingsViewRoute)
                        },
                    )
                    HorizontalDivider()
                    SettingsSimpleTile(
                        icon = {
                            Icon(Icons.Filled.MusicNote, null)
                        },
                        title = {
                            Text(context.symphony.t.NowPlaying)
                        },
                        onClick = {
                            context.navController.navigate(NowPlayingSettingsViewRoute)
                        },
                    )
                    if (AppMeta.canCheckForUpdates) {
                        HorizontalDivider()
                        SettingsSimpleTile(
                            icon = {
                                Icon(Icons.Filled.Update, null)
                            },
                            title = {
                                Text(context.symphony.t.Updates)
                            },
                            onClick = {
                                context.navController.navigate(UpdateSettingsViewRoute)
                            },
                        )
                    }
                    // MAZIKA is a fork of Symphony and ships under the AGPL-3.0, which
                    // requires the licence terms and the absence of warranty to be
                    // discoverable from the app itself, not just the repository. The
                    // About chip carries that notice.
                    HorizontalDivider()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        SettingsChip(
                            icon = Icons.Filled.Info,
                            label = context.symphony.t.About,
                        ) {
                            showAboutDialog = true
                        }
                        LinkChip(
                            context,
                            icon = Icons.Filled.Code,
                            label = context.symphony.t.SourceCode,
                            url = AppMeta.sourceCodeUrl,
                        )
                        LinkChip(
                            context,
                            icon = Icons.Filled.Gavel,
                            label = context.symphony.t.License,
                            url = AppMeta.licenseUrl,
                        )
                    }
                }

                if (showAboutDialog) {
                    ScaffoldDialog(
                        title = {
                            Text(context.symphony.t.About)
                        },
                        content = {
                            Box(modifier = Modifier.padding(20.dp, 16.dp)) {
                                Text(
                                    context.symphony.t.AboutNotice,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = { showAboutDialog = false }) {
                                Text(context.symphony.t.Done)
                            }
                        },
                        onDismissRequest = {
                            showAboutDialog = false
                        },
                    )
                }
            }
        }
    )
}

// MAZIKA: restored from upstream Symphony, where the About links were compact chips
// rather than full-width tiles printing their own URL.
@Composable
private fun SettingsChip(
    icon: ImageVector? = null,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(6.dp, 4.dp),
    ) {
        icon?.let {
            Icon(
                it,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LinkChip(
    context: ViewContext,
    icon: ImageVector? = null,
    label: String,
    url: String,
) = SettingsChip(icon, label) {
    ActivityUtils.startBrowserActivity(context.activity, Uri.parse(url))
}
