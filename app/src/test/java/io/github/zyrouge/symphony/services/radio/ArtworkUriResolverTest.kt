package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun artworkVersionToken_isStableForAnUnchangedFile() {
        // Browsing the same library twice must not mint new uris, or Android Auto would
        // re-fetch every icon on every load and grants would pile up per item.
        assertEquals(
            artworkVersionToken(1_700_000_000_000, 48_213),
            artworkVersionToken(1_700_000_000_000, 48_213),
        )
    }

    @Test
    fun artworkVersionToken_changesWhenTheFileDoes() {
        val original = artworkVersionToken(1_700_000_000_000, 48_213)

        assertNotEquals(original, artworkVersionToken(1_700_000_000_001, 48_213))
        assertNotEquals(original, artworkVersionToken(1_700_000_000_000, 48_214))
    }

    @Test
    fun artworkVersionToken_doesNotCollideAcrossFieldBoundaries() {
        // Concatenating without a separator would make (1, 23) and (12, 3) the same token.
        assertNotEquals(artworkVersionToken(1, 23), artworkVersionToken(12, 3))
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
