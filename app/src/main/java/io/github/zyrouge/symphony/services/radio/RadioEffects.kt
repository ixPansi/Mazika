package io.github.zyrouge.symphony.services.radio

import java.util.Timer
import kotlin.math.ceil

object RadioEffects {
    class Fader(
        val options: Options,
        val onUpdate: (Float) -> Unit,
        val onFinish: (Result) -> Unit,
    ) {
        enum class Result {
            Completed,
            Cancelled,
        }

        data class Options(
            val from: Float,
            val to: Float,
            val duration: Int,
            val interval: Int = DEFAULT_INTERVAL,
        ) {
            init {
                require(interval > 0)
            }

            companion object {
                private const val DEFAULT_INTERVAL = 50
            }
        }

        internal data class Step(val value: Float, val completed: Boolean)

        internal class Progress(private val options: Options) {
            private val stepCount = when {
                options.duration <= 0 || options.from == options.to -> 0
                else -> ceil(options.duration.toDouble() / options.interval).toInt().coerceAtLeast(1)
            }
            private var step = 0

            val immediate get() = stepCount == 0

            fun next(): Step {
                if (immediate) {
                    return Step(options.to, completed = true)
                }
                step = (step + 1).coerceAtMost(stepCount)
                val completed = step == stepCount
                val value = when {
                    completed -> options.to
                    else -> options.from + (options.to - options.from) * step / stepCount
                }
                return Step(value, completed)
            }
        }

        private val lock = Any()
        private val progress = Progress(options)
        private var timer: Timer? = null
        private var started = false
        private var result: Result? = null

        fun start() {
            var completed = false
            synchronized(lock) {
                if (started || result != null) {
                    return
                }
                started = true
                if (progress.immediate) {
                    result = Result.Completed
                    onUpdate(options.to)
                    completed = true
                } else {
                    timer = kotlin.concurrent.timer(
                        name = "RadioFader",
                        daemon = true,
                        initialDelay = options.interval.toLong(),
                        period = options.interval.toLong(),
                    ) {
                        tick()
                    }
                }
            }
            if (completed) {
                onFinish(Result.Completed)
            }
        }

        fun stop() {
            val cancelled = synchronized(lock) {
                if (result != null) {
                    false
                } else {
                    result = Result.Cancelled
                    destroyTimer()
                    true
                }
            }
            if (cancelled) {
                onFinish(Result.Cancelled)
            }
        }

        private fun tick() {
            var completed = false
            synchronized(lock) {
                if (result != null) {
                    return
                }
                val update = progress.next()
                onUpdate(update.value)
                if (update.completed && result == null) {
                    result = Result.Completed
                    destroyTimer()
                    completed = true
                }
            }
            if (completed) {
                onFinish(Result.Completed)
            }
        }

        private fun destroyTimer() {
            timer?.cancel()
            timer = null
        }
    }
}
