package io.github.zyrouge.symphony.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MAZIKA custom playlist cover tests — the pure sampling/naming helpers used when
 * persisting a selected image (the full decode/crop path needs an Android bitmap
 * and is covered by manual/instrumented testing).
 */
class CustomCoversTest {
    @Test
    fun smallImage_isNotDownsampled() {
        assertEquals(1, CustomCovers.calculateInSampleSize(800, 600, 1024))
    }

    @Test
    fun largeImage_isSampledSafely() {
        val sample = CustomCovers.calculateInSampleSize(4096, 4096, 1024)
        assertTrue(sample >= 2, "expected a downsample factor >= 2, got $sample")
        // The downscaled dimension still comfortably covers the target square.
        assertTrue(4096 / sample >= 1024)
    }

    @Test
    fun sanitizeId_replacesUnsafeCharacters() {
        assertEquals("a_b_c", CustomCovers.sanitizeId("a/b:c"))
        assertEquals("favorites", CustomCovers.sanitizeId("favorites"))
        assertEquals("1690000000000", CustomCovers.sanitizeId("1690000000000"))
    }

    @Test
    fun retiredCover_outlivesAnAndroidAutoBrowseCache() {
        // Android Auto caches the browse tree, and the content uris inside it, across
        // sessions and reconnects. At ten minutes a cover replaced in the morning left a
        // blank tile in the car that afternoon, because the uri Auto still held pointed
        // at a file that had already been deleted. Anything under a day reopens that.
        val oneDay = 24 * 60 * 60 * 1000L
        assertTrue(
            CustomCovers.RETIRED_COVER_GRACE_MS >= oneDay,
            "retired covers must outlive Android Auto's browse cache",
        )
    }

    @Test
    fun retiredCover_isKeptUntilGracePeriodEnds() {
        val now = 1_000_000L
        assertFalse(
            CustomCovers.isPastDeletionGracePeriod(
                now - CustomCovers.RETIRED_COVER_GRACE_MS + 1L,
                now,
            )
        )
        assertTrue(
            CustomCovers.isPastDeletionGracePeriod(
                now - CustomCovers.RETIRED_COVER_GRACE_MS,
                now,
            )
        )
    }
}
