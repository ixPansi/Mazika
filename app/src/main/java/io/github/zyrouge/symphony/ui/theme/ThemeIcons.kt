package io.github.zyrouge.symphony.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.zyrouge.symphony.utils.Logger

/**
 * MAZIKA: switches the launcher icon to match the selected [ThemePreset].
 *
 * Android cannot recolour a launcher icon at runtime, so each preset ships its own
 * `activity-alias` (declared in the manifest) pointing at MainActivity with a
 * different icon. Exactly one alias is enabled at a time.
 *
 * Caveats worth knowing, they are inherent to this approach:
 *  - The launcher may briefly remove and re-add the app while the swap happens.
 *  - Some launchers cache the old icon until they are restarted.
 *  - Home-screen shortcuts pointing at the old alias can be dropped.
 * We therefore only touch the package manager when the icon actually changes.
 */
object ThemeIcons {
    private const val ALIAS_PREFIX = "io.github.zyrouge.symphony.Launcher"

    private val allAliases = ThemePreset.selectable
        .mapNotNull { it.aliasSuffix }
        .distinct()

    /** Alias for a preset; [ThemePreset.Custom] keeps the brand default. */
    private fun aliasFor(preset: ThemePreset): String =
        ALIAS_PREFIX + (preset.aliasSuffix ?: ThemePreset.Default.aliasSuffix!!)

    /**
     * Enables the alias matching [preset] and disables the others. No-ops when the
     * correct alias is already the enabled one, so repeated calls are cheap and do
     * not make the launcher flicker.
     */
    fun apply(context: Context, preset: ThemePreset) {
        try {
            val packageManager = context.packageManager
            val target = aliasFor(preset)
            val current = allAliases
                .map { ALIAS_PREFIX + it }
                .firstOrNull { isEnabled(packageManager, context, it) }
            if (current == target) {
                return
            }
            // Enable the new alias before disabling the old one, so the app never
            // has zero launcher entries (which can drop it from the home screen).
            setEnabled(packageManager, context, target, true)
            allAliases
                .map { ALIAS_PREFIX + it }
                .filter { it != target }
                .forEach { setEnabled(packageManager, context, it, false) }
        } catch (err: Exception) {
            Logger.error("ThemeIcons", "unable to switch launcher icon", err)
        }
    }

    private fun isEnabled(
        packageManager: PackageManager,
        context: Context,
        alias: String,
    ): Boolean {
        val state = packageManager.getComponentEnabledSetting(
            ComponentName(context.packageName, alias)
        )
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // DEFAULT means "whatever the manifest says"; only the brand alias ships
            // enabled, so that is the one that is live before any user choice.
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
                alias == ALIAS_PREFIX + ThemePreset.Default.aliasSuffix
            else -> false
        }
    }

    private fun setEnabled(
        packageManager: PackageManager,
        context: Context,
        alias: String,
        enabled: Boolean,
    ) {
        packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, alias),
            when {
                enabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}
