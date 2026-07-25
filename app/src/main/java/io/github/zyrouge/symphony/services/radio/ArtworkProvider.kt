package io.github.zyrouge.symphony.services.radio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.zyrouge.symphony.utils.Logger
import java.io.File

/**
 * MAZIKA: read-only content provider that exposes song/album/artist artwork and
 * custom playlist covers to the Android Auto host process (which runs in another
 * app and cannot read the app's private files directly).
 *
 * The provider is not exported; [RadioBrowserService] grants read permission for
 * the specific icon URIs to the connecting browser client. Access is strictly
 * read-only and path-validated to prevent traversal outside the two cover
 * directories.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val baseDir = when (segments[0]) {
            SEGMENT_COVERS -> File(ctx.dataDir, "covers")
            SEGMENT_PLAYLIST_COVERS -> File(ctx.filesDir, "playlist_covers")
            SEGMENT_SONG_COVERS -> File(ctx.filesDir, "song_covers")
            else -> return null
        }
        val name = try {
            MediaId.decode(segments[1])
        } catch (_: Exception) {
            return null
        }
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) {
            return null
        }
        return try {
            val file = File(baseDir, name).canonicalFile
            val allowedPrefix = baseDir.canonicalFile.path + File.separator
            if (!file.path.startsWith(allowedPrefix) || !file.exists()) {
                return null
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (err: Exception) {
            Logger.warn("ArtworkProvider", "openFile failed for $uri: $err")
            null
        }
    }

    override fun getType(uri: Uri) = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val SEGMENT_COVERS = "covers"
        private const val SEGMENT_PLAYLIST_COVERS = "playlist_covers"
        private const val SEGMENT_SONG_COVERS = "song_covers"

        fun authority(context: Context) = "${context.packageName}.artwork"

        /** Uri for a file inside the app's artwork cache (dataDir/covers). */
        fun coversUri(context: Context, name: String): Uri = Uri.parse(
            "content://${authority(context)}/$SEGMENT_COVERS/${MediaId.encode(name)}"
        )

        /** Uri for a custom song cover (filesDir/song_covers). */
        fun songCoverUri(context: Context, name: String): Uri = Uri.parse(
            "content://${authority(context)}/$SEGMENT_SONG_COVERS/${MediaId.encode(name)}"
        )

        /** Uri for a custom playlist cover (filesDir/playlist_covers). */
        fun playlistCoverUri(context: Context, name: String): Uri = Uri.parse(
            "content://${authority(context)}/$SEGMENT_PLAYLIST_COVERS/${MediaId.encode(name)}"
        )
    }
}
