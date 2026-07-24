package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * MAZIKA Android Auto tests — stable, separator-safe media id encoding used to
 * build the browse tree and resolve playback.
 */
class MediaIdTest {
    @Test
    fun songWithoutContext_roundTrips() {
        val mediaId = MediaId.of(MediaId.TYPE_SONG, "1690000000000.mr")
        val parsed = MediaId.parse(mediaId)!!
        assertEquals(MediaId.TYPE_SONG, parsed.type)
        assertEquals("1690000000000.mr", parsed.id)
        assertNull(parsed.contextType)
        assertNull(parsed.contextId)
    }

    @Test
    fun songWithContext_roundTrips() {
        val mediaId = MediaId.of(MediaId.TYPE_SONG, "song/with:weird|chars", MediaId.TYPE_ALBUM, "Album|Name:2024")
        val parsed = MediaId.parse(mediaId)!!
        assertEquals(MediaId.TYPE_SONG, parsed.type)
        assertEquals("song/with:weird|chars", parsed.id)
        assertEquals(MediaId.TYPE_ALBUM, parsed.contextType)
        assertEquals("Album|Name:2024", parsed.contextId)
    }

    @Test
    fun separatorsInRawIdsAreEncoded() {
        // Album ids can contain the '|' separator; encoding must keep parsing safe.
        val raw = "a|b|c|d"
        val mediaId = MediaId.of(MediaId.TYPE_ALBUM, raw)
        assertEquals(2, mediaId.split('|').size)
        assertEquals(raw, MediaId.parse(mediaId)!!.id)
    }

    @Test
    fun wrongArity_returnsNull() {
        assertNull(MediaId.parse("song|abc|extra"))
        assertNull(MediaId.parse("nope"))
    }
}
