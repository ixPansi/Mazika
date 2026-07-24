package io.github.zyrouge.symphony.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MAZIKA custom playlist cover tests — the pure sampling/naming helpers used when
 * persisting a selected image (the full decode/crop path needs an Android bitmap
 * and is covered by manual/instrumented testing).
 */
class PlaylistCoversTest {
    @Test
    fun smallImage_isNotDownsampled() {
        assertEquals(1, PlaylistCovers.calculateInSampleSize(800, 600, 1024))
    }

    @Test
    fun largeImage_isSampledSafely() {
        val sample = PlaylistCovers.calculateInSampleSize(4096, 4096, 1024)
        assertTrue(sample >= 2, "expected a downsample factor >= 2, got $sample")
        // The downscaled dimension still comfortably covers the target square.
        assertTrue(4096 / sample >= 1024)
    }

    @Test
    fun sanitizeId_replacesUnsafeCharacters() {
        assertEquals("a_b_c", PlaylistCovers.sanitizeId("a/b:c"))
        assertEquals("favorites", PlaylistCovers.sanitizeId("favorites"))
        assertEquals("1690000000000", PlaylistCovers.sanitizeId("1690000000000"))
    }
}
