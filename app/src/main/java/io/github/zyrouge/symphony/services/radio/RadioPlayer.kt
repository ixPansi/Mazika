package io.github.zyrouge.symphony.services.radio

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.launch
import java.util.Timer

typealias RadioPlayerOnPreparedListener = () -> Unit
typealias RadioPlayerOnPlaybackPositionListener = (RadioPlayer.PlaybackPosition) -> Unit
typealias RadioPlayerOnFinishListener = () -> Unit
typealias RadioPlayerOnErrorListener = (Int, Int) -> Unit

class RadioPlayer(val symphony: Symphony, val id: String, val uri: Uri) {
    data class PlaybackPosition(val played: Long, val total: Long) {
        val ratio: Float
            get() = (played.toFloat() / total).takeIf { it.isFinite() } ?: 0f

        companion object {
            val zero = PlaybackPosition(0L, 0L)
        }
    }

    enum class State {
        Unprepared,
        Preparing,
        Prepared,
        Finished,
        Destroyed,
    }

    private val unsafeMediaPlayer: MediaPlayer
    private val mediaPlayer: MediaPlayer? get() = if (usable) unsafeMediaPlayer else null
    @Volatile
    private var onPrepared: RadioPlayerOnPreparedListener? = null
    @Volatile
    private var onPlaybackPosition: RadioPlayerOnPlaybackPositionListener? = null
    @Volatile
    private var onFinish: RadioPlayerOnFinishListener? = null
    @Volatile
    private var onError: RadioPlayerOnErrorListener? = null
    @Volatile
    private var fader: RadioEffects.Fader? = null
    private var playbackPositionUpdater: Timer? = null
    private val stateLock = Any()
    private var destroyStarted = false

    @Volatile
    var state = State.Unprepared
        private set
    @Volatile
    var hasPlayedOnce = false
        private set
    @Volatile
    var volume = MAX_VOLUME
        private set
    @Volatile
    var speed = DEFAULT_SPEED
        private set
    @Volatile
    var pitch = DEFAULT_PITCH
        private set

