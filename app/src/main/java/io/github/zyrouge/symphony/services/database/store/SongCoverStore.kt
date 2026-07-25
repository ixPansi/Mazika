package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.zyrouge.symphony.services.groove.SongCover

@Dao
interface SongCoverStore {
    @Upsert
    suspend fun upsert(vararg cover: SongCover)

    @Query("DELETE FROM song_covers WHERE path = :path")
    suspend fun delete(path: String): Int

    @Query("SELECT * FROM song_covers")
    suspend fun entries(): List<SongCover>
}
