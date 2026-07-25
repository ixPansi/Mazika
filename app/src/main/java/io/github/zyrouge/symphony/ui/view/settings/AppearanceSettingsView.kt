package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.zyrouge.symphony.services.i18n.CommonTranslation
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.ConsiderContributingTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsFloatInputTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsOptionTile
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.components.settings.SettingsSwitchTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.theme.PrimaryThemeColor
import io.github.zyrouge.symphony.ui.theme.SymphonyTypography
import io.github.zyrouge.symphony.ui.theme.ThemeColors
import io.github.zyrouge.symphony.ui.theme.ThemeIcons
import io.github.zyrouge.symphony.ui.theme.ThemeMode
import io.github.zyrouge.symphony.ui.theme.ThemePreset
import kotlinx.serialization.Serializable

private val scalingPresets = listOf(
    0.25f, 0.5f, 0.75f, 0.9f, 1f,
    1.1f, 1.25f, 1.5f, 1.75f, 2f,
    2.25f, 2.5f, 2.75f, 3f,
)

@Serializable
object AppearanceSettingsViewRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsView(context: ViewContext) {
    val scrollState = rememberScrollState()
    val language by context.symphony.settings.language.flow.collectAsState()
    val fontFamily by context.symphony.settings.fontFamily.flow.collectAsState()
    val themeMode by context.symphony.settings.themeMode.flow.collectAsState()
    val useMaterialYou by context.symphony.settings.useMaterialYou.flow.collectAsState()
    val primaryColor by context.symphony.settings.primaryColor.flow.collectAsState()
    val themePreset by context.symphony.settings.themePreset.flow.collectAsState()
    val fontScale by context.symphony.settings.fontScale.flow.collectAsState()
    val contentScale by context.symphony.settings.contentScale.flow.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("${context.symphony.t.Settings} - ${context.symphony.t.Appearance}")
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
                    ConsiderContributingTile(context)
                    SettingsSideHeading(context.symphony.t.Appearance)
                    SettingsOptionTile(
                        icon = {
                            Icon(Icons.Filled.Language, null)
                        },
                        title = {
                            Text(context.symphony.t.Language_)
                        },
                        value = language ?: "",
                        values = run {
                            val defaultLocaleNativeName =
                                context.symphony.translator.getDefaultLocaleNativeName()
                            mapOf(
                                "" to "${context.symphony.t.System} (${defaultLocaleNativeName})"
                            ) + context.symphony.translator.translations.localeNativeNames
                        },
                        captions = run {
                            val defaultLocaleDisplayName =
                                context.symphony.translator.getDefaultLocaleDisplayName()
                            mapOf(
                                "" to "${CommonTranslation.System} (${defaultLocaleDisplayName})"
                            ) + context.symphony.translator.translations.localeDisplayNames
                        },
                        onChange = { value ->
                            context.symphony.settings.language.setValue(value.takeUnless { it == "" })
                        }
                    )
                    HorizontalDivider()
                    SettingsOptionTile(
                        icon = {
                            Icon(Icons.Filled.TextFormat, null)
                        },
                        title = {
                            Text(context.symphony.t.Font)
                        },
                        value = SymphonyTypography.resolveFont(fontFamily).fontName,
                        values = SymphonyTypography.all.keys.associateWith { it },
                        onChange = { value ->
                            context.symphony.settings.fontFamily.setValue(value)
                        }
                    )
                    HorizontalDivider()
                    SettingsFloatInputTile(
                        context,
                        icon = {
                            Icon(Icons.Filled.TextIncrease, null)
                        },
                        title = {
                            Text(context.symphony.t.FontScale)
                        },
                        value = fontScale,
                        presets = scalingPresets,
                        labelText = { "x$it" },
                        onReset = {
                            context.symphony.settings.fontScale.setValue(
                                context.symphony.settings.fontScale.defaultValue,
                            )
                        },
                        onChange = { value ->
                            context.symphony.settings.fontScale.setValue(value)
                        }
                    )
                    HorizontalDivider()
                    SettingsFloatInputTile(
                        context,
                        icon = {
                            Icon(Icons.Filled.PhotoSizeSelectLarge, null)
                        },
                        title = {
                            Text(context.symphony.t.ContentScale)
                        },
                        value = contentScale,
                        presets = scalingPresets,
                        labelText = { "x$it" },
                        onReset = {
                            context.symphony.settings.contentScale.setValue(
                                context.symphony.settings.contentScale.defaultValue,
                            )
                        },
                        onChange = { value ->
                            context.symphony.settings.contentScale.setValue(value)
                        }
                    )
                    HorizontalDivider()
                    // MAZIKA: one-tap named themes. Selecting a preset applies its
                    // mode and colour together, turns Material You off so the
                    // preset's colour is what actually shows, and swaps the launcher
                    // icon to match.
                    SettingsOptionTile(
                        icon = {
                            Icon(Icons.Filled.Palette, null)
                        },
                        title = {
                            Text(context.symphony.t.ThemePreset)
                        },
                        value = themePreset,
                        values = buildMap {
                            ThemePreset.selectable.forEach { put(it, it.label(context)) }
                            if (themePreset == ThemePreset.Custom) {
                                put(ThemePreset.Custom, context.symphony.t.Custom)
                            }
                        },
                        onChange = { value ->
                            if (value == ThemePreset.Custom) {
                                return@SettingsOptionTile
                            }
                            context.symphony.settings.themePreset.setValue(value)
                            context.symphony.settings.themeMode.setValue(value.themeMode)
                            context.symphony.settings.primaryColor.setValue(value.primaryColor.name)
                            context.symphony.settings.useMaterialYou.setValue(false)
                            ThemeIcons.apply(context.symphony.applicationContext, value)
                        }
                    )
                    HorizontalDivider()
                    SettingsOptionTile(
                        icon = {
                            Icon(Icons.Filled.Palette, null)
                        },
                        title = {
                            Text(context.symphony.t.Theme)
                        },
                        value = themeMode,
                        values = mapOf(
                            ThemeMode.SYSTEM to context.symphony.t.SystemLightDark,
                            ThemeMode.SYSTEM_BLACK to context.symphony.t.SystemLightBlack,
                            ThemeMode.LIGHT to context.symphony.t.Light,
                            ThemeMode.DARK to context.symphony.t.Dark,
                            ThemeMode.BLACK to context.symphony.t.Black,
                        ),
                        onChange = { value ->
                            context.symphony.settings.themeMode.setValue(value)
                            syncPresetToSelection(context, value, primaryColor)
                        }
                    )
                    HorizontalDivider()
                    SettingsSwitchTile(
                        icon = {
                            Icon(Icons.Filled.Face, null)
                        },
                        title = {
                            Text(context.symphony.t.MaterialYou)
                        },
                        value = useMaterialYou,
                        onChange = { value ->
                            context.symphony.settings.useMaterialYou.setValue(value)
                        }
                    )
                    HorizontalDivider()
                    SettingsOptionTile(
                        icon = {
                            Icon(Icons.Filled.Colorize, null)
                        },
                        title = {
                            Text(context.symphony.t.PrimaryColor)
                        },
                        value = ThemeColors.resolvePrimaryColorKey(primaryColor),
                        values = PrimaryThemeColor.entries.associateWith { it.label(context) },
                        enabled = !useMaterialYou,
                        onChange = { value ->
                            context.symphony.settings.primaryColor.setValue(value.name)
                            syncPresetToSelection(context, themeMode, value.name)
                        }
                    )
                }
            }
        }
    )
}

