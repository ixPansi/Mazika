package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AndroidAutoCategoryTest {
    @Test
    fun defaultCategoriesMatchFreshInstallOrder() {
        assertEquals(
            listOf(
                AndroidAutoCategory.PLAYLISTS,
                AndroidAutoCategory.SONGS,
                AndroidAutoCategory.ARTISTS,
                AndroidAutoCategory.GENRES,
                AndroidAutoCategory.ALBUMS,
            ),
            AndroidAutoCategory.Default,
        )
    }
}
