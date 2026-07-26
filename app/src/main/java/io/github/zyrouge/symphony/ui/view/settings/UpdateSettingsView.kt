package io.github.zyrouge.symphony.ui.view.settings

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.zyrouge.symphony.services.AppMeta
import io.github.zyrouge.symphony.ui.components.AdaptiveSnackbar
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsSwitchTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.ActivityUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object UpdateSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsView(context: ViewContext) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val checkForUpdates by context.symphony.settings.checkForUpdates.flow.collectAsState()
    val showUpdateToast by context.symphony.settings.showUpdateToast.flow.collectAsState()
    val lastUpdateCheck by context.symphony.settings.lastUpdateCheck.flow.collectAsState()
    val updateState by AppMeta.updateState.collectAsState()
    val availableRelease = if (AppMeta.canCheckForUpdates) {
        (updateState as? AppMeta.UpdateState.Available)?.release
    } else {
        null
    }
    // MAZIKA: only report an outcome for a check the user started from this screen,
    // so the automatic one at startup does not pop a snackbar behind their back.
    var manualCheckRequested by remember { mutableStateOf(false) }

    LaunchedEffect(updateState) {
        if (!manualCheckRequested) {
            return@LaunchedEffect
        }
        val message = when (updateState) {
            is AppMeta.UpdateState.UpToDate -> context.symphony.t.UpToDate
            is AppMeta.UpdateState.Failed -> context.symphony.t.UpdateCheckFailed
            // Available is already shown by the tile below; Idle and Checking are
            // not outcomes worth interrupting for.
            else -> return@LaunchedEffect
        }
        manualCheckRequested = false
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message, withDismissAction = true)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                AdaptiveSnackbar(it)
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("${context.symphony.t.Settings} - ${context.symphony.t.Updates}")
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
                    if (AppMeta.canCheckForUpdates) {
                        SettingsSideHeading(context.symphony.t.Updates)
                        availableRelease?.let { release ->
                            SettingsSimpleTile(
                                icon = {
                                    Icon(Icons.Filled.Update, null)
                                },
                                title = {
                                    Text(
                                        context.symphony.t.NewVersionAvailableX(release.tag),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    ActivityUtils.startBrowserActivity(
                                        context.activity,
                                        Uri.parse(release.htmlUrl),
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                        // MAZIKA: the automatic check runs once per process launch, so a
                        // device that was offline at startup would never retry. This tile
                        // triggers one on demand and, unlike before, reports every
                        // outcome - a failed check used to be indistinguishable from one
                        // that never ran.
                        SettingsSimpleTile(
                            icon = {
                                Icon(Icons.Filled.Refresh, null)
                            },
                            title = {
                                Text(context.symphony.t.CheckForUpdates)
                            },
                            subtitle = {
                                Text(
                                    when {
                                        updateState is AppMeta.UpdateState.Checking ->
                                            context.symphony.t.CheckingForUpdates
                                        updateState is AppMeta.UpdateState.Failed ->
                                            context.symphony.t.UpdateCheckFailed
                                        updateState is AppMeta.UpdateState.UpToDate ->
                                            context.symphony.t.UpToDate
                                        lastUpdateCheck > 0L ->
                                            context.symphony.t.LastCheckedX(
                                                DateUtils
                                                    .getRelativeTimeSpanString(lastUpdateCheck)
                                                    .toString()
                                            )
                                        else -> context.symphony.t.TapToCheckForUpdates
                                    }
                                )
                            },
                            onClick = {
                                manualCheckRequested = true
                                context.symphony.checkForUpdatesNow()
                            },
                        )
                        HorizontalDivider()
                        SettingsSwitchTile(
                            icon = {
                                Icon(Icons.Filled.Update, null)
                            },
                            title = {
                                Text(context.symphony.t.CheckForUpdatesOnStartup)
                            },
                            value = checkForUpdates,
                            onChange = { value ->
                                context.symphony.settings.checkForUpdates.setValue(value)
                                if (value) {
                                    context.symphony.checkForUpdates()
                                }
                            }
                        )
                        HorizontalDivider()
                        SettingsSwitchTile(
                            icon = {
                                Icon(Icons.Filled.Update, null)
                            },
                            title = {
                                Text(context.symphony.t.ShowUpdateToast)
                            },
                            value = showUpdateToast,
                            onChange = { value ->
                                context.symphony.settings.showUpdateToast.setValue(value)
                            }
                        )
                    }
                }
            }
        }
    )
}
