package io.github.zyrouge.symphony.services.radio

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat.MediaItem
import androidx.media.MediaBrowserServiceCompat
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.SymphonyProvider
import io.github.zyrouge.symphony.utils.EventUnsubscribeFn
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MAZIKA: media browser service that lets Android Auto browse and play the local
 * library. It shares the existing [RadioSession] MediaSessionCompat (one session,
 * one queue) and the process-scoped [Symphony] instance, so it works even when the
 * phone UI process was started cold by Android Auto with no activity present.
 */
class RadioBrowserService : MediaBrowserServiceCompat() {
    private lateinit var symphony: Symphony
    private lateinit var browser: RadioBrowser
    private var clientPackageName: String? = null
    private var unsubscribeRadio: EventUnsubscribeFn? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // This service is exported, so the system (media resumption, Assistant,
        // Android Auto) can create it independently of the activity — potentially
        // before any crash handler is installed. Keep the work here minimal and
        // guarded: publishing the session token is all that is needed to connect.
        // Bootstrapping the app (emitReady -> media session, receivers, library
        // scan) is deferred to the first browse/search request instead, so merely
        // being bound cannot start playback machinery or take down the process.
        try {
            symphony = SymphonyProvider.get(application)
            browser = RadioBrowser(symphony)
            sessionToken = symphony.radio.session.mediaSession.sessionToken
            // The Queue and Lyrics categories mirror live state, so the browser has to
            // be told when they go stale - otherwise the car keeps showing the queue as
            // it was when the pane was first opened.
            unsubscribeRadio = symphony.radio.onUpdate.subscribe { event ->
                when (event) {
                    is Radio.Events.Queue -> notifyChildrenChangedOnMain(MediaId.CATEGORY_QUEUE)
                    // Only a song change invalidates the lyrics - not a pause, and
                    // certainly not a seek, which is what tapping a lyric line does.
                    Radio.Events.Player.Staged,
                    Radio.Events.Player.Started,
                    Radio.Events.Player.Ended,
                        -> notifyChildrenChangedOnMain(MediaId.CATEGORY_LYRICS)

                    else -> {}
                }
            }
        } catch (err: Throwable) {
            Logger.error("RadioBrowserService", "initialisation failed", err)
            stopSelf()
        }
    }

    override fun onDestroy() {
        unsubscribeRadio?.invoke()
        unsubscribeRadio = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** [notifyChildrenChanged] is main-thread only; radio events arrive from anywhere. */
    private fun notifyChildrenChangedOnMain(parentId: String) {
        mainHandler.post {
            try {
                notifyChildrenChanged(parentId)
            } catch (err: Exception) {
                Logger.warn("RadioBrowserService", "notifyChildrenChanged($parentId): $err")
            }
        }
    }

    /** Initialises the app on demand, when a browser client actually asks for content. */
    private fun ensureReady() {
        try {
            symphony.emitReady()
        } catch (err: Throwable) {
            Logger.error("RadioBrowserService", "emitReady failed", err)
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        this.clientPackageName = clientPackageName
        // Queue artwork is published through the media session rather than through this
        // service, so RadioSession needs to know who to grant read access to.
        symphony.radio.session.browserClientPackage = clientPackageName
        return BrowserRoot(MediaId.ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        ensureReady()
        symphony.groove.coroutineScope.launch {
            val items = try {
                withTimeoutOrNull(LIBRARY_WAIT_MS) { symphony.groove.readyDeferred.await() }
                browser.getChildren(parentId)
            } catch (err: Exception) {
                Logger.error("RadioBrowserService", "onLoadChildren failed for $parentId", err)
                emptyList()
            }
            // MediaBrowserServiceCompat is not thread safe: sendResult must be
            // delivered on the service's main thread, and it throws if the result
            // was already sent. Both calls stay inside the guard so a failure here
            // can never escape into the shared coroutine scope.
            withContext(Dispatchers.Main) {
                try {
                    grantIconPermissions(items)
                    result.sendResult(items.toMutableList())
                } catch (err: Exception) {
                    Logger.error("RadioBrowserService", "sending children failed", err)
                }
            }
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaItem>>,
    ) {
        result.detach()
        ensureReady()
        symphony.groove.coroutineScope.launch {
            val items = try {
                withTimeoutOrNull(LIBRARY_WAIT_MS) { symphony.groove.readyDeferred.await() }
                browser.search(query)
            } catch (err: Exception) {
                Logger.error("RadioBrowserService", "onSearch failed for '$query'", err)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                try {
                    grantIconPermissions(items)
                    result.sendResult(items.toMutableList())
                } catch (err: Exception) {
                    Logger.error("RadioBrowserService", "sending search results failed", err)
                }
            }
        }
    }

    // Grant the connecting browser client (e.g. Android Auto) read access to the
    // artwork content URIs referenced by the returned items.
    private fun grantIconPermissions(items: List<MediaItem>) {
        val client = clientPackageName ?: return
        val authority = ArtworkProvider.authority(this)
        items.forEach { item ->
            val uri = item.description.iconUri ?: return@forEach
            if (uri.scheme == "content" && uri.authority == authority) {
                try {
                    grantUriPermission(client, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val LIBRARY_WAIT_MS = 10_000L
    }
}
