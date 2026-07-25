package io.github.zyrouge.symphony

import android.app.Activity
import android.app.SearchManager
import android.os.Bundle
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MAZIKA: handles `android.media.action.MEDIA_PLAY_FROM_SEARCH`.
 *
 * Android Auto requires media apps to expose this so a voice command such as
 * "play jazz on MAZIKA" can start playback. It is a no-UI trampoline: it starts
 * playback on the shared engine and finishes immediately, so a command given while
 * driving never brings the phone UI to the front.
 *
 * The search itself is not reimplemented here — it delegates to
 * [io.github.zyrouge.symphony.services.radio.RadioSession.playFromSearch], the same
 * path the media-session `onPlayFromSearch` callback uses.
 */
class MediaSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val query = intent?.getStringExtra(SearchManager.QUERY)
            val symphony = SymphonyProvider.get(application)
            // The process may be cold here (started purely by the voice intent).
            symphony.emitReady()
            symphony.groove.coroutineScope.launch {
                // Wait for the library scan, otherwise a cold start searches nothing.
                withTimeoutOrNull(LIBRARY_WAIT_MS) { symphony.groove.readyDeferred.await() }
                runCatching { symphony.radio.session.playFromSearch(query) }
                    .onFailure { Logger.error("MediaSearchActivity", "search playback failed", it) }
            }
        } catch (err: Throwable) {
            Logger.error("MediaSearchActivity", "unable to handle media search intent", err)
        }
        // Theme.NoDisplay requires finishing before onResume.
        finish()
    }

    companion object {
        private const val LIBRARY_WAIT_MS = 10_000L
    }
}
