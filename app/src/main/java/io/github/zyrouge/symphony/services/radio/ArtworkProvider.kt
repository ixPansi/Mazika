package io.github.zyrouge.symphony.services.radio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.zyrouge.symphony.utils.Logger
import java.io.File

/**
 * MAZIKA: read-only content provider that exposes song/album/artist artwork and
 * custom playlist covers to the Android Auto host process (which runs in another
 * app and cannot read the app's private files directly).
 *
 * The provider is not exported; [RadioBrowserService] grants prefix read access
 * to connected browser clients. Access is strictly read-only and path-validated
 * to prevent traversal outside the three cover directories.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val name = try {
            MediaId.decode(segments[1])
        } catch (_: Exception) {
            return null
        }
        return try {
            val file = resolveArtworkFile(ctx, segments[0], name) ?: return null
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (err: Exception) {
            Logger.warn("ArtworkProvider", "openFile failed for $uri: $err")
            null
        }
    }

    override fun getType(uri: Uri): String = when (uri.pathSegments.firstOrNull()) {
        // Custom covers are always written as WebP by CustomCovers. Being specific
        // matters: some image pipelines refuse to decode a wildcard type.
        SEGMENT_PLAYLIST_COVERS, SEGMENT_SONG_COVERS -> "image/webp"
        else -> "image/*"
    }

    /**
     * MAZIKA: answers the metadata a client asks for before opening the file.
     *
     * This used to return null. Clients that probe DISPLAY_NAME/SIZE first - which
     * several image loaders do - got nothing back and gave up without ever calling
     * [openFile]. Mirrors what FileProvider returns.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val name = try {
            MediaId.decode(segments[1])
        } catch (_: Exception) {
            return null
        }
        val file = resolveArtworkFile(ctx, segments[0], name) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(values) }
    }

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
        internal const val QUERY_VERSION = "v"

        fun authority(context: Context) = "${context.packageName}.artwork"

        /** Validated Uri for a file inside the app's artwork cache (dataDir/covers). */
        fun coversUri(context: Context, name: String): Uri? =
            artworkUri(context, SEGMENT_COVERS, name)

        /** Validated Uri for a custom song cover (filesDir/song_covers). */
        fun songCoverUri(context: Context, name: String): Uri? =
            artworkUri(context, SEGMENT_SONG_COVERS, name)

        /** Validated Uri for a custom playlist cover (filesDir/playlist_covers). */
        fun playlistCoverUri(context: Context, name: String): Uri? =
            artworkUri(context, SEGMENT_PLAYLIST_COVERS, name)

        /**
         * The directory roots artwork can live under, for granting a browser client
         * prefix read access in one call per directory instead of one per image.
         */
        fun artworkRootUris(context: Context): List<Uri> = listOf(
            SEGMENT_COVERS,
            SEGMENT_SONG_COVERS,
            SEGMENT_PLAYLIST_COVERS,
        ).map { Uri.parse("content://${authority(context)}/$it") }

        /**
         * The uri carries a version token for the file it points at - see
         * [artworkVersionToken]. Only [openFile], [query] and [getType] consume these uris,
         * and all three read the path alone, so a client that drops the query still
         * resolves the same file: the worst case is a stale cache, never a broken image.
         */
        private fun artworkUri(context: Context, segment: String, name: String): Uri? {
            // The file is resolved anyway to reject missing names, so the token is free.
            val file = resolveArtworkFile(context, segment, name) ?: return null
            return Uri.parse("content://${authority(context)}/$segment/${MediaId.encode(name)}")
                .buildUpon()
                .appendQueryParameter(
                    QUERY_VERSION,
                    artworkVersionToken(file.lastModified(), file.length()),
                )
                .build()
        }

        private fun resolveArtworkFile(context: Context, segment: String, name: String): File? {
            if (!isSafeArtworkFileName(name)) return null
            val baseDir = when (segment) {
                SEGMENT_COVERS -> File(context.dataDir, "covers")
                SEGMENT_PLAYLIST_COVERS -> File(context.filesDir, "playlist_covers")
                SEGMENT_SONG_COVERS -> File(context.filesDir, "song_covers")
                else -> return null
            }
            return runCatching {
                val canonicalBase = baseDir.canonicalFile
                val file = File(canonicalBase, name).canonicalFile
                val allowedPrefix = canonicalBase.path + File.separator
                file.takeIf { it.path.startsWith(allowedPrefix) && it.isFile }
            }.getOrNull()
        }
    }
}
