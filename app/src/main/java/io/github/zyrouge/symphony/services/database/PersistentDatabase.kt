package io.github.zyrouge.symphony.services.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.store.PlayHistoryStore
import io.github.zyrouge.symphony.services.database.store.PlaylistStore
import io.github.zyrouge.symphony.services.database.store.SongCoverStore
import io.github.zyrouge.symphony.services.groove.PlayedItem
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.services.groove.SongCover
import io.github.zyrouge.symphony.utils.RoomConvertors

@Database(
    entities = [Playlist::class, SongCover::class, PlayedItem::class],
    version = 4,
)
@TypeConverters(RoomConvertors::class)
abstract class PersistentDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistStore
    abstract fun songCovers(): SongCoverStore
    abstract fun playHistory(): PlayHistoryStore

    companion object {
        // MAZIKA: add the nullable custom playlist cover column. Existing rows get
        // NULL, which behaves as "no custom cover", so old playlists keep working.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN customCoverPath TEXT")
            }
        }

        // MAZIKA: custom per-song covers, keyed by song path so they survive rescans.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `song_covers` (" +
                            "`path` TEXT NOT NULL, `coverFile` TEXT NOT NULL, " +
                            "PRIMARY KEY(`path`))"
                )
            }
        }

        // MAZIKA: "recently played" history. Keyed by (type, id) so one row holds an
        // item's most recent play; ids are chosen to survive a rescan (see PlayedItem).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `played_items` (" +
                            "`type` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                            "`playedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`type`, `id`))"
                )
            }
        }

        fun create(symphony: Symphony) = Room
            .databaseBuilder(
                symphony.applicationContext,
                PersistentDatabase::class.java,
                "persistent"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }
}
