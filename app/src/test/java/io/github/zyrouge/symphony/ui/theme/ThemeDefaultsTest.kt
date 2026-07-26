package io.github.zyrouge.symphony.ui.theme

import io.github.zyrouge.symphony.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThemeDefaultsTest {
    @Test
    fun sunsetIsTheCoherentDefaultPreset() {
        assertEquals(ThemePreset.Sunset, ThemePreset.Default)
        assertEquals(ThemePreset.Sunset, ThemePreset.selectable.first())
        assertEquals(ThemeMode.DARK, ThemePreset.Default.themeMode)
        assertEquals(PrimaryThemeColor.Orange, ThemePreset.Default.primaryColor)
        assertEquals("Orange", ThemePreset.Default.aliasSuffix)
        assertEquals(
            R.style.Theme_Symphony_SplashScreen_Orange,
            ThemePreset.Default.splashTheme,
        )
        assertEquals(PrimaryThemeColor.Orange, ThemeColors.resolvePrimaryColorKey(null))
    }
}
