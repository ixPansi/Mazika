package io.github.zyrouge.symphony.services.radio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadioEffectsTest {
    @Test
    fun progressEndsAtExactTarget() {
        val progress = RadioEffects.Fader.Progress(
            RadioEffects.Fader.Options(from = 0f, to = 1f, duration = 100, interval = 30)
        )
        val updates = mutableListOf<RadioEffects.Fader.Step>()

        do {
            updates += progress.next()
        } while (!updates.last().completed)

        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f), updates.map { it.value })
        assertEquals(1f, updates.last().value)
        assertTrue(updates.last().completed)
    }

    @Test
    fun descendingProgressEndsAtExactTarget() {
        val progress = RadioEffects.Fader.Progress(
            RadioEffects.Fader.Options(from = 1f, to = 0.1f, duration = 80, interval = 30)
        )
        val updates = mutableListOf<RadioEffects.Fader.Step>()

        do {
            updates += progress.next()
        } while (!updates.last().completed)

        assertEquals(0.1f, updates.last().value)
        assertTrue(updates.last().completed)
    }

    @Test
    fun immediateFadeCompletesOnceAtExactTarget() {
        val updates = mutableListOf<Float>()
        val results = mutableListOf<RadioEffects.Fader.Result>()
        val fader = RadioEffects.Fader(
            options = RadioEffects.Fader.Options(from = 0.8f, to = 0.2f, duration = 0),
            onUpdate = updates::add,
            onFinish = results::add,
        )

        fader.start()
        fader.start()
        fader.stop()

        assertEquals(listOf(0.2f), updates)
        assertEquals(listOf(RadioEffects.Fader.Result.Completed), results)
    }

    @Test
    fun cancellationIsOneShotAndCannotLaterStart() {
        val updates = mutableListOf<Float>()
        val results = mutableListOf<RadioEffects.Fader.Result>()
        val fader = RadioEffects.Fader(
            options = RadioEffects.Fader.Options(from = 0f, to = 1f, duration = 100),
            onUpdate = updates::add,
            onFinish = results::add,
        )

        fader.stop()
        fader.stop()
        fader.start()

        assertTrue(updates.isEmpty())
        assertEquals(listOf(RadioEffects.Fader.Result.Cancelled), results)
    }
}
