package io.github.zyrouge.symphony.services.radio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContract
import io.github.zyrouge.symphony.R
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RadioSession(val symphony: Symphony) {
    data class UpdateRequest(
        val song: Song,
        val artworkUri: Uri,
        val artworkBitmap: Bitmap,
        val playbackPosition: RadioPlayer.PlaybackPosition,
        val isPlaying: Boolean,
    )

    internal val mediaSession = MediaSessionCompat(symphony.applicationContext, MEDIA_SESSION_ID)
    private val artworkCacher = RadioArtworkCacher(symphony)
    private val notification = RadioNotification(symphony)

    // MAZIKA: resolves Android Auto browse/search media ids onto the shared engine.
    private val browser by lazy { RadioBrowser(symphony) }

    /**
     * MAZIKA: package name of the browser client currently connected (Android Auto,
     * Assistant, …), recorded by [RadioBrowserService.onGetRoot]. Queue artwork is sent
     * as content URIs, and the receiving process needs read permission for them - unlike
     * browse results, queue items never pass through the service, so they miss its
     * per-item grant. Null until something connects, in which case no grant is needed.
     */
    @Volatile
    internal var browserClientPackage: String? = null

    /**
     * MAZIKA: search-and-play entry point shared by the media session callback and the
     * MEDIA_PLAY_FROM_SEARCH activity, so voice search has exactly one implementation.
     */
    fun playFromSearch(query: String?) = browser.playFromSearch(query)

    private var currentSongId: String? = null
    private var receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                handleAction(action)
            }
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            symphony.applicationContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_PLAY_PAUSE)
                    addAction(ACTION_PREVIOUS)
                    addAction(ACTION_NEXT)
                    addAction(ACTION_STOP)
                },
                Context.RECEIVER_EXPORTED,
                // https://developer.android.com/reference/android/content/Context#RECEIVER_EXPORTED
                // really, RECEIVER_EXPORTED and RECEIVER_NOT_EXPORTED makes no difference.
                // the notification appears perfectly, Pano Scrobbler sees it,
                // Wear OS can send signals to play/pause the app, other media apps can pause it,
                // no clue what the difference here is... but here we are.
            )
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            symphony.applicationContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_PLAY_PAUSE)
                    addAction(ACTION_PREVIOUS)
                    addAction(ACTION_NEXT)
                    addAction(ACTION_STOP)
                },
            )
        }
        mediaSession.setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    super.onPlay()
                    handleAction(ACTION_PLAY_PAUSE)
                }

                override fun onPause() {
                    super.onPause()
                    handleAction(ACTION_PLAY_PAUSE)
                }

                override fun onSkipToPrevious() {
                    super.onSkipToPrevious()
                    handleAction(ACTION_PREVIOUS)
                }

                override fun onSkipToNext() {
                    super.onSkipToNext()
                    handleAction(ACTION_NEXT)
                }

                override fun onStop() {
                    super.onStop()
                    handleAction(ACTION_STOP)
                }

                // MAZIKA: tapping an entry in Android Auto's queue view.
                override fun onSkipToQueueItem(id: Long) {
                    super.onSkipToQueueItem(id)
                    symphony.radio.jumpTo(id.toInt())
                }

                override fun onSeekTo(pos: Long) {
                    super.onSeekTo(pos)
                    symphony.radio.seek(pos)
                }

                // MAZIKA: Android Auto / voice playback entry points. They resolve
                // the stable media id onto a MAZIKA queue via the shared engine, so
                // the car, phone UI and notification stay in sync and the
                // pause/resume fade preference is respected.
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    super.onPlayFromMediaId(mediaId, extras)
                    mediaId?.let { browser.playFromMediaId(it) }
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    super.onPrepareFromMediaId(mediaId, extras)
                    mediaId?.let { browser.playFromMediaId(it) }
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    super.onPlayFromSearch(query, extras)
                    playFromSearch(query)
                }

                // MAZIKA: seek buttons on the Android Auto playback screen, using the
                // same durations as the phone's seek controls. Android Auto only shows
                // custom actions on the full player - the dashboard/mini media card
                // keeps the standard play-pause and next/previous controls.
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    super.onCustomAction(action, extras)
                    when (action) {
                        ACTION_SEEK_BACK -> symphony.radio.shorty.seekFromCurrent(
                            -symphony.settings.seekBackDuration.value
                        )

                        ACTION_SEEK_FORWARD -> symphony.radio.shorty.seekFromCurrent(
                            symphony.settings.seekForwardDuration.value
                        )
                    }
                }

                override fun onRewind() {
                    super.onRewind()
                    val duration = symphony.settings.seekBackDuration.value
                    symphony.radio.shorty.seekFromCurrent(-duration)
                }

                override fun onFastForward() {
                    super.onFastForward()
                    val duration = symphony.settings.seekForwardDuration.value
                    symphony.radio.shorty.seekFromCurrent(duration)
                }

                override fun onMediaButtonEvent(intent: Intent?): Boolean {
                    val handled = super.onMediaButtonEvent(intent)
                    if (handled) {
                        return true
                    }
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    return when (keyEvent?.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                            -> {
                            handleAction(ACTION_PREVIOUS)
                            true
                        }

                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            handleAction(ACTION_NEXT)
                            true
                        }

                        KeyEvent.KEYCODE_MEDIA_CLOSE,
                        KeyEvent.KEYCODE_MEDIA_STOP,
                            -> {
                            handleAction(ACTION_STOP)
                            true
                        }

                        else -> false
                    }
                }
            }
        )
        notification.start()
        updateQueue()
        symphony.radio.onUpdate.subscribe {
            when (it) {
                Radio.Events.Player.Ended -> cancel()
                is Radio.Events.Player -> update()
                // MAZIKA: keep the published queue in step, so Android Auto's queue
                // view reflects adds, removals and reordering immediately.
                is Radio.Events.Queue -> updateQueue()
                else -> {}
            }
        }
    }

    fun handleAction(action: String) {
        when (action) {
            ACTION_PLAY_PAUSE -> symphony.radio.shorty.playPause()
            ACTION_PREVIOUS -> symphony.radio.shorty.previous()
            ACTION_NEXT -> symphony.radio.shorty.skip()
            ACTION_STOP -> symphony.radio.stop()
        }
    }

    fun cancel() {
        notification.cancel()
        mediaSession.isActive = false
    }

    fun destroy() {
        cancel()
        symphony.applicationContext.unregisterReceiver(receiver)
    }

    fun createEqualizerActivityContract() = object : ActivityResultContract<Unit, Unit>() {
        override fun createIntent(
            context: Context,
            input: Unit,
        ) = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, symphony.applicationContext.packageName)
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, symphony.radio.audioSessionId ?: 0)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ) {
        }
    }

    /**
     * MAZIKA: publishes the play queue to the media session so Android Auto shows the
     * real upcoming songs (and lets the user jump to one) instead of falling back to
     * browse content. The queue item id is the queue index, which is what
     * onSkipToQueueItem receives back.
     */
    private fun updateQueue() {
        symphony.groove.coroutineScope.launch {
            grantArtworkAccessToBrowserClient()
            val items = symphony.radio.queue.currentQueue
                .take(MAX_QUEUE_ITEMS)
                .mapIndexedNotNull { index, songId ->
                    val song = symphony.groove.song.get(songId) ?: return@mapIndexedNotNull null
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId(MediaId.of(MediaId.TYPE_SONG, songId))
                        .setTitle(song.title)
                        .setSubtitle(song.artists.joinToString().ifEmpty { null })
                        // Artwork as a content URI, never a bitmap: 200 bitmaps in one
                        // queue parcel is a guaranteed TransactionTooLargeException.
                        .apply { browser.artworkUriFor(songId)?.let { setIconUri(it) } }
                        .build()
                    MediaSessionCompat.QueueItem(description, index.toLong())
                }
            withContext(Dispatchers.Main) {
                runCatching {
                    mediaSession.setQueue(items)
                    mediaSession.setQueueTitle(symphony.t.Queue)
                }.onFailure {
                    Logger.warn("RadioSession", "unable to publish queue: $it")
                }
            }
        }
    }

    /**
     * Lets the connected browser client read the two artwork directories.
     *
     * Two prefix grants rather than one grant per queue item: a 200-song queue would
     * otherwise mean 200 binder calls on every queue change. The provider itself stays
     * unexported, read-only and path-validated, so the widened scope is still just
     * "the cover images this app owns", handed to the one client that asked to browse.
     */
    private fun grantArtworkAccessToBrowserClient() {
        val client = browserClientPackage ?: return
        val context = symphony.applicationContext
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        ArtworkProvider.artworkRootUris(context).forEach { uri ->
            try {
                context.grantUriPermission(client, uri, flags)
            } catch (err: Exception) {
                Logger.warn("RadioSession", "unable to grant $uri to $client: $err")
            }
        }
    }

    private fun update() {
        symphony.groove.coroutineScope.launch {
            updateAsync()
        }
    }

    private suspend fun updateAsync() {
        val song = symphony.radio.queue.currentSongId
            ?.let { symphony.groove.song.get(it) } ?: return
        currentSongId = song.id
        val artworkUri = symphony.groove.song.getArtworkUri(song.id)
        val artworkBitmap = artworkCacher.getArtwork(song)
        val playbackPosition = symphony.radio.currentPlaybackPosition
            ?: RadioPlayer.PlaybackPosition(played = 0L, total = song.duration)
        val isPlaying = symphony.radio.isPlaying
        if (currentSongId != song.id) {
            return
        }
        val req = UpdateRequest(
            song = song,
            artworkUri = artworkUri,
            artworkBitmap = artworkBitmap,
            playbackPosition = playbackPosition,
            isPlaying = isPlaying,
        )
        updateSession(req)
        notification.update(req)
    }

    private fun updateSession(req: UpdateRequest) {
        ensureEnabled()
        mediaSession.run {
            setMetadata(
                MediaMetadataCompat.Builder().run {
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, req.song.title)
                    if (req.song.artists.isNotEmpty()) {
                        putString(
                            MediaMetadataCompat.METADATA_KEY_ARTIST,
                            req.song.artists.joinToString()
                        )
                    }
                    putString(MediaMetadataCompat.METADATA_KEY_ALBUM, req.song.album)
                    req.artworkBitmap.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                    }
                    putLong(
                        MediaMetadataCompat.METADATA_KEY_DURATION,
                        req.playbackPosition.total.toLong()
                    )
                    build()
                }
            )
            setPlaybackState(
                PlaybackStateCompat.Builder().run {
                    setState(
                        when {
                            req.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                            else -> PlaybackStateCompat.STATE_PAUSED
                        },
                        req.playbackPosition.played.toLong(),
                        1f
                    )
                    setActions(
                        PlaybackStateCompat.ACTION_PLAY
                                or PlaybackStateCompat.ACTION_PAUSE
                                or PlaybackStateCompat.ACTION_PLAY_PAUSE
                                or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                                or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                                or PlaybackStateCompat.ACTION_STOP
                                or PlaybackStateCompat.ACTION_REWIND
                                or PlaybackStateCompat.ACTION_FAST_FORWARD
                                or PlaybackStateCompat.ACTION_SEEK_TO
                                // MAZIKA: enable Android Auto / voice playback.
                                or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                                or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                                or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
                                or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
                    )
                    addCustomAction(
                        PlaybackStateCompat.CustomAction.Builder(
                            ACTION_SEEK_BACK,
                            symphony.t.XSecs("-${symphony.settings.seekBackDuration.value}"),
                            R.drawable.material_icon_fast_rewind,
                        ).build()
                    )
                    addCustomAction(
                        PlaybackStateCompat.CustomAction.Builder(
                            ACTION_SEEK_FORWARD,
                            symphony.t.XSecs("+${symphony.settings.seekForwardDuration.value}"),
                            R.drawable.material_icon_fast_forward,
                        ).build()
                    )
                    build()
                }
            )
        }
    }

    private fun ensureEnabled() {
        if (!mediaSession.isActive) {
            mediaSession.isActive = true
        }
    }

    companion object {
        val MEDIA_SESSION_ID = "${R.string.app_name}_media_session"

        val ACTION_PLAY_PAUSE = "${R.string.app_name}_play_pause"
        val ACTION_PREVIOUS = "${R.string.app_name}_previous"
        val ACTION_NEXT = "${R.string.app_name}_next"
        val ACTION_STOP = "${R.string.app_name}_stop"

        // MAZIKA: custom transport actions surfaced on the Android Auto player.
        const val ACTION_SEEK_BACK = "com.mazika.musicplayer.SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.mazika.musicplayer.SEEK_FORWARD"

        // Android Auto refuses very large queues; this is well past what is useful.
        private const val MAX_QUEUE_ITEMS = 200
    }
}
