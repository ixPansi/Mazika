package io.github.zyrouge.symphony.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppMetaVersionTest {
    @Test
    fun higherNumericCode_isNewer() {
        assertTrue(AppMeta.isNewerStableVersion("v2024.12.115", "v2024.12.116"))
        assertFalse(AppMeta.isNewerStableVersion("v2024.12.115", "v2024.12.99"))
    }

    @Test
    fun yearAndMonth_areComparedBeforeCode() {
        assertTrue(AppMeta.isNewerStableVersion("2024.12.999", "2025.1.1"))
        assertFalse(AppMeta.isNewerStableVersion("2025.1.1", "2024.12.999"))
        assertTrue(AppMeta.isNewerStableVersion("2025.1.999", "2025.2.1"))
    }

    @Test
    fun equalVersion_isNotNewer() {
        assertFalse(AppMeta.isNewerStableVersion("v2025.7.120", "2025.07.120"))
    }

    @Test
    fun nonNumericOrInvalidStableVersions_areNotNewer() {
        listOf(
            "v2025.7.121-nightly+abc",
            "v2025.13.121",
            "v25.7.121",
            "v2025.7",
            "latest",
        ).forEach { candidate ->
            assertFalse(AppMeta.isNewerStableVersion("v2025.7.120", candidate), candidate)
        }
        assertFalse(AppMeta.isNewerStableVersion("invalid", "v2025.7.121"))
    }
}
