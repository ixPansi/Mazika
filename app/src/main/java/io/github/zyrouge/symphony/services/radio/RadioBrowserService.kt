package io.github.zyrouge.symphony.services.radio

import android.os.Bundle
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MAZIKA: media browser service that lets Android Auto browse and play the local
 * library. It shares the existing [RadioSession] MediaSessionCompat (one session,
 * one queue) and the process-scoped [Symphony] instance, so it works even when the
 * phone UI process was started cold by Android Auto with no activity present.
 */
class RadioBrowserService : MediaBrowserServiceCompat() {
    private lateinit var symphony: Symphony
    private lateinit var browser: RadioBrowser
    private var artworkChangeUnsubscribe: EventUnsubscribeFn? = null
    private val delayedReadinessParents = ConcurrentHashMap.newKeySet<String>()
    private val readinessRefreshScheduled = AtomicBoolean()
    @Volatile
    private var destroyed = false

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
            artworkChangeUnsubscribe = symphony.radio.session.onBrowseChildrenChanged
                .subscribe { parentIds ->
                    symphony.groove.coroutineScope.launch(Dispatchers.Main) {
                        if (!destroyed) notifyBrowseParents(parentIds)
                    }
                }
        } catch (err: Throwable) {
            Logger.error("RadioBrowserService", "initialisation failed", err)
            stopSelf()
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
        // Prefix access is granted immediately, including for queue URIs that were
        // published before this browser connected.
        symphony.radio.session.registerBrowserClient(clientPackageName)
        return BrowserRoot(MediaId.ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        ensureReady()
        symphony.groove.coroutineScope.launch {
            val items = try {
                val ready = withTimeoutOrNull(LIBRARY_WAIT_MS) {
                    symphony.groove.readyDeferred.await()
                } == true
                if (!ready) scheduleReadinessRefresh(parentId)
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
                val ready = withTimeoutOrNull(LIBRARY_WAIT_MS) {
                    symphony.groove.readyDeferred.await()
                } == true
                if (!ready) scheduleReadinessRefresh()
                browser.search(query)
            } catch (err: Exception) {
                Logger.error("RadioBrowserService", "onSearch failed for '$query'", err)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                try {
                    result.sendResult(items.toMutableList())
                } catch (err: Exception) {
                    Logger.error("RadioBrowserService", "sending search results failed", err)
                }
            }
        }
    }

    private fun scheduleReadinessRefresh(parentId: String? = null) {
        parentId?.let(delayedReadinessParents::add)
        if (!readinessRefreshScheduled.compareAndSet(false, true)) return
        symphony.groove.coroutineScope.launch {
            try {
                symphony.groove.readyDeferred.await()
                withContext(Dispatchers.Main) {
                    if (destroyed) return@withContext
                    notifyBrowseParents(
                        buildSet {
                            add(MediaId.ROOT)
                            addAll(AndroidAutoCategory.entries.map { it.mediaId })
                            addAll(delayedReadinessParents)
                        }
                    )
                }
            } catch (err: Exception) {
                Logger.warn("RadioBrowserService", "delayed readiness refresh failed: $err")
            }
        }
    }

    private fun notifyBrowseParents(parentIds: Set<String>) {
        parentIds.forEach { parentId ->
            runCatching { notifyChildrenChanged(parentId) }
                .onFailure {
                    Logger.warn(
                        "RadioBrowserService",
                        "unable to notify children changed for $parentId: $it",
                    )
                }
        }
    }

    override fun onDestroy() {
        destroyed = true
        artworkChangeUnsubscribe?.invoke()
        artworkChangeUnsubscribe = null
        super.onDestroy()
    }

    companion object {
        private const val LIBRARY_WAIT_MS = 10_000L
    }
}
