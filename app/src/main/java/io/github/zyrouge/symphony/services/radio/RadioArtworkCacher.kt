package io.github.zyrouge.symphony.services.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.Assets

class RadioArtworkCacher(val symphony: Symphony) {
    private var default: Bitmap? = null
    private val cached = mutableMapOf<String, Bitmap>()
    private val generations = mutableMapOf<String, Long>()
    private val cacheLock = Any()
    private val cacheLimit = 3

    /** MAZIKA: drops a song's cached bitmap so the next read re-decodes it. Needed when
     * the user replaces a song's cover — the id has not changed, only the image. */
    fun invalidate(songId: String) {
        synchronized(cacheLock) {
            generations[songId] = (generations[songId] ?: 0L) + 1L
            cached.remove(songId)
        }
    }

    suspend fun getArtwork(song: Song): Bitmap {
        val generation = synchronized(cacheLock) {
            cached[song.id]?.let { return it }
            generations[song.id] ?: 0L
        }
        val result = symphony.applicationContext.imageLoader
            .execute(song.createArtworkImageRequest(symphony).build())
        val bitmap = result.drawable?.toBitmap() ?: getDefaultArtwork()
        synchronized(cacheLock) {
            // A cover replacement may have invalidated this decode while Coil was
            // working. Never let that old result become the next cached artwork.
            if ((generations[song.id] ?: 0L) == generation) {
                updateCache(song.id, bitmap)
            }
        }
        return bitmap
    }

    private fun getDefaultArtwork(): Bitmap {
        return default ?: run {
            val bitmap = BitmapFactory.decodeResource(
                symphony.applicationContext.resources,
                Assets.placeholderDarkId,
            )
            default = bitmap
            bitmap
        }
    }

    private fun updateCache(key: String, value: Bitmap) {
        if (!cached.containsKey(key) && cached.size >= cacheLimit) {
            cached.remove(cached.keys.first())
        }
        cached[key] = value
    }
}
