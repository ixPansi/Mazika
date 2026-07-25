package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.PlaySource
import kotlin.random.Random

class RadioShorty(private val symphony: Symphony) {
    fun playPause() {
        if (!symphony.radio.hasPlayer) {
            return
        }
        when {
            symphony.radio.isPlaying -> symphony.radio.pause()
            else -> symphony.radio.resume()
        }
    }

    fun seekFromCurrent(offsetSecs: Int) {
        if (!symphony.radio.hasPlayer) {
            return
        }
        symphony.radio.currentPlaybackPosition?.run {
            val to = (played + (offsetSecs * 1000)).coerceIn(0..total)
            symphony.radio.seek(to)
        }
    }

    fun previous(): Boolean {
        return when {
            !symphony.radio.hasPlayer -> false
            symphony.radio.currentPlaybackPosition!!.played <= 3000 && symphony.radio.canJumpToPrevious() -> {
                symphony.radio.jumpToPrevious()
                true
            }

            else -> {
                symphony.radio.seek(0)
                false
            }
        }
    }

    fun skip(): Boolean {
        return when {
            !symphony.radio.hasPlayer -> false
            symphony.radio.canJumpToNext() -> {
                symphony.radio.jumpToNext()
                true
            }

            else -> {
                symphony.radio.play(Radio.PlayOptions(index = 0, autostart = false))
                false
            }
        }
    }

    /**
     * [source] is what the user actually tapped — the album, playlist, artist or folder
     * the queue came from. It is the only place that knows, because everything below
     * this point deals in bare song ids, and it is what "recently played" is built on.
     * Null means the caller has no larger thing to point at.
     */
    fun playQueue(
        songIds: List<String>,
        options: Radio.PlayOptions = Radio.PlayOptions(),
        shuffle: Boolean = false,
        source: PlaySource? = null,
    ) {
        symphony.radio.stop(ended = false)
        if (songIds.isEmpty()) {
            return
        }
        symphony.groove.playHistory.onPlayQueue(source)
        symphony.radio.queue.add(
            songIds,
            options = options.run {
                copy(index = if (shuffle) Random.nextInt(songIds.size) else options.index)
            }
        )
        symphony.radio.queue.setShuffleMode(shuffle)
    }

    fun playQueue(
        songId: String,
        options: Radio.PlayOptions = Radio.PlayOptions(),
        shuffle: Boolean = false,
        source: PlaySource? = null,
    ) = playQueue(listOf(songId), options = options, shuffle = shuffle, source = source)
}
