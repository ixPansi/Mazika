package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackTransitionTest {
    @Test
    fun pauseWhilePreparingPreventsAutostart() {
        val transition = PlaybackTransition()
        val generation = transition.begin(playWhenReady = true)
        var started = false

        val pause = transition.pause()

        assertEquals(generation, pause.generation)
        assertTrue(pause.intentChanged)
        assertTrue(transition.isCurrent(generation))
        assertFalse(transition.shouldStart(generation))
        assertFalse(transition.startIfReady(generation) {
            started = true
            true
        })
        assertFalse(started)
    }

    @Test
    fun newerTransitionRejectsStaleCallbacks() {
        val transition = PlaybackTransition()
        val stale = transition.begin(playWhenReady = true)
        val current = transition.begin(playWhenReady = true)
        var staleCallbackRan = false

        assertNotEquals(stale, current)
        assertFalse(transition.isCurrent(stale))
        assertFalse(transition.shouldStart(stale))
        assertFalse(transition.runIfCurrent(stale) { staleCallbackRan = true })
        assertFalse(staleCallbackRan)
        assertTrue(transition.shouldStart(current))
    }

    @Test
    fun rapidTransitionsOnlyAllowLatestCallbackToStart() {
        val transition = PlaybackTransition()
        val first = transition.begin(playWhenReady = true)
        val second = transition.begin(playWhenReady = true)
        val third = transition.begin(playWhenReady = true)

        assertFalse(transition.shouldStart(first))
        assertFalse(transition.shouldStart(second))
        assertTrue(transition.shouldStart(third))
    }

    @Test
    fun resumeRestoresIntentWithoutRevivingAnOlderGeneration() {
        val transition = PlaybackTransition()
        val stale = transition.begin(playWhenReady = true)
        val current = transition.begin(playWhenReady = true)
        var pauseCompleted = false

        assertTrue(transition.pause().intentChanged)
        assertFalse(transition.pause().intentChanged)
        assertFalse(transition.shouldStart(current))

        val resumed = transition.resume()

        assertTrue(transition.playWhenReady)
        assertTrue(transition.shouldStart(resumed))
        assertFalse(transition.shouldStart(stale))
        assertFalse(transition.completePause(current) {
            pauseCompleted = true
            true
        })
        assertFalse(pauseCompleted)
    }

    @Test
    fun stopInvalidatesCallbacksAndClearsIntent() {
        val transition = PlaybackTransition()
        val generation = transition.begin(playWhenReady = true)

        transition.invalidate()

        assertFalse(transition.playWhenReady)
        assertFalse(transition.isCurrent(generation))
        assertFalse(transition.shouldStart(generation))
    }

    @Test
    fun failedRequestCannotInvalidateNewerPlaybackIntent() {
        val transition = PlaybackTransition()
        val failed = transition.begin(playWhenReady = true)
        val current = transition.begin(playWhenReady = false)

        assertEquals(null, transition.invalidateIfCurrent(failed))
        assertTrue(transition.isCurrent(current))
        assertFalse(transition.playWhenReady)
        assertEquals(false, transition.invalidateIfCurrent(current))
        assertFalse(transition.isCurrent(current))
    }

    @Test
    fun resumedPlaybackRejectsPendingPauseWork() {
        val transition = PlaybackTransition()
        val generation = transition.begin(playWhenReady = true)
        transition.pause()
        var pauseWorkRan = false

        assertTrue(transition.runIfPaused(generation) { pauseWorkRan = true })
        assertTrue(pauseWorkRan)

        transition.resume()
        pauseWorkRan = false
        assertFalse(transition.runIfPaused(generation) { pauseWorkRan = true })
        assertFalse(pauseWorkRan)
    }
}
