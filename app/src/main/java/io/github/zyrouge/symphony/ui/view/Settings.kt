package io.github.zyrouge.symphony.ui.view

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.R
import io.github.zyrouge.symphony.services.AppMeta
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsLinkTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
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
                    // discoverable from the app itself, not just the repository.
                    HorizontalDivider()
                    SettingsSideHeading(context.symphony.t.About)
                    Text(
                        context.symphony.t.AboutNotice,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 8.dp,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SettingsLinkTile(
                        context,
                        icon = {
                            Icon(Icons.Filled.Code, null)
                        },
                        title = {
                            Text(context.symphony.t.SourceCode)
                        },
                        url = AppMeta.sourceCodeUrl,
                    )
                    SettingsLinkTile(
                        context,
                        icon = {
                            Icon(Icons.Filled.Gavel, null)
                        },
                        title = {
                            Text(context.symphony.t.License)
                        },
                        url = AppMeta.licenseUrl,
                    )
                }
            }
        }
    )
}
