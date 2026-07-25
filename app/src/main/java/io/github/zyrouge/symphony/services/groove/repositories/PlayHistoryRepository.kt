package io.github.zyrouge.symphony.services.groove.repositories

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.PlaySource
import io.github.zyrouge.symphony.services.groove.PlayedItem
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MAZIKA: what the user recently played *from* — a playlist, an album, an artist, a
 * folder, or a lone song when there was no larger thing to point at.
 *
 * Playing is recorded by source rather than by track, because that is what "recently
 * played" means to a listener: you played an album, not forty-three songs.
 * [io.github.zyrouge.symphony.services.radio.RadioShorty.playQueue] is the single funnel
 * every play goes through, so it is the one place that has to hand the source over.
 */
class PlayHistoryRepository(private val symphony: Symphony) {
    private val _recent = MutableStateFlow<List<PlaySource>>(emptyList())
    val recent = _recent.asStateFlow()

    /**
     * The source of the queue currently playing, waiting to be committed once it has
     * been listened to for [DWELL_MS]. Without that wait, opening an album and backing
     * straight out — or skipping through a queue — would fill the row with things that
     * were never actually listened to.
     */
    @Volatile
    private var pending: PlaySource? = null

    fun start() {
        symphony.radio.onPlaybackPositionUpdate.subscribe { position ->
            val source = pending ?: return@subscribe
            if (position.played >= DWELL_MS) {
                pending = null
                record(source)
            }
        }
    }

    /** Called when a queue is started from a known source. */
    fun onPlayQueue(source: PlaySource?) {
        pending = source
    }

    /** Loads the stored history. Runs after the library, since it resolves song paths. */
    suspend fun fetch() {
        try {
            val stored = symphony.database.playHistory.entries()
            _recent.update { stored.mapNotNull { it.toSource() } }
        } catch (err: Exception) {
            Logger.error("PlayHistoryRepository", "unable to load play history", err)
        }
    }

    fun reset() {
        pending = null
        _recent.update { emptyList() }
    }

    private fun record(source: PlaySource) {
        // Move to the front in memory straight away; the row is one per item, so a
        // repeat play moves it rather than duplicating it.
        _recent.update { current ->
            listOf(source) + current.filterNot { it == source }
        }
        symphony.groove.coroutineScope.launch {
            try {
                symphony.database.playHistory.upsert(
                    PlayedItem.of(source, System.currentTimeMillis())
                )
                symphony.database.playHistory.prune(MAX_ENTRIES)
            } catch (err: Exception) {
                Logger.error("PlayHistoryRepository", "unable to record $source", err)
            }
        }
    }

    companion object {
        /** How long a source has to actually play before it counts as played. */
        private const val DWELL_MS = 5_000

        /** Far more than the row shows, but bounded. */
        private const val MAX_ENTRIES = 50
    }
}
