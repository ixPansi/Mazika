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
    private lateinit var validator: PackageValidator
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
            // Publish the token before anything else that can fail. Without it
            // MediaBrowserServiceCompat parks every incoming connection indefinitely, so
            // a host that binds gets neither content nor an error - indistinguishable,
            // from the car's side, from the app not being there at all.
            sessionToken = symphony.radio.session.mediaSession.sessionToken
            validator = PackageValidator(applicationContext)
        } catch (err: Throwable) {
            Logger.error("RadioBrowserService", "initialisation failed", err)
            stopSelf()
            return
        }
        // Past this point a failure degrades browsing but must not leave the service
        // bound-but-mute, so it is guarded separately from the token.
        runCatching {
            browser = RadioBrowser(symphony)
            artworkChangeUnsubscribe = symphony.radio.session.onBrowseChildrenChanged
                .subscribe { parentIds ->
                    symphony.groove.coroutineScope.launch(Dispatchers.Main) {
                        if (!destroyed) notifyBrowseParents(parentIds)
                    }
                }
        }.onFailure {
            Logger.error("RadioBrowserService", "browse setup failed", it)
        }
    }

    /**
     * Re-registers whichever client is currently talking to us.
     *
     * Artwork grants are per-client, and `onGetRoot` is not guaranteed to run again when
     * a host reconnects or when a second one attaches. Registering here as well means a
     * browse result never carries icon uris the caller cannot open.
     */
    private fun registerCurrentBrowser() {
        runCatching { currentBrowserInfo?.packageName }
            .getOrNull()
            ?.let { symphony.radio.session.registerBrowserClient(it) }
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
    ): BrowserRoot? {
        // This runs on a binder thread. Anything thrown here propagates to the host,
        // which drops the connection and caches the failure - so the whole body is
        // guarded, unlike before, where an uninitialised `symphony` after a failed
        // bootstrap threw straight across the binder.
        return try {
            if (!validator.isKnownCaller(clientPackageName, clientUid)) {
                null
            } else {
                // Prefix access is granted immediately, including for queue URIs that
                // were published before this browser connected.
                symphony.radio.session.registerBrowserClient(clientPackageName)
                BrowserRoot(MediaId.ROOT, null)
            }
        } catch (err: Throwable) {
            Logger.error("RadioBrowserService", "onGetRoot failed for $clientPackageName", err)
            // An empty-but-valid root keeps the handshake honest: the host connects and
            // simply sees nothing, rather than treating the app as broken.
            BrowserRoot(MediaId.ROOT, null)
        }
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        registerCurrentBrowser()
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
        registerCurrentBrowser()
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
