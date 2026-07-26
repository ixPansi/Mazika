package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtworkUriResolverTest {
    @Test
    fun validCustomArtwork_winsOverEmbeddedArtwork() {
        val result = resolvePreferredArtwork(
            custom = "custom",
            embedded = "embedded",
            default = { "default" },
            resolve = { it },
        )

        assertEquals("custom", result)
    }

    @Test
    fun invalidCustomArtwork_fallsBackToEmbeddedArtwork() {
        val result = resolvePreferredArtwork(
            custom = "missing-custom",
            embedded = "embedded",
            default = { "default" },
            resolve = { it.takeUnless { value -> value.startsWith("missing") } },
        )

        assertEquals("embedded", result)
    }

    @Test
    fun invalidFileArtwork_fallsBackToDefaultArtwork() {
        val result = resolvePreferredArtwork(
            custom = "missing-custom",
            embedded = "missing-embedded",
            default = { "default" },
            resolve = { null },
        )

        assertEquals("default", result)
    }

    @Test
    fun invalidPlaylistCover_usesResolvedFirstSongArtwork() {
        val result = resolvePreferredArtwork(
            custom = "missing-playlist",
            embedded = null,
            default = { "custom-first-song" },
            resolve = { null },
        )

        assertEquals("custom-first-song", result)
    }

    @Test
    fun artworkFileNames_rejectTraversalAndSeparators() {
        assertTrue(isSafeArtworkFileName("song_123.webp"))
        assertFalse(isSafeArtworkFileName(""))
        assertFalse(isSafeArtworkFileName("../cover.webp"))
        assertFalse(isSafeArtworkFileName("folder/cover.webp"))
        assertFalse(isSafeArtworkFileName("folder\\cover.webp"))
    }
}
