package io.github.zyrouge.symphony.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * MAZIKA: durable storage and processing of user-selected custom playlist covers.
 *
 * A selected image is decoded with sampling (so very large images cannot OOM),
 * rotated according to its EXIF orientation, centre-cropped to a square, scaled
 * down to at most [MAX_SIZE] and written as a WebP file inside the app's internal
 * storage (`files/playlist_covers/`). The stored value kept in the playlist is
 * just the file name; a fresh timestamped name is used on every save so that the
 * image loader's cache key changes automatically when a cover is replaced.
 *
 * All methods here perform disk/IO work and must be called off the main thread.
 */
object PlaylistCovers {
    private const val DIRECTORY = "playlist_covers"
    const val MAX_SIZE = 1024
    private const val QUALITY = 85

    fun coversDir(context: Context): File = File(context.filesDir, DIRECTORY)

    /** Resolves a stored cover file name to an absolute [File]. */
    fun resolveFile(symphony: Symphony, name: String): File =
        File(coversDir(symphony.applicationContext), name)

    /**
     * Reads [sourceUri] through the content resolver, produces a square optimised
     * copy and stores it. Returns the stored file name, or null if the image could
     * not be read/decoded (caller should surface an error, never crash).
     */
    fun saveFromUri(symphony: Symphony, playlistId: String, sourceUri: Uri): String? {
        val context = symphony.applicationContext
        val resolver = context.contentResolver
        try {
            // 1. Read bounds only, so we can pick a safe sample size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            // 2. Decode sampled.
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SIZE)
            }
            var bitmap = resolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            // 3. Respect EXIF orientation.
            val orientation = runCatching {
                resolver.openInputStream(sourceUri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            bitmap = applyOrientation(bitmap, orientation)

            // 4. Centre-crop to a square.
            bitmap = centerCropSquare(bitmap)

            // 5. Scale down to at most MAX_SIZE.
            if (bitmap.width > MAX_SIZE) {
                val scaled = Bitmap.createScaledBitmap(bitmap, MAX_SIZE, MAX_SIZE, true)
                if (scaled != bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            // 6. Write atomically (temp file -> rename) as WebP.
            val dir = coversDir(context)
            if (!dir.exists()) dir.mkdirs()
            val name = "${sanitizeId(playlistId)}_${System.currentTimeMillis()}.webp"
            val destination = File(dir, name)
            val temp = File(dir, "$name.tmp")
            FileOutputStream(temp).use { out ->
                @Suppress("DEPRECATION")
                val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, QUALITY, out)
            }
            bitmap.recycle()
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            return name
        } catch (err: Exception) {
            Logger.error("PlaylistCovers", "failed to save cover for $playlistId", err)
            return null
        }
    }

    /** Deletes a stored cover file by name. Safe to call with a null/blank name. */
    fun delete(symphony: Symphony, name: String?) {
        if (name.isNullOrBlank()) return
        runCatching {
            val file = resolveFile(symphony, name)
            if (file.exists()) file.delete()
        }.onFailure {
            Logger.warn("PlaylistCovers", "failed to delete cover $name: $it")
        }
    }

    /** Removes stored cover files that are no longer referenced by any playlist. */
    fun cleanupOrphans(symphony: Symphony, referencedNames: Set<String>) {
        runCatching {
            val dir = coversDir(symphony.applicationContext)
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { file ->
                if (file.name !in referencedNames) {
                    file.delete()
                }
            }
        }.onFailure {
            Logger.warn("PlaylistCovers", "orphan cleanup failed: $it")
        }
    }

    internal fun sanitizeId(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    internal fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        // Keep the smaller side comfortably above the target so a centre-crop still
        // has enough pixels, halving until further halving would drop below it.
        while ((w / 2) >= target && (h / 2) >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        if (bitmap.width == bitmap.height) return bitmap
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        val cropped = Bitmap.createBitmap(bitmap, x, y, size, size)
        if (cropped != bitmap) bitmap.recycle()
        return cropped
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
            )
            if (rotated != bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }
}
