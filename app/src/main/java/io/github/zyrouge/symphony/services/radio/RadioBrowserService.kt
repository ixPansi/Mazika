package io.github.zyrouge.symphony.services.radio

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import androidx.media.MediaBrowserServiceCompat
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.SymphonyProvider
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.launch
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

    override fun onCreate() {
        super.onCreate()
        symphony = SymphonyProvider.get(application)
        // Ensure the library loads and the media session starts even if no activity
        // ever ran (idempotent — a no-op once the app is already initialised).
        symphony.emitReady()
        browser = RadioBrowser(symphony)
        sessionToken = symphony.radio.session.mediaSession.sessionToken
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        this.clientPackageName = clientPackageName
        return BrowserRoot(MediaId.ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        symphony.groove.coroutineScope.launch {
            val items = try {
                withTimeoutOrNull(LIBRARY_WAIT_MS) { symphony.groove.readyDeferred.await() }
                browser.getChildren(parentId)
            } catch (err: Exception) {
                Logger.error("RadioBrowserService", "onLoadChildren failed for $parentId", err)
                emptyList()
            }
            grantIconPermissions(items)
            result.sendResult(items.toMutableList())
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaItem>>,
    ) {
        result.detach()
        symphony.groove.coroutineScope.launch {
            val items = try {
                withTimeoutOrNull(LIBRARY_WAIT_MS) { symphony.groove.readyDeferred.await() }
                browser.search(query)
            } catch (err: Exception) {
                Logger.error("RadioBrowserService", "onSearch failed for '$query'", err)
                emptyList()
            }
            grantIconPermissions(items)
            result.sendResult(items.toMutableList())
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
