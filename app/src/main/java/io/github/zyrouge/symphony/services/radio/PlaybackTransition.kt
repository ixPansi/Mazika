package io.github.zyrouge.symphony.services.radio

internal class PlaybackTransition {
    data class PauseResult(
        val generation: Long,
        val intentChanged: Boolean,
    )

    private data class State(
        val generation: Long,
        val playWhenReady: Boolean,
    )

    @Volatile
    private var state = State(generation = 0L, playWhenReady = false)

    val playWhenReady get() = state.playWhenReady

    @Synchronized
    fun begin(playWhenReady: Boolean): Long {
        val next = State(state.generation + 1, playWhenReady)
        state = next
        return next.generation
    }

    @Synchronized
    fun resume(): Long {
        state = state.copy(playWhenReady = true)
        return state.generation
    }

    @Synchronized
    fun pause(): PauseResult {
        val changed = state.playWhenReady
        state = state.copy(playWhenReady = false)
        return PauseResult(state.generation, changed)
    }

    @Synchronized
    fun invalidate() {
        invalidate {}
    }

    @Synchronized
    fun invalidate(action: () -> Unit) {
        state = State(state.generation + 1, playWhenReady = false)
        action()
    }

    /** Invalidates only the request that failed, preserving any newer user action. */
    @Synchronized
    fun invalidateIfCurrent(generation: Long): Boolean? {
        if (state.generation != generation) {
            return null
        }
        val playWhenReady = state.playWhenReady
        state = State(state.generation + 1, playWhenReady = false)
        return playWhenReady
    }

    fun isCurrent(generation: Long) = state.generation == generation

    fun shouldStart(generation: Long): Boolean {
        val current = state
        return current.generation == generation && current.playWhenReady
    }

    @Synchronized
    fun runIfPaused(generation: Long, action: () -> Unit): Boolean {
        if (state.generation != generation || state.playWhenReady) {
            return false
        }
        action()
        return true
    }

    @Synchronized
    fun runIfCurrent(generation: Long, action: () -> Unit): Boolean {
        if (state.generation != generation) {
            return false
        }
        action()
        return true
    }

    @Synchronized
    fun startIfReady(generation: Long, start: () -> Boolean): Boolean {
        if (state.generation != generation || !state.playWhenReady) {
            return false
        }
        return start()
    }

    @Synchronized
    fun completePause(generation: Long, pause: () -> Boolean): Boolean {
        if (state.generation != generation || state.playWhenReady) {
            return false
        }
        return pause()
    }
}
