package io.github.zyrouge.symphony.services.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.store.PlaylistStore
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.RoomConvertors

@Database(entities = [Playlist::class], version = 2)
@TypeConverters(RoomConvertors::class)
abstract class PersistentDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistStore

    companion object {
        // MAZIKA: add the nullable custom playlist cover column. Existing rows get
        // NULL, which behaves as "no custom cover", so old playlists keep working.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN customCoverPath TEXT")
            }
        }

        fun create(symphony: Symphony) = Room
            .databaseBuilder(
                symphony.applicationContext,
                PersistentDatabase::class.java,
                "persistent"
            )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
