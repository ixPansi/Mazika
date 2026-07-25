package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.zyrouge.symphony.services.groove.PlayedItem

@Dao
interface PlayHistoryStore {
    @Upsert
    suspend fun upsert(vararg item: PlayedItem)

    @Query("SELECT * FROM played_items ORDER BY playedAt DESC")
    suspend fun entries(): List<PlayedItem>

    /**
     * Drops everything older than the [limit] most recent plays. Expressed as a
     * timestamp cut-off rather than a NOT IN over the composite key, which SQLite
     * handles poorly.
     */
    @Query(
        "DELETE FROM played_items WHERE playedAt < (" +
                "SELECT MIN(playedAt) FROM (" +
                "SELECT playedAt FROM played_items ORDER BY playedAt DESC LIMIT :limit" +
                "))"
    )
    suspend fun prune(limit: Int): Int

    @Query("DELETE FROM played_items")
    suspend fun clear()
}
