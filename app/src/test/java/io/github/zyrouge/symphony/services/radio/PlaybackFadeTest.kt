package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MAZIKA fade tests — the pause/resume vs track-start fade truth table.
 * [PlaybackFade.shouldFade] is the exact decision used by Radio.start()/pause().
 */
class PlaybackFadeTest {
    // Case 1: master fade OFF -> pause and resume are immediate regardless of the
    // dependent option.
    @Test
    fun masterOff_pauseResumeImmediate() {
        assertFalse(PlaybackFade.shouldFade(mainFadeEnabled = false, fadeOnPauseResume = true, isUserPauseResume = true))
        assertFalse(PlaybackFade.shouldFade(mainFadeEnabled = false, fadeOnPauseResume = false, isUserPauseResume = true))
    }

    // Case 2: master ON + dependent OFF -> pause/resume immediate, but a fresh track
    // start still fades (existing track-transition fade).
    @Test
    fun masterOn_dependentOff() {
        assertFalse(PlaybackFade.shouldFade(mainFadeEnabled = true, fadeOnPauseResume = false, isUserPauseResume = true))
        assertTrue(PlaybackFade.shouldFade(mainFadeEnabled = true, fadeOnPauseResume = false, isUserPauseResume = false))
    }

    // Case 3: both ON -> fade on pause/resume and on track start.
    @Test
    fun bothOn_fadeEverywhere() {
        assertTrue(PlaybackFade.shouldFade(mainFadeEnabled = true, fadeOnPauseResume = true, isUserPauseResume = true))
        assertTrue(PlaybackFade.shouldFade(mainFadeEnabled = true, fadeOnPauseResume = true, isUserPauseResume = false))
    }

    // A fresh track start follows the master option only (dependent option ignored).
    @Test
    fun trackStart_followsMasterOnly() {
        assertTrue(PlaybackFade.shouldFade(mainFadeEnabled = true, fadeOnPauseResume = false, isUserPauseResume = false))
        assertFalse(PlaybackFade.shouldFade(mainFadeEnabled = false, fadeOnPauseResume = true, isUserPauseResume = false))
    }
}