    val usable get() = state == State.Prepared
    val available get() = state != State.Finished && state != State.Destroyed
    val fadePlayback get() = symphony.settings.fadePlayback.value
    val audioSessionId get() = mediaPlayer?.audioSessionId
    val isPlaying
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }

    val playbackPosition
        get() = mediaPlayer?.let {
            try {
                PlaybackPosition(
                    played = it.currentPosition.toLong(),
                    total = it.duration.toLong(),
                )
            } catch (_: IllegalStateException) {
                null
            }
        }

    init {
        unsafeMediaPlayer = MediaPlayer().also { ump ->
            ump.setOnPreparedListener {
                val listener = synchronized(stateLock) {
                    if (destroyStarted || state != State.Preparing) {
                        null
                    } else {
                        ump.playbackParams.setAudioFallbackMode(
                            PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT
                        )
                        state = State.Prepared
                        onPrepared
                    }
                }
                listener?.invoke()
            }
            ump.setOnCompletionListener {
                val listener = synchronized(stateLock) {
                    if (destroyStarted || state != State.Prepared) {
                        null
                    } else {
                        state = State.Finished
                        onFinish
                    }
                }
                if (listener != null) {
                    destroyDurationTimer()
                    listener.invoke()
                }
            }
            ump.setOnErrorListener { _, what, extra ->
                val listener = synchronized(stateLock) {
                    if (destroyStarted) {
                        null
                    } else {
                        state = State.Destroyed
                        onError
                    }
                }
                destroyDurationTimer()
                listener?.invoke(what, extra)
                true
            }
            ump.setDataSource(symphony.applicationContext, uri)
        }
    }

    fun prepare() {
        val preparedListener = synchronized(stateLock) {
            when (state) {
                State.Unprepared -> {
                    state = State.Preparing
                    try {
                        unsafeMediaPlayer.prepareAsync()
                    } catch (err: Exception) {
                        state = State.Unprepared
                        throw err
                    }
                    null
                }

                State.Prepared -> onPrepared
                else -> null
            }
        }
        preparedListener?.invoke()
    }

    fun stop() = destroy()

    fun destroy() {
        val shouldDestroy = synchronized(stateLock) {
            if (destroyStarted) {
                false
            } else {
                destroyStarted = true
                state = State.Destroyed
                clearListeners()
                true
            }
        }
        if (!shouldDestroy) return
        cancelVolumeChange()
        destroyDurationTimer()
        symphony.groove.coroutineScope.launch {
            try {
                unsafeMediaPlayer.stop()
            } catch (_: Exception) {
            } finally {
                try {
                    unsafeMediaPlayer.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun start(): Boolean {
        val player = mediaPlayer ?: return false
        player.start()
        createDurationTimer()
        if (!hasPlayedOnce) {
            hasPlayedOnce = true
            changeSpeed(speed)
            changePitch(pitch)
        }
        return true
    }

    fun pause(): Boolean {
        cancelVolumeChange()
        val player = mediaPlayer
        if (player == null) {
            destroyDurationTimer()
            return false
        }
        return try {
            if (!player.isPlaying) {
                false
            } else {
                player.pause()
                true
            }
        } catch (_: IllegalStateException) {
            false
        } finally {
            destroyDurationTimer()
        }
    }

    fun seek(to: Int) = mediaPlayer?.let {
        it.seekTo(to)
        emitPlaybackPosition()
    }

    fun changeVolume(
        to: Float,
        forceFade: Boolean = false,
        onFinish: (RadioEffects.Fader.Result) -> Unit,
    ) {
        cancelVolumeChange()
        when {
            to == volume -> onFinish(RadioEffects.Fader.Result.Completed)
            forceFade || fadePlayback -> {
                val duration = (symphony.settings.fadePlaybackDuration.value * 1000).toInt()
                lateinit var nextFader: RadioEffects.Fader
                nextFader = RadioEffects.Fader(
                    RadioEffects.Fader.Options(volume, to, duration),
                    onUpdate = {
                        changeVolumeInstant(it)
                    },
                    onFinish = { result ->
                        if (fader === nextFader) {
                            fader = null
                        }
                        onFinish(result)
                    }
                )
                fader = nextFader
                nextFader.start()
            }

            else -> {
                changeVolumeInstant(to)
                onFinish(RadioEffects.Fader.Result.Completed)
            }
        }
    }

    fun changeVolumeInstant(to: Float) {
        volume = to
        mediaPlayer?.setVolume(to, to)
    }

    fun changeSpeed(to: Float) {
        if (!hasPlayedOnce) {
            speed = to
            return
        }
        mediaPlayer?.let {
            val isPlaying = it.isPlaying
            try {
                it.playbackParams = it.playbackParams.setSpeed(to)
                speed = to
            } catch (err: Exception) {
                Logger.error("RadioPlayer", "changing speed failed", err)
            }
            if (!isPlaying) {
                it.pause()
            }
        }
    }

    fun changePitch(to: Float) {
        if (!hasPlayedOnce) {
            pitch = to
            return
        }
        mediaPlayer?.let {
            val isPlaying = it.isPlaying
            try {
                it.playbackParams = it.playbackParams.setPitch(to)
                pitch = to
            } catch (err: Exception) {
                Logger.error("RadioPlayer", "changing pitch failed", err)
            }
            if (!isPlaying) {
                it.pause()
            }
        }
    }

    fun setOnPreparedListener(listener: RadioPlayerOnPreparedListener?) {
        onPrepared = listener
    }

    fun setOnPlaybackPositionListener(listener: RadioPlayerOnPlaybackPositionListener?) {
        onPlaybackPosition = listener
    }

    fun setOnFinishListener(listener: RadioPlayerOnFinishListener?) {
        onFinish = listener
    }

    fun setOnErrorListener(listener: RadioPlayerOnErrorListener?) {
        onError = listener
    }

    fun clearListeners() {
        onPrepared = null
        onPlaybackPosition = null
        onFinish = null
        onError = null
    }

    private fun createDurationTimer() {
        destroyDurationTimer()
        playbackPositionUpdater = kotlin.concurrent.timer(
            name = "RadioPlaybackPosition",
            daemon = true,
            period = 100L,
        ) {
            emitPlaybackPosition()
        }
    }

    private fun emitPlaybackPosition() {
        playbackPosition?.let {
            onPlaybackPosition?.invoke(it)
        }
    }

    private fun destroyDurationTimer() {
        playbackPositionUpdater?.cancel()
        playbackPositionUpdater = null
    }

    internal fun cancelVolumeChange() {
        val currentFader = fader
        fader = null
        currentFader?.stop()
    }

    companion object {
        const val MIN_VOLUME = 0f
        const val MAX_VOLUME = 1f
        const val DUCK_VOLUME = 0.2f
        const val DEFAULT_SPEED = 1f
        const val DEFAULT_PITCH = 1f
    }
}