/**
 * MAZIKA: keeps the preset in step with hand-picked mode/colour values. If the pair
 * matches a named preset we select it (and its launcher icon); otherwise the user is
 * on a custom combination.
 */
private fun syncPresetToSelection(
    context: ViewContext,
    themeMode: ThemeMode,
    primaryColorName: String?,
) {
    val preset = ThemePreset.match(
        themeMode,
        ThemeColors.resolvePrimaryColorKey(primaryColorName),
    )
    context.symphony.settings.themePreset.setValue(preset)
    if (preset != ThemePreset.Custom) {
        ThemeIcons.apply(context.symphony.applicationContext, preset)
    }
}

// Preset and brand-colour names are product names, so they stay untranslated —
// only "Custom" comes from the translations.
fun ThemePreset.label(context: ViewContext) = when (this) {
    ThemePreset.MazikaRed -> "MAZIKA Red"
    ThemePreset.Midnight -> "Midnight"
    ThemePreset.Forest -> "Forest"
    ThemePreset.Ocean -> "Ocean"
    ThemePreset.Sunset -> "Sunset"
    ThemePreset.Daylight -> "Daylight"
    ThemePreset.Custom -> context.symphony.t.Custom
}

fun PrimaryThemeColor.label(context: ViewContext) = when (this) {
    PrimaryThemeColor.MazikaRed -> "MAZIKA Red"
    PrimaryThemeColor.Red -> context.symphony.t.Red
    PrimaryThemeColor.Orange -> context.symphony.t.Orange
    PrimaryThemeColor.Amber -> context.symphony.t.Amber
    PrimaryThemeColor.Yellow -> context.symphony.t.Yellow
    PrimaryThemeColor.Lime -> context.symphony.t.Lime
    PrimaryThemeColor.Green -> context.symphony.t.Green
    PrimaryThemeColor.Emerald -> context.symphony.t.Emerald
    PrimaryThemeColor.Teal -> context.symphony.t.Teal
    PrimaryThemeColor.Cyan -> context.symphony.t.Cyan
    PrimaryThemeColor.Sky -> context.symphony.t.Sky
    PrimaryThemeColor.Blue -> context.symphony.t.Blue
    PrimaryThemeColor.Indigo -> context.symphony.t.Indigo
    PrimaryThemeColor.Violet -> context.symphony.t.Violet
    PrimaryThemeColor.Purple -> context.symphony.t.Purple
    PrimaryThemeColor.Fuchsia -> context.symphony.t.Fuchsia
    PrimaryThemeColor.Pink -> context.symphony.t.Pink
    PrimaryThemeColor.Rose -> context.symphony.t.Rose
}
