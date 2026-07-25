package io.github.zyrouge.symphony.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MAZIKA playlist-cover cropper tests: mapping the on-screen framing back onto
 * fractions of the source image.
 */
class PlaylistCoverCropTest {
    private val viewport = 1000f
    private val tolerance = 0.001f

    private fun region(aspect: Float, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) =
        toCropRegion(aspect = aspect, scale = scale, offsetX = dx, offsetY = dy, viewportPx = viewport)

    @Test
    fun squareImageUnzoomed_takesWholeImage() {
        val crop = region(aspect = 1f)
        assertEquals(0f, crop.left, tolerance)
        assertEquals(0f, crop.top, tolerance)
        assertEquals(1f, crop.size, tolerance)
    }

    @Test
    fun landscapeImage_centreCropsHorizontally() {
        // Twice as wide as tall: the square covers half the width, centred.
        val crop = region(aspect = 2f)
        assertEquals(0.25f, crop.left, tolerance)
        assertEquals(0f, crop.top, tolerance)
        assertEquals(0.5f, crop.size, tolerance)
    }

    @Test
    fun portraitImage_centreCropsVertically() {
        // Twice as tall as wide: full width, centred vertically.
        val crop = region(aspect = 0.5f)
        assertEquals(0f, crop.left, tolerance)
        assertEquals(0.25f, crop.top, tolerance)
        assertEquals(1f, crop.size, tolerance)
    }

    @Test
    fun zoomingIn_shrinksTheCroppedRegion() {
        val normal = region(aspect = 1f)
        val zoomed = region(aspect = 1f, scale = 2f)
        assertTrue(zoomed.size < normal.size, "zooming in must select less of the source")
        assertEquals(0.5f, zoomed.size, tolerance)
        // Still centred when it has not been panned.
        assertEquals(0.25f, zoomed.left, tolerance)
        assertEquals(0.25f, zoomed.top, tolerance)
    }

    @Test
    fun panningStaysInsideTheImage() {
        // Extreme pans must never produce an out-of-bounds region.
        val crop = region(aspect = 1f, scale = 2f, dx = 100000f, dy = -100000f)
        assertTrue(crop.left >= 0f, "left was ${crop.left}")
        assertTrue(crop.top >= 0f, "top was ${crop.top}")
        assertTrue(crop.left + crop.size <= 1f + tolerance, "region overflows the right edge")
        assertTrue(crop.top + crop.size <= 1f + tolerance, "region overflows the bottom edge")
    }

    @Test
    fun panningMovesTheRegion() {
        val centred = region(aspect = 1f, scale = 2f)
        // Dragging the image right reveals content further left, so the crop moves left.
        val panned = region(aspect = 1f, scale = 2f, dx = 200f)
        assertTrue(panned.left < centred.left, "expected the crop to move left when panning right")
    }
}
