package io.github.zyrouge.symphony.services.radio

/**
 * MAZIKA: pure decision for whether a volume fade should be applied, shared by the
 * resume and pause paths so the behaviour is defined in one testable place.
 *
 * - A fresh track start ([isUserPauseResume] = false) fades whenever the master
 *   "Fade playback" option is on (existing track-transition fade).
 * - A user pause or resume ([isUserPauseResume] = true) fades only when both the
 *   master option and the dependent "Fade on pause and resume" option are on.
 *
 * Forced fades (e.g. the sleep timer) bypass this and always fade.
 */
object PlaybackFade {
    fun shouldFade(
        mainFadeEnabled: Boolean,
        fadeOnPauseResume: Boolean,
        isUserPauseResume: Boolean,
    ): Boolean = mainFadeEnabled && (!isUserPauseResume || fadeOnPauseResume)
}
