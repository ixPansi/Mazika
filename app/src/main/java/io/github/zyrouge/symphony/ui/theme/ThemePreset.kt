package io.github.zyrouge.symphony.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MAZIKA: a named, one-tap theme.
 *
 * Selecting a preset applies its [themeMode] and [primaryColor] together, turns
 * Material You off (so the preset's colour is actually what you see), and swaps the
 * launcher icon to the matching variant via [aliasSuffix].
 *
 * The individual theme-mode and primary-colour pickers still work; changing either
 * of them by hand simply moves you to the Custom preset.
 */
enum class ThemePreset(
    val themeMode: ThemeMode,
    val primaryColor: PrimaryThemeColor,
    /**
     * Suffix of the `activity-alias` and icon resources for this preset, or null
     * for [Custom], which leaves the launcher icon on the brand default.
     */
    val aliasSuffix: String?,
) {
    /** The MAZIKA brand look: dark red on a dark surface. Default. */
    MazikaRed(ThemeMode.DARK, PrimaryThemeColor.MazikaRed, "Red"),

    /** Deep blue night theme, pure-black background. */
    Midnight(ThemeMode.BLACK, PrimaryThemeColor.Blue, "Blue"),

    /** Calm green. */
    Forest(ThemeMode.DARK, PrimaryThemeColor.Emerald, "Green"),

    /** Cool cyan. */
    Ocean(ThemeMode.DARK, PrimaryThemeColor.Cyan, "Cyan"),

    /** Warm orange. */
    Sunset(ThemeMode.DARK, PrimaryThemeColor.Orange, "Orange"),

    /** The original Symphony purple, on a light surface. */
    Daylight(ThemeMode.LIGHT, PrimaryThemeColor.Purple, "Purple"),

    /** Anything the user configured by hand. */
    Custom(ThemeMode.SYSTEM, PrimaryThemeColor.MazikaRed, null);

    val color: Color get() = ThemeColors.resolvePrimaryColor(primaryColor)

    companion object {
        val Default = MazikaRed

        /** Presets offered in the picker, excluding the implicit [Custom] entry. */
        val selectable = entries.filter { it != Custom }

        /**
         * The preset matching an explicit mode/colour pair, or [Custom] when the
         * combination does not correspond to a named preset.
         */
        fun match(themeMode: ThemeMode, primaryColor: PrimaryThemeColor) = selectable
            .find { it.themeMode == themeMode && it.primaryColor == primaryColor }
            ?: Custom
    }
}
