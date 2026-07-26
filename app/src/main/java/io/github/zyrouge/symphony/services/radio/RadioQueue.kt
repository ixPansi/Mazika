package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.concurrentListOf

class RadioQueue(private val symphony: Symphony) {
    data class PlayTarget(
        val songId: String,
        internal val preferredIndex: Int,
        internal val version: Long,
    )

    enum class LoopMode {
        None,
        Queue,
        Song;

        companion object {
            val values = enumValues<LoopMode>()
        }
    }

    val originalQueue = concurrentListOf<String>()
    val currentQueue = concurrentListOf<String>()
    private var mutationVersion = 0L

    var currentSongIndex = -1
        internal set(value) {
            field = value
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.IndexChanged)
        }

    var currentShuffleMode = false
        private set(value) {
            field = value
            symphony.radio.onUpdate.dispatch(Radio.Events.QueueOption.ShuffleModeChanged)
        }

    var currentLoopMode = LoopMode.None
        private set(value) {
            field = value
            symphony.radio.onUpdate.dispatch(Radio.Events.QueueOption.LoopModeChanged)
        }

    val currentSongId: String?
        get() = getSongIdAt(currentSongIndex)

    fun hasSongAt(index: Int) = index > -1 && index < currentQueue.size
    fun getSongIdAt(index: Int) = if (hasSongAt(index)) currentQueue[index] else null

    @Synchronized
    fun resolvePlayTarget(index: Int): PlayTarget? {
        val songId = getSongIdAt(index) ?: return null
        return PlayTarget(
            songId = songId,
            preferredIndex = index,
            version = mutationVersion,
        )
    }

    @Synchronized
    fun resolvePlayTarget(songId: String, preferredIndex: Int): PlayTarget? {
        val index = preferredIndex
            .takeIf { getSongIdAt(it) == songId }
            ?: currentQueue.indexOf(songId).takeIf { it >= 0 }
            ?: return null
        return PlayTarget(
            songId = songId,
            preferredIndex = index,
            version = mutationVersion,
        )
    }

    /** Holds the queue occurrence stable while [action] commits its matching player. */
    @Synchronized
    fun withResolvedPlayTarget(target: PlayTarget, action: (Int) -> Unit): Boolean {
        val index = findPlayTargetIndex(target) ?: return false
        action(index)
        return true
    }

    private fun findPlayTargetIndex(target: PlayTarget): Int? {
        return resolveUnchangedPlayTarget(
            values = currentQueue,
            value = target.songId,
            preferredIndex = target.preferredIndex,
            targetVersion = target.version,
            currentVersion = mutationVersion,
        )
    }

    @Synchronized
    fun reset() {
        originalQueue.clear()
        currentQueue.clear()
        mutationVersion++
        currentSongIndex = -1
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Cleared)
    }

    fun add(
        songIds: List<String>,
        index: Int? = null,
        options: Radio.PlayOptions = Radio.PlayOptions(),
    ) {
        synchronized(this) {
            index?.let {
                originalQueue.addAll(it, songIds)
                currentQueue.addAll(it, songIds)
                if (it <= currentSongIndex) {
                    currentSongIndex += songIds.size
                }
            } ?: run {
                originalQueue.addAll(songIds)
                currentQueue.addAll(songIds)
            }
            mutationVersion++
        }
        afterAdd(options)
    }

    fun add(
        songId: String,
        index: Int? = null,
        options: Radio.PlayOptions = Radio.PlayOptions(),
    ) = add(listOf(songId), index, options)

    private fun afterAdd(options: Radio.PlayOptions) {
        if (!symphony.radio.hasPlayer) {
            symphony.radio.play(options)
        }
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
    }

    fun remove(index: Int) {
        var playReplacement = false
        var replacementIndex = -1
        val removed = synchronized(this) {
            if (!hasSongAt(index)) return@synchronized false
            val songId = currentQueue[index]
            removeFromOriginalQueue(index, songId)
            currentQueue.removeAt(index)
            mutationVersion++
            when {
                currentSongIndex == index -> {
                    currentSongIndex = index.coerceAtMost(currentQueue.lastIndex)
                    playReplacement = true
                    replacementIndex = currentSongIndex
                }

                index < currentSongIndex -> currentSongIndex--
            }
            true
        }
        if (!removed) return
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        if (playReplacement) {
            symphony.radio.play(Radio.PlayOptions(index = replacementIndex))
        }
    }

    internal fun removeWithoutPlayback(target: PlayTarget): Int? {
        var removedIndex: Int? = null
        synchronized(this) {
            val index = findPlayTargetIndex(target) ?: return@synchronized
            val songId = currentQueue[index]
            removeFromOriginalQueue(index, songId)
            currentQueue.removeAt(index)
            mutationVersion++
            if (index < currentSongIndex) {
                currentSongIndex--
            } else if (index == currentSongIndex && currentSongIndex >= currentQueue.size) {
                currentSongIndex = currentQueue.lastIndex
            }
            removedIndex = index
        }
        if (removedIndex != null) {
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        }
        return removedIndex
    }

    internal fun removeCurrentWithoutPlayback(expectedSongId: String): Int? {
        var removedIndex: Int? = null
        synchronized(this) {
            val index = currentSongIndex
            if (getSongIdAt(index) != expectedSongId) return@synchronized
            removeFromOriginalQueue(index, expectedSongId)
            currentQueue.removeAt(index)
            mutationVersion++
            if (currentSongIndex >= currentQueue.size) {
                currentSongIndex = currentQueue.lastIndex
            }
            removedIndex = index
        }
        if (removedIndex != null) {
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        }
        return removedIndex
    }

    internal fun removeSongWithoutPlayback(songId: String, preferredIndex: Int): Int? {
        var removedIndex: Int? = null
        synchronized(this) {
            val index = preferredIndex
                .takeIf { getSongIdAt(it) == songId }
                ?: currentQueue.indexOf(songId).takeIf { it >= 0 }
                ?: return@synchronized
            removeFromOriginalQueue(index, songId)
            currentQueue.removeAt(index)
            mutationVersion++
            if (index < currentSongIndex) {
                currentSongIndex--
            } else if (index == currentSongIndex && currentSongIndex >= currentQueue.size) {
                currentSongIndex = currentQueue.lastIndex
            }
            removedIndex = index
        }
        if (removedIndex != null) {
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        }
        return removedIndex
    }

    /**
     * MAZIKA: moves a queued song, keeping the currently playing track pointed at the
     * same song so reordering never interrupts or changes playback.
     *
     * Only [currentQueue] is reordered - [originalQueue] is the pre-shuffle ordering
     * used to restore the unshuffled queue, so it is left alone while shuffled.
     */
    @Synchronized
    fun move(from: Int, to: Int) {
        moveInternal(from, to)
    }

    /** Applies a UI drag only if no other queue mutation replaced its source list. */
    @Synchronized
    fun moveIfUnchanged(expectedQueue: List<String>, from: Int, to: Int): Boolean {
        if (currentQueue != expectedQueue) return false
        moveInternal(from, to)
        return true
    }

    private fun moveInternal(from: Int, to: Int) {
        if (from == to) return
        if (!hasSongAt(from) || !hasSongAt(to)) return
        val playingIndex = currentSongIndex
            .takeIf { hasSongAt(it) }
            ?.let { indexAfterMove(it, from, to) }
        currentQueue.add(to, currentQueue.removeAt(from))
        if (!currentShuffleMode && from < originalQueue.size && to < originalQueue.size) {
            originalQueue.add(to, originalQueue.removeAt(from))
        }
        mutationVersion++
        // Follow the exact queue occurrence. Looking the song id up with indexOf would
        // jump to the first duplicate instead of retaining the playing row's identity.
        playingIndex?.let { currentSongIndex = it }
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.IndexChanged)
    }

    fun remove(indices: List<Int>) {
        var currentSongRemoved = false
        var replacementIndex = -1
        val removed = synchronized(this) {
            val sortedIndices = indices
                .distinct()
                .filter(::hasSongAt)
                .sortedDescending()
            if (sortedIndices.isEmpty()) return@synchronized false

            val previousCurrentIndex = currentSongIndex
            val removedSongIds = sortedIndices.map { currentQueue[it] }
            if (currentShuffleMode) {
                removedSongIds.forEach { songId ->
                    originalQueue.indexOf(songId)
                        .takeIf { it >= 0 }
                        ?.let(originalQueue::removeAt)
                }
            } else {
                sortedIndices.forEach(originalQueue::removeAt)
            }
            sortedIndices.forEach(currentQueue::removeAt)
            mutationVersion++

            currentSongRemoved = previousCurrentIndex in sortedIndices
            replacementIndex = indexAfterRemovals(
                index = previousCurrentIndex,
                removedIndices = sortedIndices,
                remainingSize = currentQueue.size,
            )
            currentSongIndex = replacementIndex
            true
        }
        if (!removed) return
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        if (currentSongRemoved) {
            symphony.radio.play(Radio.PlayOptions(index = replacementIndex))
        }
    }

    private fun removeFromOriginalQueue(currentIndex: Int, songId: String) {
        val originalIndex = when {
            !currentShuffleMode -> currentIndex
            else -> originalQueue.indexOf(songId)
        }
        if (originalIndex in originalQueue.indices) {
            originalQueue.removeAt(originalIndex)
        }
    }

    fun setLoopMode(loopMode: LoopMode) {
        currentLoopMode = loopMode
    }

    fun toggleLoopMode() {
        val next = (currentLoopMode.ordinal + 1) % LoopMode.values.size
        setLoopMode(LoopMode.values[next])
    }

    fun toggleShuffleMode() = setShuffleMode(!currentShuffleMode)

    @Synchronized
    fun setShuffleMode(to: Boolean) {
        currentShuffleMode = to
        if (currentQueue.isNotEmpty()) {
            val currentSongId = getSongIdAt(currentSongIndex) ?: getSongIdAt(0)!!
            currentSongIndex = if (currentShuffleMode) {
                val newQueue = originalQueue.toMutableList()
                newQueue.removeAt(currentSongIndex)
                newQueue.shuffle()
                newQueue.add(0, currentSongId)
                currentQueue.clear()
                currentQueue.addAll(newQueue)
                0
            } else {
                currentQueue.clear()
                currentQueue.addAll(originalQueue)
                originalQueue.indexOfFirst { it == currentSongId }
            }
        }
        mutationVersion++
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
    }

    fun isEmpty() = originalQueue.isEmpty()

    data class Serialized(
        val currentSongIndex: Int,
        val playedDuration: Long,
        val originalQueue: List<String>,
        val currentQueue: List<String>,
        val shuffled: Boolean,
    ) {
        fun serialize() =
            listOf(
                currentSongIndex.toString(),
                playedDuration.toString(),
                originalQueue.joinToString(","),
                currentQueue.joinToString(","),
                shuffled.toString(),
            ).joinToString(";")

        companion object {
            fun create(queue: RadioQueue, playbackPosition: RadioPlayer.PlaybackPosition) =
                Serialized(
                    currentSongIndex = queue.currentSongIndex,
                    playedDuration = playbackPosition.played,
                    originalQueue = queue.originalQueue.toList(),
                    currentQueue = queue.currentQueue.toList(),
                    shuffled = queue.currentShuffleMode,
                )

            fun parse(data: String): Serialized? {
                try {
                    val semi = data.split(";")
                    return Serialized(
                        currentSongIndex = semi[0].toInt(),
                        playedDuration = semi[1].toLong(),
                        originalQueue = semi[2].split(","),
                        currentQueue = semi[3].split(","),
                        shuffled = semi[4].toBoolean(),
                    )
                } catch (_: Exception) {
                }
                return null
            }
        }
    }

    fun restore(serialized: Serialized) {
        if (serialized.originalQueue.isNotEmpty()) {
            symphony.radio.stop(ended = false)
            synchronized(this) {
                originalQueue.clear()
                originalQueue.addAll(serialized.originalQueue)
                currentQueue.clear()
                currentQueue.addAll(serialized.currentQueue)
                mutationVersion++
            }
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
            currentShuffleMode = serialized.shuffled
            afterAdd(
                Radio.PlayOptions(
                    index = serialized.currentSongIndex,
                    autostart = false,
                    startPosition = serialized.playedDuration,
                )
            )
        }
    }
}

internal fun indexAfterMove(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    from < to && index > from && index <= to -> index - 1
    to < from && index >= to && index < from -> index + 1
    else -> index
}

internal fun replacementIndexAfterRemoval(removedIndex: Int, remainingSize: Int): Int? =
    if (remainingSize <= 0) null else removedIndex.coerceIn(0, remainingSize - 1)

internal fun indexAfterRemovals(
    index: Int,
    removedIndices: List<Int>,
    remainingSize: Int,
): Int {
    if (index < 0 || remainingSize <= 0) return -1
    val shiftedIndex = index - removedIndices.count { it < index }
    return if (index in removedIndices) {
        shiftedIndex.coerceAtMost(remainingSize - 1)
    } else {
        shiftedIndex
    }
}

internal fun <T> resolveUnchangedPlayTarget(
    values: List<T>,
    value: T,
    preferredIndex: Int,
    targetVersion: Long,
    currentVersion: Long,
): Int? {
    if (targetVersion != currentVersion) return null
    return preferredIndex.takeIf {
        it in values.indices && values[it] == value
    }
}
